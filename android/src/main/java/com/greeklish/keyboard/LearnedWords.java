package com.greeklish.keyboard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Words the user taught the keyboard: typed Latin (lowercase) -> what they want instead.
 * E.g. "gym" -> "gym" (keep English), "pws" -> "πώς". Stored only on the phone.
 */
public final class LearnedWords {
    private static final String KEY = "learned_words";
    private final SharedPreferences prefs;
    private JSONObject map;

    public LearnedWords(Context ctx) {
        prefs = ctx.getSharedPreferences("keyboard", Context.MODE_PRIVATE);
        reload();
    }

    public void reload() {
        try {
            map = new JSONObject(prefs.getString(KEY, "{}"));
        } catch (Exception e) {
            map = new JSONObject();
        }
    }

    /** What the user wants for this typed word, adjusted to its capitalisation; null if not learned. */
    public String get(String typed) {
        String out = map.optString(typed.toLowerCase(Locale.ROOT), null);
        if (out == null || out.isEmpty() || typed.isEmpty()) return null;
        if (Character.isUpperCase(typed.charAt(0)) && !Character.isUpperCase(out.charAt(0))) {
            out = out.substring(0, 1).toUpperCase(Locale.ROOT) + out.substring(1);
        }
        return out;
    }

    public void learn(String typed, String wanted) {
        if (typed.isEmpty() || wanted.isEmpty() || typed.length() > 40) return;
        String key = typed.toLowerCase(Locale.ROOT);
        // Store lowercase when the only difference is the capital letter we add back in get().
        String value = Character.isUpperCase(typed.charAt(0)) && wanted.length() > 0
                ? wanted.substring(0, 1).toLowerCase(Locale.ROOT) + wanted.substring(1) : wanted;
        if (typed.equals(wanted)) value = key;
        try {
            map.put(key, value);
        } catch (Exception ignored) {
        }
        save();
    }

    public void remove(String typed) {
        map.remove(typed.toLowerCase(Locale.ROOT));
        save();
    }

    public void clear() {
        map = new JSONObject();
        save();
    }

    /** "typed → wanted" pairs, sorted by typed word. */
    public List<String[]> all() {
        List<String[]> out = new ArrayList<>();
        for (Iterator<String> it = map.keys(); it.hasNext(); ) {
            String k = it.next();
            out.add(new String[]{k, map.optString(k)});
        }
        Collections.sort(out, (a, b) -> a[0].compareTo(b[0]));
        return out;
    }

    private void save() {
        prefs.edit().putString(KEY, map.toString()).apply();
    }
}
