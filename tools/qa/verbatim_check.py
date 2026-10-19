"""Verbatim copy & prose integrity check (DESIGN-PRINCIPLES.md Section 7, R3c.6).

Usage:
  py -3 -I tools/qa/verbatim_check.py [--corpus <dir>] [--shingle-size 8]

Scans:
  - mod/src/main/lang/nirncraft/*.json
  - mod/src/main/resources/nirncraft/lore/**/*.json
  - mod/src/main/resources/nirncraft/quests/**/*.json
  - mod/src/main/resources/nirncraft/realms/books.json

Checks:
  1. Internal prose integrity:
     - No empty strings on required prose keys (hook, title, text, pitch, greet,
       talk, bark, objective, pages, book.*, story.*, dialogue.*).
     - No duplicate full-page texts across distinct book/note IDs.
  2. External corpus check:
     - If .reference/corpus/ (or --corpus) exists, compares all prose strings
       against the corpus by 8-word normalized shingles (lowercase, punctuation
       stripped). Any match of >= 8 consecutive words exits 1.
     - If .reference/corpus/ does not exist, prints a clear
       `corpus: skipped (.reference/corpus not present)` note and exits 0
       after passing internal self-checks.
"""
import argparse
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
LANG_DIR = os.path.join(ROOT, "mod", "src", "main", "lang", "nirncraft")
SKY_DIR = os.path.join(ROOT, "mod", "src", "main", "resources", "nirncraft")
DEFAULT_CORPUS = os.path.join(ROOT, ".reference", "corpus")

PROSE_FIELD_NAMES = {"hook", "title", "text", "pitch", "greet", "talk", "bark", "objective"}
PROSE_KEY_PREFIXES = ("book.", "story.", "dialogue.", "lore.", "quest.")


def norm_words(s):
    return re.findall(r"[a-z0-9]+", str(s).lower())


def shingles_of(words, k=8):
    if len(words) < k:
        return set()
    return {" ".join(words[i:i + k]) for i in range(len(words) - k + 1)}


