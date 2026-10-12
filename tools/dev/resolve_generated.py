"""Resolve merge conflicts in index/lang files after merge_worktree.sh: unions JSON-array index files (ours then
theirs), keeps 'ours' for generated lang files (regenerate with node tools/quest_lang.js / place_lore.js).
usage: py -3 -I tools/dev/resolve_generated.py"""
import json, re, subprocess, sys
files = subprocess.run(["git", "diff", "--name-only", "--diff-filter=U"], capture_output=True, text=True).stdout.split()
pat = re.compile(r'<<<<<<< ours\n(.*?)=======\n(.*?)>>>>>>> theirs\n', re.S)
for p in files:
    s = open(p, encoding="utf-8").read()
    if p.endswith("index.json"):
        def f(m):
            o = m.group(1).rstrip("\n").rstrip()
            if not o.endswith(","): o += ","
            return o + "\n" + m.group(2)
        s = pat.sub(f, s)
        arr = json.loads(s)
        seen, out = set(), []
        for x in arr:
            k = json.dumps(x, sort_keys=True)
            if k not in seen: seen.add(k); out.append(x)
        s = json.dumps(out, indent=1, ensure_ascii=False) + "\n"
        print("union", p, len(out))
    elif "/lang/" in p.replace("\\", "/"):
        s = pat.sub(lambda m: m.group(1), s)
        print("ours (regenerate)", p)
    else:
        print("UNRESOLVED", p); continue
    open(p, "w", encoding="utf-8", newline="\n").write(s)
