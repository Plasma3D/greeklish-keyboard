package com.greeklish.keyboard;

import java.util.ArrayList;
import java.util.List;

/** Key definitions for the letter page and the two symbol pages (Gboard arrangement). */
public final class Layouts {
    public static final int CODE_CHAR = 0, SHIFT = -1, DELETE = -2, SYMBOLS = -3, LETTERS = -4,
            SYMBOLS2 = -5, ENTER = -6, SPACE = -7, MODE = -8;

    public static final class Key {
        public final String label;      // what is drawn (and typed, for CODE_CHAR)
        public final int code;
        public final float width;       // in units of a normal key
        public final String popup;      // long-press alternatives ("" = none)
        public final String hint;       // small corner hint (top row numbers)
        public final boolean function;  // drawn with the darker function-key colour

        Key(String label, int code, float width, String popup, String hint, boolean function) {
            this.label = label;
            this.code = code;
            this.width = width;
            this.popup = popup;
            this.hint = hint;
            this.function = function;
        }

        /** Part of the optional always-visible number row (drawn shorter). */
        public boolean numberRow;

        // Layout position, filled in by KeysView.
        public float left, top, right, bottom;
    }

    private static Key ch(String c, String popup) {
        String hint = popup.length() == 1 && Character.isDigit(popup.charAt(0)) ? popup : "";
        return new Key(c, CODE_CHAR, 1f, popup, hint, false);
    }

    private static Key fn(String label, int code, float w) {
        return new Key(label, code, w, "", "", true);
    }

    private static List<Key> row(String chars, String[] popups) {
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < chars.length(); i++) {
            r.add(ch(String.valueOf(chars.charAt(i)), popups == null ? "" : popups[i]));
        }
        return r;
    }

    /** Letter keys: the first popup character is the hold symbol, shown as a hint (Gboard layout). */
    private static List<Key> hinted(String chars, String[] popups) {
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < chars.length(); i++) {
            String p = popups[i];
            r.add(new Key(String.valueOf(chars.charAt(i)), CODE_CHAR, 1f, p, p.isEmpty() ? "" : p.substring(0, 1), false));
        }
        return r;
    }

    public static List<List<Key>> letters() {
        return letters(false);
    }

    public static List<List<Key>> letters(boolean numberRow) {
        return letters(numberRow, false);
    }

    /** Tonos dead key on the Greek layout: tap it, then a vowel (΄ + α = ά). */
    public static final String TONOS = "΄", DIALYTIKA = "¨";

    public static List<List<Key>> letters(boolean numberRow, boolean greek) {
        if (greek) return greekLetters(numberRow);
        List<List<Key>> rows = new ArrayList<>();
        if (numberRow) {
            List<Key> nums = row("1234567890", null);
            for (Key k : nums) k.numberRow = true;
            rows.add(nums);
        }
        rows.add(hinted("qwertyuiop", new String[]{"1", "2", "3€", "4", "5", "6", "7", "8", "9", "0"}));
        rows.add(hinted("asdfghjkl", new String[]{"@", "#", "$€£", "_", "&", "-–", "+", "(", ")"}));
        List<Key> r3 = new ArrayList<>();
        r3.add(fn("", SHIFT, 1.5f));
        r3.addAll(hinted("zxcvbnm", new String[]{"*", "\"«»", "'", ":", ";", "!¡", "?¿"}));
        r3.add(fn("", DELETE, 1.5f));
        rows.add(r3);
        rows.add(bottomRow("?123", SYMBOLS));
        return rows;
    }

    /** Standard Greek layout (as on Gboard and Greek PC keyboards). Vowels hold their accented forms. */
    private static List<List<Key>> greekLetters(boolean numberRow) {
        List<List<Key>> rows = new ArrayList<>();
        if (numberRow) {
            List<Key> nums = row("1234567890", null);
            for (Key k : nums) k.numberRow = true;
            rows.add(nums);
        }
        List<Key> r1 = new ArrayList<>();
        r1.add(new Key(TONOS, CODE_CHAR, 1f, "1" + DIALYTIKA + "΅;", "1", false));
        r1.addAll(hinted("ςερτυθιοπ", new String[]{"2", "3έ", "4", "5", "6ύϋΰ", "7", "8ίϊΐ", "9ό", "0"}));
        rows.add(r1);
        rows.add(hinted("ασδφγηξκλ", new String[]{"@ά", "#", "$€£", "_", "&", "-ή–", "+", "(", ")"}));
        List<Key> r3 = new ArrayList<>();
        r3.add(fn("", SHIFT, 1.5f));
        r3.addAll(hinted("ζχψωβνμ", new String[]{"*", "\"«»", "'", ":ώ", ";", "!¡", "?;"}));
        r3.add(fn("", DELETE, 1.5f));
        rows.add(r3);
        rows.add(bottomRow("?123", SYMBOLS));
        return rows;
    }

    public static List<List<Key>> symbols() {
        List<List<Key>> rows = new ArrayList<>();
        rows.add(row("1234567890", new String[]{"¹½⅓¼", "²", "³¾", "⁴", "", "", "", "", "", "ⁿ∅"}));
        rows.add(row("@#€_&-+()/", new String[]{"", "", "$£¥¢", "", "", "–—·", "±", "<{[", ">}]", ""}));
        List<Key> r3 = new ArrayList<>();
        r3.add(fn("=\\<", SYMBOLS2, 1.5f));
        r3.addAll(row("*\"':;!?", new String[]{"†‡★", "„“”«»", "‚‘’", "", "", "¡", "¿"}));
        r3.add(fn("", DELETE, 1.5f));
        rows.add(r3);
        rows.add(bottomRow("ABC", LETTERS));
        return rows;
    }

    public static List<List<Key>> symbols2() {
        List<List<Key>> rows = new ArrayList<>();
        rows.add(row("~`|•√π÷×¶∆", null));
        rows.add(row("£¥$¢^°={}\\", null));
        List<Key> r3 = new ArrayList<>();
        r3.add(fn("?123", SYMBOLS, 1.5f));
        r3.addAll(row("%©®™✓[]", null));
        r3.add(fn("", DELETE, 1.5f));
        rows.add(r3);
        rows.add(bottomRow("ABC", LETTERS));
        return rows;
    }

    private static List<Key> bottomRow(String switchLabel, int switchCode) {
        List<Key> r = new ArrayList<>();
        r.add(fn(switchLabel, switchCode, 1.5f));
        r.add(new Key(",", CODE_CHAR, 1f, "", "", false));
        r.add(fn("", MODE, 1f));
        r.add(new Key(" ", SPACE, 4f, "", "", false));  // row total = 10 key widths
        r.add(new Key(".", CODE_CHAR, 1f, ".,?!'\"-:;", "", false));
        r.add(new Key("", ENTER, 1.5f, "", "", true));
        return r;
    }

    private Layouts() {
    }
}
