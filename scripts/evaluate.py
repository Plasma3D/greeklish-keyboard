"""Fair evaluation on real Greeklish: align words (not positions), score only Greek reference words.

  .venv/Scripts/python.exe scripts/evaluate.py checkpoints/g2g_best.pt [n_errors_to_show]
"""
import re
import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from model import GreeklishCTC  # noqa: E402
from train import convert, load_tsv, strip  # noqa: E402

WORD = re.compile(r"[\w']+", re.UNICODE)
GREEK = re.compile(r"[Ͱ-Ͽἀ-῿]")


def align(p, r):
    """Levenshtein alignment of word lists -> list of (pred_word|None, ref_word|None)."""
    n, m = len(p), len(r)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            sub = 0 if strip(p[i - 1].lower()) == strip(r[j - 1].lower()) else 1
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + sub)
    i, j, out = n, m, []
    while i or j:
        if i and j and d[i][j] == d[i - 1][j - 1] + (0 if strip(p[i - 1].lower()) == strip(r[j - 1].lower()) else 1):
            out.append((p[i - 1], r[j - 1])); i -= 1; j -= 1
        elif i and d[i][j] == d[i - 1][j] + 1:
            out.append((p[i - 1], None)); i -= 1
        else:
            out.append((None, r[j - 1])); j -= 1
    return out[::-1]


def load(path):
    st = torch.load(path, map_location="cuda", weights_only=False)
    a, v = st["args"], st["vocab"]
    m = GreeklishCTC(len(v["src"]), len(v["tgt"]), d=a["d"], heads=a["d"] // 64, enc_layers=a["enc"],
                     dec_layers=a["dec"], ff=a["d"] * 4, max_len=v["max_len"]).cuda()
    m.load_state_dict(st["model"])
    return m.eval(), v


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    model, vocab = load(sys.argv[1])
    show = int(sys.argv[2]) if len(sys.argv) > 2 else 25
    for name in ["test_real", "test_aueb_friends"]:
        src, ref = load_tsv(name)
        pred = convert(model, src, vocab, "cuda")
        exact = noacc = total = 0
        errors = []
        for s, p, r in zip(src, pred, ref):
            for pw, rw in align(WORD.findall(p), WORD.findall(r)):
                if rw is None or not GREEK.search(rw):
                    continue  # only judge Greek words people meant to write
                total += 1
                if pw is not None and pw.lower() == rw.lower():
                    exact += 1
                elif pw is not None and strip(pw.lower()) == strip(rw.lower()):
                    noacc += 1
                    errors.append(("accent", pw, rw))
                else:
                    errors.append(("wrong", pw, rw))
        print(f"{name}: {total} Greek words   exact {exact / total:.1%}   "
              f"right-but-accent {noacc / total:.1%}   wrong {(total - exact - noacc) / total:.1%}")
        if name == "test_real":
            for kind, pw, rw in errors[:show]:
                print(f"   {kind:6} got {pw!s:18} want {rw}")


if __name__ == "__main__":
    main()
