using System.Reflection;
using System.Text;
using System.Text.Json;
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;

namespace GreeklishKeyboard;

/// <summary>
/// Runs the Greeklish -> Greek model (same file as the Android app). Each space-separated
/// word is decoded entirely in Greek or entirely in Latin, whichever the model prefers.
/// </summary>
public sealed class Converter : IDisposable
{
    public sealed record Result(string Input, string Best, string? Alternative);

    private readonly InferenceSession _session;
    private readonly Dictionary<char, long> _src = new();
    private readonly string[] _tgt;
    private readonly bool[] _greekOk, _latinOk;
    private readonly int _maxLen, _upsample;
    private readonly object _lock = new();

    public Converter()
    {
        var asm = Assembly.GetExecutingAssembly();
        using var vs = asm.GetManifestResourceStream("vocab.json")!;
        using var doc = JsonDocument.Parse(vs);
        var root = doc.RootElement;
        long i = 0;
        foreach (var s in root.GetProperty("src").EnumerateArray())
        {
            var str = s.GetString()!;
            if (str.Length == 1) _src[str[0]] = i;
            i++;
        }
        _tgt = root.GetProperty("tgt").EnumerateArray().Select(x => x.GetString()!).ToArray();
        _greekOk = new bool[_tgt.Length];
        _latinOk = new bool[_tgt.Length];
        for (int v = 0; v < _tgt.Length; v++)
        {
            bool greek = false, latin = false;
            if (v > 1 && _tgt[v].Length == 1)
            {
                char c = _tgt[v][0];
                greek = (c >= 'Ͱ' && c <= 'Ͽ') || (c >= 'ἀ' && c <= '῿');
                latin = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            }
            _greekOk[v] = !latin;
            _latinOk[v] = !greek;
        }
        _maxLen = root.GetProperty("max_len").GetInt32();
        _upsample = root.GetProperty("upsample").GetInt32();

        using var ms = new MemoryStream();
        asm.GetManifestResourceStream("greeklish.onnx")!.CopyTo(ms);
        var opts = new SessionOptions { IntraOpNumThreads = 2, GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL };
        _session = new InferenceSession(ms.ToArray(), opts);
    }

    /// <summary>Greek for the last word of <c>context + " " + word</c>, plus an alternative spelling.</summary>
    public Result ConvertLastWord(string context, string word)
    {
        lock (_lock)
        {
            string text = context.Length == 0 ? word : context + " " + word;
            if (text.Length > _maxLen) text = text[^_maxLen..];
            int n = text.Length;
            float[,] logp = Run(text);
            int start = (n - word.Length) * _upsample, end = n * _upsample;
            int prev = start > 0 ? ArgMax(logp, start - 1, null) : -1;
            bool[] mask = ChooseScript(logp, start, end);
            int[] best = new int[end - start];
            for (int f = start; f < end; f++) best[f - start] = ArgMax(logp, f, mask);
            string main = Collapse(best, prev);

            // Alternative: flip the least confident frame to its runner-up (πως / πώς).
            string? alt = null;
            float gapMin = float.MaxValue;
            int flipAt = -1, flipTo = -1;
            for (int f = start; f < end; f++)
            {
                int top = best[f - start];
                int second = SecondBest(logp, f, mask, top);
                if (second < 0 || (top == 0 && second == 0)) continue;
                float gap = logp[f, top] - logp[f, second];
                if (gap < gapMin) { gapMin = gap; flipAt = f - start; flipTo = second; }
            }
            if (flipAt >= 0 && gapMin < 2.5f)
            {
                var a = (int[])best.Clone();
                a[flipAt] = flipTo;
                string s = Collapse(a, prev);
                if (s != main && s.Length == main.Length && !string.Equals(s, main, StringComparison.OrdinalIgnoreCase)) alt = s;
            }
            return new Result(word, main, alt);
        }
    }

    /// <summary>Converts every space-separated word; one Greek string per word.</summary>
    public string[] ConvertWords(string text)
    {
        lock (_lock)
        {
            if (text.Length > _maxLen) text = text[^_maxLen..];
            float[,] logp = Run(text);
            string[] words = text.Split(' ');
            var outp = new string[words.Length];
            int pos = 0;
            for (int w = 0; w < words.Length; w++)
            {
                int start = pos * _upsample, end = (pos + words[w].Length) * _upsample;
                int prev = start > 0 ? ArgMax(logp, start - 1, null) : -1;
                bool[] mask = ChooseScript(logp, start, end);
                var best = new int[end - start];
                for (int f = start; f < end; f++) best[f - start] = ArgMax(logp, f, mask);
                outp[w] = Collapse(best, prev);
                pos += words[w].Length + 1;
            }
            return outp;
        }
    }

    private float[,] Run(string text)
    {
        int n = text.Length;
        var ids = new DenseTensor<long>(new[] { 1, n });
        for (int i = 0; i < n; i++) ids[0, i] = _src.TryGetValue(text[i], out var id) ? id : 1;
        using var results = _session.Run(new[] { NamedOnnxValue.CreateFromTensor("src", ids) });
        var t = results.First().AsTensor<float>();
        int frames = t.Dimensions[1], vocab = t.Dimensions[2];
        var outp = new float[frames, vocab];
        for (int f = 0; f < frames; f++)
            for (int v = 0; v < vocab; v++) outp[f, v] = t[0, f, v];
        return outp;
    }

    private bool[] ChooseScript(float[,] logp, int start, int end)
    {
        double g = 0, l = 0;
        for (int f = start; f < end; f++)
        {
            g += logp[f, ArgMax(logp, f, _greekOk)];
            l += logp[f, ArgMax(logp, f, _latinOk)];
        }
        return g >= l ? _greekOk : _latinOk;
    }

    private string Collapse(int[] frames, int prev)
    {
        var sb = new StringBuilder();
        foreach (int t in frames)
        {
            if (t != prev && t > 1) sb.Append(_tgt[t]);
            prev = t;
        }
        return sb.ToString();
    }

    private static int ArgMax(float[,] logp, int f, bool[]? allowed)
    {
        int best = 0;
        float bv = float.MinValue;
        for (int v = 0; v < logp.GetLength(1); v++)
        {
            if (allowed != null && !allowed[v]) continue;
            if (logp[f, v] > bv) { bv = logp[f, v]; best = v; }
        }
        return best;
    }

    private static int SecondBest(float[,] logp, int f, bool[] allowed, int exclude)
    {
        int best = -1;
        float bv = float.MinValue;
        for (int v = 0; v < logp.GetLength(1); v++)
        {
            if (v == exclude || v == 1 || !allowed[v]) continue;
            if (logp[f, v] > bv) { bv = logp[f, v]; best = v; }
        }
        return best;
    }

    public void Dispose() => _session.Dispose();
}
