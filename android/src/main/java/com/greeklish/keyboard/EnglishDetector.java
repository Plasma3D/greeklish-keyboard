package com.greeklish.keyboard;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides whether a typed word is English (keep it) or Greeklish (convert it), from how much more
 * common it is in English than in Greeklish (assets/english.tsv, scripts/build_english.py).
 * Words common in both ("to", "me", "i") follow the sentence. Same rule as the Windows app.
 */
public final class EnglishDetector {
    public static final double STRONG = 3.0, WEAK = -1.5, NEED = 2.0;
    private final Map<String, Double> scores = new HashMap<>();

    public EnglishDetector(Context ctx) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("english.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) scores.put(line.substring(0, tab), Double.parseDouble(line.substring(tab + 1)));
            }
        } catch (Exception ignored) {
        }
    }

    /** ln(P_english / P_greeklish): high = English, low = Greeklish. */
    public double score(String word) {
        StringBuilder sb = new StringBuilder();
        for (char c : word.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isDigit(c)) return -6;  // 8a, 3erw: Greeklish
            if ((c >= 'a' && c <= 'z') || c == '\'') sb.append(c);
        }
        String w = sb.toString().replaceAll("^'+|'+$", "");
        Double s = w.isEmpty() ? null : scores.get(w);
        return s == null ? -6 : s;
    }

    /** True if the word should stay English, given the scores of the previous words in this sentence. */
    public static boolean isEnglish(double score, List<Double> sentence) {
        if (score >= STRONG) return true;
        if (score <= WEAK) return false;
        int n = Math.min(3, sentence.size());
        double ctx = 0;
        for (int i = sentence.size() - n; i < sentence.size(); i++) ctx += Math.max(-3, Math.min(3, sentence.get(i)));
        if (n > 0) ctx /= n;
        return score + ctx >= NEED;
    }

    /** A clearly English word right after an ambiguous one means that one was English too ("do" in "do you"). */
    public static boolean previousWasEnglish(double score, double previousScore) {
        return score >= STRONG && previousScore > WEAK;
    }

    public static boolean endsSentence(String word) {
        return !word.isEmpty() && ".!?;".indexOf(word.charAt(word.length() - 1)) >= 0;
    }
}
