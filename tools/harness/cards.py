"""Subject cards: what a subject is, how it looks, how well it is tested, at three levels of detail.

    basic  identity + the one picture + key facts                    (a glance; lists of hundreds)
    mid    front + side + facts + status of every view + open issues  (a review)
    full   every view (sheet pages) + all data + history              (a deep look)

Data comes from the game's subject index (size, category, slot, effects, cost ...), pictures from the latest captures,
status from the results and the issue tally. Written to .work/harness/cards/<subject>/: <level>.png (reviewer),
card.json and card.md (agents). `harness.py card SUBJECT [--level ...]`, `harness.py cards --kind K` for many.
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw

import catalog
import results

CARDS = results.STORE / "cards"
W = 1200


def facts(subject):
    """What the game's index says about a subject (kind and data), searched across every section."""
    idx = catalog.subject_index()
    for section in ("entities", "items", "blocks", "spells", "ingredients", "fish", "landmarks"):
        for rec in idx.get(section, []):
            if isinstance(rec, dict) and rec.get("id") == subject:
                out = {"section": section, **rec}
                if section == "items":  # an ingredient's effects, a fish's rarity
                    for extra in ("ingredients", "fish"):
                        more = next((r for r in idx.get(extra, []) if r.get("id") == subject
                                     or r.get("item", "").endswith(":" + subject)), None)
                        if more:
                            out.update({k: v for k, v in more.items() if k not in ("id", "item")})
                return out
    for section in ("npcRaces", "roles", "furnitureKinds", "trees", "landmarkKinds"):
        if subject in idx.get(section, []):
            return {"section": section, "id": subject}
    return {"id": subject}


def status(roles):
    """Per view: the latest result's status, flags, error id; plus open issues for the subject's scenes."""
    try:
        led = json.loads((results.STORE / "issues.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        led = {}
    led.pop("_stats", None)
    ACTIVE = ("open", "in-progress", "testing", "needs-review")
    views, issues = [], []
    for role, sid, title in roles:
        rec = None
        for v in ("shaders", "vanilla", "lab", "measure"):
            rec = results.latest(sid, v)
            if rec:
                break
        m = (rec or {}).get("metrics") or {}
        views.append({"scene": sid, "role": role, "title": title, "status": (rec or {}).get("status", "never run"),
                      "code": (rec or {}).get("errorCode"), "flags": (m.get("flags") or [])[:3],
                      "at": (rec or {}).get("capturedAt")})
        issues += [{"key": k, "why": e.get("why", "")[:160], "state": e.get("state")}
                   for k, e in led.items() if k.split("|")[0] == sid and e.get("state") in ACTIVE]
    return views, issues


def _text_block(lines, width, size=17):
    font = results._font(size)
    h = 10 + len(lines) * (size + 6)
    im = Image.new("RGB", (width, h), (16, 16, 20))
    d = ImageDraw.Draw(im)
    y = 6
    for text, colour in lines:
        d.text((10, y), text[:150], font=font, fill=colour)
        y += size + 6
    return im


def card(subject, roles, level="mid"):
    """Write the subject's card at `level`; returns the written paths (png pages, card.json, card.md)."""
    f = facts(subject)
    views, issues = status(roles)
    out = CARDS / subject
    out.mkdir(parents=True, exist_ok=True)
    captured = sum(1 for v in views if v["status"] not in ("never run",))
    passing = sum(1 for v in views if v["status"] == "pass")
    data = {"subject": subject, "facts": f, "views": views, "issues": issues,
            "coverage": {"views": len(views), "captured": captured, "passing": passing}}
    (out / "card.json").write_text(json.dumps(data, indent=1), encoding="utf-8")
    key = [k for k in ("kind", "section", "category", "slot", "width", "height", "effects", "school", "tier",
                       "type", "cost", "rarity", "class") if k in f]
    md = [f"# {subject}", "", " · ".join(f"{k}: {f[k]}" for k in key), "",
          f"coverage: {captured}/{len(views)} views captured, {passing} passing", ""]
    md += [f"- {v['role']:9} {v['scene']}: {v['status']}" + (f" [{v['code']}]" if v.get("code") else "")
           for v in views]
    if issues:
        md += ["", "open issues:"] + [f"- {i['key']} ({i['state']}): {i['why']}" for i in issues]
    (out / "card.md").write_text("\n".join(md) + "\n", encoding="utf-8")

    head = [(f"{subject}", (255, 255, 255)),
            (" · ".join(f"{k}: {f[k]}" for k in key)[:150] or "(not in the subject index)", (210, 205, 190)),
            (f"coverage {captured}/{len(views)} views, {passing} passing; {len(issues)} open issue(s)",
             (255, 214, 120) if issues or passing < len(views) else (170, 220, 170))]
    if level == "basic":
        sheet = results.subject_sheet(subject, roles, "single")
        pics = [Image.open(sheet[0]).convert("RGB")] if sheet else []
        body = []
    elif level == "mid":
        sheet = results.subject_sheet(subject, roles, "pair")
        pics = [Image.open(sheet[0]).convert("RGB")] if sheet else []
        body = [(f"{v['role']:9} {v['scene']}: {v['status']}" + (f" [{v['code']}]" if v.get("code") else "")
                 + (f"  ! {v['flags'][0][:60]}" if v.get("flags") else ""),
                 (170, 220, 170) if v["status"] == "pass" else (255, 214, 120)) for v in views[:14]]
        body += [(f"issue {i['key']}: {i['why'][:110]}", (255, 150, 150)) for i in issues[:4]]
    else:
        pics = [Image.open(p).convert("RGB") for p in results.subject_sheet(subject, roles, "full")]
        body = [(f"{v['role']:9} {v['scene']}: {v['status']} {v.get('at') or ''}" + (f" [{v['code']}]" if v.get("code") else ""),
                 (170, 220, 170) if v["status"] == "pass" else (255, 214, 120)) for v in views]
        body += [(f"issue {i['key']} ({i['state']}): {i['why'][:110]}", (255, 150, 150)) for i in issues]
    for old in out.glob(f"{level}*.png"):
        old.unlink()
    paths = []
    pages = pics or [None]
    for n, pic in enumerate(pages, 1):
        parts = [_text_block(head, W, 18)] + ([_text_block(body, W, 15)] if body and n == 1 else [])
        if pic is not None:
            pic = pic.resize((W, round(pic.height * W / pic.width)))
            parts.append(pic)
        im = Image.new("RGB", (W, sum(p.height for p in parts)), (10, 10, 12))
        y = 0
        for p in parts:
            im.paste(p, (0, y))
            y += p.height
        path = out / (f"{level}.png" if len(pages) == 1 else f"{level}-p{n}.png")
        im.save(path)
        paths.append(path)
    return paths + [out / "card.json", out / "card.md"]
