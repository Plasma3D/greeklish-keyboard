using System.Runtime.InteropServices;
using System.Text;

namespace GreeklishKeyboard;

/// <summary>Win32 calls: low-level keyboard/mouse hooks, SendInput, caret position, focus events.</summary>
internal static class Native
{
    public const int WH_KEYBOARD_LL = 13, WH_MOUSE_LL = 14;
    public const int WM_KEYDOWN = 0x0100, WM_KEYUP = 0x0101, WM_SYSKEYDOWN = 0x0104;
    public const int WM_LBUTTONDOWN = 0x0201, WM_RBUTTONDOWN = 0x0204, WM_MBUTTONDOWN = 0x0207;
    public const uint LLKHF_INJECTED = 0x10;
    public const uint EVENT_SYSTEM_FOREGROUND = 0x0003, EVENT_OBJECT_FOCUS = 0x8005;
    public const uint WINEVENT_OUTOFCONTEXT = 0;

    public delegate IntPtr HookProc(int nCode, IntPtr wParam, IntPtr lParam);
    public delegate void WinEventProc(IntPtr hook, uint evt, IntPtr hwnd, int idObject, int idChild, uint thread, uint time);

    [StructLayout(LayoutKind.Sequential)]
    public struct KBDLLHOOKSTRUCT { public uint vkCode, scanCode, flags, time; public IntPtr extra; }

