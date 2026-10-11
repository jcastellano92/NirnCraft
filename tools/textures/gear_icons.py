"""Inventory icons (16x16) of the armour pieces, drawn from hand-made shape templates and painted with each set's look.

Templates use: '#' the piece's main material, 't' trim, 'd' dark interior (eye slits, the inside of a helmet),
'l' leather, 'f' fur, 'c' cloth, 'a' accent, 'g' glow, 'h' horn, '.' empty. Features (horns, crests, plumes, spikes,
pauldrons, fur collars, belts, emblems...) are stamped on top, then everything is shaded with a top-left light and
outlined.
"""
from PIL import Image

import gear_armor as ga
from gear_px import hexc, mix, rnd, shade

HELMS = {
    "open": [
        "................",
        "................",
        "......####......",
        "....########....",
        "...##########...",
        "..############..",
        "..############..",
        "..tttttttttttt..",
        "..##dddddddd##..",
        "..##dddddddd##..",
        "..##dddddddd##..",
        "..###dddddd###..",
        "..###dddddd###..",
        "...##......##...",
        "................",
        "................"],
    "nose": [
        "................",
        "................",
        "......####......",
        "....########....",
        "...##########...",
        "..############..",
        "..############..",
        "..tttttttttttt..",
        "..##ddd##ddd##..",
        "..##ddd##ddd##..",
        "..##ddd##ddd##..",
        "..###dddddd###..",
        "..###dddddd###..",
        "...##......##...",
        "................",
        "................"],
    "closed_slit": [
        "................",
        "................",
        "......####......",
        "....########....",
        "...##########...",
        "..############..",
        "..############..",
        "..##dddddddd##..",
        "..############..",
        "..#####tt#####..",
        "..##.#.tt.#.##..",
        "..############..",
        "..############..",
        "...##########...",
        "................",
        "................"],
    "visor": [
        "................",
        "................",
        "......####......",
        "....########....",
        "...##########...",
        "..############..",
        "..##gggggggg##..",
        "..##gggggggg##..",
        "..############..",
        "..#####tt#####..",
        "..############..",
        "..############..",
        "..############..",
        "...##########...",
        "................",
        "................"],
    "greathelm": [
        "................",
        "................",
        "....########....",
        "...##########...",
        "..############..",
        "..############..",
        "..##dddddddd##..",
        "..######dd####..",
        "..##d###dd##d#..",
        "..######dd####..",
        "..############..",
        "..############..",
        "..tttttttttttt..",
        "..############..",
        "................",
        "................"],
    "mask": [
        "................",
        "................",
        "......####......",
        "....########....",
        "...##########...",
        "..############..",
        "..t##########t..",
        "..t#dd####dd#t..",
        "..t##########t..",
        "..t####tt####t..",
        "..t###dddd###t..",
        "..t##########t..",
        "...##########...",
        "....########....",
        "................",
        "................"],
    "hood": [
        "................",
        "......####......",
        ".....######.....",
        "....########....",
        "...##########...",
        "...###dddd###...",
        "..###dddddd###..",
        "..##dddddddd##..",
        "..##dddddddd##..",
        "..##dddddddd##..",
        "..###dddddd###..",
        "..####dddd####..",
        "..############..",
        "...##########...",
        "....########....",
        "................"],
    "cap": [
        "................",
        "................",
        "................",
        "................",
        "......####......",
        "....########....",
        "...##########...",
        "..############..",
        "..############..",
        ".tttttttttttttt.",
        "..##dddddddd##..",
        "..##........##..",
        "...#........#...",
        "................",
        "................",
        "................"],
    "turban": [
        "................",
        "................",
        ".......aa.......",
        "....########....",
        "...##########...",
        "..############..",
        "..tttttttttttt..",
        "..############..",
        "..##dddddddd##..",
        "..##dddddddd##..",
        "..cccccccccccc..",
        "..cccccccccccc..",
        "...cccccccccc...",
        "....cccccccc....",
        "................",
        "................"],
}
HELMS["kabuto"] = HELMS["open"]

