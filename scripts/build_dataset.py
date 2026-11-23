"""Build Greeklish -> Greek training data.

Sources:
  data/raw/opus/el-en.txt.zip   OpenSubtitles v2024 (Greek side), casual dialogue
  data/raw/aueb/...             AUEB Greeklish project (Apache-2.0)

Writes data/processed/:
  train.tsv, val.tsv            synthetic pairs  (greeklish \t greek)
  test_real.tsv                 real people's Greeklish (AUEB gazzetta + survivorbot)
  test_aueb_friends.tsv         AUEB's own artificial Friends test set
Run:  python -I scripts/build_dataset.py [target_sentences]
"""
import hashlib
import io
import random
import re
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from greeklish import to_greeklish, is_greek_letter  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "data" / "raw"
OUT = ROOT / "data" / "processed"
TARGET = int(sys.argv[1]) if len(sys.argv) > 1 else 10_000_000
VAL_SIZE = 20_000
FRAGMENT_RATE = 0.2  # share of samples cut to 1-6 word spans (keyboard sees partial text)
rng = random.Random(20261111)

TAG = re.compile(r"<[^>]{0,20}>|\{[^}]{0,40}\}")
SPACES = re.compile(r"\s+")
TONOS_MAP = {"Α": "Ά", "Ε": "Έ", "Η": "Ή", "Ι": "Ί", "Ο": "Ό", "Υ": "Ύ", "Ω": "Ώ"}
TONOS_CAP = re.compile(r"[΄'`’](?<![\w][΄'`’])([ΑΕΗΙΟΥΩ])")
BROKEN_START = re.compile(r"(?:^|[\s\"(«-])'[Ͱ-Ͽἀ-῿]")
ALLOWED_EXTRA = set(" .,;:!?'\"-()…«»0123456789%€$&/·’‘“”–—")


def clean(line):
    line = TAG.sub("", line)
    # "΄Ο,τι" / "'Ισως": accent typed before a capital -> "Ό,τι" / "Ίσως"
    line = TONOS_CAP.sub(lambda m: TONOS_MAP[m.group(1)], line)
    line = line.replace("`", "'").replace("΄", "'").replace("’", "'").replace("‘", "'")
    line = SPACES.sub(" ", line).strip()
    if BROKEN_START.search(line):  # "'φησε" = lost capital (or rare elision): skip
        return None
    line = line.lstrip("-–— ").strip()
    if not (2 <= len(line) <= 200):
        return None
    letters = [c for c in line if c.isalpha()]
    if not letters:
        return None
    greek = sum(is_greek_letter(c) for c in letters)
    if greek / len(letters) < 0.8 or greek < 2:
        return None
    # drop lines with odd symbols (subtitle credits, encodings gone wrong)
    if any(not (c.isalpha() or c in ALLOWED_EXTRA) for c in line):
        return None
    return line


def key(s):
    return hashlib.blake2b(s.lower().encode("utf-8"), digest_size=8).digest()


def read_lines(path):
    return [l.rstrip("\n\r") for l in path.read_text(encoding="utf-8").split("\n")]


def subtitle_lines():
    zpath = RAW / "opus" / "el-en.txt.zip"
    with zipfile.ZipFile(zpath) as z:
        name = next(n for n in z.namelist() if n.endswith(".el"))
        with z.open(name) as f:
            for raw in io.TextIOWrapper(f, encoding="utf-8", errors="replace"):
                yield raw


def make_pair(greek):
    if rng.random() < FRAGMENT_RATE:
        words = greek.split(" ")
        if len(words) > 2:
            n = rng.randint(1, min(6, len(words) - 1))
            start = rng.randint(0, len(words) - n)
            greek = " ".join(words[start:start + n])
    latin, target = to_greeklish(greek, rng)
    return latin, target


def write_tsv(path, pairs):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for a, b in pairs:
            f.write(a.replace("\t", " ") + "\t" + b.replace("\t", " ") + "\n")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    aueb = RAW / "aueb"

    # ---- test sets (never trained on) ----
    blocked = set()
    real = []
    for n in ["gazzetta_accepted", "gazzetta_rejected", "survivorbot_msc"]:
        gl = [l for l in read_lines(aueb / "real_world" / "greeklish" / f"greeklish_{n}.txt")]
        gr = read_lines(aueb / "real_world" / "greek" / f"greek_{n}.txt")
        for a, b in zip(gl, gr):  # Greek files have extra unpaired lines at the end
            if a.strip() and b.strip():
                real.append((a.strip(), b.strip()))
                blocked.add(key(b.strip()))
    write_tsv(OUT / "test_real.tsv", real)

    fr = list(zip(read_lines(aueb / "artificial" / "greeklish" / "greeklish_friends_test.txt"),
                  read_lines(aueb / "artificial" / "greek" / "greek_friends_test.txt")))
    fr = [(a.strip(), b.strip()) for a, b in fr if a.strip() and b.strip()]
    write_tsv(OUT / "test_aueb_friends.tsv", fr)
    for f in (aueb / "artificial" / "greek").glob("*test*.txt"):
        for l in read_lines(f):
            blocked.add(key(l.strip()))
    print(f"test_real {len(real)}  test_aueb_friends {len(fr)}  blocked {len(blocked)}", flush=True)

    # ---- Greek sentences ----
    seen = set(blocked)
    sentences = []

    # AUEB Greek training text (re-converted with our more realistic converter)
    for name in ["greek_europarl_training_100k.txt", "greek_para5.txt", "greek_friends.txt"]:
        for l in read_lines(aueb / "artificial" / "greek" / name):
            c = clean(l)
            if c and key(c) not in seen:
                seen.add(key(c))
                sentences.append(c)
    print(f"aueb sentences {len(sentences)}", flush=True)

    # Pass 1: count subtitle lines, to sample evenly across the whole corpus
    total = 0
    for _ in subtitle_lines():
        total += 1
    keep_p = min(1.0, (TARGET * 1.6) / max(total, 1))  # oversample: cleaning + dedupe drop many
    print(f"subtitle lines {total:,}  keep prob {keep_p:.3f}", flush=True)

    kept = 0
    for i, raw in enumerate(subtitle_lines()):
        if rng.random() > keep_p:
            continue
        c = clean(raw)
        if not c:
            continue
        k = key(c)
        if k in seen:
            continue
        seen.add(k)
        sentences.append(c)
        kept += 1
        if len(sentences) >= TARGET:
            break
        if kept % 1_000_000 == 0:
            print(f"  subtitles kept {kept:,} (line {i:,})", flush=True)
    del seen
    print(f"total unique sentences {len(sentences):,}", flush=True)

    rng.shuffle(sentences)
    val, train = sentences[:VAL_SIZE], sentences[VAL_SIZE:]
    write_tsv(OUT / "val.tsv", (make_pair(s) for s in val))
    with open(OUT / "train.tsv", "w", encoding="utf-8", newline="\n") as f:
        for j, s in enumerate(train):
            a, b = make_pair(s)
            f.write(a.replace("\t", " ") + "\t" + b.replace("\t", " ") + "\n")
            if j % 2_000_000 == 0 and j:
                print(f"  wrote {j:,}", flush=True)
    print(f"train {len(train):,}  val {len(val):,}  ->  {OUT}", flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
