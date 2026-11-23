"""Build android/src/main/assets/emoji.tsv from Unicode's emoji-test.txt.

Format (UTF-8, tab separated):
  #<group name>                      category header
  <emoji>\t<english name>\t<tone1,tone2,...>   (tone variants: light .. dark, may be empty)

  python -I scripts/build_emoji.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "data" / "raw" / "unicode" / "emoji-test.txt"
OUT = ROOT / "android" / "src" / "main" / "assets" / "emoji.tsv"
TONES = ["light skin tone", "medium-light skin tone", "medium skin tone", "medium-dark skin tone", "dark skin tone"]
LINE = re.compile(r"^(.+?)\s*;\s*fully-qualified\s*#\s*(\S+)\s+E\d+\.\d+\s+(.+)$")


def main():
    groups = []  # [(group, [(emoji, name)])]
    tones = {}   # base name -> {tone: emoji}
    for raw in SRC.read_text(encoding="utf-8").splitlines():
        if raw.startswith("# group:"):
            g = raw.split(":", 1)[1].strip()
            groups.append((g, []))
            continue
        m = LINE.match(raw)
        if not m or not groups:
            continue
        emoji, name = m.group(2), m.group(3)
        if groups[-1][0] == "Component":
            continue
        if "skin tone" in name:
            base, _, tone = name.rpartition(": ")
            if tone in TONES:  # exactly one tone; skip mixed-tone combinations
                tones.setdefault(base, {})[tone] = emoji
            continue
        groups[-1][1].append((emoji, name))

    lines, count = [], 0
    for g, items in groups:
        if not items:
            continue
        lines.append("#" + g)
        for emoji, name in items:
            t = tones.get(name, {})
            variants = ",".join(t[x] for x in TONES if x in t) if len(t) == 5 else ""
            lines.append(f"{emoji}\t{name}\t{variants}")
            count += 1
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    with_tones = sum(1 for l in lines if l.count("\t") == 2 and l.split("\t")[2])
    print(f"{count} emojis, {with_tones} with skin tones, {len([l for l in lines if l.startswith('#')])} groups "
          f"-> {OUT} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
