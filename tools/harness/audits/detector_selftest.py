"""Self-test of the harness's image detectors on synthetic fixtures: each defect must be flagged, a clean frame must
not be. A detector that stops seeing what it should (or starts crying wolf) fails here before it fools a review.

    py -3 tools/harness/audits/detector_selftest.py
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import results  # noqa: E402


def frame(seed=1):
    rng = np.random.default_rng(seed)
    sky = np.linspace(170, 230, 360)[:, None, None] * np.array([0.7, 0.8, 1.0])
    ground = 80 + rng.normal(0, 18, (360, 640, 3))
    img = np.concatenate([np.repeat(sky, 640, axis=1), ground], axis=0)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def with_box(img):
    a = np.asarray(img).copy()
    a[200:296, 300:396] = 0
    return Image.fromarray(a)


def with_magenta(img):
    a = np.asarray(img).copy()
    for y in range(400, 464, 8):
        for x in range(100, 164, 8):
            a[y:y + 8, x:x + 8] = (248, 0, 248) if ((x + y) // 8) % 2 == 0 else (0, 0, 0)
    return Image.fromarray(a)


def blown(img):
    return Image.fromarray(np.full_like(np.asarray(img), 255))


def main():
    clean = frame()
    cases = [("clean frame", clean, []), ("black box", with_box(clean), ["black box"]),
             ("missing texture", with_magenta(clean), ["missing texture"]), ("blown out", blown(clean), ["blown out"]),
             ("black frame", Image.new("RGB", (640, 720)), ["black frame"])]
    problems = 0
    for name, img, want in cases:
        flags = results.metrics(img)["flags"]
        ok = all(any(w in f for f in flags) for w in want) and (want or not flags)
        print(f"{'ok  ' if ok else 'FAIL'} {name}: flags {flags}")
        problems += 0 if ok else 1
    a, b = frame(1), frame(1)
    de, luma, struct = results.fidelity(a, b)
    ok = de < 0.5 and abs(1 - luma) < 0.01 and struct < 1
    print(f"{'ok  ' if ok else 'FAIL'} fidelity of identical frames: dE {de} luma {luma} structure {struct}")
    problems += 0 if ok else 1
    print(f"problems {problems}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
