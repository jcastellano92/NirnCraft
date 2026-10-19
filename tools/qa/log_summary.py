"""Condenses a Minecraft/Forge log into a few lines for the QA harness.

usage: py -3 -I tools/qa/log_summary.py <log file> [crash-reports dir]
Prints 'FAIL ...' lines for real problems (crash reports, NirnCraft stack traces, failed selftests, registry
errors, missing assets) and short 'info' lines for counts. Known harmless dev-environment noise is ignored.
"""
import os
import re
import sys
from collections import Counter

NOISE = re.compile(r"netty|Reflective setAccessible|mixin/\]: Error cleaning|ForgeConfigSpec|missing mods\.toml|"
                   r"unexpected schema|OFFLINE/INSECURE|online-mode|authenticate usernames|change this, set|fml\.toml",
                   re.I)


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    path = sys.argv[1]
    crash_dir = sys.argv[2] if len(sys.argv) > 2 else None
    if not os.path.exists(path):
        print("FAIL no log at", path)
        return
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    fails, infos = [], []

    for key in ("places of Tamriel", "regions of Nirn"):
        hit = [l for l in lines if key in l]
        if hit:
            infos.append("info " + re.sub(r"^.*?: ", "", hit[-1]))
    done = [l for l in lines if "Done (" in l]
    if done:
        infos.append("info " + re.sub(r"^.*?: ", "", done[-1]))

    # selftest results printed by /nirncraft-quest selftest and /nirncraft magic selftest
    for l in lines:
        if re.search(r"selftest", l, re.I) and re.search(r"(\d+/\d+|problem|OK|fail)", l, re.I) and "Autorun" not in l:
            msg = re.sub(r"^.*?\]: ", "", l)
            if re.search(r"(?i)\b([1-9]\d*) problems?\b|fail(ed)?\b|error", msg) and not re.search(r"(?i)\b0 (problems|errors)", msg):
                fails.append("FAIL selftest: " + msg[:200])
            else:
                infos.append("info selftest: " + msg[:200])

    # exceptions with a NirnCraft frame or registry trouble
    traces = Counter()
    for i, l in enumerate(lines):
        if NOISE.search(l):
            continue
        if re.search(r"Exception|Error:|Caused by", l) and not l.lstrip().startswith("at "):
            window = lines[i:i + 25]
            if any("com.nirncraft" in w for w in window) or re.search(r"Duplicate|registry|NoClassDefFound|Attempted to load class", l):
                traces[re.sub(r"^\[[^\]]*\] \[[^\]]*\] ", "", l.strip())[:180]] += 1
    for msg, n in traces.most_common(12):
        fails.append(f"FAIL x{n} {msg}")

    missing = Counter()
    for l in lines:
        m = re.search(r"(Missing (sound|texture|model)s? .*?|Unable to load model: .*?|Missing textures in model .*?)(\s|$)", l)
        if m and "nirncraft" in l:
            missing[m.group(1)[:140]] += 1
    for msg, n in missing.most_common(6):
        fails.append(f"FAIL x{n} {msg}")

    lag = sum(1 for l in lines if "Can't keep up" in l)
    if lag:
        infos.append(f"info {lag}x 'Can't keep up' (vanilla forceload stalls are normal in tests)")
    warns = Counter(re.sub(r"^\[[^\]]*\] ", "", l)[:120] for l in lines if "/WARN]" in l and "nirncraft" in l.lower() and not NOISE.search(l))
    for msg, n in warns.most_common(5):
        infos.append(f"warn x{n} {msg}")

    if crash_dir and os.path.isdir(crash_dir):
        for f in sorted(os.listdir(crash_dir)):
            fails.append("FAIL crash report " + f)

    for l in fails + infos:
        print(l)
    if not fails:
        print("info no failures found")


if __name__ == "__main__":
    main()
