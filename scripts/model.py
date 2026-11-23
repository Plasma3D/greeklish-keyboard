"""Tiny non-autoregressive Greeklish -> Greek model (CTC).

Encoder reads Latin chars; each position is expanded into UPSAMPLE output slots,
a couple more layers mix the slots, and CTC aligns them to the Greek target.
One forward pass per conversion, so it is cheap enough to run on every keystroke.
"""
import math

import torch
import torch.nn as nn


class GreeklishCTC(nn.Module):
    def __init__(self, src_vocab, tgt_vocab, d=256, heads=4, enc_layers=6, dec_layers=2,
                 ff=1024, upsample=3, max_len=128, dropout=0.1):
        super().__init__()
        self.upsample = upsample
        self.d = d
        self.emb = nn.Embedding(src_vocab, d, padding_idx=0)
        self.pos = nn.Embedding(max_len, d)
        layer = lambda: nn.TransformerEncoderLayer(d, heads, ff, dropout, batch_first=True,
                                                   norm_first=True, activation="gelu")
        self.enc = nn.TransformerEncoder(layer(), enc_layers, enable_nested_tensor=False)
        self.expand = nn.Linear(d, d * upsample)
        self.slot = nn.Embedding(upsample, d)
        self.pos_up = nn.Embedding(max_len * upsample, d)
        self.dec = nn.TransformerEncoder(layer(), dec_layers, enable_nested_tensor=False)
        self.norm = nn.LayerNorm(d)
        self.out = nn.Linear(d, tgt_vocab)

    def forward(self, src, src_mask=None):
        """src: (B, L) int64, 0 = pad. Returns log-probs (B, L*U, V)."""
        B, L = src.shape
        if src_mask is None:
            src_mask = src == 0
        pos = torch.arange(L, device=src.device)
        x = self.emb(src) * math.sqrt(self.d) + self.pos(pos)[None]
        x = self.enc(x, src_key_padding_mask=src_mask)
        U = self.upsample
        x = self.expand(x).view(B, L, U, self.d) + self.slot.weight[None, None]
        x = x.reshape(B, L * U, self.d)
        x = x + self.pos_up(torch.arange(L * U, device=src.device))[None]
        up_mask = src_mask.repeat_interleave(U, dim=1)
        x = self.dec(x, src_key_padding_mask=up_mask)
        return self.out(self.norm(x)).log_softmax(-1)


def script_masks(tgt_vocab):
    """Boolean masks (V,) of target symbols allowed in a Greek word / a Latin word."""
    import re
    greek = torch.tensor([bool(re.match(r"[Ͱ-Ͽἀ-῿]", c)) for c in tgt_vocab])
    latin = torch.tensor([bool(re.match(r"[A-Za-z]", c)) for c in tgt_vocab])
    return ~latin, ~greek  # greek-word mask allows everything except Latin letters, and vice versa


def decode_words(logp, src_text, upsample, masks):
    """Greedy CTC, but each space-separated input word is decoded entirely in Greek or
    entirely in Latin script (whichever scores higher), so we never get 'πoυ'.
    logp: (T, V) for one sample. Returns list of target ids."""
    allow_gr, allow_la = masks
    out, start = [], 0
    spans = []
    for i, ch in enumerate(src_text + " "):
        if ch == " ":
            if i > start:
                spans.append((start, i, False))
            if i < len(src_text):
                spans.append((i, i + 1, True))
            start = i + 1
    prev = -1
    for a, b, is_space in spans:
        seg = logp[a * upsample:b * upsample]
        if is_space:
            best = seg.argmax(-1)
        else:
            g = seg.masked_fill(~allow_gr, -1e9).max(-1)
            l = seg.masked_fill(~allow_la, -1e9).max(-1)
            best = g.indices if g.values.sum() >= l.values.sum() else l.indices
        for t in best.tolist():
            if t != prev and t != 0:
                out.append(t)
            prev = t
    return out


def ctc_greedy(logp, lengths):
    """logp: (B, T, V); lengths: (B,) valid T. Returns list of index lists."""
    best = logp.argmax(-1).cpu().tolist()
    out = []
    for seq, n in zip(best, lengths.tolist()):
        res, prev = [], -1
        for t in seq[:n]:
            if t != prev and t != 0:
                res.append(t)
            prev = t
        out.append(res)
    return out
