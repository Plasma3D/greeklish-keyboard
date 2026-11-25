using System.Text;

namespace GreeklishKeyboard;

/// <summary>
/// Watches what you type in any app (low-level keyboard hook). The Latin word since the last
/// space is kept in <see cref="_word"/>; on Space/Enter it is replaced with the Greek by sending
/// backspaces + the Greek text. Backspace right after a replacement brings back what you typed.
/// Tab picks the alternative, Esc keeps the Latin. Nothing ever leaves the PC.
/// </summary>
public sealed class Engine : IDisposable
{
    public event Action? StateChanged;

    private readonly AppSettings _settings;
    private readonly Bubble _bubble;
    private readonly Control _ui;
    private Converter? _conv;
    private Autocorrect? _autocorrect;

    /// <summary>AI result with autocorrect applied: a non-word becomes the closest real word; the AI's own version goes to Tab.</summary>
    private Converter.Result Fix(Converter.Result r)
    {
        if (!_settings.Autocorrect || _autocorrect == null) return r;
        var f = _autocorrect.Correct(r.Best, r.Input);
        return f == null ? r : new Converter.Result(r.Input, f, r.Best);
    }

    private readonly Native.HookProc _kbProc, _mouseProc;
    private readonly Native.WinEventProc _focusProc;
    private IntPtr _kbHook, _mouseHook, _fgHook, _focusHook;

    // Typing state
    private readonly StringBuilder _word = new();
    private bool _keepLatin;
    private readonly List<string> _context = new();  // previous words, as typed
    private Converter.Result? _cache;
    private string? _middle, _right;                  // what Space / Tab would type
    private string? _shownFor;                        // the typed word those suggestions belong to
    private int _request;

    // Undo of the last replacement
    private string? _undoLatin, _undoTyped;

    private volatile bool _passwordField;

    // English detection: scores of the words so far in this sentence
    private readonly EnglishDetector _english = new();
    private readonly List<double> _sentence = new();
    private double? _prevScore;

    private bool LooksEnglish(string latin) =>
        _settings.KeepEnglish && EnglishDetector.IsEnglish(_english.Score(latin), _sentence);
    private DateTime _pausedUntil = DateTime.MinValue;

    public Engine(AppSettings settings, Bubble bubble, Control ui)
    {
        _settings = settings;
        _bubble = bubble;
        _ui = ui;
        new Thread(WorkerLoop) { IsBackground = true, Name = "Greeklish typing" }.Start();
        _kbProc = KeyboardProc;
        _mouseProc = MouseProc;
        _focusProc = FocusProc;
        var mod = Native.GetModuleHandle(null);
        _kbHook = Native.SetWindowsHookEx(Native.WH_KEYBOARD_LL, _kbProc, mod, 0);
        _mouseHook = Native.SetWindowsHookEx(Native.WH_MOUSE_LL, _mouseProc, mod, 0);
        _fgHook = Native.SetWinEventHook(Native.EVENT_SYSTEM_FOREGROUND, Native.EVENT_SYSTEM_FOREGROUND, IntPtr.Zero,
            _focusProc, 0, 0, Native.WINEVENT_OUTOFCONTEXT);
        _focusHook = Native.SetWinEventHook(Native.EVENT_OBJECT_FOCUS, Native.EVENT_OBJECT_FOCUS, IntPtr.Zero,
            _focusProc, 0, 0, Native.WINEVENT_OUTOFCONTEXT);
        Log.Write($"start v{Application.ProductVersion}: keyboard hook {(_kbHook != IntPtr.Zero ? "ok" : "FAILED " + System.Runtime.InteropServices.Marshal.GetLastWin32Error())}, mouse hook {(_mouseHook != IntPtr.Zero ? "ok" : "FAILED")}");

        Task.Run(() =>
        {
            try
            {
                var c = new Converter();
                var r = c.ConvertLastWord("", "kalimera");  // warm up so the first real word is instant
                _autocorrect = new Autocorrect();
                _conv = c;
                Log.Write("model loaded, test: kalimera -> " + r.Best);
            }
            catch (Exception e)
            {
                Log.Write("MODEL FAILED TO LOAD: " + e);
                LoadError = e.Message;
            }
            _ui.BeginInvoke(() => StateChanged?.Invoke());
        });
    }

