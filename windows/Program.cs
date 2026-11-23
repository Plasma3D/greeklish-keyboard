using System.Drawing.Drawing2D;
using System.Drawing.Text;

namespace GreeklishKeyboard;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        if (args.Contains("--selftest"))
        {
            // Loads the model exactly like the tray app does and reports the result.
            try
            {
                using var c = new Converter();
                var r = c.ConvertLastWord("", "kalimera");
                File.WriteAllText(args[^1], "OK " + r.Best);
            }
            catch (Exception e)
            {
                File.WriteAllText(args[^1], "FAIL " + e);
            }
            return;
        }
        using var single = new Mutex(true, "GreeklishKeyboard.SingleInstance", out bool first);
        using var showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, "GreeklishKeyboard.ShowSettings");
        if (!first)
        {
            showSignal.Set();  // already running in the tray: ask it to open its window
            return;
        }
        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApp(showSignal, showWindow: !args.Contains("--background")));
    }
}

/// <summary>Tray icon (ΕΛ / EN), its menu, and the settings window.</summary>
internal sealed class TrayApp : ApplicationContext
{
    private readonly AppSettings _settings = AppSettings.Load();
    private readonly NotifyIcon _tray;
    private readonly Engine _engine;
    private readonly Control _ui = new();
    private readonly ToolStripMenuItem _toggle, _pause;
    private SettingsForm? _form;

    public TrayApp(EventWaitHandle showSignal, bool showWindow)
    {
        _ = _ui.Handle;  // force the window handle now: background work is marshalled back through it
        var bubble = new Bubble();
        _engine = new Engine(_settings, bubble, _ui);

        var menu = new ContextMenuStrip();
        var title = new ToolStripMenuItem("Greeklish keyboard") { Enabled = false };
        _toggle = new ToolStripMenuItem("Greeklish on", null, (_, _) => _engine.Enabled = !_engine.Enabled);
        _pause = new ToolStripMenuItem("Pause for 10 minutes", null, (_, _) => _engine.PauseFor(TimeSpan.FromMinutes(10)));
        menu.Items.AddRange(new ToolStripItem[]
        {
            title, _toggle, _pause,
            new ToolStripMenuItem("Settings…", null, (_, _) => ShowSettings()),
            new ToolStripMenuItem("Donate…", null, (_, _) => OpenDonatePage()),
            new ToolStripSeparator(),
            new ToolStripMenuItem("Quit", null, (_, _) => Quit()),
        });

        _tray = new NotifyIcon { ContextMenuStrip = menu, Visible = true };
        _tray.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) ShowSettings(); };
        _engine.StateChanged += Refresh;
        Refresh();

        // Opening the .exe again (while it runs) shows the window instead of silently doing nothing.
        ThreadPool.RegisterWaitForSingleObject(showSignal, (_, _) => _ui.BeginInvoke(ShowSettings), null, -1, false);
        if (showWindow) _ui.BeginInvoke(ShowSettings);

        if (!File.Exists(FirstRunMarker))
        {
            _tray.ShowBalloonTip(6000, "Greeklish keyboard is running",
                "Type Greeklish anywhere and press Space to get Greek. " + HotkeyText(_settings.Hotkey) + " switches it on/off.",
                ToolTipIcon.Info);
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(FirstRunMarker)!);
                File.WriteAllText(FirstRunMarker, "1");
            }
            catch { }
        }
    }

    private static string FirstRunMarker =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GreeklishKeyboard", ".welcomed");

    private void Refresh()
    {
        bool on = _engine.Enabled && !_engine.Paused;
        string state = !_engine.ModelReady ? "loading…" : _engine.Paused ? "paused" : on ? "ΕΛ" : "EN";
        _tray.Text = "Greeklish keyboard: " + state;
        _form?.SetStatus(StatusText());
        var old = _tray.Icon;
        _tray.Icon = MakeIcon(on && _engine.ModelReady ? "ΕΛ" : _engine.Paused ? "II" : "EN", on);
        old?.Dispose();
        _toggle.Checked = _engine.Enabled;
        _toggle.ShortcutKeyDisplayString = HotkeyText(_settings.Hotkey);
        _pause.Enabled = on;
    }

    /// <summary>Tray icon: a rounded tile with ΕΛ (pink, on) or EN (grey, off).</summary>
    private static Icon MakeIcon(string text, bool on)
    {
        int size = SystemInformation.SmallIconSize.Width * 2;
        using var bmp = new Bitmap(size, size);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            using var path = new GraphicsPath();
            int r = size / 4, d = r * 2;
            var rc = new Rectangle(0, size / 8, size - 1, size * 3 / 4);
            path.AddArc(rc.X, rc.Y, d, d, 180, 90);
            path.AddArc(rc.Right - d, rc.Y, d, d, 270, 90);
            path.AddArc(rc.Right - d, rc.Bottom - d, d, d, 0, 90);
            path.AddArc(rc.X, rc.Bottom - d, d, d, 90, 90);
            path.CloseFigure();
            using var bg = new SolidBrush(on ? Color.FromArgb(0xf2, 0xb8, 0xc6) : Color.FromArgb(0x8a, 0x8a, 0x8a));
            g.FillPath(bg, path);
            using var font = new Font("Segoe UI Semibold", size * 0.36f, GraphicsUnit.Pixel);
            using var fg = new SolidBrush(on ? Color.FromArgb(0x4a, 0x1b, 0x28) : Color.White);
            var fmt = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString(text, font, fg, rc, fmt);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }

    /// <summary>PayPal donation page for the developer's account, in euros (opens in the default browser).</summary>
    public const string DonateUrl =
        "https://www.paypal.com/donate/?business=gnnsnsts%40gmail.com&currency_code=EUR&item_name=Greeklish%20Keyboard&locale.x=en_US";

    public static void OpenDonatePage()
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(DonateUrl) { UseShellExecute = true });
        }
        catch
        {
            MessageBox.Show("Couldn't open the browser. The donation page is:\n" + DonateUrl, "Greeklish keyboard");
        }
    }

    public static string HotkeyText(int hotkey)
    {
        var k = (Keys)hotkey;
        var parts = new List<string>();
        if (k.HasFlag(Keys.Control)) parts.Add("Ctrl");
        if (k.HasFlag(Keys.Shift)) parts.Add("Shift");
        if (k.HasFlag(Keys.Alt)) parts.Add("Alt");
        parts.Add((k & Keys.KeyCode).ToString());
        return string.Join("+", parts);
    }

    private string StatusText() =>
        _engine.LoadError != null ? "Error: " + _engine.LoadError
        : !_engine.ModelReady ? "Loading the AI…"
        : _engine.Paused ? "Paused"
        : _engine.Enabled ? "Ready: type Greeklish in any app and press Space"
        : "Off (English). Turn on with " + HotkeyText(_settings.Hotkey);

    private void ShowSettings()
    {
        if (_form == null || _form.IsDisposed)
        {
            _form = new SettingsForm(_settings, _engine);
            _form.FormClosed += (_, _) => Refresh();
        }
        _form.SetStatus(StatusText());
        _form.Show();
        _form.Activate();
    }

    private void Quit()
    {
        _tray.Visible = false;
        _engine.Dispose();
        ExitThread();
    }
}
