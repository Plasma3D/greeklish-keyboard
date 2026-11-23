"""Build english.tsv: how much more likely each common word is English than Greeklish.

English counts: English side of OpenSubtitles (data/raw/opus/el-en.txt.zip).
Greeklish counts: the Latin side of our training pairs (data/processed/train.tsv).
score = ln(P_english(word) / P_greeklish(word)), clipped to [-6, 6].
  >> 0: English (same, yes, press, the)   << 0: Greeklish (kai, pou, file)   ~0: both (i, a, to)

  python -I scripts/build_english.py
"""
import io
import math
import re
import sys
import zipfile
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ZIP = ROOT / "data" / "raw" / "opus" / "el-en.txt.zip"
TRAIN = ROOT / "data" / "processed" / "train.tsv"
OUTS = [ROOT / "android" / "src" / "main" / "assets" / "english.tsv", ROOT / "windows" / "Assets" / "english.tsv"]
TOP = 25_000
EN_LINES = 20_000_000
WORD = re.compile(r"[a-z]+(?:'[a-z]+)?")


def main():
    en = Counter()
    with zipfile.ZipFile(ZIP) as z:
        with z.open("OpenSubtitles.el-en.en") as f:
            for i, line in enumerate(io.TextIOWrapper(f, encoding="utf-8", errors="replace")):
                en.update(WORD.findall(line.lower().replace("’", "'")))
                if i >= EN_LINES:
                    break
    en_total = sum(en.values())
    vocab = {w for w, _ in en.most_common(TOP)}
    print(f"english tokens {en_total:,}, keeping top {len(vocab):,}", flush=True)

    gl = Counter()
    gl_total = 0
    with open(TRAIN, encoding="utf-8") as f:
        for line in f:
            src = line.split("\t", 1)[0].lower()
            toks = WORD.findall(src)
            gl_total += len(toks)
            gl.update(t for t in toks if t in vocab)
    print(f"greeklish tokens {gl_total:,}", flush=True)

    rows = []
    for w in vocab:
        p_en = (en[w] + 0.5) / en_total
        p_gl = (gl[w] + 0.5) / gl_total
        s = max(-6.0, min(6.0, math.log(p_en / p_gl)))
        rows.append((w, round(s, 1)))
    rows.sort(key=lambda r: -en[r[0]])
    text = "".join(f"{w}\t{s}\n" for w, s in rows)
    for out in OUTS:
        out.write_text(text, encoding="utf-8")
    print(f"wrote {len(rows):,} words ({len(text) // 1024} KB)")
    for w in ["the", "same", "yes", "press", "have", "do", "i", "a", "to", "me", "an", "as", "file", "san", "ok",
              "kai", "pou", "na", "ti", "o", "gym", "meta", "mama", "is", "it", "on", "in", "so", "den", "re", "sorry"]:
        print(f"  {w:8} en={en[w]:>9,} gl={gl[w]:>9,} score={dict(rows).get(w)}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
