"""Encode TSV pairs into compact numpy arrays for fast training.

Writes data/bin/{vocab.json, <split>.npz}. Run with the training venv:
  .venv/Scripts/python.exe scripts/prepare.py
"""
import json
import sys
from collections import Counter
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
PROC = ROOT / "data" / "processed"
BIN = ROOT / "data" / "bin"
MAX_LEN = 128
SPECIALS = ["<pad>", "<unk>"]  # src; tgt uses index 0 as CTC blank, 1 as unk
MIN_COUNT = 50


def read_pairs(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2 and parts[0] and parts[1]:
                yield parts


def build_vocab():
    src, tgt = Counter(), Counter()
    for i, (a, b) in enumerate(read_pairs(PROC / "train.tsv")):
        src.update(a)
        tgt.update(b)
        if i >= 3_000_000:
            break
    s = SPECIALS + sorted(c for c, n in src.items() if n >= MIN_COUNT)
    t = ["<blank>", "<unk>"] + sorted(c for c, n in tgt.items() if n >= MIN_COUNT)
    assert len(s) < 256 and len(t) < 256, (len(s), len(t))
    return s, t


def encode_split(name, s2i, t2i, skip_long=True):
    src_flat, tgt_flat, src_off, tgt_off = bytearray(), bytearray(), [0], [0]
    skipped = 0
    for a, b in read_pairs(PROC / f"{name}.tsv"):
        if skip_long and (len(a) > MAX_LEN or len(b) > 3 * len(a)):
            skipped += 1
            continue
        a, b = a[:MAX_LEN], b[: 3 * min(len(a), MAX_LEN)]
        src_flat += bytes(s2i.get(c, 1) for c in a)
        tgt_flat += bytes(t2i.get(c, 1) for c in b)
        src_off.append(len(src_flat))
        tgt_off.append(len(tgt_flat))
    np.savez(BIN / f"{name}.npz",
             src=np.frombuffer(bytes(src_flat), dtype=np.uint8),
             tgt=np.frombuffer(bytes(tgt_flat), dtype=np.uint8),
             src_off=np.array(src_off, dtype=np.int64),
             tgt_off=np.array(tgt_off, dtype=np.int64))
    print(f"{name}: {len(src_off) - 1:,} pairs, skipped {skipped:,}", flush=True)


def main():
    BIN.mkdir(parents=True, exist_ok=True)
    if (BIN / "vocab.json").exists():  # keep ids stable so trained models stay compatible
        v = json.loads((BIN / "vocab.json").read_text(encoding="utf-8"))
        s, t = v["src"], v["tgt"]
    else:
        s, t = build_vocab()
    (BIN / "vocab.json").write_text(json.dumps({"src": s, "tgt": t, "max_len": MAX_LEN}, ensure_ascii=False), encoding="utf-8")
    print(f"src vocab {len(s)}  tgt vocab {len(t)}", flush=True)
    s2i = {c: i for i, c in enumerate(s)}
    t2i = {c: i for i, c in enumerate(t)}
    for name in ["val", "test_real", "test_aueb_friends", "train"]:
        encode_split(name, s2i, t2i, skip_long=(name in ("train", "val")))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