def jload(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def collect_target_files():
    files = set()
    files.update(glob.glob(os.path.join(LANG_DIR, "*.json")))
    files.update(glob.glob(os.path.join(SKY_DIR, "lore", "**", "*.json"), recursive=True))
    files.update(glob.glob(os.path.join(SKY_DIR, "quests", "**", "*.json"), recursive=True))
    books_path = os.path.join(SKY_DIR, "realms", "books.json")
    if os.path.exists(books_path):
        files.add(books_path)
    return sorted(f for f in files if os.path.isfile(f))


def load_corpus_shingles(corpus_dir, k=8):
    shingles = {}
    for root_dir, _, fnames in os.walk(corpus_dir):
        for fn in sorted(fnames):
            if not fn.endswith((".txt", ".json", ".md", ".csv")):
                continue
            fpath = os.path.join(root_dir, fn)
            rel = os.path.relpath(fpath, corpus_dir)
            try:
                raw = open(fpath, "r", encoding="utf-8", errors="replace").read()
                if fn.endswith(".json"):
                    data = json.loads(raw)
                    texts = []

                    def walk(o):
                        if isinstance(o, str):
                            texts.append(o)
                        elif isinstance(o, dict):
                            for v in o.values():
                                walk(v)
                        elif isinstance(o, list):
                            for v in o:
                                walk(v)

                    walk(data)
                else:
                    texts = [raw]
                for t in texts:
                    w = norm_words(t)
                    for i in range(len(w) - k + 1):
                        sh = " ".join(w[i:i + k])
                        shingles.setdefault(sh, rel)
            except Exception:
                continue
    return shingles


def main(argv=None):
    ap = argparse.ArgumentParser(description="Verbatim copy & prose integrity check.")
    ap.add_argument("--corpus", default=DEFAULT_CORPUS, help="Path to reference corpus directory")
    ap.add_argument("--shingle-size", type=int, default=8, help="Word shingle length (default: 8)")
    args = ap.parse_args(argv)

    k = args.shingle_size
    target_files = collect_target_files()

    total_strings = 0
    total_words = 0
    empty_errors = []
    dup_errors = []
    seen_full_texts = {}  # normalized_full_text -> (doc_id, rel_path)
    all_prose_entries = []  # (rel_path, key_path, text)

    for fpath in target_files:
        rel = os.path.relpath(fpath, ROOT).replace("\\", "/")
        try:
            data = jload(fpath)
        except Exception as e:
            empty_errors.append(f"{rel}: failed to parse JSON: {e}")
            continue

        # Check duplicate full-page texts across distinct book/note IDs
        if "/lore/places/" in rel and isinstance(data, dict):
            for pid, pl in (data.get("places") or {}).items():
                if not isinstance(pl, dict):
                    continue
                for idx, n in enumerate(pl.get("notes") or []):
                    if not isinstance(n, dict):
                        continue
                    nid = n.get("id") or f"{pid}#{idx}"
                    pages = n.get("pages") or []
                    if not pages or any(not str(pg).strip() for pg in pages):
                        empty_errors.append(f"{rel}:{nid}: empty page in note")
                    full = " ".join( norm_words(" ".join(str(pg) for pg in pages)) )
                    if len(full) >= 24:
                        prev = seen_full_texts.get(full)
                        if prev and prev[0] != nid:
                            dup_errors.append(f"{rel}:{nid} duplicates full-page text of {prev[1]}:{prev[0]}")
                        seen_full_texts[full] = (nid, rel)

        if rel.endswith("realms/books.json") and isinstance(data, list):
            for b in data:
                if not isinstance(b, dict):
                    continue
                bid = b.get("id") or b.get("title") or ""
                if not bid.strip():
                    empty_errors.append(f"{rel}: realm book missing id")
                if "pages" in b:
                    pages = b.get("pages") or []
                    if not pages or any(not str(pg).strip() for pg in pages):
                        empty_errors.append(f"{rel}:{bid}: empty page in realm book")
                    full = " ".join(norm_words(" ".join(str(pg) for pg in pages)))
                    if len(full) >= 24:
                        prev = seen_full_texts.get(full)
                        if prev and prev[0] != bid:
                            dup_errors.append(f"{rel}:{bid} duplicates full-page text of {prev[1]}:{prev[0]}")
                        seen_full_texts[full] = (bid, rel)

        def walk_strings(obj, kpath, field_name=""):
            nonlocal total_strings, total_words
            if isinstance(obj, str):
                total_strings += 1
                w = norm_words(obj)
                total_words += len(w)
                is_req = (field_name in PROSE_FIELD_NAMES) or any(kpath.startswith(px) for px in PROSE_KEY_PREFIXES)
                if is_req and not obj.strip():
                    empty_errors.append(f"{rel}:{kpath}: required prose string is empty")
                if len(w) >= k:
                    all_prose_entries.append((rel, kpath, w))
            elif isinstance(obj, dict):
                for dk, dv in obj.items():
                    sub = f"{kpath}.{dk}" if kpath else dk
                    walk_strings(dv, sub, dk)
            elif isinstance(obj, list):
                for idx, lv in enumerate(obj):
                    walk_strings(lv, f"{kpath}[{idx}]", field_name)

        walk_strings(data, "")

    shingle_hits = []
    corpus_present = os.path.isdir(args.corpus)
    if corpus_present:
        corpus_shingles = load_corpus_shingles(args.corpus, k=k)
        for rel, kpath, words in all_prose_entries:
            for i in range(len(words) - k + 1):
                sh = " ".join(words[i:i + k])
                if sh in corpus_shingles:
                    shingle_hits.append((rel, kpath, sh, corpus_shingles[sh]))
                    break
        corpus_note = f"checked ({len(corpus_shingles)} {k}-word shingles in {args.corpus})"
    else:
        corpus_note = "skipped (.reference/corpus not present)"

    print(f"verbatim_check: scanned {len(target_files)} files, {total_strings} strings, {total_words} words, {len(seen_full_texts)} distinct book/note texts.")
    print(f"corpus: {corpus_note}")

    if empty_errors:
        for e in empty_errors[:20]:
            print("  FAIL (empty prose):", e)
    if dup_errors:
        for e in dup_errors[:20]:
            print("  FAIL (duplicate text):", e)
    if shingle_hits:
        for rel, kpath, sh, src in shingle_hits[:20]:
            print(f"  FAIL (verbatim {k}-word match): {rel}:{kpath} matched {src!r} on {sh!r}")

    total_errs = len(empty_errors) + len(dup_errors) + len(shingle_hits)
    if total_errs:
        print(f"verbatim_check: FAIL ({total_errs} issue(s))")
        return 1
    print("verbatim_check: PASS (0 empty required prose strings, 0 duplicate book/note texts, 0 verbatim matches)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
