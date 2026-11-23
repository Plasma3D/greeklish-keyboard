package com.greeklish.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The row above the keys. Idle: toolbar icons (emoji, clipboard, settings, mode).
 * While typing: three suggestions, with the Greek conversion bold in the middle.
 */
public class StripView extends View {

    public interface Listener {
        void onToolbar(int action);

        void onSuggestion(String text, int slot);
    }

    public static final int TOOL_EMOJI = 1, TOOL_CLIPBOARD = 2, TOOL_SETTINGS = 3, TOOL_MODE = 4, TOOL_SEARCH_EXIT = 5;
    /** Slot number passed to onSuggestion for an emoji search result. */
    public static final int SLOT_EMOJI = 10;
    private static final int[] TOOLS = {TOOL_EMOJI, TOOL_CLIPBOARD, TOOL_SETTINGS, TOOL_MODE};

    private final Listener listener;
    private final float dp;
    private Theme theme;
    private String[] suggestions;  // [left, middle, right] or null for toolbar
    private int activeTool = 0;
    private boolean greeklishMode = true;
    private int pressedSlot = -1;
    private boolean middleLearned = false;
    private String searchQuery;               // non-null while searching emoji
    private java.util.List<String> results = new java.util.ArrayList<>();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    public StripView(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;
        dp = ctx.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setTheme(Theme t) {
        theme = t;
        invalidate();
    }

    public void showSuggestions(String left, String middle, String right) {
        showSuggestions(left, middle, right, false);
    }

    /** {@code learned}: the middle word is one the user taught; drawn with a small dot. */
    public void showSuggestions(String left, String middle, String right, boolean learned) {
        suggestions = new String[]{left, middle, right};
        middleLearned = learned;
        invalidate();
    }

    public void showToolbar() {
        suggestions = null;
        invalidate();
    }

    /** Emoji search: the query on the left, matching emojis to its right. null query ends search. */
    public void showEmojiSearch(String query, java.util.List<String> found) {
        searchQuery = query;
        results = found == null ? new java.util.ArrayList<>() : found;
        invalidate();
    }

    private float queryWidth() {
        return getWidth() * 0.4f;
    }

    public void setActiveTool(int tool) {
        activeTool = tool;
        invalidate();
    }

    public void setGreeklishMode(boolean on) {
        greeklishMode = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (48 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (theme == null) return;
        c.drawColor(theme.background);
        float w = getWidth(), h = getHeight();
        if (searchQuery != null) {
            float qw = queryWidth();
            fill.setColor(theme.functionKey);
            c.drawRoundRect(new RectF(6 * dp, 6 * dp, qw - 4 * dp, h - 6 * dp), 18 * dp, 18 * dp, fill);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(15 * dp);
            text.setColor(searchQuery.isEmpty() ? theme.textSecondary : theme.text);
            String q = searchQuery.isEmpty() ? "\uD83D\uDD0D Search" : "\uD83D\uDD0D " + searchQuery + "\u258F";
            CharSequence fitted = TextUtils.ellipsize(q, text, qw - 24 * dp, TextUtils.TruncateAt.START);
            c.drawText(fitted, 0, fitted.length(), 14 * dp, h / 2 - (text.descent() + text.ascent()) / 2, text);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(24 * dp);
            float cell = 44 * dp;
            for (int i = 0; i < results.size() && qw + cell * (i + 1) <= w; i++) {
                if (i == pressedSlot) {
                    fill.setColor(theme.keyPressed);
                    c.drawRoundRect(new RectF(qw + cell * i + 2 * dp, 4 * dp, qw + cell * (i + 1) - 2 * dp, h - 4 * dp), 8 * dp, 8 * dp, fill);
                }
                c.drawText(results.get(i), qw + cell * i + cell / 2, h / 2 - (text.descent() + text.ascent()) / 2, text);
            }
            if (results.isEmpty() && !searchQuery.isEmpty()) {
                text.setTextSize(13 * dp);
                text.setColor(theme.textSecondary);
                c.drawText("No matches", qw + (w - qw) / 2, h / 2 - (text.descent() + text.ascent()) / 2, text);
            }
            return;
        }
        if (suggestions != null && activeTool == 0) {
            float slot = w / 3f;
            for (int i = 0; i < 3; i++) {
                String s = suggestions[i];
                float cx = slot * i + slot / 2;
                if (i == pressedSlot && s != null) {
                    fill.setColor(theme.keyPressed);
                    c.drawRoundRect(new RectF(slot * i + 4 * dp, 4 * dp, slot * (i + 1) - 4 * dp, h - 4 * dp), 8 * dp, 8 * dp, fill);
                }
                if (i > 0) {
                    fill.setColor(theme.divider);
                    c.drawRect(slot * i, h * 0.3f, slot * i + Math.max(1, dp * 0.6f), h * 0.7f, fill);
                }
                if (s == null) continue;
                text.setColor(i == 1 ? theme.text : theme.textSecondary);
                text.setTextSize(17 * dp);
                text.setTypeface(Typeface.create("sans-serif", i == 1 ? Typeface.BOLD : Typeface.NORMAL));
                CharSequence fitted = TextUtils.ellipsize(s, text, slot - 24 * dp, TextUtils.TruncateAt.END);
                float baseline = h / 2 - (text.descent() + text.ascent()) / 2;
                c.drawText(fitted, 0, fitted.length(), cx, baseline, text);
                if (i == 1 && middleLearned) {
                    fill.setColor(theme.accent);
                    float tw = text.measureText(fitted, 0, fitted.length());
                    c.drawCircle(cx + tw / 2 + 7 * dp, baseline - 9 * dp, 3 * dp, fill);
                }
            }
            return;
        }
        float slot = w / TOOLS.length;
        for (int i = 0; i < TOOLS.length; i++) {
            float cx = slot * i + slot / 2, cy = h / 2;
            int tool = TOOLS[i];
            if (tool == activeTool || i == pressedSlot) {
                fill.setColor(theme.keyPressed);
                c.drawRoundRect(new RectF(cx - 22 * dp, cy - 16 * dp, cx + 22 * dp, cy + 16 * dp), 16 * dp, 16 * dp, fill);
            }
            int col = theme.textSecondary;
            switch (tool) {
                case TOOL_EMOJI:
                    Icons.draw(c, Icons.EMOJI, cx, cy, 22 * dp, col);
                    break;
                case TOOL_CLIPBOARD:
                    Icons.draw(c, Icons.CLIPBOARD, cx, cy, 22 * dp, col);
                    break;
                case TOOL_SETTINGS:
                    Icons.draw(c, Icons.SETTINGS, cx, cy, 22 * dp, col);
                    break;
                case TOOL_MODE:
                    text.setColor(col);
                    text.setTextSize(14 * dp);
                    text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    c.drawText(greeklishMode ? "ΕΛ" : "EN", cx, cy - (text.descent() + text.ascent()) / 2, text);
                    break;
                default:
                    break;
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float w = getWidth();
        if (searchQuery != null) {
            float qw = queryWidth();
            int idx = e.getX() < qw ? -2 : (int) ((e.getX() - qw) / (44 * dp));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressedSlot = idx;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    pressedSlot = -1;
                    invalidate();
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (idx == -2) listener.onToolbar(TOOL_SEARCH_EXIT);
                    else if (idx >= 0 && idx < results.size()) listener.onSuggestion(results.get(idx), SLOT_EMOJI);
                    return true;
                default:
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) pressedSlot = -1;
                    return true;
            }
        }
        boolean suggesting = suggestions != null && activeTool == 0;
        int count = suggesting ? 3 : TOOLS.length;
        int slot = Math.max(0, Math.min(count - 1, (int) (e.getX() / (w / count))));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedSlot = slot;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                pressedSlot = -1;
                invalidate();
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (suggesting) {
                    if (suggestions[slot] != null) listener.onSuggestion(suggestions[slot], slot);
                } else {
                    listener.onToolbar(TOOLS[slot]);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedSlot = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
