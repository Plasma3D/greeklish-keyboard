using System.Drawing.Drawing2D;

namespace GreeklishKeyboard;

/// <summary>
/// Small suggestion bubble next to the text cursor: [typed · Esc] | [Greek · Space] | [alternative · Tab].
/// It never takes focus and clicks pass through it, so typing is never interrupted.
/// </summary>
public sealed class Bubble : Form
{
    private static readonly Color Bg = Color.FromArgb(0x1d, 0x24, 0x20);
    private static readonly Color Fg = Color.FromArgb(0xe8, 0xec, 0xe9);
    private static readonly Color Muted = Color.FromArgb(0xa9, 0xb5, 0xae);
    private static readonly Color Line = Color.FromArgb(0x56, 0x64, 0x5c);
    private static readonly Color Accent = Color.FromArgb(0xf2, 0xb8, 0xc6);

    private string? _left, _middle, _right;
    private bool _learned;
    private readonly Font _font, _bold, _small;

    public Bubble()
    {
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        BackColor = Bg;
        DoubleBuffered = true;
        _font = new Font("Segoe UI", 11f);
        _bold = new Font("Segoe UI Semibold", 11f);
        _small = new Font("Segoe UI", 7.5f);
    }

    protected override bool ShowWithoutActivation => true;

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.ExStyle |= 0x08000000 /* NOACTIVATE */ | 0x80 /* TOOLWINDOW */ | 0x20 /* TRANSPARENT */ | 0x8 /* TOPMOST */;
            return cp;
        }
    }

    public void Show(string? left, string middle, string? right, bool learned, Rectangle caret)
    {
        _left = left;
        _middle = middle;
        _right = right;
        _learned = learned;
        var parts = Parts();
        int w = 0;
        using (var g = CreateGraphics())
        {
            foreach (var (text, key, bold) in parts)
            {
                var f = bold ? _bold : _font;
                w += (int)Math.Ceiling(g.MeasureString(text, f).Width + g.MeasureString(key, _small).Width) + 22;
            }
        }
        w += 6 + (_learned ? 10 : 0);
        int h = (int)(34 * DeviceDpi / 96f);
        var screen = Screen.FromPoint(caret.Location).WorkingArea;
        int x = Math.Clamp(caret.X - 12, screen.Left, screen.Right - w);
        int y = caret.Bottom + 6;
        if (y + h > screen.Bottom) y = caret.Top - h - 6;  // no room below: show above the line
        Bounds = new Rectangle(x, y, w, h);
        using (var path = Rounded(new Rectangle(0, 0, w, h), 10)) Region = new Region(path);
        if (!Visible) base.Show();
        Invalidate();
    }

    public new void Hide()
    {
        if (Visible) base.Hide();
    }

    private List<(string text, string key, bool bold)> Parts()
    {
        var p = new List<(string, string, bool)>();
        if (_left != null) p.Add((_left, "Esc", false));
        if (_middle != null) p.Add((_middle, "Space", true));
        if (_right != null) p.Add((_right, "Tab", false));
        return p;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;
        float x = 6, h = Height;
        var parts = Parts();
        for (int i = 0; i < parts.Count; i++)
        {
            var (text, key, bold) = parts[i];
            if (i > 0)
            {
                using var pen = new Pen(Line);
                g.DrawLine(pen, x, h * 0.28f, x, h * 0.72f);
            }
            var f = bold ? _bold : _font;
            var ts = g.MeasureString(text, f);
            using (var b = new SolidBrush(bold ? Fg : Muted)) g.DrawString(text, f, b, x + 8, (h - ts.Height) / 2);
            float kx = x + 8 + ts.Width + 2;
            if (bold && _learned)
            {
                using var dot = new SolidBrush(Accent);
                g.FillEllipse(dot, kx, h * 0.3f, 6, 6);
                kx += 10;
            }
            var ks = g.MeasureString(key, _small);
            var keyRect = new RectangleF(kx, (h - ks.Height) / 2 - 1, ks.Width + 4, ks.Height + 2);
            using (var pen = new Pen(Line)) g.DrawRectangle(pen, keyRect.X, keyRect.Y, keyRect.Width, keyRect.Height);
            using (var b = new SolidBrush(Muted)) g.DrawString(key, _small, b, kx + 2, keyRect.Y + 1);
            x = kx + keyRect.Width + 10;
        }
    }

    private static GraphicsPath Rounded(Rectangle r, int radius)
    {
        var p = new GraphicsPath();
        int d = radius * 2;
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }
}
