package com.greeklish.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.PopupWindow;

import java.util.List;

/**
 * The key area, drawn on a Canvas in a Gboard-like style. Handles multi-touch typing,
 * key previews, long-press alternatives, backspace repeat / swipe-to-delete-words and
 * sliding on the space bar to move the cursor.
 */
public class KeysView extends View {

    public interface Listener {
        void onText(String text);

        void onKey(int code);

        void onModeLongPress();

        /** Swipe left on backspace: select {@code words} words before the cursor (0 = none). */
        void onDeleteSwipe(int words, boolean finished);

        /** Slide on space: move cursor by {@code chars}. */
        void onCursorMove(int chars);
    }

    public static final int SHIFT_OFF = 0, SHIFT_ONCE = 1, SHIFT_LOCKED = 2;

    private final Listener listener;
    private Theme theme;
    private List<List<Layouts.Key>> rows;
    private int shift = SHIFT_OFF;
    private String spaceLabel = "Greeklish";
    private int enterIcon = Icons.ENTER;
    private boolean haptics = true;

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float dp;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Touch state
    private static final class Touch {
        Layouts.Key key;
        float downX, downY;
        boolean longPressed, consumed, sliding;
        int popupIndex;
        int lastSteps;
    }

    private final SparseArray<Touch> touches = new SparseArray<>();
    private Layouts.Key pressedKey;
    private final PopupWindow preview;
    private final PreviewView previewView;
    private Runnable longPressRunnable, repeatRunnable;

    public KeysView(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;
        this.dp = ctx.getResources().getDisplayMetrics().density;
        textPaint.setTextAlign(Paint.Align.CENTER);
        borderPaint.setStyle(Paint.Style.STROKE);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        previewView = new PreviewView(ctx);
        preview = new PopupWindow(previewView);
        preview.setClippingEnabled(false);
        preview.setTouchable(false);
        preview.setBackgroundDrawable(null);
        setLayout(Layouts.letters(numberRow(ctx)));
    }

    public void setTheme(Theme t) {
        theme = t;
        previewView.theme = t;
        invalidate();
    }

    public void setLayout(List<List<Layouts.Key>> layout) {
        rows = layout;
        requestLayout();
        layoutKeys(getWidth(), getHeight());
        invalidate();
    }

    public List<List<Layouts.Key>> getLayout() {
        return rows;
    }

    public void setShift(int s) {
        shift = s;
        invalidate();
    }

    public int getShift() {
        return shift;
    }

    /** Name of the current mode, written on the space bar (Greeklish / Ελληνικά / English). */
    public void setSpaceLabel(String label) {
        spaceLabel = label;
        invalidate();
    }

    public void setEnterIcon(int icon) {
        enterIcon = icon;
        invalidate();
    }

    public void setHaptics(boolean on) {
        haptics = on;
    }

    /** Key height in dp: size preset (0 Normal, 1 Large, 2 Extra large) x fine-tune slider (80-130%). */
    public static float keyHeightDp(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences("keyboard", Context.MODE_PRIVATE);
        int size = p.getInt("size", 1);
        int base = size == 0 ? 44 : size == 2 ? 60 : 52;
        return base * p.getInt("height_pct", 100) / 100f;
    }

    public static boolean numberRow(Context ctx) {
        return ctx.getSharedPreferences("keyboard", Context.MODE_PRIVATE).getBoolean("number_row", false);
    }

    public static int preferredHeight(Context ctx) {
        // rows of (key + 10dp gap) + 6dp padding top and bottom; the number row is shorter
        float rows = 4 + (numberRow(ctx) ? NUMBER_ROW_WEIGHT : 0);
        return (int) (ctx.getResources().getDisplayMetrics().density * (rows * (keyHeightDp(ctx) + 10) + 12));
    }

    private static final float NUMBER_ROW_WEIGHT = 0.75f;

