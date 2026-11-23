package com.greeklish.keyboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** One-time setup: enable the keyboard, switch to it, try it, and a few settings. */
public class SetupActivity extends Activity {

    private Theme theme;
    private float dp;
    private LinearLayout step1, step2;
    private SharedPreferences prefs;
    private TextView themeName;
    private LinearLayout swatches;
    private static final int PICK_PHOTO = 7;
    /** PayPal donation page for the developer's account, in euros. */
    static final String DONATE_URL =
            "https://www.paypal.com/donate/?business=gnnsnsts%40gmail.com&currency_code=EUR&item_name=Greeklish%20Keyboard&locale.x=en_US";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        theme = new Theme(this);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("keyboard", MODE_PRIVATE);
        getWindow().setStatusBarColor(pageBackground());
        getWindow().setNavigationBarColor(pageBackground());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        col.setPadding(pad, (int) (40 * dp), pad, pad);

        TextView logo = text("αβ", 20, theme.onAccent, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(theme.accent, 14));
        col.addView(logo, new LinearLayout.LayoutParams((int) (52 * dp), (int) (52 * dp)));
        col.addView(space(16));
        col.addView(text("Greeklish keyboard", 24, theme.text, true));
        col.addView(space(6));
        col.addView(text("Type in Latin letters and send Greek. The AI runs fully on your phone, "
                + "and the app has no internet access.", 15, theme.textSecondary, false));
        col.addView(space(24));

