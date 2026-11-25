using System.Reflection;

namespace GreeklishKeyboard;

/// <summary>
/// Decides whether a typed word is English (keep it) or Greeklish (convert it), from how much
/// more common each word is in English than in Greeklish (Assets/english.tsv, built by
/// scripts/build_english.py). Words common in both ("to", "me", "i") follow the sentence.
/// Same rule as scripts/test_english.py and the Android app.
/// </summary>
public sealed class EnglishDetector
{
    public const double Strong = 3.0, Weak = -1.5, Need = 2.0;
    /// <summary>In an English sentence, real English words above this stay English (rotate, file); se, na, kai don't.</summary>
    public const double Floor = -4.0;
    private readonly Dictionary<string, double> _scores = new();

    public EnglishDetector()
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("english.tsv");
        if (s == null) return;
        using var r = new StreamReader(s);
        string? line;
        while ((line = r.ReadLine()) != null)
        {
            int tab = line.IndexOf('\t');
            if (tab > 0 && double.TryParse(line[(tab + 1)..], System.Globalization.NumberStyles.Float,
                    System.Globalization.CultureInfo.InvariantCulture, out var v))
                _scores[line[..tab]] = v;
        }
    }

    /// <summary>ln(P_english / P_greeklish) for the word: high = English, low = Greeklish.</summary>
    public double Score(string word)
    {
        if (word.Any(char.IsDigit)) return -6;  // 8a, 3erw: Greeklish
        var w = new string(word.ToLowerInvariant().Where(c => c is >= 'a' and <= 'z' or '\'').ToArray()).Trim('\'');
        return w.Length > 0 && _scores.TryGetValue(w, out var s) ? s : -6;
    }

    /// <summary>True if the word should stay English, given the scores of the previous words in this sentence.</summary>
    public static bool IsEnglish(double score, IReadOnlyList<double> sentence)
    {
        if (score >= Strong) return true;
        if (score > Weak && score + Recent(sentence, sentence.Count) >= Need) return true;
        return score > Floor && EnglishSentence(sentence);
    }

    /// <summary>
    /// The sentence is clearly English so far ("when i do", "send me the"): 2 of the last 3 words are
    /// clearly English, or the last 2-3 lean English on average. Then it continues in English.
    /// </summary>
    public static bool EnglishSentence(IReadOnlyList<double> sentence)
    {
        var last = sentence.TakeLast(3).ToList();
        return last.Count(x => x >= Strong) >= 2 || (last.Count >= 2 && Recent(sentence, sentence.Count) >= Need);
    }

    /// <summary>Average score (clipped to -3..3) of up to 3 words before index end; 0 if none.</summary>
    private static double Recent(IReadOnlyList<double> sentence, int end)
    {
        int n = Math.Min(3, end);
        if (n == 0) return 0;
        double ctx = 0;
        for (int i = end - n; i < end; i++) ctx += Math.Clamp(sentence[i], -3, 3);
        return ctx / n;
    }

    /// <summary>
    /// A clearly English word right after an ambiguous one means that one was English too ("do" in "do you"),
    /// unless the sentence before it was Greek ("pes mou gia to game": "to" stays as το).
    /// The sentence list ends with the previous word's score.
    /// </summary>
    public static bool PreviousWasEnglish(double score, IReadOnlyList<double> sentence)
    {
        if (score < Strong || sentence.Count == 0) return false;
        double prev = sentence[^1];
        return prev > Weak && prev + Recent(sentence, sentence.Count - 1) >= 0;
    }

    public static bool EndsSentence(string word) => word.Length > 0 && ".!?;".Contains(word[^1]);
}