    private float textScale() {
        boolean big = getContext().getSharedPreferences("keyboard", Context.MODE_PRIVATE).getBoolean("big_letters", false);
        return Math.min(keyHeightDp(getContext()) / 44f, 1.25f) * (big ? 1.15f : 1f);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), preferredHeight(getContext()));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutKeys(w, h);
    }

    private void layoutKeys(int w, int h) {
        if (w == 0 || h == 0 || rows == null) return;
        float padX = 4 * dp, padY = 6 * dp, gapX = 5 * dp, gapY = 10 * dp;
        float weights = 0;
        for (List<Layouts.Key> row : rows) weights += rowWeight(row);
        float rowUnit = (h - 2 * padY) / weights;
        float y = padY;
        for (int r = 0; r < rows.size(); r++) {
            List<Layouts.Key> row = rows.get(r);
            float total = 0;
            for (Layouts.Key k : row) total += k.width;
            // A row never gets wider than the screen: wide rows shrink to fit, short rows are centred.
            float unit = (w - 2 * padX) / Math.max(10f, total);
            float x = padX + (Math.max(10f, total) - total) * unit / 2f;
            float rowH = rowUnit * rowWeight(row);
            for (Layouts.Key k : row) {
                k.left = x + gapX / 2;
                k.right = x + k.width * unit - gapX / 2;
                k.top = y + gapY / 2;
                k.bottom = y + rowH - gapY / 2;
                x += k.width * unit;
            }
            y += rowH;
        }
    }

    private static float rowWeight(List<Layouts.Key> row) {
        return !row.isEmpty() && row.get(0).numberRow ? NUMBER_ROW_WEIGHT : 1f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (theme == null) return;
        canvas.drawColor(theme.background);
        float radius = 7 * dp;
        for (List<Layouts.Key> row : rows) {
            for (Layouts.Key k : row) {
                boolean isEnter = k.code == Layouts.ENTER;
                int fill = isEnter ? theme.accent : (k.function || k.code == Layouts.MODE) ? theme.functionKey : theme.key;
                if (k == pressedKey && !isEnter) fill = theme.keyPressed;
                if (k.code == Layouts.SHIFT && shift != SHIFT_OFF) fill = theme.keyPressed;
                keyPaint.setColor(fill);
                rect.set(k.left, k.top, k.right, k.bottom);
                float r = isEnter ? Math.min(rect.height(), rect.width()) / 2 : radius;
                canvas.drawRoundRect(rect, r, r, keyPaint);
                if (theme.borders && !isEnter) {
                    borderPaint.setColor(theme.border);
                    borderPaint.setStrokeWidth(Math.max(1f, dp));
                    rect.inset(dp / 2, dp / 2);
                    canvas.drawRoundRect(rect, r, r, borderPaint);
                }
                drawKeyContent(canvas, k);
            }
        }
    }

    private void drawKeyContent(Canvas canvas, Layouts.Key k) {
        float cx = (k.left + k.right) / 2, cy = (k.top + k.bottom) / 2;
        float s = textScale();
        float icon = 22 * dp * Math.min(s, 1.25f);
        switch (k.code) {
            case Layouts.SHIFT:
                Icons.draw(canvas, shift == SHIFT_LOCKED ? Icons.SHIFT_LOCK : shift == SHIFT_ONCE ? Icons.SHIFT_ON : Icons.SHIFT,
                        cx, cy, icon, theme.text);
                return;
            case Layouts.DELETE:
                Icons.draw(canvas, Icons.BACKSPACE, cx, cy, icon, theme.text);
                return;
            case Layouts.ENTER:
                Icons.draw(canvas, enterIcon, cx, cy, icon, theme.onAccent);
                return;
            case Layouts.MODE:
                Icons.draw(canvas, Icons.GLOBE, cx, cy, icon * 0.95f, theme.text);
                return;
            case Layouts.SPACE:
                textPaint.setColor(theme.textSecondary);
                textPaint.setTextSize(14 * dp);
                canvas.drawText(spaceLabel, cx, cy + 4.5f * dp, textPaint);
                return;
            default:
                break;
        }
        String label = displayLabel(k);
        boolean small = k.function || label.length() > 1;
        textPaint.setColor(theme.text);
        textPaint.setTextSize((small ? 15 : k.numberRow ? 18 : 22) * dp * s);
        canvas.drawText(label, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint);
        if (!k.hint.isEmpty()) {
            hintPaint.setColor(theme.textSecondary);
            hintPaint.setTextSize(12 * dp);
            canvas.drawText(k.hint, k.right - 5 * dp, k.top + 13 * dp, hintPaint);
        }
    }

    private String displayLabel(Layouts.Key k) {
        if (k.code == Layouts.CODE_CHAR && shift != SHIFT_OFF && k.label.length() == 1
                && Character.isLetter(k.label.charAt(0))) {
            return k.label.toUpperCase(java.util.Locale.ROOT);
        }
        return k.label;
    }

    // ---------------------------------------------------------------- touch

    private Layouts.Key keyAt(float x, float y) {
        Layouts.Key best = null;
        float bestDist = Float.MAX_VALUE;
        for (List<Layouts.Key> row : rows) {
            for (Layouts.Key k : row) {
                // nearest key centre, weighted to rows: forgiving like Gboard's gaps
                if (y < k.top - 6 * dp || y > k.bottom + 6 * dp) continue;
                float dx = Math.max(0, Math.max(k.left - x, x - k.right));
                if (dx < bestDist) {
                    bestDist = dx;
                    best = k;
                }
            }
        }
        return best;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                // A second finger landing commits the first finger's character (fast two-thumb typing).
                for (int i = 0; i < touches.size(); i++) {
                    Touch other = touches.valueAt(i);
                    if (!other.consumed && !other.longPressed && !other.sliding && other.key != null
                            && other.key.code == Layouts.CODE_CHAR) {
                        fire(other.key);
                        other.consumed = true;
                    }
                }
                Touch t = new Touch();
                t.downX = e.getX(idx);
                t.downY = e.getY(idx);
                t.key = keyAt(t.downX, t.downY);
                touches.put(id, t);
                if (t.key != null) press(t, id);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    Touch t = touches.get(e.getPointerId(i));
                    if (t != null) move(t, e.getX(i), e.getY(i));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                Touch t = touches.get(id);
                touches.remove(id);
                if (t != null) release(t);
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                touches.clear();
                cancelTimers();
                hidePreview();
                pressedKey = null;
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    private void press(Touch t, int pointerId) {
        pressedKey = t.key;
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (t.key.code == Layouts.CODE_CHAR) showPreview(t.key, null, 0);
        invalidate();
        cancelTimers();
        final Layouts.Key key = t.key;
        if (key.code == Layouts.DELETE) {
            listener.onKey(Layouts.DELETE);
            t.consumed = true;
            repeatRunnable = new Runnable() {
                int count = 0;

                @Override
                public void run() {
                    if (t.sliding) return;
                    listener.onKey(Layouts.DELETE);
                    count++;
                    handler.postDelayed(this, count > 15 ? 30 : 60);
                }
            };
            handler.postDelayed(repeatRunnable, 400);
            return;
        }
        if (!key.popup.isEmpty() || key.code == Layouts.MODE || ",".equals(key.label)) {
            longPressRunnable = () -> {
                if (t.sliding || t.consumed) return;
                t.longPressed = true;
                if (key.code == Layouts.MODE) {
                    hidePreview();
                    listener.onModeLongPress();
                    t.consumed = true;
                } else if (",".equals(key.label)) {
                    hidePreview();
                    listener.onKey(GreeklishIME.ACTION_EMOJI);
                    t.consumed = true;
                } else {
                    t.popupIndex = 0;
                    showPreview(key, key.popup, 0);
                }
                if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            };
            handler.postDelayed(longPressRunnable, 350);
        }
    }

    private void move(Touch t, float x, float y) {
        if (t.key == null || t.consumed && !t.sliding && t.key.code != Layouts.DELETE) return;
        float dx = x - t.downX;
        if (t.key.code == Layouts.SPACE) {
            float step = 12 * dp;
            if (!t.sliding && Math.abs(dx) > 16 * dp) {
                t.sliding = true;
                cancelTimers();
            }
            if (t.sliding) {
                int steps = (int) (dx / step);
                if (steps != t.lastSteps) {
                    listener.onCursorMove(steps - t.lastSteps);
                    t.lastSteps = steps;
                }
            }
            return;
        }
        if (t.key.code == Layouts.DELETE) {
            if (!t.sliding && dx < -20 * dp) {
                t.sliding = true;
                cancelTimers();
            }
            if (t.sliding) {
                int words = Math.max(0, (int) ((-dx - 20 * dp) / (32 * dp)) + 1);
                if (dx > -20 * dp) words = 0;
                if (words != t.lastSteps) {
                    t.lastSteps = words;
                    listener.onDeleteSwipe(words, false);
                }
            }
            return;
        }
        if (t.longPressed && !t.key.popup.isEmpty()) {
            int n = t.key.popup.length();
            float cell = 36 * dp;
            int index = (int) Math.floor((dx + cell / 2) / cell);
            index = Math.max(0, Math.min(n - 1, index));
            if (index != t.popupIndex) {
                t.popupIndex = index;
                showPreview(t.key, t.key.popup, index);
            }
            return;
        }
        // Finger slid onto another key before lifting: follow it, like Gboard.
        Layouts.Key k = keyAt(x, y);
        if (k != null && k != t.key && t.key.code == Layouts.CODE_CHAR && k.code == Layouts.CODE_CHAR) {
            cancelTimers();
            t.key = k;
            pressedKey = k;
            showPreview(k, null, 0);
            invalidate();
            press(t, 0);
        }
    }

    private void release(Touch t) {
        cancelTimers();
        hidePreview();
        if (pressedKey == t.key) pressedKey = null;
        invalidate();
        if (t.key == null) return;
        if (t.sliding) {
            if (t.key.code == Layouts.DELETE) listener.onDeleteSwipe(t.lastSteps, true);
            return;
        }
        if (t.consumed) return;
        if (t.longPressed && !t.key.popup.isEmpty()) {
            listener.onText(applyShift(String.valueOf(t.key.popup.charAt(t.popupIndex))));
            return;
        }
        fire(t.key);
    }

    private void fire(Layouts.Key k) {
        if (k.code == Layouts.CODE_CHAR) {
            listener.onText(applyShift(k.label));
        } else {
            listener.onKey(k.code);
        }
    }

    private String applyShift(String s) {
        return shift != SHIFT_OFF ? s.toUpperCase(java.util.Locale.ROOT) : s;
    }

    private void cancelTimers() {
        if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
        if (repeatRunnable != null) handler.removeCallbacks(repeatRunnable);
        longPressRunnable = null;
        repeatRunnable = null;
    }

    // ---------------------------------------------------------------- previews

    private void showPreview(Layouts.Key k, String options, int selected) {
        if (getWindowToken() == null) return;
        int[] loc = new int[2];
        getLocationInWindow(loc);
        float keyW = k.right - k.left;
        previewView.label = displayLabel(k);
        previewView.options = options == null ? null : applyShift(options);
        previewView.selected = selected;
        int cell = (int) (36 * dp);
        int w = options == null ? (int) (keyW + 12 * dp) : Math.max((int) keyW, cell * options.length() + (int) (12 * dp));
        int h = (int) ((k.bottom - k.top) * 2.0f);
        int x = loc[0] + (int) (options == null ? k.left - 6 * dp : k.left - 6 * dp);
        x = Math.max(0, Math.min(x, loc[0] + getWidth() - w));
        int y = loc[1] + (int) k.bottom - h;
        previewView.keyHeight = k.bottom - k.top;
        if (preview.isShowing()) {
            preview.update(x, y, w, h);
            previewView.invalidate();
        } else {
            preview.setWidth(w);
            preview.setHeight(h);
            preview.showAtLocation(this, Gravity.NO_GRAVITY, x, y);
        }
    }

    private void hidePreview() {
        if (preview.isShowing()) preview.dismiss();
    }

    /** Bubble above a pressed key, or the row of long-press alternatives. */
    private final class PreviewView extends View {
        Theme theme;
        String label = "", options;
        int selected;
        float keyHeight;
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);

        PreviewView(Context ctx) {
            super(ctx);
            txt.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (theme == null) return;
            float w = getWidth(), h = getHeight();
            bg.setColor(theme.popup);
            bg.setShadowLayer(4 * dp, 0, 1 * dp, 0x40000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            float top = 2 * dp, bottom = h - keyHeight - 2 * dp;
            if (options == null) {
                c.drawRoundRect(new RectF(2 * dp, top, w - 2 * dp, bottom + keyHeight * 0.15f), 10 * dp, 10 * dp, bg);
                txt.setColor(theme.text);
                txt.setTextSize(28 * dp);
                c.drawText(label, w / 2, (top + bottom) / 2 - (txt.descent() + txt.ascent()) / 2, txt);
                return;
            }
            c.drawRoundRect(new RectF(2 * dp, top, w - 2 * dp, bottom), 10 * dp, 10 * dp, bg);
            float cell = 36 * dp, x0 = 6 * dp;
            txt.setTextSize(22 * dp);
            for (int i = 0; i < options.length(); i++) {
                float cx = x0 + cell * i + cell / 2;
                if (i == selected) {
                    Paint sel = new Paint(Paint.ANTI_ALIAS_FLAG);
                    sel.setColor(theme.accent);
                    c.drawRoundRect(new RectF(cx - cell / 2 + 2 * dp, top + 6 * dp, cx + cell / 2 - 2 * dp, bottom - 6 * dp),
                            8 * dp, 8 * dp, sel);
                    txt.setColor(theme.onAccent);
                } else {
                    txt.setColor(theme.text);
                }
                c.drawText(String.valueOf(options.charAt(i)), cx, (top + bottom) / 2 - (txt.descent() + txt.ascent()) / 2, txt);
            }
        }
    }
}