        step1 = step("1", "Enable keyboard", "Turn on Greeklish Keyboard in the list", v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        col.addView(step1);
        col.addView(space(10));
        step2 = step("2", "Switch to it", "Pick it as your keyboard", v ->
                ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showInputMethodPicker());
        col.addView(step2);
        col.addView(space(24));

        col.addView(text("Try it here", 13, theme.textSecondary, false));
        col.addView(space(6));
        EditText test = new EditText(this);
        test.setHint("kalimera, ti kaneis?");
        test.setTextColor(theme.text);
        test.setHintTextColor(theme.textSecondary);
        test.setBackground(stroke());
        test.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
        col.addView(test);
        col.addView(space(24));

        col.addView(text("Keyboard size", 13, theme.textSecondary, false));
        col.addView(space(8));
        col.addView(sizePicker());
        col.addView(space(12));
        col.addView(heightSlider());
        col.addView(space(12));
        col.addView(letterSizePicker());
        col.addView(space(20));

        col.addView(text("Theme", 13, theme.textSecondary, false));
        col.addView(space(8));
        col.addView(themePicker());
        col.addView(space(4));
        themeName = text(Theme.NAMES[prefs.getInt("theme", 0)], 13, theme.textSecondary, false);
        col.addView(themeName);
        col.addView(toggle("Key borders", "key_borders", false));
        col.addView(space(12));

        col.addView(text("Settings", 13, theme.textSecondary, false));
        col.addView(toggle("Number row", "number_row", false));
        col.addView(toggle("Vibrate on keypress", "vibrate", true));
        col.addView(toggle("Clipboard history (kept 1 hour)", "clipboard_history", true));
        col.addView(toggle("Learn my words", "learn_words", true));
        col.addView(toggle("Keep English words in English", "keep_english", true));
        col.addView(toggle("Autocorrect typos", "autocorrect", true));
        View words = step("\u2605", "Your words", "Words you kept in Latin or spelled your way", v -> showWords());
        col.addView(words);

        col.addView(space(28));
        col.addView(text("Support the app", 13, theme.textSecondary, false));
        col.addView(space(6));
        col.addView(text("Greeklish Keyboard is free and has no ads. If it helps you, you can buy the developer a coffee.",
                14, theme.textSecondary, false));
        col.addView(space(12));
        TextView donate = text("♥  Donate with PayPal", 16, theme.onAccent, true);
        donate.setGravity(Gravity.CENTER);
        donate.setBackground(round(theme.accent, 24));
        // Opens PayPal in the browser; the keyboard itself still never connects to the internet.
        donate.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(DONATE_URL)));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "No browser found", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        col.addView(donate, new LinearLayout.LayoutParams(-1, (int) (48 * dp)));
        col.addView(space(24));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(pageBackground());
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refresh();  // the keyboard picker is a dialog, so onResume isn't called
    }

    private void refresh() {
        boolean enabled = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
            if (i.getPackageName().equals(getPackageName())) enabled = true;
        }
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = current != null && current.startsWith(getPackageName() + "/");
        markStep(step1, enabled, enabled ? "Done" : "Turn on Greeklish Keyboard in the list");
        markStep(step2, selected, selected ? "Done" : "Pick it as your keyboard");
        step2.setAlpha(enabled ? 1f : 0.5f);
    }

    private LinearLayout step(String num, String title, String sub, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (14 * dp), (int) (14 * dp), (int) (14 * dp), (int) (14 * dp));
        row.setBackground(stroke());
        TextView badge = text(num, 14, theme.onAccent, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(theme.accent, 14));
        row.addView(badge, new LinearLayout.LayoutParams((int) (28 * dp), (int) (28 * dp)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding((int) (14 * dp), 0, 0, 0);
        texts.addView(text(title, 16, theme.text, true));
        texts.addView(text(sub, 13, theme.textSecondary, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setOnClickListener(l);
        return row;
    }

    private void markStep(LinearLayout row, boolean done, String sub) {
        TextView badge = (TextView) row.getChildAt(0);
        LinearLayout texts = (LinearLayout) row.getChildAt(1);
        ((TextView) texts.getChildAt(1)).setText(sub);
        if (done) {
            badge.setText("✓");
            badge.setBackground(round(0xFF1E8E3E, 14));
            badge.setTextColor(0xFFFFFFFF);
        }
    }

    /** Normal / Large / Extra large, as three pill buttons; the chosen one is filled. */
    private View sizePicker() {
        LinearLayout row = new LinearLayout(this);
        String[] labels = {"Normal", "Large", "Extra large"};
        TextView[] buttons = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int size = i;
            TextView b = text(labels[i], 15, theme.text, true);
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(v -> {
                prefs.edit().putInt("size", size).apply();
                paintSizes(buttons);
            });
            buttons[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (44 * dp), 1f);
            if (i > 0) lp.leftMargin = (int) (8 * dp);
            row.addView(b, lp);
        }
        paintSizes(buttons);
        return row;
    }

    private void paintSizes(TextView[] buttons) {
        int chosen = prefs.getInt("size", 1);
        for (int i = 0; i < buttons.length; i++) {
            boolean on = i == chosen;
            GradientDrawable g = new GradientDrawable();
            g.setColor(on ? theme.accent : theme.key);
            g.setCornerRadius(22 * dp);
            if (!on) g.setStroke((int) Math.max(1, dp), theme.divider);
            buttons[i].setBackground(g);
            buttons[i].setTextColor(on ? theme.onAccent : theme.text);
        }
    }

    /** The photo theme uses see-through colours; the settings page needs a solid one. */
    private int pageBackground() {
        return theme.photo != null ? 0xFF161616 : theme.background;
    }

    private View heightSlider() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.addView(text("Fine-tune height", 15, theme.text, false), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView value = text(prefs.getInt("height_pct", 100) + "%", 15, theme.textSecondary, false);
        head.addView(value);
        box.addView(head);
        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(50);  // 80% .. 130%
        bar.setProgress(prefs.getInt("height_pct", 100) - 80);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar b, int p, boolean fromUser) {
                value.setText((80 + p) + "%");
                prefs.edit().putInt("height_pct", 80 + p).apply();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar b) {
            }
        });
        box.addView(bar);
        return box;
    }

    private View letterSizePicker() {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(text("Letter size", 15, theme.text, false), new LinearLayout.LayoutParams(0, -2, 1f));
        String[] labels = {"Normal", "Big"};
        TextView[] buttons = new TextView[2];
        for (int i = 0; i < 2; i++) {
            final boolean big = i == 1;
            TextView b = text(labels[i], 14, theme.text, true);
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(v -> {
                prefs.edit().putBoolean("big_letters", big).apply();
                paintPills(buttons, prefs.getBoolean("big_letters", false) ? 1 : 0);
            });
            buttons[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (84 * dp), (int) (38 * dp));
            lp.leftMargin = (int) (8 * dp);
            box.addView(b, lp);
        }
        paintPills(buttons, prefs.getBoolean("big_letters", false) ? 1 : 0);
        return box;
    }

    private void paintPills(TextView[] buttons, int chosen) {
        for (int i = 0; i < buttons.length; i++) {
            boolean on = i == chosen;
            GradientDrawable g = new GradientDrawable();
            g.setColor(on ? theme.accent : theme.key);
            g.setCornerRadius(22 * dp);
            if (!on) g.setStroke((int) Math.max(1, dp), theme.divider);
            buttons[i].setBackground(g);
            buttons[i].setTextColor(on ? theme.onAccent : theme.text);
        }
    }

    /** Round colour swatches for each theme; the last one picks a photo from the gallery. */
    private View themePicker() {
        swatches = new LinearLayout(this);
        int[] colors = {0, 0xFFF1F3F4, 0xFF3C4043, 0xFF000000, 0xFF1E3A66, 0xFF5C2340, 0xFF1E4733, 0};
        for (int i = 0; i < colors.length; i++) {
            final int id = i;
            TextView sw = new TextView(this);
            sw.setGravity(Gravity.CENTER);
            sw.setTextSize(16);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            if (i == Theme.WALLPAPER) g.setColors(new int[]{theme.key, theme.accent});
            else if (i == Theme.PHOTO) {
                g.setColor(theme.key);
                sw.setText("\uD83D\uDDBC");
            } else g.setColor(colors[i]);
            g.setStroke((int) Math.max(1, dp), theme.divider);
            sw.setBackground(g);
            sw.setOnClickListener(v -> {
                if (id == Theme.PHOTO) {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.setType("image/*");
                    startActivityForResult(Intent.createChooser(pick, "Pick a photo"), PICK_PHOTO);
                    return;
                }
                prefs.edit().putInt("theme", id).apply();
                recreate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (38 * dp), 1f);
            lp.rightMargin = (int) (6 * dp);
            swatches.addView(sw, lp);
        }
        markSwatch();
        return swatches;
    }

    private void markSwatch() {
        int chosen = prefs.getInt("theme", 0);
        for (int i = 0; i < swatches.getChildCount(); i++) {
            View v = swatches.getChildAt(i);
            v.setScaleX(i == chosen ? 1.12f : 1f);
            v.setScaleY(i == chosen ? 1.12f : 1f);
            GradientDrawable g = (GradientDrawable) v.getBackground();
            g.setStroke((int) ((i == chosen ? 3 : 1) * dp), i == chosen ? theme.accent : theme.divider);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PHOTO || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            savePhoto(data.getData());
            prefs.edit().putInt("theme", Theme.PHOTO).apply();
            recreate();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Couldn't use that photo", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Centre-crops the photo to the keyboard's shape and stores a copy inside the app. */
    private void savePhoto(android.net.Uri uri) throws Exception {
        android.graphics.Bitmap src;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 2;
            src = android.graphics.BitmapFactory.decodeStream(in, null, o);
        }
        if (src == null) throw new IllegalStateException("not an image");
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int kbH = KeysView.preferredHeight(this) + (int) (60 * dp);
        float targetRatio = (float) kbH / screenW;
        int cw = src.getWidth(), ch = (int) (cw * targetRatio);
        if (ch > src.getHeight()) {
            ch = src.getHeight();
            cw = (int) (ch / targetRatio);
        }
        android.graphics.Bitmap crop = android.graphics.Bitmap.createBitmap(src,
                (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
        int outW = Math.min(screenW, 1080);
        android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(crop, outW, (int) (outW * targetRatio), true);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(Theme.photoFile(this))) {
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out);
        }
    }

    /** Learned words, tap one to remove it. */
    private void showWords() {
        LearnedWords lw = new LearnedWords(this);
        java.util.List<String[]> all = lw.all();
        if (all.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Your words")
                    .setMessage("Nothing yet. When you keep a word in Latin (tap the left suggestion) "
                            + "or pick a different spelling, it shows up here.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] rows = new String[all.size()];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = all.get(i)[0].equals(all.get(i)[1]) ? all.get(i)[0] + "   (keep as typed)"
                    : all.get(i)[0] + "  \u2192  " + all.get(i)[1];
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Your words (tap to remove)")
                .setItems(rows, (d, which) -> {
                    lw.remove(all.get(which)[0]);
                    showWords();
                })
                .setNegativeButton("Close", null)
                .setNeutralButton("Remove all", (d, w) -> lw.clear())
                .show();
    }

    private View toggle(String label, String key) {
        return toggle(label, key, true);
    }

    private View toggle(String label, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(theme.text);
        s.setTextSize(16);
        s.setChecked(prefs.getBoolean(key, def));
        s.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
        s.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(key, on).apply());
        return s;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, (int) (h * dp)));
        return v;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * dp);
        return g;
    }

    private GradientDrawable stroke() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(theme.key);
        g.setCornerRadius(14 * dp);
        g.setStroke((int) Math.max(1, dp), theme.divider);
        return g;
    }
}
