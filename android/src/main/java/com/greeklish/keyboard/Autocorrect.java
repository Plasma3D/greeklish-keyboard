package com.greeklish.keyboard;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fixes Greek words the AI produced that are not real words, using a dictionary of 120k words with
 * frequencies (assets/greek_words.tsv). Words are compared by how they sound in Greeklish
 * (η/ι/υ = i, ο/ω = o, ...), so "kalimeea" finds καλημέρα. Common words win. Port of Autocorrect.cs.
 */
public final class Autocorrect {
    private static final class Entry {
        final String word, key;
        final int count;

        Entry(String word, String key, int count) {
            this.word = word;
            this.key = key;
            this.count = count;
        }
    }

    private final Map<String, Integer> known = new HashMap<>();
    private final Map<String, Entry> bestByPlain = new HashMap<>();
    private final Map<String, List<Entry>> buckets = new HashMap<>();

    public Autocorrect(Context ctx) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("greek_words.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String w = line.substring(0, tab);
                int count = Integer.parseInt(line.substring(tab + 1));
                known.put(w, count);
                String plain = plain(w);
                Entry e = new Entry(w, soundKey(w), count);
                Entry b = bestByPlain.get(plain);
                if (b == null || b.count < count) bestByPlain.put(plain, e);
                if (e.key.isEmpty()) continue;
                String bucket = e.key.charAt(0) + ":" + e.key.length();
                List<Entry> list = buckets.get(bucket);
                if (list == null) buckets.put(bucket, list = new ArrayList<>());
                list.add(e);
            }
        } catch (Exception ignored) {
        }
    }

    /** The corrected word, or null if the AI's word is fine (or nothing close enough exists). */
    public String correct(String aiOutput, String typed) {
        int end = 0;
        while (end < aiOutput.length() && isGreek(aiOutput.charAt(end))) end++;
        if (end < 3) return null;
        String tail = aiOutput.substring(end);
        for (char c : tail.toCharArray()) if (Character.isLetter(c)) return null;
        String core = aiOutput.substring(0, end);
        String lower = core.toLowerCase(Locale.ROOT);

        String fixed = null;
        Integer have = known.get(lower);
        if (have != null) {
            // A real word, but maybe a rare spelling of a common one (ευχαριστω -> ευχαριστώ).
            Entry b = bestByPlain.get(plain(lower));
            if (b != null && !b.word.equals(lower) && b.count > 20 * have) fixed = b.word;
        } else if (end >= 4) {
            StringBuilder letters = new StringBuilder();
            for (char c : typed.toCharArray()) {
                if (!Character.isLetterOrDigit(c)) break;
                letters.append(c);
            }
            String key = soundKey(letters.length() >= 3 ? letters.toString() : lower);
            if (!key.isEmpty()) fixed = closest(key);
        }
        if (fixed == null) return null;
        boolean allCaps = core.length() > 1 && core.equals(core.toUpperCase(Locale.ROOT));
        if (allCaps) fixed = fixed.toUpperCase(Locale.ROOT);
        else if (Character.isUpperCase(core.charAt(0))) fixed = fixed.substring(0, 1).toUpperCase(Locale.ROOT) + fixed.substring(1);
        return fixed + tail;
    }

    private String closest(String key) {
        int maxDist = key.length() >= 6 ? 2 : 1;
        Entry best = null;
        double bestCost = Double.MAX_VALUE;
        for (int len = key.length() - maxDist; len <= key.length() + maxDist; len++) {
            List<Entry> list = buckets.get(key.charAt(0) + ":" + len);
            if (list == null) continue;
            for (Entry e : list) {
                int d = distance(key, e.key, maxDist);
                // 2 letters off only towards common words: protects names from odd swaps
                if (d > maxDist || (d == 2 && e.count < 300)) continue;
                double cost = d - 0.5 * Math.log10(e.count + 1);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = e;
                }
            }
        }
        return best == null ? null : best.word;
    }

    private static boolean isGreek(char c) {
        return (c >= 'Ͱ' && c <= 'Ͽ') || (c >= 'ἀ' && c <= '῿');
    }

    static String plain(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('ς', 'σ');
    }

    private static final String[][] GREEK_SOUNDS = {
            {"ου", "u"}, {"αι", "e"}, {"ει", "i"}, {"οι", "i"}, {"μπ", "b"}, {"ντ", "d"}, {"γκ", "g"}, {"γγ", "g"},
            {"ψ", "ps"}, {"ξ", "x"}, {"θ", "8"}, {"χ", "x"}, {"α", "a"}, {"β", "v"}, {"γ", "g"}, {"δ", "d"}, {"ε", "e"},
            {"ζ", "z"}, {"η", "i"}, {"ι", "i"}, {"κ", "k"}, {"λ", "l"}, {"μ", "m"}, {"ν", "n"}, {"ο", "o"}, {"π", "p"},
            {"ρ", "r"}, {"σ", "s"}, {"τ", "t"}, {"υ", "i"}, {"φ", "f"}, {"ω", "o"},
    };

    /** How a word sounds in plain Latin letters: καλημέρα, kalimera and kalhmera all give "kalimera". */
    static String soundKey(String s) {
        s = plain(s);
        for (String[] g : GREEK_SOUNDS) s = s.replace(g[0], g[1]);
        s = s.replace("th", "8").replace("ks", "x").replace("ch", "x").replace("ou", "u").replace("ai", "e")
                .replace("ei", "i").replace("oi", "i").replace("mp", "b").replace("nt", "d").replace("gk", "g")
                .replace('w', 'o').replace('h', 'i').replace('y', 'i').replace('3', 'x').replace('j', 'x');
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) continue;
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == c) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /** Damerau-Levenshtein distance (swaps count as 1), giving up above max. */
    private static int distance(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) return max + 1;
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > max) return max + 1;
        }
        return d[a.length()][b.length()];
    }
}
