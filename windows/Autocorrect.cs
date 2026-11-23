using System.Globalization;
using System.Reflection;
using System.Text;

namespace GreeklishKeyboard;

/// <summary>
/// Fixes Greek words the AI produced that are not real words, using a dictionary of 120k words
/// with frequencies (Assets/greek_words.tsv, scripts/build_greek_dict.py). Words are compared by
/// how they sound in Greeklish (η/ι/υ = i, ο/ω = o, ...), so "kalimeea" finds καλημέρα, not κάλυμμα.
/// Common words beat rare ones. Real words are left alone, except for a missing accent.
/// </summary>
public sealed class Autocorrect
{
    private readonly record struct Entry(string Word, string Key, int Count);

    private readonly Dictionary<string, int> _known = new();
    private readonly Dictionary<string, (string Word, int Count)> _bestByPlain = new();
    private readonly Dictionary<(char, int), List<Entry>> _buckets = new();

    public Autocorrect()
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("greek_words.tsv");
        if (s == null) return;
        using var r = new StreamReader(s);
        string? line;
        while ((line = r.ReadLine()) != null)
        {
            int tab = line.IndexOf('\t');
            if (tab <= 0) continue;
            string w = line[..tab];
            int count = int.Parse(line[(tab + 1)..], CultureInfo.InvariantCulture);
            _known[w] = count;
            string plain = Plain(w);
            if (!_bestByPlain.TryGetValue(plain, out var b) || b.Count < count) _bestByPlain[plain] = (w, count);
            string key = SoundKey(w);
            if (key.Length == 0) continue;
            var bucket = (key[0], key.Length);
            if (!_buckets.TryGetValue(bucket, out var list)) _buckets[bucket] = list = new List<Entry>();
            list.Add(new Entry(w, key, count));
        }
    }

    /// <summary>The corrected word, or null if the AI's word is fine (or nothing close enough exists).</summary>
    public string? Correct(string aiOutput, string typed)
    {
        // Split "κάνεις;" into the Greek word and what follows it.
        int end = 0;
        while (end < aiOutput.Length && IsGreekLetter(aiOutput[end])) end++;
        if (end < 3 || aiOutput[end..].Any(char.IsLetter)) return null;
        string core = aiOutput[..end], tail = aiOutput[end..];
        string lower = core.ToLowerInvariant();

        string? fixedWord = null;
        if (_known.TryGetValue(lower, out int have))
        {
            // A real word, but maybe a rare spelling of a common one (ευχαριστω -> ευχαριστώ).
            if (_bestByPlain.TryGetValue(Plain(lower), out var b) && b.Word != lower && b.Count > 20 * have) fixedWord = b.Word;
        }
        else if (end >= 4)
        {
            string typedLetters = new(typed.TakeWhile(c => char.IsLetterOrDigit(c)).ToArray());
            string key = SoundKey(typedLetters.Length >= 3 ? typedLetters : lower);
            if (key.Length > 0) fixedWord = Closest(key);
        }
        if (fixedWord == null) return null;
        if (core.Length > 1 && core.All(c => !char.IsLetter(c) || char.IsUpper(c))) fixedWord = fixedWord.ToUpperInvariant();
        else if (char.IsUpper(core[0])) fixedWord = char.ToUpperInvariant(fixedWord[0]) + fixedWord[1..];
        return fixedWord + tail;
    }

    private string? Closest(string key)
    {
        int maxDist = key.Length >= 6 ? 2 : 1;
        Entry? best = null;
        double bestCost = double.MaxValue;
        for (int len = key.Length - maxDist; len <= key.Length + maxDist; len++)
        {
            if (!_buckets.TryGetValue((key[0], len), out var list)) continue;
            foreach (var e in list)
            {
                int d = Distance(key, e.Key, maxDist);
                // 2 letters off only towards common words: protects names (giorgakis) from odd swaps
                if (d > maxDist || (d == 2 && e.Count < 300)) continue;
                double cost = d - 0.5 * Math.Log10(e.Count + 1);  // closer first, but common words count a lot
                if (cost < bestCost)
                {
                    bestCost = cost;
                    best = e;
                }
            }
        }
        return best?.Word;
    }

    private static bool IsGreekLetter(char c) => c is >= 'Ͱ' and <= 'Ͽ' or >= 'ἀ' and <= '῿';

    /// <summary>Lowercase, no accents, final sigma as σ: καλημερα ~ καλημέρα.</summary>
    public static string Plain(string s)
    {
        var sb = new StringBuilder(s.Length);
        foreach (char c in s.ToLowerInvariant().Normalize(NormalizationForm.FormD))
        {
            if (CharUnicodeInfo.GetUnicodeCategory(c) == UnicodeCategory.NonSpacingMark) continue;
            sb.Append(c == 'ς' ? 'σ' : c);
        }
        return sb.ToString();
    }

    private static readonly (string, string)[] GreekSounds =
    {
        ("ου", "u"), ("αι", "e"), ("ει", "i"), ("οι", "i"), ("μπ", "b"), ("ντ", "d"), ("γκ", "g"), ("γγ", "g"),
        ("ψ", "ps"), ("ξ", "x"), ("θ", "8"), ("χ", "x"), ("α", "a"), ("β", "v"), ("γ", "g"), ("δ", "d"), ("ε", "e"),
        ("ζ", "z"), ("η", "i"), ("ι", "i"), ("κ", "k"), ("λ", "l"), ("μ", "m"), ("ν", "n"), ("ο", "o"), ("π", "p"),
        ("ρ", "r"), ("σ", "s"), ("τ", "t"), ("υ", "i"), ("φ", "f"), ("ω", "o"),
    };

    /// <summary>How a word sounds, in plain Latin letters: καλημέρα, kalimera and kalhmera all give "kalimera".</summary>
    public static string SoundKey(string s)
    {
        s = Plain(s);
        foreach (var (g, l) in GreekSounds) s = s.Replace(g, l);
        s = s.Replace("th", "8").Replace("ks", "x").Replace("ch", "x").Replace("ou", "u").Replace("ai", "e")
            .Replace("ei", "i").Replace("oi", "i").Replace("mp", "b").Replace("nt", "d").Replace("gk", "g")
            .Replace('w', 'o').Replace('h', 'i').Replace('y', 'i').Replace('3', 'x').Replace('j', 'x');
        var sb = new StringBuilder(s.Length);
        foreach (char c in s)
        {
            if (!char.IsLetterOrDigit(c)) continue;
            if (sb.Length > 0 && sb[^1] == c) continue;  // λλ / ll -> l
            sb.Append(c);
        }
        return sb.ToString();
    }

    /// <summary>Damerau-Levenshtein distance (swaps count as 1), giving up above <paramref name="max"/>.</summary>
    private static int Distance(string a, string b, int max)
    {
        if (Math.Abs(a.Length - b.Length) > max) return max + 1;
        var d = new int[a.Length + 1, b.Length + 1];
        for (int i = 0; i <= a.Length; i++) d[i, 0] = i;
        for (int j = 0; j <= b.Length; j++) d[0, j] = j;
        for (int i = 1; i <= a.Length; i++)
        {
            int rowMin = int.MaxValue;
            for (int j = 1; j <= b.Length; j++)
            {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                int v = Math.Min(Math.Min(d[i - 1, j] + 1, d[i, j - 1] + 1), d[i - 1, j - 1] + cost);
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = Math.Min(v, d[i - 2, j - 2] + 1);
                d[i, j] = v;
                rowMin = Math.Min(rowMin, v);
            }
            if (rowMin > max) return max + 1;
        }
        return d[a.Length, b.Length];
    }
}
