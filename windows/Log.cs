namespace GreeklishKeyboard;

/// <summary>Small diagnostics log in %APPDATA%\GreeklishKeyboard\log.txt. Never contains what you type.</summary>
internal static class Log
{
    private static readonly string FilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GreeklishKeyboard", "log.txt");

    public static void Write(string message)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            var info = new FileInfo(FilePath);
            if (info.Exists && info.Length > 200_000) info.Delete();
            File.AppendAllText(FilePath, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {message}{Environment.NewLine}");
        }
        catch { }
    }
}
