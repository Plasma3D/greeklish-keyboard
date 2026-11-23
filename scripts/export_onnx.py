"""Export a checkpoint to ONNX (+ int8 quantized) for Android, and check it matches PyTorch.

  .venv/Scripts/python.exe scripts/export_onnx.py checkpoints/g2g_v2_best.pt
Writes export/greeklish.onnx, export/greeklish.int8.onnx, export/vocab.json
"""
import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic

sys.path.insert(0, str(Path(__file__).resolve().parent))
from evaluate import load  # noqa: E402
from model import decode_words, script_masks  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "export"


class Wrapper(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, src):  # single sentence, no padding: (1, L) int64 -> (1, L*U, V) log-probs
        return self.m(src, src_mask=torch.zeros_like(src, dtype=torch.bool))


def run(sess, vocab, text, masks, U):
    s2i = {c: i for i, c in enumerate(vocab["src"])}
    ids = np.array([[s2i.get(c, 1) for c in text[: vocab["max_len"]]]], dtype=np.int64)
    logp = sess.run(None, {"src": ids})[0][0]
    out = decode_words(torch.from_numpy(logp), text, U, masks)
    return "".join(vocab["tgt"][i] for i in out if i > 1)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    model, vocab = load(sys.argv[1])
    model = model.cpu().float().eval()
    OUT.mkdir(exist_ok=True)
    fp32, int8 = OUT / "greeklish.onnx", OUT / "greeklish.int8.onnx"
    dummy = torch.randint(2, len(vocab["src"]), (1, 20))
    L = torch.export.Dim("len", min=1, max=vocab["max_len"])
    prog = torch.onnx.export(Wrapper(model), (dummy,), input_names=["src"], output_names=["logp"],
                             dynamic_shapes={"src": {1: L}}, opset_version=18, dynamo=True)
    prog.save(str(fp32))
    quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QInt8)
    (OUT / "vocab.json").write_text(json.dumps({**vocab, "upsample": model.upsample}, ensure_ascii=False), encoding="utf-8")
    print(f"fp32 {fp32.stat().st_size / 1e6:.1f} MB   int8 {int8.stat().st_size / 1e6:.1f} MB")

    masks = script_masks(vocab["tgt"])
    tests = ["kalimera ti kaneis?", "nai to jero", "8a se dw avrio sto gym", "gt den mou apantas?",
             "eimai poly kourasmenos simera", "pame gia kafe meta?"]
    for path in (fp32, int8):
        sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        t0 = time.perf_counter()
        outs = [run(sess, vocab, t, masks, model.upsample) for t in tests]
        ms = (time.perf_counter() - t0) / len(tests) * 1000
        print(f"\n{path.name}: {ms:.1f} ms/sentence on PC CPU")
        for t, o in zip(tests, outs):
            print(f"   {t:35} -> {o}")


if __name__ == "__main__":
    main()