    public bool ModelReady => _conv != null;
    public string? LoadError { get; private set; }
    public bool Paused => DateTime.Now < _pausedUntil;

    public bool Enabled
    {
        get => _settings.Enabled;
        set
        {
            _settings.Enabled = value;
            _pausedUntil = DateTime.MinValue;
            _settings.Save();
            Reset();
            StateChanged?.Invoke();
        }
    }

    public void PauseFor(TimeSpan t)
    {
        _pausedUntil = DateTime.Now + t;
        Reset();
        StateChanged?.Invoke();
        var timer = new System.Windows.Forms.Timer { Interval = (int)t.TotalMilliseconds + 500 };
        timer.Tick += (_, _) => { timer.Dispose(); StateChanged?.Invoke(); };
        timer.Start();
    }

    private bool Active()
    {
        if (!_settings.Enabled || Paused || _conv == null || _passwordField) return false;
        // If Windows is already set to the Greek keyboard layout, the user types Greek directly.
        var fg = Native.GetForegroundWindow();
        uint tid = Native.GetWindowThreadProcessId(fg, out uint pid);
        // Our own windows (the "Try it here" box) convert by themselves: typing into our own
        // thread from the hook gets keys out of order.
        if (pid == (uint)Environment.ProcessId) return false;
        long layout = Native.GetKeyboardLayout(tid).ToInt64();
        return (layout & 0xFF) != 0x08;
    }

