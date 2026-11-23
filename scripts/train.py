"""Train the Greeklish -> Greek CTC model.

  .venv/Scripts/python.exe scripts/train.py --steps 60000
  .venv/Scripts/python.exe scripts/train.py --steps 300 --eval-every 100   (smoke test)
"""
import argparse
import json
import math
import sys
import time
import unicodedata
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

sys.path.insert(0, str(Path(__file__).resolve().parent))
from model import GreeklishCTC, decode_words, script_masks  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
BIN = ROOT / "data" / "bin"
PROC = ROOT / "data" / "processed"
CKPT = ROOT / "checkpoints"


class Split:
    def __init__(self, name):
        z = np.load(BIN / f"{name}.npz")
        self.src, self.tgt = z["src"], z["tgt"]
        self.so, self.to = z["src_off"], z["tgt_off"]
        self.n = len(self.so) - 1
        self.slen = np.diff(self.so)

    def batch(self, idx):
        idx = np.asarray(idx)
        L = int(self.slen[idx].max())
        src = np.zeros((len(idx), L), dtype=np.int64)
        tg, tl = [], []
        for j, i in enumerate(idx):
            s = self.src[self.so[i]:self.so[i + 1]]
            src[j, :len(s)] = s
            t = self.tgt[self.to[i]:self.to[i + 1]]
            tg.append(t)
            tl.append(len(t))
        return (torch.from_numpy(src), torch.from_numpy(np.concatenate(tg).astype(np.int64)),
                torch.tensor(tl, dtype=torch.long), torch.from_numpy(self.slen[idx].astype(np.int64)))


