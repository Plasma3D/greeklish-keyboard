"""Realistic Greek -> Greeklish converter, used to make training pairs.

Each sentence gets one random "writer style" (people are mostly consistent within a
message), plus a little per-letter noise. The Greek target is returned with the same
casing the Greeklish got, so the model learns: lowercase in -> lowercase out.
"""
import random
import unicodedata

VOICELESS = set("θκξπσςτφχψ")  # αυ/ευ sound like af/ef before these and at word end


def strip_accents(s):
    out = []
    for ch in unicodedata.normalize("NFD", s):
        if unicodedata.category(ch) != "Mn":
            out.append(ch)
    return unicodedata.normalize("NFC", "".join(out))


def pick(rng, options):
    """options: list of (value, weight)."""
    total = sum(w for _, w in options)
    r = rng.random() * total
    for v, w in options:
        r -= w
        if r <= 0:
            return v
    return options[-1][0]


# Each entry: Greek unit -> weighted choices. A sentence samples one choice per unit
# (its style); NOISE of the time a letter is re-sampled independently.
STYLE_CHOICES = {
    "θ": [("th", 60), ("8", 25), ("u", 4), ("9", 3), ("t", 3)],
    "χ": [("x", 72), ("ch", 18), ("h", 10)],
    "ξ": [("ks", 55), ("x", 28), ("j", 10), ("3", 7)],
    "ψ": [("ps", 88), ("c", 7), ("y", 5)],
    "ω": [("o", 55), ("w", 40), ("v", 5)],
    "η": [("i", 75), ("h", 25)],
    "υ": [("i", 50), ("y", 45), ("u", 5)],
    "ου": [("ou", 85), ("u", 15)],
    "ει": [("ei", 60), ("i", 40)],
    "οι": [("oi", 62), ("i", 33), ("oy", 5)],
    "αι": [("ai", 72), ("e", 28)],
    "υι": [("ui", 50), ("yi", 50)],
    "αυ": [("phon", 50), ("au", 30), ("ay", 20)],  # phon = av/af by sound
    "ευ": [("phon", 50), ("eu", 30), ("ey", 20)],
    "β": [("v", 95), ("b", 5)],
    "δ": [("d", 95), ("dh", 5)],
    "φ": [("f", 92), ("ph", 8)],
    "γγ": [("gg", 60), ("ng", 40)],
    "γκ": [("gk", 60), ("g", 30), ("nk", 10)],
    "μπ": [("mp", 75), ("b", 25)],
    "ντ": [("nt", 80), ("d", 20)],
    "και": [("kai", 60), ("k", 30), ("ke", 10)],
    ";": [("?", 85), (";", 15)],
    "·": [(";", 30), (".", 40), (",", 30)],
    "case": [("lower", 62), ("sentence", 30), ("keep", 8)],
    "abbrev": [(True, 30), (False, 70)],   # texting shortcuts like gt / dld
    "single": [(True, 30), (False, 70)],   # περισσότερο -> perisotero
    "typos": [(0, 75), (1, 20), (2, 5)],  # random human typos per sentence
}

# Texting abbreviations (accentless lowercase Greek word -> options). Used only in
# "abbrev" styles, and only some of the time, so the full spelling stays common too.
ABBREV = {
    "γιατι": ["gt", "giati"], "δηλαδη": ["dld", "dhladh"], "τιποτα": ["tpt", "tipota"],
    "κατι": ["kt", "kati"], "πολυ": ["pl", "poly"], "μου": ["m", "mou"], "σου": ["s", "sou"],
    "τιποτε": ["tpt"], "οτιδηποτε": ["otidipote", "otdpt"], "ευχαριστω": ["euxaristw", "eyxaristw", "efxaristo"],
    "λοιπον": ["lpn", "loipon"], "παντως": ["pntws", "pantws"], "τωρα": ["twra", "tr"], "ειναι": ["einai", "ine"],
}
# Latin keyboard neighbours, for realistic typos
NEIGHBOURS = {}
for row in ["qwertyuiop", "asdfghjkl", "zxcvbnm"]:
    for i, c in enumerate(row):
        NEIGHBOURS[c] = (row[i - 1] if i else "") + (row[i + 1] if i + 1 < len(row) else "")
DOUBLES = set("λσμνπτκρφγ")
NOISE = 0.03

SIMPLE = {
    "α": "a", "γ": "g", "ε": "e", "ζ": "z", "ι": "i", "κ": "k", "λ": "l", "μ": "m",
    "ν": "n", "ο": "o", "π": "p", "ρ": "r", "σ": "s", "ς": "s", "τ": "t",
}
STYLED_SINGLE = {"θ", "χ", "ξ", "ψ", "ω", "η", "υ", "β", "δ", "φ"}
DIGRAPHS = ["ου", "ει", "οι", "αι", "υι", "αυ", "ευ", "γγ", "γκ", "μπ", "ντ"]


def sample_style(rng):
    return {k: pick(rng, v) for k, v in STYLE_CHOICES.items()}


def choose(rng, style, key):
    if rng.random() < NOISE:
        return pick(rng, STYLE_CHOICES[key])
    return style[key]


