# The kernel budget's deliberate update (native/shaders/kernel_budget.json, a ratchet: any increase fails the build):
# set the named modules' "spv" rows to this build's numbers, and say what moved. Run it after a build that changed
# those kernels on purpose, and commit the new rows in the same commit with the reason.
#   py -3 native/tools/budget_set.py <module> [<module> ...]        e.g. stage_trace stage_trace_clock
#   py -3 native/tools/budget_set.py --pipe <log> "<pipeline name>"  the "pipe" row (nvidia) from a run's
#        ATLAS_PIPE_STATS=1 log: registers, shared, local (low 32 bits), stack, binary
import collections, json, os, re, subprocess, sys

NATIVE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
BUDGET = os.path.join(NATIVE, "shaders", "kernel_budget.json")


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__ if __doc__ else "usage: budget_set.py <module> ... | --pipe <log> <name>")
        return 2
    d = json.load(open(BUDGET, encoding="utf-8"), object_pairs_hook=collections.OrderedDict)
    if args[0] == "--pipe":
        log, name = args[1], args[2]
        row = None
        for l in open(log, encoding="utf-8", errors="replace"):
            if l.startswith("TRACE pipestats %s[0]:" % name):
                st = dict((k.strip(), int(v)) for k, v in re.findall(r"([A-Za-z ]+)=(\d+);", l))
                row = collections.OrderedDict([("registers", st.get("Register Count", 0)), ("shared", st.get("Shared Memory Size", 0)),
                                               ("local", st.get("Local Memory Size", 0) & 0xFFFFFFFF), ("stack", st.get("Stack Size", 0)),
                                               ("binary", st.get("Binary Size", 0))])
        if row is None:
            print("no 'TRACE pipestats %s[0]' line in %s" % (name, log))
            return 1
        print("pipe nvidia %s: %s -> %s" % (name, dict(d["pipe"]["nvidia"].get(name, {})), dict(row)))
        d["pipe"]["nvidia"][name] = row
    else:
        exe = os.path.join(NATIVE, "build", "Release", "spv_budget.exe")
        if not os.path.exists(exe):
            exe = os.path.join(NATIVE, "build", "spv_budget")
        gen = os.path.join(NATIVE, "build", "gen")
        out = subprocess.run([exe, "none", gen, os.path.join(gen, "spv_modules.txt"), "--write"], capture_output=True, text=True).stdout
        new = json.loads("{" + out + "}")["spv"]
        for k in args:
            if k not in new:
                print("%s: not a module of this build" % k)
                return 1
            old = d["spv"].get(k, {})
            moved = {f: (old.get(f), new[k][f]) for f in new[k] if old.get(f) != new[k][f]}
            print("spv %s: %s" % (k, moved or "unchanged"))
            d["spv"][k] = new[k]
    open(BUDGET, "w", encoding="utf-8", newline="\n").write(json.dumps(d, indent=1) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
