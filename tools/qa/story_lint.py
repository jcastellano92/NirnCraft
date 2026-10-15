"""MS1 Regional Story Chain linter (offline, stdlib only).

Usage:
  py -3 -I tools/qa/story_lint.py [--province <P>] [--region <R>] [--selftest]

Validates every file in mod/src/main/resources/skycraft/quests/story/*.json:
  1. Valid land region in regions.json.
  2. Non-empty "why" on story root and each quest citing existing World Sheet
     L7-L10 fields in tamriel/world/<province>.json.
  3. hook.place (and any hook.places) is a real place in places/*.json in or
     near the region; hook.max_road_blocks <= 800.
  4. len(quests) >= 3.
  5. Acyclic requires DAG with a chain of >= 3 quests ending at a finale: true
     quest with a non-empty consequence.
  6. Every referenced place exists in places/*.json.
  7. No verbatim copied Elder Scrolls quest titles or stage text.
"""
import argparse
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
SKY = os.path.join(RES, "skycraft")
STORY_DIR = os.path.join(SKY, "quests", "story")
WORLD_DIR = os.path.join(SKY, "tamriel", "world")

# Canonical Elder Scrolls quest titles that must never be copied verbatim as story quest titles
BANNED_TES_QUEST_TITLES = {
    "unbound", "before the storm", "bleak falls barrow", "dragon rising",
    "the way of the voice", "the horn of jurgen windcaller", "a blade in the dark",
    "diplomatic immunity", "a cornered rat", "alduin s wall", "the throat of the world",
    "elder knowledge", "alduin s bane", "the fallen", "paarthurnax", "season unending",
    "the world eater s eyrie", "sovngarde", "dragonslayer", "in my time of need",
    "the blessings of nature", "the whispering door", "take up arms", "proving honor",
    "the silver hand", "blood s honor", "purity of revenge", "glory of the dead",
}


