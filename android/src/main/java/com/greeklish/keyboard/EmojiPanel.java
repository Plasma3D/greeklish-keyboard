package com.greeklish.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full Unicode emoji set (assets/emoji.tsv) with category tabs, recents, skin tones on
 * long-press (the chosen tone is remembered) and search in English, Greek or Greeklish.
 */
public class EmojiPanel extends LinearLayout {

    public interface Listener {
        void onEmoji(String emoji);

        void onKey(int code);

        void onEmojiSearchStart();
    }

    /** One emoji with its English name, Greek keywords and skin-tone variants (may be empty). */
    public static final class Emoji {
        final String base, name;
        final String[] tones;
        String greek = "";

        Emoji(String base, String name, String[] tones) {
            this.base = base;
            this.name = name;
            this.tones = tones;
        }
    }

    private static final String[] TAB_ICONS = {"🕘", "😀", "👋", "🐶", "🍕", "✈️", "⚽", "💡", "❤️", "🏁"};
    private static final int MAX_RECENT = 40;

    private final Listener listener;
    private final SharedPreferences prefs;
    private final GridView grid;
    private final LinearLayout tabs;
    private final TextView search;
    private final float dp;
    private final List<List<Emoji>> groups = new ArrayList<>();
    private final Map<String, Emoji> byEmoji = new HashMap<>();
    private Theme theme;
    private int current = 1;
    private List<String> items = new ArrayList<>();
    private PopupWindow tonePopup;

