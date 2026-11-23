namespace GreeklishKeyboard;

/// <summary>Settings window in the same style as the phone app's setup screen.</summary>
internal sealed class SettingsForm : Form
{
    private static readonly Color Bg = Color.FromArgb(0x1d, 0x24, 0x20);
    private static readonly Color Card = Color.FromArgb(0x3a, 0x46, 0x40);
    private static readonly Color Fg = Color.FromArgb(0xe8, 0xec, 0xe9);
    private static readonly Color Muted = Color.FromArgb(0xa9, 0xb5, 0xae);
    private static readonly Color Accent = Color.FromArgb(0xf2, 0xb8, 0xc6);
    private static readonly Color OnAccent = Color.FromArgb(0x4a, 0x1b, 0x28);

    private readonly AppSettings _settings;
    private readonly ListBox _words;
    private readonly Label _status;

    public void SetStatus(string text)
    {
        _status.Text = "Status: " + text;
        _status.ForeColor = text.StartsWith("Ready") ? Accent : Muted;
    }

    public SettingsForm(AppSettings settings, Engine engine)
    {
        _settings = settings;
        Text = "Greeklish keyboard";
        BackColor = Bg;
        ForeColor = Fg;
        Font = new Font("Segoe UI", 10f);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(440, 640);

        var col = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false,
            AutoScroll = true, Padding = new Padding(22, 20, 22, 20), BackColor = Bg,
        };
        Controls.Add(col);