    /// <summary>The previous word re-converted now that the next one is known, or null (small, safe fixes only).</summary>
    public string? RecheckPrevious(IReadOnlyList<string> typedWords, string previousOutput)
    {
        if (_conv == null || typedWords.Count < 2 || _settings.LearnedFor(typedWords[^2]) != null) return null;
        string prevLatin = typedWords[^2];
        if (_settings.KeepEnglish && previousOutput != prevLatin
            && EnglishDetector.PreviousWasEnglish(_english.Score(typedWords[^1]), typedWords.SkipLast(1).Select(_english.Score).ToList()))
            return prevLatin;  // "do" δω -> do once "you" follows
        try
        {
            var outp = _conv.ConvertWords(string.Join(" ", typedWords.TakeLast(7)));
            string fixedWord = outp[^2];
            if (_settings.Autocorrect && _autocorrect?.Correct(fixedWord, prevLatin) is { } corrected) fixedWord = corrected;
            if (fixedWord == previousOutput || fixedWord.Length != previousOutput.Length) return null;
            int diff = 0;
            for (int i = 0; i < fixedWord.Length; i++) if (fixedWord[i] != previousOutput[i]) diff++;
            return diff <= 2 ? fixedWord : null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>Greek for one word (with previous words as context), honouring "Your words". Used by the Try-it box.</summary>
    public string ConvertWord(string context, string latin)
    {
        if (!_settings.Enabled || Paused || !latin.Any(char.IsLetter)) return latin;
        var taught = _settings.LearnedFor(latin);
        if (taught != null) return taught;
        var previous = context.Split(' ', StringSplitOptions.RemoveEmptyEntries).Select(_english.Score).ToList();
        if (_settings.KeepEnglish && EnglishDetector.IsEnglish(_english.Score(latin), previous)) return latin;
        try
        {
            return _conv == null ? latin : Fix(_conv.ConvertLastWord(context, latin)).Best;
        }
        catch
        {
            return latin;
        }
    }

    // ------------------------------------------------------------------ keyboard

    private IntPtr KeyboardProc(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0)
        {
            var k = System.Runtime.InteropServices.Marshal.PtrToStructure<Native.KBDLLHOOKSTRUCT>(lParam);
            int msg = wParam.ToInt32();
            bool ours = k.extra == Native.OwnInputTag;  // keys we typed ourselves
            if (!ours)
            {
                lock (_lk)
                {
                    if (_busy)
                    {
                        // We're typing a replacement: hold this key; the worker handles it right after, in order.
                        _held.Enqueue(new Native.HeldKey(k.vkCode, k.scanCode, k.flags));
                        return (IntPtr)1;
                    }
                }
            }
            if (!ours && (msg == Native.WM_KEYDOWN || msg == Native.WM_SYSKEYDOWN))
            {
                try
                {
                    var mods = Mods.Live();
                    _outgoing = null;
                    bool handled = HandleKeyDown(k.vkCode, k.scanCode, mods);
                    if (handled && _outgoing is { } o)
                    {
                        lock (_lk) _busy = true;
                        _work.Add(() => RunJob(o, mods));
                    }
                    if (handled) return (IntPtr)1;  // we handled it: the app never sees this key
                }
                catch (Exception e)
                {
                    Log.Write("key handling error: " + e.GetType().Name + ": " + e.Message);
                    try { Reset(); } catch { }
                }
            }
        }
        return Native.CallNextHookEx(_kbHook, nCode, wParam, lParam);
    }

    /// <summary>Modifier keys held at the moment of a key press.</summary>
    private struct Mods
    {
        public bool Ctrl, Alt, Shift, Win;

        public static Mods Live() => new()
        {
            Ctrl = Native.IsDown(0x11), Alt = Native.IsDown(0x12), Shift = Native.IsDown(0x10),
            Win = Native.IsDown(0x5B) || Native.IsDown(0x5C),
        };

        /// <summary>Follows held modifier keys while replaying.</summary>
        public void Apply(uint vk, bool up)
        {
            bool down = !up;
            if (vk is 0x10 or 0xA0 or 0xA1) Shift = down;
            else if (vk is 0x11 or 0xA2 or 0xA3) Ctrl = down;
            else if (vk is 0x12 or 0xA4 or 0xA5) Alt = down;
            else if (vk is 0x5B or 0x5C) Win = down;
        }
    }

    private bool HandleKeyDown(uint vkCode, uint scanCode, Mods m)
    {
        int vk = (int)vkCode;
        bool ctrl = m.Ctrl, alt = m.Alt, shift = m.Shift, win = m.Win;

        // Switch hotkey (default Ctrl+Shift+G) works everywhere.
        var hk = (Keys)_settings.Hotkey;
        if (vk == (int)(hk & Keys.KeyCode) && ctrl == hk.HasFlag(Keys.Control) && shift == hk.HasFlag(Keys.Shift)
            && alt == hk.HasFlag(Keys.Alt))
        {
            _ui.BeginInvoke(() => Enabled = !Enabled);
            return true;
        }

        if (vk is 0x10 or 0x11 or 0x12 or 0x14 or >= 0xA0 and <= 0xA5) return false;  // modifier keys alone
        if (!Active())
        {
            Diag($"inactive: enabled={_settings.Enabled} paused={Paused} model={_conv != null} password={_passwordField}");
            if (_word.Length > 0 || _context.Count > 0) Reset();
            return false;
        }
        if (ctrl || alt || win)
        {
            Reset();  // shortcuts (Ctrl+A, Ctrl+V…) change the text in ways we can't follow
            return false;
        }

        switch (vk)
        {
            case 0x08: return Backspace();
            case 0x20: return _word.Length > 0 && Commit(null, " ", 0, learnAs: null);
            case 0x0D:
                if (_word.Length > 0) return Commit(null, "", 0x0D, learnAs: null);
                _undoLatin = null;
                _context.Clear();
                _sentence.Clear();
                _prevScore = null;
                return false;
            case 0x09:  // Tab: take the right-hand suggestion
                if (_word.Length > 0 && _right != null && _shownFor == _word.ToString()) return Commit(_right, " ", 0, learnAs: _right);
                Reset();
                return false;
            case 0x1B:  // Esc: keep what I typed
                if (_word.Length > 0 && !_keepLatin && _shownFor == _word.ToString() && _middle != _word.ToString())
                {
                    _keepLatin = true;
                    ShowSuggestions();
                    return true;
                }
                Reset();
                return false;
        }
        if (vk is >= 0x21 and <= 0x2E or >= 0x70 and <= 0x87)
        {
            Reset();  // arrows, Home/End, Page, Delete, F-keys: the cursor moved
            return false;
        }

        string ch = CharFor(vkCode, scanCode, shift);
        if (ch.Length == 0 || char.IsWhiteSpace(ch[0]))
        {
            Reset();
            return false;
        }
        _undoLatin = null;
        _word.Append(ch);
        Diag($"letter: word now {_word.Length} chars");
        Update();
        return false;  // the Latin letter goes through; it gets replaced on Space
    }

    private bool Backspace()
    {
        if (_word.Length > 0)
        {
            _word.Length--;
            if (_word.Length == 0)
            {
                _keepLatin = false;
                HideBubble();
            }
            else
            {
                Update();
            }
            return false;
        }
        if (_undoLatin != null && _undoTyped != null)
        {
            // Bring back exactly what was typed, and keep it Latin this time.
            QueueSend(_undoTyped.Length, _undoLatin, 0);
            _word.Append(_undoLatin);
            _keepLatin = true;
            if (_context.Count > 0) _context.RemoveAt(_context.Count - 1);
            _undoLatin = _undoTyped = null;
            _prevAutoLatin = _prevAutoTyped = null;
            Update();
            return true;
        }
        _context.Clear();
        _prevAutoLatin = _prevAutoTyped = null;
        return false;
    }

    // Typing from inside the keyboard hook makes Windows drop or reorder keys, so replacements are
    // typed by one worker thread, key by key. While it works, every key the user presses is held
    // and then handled in order (a held Space converts its own word, letters are passed on).
    private readonly object _lk = new();
    private bool _busy;
    private readonly Queue<Native.HeldKey> _held = new();
    private (int backspaces, string text, ushort pressAfter)? _outgoing;
    private readonly System.Collections.Concurrent.BlockingCollection<Action> _work = new();

    /// <summary>Called by the typing logic: what to type once this key press is handled.</summary>
    private void QueueSend(int backspaces, string text, ushort pressAfter) => _outgoing = (backspaces, text, pressAfter);

    private void WorkerLoop()
    {
        foreach (var job in _work.GetConsumingEnumerable())
        {
            try { job(); }
            catch (Exception e) { Log.Write("worker error: " + e.Message); }
        }
    }

    private void RunJob((int backspaces, string text, ushort pressAfter) o, Mods mods)
    {
        try
        {
            Native.SendSlowly(o.backspaces, o.text, o.pressAfter);
            while (true)
            {
                Native.HeldKey e;
                lock (_lk)
                {
                    if (_held.Count == 0)
                    {
                        _busy = false;  // keys go straight through again
                        return;
                    }
                    e = _held.Dequeue();
                }
                bool up = (e.LlFlags & 0x80) != 0;
                mods.Apply(e.Vk, up);
                if (up)
                {
                    Native.InjectOwn(e);
                    continue;
                }
                var snapshot = mods;
                var (handled, outgoing) = ((bool, (int, string, ushort)?))_ui.Invoke(() =>
                {
                    _outgoing = null;
                    bool h = HandleKeyDown(e.Vk, e.Scan, snapshot);
                    return (h, _outgoing);
                });
                if (!handled) Native.InjectOwn(e);  // an ordinary key: give it to the app now
                else if (outgoing is { } next) Native.SendSlowly(next.Item1, next.Item2, next.Item3);
            }
        }
        catch (Exception ex)
        {
            Log.Write("send failed: " + ex.Message);
            lock (_lk)
            {
                _held.Clear();
                _busy = false;
            }
        }
    }

    private int _diagCount;

    /// <summary>The first 60 decisions after start go to the log (never the typed text), to diagnose problems.</summary>
    private void Diag(string what)
    {
        if (_diagCount++ < 60) Log.Write(what);
    }

    /// <summary>Replaces the typed word with {choice} + {ending}. Returns true if the key was handled.</summary>
    private bool Commit(string? choice, string ending, ushort pressAfter, string? learnAs)
    {
        string latin = _word.ToString();
        string? taught = _settings.LearnedFor(latin);
        bool english = choice == null && !_keepLatin && taught == null && LooksEnglish(latin);
        string output = choice ?? (_keepLatin || english ? latin : taught ?? ConvertNow(latin));
        if (learnAs != null) _settings.Learn(latin, learnAs);
        else if (_keepLatin) _settings.Learn(latin, latin);
        bool auto = choice == null && !_keepLatin && taught == null && !english;
        double score = _english.Score(latin);

        // Now that this word is known, re-check the previous one: "ti" τη -> τι before "kaneis",
        // or "do" δω -> do once "you" shows the sentence is English.
        string? fixedPrev = null;
        if (english && ending == " " && _prevAutoLatin != null && _prevScore != null
            && EnglishDetector.PreviousWasEnglish(score, _sentence) && _context.Count > 0 && _context[^1] == _prevAutoLatin
            && _prevAutoTyped != _prevAutoLatin + " ")
            fixedPrev = _prevAutoLatin;
        else if (auto && ending == " ")
            fixedPrev = FixPrevious(latin);
        _sentence.Add(score);
        if (EnglishDetector.EndsSentence(latin)) { _sentence.Clear(); _prevScore = null; }
        else _prevScore = score;

        _context.Add(latin);
        if (_context.Count > 6) _context.RemoveAt(0);
        _word.Clear();
        _keepLatin = false;
        _cache = null;
        _request++;
        HideBubble();

        string? prevTyped = _prevAutoTyped;
        _prevAutoLatin = auto && ending == " " ? latin : null;
        _prevAutoTyped = output + " ";

        Diag($"commit: typed {latin.Length} chars -> output {output.Length} chars, changed={output != latin}, fixPrev={fixedPrev != null}");
        if (fixedPrev == null && output == latin && ending == " " && pressAfter == 0)
        {
            _undoLatin = null;
            return false;  // nothing to change: let the real Space through
        }
        if (fixedPrev != null)
        {
            QueueSend(latin.Length + prevTyped!.Length, fixedPrev + " " + output + ending, pressAfter);
        }
        else
        {
            QueueSend(latin.Length, output + ending, pressAfter);
        }
        bool changed = output != latin;
        _undoLatin = changed && ending == " " ? latin : null;
        _undoTyped = output + ending;
        return true;
    }

    private string? _prevAutoLatin, _prevAutoTyped;

    /// <summary>The previous word corrected with this word as context, or null. Only small, safe fixes.</summary>
    private string? FixPrevious(string latin)
    {
        if (_conv == null || _prevAutoLatin == null || _prevAutoTyped == null || _context.Count == 0
            || _context[^1] != _prevAutoLatin) return null;
        try
        {
            var words = _conv.ConvertWords(string.Join(" ", _context) + " " + latin);
            if (words.Length < 2) return null;
            string fixedWord = words[^2], old = _prevAutoTyped[..^1];
            if (_settings.Autocorrect && _autocorrect?.Correct(fixedWord, _prevAutoLatin) is { } corrected) fixedWord = corrected;
            if (fixedWord == old || fixedWord.Length != old.Length) return null;
            int diff = 0;
            for (int i = 0; i < old.Length; i++) if (old[i] != fixedWord[i]) diff++;
            return diff <= 2 ? fixedWord : null;
        }
        catch
        {
            return null;
        }
    }

    private string ConvertNow(string latin)
    {
        var c = _cache;
        if (c != null && c.Input == latin) return c.Best;
        try
        {
            return _conv == null ? latin : Fix(_conv.ConvertLastWord(string.Join(" ", _context), latin)).Best;
        }
        catch
        {
            return latin;
        }
    }

    /// <summary>Character for a key press in the current keyboard layout (respects Shift and Caps Lock).</summary>
    private static string CharFor(uint vkCode, uint scanCode, bool shift)
    {
        var state = new byte[256];
        if (shift) state[0x10] = 0x80;
        if ((Native.GetKeyState(0x14) & 1) != 0) state[0x14] = 1;
        var fg = Native.GetForegroundWindow();
        var layout = Native.GetKeyboardLayout(Native.GetWindowThreadProcessId(fg, out _));
        var sb = new StringBuilder(8);
        int n = Native.ToUnicodeEx(vkCode, scanCode, state, sb, sb.Capacity, 0x4, layout);
        return n > 0 ? sb.ToString(0, n) : "";
    }

    // ------------------------------------------------------------------ suggestions

    private void Update()
    {
        string latin = _word.ToString();
        if (!latin.Any(char.IsLetter))
        {
            _middle = latin;
            _right = null;
            HideBubble();
            return;
        }
        int id = ++_request;
        string ctx = string.Join(" ", _context);
        var conv = _conv;
        Task.Run(() =>
        {
            try
            {
                var r = Fix(conv!.ConvertLastWord(ctx, latin));
                _ui.BeginInvoke(() =>
                {
                    if (id != _request || _word.ToString() != latin) return;
                    _cache = r;
                    ShowSuggestions();
                });
            }
            catch { }
        });
    }

    private void ShowSuggestions()
    {
        string latin = _word.ToString();
        string? taught = _settings.LearnedFor(latin);
        string? best = _cache?.Input == latin ? _cache.Best : null;
        string? left;
        bool learned = false;
        if (_keepLatin)
        {
            _middle = latin;
            _right = best != null && best != latin ? best : null;
            left = null;
        }
        else if (taught != null)
        {
            _middle = taught;
            learned = true;
            _right = best != null && best != taught ? best : null;
            left = taught == latin ? null : latin;
        }
        else if (LooksEnglish(latin))
        {
            _middle = latin;  // English: stays as typed; Tab gives the Greek
            _right = best != null && best != latin ? best : null;
            left = null;
        }
        else
        {
            _middle = best ?? latin;
            _right = _cache?.Alternative;
            left = _middle == latin ? null : latin;
        }
        _shownFor = latin;
        if (!_settings.ShowBubble || _middle == latin && _right == null) { _bubble.Hide(); return; }
        _bubble.Show(left, _middle, _right, learned, CaretAnchor());
    }

    private Point? _lastClick;

    private Rectangle CaretAnchor()
    {
        var caret = Native.CaretRect();
        if (caret != null && caret.Value.X > 0) return caret.Value;
        if (_lastClick != null) return new Rectangle(_lastClick.Value, new Size(2, 18));
        Native.GetCursorPos(out var p);
        return new Rectangle(p.X, p.Y, 2, 18);
    }

    private void HideBubble() => _ui.BeginInvoke(() => _bubble.Hide());

    public void Reset()
    {
        if (_word.Length > 0) Diag($"reset with {_word.Length} chars pending");
        _word.Clear();
        _keepLatin = false;
        _context.Clear();
        _undoLatin = _undoTyped = null;
        _cache = null;
        _middle = _right = _shownFor = null;
        _prevAutoLatin = _prevAutoTyped = null;
        _sentence.Clear();
        _prevScore = null;
        _request++;
        HideBubble();
    }

    // ------------------------------------------------------------------ mouse / focus

    private IntPtr MouseProc(int nCode, IntPtr wParam, IntPtr lParam)
    {
        int msg = wParam.ToInt32();
        if (nCode >= 0 && msg is Native.WM_LBUTTONDOWN or Native.WM_RBUTTONDOWN or Native.WM_MBUTTONDOWN)
        {
            var m = System.Runtime.InteropServices.Marshal.PtrToStructure<Native.MSLLHOOKSTRUCT>(lParam);
            _lastClick = new Point(m.pt.X, m.pt.Y + 12);
            if (_word.Length > 0 || _context.Count > 0) Reset();
        }
        return Native.CallNextHookEx(_mouseHook, nCode, wParam, lParam);
    }

    private IntPtr _lastFocusWindow;

    private void FocusProc(IntPtr hook, uint evt, IntPtr hwnd, int idObject, int idChild, uint thread, uint time)
    {
        // Our own suggestion bubble also raises focus events when it appears: ignore those.
        Native.GetWindowThreadProcessId(hwnd, out uint pid);
        if (pid == (uint)Environment.ProcessId) return;
        // Browsers and Electron apps send focus events all the time inside one window;
        // only switching to another app resets the word (clicks are handled by the mouse hook).
        var fg = Native.GetForegroundWindow();
        bool newWindow = fg != _lastFocusWindow;
        _lastFocusWindow = fg;
        if (newWindow && (_word.Length > 0 || _context.Count > 0))
        {
            Diag("focus moved to another window: reset");
            Reset();
        }
        // Password boxes: stay out of the way. UI Automation is slow, so ask off the UI thread.
        Task.Run(() =>
        {
            try
            {
                _passwordField = Accessibility.FocusedIsPassword();
            }
            catch
            {
                _passwordField = false;
            }
        });
    }

    public void Dispose()
    {
        if (_kbHook != IntPtr.Zero) Native.UnhookWindowsHookEx(_kbHook);
        if (_mouseHook != IntPtr.Zero) Native.UnhookWindowsHookEx(_mouseHook);
        if (_fgHook != IntPtr.Zero) Native.UnhookWinEvent(_fgHook);
        if (_focusHook != IntPtr.Zero) Native.UnhookWinEvent(_focusHook);
        _kbHook = _mouseHook = _fgHook = _focusHook = IntPtr.Zero;
        _work.CompleteAdding();
        _conv?.Dispose();
    }
}
