package com.greeklish.keyboard;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Runs the on-device Greeklish -> Greek model (assets/greeklish.int8.onnx).
 * Mirrors scripts/model.py decode_words(): CTC greedy decoding where every
 * space-separated word is decoded entirely in Greek or entirely in Latin script.
 */
public final class Converter {

    /** Conversion of the last word in the input, plus an alternative spelling (may be null). */
    public static final class Result {
        public final String input;
        public final String best;
        public final String alternative;

        Result(String input, String best, String alternative) {
            this.input = input;
            this.best = best;
            this.alternative = alternative;
        }
    }

    private final OrtEnvironment env;
    private final OrtSession session;
    private final Map<Character, Integer> srcIndex = new HashMap<>();
    private final String[] tgt;
    private final boolean[] isGreek;
    private final boolean[] isLatin;
    private final int maxLen;
    private final int upsample;

    public Converter(Context ctx) throws Exception {
        this(readAsset(ctx, "greeklish.int8.onnx"), new String(readAsset(ctx, "vocab.json"), StandardCharsets.UTF_8));
    }

    /** Plain-Java constructor (also used by the PC test harness). */
    public Converter(byte[] model, String vocabJson) throws Exception {
        JSONObject vocab = new JSONObject(vocabJson);
        JSONArray src = vocab.getJSONArray("src");
        for (int i = 0; i < src.length(); i++) {
            String s = src.getString(i);
            if (s.length() == 1) srcIndex.put(s.charAt(0), i);
        }
        JSONArray t = vocab.getJSONArray("tgt");
        tgt = new String[t.length()];
        isGreek = new boolean[t.length()];
        isLatin = new boolean[t.length()];
        for (int i = 0; i < t.length(); i++) {
            tgt[i] = t.getString(i);
            if (i > 1 && tgt[i].length() == 1) {
                char c = tgt[i].charAt(0);
                isGreek[i] = (c >= 'Ͱ' && c <= 'Ͽ') || (c >= 'ἀ' && c <= '῿');
                isLatin[i] = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            }
        }
        maxLen = vocab.getInt("max_len");
        upsample = vocab.getInt("upsample");

        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(model, opts);
    }

    /**
     * Converts {@code context + " " + word} and returns the Greek for {@code word} only.
     * The context (previous words, in the Latin the user typed) helps pick accents.
     */
    public synchronized Result convertLastWord(String context, String word) throws Exception {
        String text = context.isEmpty() ? word : context + " " + word;
        if (text.length() > maxLen) text = text.substring(text.length() - maxLen);
        int n = text.length();
        float[][] logp = run(text);
        // The last word occupies input positions [n - word.length(), n).
        int start = (n - word.length()) * upsample;
        int end = n * upsample;
        int prevToken = start > 0 ? argmax(logp[start - 1], null) : -1;

        boolean[] mask = chooseScript(logp, start, end);
        int[] best = new int[end - start];
        for (int f = start; f < end; f++) best[f - start] = argmax(logp[f], mask);
        String main = collapse(best, prevToken);

        // Alternative: flip the least confident frame to its runner-up (e.g. πως / πώς, τη / τι).
        String alt = null;
        float smallestGap = Float.MAX_VALUE;
        int flipFrame = -1, flipTo = -1;
        for (int f = start; f < end; f++) {
            int top = best[f - start];
            int second = secondBest(logp[f], mask, top);
            if (second < 0 || (top == 0 && second == 0)) continue;
            float gap = logp[f][top] - logp[f][second];
            if (gap < smallestGap) {
                smallestGap = gap;
                flipFrame = f - start;
                flipTo = second;
            }
        }
        if (flipFrame >= 0 && smallestGap < 2.5f) {
            int[] alternative = best.clone();
            alternative[flipFrame] = flipTo;
            String a = collapse(alternative, prevToken);
            // Only same-length variants (accent / one letter): those are the useful ones.
            if (!a.equals(main) && a.length() == main.length() && !a.equalsIgnoreCase(main)) alt = a;
        }
        return new Result(word, main, alt);
    }

    /** Converts every space-separated word of {@code text}; returns one Greek string per word. */
    public synchronized String[] convertWords(String text) throws Exception {
        if (text.length() > maxLen) text = text.substring(text.length() - maxLen);
        float[][] logp = run(text);
        String[] words = text.split(" ", -1);
        String[] out = new String[words.length];
        int pos = 0;
        for (int w = 0; w < words.length; w++) {
            int start = pos * upsample, end = (pos + words[w].length()) * upsample;
            int prevToken = start > 0 ? argmax(logp[start - 1], null) : -1;
            boolean[] mask = chooseScript(logp, start, end);
            int[] best = new int[end - start];
            for (int f = start; f < end; f++) best[f - start] = argmax(logp[f], mask);
            out[w] = collapse(best, prevToken);
            pos += words[w].length() + 1;
        }
        return out;
    }

    private float[][] run(String text) throws Exception {
        int n = text.length();
        long[][] ids = new long[1][n];
        for (int i = 0; i < n; i++) {
            Integer id = srcIndex.get(text.charAt(i));
            ids[0][i] = id == null ? 1 : id;
        }
        try (OnnxTensor in = OnnxTensor.createTensor(env, ids);
             OrtSession.Result out = session.run(Collections.singletonMap("src", in))) {
            return ((float[][][]) out.get(0).getValue())[0];
        }
    }

    /** Greek-only or Latin-only mask, whichever explains the frames better. */
    private boolean[] chooseScript(float[][] logp, int start, int end) {
        boolean[] greekOk = new boolean[tgt.length];
        boolean[] latinOk = new boolean[tgt.length];
        for (int v = 0; v < tgt.length; v++) {
            greekOk[v] = !isLatin[v];
            latinOk[v] = !isGreek[v];
        }
        double g = 0, l = 0;
        for (int f = start; f < end; f++) {
            g += logp[f][argmax(logp[f], greekOk)];
            l += logp[f][argmax(logp[f], latinOk)];
        }
        return g >= l ? greekOk : latinOk;
    }

    private String collapse(int[] frames, int prev) {
        StringBuilder sb = new StringBuilder();
        for (int t : frames) {
            if (t != prev && t > 1) sb.append(tgt[t]);
            prev = t;
        }
        return sb.toString();
    }

    private static int argmax(float[] row, boolean[] allowed) {
        int best = 0;
        float bv = -Float.MAX_VALUE;
        for (int v = 0; v < row.length; v++) {
            if (allowed != null && !allowed[v]) continue;
            if (row[v] > bv) {
                bv = row[v];
                best = v;
            }
        }
        return best;
    }

    private static int secondBest(float[] row, boolean[] allowed, int exclude) {
        int best = -1;
        float bv = -Float.MAX_VALUE;
        for (int v = 0; v < row.length; v++) {
            if (v == exclude || v == 1 || !allowed[v]) continue;
            if (row[v] > bv) {
                bv = row[v];
                best = v;
            }
        }
        return best;
    }

    private static byte[] readAsset(Context ctx, String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return out.toByteArray();
        }
    }

    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }
}