CUIRASS = [
    "................",
    "................",
    "..###......###..",
    ".####dddddd####.",
    ".##############.",
    ".##############.",
    ".###########.##.",
    "..##########....",
    "...##########...",
    "...##########...",
    "...##########...",
    "...llllllllll...",
    "...##########...",
    "....########....",
    "................",
    "................"]
CUIRASS[6] = ".##############."
CUIRASS[7] = ".##.########.##."

ROBE = [
    "................",
    "................",
    "..###......###..",
    ".####dddddd####.",
    ".##############.",
    ".##############.",
    ".##.########.##.",
    ".##.########.##.",
    "....########....",
    "...llllllllll...",
    "...##########...",
    "...##########...",
    "..############..",
    "..############..",
    ".##############.",
    "................"]

GAUNTLETS = [
    "................",
    "................",
    ".tttt.....tttt..",
    ".####.....####..",
    ".####.....####..",
    ".####.....####..",
    ".#####...#####..",
    ".######.######..",
    ".######.######..",
    "..#####.#####...",
    "..#ddd#.#ddd#...",
    "..#####.#####...",
    "...###...###....",
    "................",
    "................",
    "................"]

BOOTS = [
    "................",
    "................",
    "..tttt...tttt...",
    "..####...####...",
    "..####...####...",
    "..####...####...",
    "..####...####...",
    "..####...####...",
    "..#####..#####..",
    "..######.######.",
    "..######.######.",
    "..ddddddddddddd.",
    "................",
    "................",
    "................",
    "................"]
BOOTS[11] = "..dddddd.dddddd."


def _stamp(grid, pts, ch):
    for (x, y) in pts:
        if 0 <= x < 16 and 0 <= y < 16:
            grid[y][x] = ch


def helm_features(style, grid):
    st = style
    feats = set()
    for c in ga.geo.STYLES[st]["head"]:
        feats.add(c.paint)
    # horn shapes by style (2D silhouettes rather than the model's cubes)
    if st in ("iron", "ancient_nord", "orsinium"):
        _stamp(grid, [(1, 5), (0, 4), (0, 3), (1, 2), (14, 5), (15, 4), (15, 3), (14, 2)], "h")
    elif st in ("steel", "atmoran"):
        _stamp(grid, [(1, 6), (0, 5), (0, 4), (0, 3), (14, 6), (15, 5), (15, 4), (15, 3)], "h")
    elif st == "daedric":
        _stamp(grid, [(2, 4), (1, 3), (1, 2), (0, 1), (0, 0), (13, 4), (14, 3), (14, 2), (15, 1), (15, 0), (7, 1), (8, 1), (7, 0)], "h")
    elif st == "scaled":
        _stamp(grid, [(4, 2), (4, 1), (3, 0), (11, 2), (11, 1), (12, 0)], "h")
    elif st in ("blades", "akaviri"):
        _stamp(grid, [(5, 3), (4, 2), (3, 1), (3, 0), (10, 3), (11, 2), (12, 1), (12, 0)], "h")
        _stamp(grid, [(1, 12), (0, 13), (14, 12), (15, 13), (1, 13), (14, 13)], "#")
    elif st in ("forsworn", "bosmer"):
        _stamp(grid, [(4, 4), (3, 3), (3, 2), (2, 1), (4, 1), (11, 4), (12, 3), (12, 2), (13, 1), (11, 1), (2, 0), (13, 0)], "h")
    elif st in ("hide", "wolf"):
        _stamp(grid, [(4, 3), (4, 2), (11, 3), (11, 2)] if st == "hide" else [(4, 2), (3, 1), (11, 2), (12, 1)], "f" if st == "hide" else "#")
    if st in ("glass", "elven", "elven_gilded", "bonemold", "dawnguard", "khajiit", "maormer"):
        _stamp(grid, [(7, 1), (8, 1), (7, 0), (8, 0), (6, 1), (9, 1)][:4 if st != "glass" else 6], "t" if st not in ("glass", "maormer") else "g")
    if st in ("imperial", "imperial_light", "breton"):
        _stamp(grid, [(6, 1), (7, 1), (8, 1), (9, 1), (7, 0), (8, 0), (6, 0), (10, 1)], "p")
    if st in ("dragonscale", "dragonplate"):
        _stamp(grid, [(7, 1), (8, 1), (3, 3), (2, 2), (12, 3), (13, 2), (1, 1), (14, 1)], "h")
    if st in ("orcish", "orsinium", "daedric"):
        _stamp(grid, [(5, 1), (10, 1), (7, 1)], "h")
    if st in ("stalhrim_light", "stalhrim_heavy"):
        _stamp(grid, [(4, 2), (4, 1), (3, 0), (8, 1), (8, 0), (11, 2), (11, 1), (12, 0)], "g")
    if st in ("nordic", "stormcloak", "ebony"):
        _stamp(grid, [(1, 5), (0, 4), (1, 4), (14, 5), (15, 4), (14, 4)], "t" if st != "ebony" else "#")
    if st in ("chitin", "falmer", "netch"):
        _stamp(grid, [(7, 1), (8, 0), (5, 1), (10, 1), (6, 0), (9, 0)], "#")
    if st in ("argonian", "maormer"):
        _stamp(grid, [(1, 7), (0, 6), (0, 5), (14, 7), (15, 6), (15, 5)], "t")
    if st == "ordinator":
        _stamp(grid, [(7, 1), (8, 1), (7, 0), (8, 0), (6, 1), (9, 1)], "t")
    if st == "dwarven":
        _stamp(grid, [(7, 1), (8, 1), (1, 8), (1, 9), (14, 8), (14, 9)], "t")
    if st == "nightingale":
        for x in range(4, 12):
            grid[10][x] = "c"
            grid[11][x] = "c"
    if st == "ra_gada":
        pass


