"""Build greek_words.tsv (word \t count) for autocorrect, from the Greek side of the training data.

Keeps lowercase words seen at least MIN_COUNT times (real words, not one-off typos in subtitles).

  python -I scripts/build_greek_dict.py
"""
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TRAIN = ROOT / "data" / "processed" / "train.tsv"
OUTS = [ROOT / "android" / "src" / "main" / "assets" / "greek_words.tsv", ROOT / "windows" / "Assets" / "greek_words.tsv"]
MIN_COUNT = 8
MAX_WORDS = 120_000
WORD = re.compile(r"[α-ωάέήίόύώϊϋΐΰς]+")


def main():
    counts = Counter()
    with open(TRAIN, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2:
                counts.update(WORD.findall(parts[1].lower()))
    words = [(w, c) for w, c in counts.most_common(MAX_WORDS) if c >= MIN_COUNT and len(w) > 1 or w in ("ή", "ο", "η", "α", "ε")]
    text = "".join(f"{w}\t{c}\n" for w, c in words)
    for out in OUTS:
        out.write_text(text, encoding="utf-8")
    print(f"{len(counts):,} distinct words, kept {len(words):,} ({len(text.encode('utf-8')) // 1024} KB)")
    for w in ["καλημέρα", "γυμναστήριο", "καλυμέα", "γυμαστήριο", "αγαπάω", "συγκεκριμένα"]:
        print(f"  {w}: {counts[w]}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