    public EmojiPanel(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;
        prefs = ctx.getSharedPreferences("keyboard", Context.MODE_PRIVATE);
        dp = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        load(ctx);

        search = new TextView(ctx);
        search.setText("🔍  Search emoji (kardia, gelio, fire…)");
        search.setTextSize(14);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
        search.setOnClickListener(v -> listener.onEmojiSearchStart());
        LayoutParams sp = new LayoutParams(LayoutParams.MATCH_PARENT, (int) (36 * dp));
        sp.setMargins((int) (8 * dp), (int) (6 * dp), (int) (8 * dp), (int) (4 * dp));
        addView(search, sp);

        grid = new GridView(ctx);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth((int) (44 * dp));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new GradientDrawable());
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            pick(items.get(pos));
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            Emoji e = byEmoji.get(baseOf(items.get(pos)));
            if (e == null || e.tones.length == 0) return false;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showTones(v, e);
            return true;
        });
        addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(button("ABC", v -> listener.onKey(Layouts.LETTERS)), new LayoutParams((int) (52 * dp), (int) (40 * dp)));
        tabs = new LinearLayout(ctx);
        for (int i = 0; i < TAB_ICONS.length; i++) {
            final int tab = i;
            TextView t = button(TAB_ICONS[i], v -> select(tab));
            t.setTextSize(17);
            tabs.addView(t, new LayoutParams(0, (int) (40 * dp), 1f));
        }
        bar.addView(tabs, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(button("⌫", v -> listener.onKey(Layouts.DELETE)), new LayoutParams((int) (52 * dp), (int) (40 * dp)));
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, (int) (46 * dp)));
    }

    // ------------------------------------------------------------------ data

    private void load(Context ctx) {
        Paint glyphs = new Paint();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("emoji.tsv"), StandardCharsets.UTF_8))) {
            String line;
            List<Emoji> group = null;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) {
                    group = new ArrayList<>();
                    groups.add(group);
                    continue;
                }
                String[] f = line.split("\t", -1);
                if (group == null || f.length < 3 || !glyphs.hasGlyph(f[0])) continue;  // skip emojis this phone can't draw
                List<String> tones = new ArrayList<>();
                if (!f[2].isEmpty()) for (String t : f[2].split(",")) if (glyphs.hasGlyph(t)) tones.add(t);
                Emoji e = new Emoji(f[0], f[1], tones.size() == 5 ? tones.toArray(new String[0]) : new String[0]);
                group.add(e);
                byEmoji.put(e.base, e);
                for (String t : e.tones) byEmoji.put(t, e);
            }
        } catch (Exception ignored) {
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("emoji_el.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t");
                Emoji e = f.length == 2 ? byEmoji.get(f[0]) : null;
                if (e != null) e.greek = f[1];
            }
        } catch (Exception ignored) {
        }
    }

    private String baseOf(String shown) {
        Emoji e = byEmoji.get(shown);
        return e == null ? shown : e.base;
    }

    /** The variant to show for an emoji: the skin tone the user picked last time, if any. */
    private String preferred(Emoji e) {
        int tone = prefs.getInt("tone:" + e.base, -1);
        return tone >= 0 && tone < e.tones.length ? e.tones[tone] : e.base;
    }

    // ------------------------------------------------------------------ search

    /**
     * Emojis matching {@code query}: Greek keywords first (typed in Greek or Greeklish),
     * then English names. Every word of the query must be the start of some keyword.
     */
    public List<String> search(String query) {
        String[] q = canonical(query).split(" ");
        LinkedHashSet<String> greekHits = new LinkedHashSet<>(), englishHits = new LinkedHashSet<>();
        if (query.trim().isEmpty()) return new ArrayList<>();
        for (List<Emoji> g : groups) {
            for (Emoji e : g) {
                if (!e.greek.isEmpty() && matches(q, canonical(e.greek))) greekHits.add(preferred(e));
                else if (matches(q, canonical(e.name))) englishHits.add(preferred(e));
            }
        }
        List<String> out = new ArrayList<>(greekHits);
        out.addAll(englishHits);
        return out;
    }

    private static boolean matches(String[] query, String words) {
        String padded = " " + words;
        for (String w : query) if (!w.isEmpty() && !padded.contains(" " + w)) return false;
        return true;
    }

    /** Greek and Greeklish spellings map to the same simple Latin form (καρδιά, kardia -> kardia). */
    static String canonical(String s) {
        s = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String[][] greek = {{"ου", "u"}, {"αι", "e"}, {"ει", "i"}, {"οι", "i"}, {"ψ", "ps"}, {"ξ", "x"}, {"θ", "8"},
                {"χ", "x"}, {"α", "a"}, {"β", "v"}, {"γ", "g"}, {"δ", "d"}, {"ε", "e"}, {"ζ", "z"}, {"η", "i"},
                {"ι", "i"}, {"κ", "k"}, {"λ", "l"}, {"μ", "m"}, {"ν", "n"}, {"ο", "o"}, {"π", "p"}, {"ρ", "r"},
                {"σ", "s"}, {"ς", "s"}, {"τ", "t"}, {"υ", "i"}, {"φ", "f"}, {"ω", "o"}};
        for (String[] g : greek) s = s.replace(g[0], g[1]);
        s = s.replace("th", "8").replace("ks", "x").replace("ch", "x").replace("ou", "u")
                .replace("ai", "e").replace("ei", "i").replace("oi", "i")
                .replace("mp", "b").replace("nt", "d")
                .replace('w', 'o').replace('h', 'i').replace('y', 'i');
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c)) c = ' ';
            if (out.length() > 0 && out.charAt(out.length() - 1) == c) continue;  // λλ / ll -> l
            out.append(c);
        }
        return out.toString().trim();
    }

    // ------------------------------------------------------------------ UI

    public void setTheme(Theme t) {
        theme = t;
        setBackgroundColor(t.background);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(t.functionKey);
        sb.setCornerRadius(18 * dp);
        search.setBackground(sb);
        search.setTextColor(t.textSecondary);
        refreshTabs();
        adapter.notifyDataSetChanged();
    }

    public void open() {
        select(recents().isEmpty() ? 1 : 0);
    }

    private void pick(String emoji) {
        addRecent(emoji);
        listener.onEmoji(emoji);
    }

    /** Called when an emoji is chosen from the search results. */
    public void picked(String emoji) {
        addRecent(emoji);
    }

    private void showTones(View anchor, Emoji e) {
        LinearLayout row = new LinearLayout(getContext());
        int pad = (int) (6 * dp);
        row.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.popup);
        bg.setCornerRadius(12 * dp);
        row.setBackground(bg);
        String[] all = new String[e.tones.length + 1];
        all[0] = e.base;
        System.arraycopy(e.tones, 0, all, 1, e.tones.length);
        tonePopup = new PopupWindow(row, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true);
        for (int i = 0; i < all.length; i++) {
            final int tone = i - 1;
            final String v = all[i];
            TextView t = new TextView(getContext());
            t.setText(v);
            t.setTextSize(26);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(x -> {
                prefs.edit().putInt("tone:" + e.base, tone).apply();
                tonePopup.dismiss();
                pick(v);
                adapter.notifyDataSetChanged();
            });
            row.addView(t, new LayoutParams((int) (44 * dp), (int) (48 * dp)));
        }
        tonePopup.setClippingEnabled(false);
        int w = (int) (44 * dp * all.length + 12 * dp);
        tonePopup.showAsDropDown(anchor, -(w - anchor.getWidth()) / 2, -anchor.getHeight() - (int) (64 * dp));
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15);
        t.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            l.onClick(v);
        });
        return t;
    }

    private void select(int tab) {
        current = tab;
        items = new ArrayList<>();
        if (tab == 0) {
            items = recents();
        } else if (tab - 1 < groups.size()) {
            for (Emoji e : groups.get(tab - 1)) items.add(preferred(e));
        }
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
        refreshTabs();
    }

    private void refreshTabs() {
        if (theme == null) return;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View v = tabs.getChildAt(i);
            if (i == current) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(theme.keyPressed);
                bg.setCornerRadius(16 * dp);
                v.setBackground(bg);
            } else {
                v.setBackground(null);
            }
        }
        ViewGroup bar = (ViewGroup) getChildAt(2);
        ((TextView) bar.getChildAt(0)).setTextColor(theme.text);
        ((TextView) bar.getChildAt(2)).setTextColor(theme.text);
    }

    private List<String> recents() {
        String saved = prefs.getString("recent_emoji", "");
        List<String> out = new ArrayList<>();
        for (String s : saved.split("\u0001")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private void addRecent(String e) {
        List<String> r = recents();
        r.remove(e);
        r.add(0, e);
        while (r.size() > MAX_RECENT) r.remove(r.size() - 1);
        prefs.edit().putString("recent_emoji", TextUtils.join("\u0001", r)).apply();
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int p) {
            return items.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View convert, ViewGroup parent) {
            TextView t = convert instanceof TextView ? (TextView) convert : new TextView(getContext());
            String shown = items.get(p);
            Emoji e = byEmoji.get(shown);
            // Grid tabs follow the remembered skin tone; recents keep exactly what was sent.
            if (current != 0 && e != null) shown = preferred(e);
            t.setText(shown);
            t.setTextSize(26);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight((int) (48 * dp));
            return t;
        }
    };
}
