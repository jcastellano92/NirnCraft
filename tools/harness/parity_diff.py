"""Pixel diff of paired parity captures: py -3 tools/harness/parity_diff.py <gl.png> <vk.png> <outprefix>"""
import json
import sys

import numpy as np
from PIL import Image


def diff(a_path, b_path, out_prefix, threshold=24):
    a = np.asarray(Image.open(a_path).convert('RGB'), dtype=np.int16)
    b = np.asarray(Image.open(b_path).convert('RGB'), dtype=np.int16)
    if a.shape != b.shape: raise SystemExit('size differs: %s vs %s' % (a.shape, b.shape))
    d = np.abs(a - b).max(axis=2)
    heat = np.zeros(a.shape, dtype=np.uint8)
    heat[..., 0] = np.clip(d * 4, 0, 255)
    heat[..., 1] = (d > threshold) * 120
    Image.fromarray(heat).save(out_prefix + '-diff.png')
    h, w = d.shape
    thirds = {'top': d[:h // 3], 'middle': d[h // 3:2 * h // 3], 'bottom': d[2 * h // 3:]}
    result = {'size': [w, h], 'mean_abs_diff': float(d.mean()), 'p99_diff': float(np.percentile(d, 99)),
              'max_diff': int(d.max()), 'fraction_over_%d' % threshold: float((d > threshold).mean()),
              'by_band_fraction_over': {k: float((v > threshold).mean()) for k, v in thirds.items()}}
    open(out_prefix + '-diff.json', 'w').write(json.dumps(result, indent=2))
    return result


if __name__ == '__main__':
    print(json.dumps(diff(sys.argv[1], sys.argv[2], sys.argv[3]), indent=2))
