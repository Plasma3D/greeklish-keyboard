package com.greeklish.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Clipboard history. Like Gboard, items are kept for one hour only and never leave
 * the phone (stored in the app's private storage). Tap to paste, long-press to remove.
 */
public class ClipboardPanel extends LinearLayout {

    public interface Listener {
        void onPaste(String text);

        void onKey(int code);
    }

    private static final long KEEP_MS = 60 * 60 * 1000L;
    private static final int MAX_ITEMS = 20;

    private final Listener listener;
    private final SharedPreferences prefs;
    private final GridView grid;
    private final TextView empty;
    private final float dp;
    private Theme theme;
    private List<String> items = new ArrayList<>();

    public ClipboardPanel(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;
        prefs = ctx.getSharedPreferences("keyboard", Context.MODE_PRIVATE);
        dp = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        grid = new GridView(ctx);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing((int) (8 * dp));
        grid.setVerticalSpacing((int) (8 * dp));
        grid.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
        grid.setSelector(new GradientDrawable());
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            listener.onPaste(items.get(pos));
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            remove(items.get(pos));
            return true;
        });

        empty = new TextView(ctx);
        empty.setText("Copied text shows up here for 1 hour.\nTap to paste, long-press to remove.");
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(14);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(VERTICAL);
        content.addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(empty, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(button("ABC", v -> listener.onKey(Layouts.LETTERS)), new LayoutParams((int) (56 * dp), (int) (40 * dp)));
        View spacer = new View(ctx);
        bar.addView(spacer, new LayoutParams(0, 1, 1f));
        bar.addView(button("Clear all", v -> clear()), new LayoutParams(LayoutParams.WRAP_CONTENT, (int) (40 * dp)));
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, (int) (48 * dp)));
    }

    public void setTheme(Theme t) {
        theme = t;
        setBackgroundColor(t.background);
        empty.setTextColor(t.textSecondary);
        ViewGroup bar = (ViewGroup) getChildAt(1);
        ((TextView) bar.getChildAt(0)).setTextColor(t.text);
        ((TextView) bar.getChildAt(2)).setTextColor(t.text);
        adapter.notifyDataSetChanged();
    }

    public void open() {
        items = load();
        adapter.notifyDataSetChanged();
        boolean none = items.isEmpty();
        grid.setVisibility(none ? GONE : VISIBLE);
        empty.setVisibility(none ? VISIBLE : GONE);
    }

    /** Called when something new is copied anywhere on the phone. */
    public void add(String text) {
        if (text == null || text.trim().isEmpty()) return;
        JSONArray kept = new JSONArray();
        try {
            kept.put(new JSONObject().put("s", text).put("t", System.currentTimeMillis()));
            JSONArray old = fresh();
            for (int i = 0; i < old.length() && kept.length() < MAX_ITEMS; i++) {
                if (!old.getJSONObject(i).getString("s").equals(text)) kept.put(old.getJSONObject(i));
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString("clipboard", kept.toString()).apply();
    }

    private void remove(String text) {
        JSONArray kept = new JSONArray();
        try {
            JSONArray old = fresh();
            for (int i = 0; i < old.length(); i++) {
                if (!old.getJSONObject(i).getString("s").equals(text)) kept.put(old.getJSONObject(i));
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString("clipboard", kept.toString()).apply();
        open();
    }

    private void clear() {
        prefs.edit().putString("clipboard", "[]").apply();
        open();
    }

    /** Stored items younger than one hour, newest first: [{"s": text, "t": millis}]. */
    private JSONArray fresh() {
        JSONArray out = new JSONArray();
        try {
            JSONArray arr = new JSONArray(prefs.getString("clipboard", "[]"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                if (now - arr.getJSONObject(i).getLong("t") < KEEP_MS) out.put(arr.getJSONObject(i));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private List<String> load() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = fresh();
            for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i).getString("s"));
        } catch (Exception ignored) {
        }
        return out;
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15);
        t.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
        t.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            l.onClick(v);
        });
        return t;
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
            t.setText(items.get(p));
            t.setMaxLines(3);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setTextSize(14);
            t.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));
            t.setMinHeight((int) (56 * dp));
            if (theme != null) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(theme.key);
                bg.setCornerRadius(10 * dp);
                t.setBackground(bg);
                t.setTextColor(theme.text);
            }
            return t;
        }
    };
}