        var head = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.LeftToRight, BackColor = Bg, Margin = new Padding(0, 0, 0, 6) };
        head.Controls.Add(new Label
        {
            Text = "αβ", TextAlign = ContentAlignment.MiddleCenter, Size = new Size(40, 40), BackColor = Accent,
            ForeColor = OnAccent, Font = new Font("Segoe UI Semibold", 12f),
        });
        head.Controls.Add(new Label
        {
            Text = "Greeklish keyboard", AutoSize = true, ForeColor = Fg, Font = new Font("Segoe UI Semibold", 15f),
            Margin = new Padding(10, 6, 0, 0),
        });
        col.Controls.Add(head);
        col.Controls.Add(Muted_("Type Greeklish in any app and press Space to get Greek. The AI runs on this PC; nothing is sent anywhere.", 390));

        _status = new Label { AutoSize = true, ForeColor = Muted, Margin = new Padding(0, 12, 0, 0), MaximumSize = new Size(390, 0) };
        col.Controls.Add(_status);

        col.Controls.Add(Section("Try it here"));
        var test = new TextBox
        {
            Width = 390, BackColor = Card, ForeColor = Fg, BorderStyle = BorderStyle.FixedSingle,
            PlaceholderText = "kalimera, ti kaneis?", Font = new Font("Segoe UI", 11f),
        };
        // This box belongs to the app itself, so it converts on its own (same AI, same "Your words"):
        // on Space or Enter, the Latin word before the cursor is replaced with the Greek.
        var typed = new List<string>();  // previous words as typed, for context
        string? lastGreek = null;        // what the previous word became
        test.KeyPress += (_, e) =>
        {
            if (e.KeyChar != ' ' && e.KeyChar != '\r') return;
            int caret = test.SelectionStart;
            string before = test.Text[..caret];
            int start = before.LastIndexOfAny(new[] { ' ', '\n' }) + 1;
            string latin = before[start..];
            if (latin.Length == 0) return;
            string greek = engine.ConvertWord(string.Join(" ", typed.TakeLast(6)), latin);
            typed.Add(latin);
            string head = test.Text[..start];
            // Like real typing: fix the previous word now that this one is known ("ti" τη -> τι).
            if (lastGreek != null && head.EndsWith(lastGreek + " ") && engine.RecheckPrevious(typed, lastGreek) is { } fixedPrev)
                head = head[..^(lastGreek.Length + 1)] + fixedPrev + " ";
            lastGreek = greek;
            if (greek == latin && head == test.Text[..start]) return;
            test.Text = head + greek + test.Text[caret..];
            test.SelectionStart = head.Length + greek.Length;
        };
        test.TextChanged += (_, _) => { if (test.TextLength == 0) { typed.Clear(); lastGreek = null; } };
        col.Controls.Add(test);

        col.Controls.Add(Section("Settings"));
        col.Controls.Add(Toggle("Greeklish on", engine.Enabled, v => engine.Enabled = v));
        col.Controls.Add(Toggle("Start with Windows", AppSettings.StartsWithWindows, v => AppSettings.StartsWithWindows = v));
        col.Controls.Add(Toggle("Show suggestion bubble", settings.ShowBubble, v => { settings.ShowBubble = v; settings.Save(); }));
        col.Controls.Add(Toggle("Learn my words", settings.LearnWords, v => { settings.LearnWords = v; settings.Save(); }));
        col.Controls.Add(Toggle("Keep English words in English", settings.KeepEnglish, v => { settings.KeepEnglish = v; settings.Save(); }));
        col.Controls.Add(Toggle("Autocorrect typos", settings.Autocorrect, v => { settings.Autocorrect = v; settings.Save(); }));

        var hkRow = new FlowLayoutPanel { AutoSize = true, BackColor = Bg, Margin = new Padding(0, 8, 0, 0) };
        hkRow.Controls.Add(new Label { Text = "Switch hotkey", AutoSize = true, ForeColor = Fg, Width = 230, Margin = new Padding(0, 6, 40, 0) });
        var hk = new TextBox
        {
            Width = 140, ReadOnly = true, BackColor = Card, ForeColor = Fg, BorderStyle = BorderStyle.FixedSingle,
            Text = TrayApp.HotkeyText(settings.Hotkey), TextAlign = HorizontalAlignment.Center,
        };
        hk.KeyDown += (_, e) =>
        {
            e.SuppressKeyPress = true;
            var key = e.KeyCode;
            if (key is Keys.ControlKey or Keys.ShiftKey or Keys.Menu) return;
            if (!e.Control && !e.Alt) { hk.Text = "Hold Ctrl or Alt + key"; return; }
            settings.Hotkey = (int)(key | e.Modifiers);
            settings.Save();
            hk.Text = TrayApp.HotkeyText(settings.Hotkey);
        };
        hkRow.Controls.Add(hk);
        col.Controls.Add(hkRow);

        col.Controls.Add(Section("Your words (kept in Latin or spelled your way)"));
        _words = new ListBox
        {
            Width = 390, Height = 130, BackColor = Card, ForeColor = Fg, BorderStyle = BorderStyle.None,
            Font = new Font("Segoe UI", 10.5f),
        };
        col.Controls.Add(_words);
        var buttons = new FlowLayoutPanel { AutoSize = true, BackColor = Bg, Margin = new Padding(0, 6, 0, 0) };
        buttons.Controls.Add(Button_("Remove selected", () =>
        {
            if (_words.SelectedItem is string row)
            {
                settings.Learned.Remove(row.Split(' ')[0]);
                settings.Save();
                FillWords();
            }
        }));
        buttons.Controls.Add(Button_("Remove all", () =>
        {
            if (MessageBox.Show("Forget all your words?", "Greeklish keyboard", MessageBoxButtons.YesNo) == DialogResult.Yes)
            {
                settings.Learned.Clear();
                settings.Save();
                FillWords();
            }
        }));
        col.Controls.Add(buttons);

        col.Controls.Add(Section("How to use"));
        col.Controls.Add(Muted_(
            "Space: type the Greek    Tab: take the alternative    Esc: keep it in Latin\n" +
            "Backspace right after a word: get back what you typed\n" +
            "It turns itself off in password boxes and when Windows is set to the Greek layout.", 390));

        col.Controls.Add(Section("Support the app"));
        col.Controls.Add(Muted_("Greeklish Keyboard is free and has no ads. If it helps you, you can buy the developer a coffee.", 390));
        var donate = new Button
        {
            Text = "♥  Donate with PayPal", Width = 390, Height = 40, FlatStyle = FlatStyle.Flat,
            BackColor = Accent, ForeColor = OnAccent, Font = new Font("Segoe UI Semibold", 10.5f),
            Margin = new Padding(0, 10, 0, 10), Cursor = Cursors.Hand,
        };
        donate.FlatAppearance.BorderSize = 0;
        donate.Click += (_, _) => TrayApp.OpenDonatePage();
        col.Controls.Add(donate);

        FillWords();
    }

    private void FillWords()
    {
        _words.Items.Clear();
        foreach (var (k, v) in _settings.Learned.OrderBy(p => p.Key))
            _words.Items.Add(k == v ? k + "   (keep as typed)" : k + "  →  " + v);
        if (_words.Items.Count == 0) _words.Items.Add("Nothing yet");
    }

    private static Label Section(string text) => new()
    {
        Text = text, AutoSize = true, ForeColor = Muted, Font = new Font("Segoe UI", 9f), Margin = new Padding(0, 18, 0, 6),
    };

    private static Label Muted_(string text, int width) => new()
    {
        Text = text, ForeColor = Muted, MaximumSize = new Size(width, 0), AutoSize = true, Margin = new Padding(0, 2, 0, 0),
    };

    private static CheckBox Toggle(string text, bool value, Action<bool> changed)
    {
        var c = new CheckBox
        {
            Text = text, Checked = value, AutoSize = true, ForeColor = Fg, FlatStyle = FlatStyle.Flat,
            Margin = new Padding(0, 8, 0, 0),
        };
        c.FlatAppearance.CheckedBackColor = Accent;
        c.CheckedChanged += (_, _) => changed(c.Checked);
        return c;
    }

    private static Button Button_(string text, Action click)
    {
        var b = new Button
        {
            Text = text, AutoSize = true, FlatStyle = FlatStyle.Flat, BackColor = Card, ForeColor = Fg,
            Margin = new Padding(0, 0, 8, 0), Padding = new Padding(8, 2, 8, 2),
        };
        b.FlatAppearance.BorderColor = Color.FromArgb(0x56, 0x64, 0x5c);
        b.Click += (_, _) => click();
        return b;
    }
}