def convert_word(word, rng, style):
    """word: lowercase, accentless Greek (may contain other chars)."""
    if word == "και":
        return choose(rng, style, "και")
    if style["abbrev"] and word in ABBREV and rng.random() < 0.5:
        return rng.choice(ABBREV[word])
    if style["single"]:
        # people often type one letter for doubled consonants (λλ, σσ, ...)
        word = "".join(c for i, c in enumerate(word) if not (i and c == word[i - 1] and c in DOUBLES))
    out = []
    i, n = 0, len(word)
    while i < n:
        two = word[i:i + 2]
        if two in DIGRAPHS:
            if two in ("αυ", "ευ"):
                v = choose(rng, style, two)
                if v == "phon":
                    nxt = word[i + 2] if i + 2 < n else ""
                    v = two[0].translate(str.maketrans("αε", "ae")) + ("f" if (nxt == "" or nxt in VOICELESS) else "v")
                out.append(v)
            elif two in ("μπ", "ντ") and i > 0 and rng.random() < 0.85:
                # "b"/"d" is common at word start (bike, dropi) but rare mid-word
                out.append("mp" if two == "μπ" else "nt")
            else:
                out.append(choose(rng, style, two))
            i += 2
            continue
        ch = word[i]
        if ch in STYLED_SINGLE:
            out.append(choose(rng, style, ch))
        elif ch in SIMPLE:
            out.append(SIMPLE[ch])
        elif ch in (";", "·"):
            out.append(choose(rng, style, ch))
        else:
            out.append(ch)
        i += 1
    return "".join(out)


def is_greek_letter(ch):
    return "Ͱ" <= ch <= "Ͽ" or "ἀ" <= ch <= "῿"


def tokens(text):
    """Split into runs of Greek letters and everything else."""
    run, kind = [], None
    for ch in text:
        k = is_greek_letter(ch)
        if kind is None or k == kind:
            run.append(ch)
        else:
            yield kind, "".join(run)
            run = [ch]
        kind = k
    if run:
        yield kind, "".join(run)


def greek_lower(s):
    s = s.lower()
    # fix final sigma after lowercasing
    out = []
    for i, ch in enumerate(s):
        if ch == "σ" and (i + 1 == len(s) or not is_greek_letter(s[i + 1])):
            out.append("ς")
        else:
            out.append(ch)
    return "".join(out)


def apply_case(greek, latin, mode):
    if mode == "lower":
        return greek_lower(greek), latin.lower()
    if mode == "sentence":
        g, l = greek_lower(greek), latin.lower()
        return g[:1].upper() + g[1:], l[:1].upper() + l[1:]
    return greek, latin


def add_typo(s, rng):
    """One realistic typo on a random letter: drop, double, swap or hit a neighbour key."""
    pos = [i for i, c in enumerate(s) if c.isalpha()]
    if len(pos) < 4:
        return s
    i = rng.choice(pos)
    kind = rng.random()
    if kind < 0.3:
        return s[:i] + s[i + 1:]
    if kind < 0.5:
        return s[:i] + s[i] + s[i:]
    if kind < 0.7 and i + 1 < len(s) and s[i + 1].isalpha():
        return s[:i] + s[i + 1] + s[i] + s[i + 2:]
    nb = NEIGHBOURS.get(s[i].lower(), "")
    if not nb:
        return s
    c = rng.choice(nb)
    return s[:i] + (c.upper() if s[i].isupper() else c) + s[i + 1:]


def to_greeklish(greek_text, rng):
    """Returns (greeklish_input, greek_target)."""
    style = sample_style(rng)
    latin_parts = []
    for is_gr, run in tokens(greek_text):
        if not is_gr:
            latin_parts.append(run.replace(";", choose(rng, style, ";")).replace("·", choose(rng, style, "·")))
            continue
        plain = strip_accents(run)
        lower = greek_lower(plain)
        conv = convert_word(lower, rng, style)
        # carry capitalisation from the Greek run onto the Latin
        if plain.isupper() and len(plain) > 1:
            conv = conv.upper()
        elif plain[:1].isupper():
            conv = conv[:1].upper() + conv[1:]
        latin_parts.append(conv)
    latin = "".join(latin_parts)
    for _ in range(style["typos"]):
        latin = add_typo(latin, rng)
    # All-caps Greek drops accents, so lowercasing it would teach wrong spellings.
    letters = [c for c in greek_text if is_greek_letter(c)]
    caps = sum(c.isupper() for c in letters)
    mode = "keep" if letters and caps / len(letters) > 0.5 else style["case"]
    greek, latin = apply_case(greek_text, latin, mode)
    return latin, greek


if __name__ == "__main__":
    import sys
    sys.stdout.reconfigure(encoding="utf-8")
    rng = random.Random(1)
    for s in ["Καλημέρα, τι κάνεις;", "Ναι το ξέρω", "Μπορώ να παίξω τώρα;", "Αύριο θα πάμε στο γυμναστήριο και μετά για φαγητό.",
              "Είσαι αγόρι ή κορίτσι", "Ευχαριστώ πολύ για τη βοήθεια!", "ΟΧΙ ΔΕΝ ΘΕΛΩ", "Το iPhone μου χάλασε πάλι"]:
        for _ in range(3):
            print(to_greeklish(s, rng))
