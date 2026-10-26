# The kernels' source lint (warns; the build's spv_budget and the pipeline budget fail): today's trap patterns in
# native/shaders/*.slang, with a fix hint each. A line (or the line before it) with "// lint-ok: <reason>" is let be.
#   py -3 native/tools/shader_lint.py [--strict]      (--strict: exit 1 on any finding; the harness runs it plain)
# Patterns (docs/AGENT-RULES.md, docs/ATLAS-FAR-KERNEL-REVIEW.md):
#   noinline-struct  a [noinline] function taking a struct (by value, out or inout): a real call passes it through
#                    memory (the far kernel's shared memory 7936 -> 16640 bytes a group, its far pass 40 % slower)
#   static-mut       a mutable static at file scope: thread-private, shared across a real call it is local memory
#   groupshared      groupshared in the trace kernels' files: the shared memory the walk's L1 gives up
#   loop-heavy       a heavy call (a walk, the smooth search, a micro-voxel walk, a texture tap) inside a loop: lanes
#                    that diverge in the loop wait for it one after another; return a candidate, refine after the loop
import glob, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SHADERS = os.path.join(HERE, "..", "shaders")
TRACE_FILES = {"atlas_far.slang", "atlas_groups.slang", "atlas_bricks.slang", "atlas_actors.slang", "atlas_clouds.slang", "atlas_air.slang"}
HEAVY = ["traceView", "traceViewBody", "traceLod", "traceLodS", "traceLodCore", "traceLodBody", "traceVoxGrid", "traceRegion",
         "traceGroups", "traceSecondary", "traceSecondaryCore", "coarseSmoothHit", "brickHit", "brickWalk", "vgShapeHit",
         "texSample", "vgTexel", "cumulusDensity"]
LOOP_HEAVY_FILES = {"atlas_far.slang", "atlas_groups.slang", "atlas_bricks.slang"}


def strip_comments(line):
    i = line.find("//")
    return line if i < 0 else line[:i]


def main():
    strict = "--strict" in sys.argv
    files = sorted(glob.glob(os.path.join(SHADERS, "*.slang")))
    structs = set()
    for f in files:
        for m in re.finditer(r"^\s*struct\s+(\w+)", open(f, encoding="utf-8").read(), re.M):
            structs.add(m.group(1))
    findings = []
    heavy_re = re.compile(r"\b(" + "|".join(HEAVY) + r")\s*\(")
    for f in files:
        name = os.path.basename(f)
        lines = open(f, encoding="utf-8").read().splitlines()

        def ok(i):
            return "lint-ok:" in lines[i] or (i > 0 and "lint-ok:" in lines[i - 1])

        def add(i, kind, hint):
            if not ok(i):
                findings.append("%s:%d: %s: %s" % (name, i + 1, kind, hint))

        depth = 0
        loops = []  # brace depths at which a loop body opened
        pending_loop = False
        noinline_next = False
        for i, raw in enumerate(lines):
            line = strip_comments(raw)
            st = line.strip()
            if st.startswith("[noinline]"):
                noinline_next = True
                st = st[len("[noinline]"):].strip()
                if not st:
                    continue
            if noinline_next and "(" in st:
                noinline_next = False
                sig = st
                j = i
                while ")" not in sig and j + 1 < len(lines):
                    j += 1
                    sig += " " + strip_comments(lines[j]).strip()
                m = re.match(r"[\w<>\s]+?\b(\w+)\s*\((.*?)\)", sig)
                if m:
                    for p in m.group(2).split(","):
                        toks = p.replace("inout ", "").replace("out ", "").replace("in ", "").split()
                        if len(toks) >= 2 and toks[-2] in structs:
                            add(i, "noinline-struct", "%s takes %s %s: a real call passes it through memory; inline it, or pass "
                                "scalars" % (m.group(1), toks[-2], toks[-1]))
            if depth == 0 and re.match(r"static\s+(?!const\b)", st) and not st.startswith("static const"):
                add(i, "static-mut", "a mutable static: pass the state as a parameter, or make it the root's own (AGENT-RULES)")
            if name in TRACE_FILES and re.search(r"\bgroupshared\b", line):
                add(i, "groupshared", "groupshared in a trace kernel's file: the shared memory the walk's L1 gives up")
            if name in LOOP_HEAVY_FILES and loops:
                for m in heavy_re.finditer(line):
                    if not re.match(r"\s*(TRACE_CALL\s+)?[\w<>]+\s+" + m.group(1) + r"\s*\(", line):  # (not its own definition)
                        add(i, "loop-heavy", "%s inside a loop: return a candidate from the loop and run it once after" % m.group(1))
            if re.match(r"(for|while)\s*\(", st) or re.match(r"do\s*\{?", st):
                pending_loop = True
            for ch in line:
                if ch == "{":
                    depth += 1
                    if pending_loop:
                        loops.append(depth)
                        pending_loop = False
                elif ch == "}":
                    if loops and loops[-1] == depth:
                        loops.pop()
                    depth -= 1
            if pending_loop and st.endswith(";"):
                pending_loop = False  # (a one-line loop body)
    for x in findings:
        print("shader_lint: " + x)
    kinds = {}
    for x in findings:
        k = x.split(": ")[1]
        kinds[k] = kinds.get(k, 0) + 1
    print("shader_lint: %d finding(s) %s (warnings; '// lint-ok: <reason>' lets a line be)" % (len(findings), kinds))
    return 1 if strict and findings else 0


if __name__ == "__main__":
    sys.exit(main())
