package com.greeklish.keyboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The keyboard. The word being typed is kept in Latin ({@link #word}) and shown in the
 * text field as Greek "composing" text (underlined). Space commits it; backspace right
 * after a commit brings back what you typed.
 */
public class GreeklishIME extends InputMethodService
        implements KeysView.Listener, StripView.Listener, EmojiPanel.Listener, ClipboardPanel.Listener {

    private static final String TAG = "GreeklishIME";
    public static final int ACTION_EMOJI = -100;
    private static final int PANEL_KEYS = 0, PANEL_EMOJI = 1, PANEL_CLIPBOARD = 2;

    private SharedPreferences prefs;
    private LearnedWords learned;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Converter converter;
    private volatile Autocorrect autocorrect;
    private EnglishDetector english;
    private final java.util.ArrayList<Double> sentence = new java.util.ArrayList<>();  // English scores so far
    private Double prevScore;

    private boolean looksEnglish(String latin) {
        return english != null && prefs.getBoolean("keep_english", true)
                && EnglishDetector.isEnglish(english.score(latin), sentence);
    }

    /** AI result with autocorrect applied: a non-word becomes the closest real word; the AI's version goes right. */
    private Converter.Result fix(Converter.Result r) {
        Autocorrect a = autocorrect;
        if (a == null || !prefs.getBoolean("autocorrect", true)) return r;
        String f = a.correct(r.best, r.input);
        return f == null ? r : new Converter.Result(r.input, f, r.best);
    }

    private Theme theme;
    private StripView strip;
    private KeysView keys;
    private EmojiPanel emoji;
    private ClipboardPanel clipboard;
    private int panel = PANEL_KEYS;
    private StringBuilder emojiQuery;  // non-null while searching emoji with the letter keys
    private String builtFor = "";

    // Typing state
    private final StringBuilder word = new StringBuilder();     // Latin letters of the current word
    private boolean keepLatin = false;                          // user chose to keep this word as typed
    private final ArrayDeque<String> context = new ArrayDeque<>(); // previous words, as typed
    private Converter.Result lastResult;
    private String lastShown = "";
    private int requestId = 0;

    // Undo of the last automatic conversion
    private String undoLatin, undoCommitted;
    // Previous automatically converted word, so the next word can fix it
    private String prevAutoLatin, prevAutoCommitted = "";

    // Field state
    private boolean greeklishMode = true;
    private boolean fieldAllowsConversion = true;
    private int enterAction = EditorInfo.IME_ACTION_UNSPECIFIED;
    private boolean enterIsNewline = true;
    private long lastShiftTap = 0, lastSpaceTime = 0;
    private int selStart = 0, selEnd = 0;
    private int swipeAnchor = -1;
    private String swipeBefore = "";

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("keyboard", MODE_PRIVATE);
        greeklishMode = prefs.getBoolean("greeklish", true);
        learned = new LearnedWords(this);
        worker.execute(() -> {
            try {
                long t0 = SystemClock.elapsedRealtime();
                english = new EnglishDetector(this);
                autocorrect = new Autocorrect(this);
                converter = new Converter(this);
                Log.i(TAG, "model loaded in " + (SystemClock.elapsedRealtime() - t0) + " ms");
            } catch (Exception e) {
                Log.e(TAG, "could not load model", e);
            }
        });
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.addPrimaryClipChangedListener(() -> {
            if (!prefs.getBoolean("clipboard_history", true)) return;
            try {
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return;
                CharSequence text = clip.getItemAt(0).coerceToText(this);
                if (text == null) return;
                ClipboardPanel target = clipboard != null ? clipboard : new ClipboardPanel(this, this);
                target.add(text.toString());
            } catch (Exception ignored) {
                // Android only lets the active keyboard read the clipboard; ignore otherwise.
            }
        });
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        if (converter != null) converter.close();
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        theme = new Theme(this);
        strip = new StripView(this, this);
        keys = new KeysView(this, this);
        emoji = new EmojiPanel(this, this);
        clipboard = new ClipboardPanel(this, this);
        strip.setTheme(theme);
        keys.setTheme(theme);
        emoji.setTheme(theme);
        clipboard.setTheme(theme);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        if (theme.photo != null) {
            // Your photo behind everything; the views on top use translucent colours.
            root.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), theme.photo));
        } else {
            root.setBackgroundColor(theme.background);
        }
        root.addView(strip);
        FrameLayout body = new FrameLayout(this);
        body.addView(keys);
        body.addView(emoji, new FrameLayout.LayoutParams(-1, -1));
        body.addView(clipboard, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, KeysView.preferredHeight(this)));
        // Leave room for the system's back / switch-keyboard buttons below the keys,
        // instead of letting them draw on top of ?123 and Enter.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            applyBottomPadding(v, insets);
            return insets;
        });
        root.post(() -> applyBottomPadding(root, root.getRootWindowInsets()));
        builtFor = lookSignature();
        showPanel(PANEL_KEYS);
        return root;
    }

    /**
     * Space under the keys for the system's back / switch-keyboard buttons. Some phones
     * report 0 to keyboards, so fall back to Android's own height for that bar.
     */
    private void applyBottomPadding(View v, WindowInsets insets) {
        int bottom = 0;
        if (insets != null) {
            bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.tappableElement()).bottom
                    : insets.getSystemWindowInsetBottom();
        }
        if (bottom == 0 && Build.VERSION.SDK_INT >= 29) {
            bottom = systemDimen("navigation_bar_frame_height");
            if (bottom == 0) bottom = systemDimen("navigation_bar_height");
        }
        int extra = (int) (6 * getResources().getDisplayMetrics().density);
        if (v.getPaddingBottom() != bottom + extra) v.setPadding(0, 0, 0, bottom + extra);
    }

    /** Everything in the settings that changes how the keyboard looks. */
    private String lookSignature() {
        boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return prefs.getInt("theme", 0) + "/" + prefs.getBoolean("key_borders", false) + "/" + dark + "/"
                + Theme.photoFile(this).lastModified() + "/" + KeysView.preferredHeight(this) + "/"
                + prefs.getBoolean("big_letters", false);
    }

    @SuppressWarnings("DiscouragedApi")
    private int systemDimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (theme == null || !builtFor.equals(lookSignature())) {
            setInputView(onCreateInputView());  // theme, size or number-row setting changed
        }
        resetWord();
        context.clear();
        undoLatin = null;
        learned.reload();  // the list may have been edited in Settings
        if (emojiQuery != null) endEmojiSearch(false);
        selStart = info.initialSelStart;
        selEnd = info.initialSelEnd;

        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        boolean numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
                || cls == InputType.TYPE_CLASS_DATETIME;
        boolean secret = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        boolean address = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_URI;
        fieldAllowsConversion = cls == InputType.TYPE_CLASS_TEXT && !secret && !address;

        // Same rule as Gboard: an action the app asks for (search, go, send, next, done) wins,
        // even in multi-line boxes like Google's search bar. Otherwise Enter is a plain Enter press.
        enterAction = info.actionLabel != null ? info.actionId : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        enterIsNewline = (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                || enterAction == EditorInfo.IME_ACTION_NONE || enterAction == EditorInfo.IME_ACTION_UNSPECIFIED;
        int icon = Icons.ENTER;
        if (!enterIsNewline) {
            switch (enterAction) {
                case EditorInfo.IME_ACTION_SEND: icon = Icons.SEND; break;
                case EditorInfo.IME_ACTION_SEARCH: icon = Icons.SEARCH; break;
                case EditorInfo.IME_ACTION_GO:
                case EditorInfo.IME_ACTION_NEXT: icon = Icons.NEXT; break;
                case EditorInfo.IME_ACTION_DONE: icon = Icons.DONE; break;
                default: break;
            }
        }
        keys.setEnterIcon(icon);
        keys.setHaptics(prefs.getBoolean("vibrate", true));
        keys.setLayout(numeric ? Layouts.symbols() : Layouts.letters(KeysView.numberRow(this)));
        keys.setShift(KeysView.SHIFT_OFF);
        updateModeViews();
        showPanel(PANEL_KEYS);
        strip.showToolbar();
        updateAutoCaps();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        commitWord(false);
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        selStart = newSelStart;
        selEnd = newSelEnd;
        if (word.length() == 0) return;
        InputConnection ic = getCurrentInputConnection();
        // Some apps briefly drop the composing (underlined) region. If our word is still right
        // before the cursor, take it back instead of giving up on the word.
        if (newSelStart == newSelEnd && candidatesEnd == -1 && ic != null && !lastShown.isEmpty()) {
            CharSequence before = ic.getTextBeforeCursor(lastShown.length(), 0);
            if (before != null && before.toString().equals(lastShown)) {
                ic.setComposingRegion(newSelEnd - lastShown.length(), newSelEnd);
                return;
            }
        }
        // The user tapped somewhere else in the text: keep what is shown and start fresh.
        if (newSelStart != newSelEnd || newSelEnd != candidatesEnd) {
            if (ic != null) ic.finishComposingText();
            resetWord();
            context.clear();
            undoLatin = null;
            strip.showToolbar();
        }
    }

    private boolean converting() {
        return greeklishMode && fieldAllowsConversion;
    }

    // ------------------------------------------------------------------ keys

    @Override
    public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        undoLatin = null;
        if (emojiQuery != null) {
            emojiQuery.append(text);
            updateEmojiSearch();
            if (keys.getShift() == KeysView.SHIFT_ONCE) keys.setShift(KeysView.SHIFT_OFF);
            return;
        }
        if (converting()) {
            word.append(text);
            refreshComposing();
        } else {
            ic.commitText(text, 1);
        }
        if (keys.getShift() == KeysView.SHIFT_ONCE) keys.setShift(KeysView.SHIFT_OFF);
    }

    @Override
    public void onKey(int code) {
        InputConnection ic = getCurrentInputConnection();
        if (emojiQuery != null) {
            if (code == Layouts.DELETE) {
                if (emojiQuery.length() > 0) emojiQuery.setLength(emojiQuery.length() - 1);
                updateEmojiSearch();
                return;
            }
            if (code == Layouts.SPACE) {
                emojiQuery.append(' ');
                updateEmojiSearch();
                return;
            }
            if (code == Layouts.ENTER || code == Layouts.LETTERS || code == Layouts.MODE || code == ACTION_EMOJI) {
                endEmojiSearch(true);
                return;
            }
        }
        switch (code) {
            case Layouts.SHIFT: {
                long now = SystemClock.uptimeMillis();
                int s = keys.getShift();
                if (s == KeysView.SHIFT_OFF) s = KeysView.SHIFT_ONCE;
                else if (s == KeysView.SHIFT_ONCE && now - lastShiftTap < 350) s = KeysView.SHIFT_LOCKED;
                else s = KeysView.SHIFT_OFF;
                lastShiftTap = now;
                keys.setShift(s);
                break;
            }
            case Layouts.DELETE:
                handleDelete(ic);
                break;
            case Layouts.SPACE:
                handleSpace(ic);
                break;
            case Layouts.ENTER:
                commitWord(false);
                if (ic == null) break;
                if (!enterIsNewline) {
                    ic.performEditorAction(enterAction);
                } else if ((getCurrentInputEditorInfo().inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) {
                    ic.commitText("\n", 1);
                } else {
                    // Single-line box without an action (e.g. web pages): let the app handle Enter.
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                }
                updateAutoCaps();
                break;
            case Layouts.SYMBOLS:
                keys.setLayout(Layouts.symbols());
                break;
            case Layouts.SYMBOLS2:
                keys.setLayout(Layouts.symbols2());
                break;
            case Layouts.LETTERS:
                keys.setLayout(Layouts.letters(KeysView.numberRow(this)));
                showPanel(PANEL_KEYS);
                updateAutoCaps();
                break;
            case Layouts.MODE:
                commitWord(false);
                greeklishMode = !greeklishMode;
                prefs.edit().putBoolean("greeklish", greeklishMode).apply();
                updateModeViews();
                break;
            case ACTION_EMOJI:
                commitWord(false);
                showPanel(PANEL_EMOJI);
                break;
            default:
                break;
        }
    }

    private void handleDelete(InputConnection ic) {
        if (ic == null) return;
        if (word.length() > 0) {
            word.setLength(word.length() - Character.charCount(word.codePointBefore(word.length())));
            if (word.length() == 0) {
                ic.commitText("", 1);
                resetWord();
                strip.showToolbar();
            } else {
                refreshComposing();
            }
            return;
        }
        // Backspace right after an automatic conversion: bring back exactly what was typed.
        if (undoLatin != null) {
            CharSequence before = ic.getTextBeforeCursor(undoCommitted.length(), 0);
            if (before != null && before.toString().equals(undoCommitted)) {
                ic.deleteSurroundingText(undoCommitted.length(), 0);
                word.append(undoLatin);
                keepLatin = true;
                if (!context.isEmpty()) context.removeLast();
                undoLatin = null;
                ic.setComposingText(word, 1);
                lastShown = word.toString();
                refreshComposing();
                return;
            }
            undoLatin = null;
        }
        if (selStart != selEnd) {
            ic.commitText("", 1);
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
        }
        if (!context.isEmpty()) context.clear();
        updateAutoCaps();
    }

    private void handleSpace(InputConnection ic) {
        if (ic == null) return;
        long now = SystemClock.uptimeMillis();
        if (word.length() == 0 && now - lastSpaceTime < 600) {
            // Double space -> ". " (like Gboard)
            CharSequence before = ic.getTextBeforeCursor(2, 0);
            if (before != null && before.length() == 2 && before.charAt(1) == ' '
                    && Character.isLetterOrDigit(before.charAt(0))) {
                ic.deleteSurroundingText(1, 0);
                ic.commitText(". ", 1);
                lastSpaceTime = 0;
                undoLatin = null;
                updateAutoCaps();
                return;
            }
        }
        lastSpaceTime = now;
        if (word.length() > 0) {
            commitWord(true);
        } else {
            undoLatin = null;
            ic.commitText(" ", 1);
            updateAutoCaps();
        }
    }

    /** Commits the current word (converted unless the user chose Latin). */
    private void commitWord(boolean addSpace) {
        commitChoice(null, addSpace);
    }

    private void commitChoice(String choice, boolean addSpace) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || word.length() == 0) {
            if (addSpace && ic != null) ic.commitText(" ", 1);
            return;
        }
        String latin = word.toString();
        String taught = learned.get(latin);
        if (context.isEmpty()) {
            sentence.clear();
            prevScore = null;
        }
        boolean isEnglish = choice == null && !keepLatin && taught == null && looksEnglish(latin);
        String out = choice != null ? choice : keepLatin || isEnglish ? latin : taught != null ? taught : convertNow(latin);
        // Kept in Latin after undoing a conversion: remember that for next time.
        if (choice == null && keepLatin && prefs.getBoolean("learn_words", true)) learned.learn(latin, latin);
        String committed = out + (addSpace ? " " : "");
        ic.commitText(committed, 1);
        boolean auto = choice == null && !keepLatin && taught == null && !isEnglish;
        double score = english == null ? -6 : english.score(latin);
        if (isEnglish && addSpace) revertPreviousToEnglish(ic, score, committed);
        else if (auto && addSpace) fixPreviousWord(ic, latin, committed);
        sentence.add(score);
        if (EnglishDetector.endsSentence(latin)) {
            sentence.clear();
            prevScore = null;
        } else {
            prevScore = score;
        }
        boolean converted = !out.equals(latin);
        undoLatin = converted && addSpace ? latin : null;
        undoCommitted = committed;
        prevAutoLatin = auto && addSpace ? latin : null;
        prevAutoCommitted = committed;
        context.addLast(latin);
        while (context.size() > 6) context.removeFirst();
        resetWord();
        strip.showToolbar();
        updateAutoCaps();
    }

    /**
     * Once the next word is known, re-check the previous automatically converted word with
     * the extra context and quietly fix it (e.g. "ti" -> τη, then "kaneis" makes it τι).
     */
    private void fixPreviousWord(InputConnection ic, String latin, String committed) {
        Converter c = converter;
        if (c == null || prevAutoLatin == null || context.isEmpty()
                || !context.peekLast().equals(prevAutoLatin)) return;
        String both = prevAutoCommitted + committed;
        CharSequence before = ic.getTextBeforeCursor(both.length(), 0);
        if (before == null || !before.toString().equals(both)) return;
        try {
            String[] words = c.convertWords(contextString() + " " + latin);
            if (words.length < 2) return;
            String fixed = words[words.length - 2];
            Autocorrect a = autocorrect;
            if (a != null && prefs.getBoolean("autocorrect", true)) {
                String corrected = a.correct(fixed, prevAutoLatin);
                if (corrected != null) fixed = corrected;
            }
            String oldOut = prevAutoCommitted.substring(0, prevAutoCommitted.length() - 1);
            // Only small, safe fixes (accent / one letter); never drop or add letters.
            if (fixed.equals(oldOut) || fixed.length() != oldOut.length() || differences(fixed, oldOut) > 2) return;
            ic.beginBatchEdit();
            ic.deleteSurroundingText(both.length(), 0);
            ic.commitText(fixed + " " + committed, 1);
            ic.endBatchEdit();
        } catch (Exception e) {
            Log.e(TAG, "re-check failed", e);
        }
    }

    /** "do" became δω, but "you" shows the sentence is English: put "do" back as typed. */
    private void revertPreviousToEnglish(InputConnection ic, double score, String committed) {
        if (prevAutoLatin == null || prevScore == null || context.isEmpty() || !context.peekLast().equals(prevAutoLatin)
                || !EnglishDetector.previousWasEnglish(score, sentence)
                || prevAutoCommitted.equals(prevAutoLatin + " ")) return;
        String both = prevAutoCommitted + committed;
        CharSequence before = ic.getTextBeforeCursor(both.length(), 0);
        if (before == null || !before.toString().equals(both)) return;
        ic.beginBatchEdit();
        ic.deleteSurroundingText(both.length(), 0);
        ic.commitText(prevAutoLatin + " " + committed, 1);
        ic.endBatchEdit();
    }

    private static int differences(String a, String b) {
        int d = 0;
        for (int i = 0; i < a.length(); i++) if (a.charAt(i) != b.charAt(i)) d++;
        return d;
    }

    private void resetWord() {
        word.setLength(0);
        keepLatin = false;
        lastResult = null;
        lastShown = "";
        requestId++;
    }

    // ------------------------------------------------------------------ conversion

    private String contextString() {
        return TextUtils.join(" ", context);
    }

    /** Synchronous conversion, used when committing (normally a cache hit). */
    private String convertNow(String latin) {
        Converter.Result r = lastResult;
        if (r != null && r.input.equals(latin)) return r.best;
        Converter c = converter;
        if (c == null) return latin;
        try {
            return fix(c.convertLastWord(contextString(), latin)).best;
        } catch (Exception e) {
            Log.e(TAG, "convert failed", e);
            return latin;
        }
    }

    /** Shows the word right away, then replaces it with the model's Greek when ready. */
    private void refreshComposing() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        final String latin = word.toString();
        final String taught = learned.get(latin);
        if (taught != null && !keepLatin) {
            ic.setComposingText(taught, 1);
            lastShown = taught;
            strip.showSuggestions(null, taught, null, true);
        }
        if (keepLatin || converter == null) {
            if (taught != null && !keepLatin) return;
            ic.setComposingText(latin, 1);
            lastShown = latin;
            strip.showSuggestions(null, latin, null);
            return;
        }
        // Instant feedback: previous Greek + the newly typed letters, until the model answers.
        if (taught == null) {
            Converter.Result r = lastResult;
            String instant = r != null && latin.startsWith(r.input) ? r.best + latin.substring(r.input.length()) : latin;
            ic.setComposingText(instant, 1);
            lastShown = instant;
        }

        final int id = ++requestId;
        final String ctx = contextString();
        final Converter c = converter;
        worker.execute(() -> {
            if (id != requestId) return;  // a newer keystroke already arrived
            try {
                Converter.Result res = fix(c.convertLastWord(ctx, latin));
                main.post(() -> {
                    if (id != requestId || !word.toString().equals(latin)) return;
                    lastResult = res;
                    if (taught != null) {
                        // Learned word stays; the model's Greek is offered on the right to override it.
                        strip.showSuggestions(null, taught, res.best.equals(taught) ? null : res.best, true);
                        return;
                    }
                    InputConnection conn = getCurrentInputConnection();
                    if (looksEnglish(latin)) {
                        // English: stays as typed; the Greek is offered on the right.
                        if (conn != null && !latin.equals(lastShown)) {
                            conn.setComposingText(latin, 1);
                            lastShown = latin;
                        }
                        strip.showSuggestions(null, latin, res.best.equals(latin) ? null : res.best);
                        return;
                    }
                    if (conn != null && !res.best.equals(lastShown)) {
                        conn.setComposingText(res.best, 1);
                        lastShown = res.best;
                    }
                    String left = res.best.equals(latin) ? null : latin;
                    strip.showSuggestions(left, res.best, res.alternative);
                });
            } catch (Exception e) {
                Log.e(TAG, "convert failed", e);
            }
        });
    }

    // ------------------------------------------------------------------ strip / panels

    @Override
    public void onSuggestion(String text, int slot) {
        if (slot == StripView.SLOT_EMOJI) {
            emoji.picked(text);
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(text, 1);
            return;
        }
        if (word.length() == 0) return;
        if (slot == 0) keepLatin = true;
        // Picking something other than the main suggestion teaches the keyboard.
        if (slot != 1 && prefs.getBoolean("learn_words", true)) learned.learn(word.toString(), text);
        commitChoice(text, true);
    }

    @Override
    public void onToolbar(int action) {
        switch (action) {
            case StripView.TOOL_EMOJI:
                showPanel(panel == PANEL_EMOJI ? PANEL_KEYS : PANEL_EMOJI);
                break;
            case StripView.TOOL_CLIPBOARD:
                showPanel(panel == PANEL_CLIPBOARD ? PANEL_KEYS : PANEL_CLIPBOARD);
                break;
            case StripView.TOOL_SETTINGS: {
                Intent i = new Intent(this, SetupActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                break;
            }
            case StripView.TOOL_MODE:
                onKey(Layouts.MODE);
                break;
            case StripView.TOOL_SEARCH_EXIT:
                endEmojiSearch(true);
                break;
            default:
                break;
        }
    }

    private void showPanel(int p) {
        panel = p;
        keys.setVisibility(p == PANEL_KEYS ? View.VISIBLE : View.GONE);
        emoji.setVisibility(p == PANEL_EMOJI ? View.VISIBLE : View.GONE);
        clipboard.setVisibility(p == PANEL_CLIPBOARD ? View.VISIBLE : View.GONE);
        if (p == PANEL_EMOJI) emoji.open();
        if (p == PANEL_CLIPBOARD) clipboard.open();
        strip.setActiveTool(p == PANEL_EMOJI ? StripView.TOOL_EMOJI : p == PANEL_CLIPBOARD ? StripView.TOOL_CLIPBOARD : 0);
    }

    @Override
    public void onEmojiSearchStart() {
        emojiQuery = new StringBuilder();
        keys.setLayout(Layouts.letters(KeysView.numberRow(this)));
        keys.setShift(KeysView.SHIFT_OFF);
        showPanel(PANEL_KEYS);
        updateEmojiSearch();
    }

    private void updateEmojiSearch() {
        strip.showEmojiSearch(emojiQuery.toString(), emoji.search(emojiQuery.toString()));
    }

    /** Leaves emoji search; back to the emoji grid, or to normal typing. */
    private void endEmojiSearch(boolean backToEmoji) {
        emojiQuery = null;
        strip.showEmojiSearch(null, null);
        strip.showToolbar();
        showPanel(backToEmoji ? PANEL_EMOJI : PANEL_KEYS);
    }

    @Override
    public void onEmoji(String e) {
        commitWord(false);
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(e, 1);
    }

    @Override
    public void onPaste(String text) {
        commitWord(false);
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    @Override
    public void onModeLongPress() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.showInputMethodPicker();
    }

    @Override
    public void onCursorMove(int chars) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (word.length() > 0) commitWord(false);
        undoLatin = null;
        context.clear();
        int target = selEnd + chars;
        if (chars > 0) {
            CharSequence after = ic.getTextAfterCursor(chars, 0);
            target = selEnd + (after == null ? 0 : after.length());
        }
        target = Math.max(0, target);
        ic.setSelection(target, target);
    }

    @Override
    public void onDeleteSwipe(int words, boolean finished) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (swipeAnchor < 0) {
            if (word.length() > 0) commitWord(false);
            swipeAnchor = selEnd;
            CharSequence before = ic.getTextBeforeCursor(1000, 0);
            swipeBefore = before == null ? "" : before.toString();
        }
        int anchor = swipeAnchor;
        int start = anchor;
        if (words > 0) {
            String b = swipeBefore;
            int i = b.length();
            for (int w = 0; w < words && i > 0; w++) {
                while (i > 0 && Character.isWhitespace(b.charAt(i - 1))) i--;
                while (i > 0 && !Character.isWhitespace(b.charAt(i - 1))) i--;
            }
            start = anchor - (b.length() - i);
        }
        if (finished) {
            if (words > 0) ic.commitText("", 1);
            swipeAnchor = -1;
            undoLatin = null;
            context.clear();
            updateAutoCaps();
        } else {
            ic.setSelection(start, anchor);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void updateModeViews() {
        keys.setGreeklishMode(greeklishMode);
        strip.setGreeklishMode(greeklishMode);
    }

    private void updateAutoCaps() {
        if (keys == null || keys.getShift() == KeysView.SHIFT_LOCKED) return;
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null || info == null) return;
        boolean caps = info.inputType != InputType.TYPE_NULL && ic.getCursorCapsMode(info.inputType) != 0;
        keys.setShift(caps ? KeysView.SHIFT_ONCE : KeysView.SHIFT_OFF);
    }
}