def cuirass_features(style, L, grid):
    chest = ga.geo.STYLES[style]["chest"]
    paints = {c.paint for c in chest}
    if "pauldron" in paints or "fur" in paints:
        ch = "f" if any(c.paint == "fur" and c.part.endswith("arm") for c in chest) else "t"
        _stamp(grid, [(0, 3), (0, 4), (1, 2), (2, 2), (0, 5), (15, 3), (15, 4), (14, 2), (13, 2), (15, 5)], ch)
    if any(c.paint == "fur" and c.part == "body" for c in chest):
        for x in range(3, 13):
            grid[3][x] = "f"
        _stamp(grid, [(4, 2), (11, 2)], "f")
    if any(c.paint == "horn" or c.paint == "spike" for c in chest):
        _stamp(grid, [(1, 1), (0, 0), (14, 1), (15, 0)], "h")
    if any(c.paint == "crystal" for c in chest):
        _stamp(grid, [(1, 1), (14, 1)], "g")
    for o in L["over"]:
        if o in ("glow_lines",):
            for y in range(5, 11):
                grid[y][5] = "g"
                grid[y][10] = "g"
        if o in ("sash", "sash_gold"):
            for k in range(8):
                x, y = 11 - k, 4 + k
                if grid[y][x] in "#l":
                    grid[y][x] = "a" if o == "sash" else "t"
        if o == "tabard":
            for y in range(6, 14):
                for x in (7, 8):
                    if grid[y][x] in "#l":
                        grid[y][x] = "c"
        if o == "center_ridge" or o == "pecs":
            for y in range(4, 10):
                if grid[y][7] == "#":
                    grid[y][7] = "r"
        if o.startswith("emblem"):
            grid[6][7] = "a"
            grid[6][8] = "a"
            grid[7][7] = "a"
            grid[7][8] = "a"
        if o == "trim_edges":
            for y in range(4, 14):
                for x in range(16):
                    if grid[y][x] in "#" and (x == 0 or grid[y][x - 1] == "."):
                        grid[y][x] = "t"


