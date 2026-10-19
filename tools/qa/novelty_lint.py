#!/usr/bin/env python3
"""Novelty lint: flags near-duplicate text in the generated lang files (lore hooks, rumours, greetings, quest pitches,
quest lines, society lines). The "never copy-paste" gate for generator batches (docs/PARITY.md PA2).

  py -3 -I tools/qa/novelty_lint.py            # summary + 10 worst clusters
  py -3 -I tools/qa/novelty_lint.py --top 30   # more clusters
  py -3 -I tools/qa/novelty_lint.py --jaccard 0.55 --skeleton 3
  py -3 -I tools/qa/novelty_lint.py --strict   # exit 1 if any pair or skeleton is flagged

Pairs: word 4-gram Jaccard above --jaccard between two texts of the same category and the same province (province
comes from a province id in the lang key, else '*'). Skeletons: the first sentence with names, numbers and quoted
words blanked; a skeleton used by more than --skeleton texts of one category is flagged. Run `node tools/place_lore.js`
and `node tools/quest_lang.js` first so the lang files are current. Standard library only.
"""
import argparse
import collections
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LANG = os.path.join(ROOT, "mod", "src", "main", "lang", "nirncraft")
FILES = ["place_lore", "society", "quest", "quest_story", "lore"]
PROVINCES = ["skyrim", "cyrodiil", "morrowind", "black_marsh", "elsweyr", "valenwood", "summerset", "hammerfell",
             "high_rock", "atmora", "akavir", "yokuda", "solstheim"]
MIN_WORDS = 8


def category(key):
    last = key.split(".")[-1]
    if last == "hook":
        return "hook"
    if last in ("rumour", "rumor") or ".rumour." in key or ".rumor." in key:
        return "rumour"
    if last in ("greet", "greeting") or ".greet" in key:
        return "greeting"
    if last in ("pitch", "offer"):
        return "pitch"
    if last in ("desc", "journal", "outro", "reply"):
        return "quest"
    if ".legend." in key:
        return "legend"
    if last == "text" or last.isdigit() or (last[:1] == "s" and last[1:].isdigit()):
        return "line"
    return None


def province(key):
    for p in PROVINCES:
        if p in key:
            return p
    return "*"


def words(t):
    return re.findall(r"[a-z']+", t.lower())


def shingles(w):
    return {" ".join(w[i:i + 4]) for i in range(len(w) - 3)}


def skeleton(t):
    s = re.split(r"(?<=[.!?])\s", t.strip(), 1)[0]
    s = re.sub(r"%\d*\$?[sd]", "X", s)
    s = re.sub(r"\b\d+\b", "N", s)
    s = re.sub(r'"[^"]*"', '"Q"', s)
    ws = s.split()
    out = []
    for i, w in enumerate(ws):
        out.append("X" if i > 0 and w[:1].isupper() else w.lower())
    return " ".join(out[:9]) if len(out) >= 6 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--top", type=int, default=10)
    ap.add_argument("--jaccard", type=float, default=0.55)
    ap.add_argument("--skeleton", type=int, default=3)
    ap.add_argument("--strict", action="store_true")
    a = ap.parse_args()

    items = []  # (file, key, cat, prov, text)
    for f in FILES:
        p = os.path.join(LANG, f + ".json")
        if not os.path.exists(p):
            continue
        with open(p, encoding="utf8") as fh:
            for k, v in json.load(fh).items():
                if not isinstance(v, str):
                    continue
                c = category(k)
                if c and len(words(v)) >= MIN_WORDS:
                    items.append((f, k, c, province(k), v))

    parent = list(range(len(items)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    sh = [shingles(words(it[4])) for it in items]
    groups = collections.defaultdict(list)
    for i, it in enumerate(items):
        groups[(it[2], it[3])].append(i)
    pairs = []
    for ids in groups.values():
        inv = collections.defaultdict(list)
        for i in ids:
            for s in sh[i]:
                inv[s].append(i)
        cand = collections.Counter()
        for lst in inv.values():
            if 1 < len(lst) <= 40:
                for x in range(len(lst)):
                    for y in range(x + 1, len(lst)):
                        cand[(lst[x], lst[y])] += 1
        for (i, j), n in cand.items():
            if n < 2:
                continue
            u = len(sh[i] | sh[j])
            jac = n / u if u else 0
            if jac > a.jaccard:
                pairs.append((jac, i, j))
                parent[find(i)] = find(j)
    clusters = collections.defaultdict(set)
    for _, i, j in pairs:
        clusters[find(i)].update((i, j))
    ranked = sorted((sorted(c) for c in clusters.values()), key=lambda c: -len(c))

    skel = collections.defaultdict(list)
    for i, it in enumerate(items):
        s = skeleton(it[4])
        if s:
            skel[(it[2], s)].append(i)
    heavy = sorted(((len(v), k, v) for k, v in skel.items() if len(v) > a.skeleton), reverse=True)

    cat_n = collections.Counter(it[2] for it in items)
    print("novelty_lint: %d texts (%s)" % (len(items), ", ".join("%s %d" % kv for kv in sorted(cat_n.items()))))
    print("near-duplicate pairs (4-gram Jaccard > %.2f, same category + province): %d in %d clusters"
          % (a.jaccard, len(pairs), len(ranked)))
    print("shared skeletons (> %d uses in a category): %d" % (a.skeleton, len(heavy)))
    print("worst clusters:")
    shown = 0
    for c in ranked[:a.top]:
        it = items[c[0]]
        print("  [%s/%s] %d texts, e.g. %s  \"%s\"" % (it[2], it[3], len(c), it[1], it[4][:90].replace("\n", " ")))
        shown += 1
    for n, (cat, s), ids in heavy[:max(0, a.top - shown)]:
        print("  [%s] skeleton x%d: \"%s ...\"  e.g. %s" % (cat, n, s, items[ids[0]][1]))
    return 1 if a.strict and (pairs or heavy) else 0


if __name__ == "__main__":
    sys.exit(main())