def jload(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def norm_words(s):
    return " ".join(re.findall(r"[a-z0-9]+", str(s).lower()))


def load_context():
    regs = {r["id"]: r for r in jload(os.path.join(SKY, "tamriel", "regions.json"), []) if r.get("kind") != "sea"}
    idx = jload(os.path.join(SKY, "tamriel", "places", "index.json"), [])
    places = {}
    for fn in idx:
        for p in jload(os.path.join(SKY, "tamriel", "places", fn), []):
            if isinstance(p, dict) and "id" in p:
                places[p["id"]] = p

    # Load all World Sheet L7-L10 ids per province
    ws_fields = {}
    for wf in glob.glob(os.path.join(WORLD_DIR, "*.json")):
        wj = jload(wf, {})
        if not isinstance(wj, dict):
            continue
        prov = wj.get("province") or os.path.basename(wf)[:-5]
        valid = set()
        for lk, items in (wj.get("layers") or {}).items():
            if lk in ("L7", "L8", "L9", "L10") and isinstance(items, list):
                for it in items:
                    if isinstance(it, dict) and it.get("id"):
                        valid.add(f"{prov}.{lk}.{it['id']}")
        for rk, rdata in (wj.get("regions") or {}).items():
            for lk, items in (rdata.get("layers") or {}).items():
                if lk in ("L7", "L8", "L9", "L10") and isinstance(items, list):
                    for it in items:
                        if isinstance(it, dict) and it.get("id"):
                            valid.add(f"{rk}.{lk}.{it['id']}")
                            valid.add(f"{prov}/{rk}.{lk}.{it['id']}")
        ws_fields[prov] = valid

    return regs, places, ws_fields


def validate_story(data, label, regs, places, ws_fields):
    """Validate a parsed story dict. Returns list of error strings."""
    errs = []
    if not isinstance(data, dict):
        return [f"{label}: root must be a JSON object"]

    rid = data.get("region")
    if not isinstance(rid, str) or rid not in regs:
        errs.append(f"{label}: invalid or unknown land region {rid!r}")
        prov = rid.split("/")[0] if isinstance(rid, str) and "/" in rid else ""
        rshort = rid.split("/")[-1] if isinstance(rid, str) else ""
    else:
        prov = regs[rid]["province"]
        rshort = rid.split("/")[-1]

    valid_ws = ws_fields.get(prov, None)

    def check_why(why_list, ctx):
        if not isinstance(why_list, list) or not why_list:
            errs.append(f"{label} ({ctx}): 'why' must be a non-empty list citing L7-L10")
            return
        for cite in why_list:
            if not isinstance(cite, str) or not re.match(r"^[a-z0-9_/]+\.L(7|8|9|10)\.[a-z0-9_]+$", cite):
                errs.append(f"{label} ({ctx}): citation {cite!r} must match <region|province>.L<7..10>.<id>")
                continue
            prefix = cite.split(".")[0]
            if prefix not in (prov, rshort, rid):
                errs.append(f"{label} ({ctx}): citation {cite!r} does not belong to region {rid!r} or province {prov!r}")
            if valid_ws is not None and valid_ws and cite not in valid_ws:
                errs.append(f"{label} ({ctx}): citation {cite!r} not found in {prov}.json World Sheet L7-L10")

    check_why(data.get("why"), "root")

    # Hook validation
    hook = data.get("hook")
    if not isinstance(hook, dict):
        errs.append(f"{label}: missing 'hook' object")
    else:
        hp = hook.get("place")
        if not isinstance(hp, str) or hp not in places:
            errs.append(f"{label} (hook): hook.place {hp!r} not found in places/*.json")
        else:
            p_obj = places[hp]
            if p_obj.get("region") != rid and p_obj.get("region", "").split("/")[0] != prov:
                errs.append(f"{label} (hook): hook.place {hp!r} is in {p_obj.get('region')!r}, not in/near {rid!r}")
        for extra_p in hook.get("places", []):
            if extra_p not in places:
                errs.append(f"{label} (hook): hook.places entry {extra_p!r} not found in places/*.json")
        if not hook.get("npc") or not isinstance(hook.get("npc"), str):
            errs.append(f"{label} (hook): missing non-empty 'npc'")
        mrb = hook.get("max_road_blocks", 800)
        if not isinstance(mrb, (int, float)) or mrb <= 0 or mrb > 800:
            errs.append(f"{label} (hook): max_road_blocks={mrb!r} must be in (0, 800]")

    # Quests validation
    quests = data.get("quests")
    if not isinstance(quests, list) or len(quests) < 3:
        errs.append(f"{label}: 'quests' must be a list of >= 3 quests (found {len(quests) if isinstance(quests, list) else 0})")
        return errs

    q_by_id = {}
    for idx, q in enumerate(quests):
        if not isinstance(q, dict):
            errs.append(f"{label}: quest[{idx}] is not an object")
            continue
        qid = q.get("id")
        if not isinstance(qid, str) or not qid:
            errs.append(f"{label}: quest[{idx}] missing non-empty 'id'")
            continue
        if qid in q_by_id:
            errs.append(f"{label}: duplicate quest id {qid!r}")
        q_by_id[qid] = q

        title = q.get("title", "")
        if not isinstance(title, str) or not title.strip():
            errs.append(f"{label} ({qid}): missing non-empty 'title'")
        elif norm_words(title) in BANNED_TES_QUEST_TITLES:
            errs.append(f"{label} ({qid}): verbatim Elder Scrolls quest title {title!r}")

        q_places = q.get("places")
        if not isinstance(q_places, list) or not q_places:
            errs.append(f"{label} ({qid}): 'places' must be a non-empty list")
        else:
            for pid in q_places:
                if pid not in places:
                    errs.append(f"{label} ({qid}): place {pid!r} not found in places/*.json")

        stages = q.get("stages")
        if not isinstance(stages, list) or not stages:
            errs.append(f"{label} ({qid}): 'stages' must be a non-empty list")
        else:
            for s_idx, st in enumerate(stages):
                if not isinstance(st, dict):
                    errs.append(f"{label} ({qid} stage[{s_idx}]): must be an object")
                    continue
                sp = st.get("place")
                if sp and sp not in places:
                    errs.append(f"{label} ({qid} stage[{s_idx}]): place {sp!r} not found in places/*.json")
                obj = st.get("objective", "")
                if not isinstance(obj, str) or not obj.strip():
                    errs.append(f"{label} ({qid} stage[{s_idx}]): missing non-empty 'objective'")
                elif norm_words(obj) in BANNED_TES_QUEST_TITLES:
                    errs.append(f"{label} ({qid} stage[{s_idx}]): verbatim TES quest text in objective")

        cons = q.get("consequence")
        if not isinstance(cons, str) or not cons.strip():
            errs.append(f"{label} ({qid}): missing non-empty 'consequence'")

        check_why(q.get("why"), qid)

    # Requires DAG & chain length >= 3 ending at finale: true
    for qid, q in q_by_id.items():
        reqs = q.get("requires", [])
        if not isinstance(reqs, list):
            errs.append(f"{label} ({qid}): 'requires' must be a list")
            continue
        for r in reqs:
            if r not in q_by_id:
                errs.append(f"{label} ({qid}): requires unknown quest id {r!r}")

    # Detect cycles and compute longest chain to each quest
    visiting = set()
    memo_depth = {}

    def depth(qid):
        if qid in memo_depth:
            return memo_depth[qid]
        if qid in visiting:
            errs.append(f"{label}: cycle detected in 'requires' involving {qid!r}")
            return 0
        visiting.add(qid)
        q = q_by_id[qid]
        reqs = [r for r in (q.get("requires") or []) if isinstance(r, str) and r in q_by_id]
        d = 1 + max((depth(r) for r in reqs), default=0)
        visiting.remove(qid)
        memo_depth[qid] = d
        return d

    for qid in q_by_id:
        depth(qid)

    finales = [qid for qid, q in q_by_id.items() if q.get("finale") is True]
    if not finales:
        errs.append(f"{label}: no quest marked with \"finale\": true")
    else:
        best_finale_chain = max(memo_depth.get(fid, 0) for fid in finales)
        if best_finale_chain < 3:
            errs.append(f"{label}: longest chain ending at a finale quest has length {best_finale_chain} (need >= 3)")
        for fid in finales:
            fq = q_by_id[fid]
            if not isinstance(fq.get("consequence"), str) or not fq.get("consequence", "").strip():
                errs.append(f"{label} ({fid}): finale quest must have a non-empty 'consequence'")

    return errs


def run_selftest(regs, places, ws_fields):
    # 1. Positive test on whiterun.json
    w_path = os.path.join(STORY_DIR, "whiterun.json")
    if not os.path.exists(w_path):
        print("SELFTEST FAIL: whiterun.json missing")
        return 1
    good = jload(w_path)
    errs = validate_story(good, "whiterun.json", regs, places, ws_fields)
    if errs:
        print("SELFTEST FAIL: valid whiterun.json produced errors:", errs)
        return 1

    # 2. Negative test: cycle in requires
    bad_cycle = json.loads(json.dumps(good))
    bad_cycle["quests"][0]["requires"] = ["story_whiterun_3"]
    if not any("cycle" in e for e in validate_story(bad_cycle, "cycle_test", regs, places, ws_fields)):
        print("SELFTEST FAIL: cycle not detected")
        return 1

    # 3. Negative test: invalid World Sheet citation
    bad_why = json.loads(json.dumps(good))
    bad_why["why"] = ["whiterun.L9.nonexistent_field"]
    if not any("not found" in e for e in validate_story(bad_why, "why_test", regs, places, ws_fields)):
        print("SELFTEST FAIL: bogus World Sheet citation not detected")
        return 1

    # 4. Negative test: missing finale
    bad_fin = json.loads(json.dumps(good))
    bad_fin["quests"][2]["finale"] = False
    if not any("finale" in e for e in validate_story(bad_fin, "finale_test", regs, places, ws_fields)):
        print("SELFTEST FAIL: missing finale not detected")
        return 1

    # 5. Negative test: unknown place
    bad_pl = json.loads(json.dumps(good))
    bad_pl["quests"][0]["places"].append("not_a_real_place_id")
    if not any("not found in places" in e for e in validate_story(bad_pl, "place_test", regs, places, ws_fields)):
        print("SELFTEST FAIL: unknown place not detected")
        return 1

    print("story_lint --selftest: PASS (all 5 positive/negative assertions succeeded)")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="MS1 Regional Story Chain linter.")
    ap.add_argument("--province", help="Filter by province id")
    ap.add_argument("--region", help="Filter by region id")
    ap.add_argument("--selftest", action="store_true", help="Run built-in positive and negative unit tests")
    args = ap.parse_args(argv)

    regs, places, ws_fields = load_context()
    if args.selftest:
        rc = run_selftest(regs, places, ws_fields)
        if rc != 0:
            return rc

    files = sorted(glob.glob(os.path.join(STORY_DIR, "*.json")))
    if not files:
        print("story_lint: no story files found in", STORY_DIR)
        return 1

    checked = 0
    all_errs = []
    for f in files:
        data = jload(f)
        rid = data.get("region", "") if isinstance(data, dict) else ""
        prov = rid.split("/")[0] if "/" in rid else ""
        if args.province and prov != args.province:
            continue
        if args.region and rid != args.region and rid.split("/")[-1] != args.region:
            continue
        checked += 1
        errs = validate_story(data, os.path.basename(f), regs, places, ws_fields)
        all_errs.extend(errs)

    for e in all_errs:
        print("  FAIL:", e)
    print(f"story_lint: checked {checked} story file(s), {len(all_errs)} error(s).")
    return 1 if all_errs else 0


if __name__ == "__main__":
    sys.exit(main())