def length_bucketed_batches(split, tokens_per_batch, rng):
    """Yield index arrays of similar-length samples, ~tokens_per_batch src chars each."""
    while True:
        perm = rng.permutation(split.n)
        for c in range(0, len(perm), 200_000):
            chunk = perm[c:c + 200_000]
            chunk = chunk[np.argsort(split.slen[chunk], kind="stable")]
            batches, i = [], 0
            while i < len(chunk):
                L = max(int(split.slen[chunk[min(i + 63, len(chunk) - 1)]]), 1)
                bs = min(512, max(16, tokens_per_batch // max(L, 8)))
                batches.append(chunk[i:i + bs])
                i += bs
            for b in rng.permutation(len(batches)):
                yield batches[b]


def strip(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def score(preds, refs):
    """CER, word accuracy, word accuracy ignoring case+accents."""
    ce = cl = wc = wt = wl = 0
    for p, r in zip(preds, refs):
        ce += edit_distance(p, r)
        cl += len(r)
        pw, rw = p.split(), r.split()
        for a, b in zip(pw, rw):
            wc += a == b
            wl += strip(a.lower()) == strip(b.lower())
        wt += max(len(rw), 1)
    return ce / max(cl, 1), wc / max(wt, 1), wl / max(wt, 1)


@torch.no_grad()
def convert(model, texts, vocab, device, bs=256):
    s2i = {c: i for i, c in enumerate(vocab["src"])}
    tgt = vocab["tgt"]
    masks = script_masks(tgt)
    U = model.upsample
    out = []
    model.eval()
    for k in range(0, len(texts), bs):
        chunk = [t[: vocab["max_len"]] for t in texts[k:k + bs]]
        L = max(len(t) for t in chunk)
        src = torch.zeros(len(chunk), L, dtype=torch.long)
        for j, t in enumerate(chunk):
            src[j, :len(t)] = torch.tensor([s2i.get(c, 1) for c in t])
        with torch.autocast("cuda", dtype=torch.bfloat16):
            logp = model(src.to(device))
        logp = logp.float().cpu()
        for j, t in enumerate(chunk):
            ids = decode_words(logp[j, :len(t) * U], t, U, masks)
            out.append("".join(tgt[i] if i > 1 else "?" for i in ids))
    model.train()
    return out


def load_tsv(name, limit=None):
    pairs = [l.rstrip("\n").split("\t") for l in open(PROC / f"{name}.tsv", encoding="utf-8")]
    pairs = [p for p in pairs if len(p) == 2][:limit]
    return [p[0] for p in pairs], [p[1] for p in pairs]


def evaluate(model, vocab, device, show=0):
    res = {}
    for name, limit in [("val", 3000), ("test_real", None), ("test_aueb_friends", None)]:
        src, ref = load_tsv(name, limit)
        pred = convert(model, src, vocab, device)
        res[name] = score(pred, ref)
        if show and name == "test_real":
            for s, p, r in list(zip(src, pred, ref))[:show]:
                print(f"   IN  {s}\n   OUT {p}\n   REF {r}")
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--steps", type=int, default=60000)
    ap.add_argument("--tokens", type=int, default=16000, help="src chars per batch")
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--warmup", type=int, default=2000)
    ap.add_argument("--d", type=int, default=256)
    ap.add_argument("--enc", type=int, default=6)
    ap.add_argument("--dec", type=int, default=2)
    ap.add_argument("--eval-every", type=int, default=2000)
    ap.add_argument("--name", default="g2g")
    ap.add_argument("--init", default=None, help="checkpoint to fine-tune from")
    args = ap.parse_args()

    torch.manual_seed(0)
    device = "cuda"
    vocab = json.loads((BIN / "vocab.json").read_text(encoding="utf-8"))
    train = Split("train")
    model = GreeklishCTC(len(vocab["src"]), len(vocab["tgt"]), d=args.d, heads=args.d // 64,
                         enc_layers=args.enc, dec_layers=args.dec, ff=args.d * 4,
                         max_len=vocab["max_len"]).to(device)
    if args.init:
        model.load_state_dict(torch.load(args.init, map_location=device, weights_only=False)["model"])
        print(f"fine-tuning from {args.init}", flush=True)
    nparams = sum(p.numel() for p in model.parameters())
    print(f"params {nparams / 1e6:.2f}M   train pairs {train.n:,}", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, betas=(0.9, 0.98), weight_decay=0.01)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1, (s + 1) / args.warmup) *
                                              0.5 * (1 + math.cos(math.pi * min(s, args.steps) / args.steps)))
    CKPT.mkdir(exist_ok=True)
    rng = np.random.default_rng(0)
    batches = length_bucketed_batches(train, args.tokens, rng)
    t0, run_loss, best = time.time(), 0.0, -1
    for step in range(1, args.steps + 1):
        src, tgt, tlen, slen = train.batch(next(batches))
        src = src.to(device, non_blocking=True)
        with torch.autocast("cuda", dtype=torch.bfloat16):
            logp = model(src)
        loss = F.ctc_loss(logp.float().transpose(0, 1), tgt.to(device), (slen * model.upsample).to(device),
                          tlen.to(device), blank=0, zero_infinity=True)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        run_loss += loss.item()
        if step % 200 == 0:
            el = time.time() - t0
            print(f"step {step:>6}  loss {run_loss / 200:.4f}  lr {sched.get_last_lr()[0]:.2e}  "
                  f"{step / el:.1f} it/s  eta {(args.steps - step) / (step / el) / 60:.0f}m", flush=True)
            run_loss = 0.0
        if step % args.eval_every == 0 or step == args.steps:
            r = evaluate(model, vocab, device, show=4 if step == args.steps else 0)
            line = "  ".join(f"{k}: CER {v[0]:.3%} word {v[1]:.1%} (no-accent {v[2]:.1%})" for k, v in r.items())
            print(f"== eval step {step}  {line}", flush=True)
            state = {"model": model.state_dict(), "args": vars(args), "vocab": vocab, "step": step, "eval": r}
            torch.save(state, CKPT / f"{args.name}_last.pt")
            if r["val"][1] > best:
                best = r["val"][1]
                torch.save(state, CKPT / f"{args.name}_best.pt")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