    [StructLayout(LayoutKind.Sequential)]
    public struct MSLLHOOKSTRUCT { public POINT pt; public uint mouseData, flags, time; public IntPtr extra; }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X, Y; }

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    public struct GUITHREADINFO
    {
        public int cbSize, flags;
        public IntPtr hwndActive, hwndFocus, hwndCapture, hwndMenuOwner, hwndMoveSize, hwndCaret;
        public RECT rcCaret;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct INPUT { public uint type; public INPUTUNION u; }

    [StructLayout(LayoutKind.Explicit)]
    public struct INPUTUNION
    {
        [FieldOffset(0)] public KEYBDINPUT ki;
        [FieldOffset(0)] public MOUSEINPUT mi;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }

    public const uint INPUT_KEYBOARD = 1, KEYEVENTF_KEYUP = 0x2, KEYEVENTF_UNICODE = 0x4;
    /// <summary>Marks keys this app types itself, so its own hook ignores them.</summary>
    public static readonly IntPtr OwnInputTag = (IntPtr)0x47524B4C;

    [DllImport("user32.dll", SetLastError = true)]
    public static extern IntPtr SetWindowsHookEx(int idHook, HookProc fn, IntPtr hMod, uint threadId);

    [DllImport("user32.dll")] public static extern bool UnhookWindowsHookEx(IntPtr hhk);
    [DllImport("user32.dll")] public static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);
    [DllImport("kernel32.dll")] public static extern IntPtr GetModuleHandle(string? name);
    [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] inputs, int size);
    [DllImport("user32.dll")] public static extern short GetAsyncKeyState(int vk);
    [DllImport("user32.dll")] public static extern short GetKeyState(int vk);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")] public static extern IntPtr GetKeyboardLayout(uint thread);
    [DllImport("user32.dll")] public static extern bool GetGUIThreadInfo(uint thread, ref GUITHREADINFO info);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hwnd, ref POINT p);
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);

    [DllImport("user32.dll")]
    public static extern int ToUnicodeEx(uint vk, uint scan, byte[] state, StringBuilder buf, int bufSize, uint flags, IntPtr layout);

    [DllImport("user32.dll")]
    public static extern IntPtr SetWinEventHook(uint min, uint max, IntPtr mod, WinEventProc fn, uint pid, uint tid, uint flags);

    [DllImport("user32.dll")] public static extern bool UnhookWinEvent(IntPtr hook);

    /// <summary>A key event the user made while we were busy; replayed afterwards, in order.</summary>
    public readonly record struct HeldKey(uint Vk, uint Scan, uint LlFlags);

    [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint mapType);

    /// <summary>
    /// Types the replacement one key at a time with a short pause after each, because many apps
    /// (Chrome, Electron, Messenger…) mix up the order when a whole burst arrives at once.
    /// Runs on a background thread; takes about 2-3 ms per key.
    /// </summary>
    public static void SendSlowly(int backspaces, string text, ushort pressAfterVk)
    {
        var list = Build(backspaces, text, pressAfterVk, null);
        var arr = new INPUT[1];
        for (int i = 0; i < list.Count; i++)
        {
            arr[0] = list[i];
            SendInput(1, arr, Marshal.SizeOf<INPUT>());
            // pause after each key release, so the app finishes one key before the next
            if ((list[i].u.ki.dwFlags & KEYEVENTF_KEYUP) != 0) Thread.Sleep(3);
        }
    }

    /// <summary>Passes on a key the user pressed while we were typing (tagged as ours, so the hook lets it through).</summary>
    public static void InjectOwn(HeldKey h)
    {
        uint flags = ((h.LlFlags & 0x80) != 0 ? KEYEVENTF_KEYUP : 0) | ((h.LlFlags & 0x01) != 0 ? 0x1u /* EXTENDED */ : 0);
        var arr = new[]
        {
            new INPUT
            {
                type = INPUT_KEYBOARD,
                u = new INPUTUNION { ki = new KEYBDINPUT { wVk = (ushort)h.Vk, wScan = (ushort)h.Scan, dwFlags = flags, dwExtraInfo = OwnInputTag } }
            }
        };
        SendInput(1, arr, Marshal.SizeOf<INPUT>());
        Thread.Sleep(2);
    }

    public static void Send(int backspaces, string text, ushort pressAfterVk = 0, IReadOnlyList<HeldKey>? replay = null)
    {
        var list = Build(backspaces, text, pressAfterVk, replay);
        if (list.Count > 0) SendInput((uint)list.Count, list.ToArray(), Marshal.SizeOf<INPUT>());
    }

    private static List<INPUT> Build(int backspaces, string text, ushort pressAfterVk, IReadOnlyList<HeldKey>? replay)
    {
        var list = new List<INPUT>();
        for (int i = 0; i < backspaces; i++)
        {
            list.Add(Key(0x08, 0));
            list.Add(Key(0x08, KEYEVENTF_KEYUP));
        }
        foreach (char ch in text)
        {
            if (ch == ' ')
            {
                // A real Space key press: some apps drop a space sent as a Unicode character.
                list.Add(Key(0x20, 0));
                list.Add(Key(0x20, KEYEVENTF_KEYUP));
                continue;
            }
            list.Add(Unicode(ch, 0));
            list.Add(Unicode(ch, KEYEVENTF_KEYUP));
        }
        if (pressAfterVk != 0)
        {
            list.Add(Key(pressAfterVk, 0));
            list.Add(Key(pressAfterVk, KEYEVENTF_KEYUP));
        }
        if (replay != null)
        {
            foreach (var h in replay)
            {
                uint flags = ((h.LlFlags & 0x80) != 0 ? KEYEVENTF_KEYUP : 0) | ((h.LlFlags & 0x01) != 0 ? 0x1u /* EXTENDED */ : 0);
                list.Add(new INPUT
                {
                    type = INPUT_KEYBOARD,
                    u = new INPUTUNION { ki = new KEYBDINPUT { wVk = (ushort)h.Vk, wScan = (ushort)h.Scan, dwFlags = flags } }
                });
            }
        }
        return list;
    }

    // Real scan codes too: some apps (Chrome, Electron) look at them, not only at the key code.
    private static INPUT Key(ushort vk, uint flags) => new()
    {
        type = INPUT_KEYBOARD,
        u = new INPUTUNION { ki = new KEYBDINPUT { wVk = vk, wScan = (ushort)MapVirtualKey(vk, 0), dwFlags = flags, dwExtraInfo = OwnInputTag } }
    };

    private static INPUT Unicode(char ch, uint flags) => new()
    {
        type = INPUT_KEYBOARD,
        u = new INPUTUNION { ki = new KEYBDINPUT { wScan = ch, dwFlags = KEYEVENTF_UNICODE | flags, dwExtraInfo = OwnInputTag } }
    };

    public static bool IsDown(int vk) => (GetAsyncKeyState(vk) & 0x8000) != 0;

    /// <summary>Text cursor of the focused app in screen coordinates, if the app exposes it.</summary>
    public static Rectangle? CaretRect()
    {
        var fg = GetForegroundWindow();
        uint tid = GetWindowThreadProcessId(fg, out _);
        var info = new GUITHREADINFO { cbSize = Marshal.SizeOf<GUITHREADINFO>() };
        if (!GetGUIThreadInfo(tid, ref info) || info.hwndCaret == IntPtr.Zero) return null;
        var p = new POINT { X = info.rcCaret.Left, Y = info.rcCaret.Top };
        ClientToScreen(info.hwndCaret, ref p);
        int h = Math.Max(16, info.rcCaret.Bottom - info.rcCaret.Top);
        return new Rectangle(p.X, p.Y, 2, h);
    }
}
