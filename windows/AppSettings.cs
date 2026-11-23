using System.Text.Json;
using Microsoft.Win32;

namespace GreeklishKeyboard;

/// <summary>Settings and learned words, stored in %APPDATA%\GreeklishKeyboard\settings.json (this PC only).</summary>
public sealed class AppSettings
{
    public bool Enabled { get; set; } = true;
    public bool ShowBubble { get; set; } = true;
    public bool LearnWords { get; set; } = true;
    /// <summary>Leave English words (yes, same, the…) as typed.</summary>
    public bool KeepEnglish { get; set; } = true;
    /// <summary>Turn non-words into the closest real Greek word (καλυμέα -> καλημέρα).</summary>
    public bool Autocorrect { get; set; } = true;
    /// <summary>Hotkey as Keys flags, default Ctrl+Shift+G.</summary>
    public int Hotkey { get; set; } = (int)(Keys.Control | Keys.Shift | Keys.G);
    /// <summary>typed latin (lowercase) -> wanted output ("gym" -> "gym").</summary>
    public Dictionary<string, string> Learned { get; set; } = new();

    private static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GreeklishKeyboard");
    private static string FilePath => Path.Combine(Dir, "settings.json");
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath)) return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new();
        }
        catch { }
        return new AppSettings();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, Json));
        }
        catch { }
    }

    public string? LearnedFor(string typed)
    {
        if (typed.Length == 0 || !Learned.TryGetValue(typed.ToLowerInvariant(), out var outp) || outp.Length == 0) return null;
        if (char.IsUpper(typed[0]) && !char.IsUpper(outp[0])) outp = char.ToUpperInvariant(outp[0]) + outp[1..];
        return outp;
    }

    public void Learn(string typed, string wanted)
    {
        if (!LearnWords || typed.Length == 0 || wanted.Length == 0 || typed.Length > 40) return;
        string key = typed.ToLowerInvariant();
        string value = typed == wanted ? key
            : char.IsUpper(typed[0]) ? char.ToLowerInvariant(wanted[0]) + wanted[1..] : wanted;
        Learned[key] = value;
        Save();
    }

    // ---- start with Windows (current user only, no admin) ----
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public static bool StartsWithWindows
    {
        get
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey);
            return k?.GetValue("GreeklishKeyboard") is string;
        }
        set
        {
            using var k = Registry.CurrentUser.CreateSubKey(RunKey);
            if (value) k.SetValue("GreeklishKeyboard", "\"" + Environment.ProcessPath + "\" --background");
            else k.DeleteValue("GreeklishKeyboard", false);
        }
    }
}