def paint(grid, L, part_mat, seed=0):
    """Colours a template grid with the set's look, shades it and outlines it."""
    r = rnd(seed)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    m = ga.mat_ramp(L, part_mat)
    t = ga.mat_ramp(L, "trim")
    lea = ga.mat_ramp(L, "leather")
    fur = ga.mat_ramp(L, "fur")
    cl = ga.mat_ramp(L, "cloth")
    acc = L["accent"]
    glow = L["glow"] or L["accent"]
    horn = L["horn"] or [L["metal"][1], L["metal"][2], L["metal"][3]]
    plume = L["plume"] or L["accent"]
    solid = {(x, y) for y in range(16) for x in range(16) if grid[y][x] != "."}

    def lit(x, y):
        up = (x, y - 1) not in solid
        left = (x - 1, y) not in solid
        down = (x, y + 1) not in solid
        right = (x + 1, y) not in solid
        return (up + left) - (down + right)

    for y in range(16):
        for x in range(16):
            ch = grid[y][x]
            if ch == ".":
                continue
            k = lit(x, y)
            if ch == "#":
                base = ga.surface(L, part_mat, x, y, 16, 16, r, "side")
                col = m[3] if k >= 2 else (mix(base, m[2], 0.6) if k == 1 else (mix(base, m[0], 0.5) if k < 0 else base))
            elif ch == "t":
                col = t[2] if k > 0 else (t[0] if k < 0 else t[1])
            elif ch == "r":
                col = m[3]
            elif ch == "d":
                col = shade(m[0], 0.35)
            elif ch == "l":
                col = lea[1] if (x % 3) else lea[2]
            elif ch == "f":
                v = r.random()
                col = fur[2] if v > 0.6 else (fur[1] if v > 0.25 else fur[0])
            elif ch == "c":
                col = cl[1] if k <= 0 else cl[2]
            elif ch == "a":
                col = acc[1]
            elif ch == "g":
                col = glow[2] if k > 0 else glow[1]
            elif ch == "h":
                col = horn[2] if k > 0 else horn[1]
            elif ch == "p":
                col = plume[2] if k > 0 else plume[1]
            else:
                continue
            px[x, y] = col
    # outline
    src = img.copy().load()
    for y in range(16):
        for x in range(16):
            if src[x, y][3]:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < 16 and 0 <= ny < 16 and src[nx, ny][3]:
                    c = src[nx, ny]
                    px[x, y] = (int(c[0] * 0.28), int(c[1] * 0.28), int(c[2] * 0.28), 255)
                    break
    return img


def _grid(rows):
    return [list(r) for r in rows]


def helmet_icon(set_id, style, seed=0):
    L = ga.LOOKS[set_id]
    grid = _grid(HELMS.get(L["helm"], HELMS["open"]))
    helm_features(style, grid)
    mat = {"metal": "plate"}.get(L["helm_mat"], L["helm_mat"])
    return paint(grid, L, mat, seed)


def cuirass_icon(set_id, style, seed=0):
    L = ga.LOOKS[set_id]
    long = any(c.paint == "skirt" for c in ga.geo.STYLES[style]["chest"])
    grid = _grid(ROBE if long else CUIRASS)
    cuirass_features(style, L, grid)
    mat = {"plate": "plate"}.get(L["chest"], L["chest"])
    return paint(grid, L, mat, seed)


def gauntlets_icon(set_id, style, seed=0):
    L = ga.LOOKS[set_id]
    grid = _grid(GAUNTLETS)
    legs = ga.geo.STYLES[style]["legs"]
    if any(c.paint == "spike" for c in legs):
        _stamp(grid, [(0, 4), (0, 6), (15, 4), (15, 6)], "h")
    if any(c.paint == "gcuff" and c.size[0] == 1 for c in legs):
        _stamp(grid, [(0, 2), (0, 3), (15, 2), (15, 3)], "t")
    mat = {"metal": "plate"}.get(L["gaunt"], L["gaunt"])
    return paint(grid, L, mat, seed)


def boots_icon(set_id, style, seed=0):
    L = ga.LOOKS[set_id]
    grid = _grid(BOOTS)
    feet = ga.geo.STYLES[style]["feet"]
    if any(c.paint == "fur" for c in feet):
        for x in range(16):
            if grid[2][x] == "t":
                grid[2][x] = "f"
        _stamp(grid, [(1, 2), (6, 2), (8, 2), (13, 2)], "f")
    if any(c.paint == "spike" for c in feet):
        _stamp(grid, [(1, 4), (0, 3), (8, 4), (7, 3)], "h")
    if any(c.paint == "cuff" and c.size == (3, 3, 1) for c in feet):
        _stamp(grid, [(3, 3), (4, 3), (10, 3), (11, 3)], "t")
    mat = {"metal": "plate"}.get(L["boots"], L["boots"])
    return paint(grid, L, mat, seed)
