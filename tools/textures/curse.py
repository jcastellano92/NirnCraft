"""Generates the curse module's textures (PIL), all original procedural art.

Writes into mod/src/main/resources/assets/skycraft/textures/:
  entity/curse/werewolf.png (+ _eyes)        128x64  WerewolfModel
  entity/curse/vampire_lord.png (+ _eyes)    128x64  VampireLordModel
  entity/curse/death_hound.png (+ _eyes)     64x32   DeathHoundModel
  entity/curse/gargoyle.png (+ _eyes)        128x64  GargoyleModel
  mob_effect/sanguinare_vampiris.png, mob_effect/sun_damage.png   18x18
  gui/curse_vignette.png                      256x256 white with a radial alpha falloff (tinted in code)
  gui/curse_icons.png                         64x16   blood drop, moon, sun
  item/*_spawn_egg models are vanilla templates (no texture).

The UV layout is read straight from the Java model sources (every `.texOffs(u, v).addBox(x, y, z, w, h, d)` under
its `addOrReplaceChild("part", ...)`), so the paint always lands on the faces the models use. Each box is painted
face by face with a material chosen by part name and box index, plus face details (eyes, teeth, claws, ribs, veins).
Run:  py tools/textures/curse.py
"""
import math
import os
import random
import re

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TEX = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "skycraft", "textures")
MODELS = os.path.join(ROOT, "mod", "src", "main", "java", "com", "skycraft", "curse", "client", "model")


# ------------------------------------------------------------------------------------------------ helpers

def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, f):
    return tuple(clamp(x * f) for x in c[:3])


def mix(a, b, t):
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3))


def jitter(c, rnd, amt):
    d = rnd.uniform(-amt, amt)
    return tuple(clamp(x + d) for x in c[:3])


def boxes(java_file):
    """[(part, index, u, v, w, h, d)] from a model source."""
    src = open(os.path.join(MODELS, java_file), encoding="utf-8").read()
    out = []
    part = None
    index = 0
    tok = re.compile(r'addOrReplaceChild\("(\w+)"|texOffs\((\d+), (\d+)\)\.addBox\(([-\d.]+)f, ([-\d.]+)f, ([-\d.]+)f, ([\d.]+)f, ([\d.]+)f, ([\d.]+)f\)')
    for m in tok.finditer(src):
        if m.group(1):
            part = m.group(1)
            index = 0
        else:
            u, v = int(m.group(2)), int(m.group(3))
            w, h, d = int(float(m.group(7))), int(float(m.group(8))), int(float(m.group(9)))
            out.append((part, index, u, v, w, h, d))
            index += 1
    return out


def faces(u, v, w, h, d):
    """Texture rectangles (x, y, width, height) of a box's faces."""
    if d == 0:
        return {"front": (u, v, w, h), "back": (u + w, v, w, h)}
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }


def paint(img, eyes, layout, material, seed):
    rnd = random.Random(seed)
    for part, idx, u, v, w, h, d in layout:
        for face, (fx, fy, fw, fh) in faces(u, v, w, h, d).items():
            for y in range(fh):
                for x in range(fw):
                    px, py = fx + x, fy + y
                    if px >= img.width or py >= img.height:
                        continue
                    c = material(part, idx, face, x, y, fw, fh, rnd)
                    if c is None:
                        img.putpixel((px, py), (0, 0, 0, 0))
                        continue
                    if len(c) == 3:
                        c = c + (255,)
                    img.putpixel((px, py), tuple(c[:4]))
                    glow = c[4] if len(c) > 4 else None
                    if glow:
                        eyes.putpixel((px, py), glow)


def glowing(rgb, glow):
    """A pixel that is also drawn full-bright on the eyes layer."""
    return rgb + (255, glow + (255,))


def save(img, *path):
    p = os.path.join(TEX, *path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)
    print("wrote", os.path.relpath(p, ROOT))


def fur(base, x, y, rnd, stripe=0.0):
    """Shaggy fur: vertical streaks and grain."""
    s = 1.0 + 0.10 * math.sin(x * 2.1 + y * 0.35) + rnd.uniform(-0.09, 0.09)
    if (x + y * 3) % 7 == 0:
        s -= 0.12
    if stripe and y % 3 == 0:
        s -= stripe
    return shade(base, s)


def claw(x, y, fw, fh, light, dark):
    """Claws: pale curved tips over a dark root, one per two pixels."""
    if x % 2 == 1 and fw > 2:
        return dark
    t = y / max(1, fh - 1)
    return mix(dark, light, t)


# ------------------------------------------------------------------------------------------------ werewolf

def werewolf():
    furc = hexc("58493c")
    dark = hexc("30261e")
    belly = hexc("948066")
    skin = hexc("3a2c28")
    ivory = hexc("d8ceb4")
    amber = hexc("f0b030")

    def mat(part, idx, face, x, y, fw, fh, rnd):
        if part == "body":
            if idx == 1:  # mane: darker, longer streaks
                return fur(dark, x, y, rnd, 0.08)
            if face == "front":
                t = abs(x - fw / 2) / (fw / 2)
                return fur(mix(belly, furc, t * 0.8), x, y, rnd)
            if face == "back" or face == "top":
                return fur(shade(furc, 0.82), x, y, rnd)
            return fur(furc, x, y, rnd)
        if part == "head":
            if idx == 0:  # skull
                if face == "front":
                    # eyes set wide, above the snout (which covers the middle of the face)
                    if y == 2 and x in (0, 1, 6, 7):
                        return glowing(amber, amber)
                    if y == 1:
                        return shade(dark, 0.8)  # heavy brow
                    return fur(furc, x, y, rnd)
                if face == "top" or face == "back":
                    return fur(dark, x, y, rnd)
                return fur(furc, x, y, rnd)
            if idx == 1:  # snout
                if face == "front":
                    if y == 0 and 1 <= x <= 3:
                        return hexc("141010")
                    if y == fh - 1:
                        return ivory if x % 2 == 0 else shade(skin, 0.6)
                    return fur(shade(furc, 0.9), x, y, rnd)
                if face == "bottom":
                    return shade(skin, 0.7)
                if face in ("left", "right") and y == fh - 1:
                    return ivory if x % 2 == 0 else shade(skin, 0.6)
                return fur(mix(furc, belly, 0.25), x, y, rnd)
            # ears
            if face == "front":
                return hexc("5a3a34") if 0 < y else fur(dark, x, y, rnd)
            return fur(dark, x, y, rnd)
        if part == "jaw":
            if face in ("front", "left", "right") and y == 0:
                return ivory if x % 2 == 0 else shade(skin, 0.6)
            if face == "top":
                return hexc("6a2a2a")  # the mouth
            return fur(mix(furc, belly, 0.4), x, y, rnd)
        if part in ("right_arm", "left_arm", "right_leg", "left_leg"):
            return fur(furc if face != "front" else mix(furc, belly, 0.2), x, y, rnd)
        if part in ("right_forearm", "left_forearm"):
            if idx == 1:  # paw and claws
                if face == "bottom":
                    return shade(skin, 0.6)
                if face == "top":
                    return fur(furc, x, y, rnd)
                if y >= fh - 2:
                    return claw(x, y - (fh - 2), fw, 2, ivory, hexc("2a2420"))
                return shade(skin, 0.9) if face == "front" else fur(furc, x, y, rnd)
            return fur(furc, x, y, rnd)
        if part in ("right_shin", "left_shin"):
            return fur(shade(furc, 0.92), x, y, rnd)
        if part in ("right_foot", "left_foot"):
            if face == "front":
                return claw(x, y, fw, fh, ivory, hexc("2a2420"))
            if face == "bottom":
                return shade(skin, 0.6)
            return fur(shade(furc, 0.85), x, y, rnd)
        if part == "tail":
            return fur(dark if face == "top" else furc, x, y, rnd, 0.05)
        return fur(furc, x, y, rnd)

    img = Image.new("RGBA", (128, 64), (0, 0, 0, 0))
    eyes = Image.new("RGBA", (128, 64), (0, 0, 0, 0))
    paint(img, eyes, boxes("WerewolfModel.java"), mat, 7)
    save(img, "entity", "curse", "werewolf.png")
    save(eyes, "entity", "curse", "werewolf_eyes.png")


# ------------------------------------------------------------------------------------------------ Vampire Lord

def vampire_lord():
    skin = hexc("767a84")
    vein = hexc("4c4e5e")
    dark = hexc("3a3640")
    membrane = hexc("2c2228")
    mvein = hexc("4a3640")
    cloth = hexc("3c1418")
    bone = hexc("1e1a1e")
    fang = hexc("e8e2d0")
    red = hexc("ff3020")

    def skin_px(x, y, rnd, base=skin):
        c = jitter(base, rnd, 6)
        if (x * 7 + y * 3) % 11 == 0 or (x + 2 * y) % 13 == 0:
            c = mix(c, vein, 0.5)
        return c

    def mat(part, idx, face, x, y, fw, fh, rnd):
        if part == "body":
            if idx == 0:
                if face == "front":
                    # a gaunt chest: rib shadows, a dark sternum line
                    if 3 <= y <= 9 and y % 2 == 1 and x not in (fw // 2 - 1, fw // 2):
                        return shade(skin, 0.68)
                    if x in (fw // 2 - 1, fw // 2) and y < 10:
                        return shade(skin, 0.8)
                    return skin_px(x, y, rnd)
                if face == "back":
                    if x in (fw // 2 - 1, fw // 2):
                        return shade(skin, 0.7)  # spine
                    return skin_px(x, y, rnd, shade(skin, 0.9))
                return skin_px(x, y, rnd)
            if idx == 1:  # hips: a dark wrap
                return jitter(cloth, rnd, 8)
            # loincloth flap, tattered
            if y >= fh - 2 and (x * 5 + y) % 3 == 0:
                return None
            return jitter(shade(cloth, 1.0 - 0.04 * y), rnd, 8)
        if part == "head":
            if idx == 0:
                if face == "front":
                    if y == 3 and x in (1, 2, 4, 5):
                        return glowing(red, red)
                    if y == 2 and 1 <= x <= 5:
                        return shade(skin, 0.55)
                    if y == 4 and x == 3:
                        return shade(skin, 0.5)  # bat nose
                    return skin_px(x, y, rnd)
                return skin_px(x, y, rnd, shade(skin, 0.95))
            if idx in (1, 2):  # bat ears
                if face in ("front", "back") and 0 < x < fw - 1 and y > 0:
                    return hexc("6a4a54")
                return skin_px(x, y, rnd)
            if idx == 3:  # spiked crown
                if face in ("front", "back", "left", "right") and y == 0 and x % 2 == 1:
                    return None
                return jitter(bone, rnd, 6)
            # muzzle with fangs
            if face == "front":
                if y == fh - 1 and x in (0, fw - 1):
                    return fang
                if y == 0 and x in (1, 2):
                    return shade(skin, 0.45)
            return skin_px(x, y, rnd)
        if part == "jaw":
            if face in ("front", "left", "right") and y == 0 and x in (0, fw - 1):
                return fang
            if face == "top":
                return hexc("5a1418")
            return skin_px(x, y, rnd)
        if part in ("right_arm", "left_arm", "right_leg", "left_leg", "right_shin", "left_shin"):
            if part.endswith("shin") and idx == 1:  # taloned feet
                if face == "front":
                    return claw(x, y, fw, fh, hexc("8a8478"), bone)
                return jitter(shade(skin, 0.7), rnd, 5)
            return skin_px(x, y, rnd)
        if part in ("right_forearm", "left_forearm"):
            if idx == 1:  # long black talons
                if face == "bottom" or y >= fh - 2:
                    return claw(x, y, fw, fh, hexc("5a5450"), bone)
                return skin_px(x, y, rnd, shade(skin, 0.85))
            return skin_px(x, y, rnd)
        if part in ("right_wing", "left_wing", "right_wing_tip", "left_wing_tip"):
            if idx == 0:  # the wing's arm bone
                return jitter(dark, rnd, 6)
            # membrane: dark leather with fanning veins, a ragged trailing edge
            tip = part.endswith("tip")
            edge = fh - 1 - (2 if (x * 7) % 5 == 0 else 0) - int(1.5 + 1.5 * math.sin(x * 0.9 + (3 if tip else 0)))
            if y > edge:
                return None
            if tip and x < 2 and y > fh * 0.6:
                return None
            ratio = (x + 1) / fw
            vein_line = any(abs(y - (k * ratio * fh * 0.9 if not tip else k * (1 - ratio) * fh * 0.9)) < 0.6 for k in (0.35, 0.7, 1.0))
            c = mix(membrane, mvein, 0.8) if vein_line else jitter(membrane, rnd, 5)
            return shade(c, 0.85 + 0.2 * (1 - y / fh))
        return skin_px(x, y, rnd)

    img = Image.new("RGBA", (128, 64), (0, 0, 0, 0))
    eyes = Image.new("RGBA", (128, 64), (0, 0, 0, 0))
    paint(img, eyes, boxes("VampireLordModel.java"), mat, 11)
    save(img, "entity", "curse", "vampire_lord.png")
    save(eyes, "entity", "curse", "vampire_lord_eyes.png")


# ------------------------------------------------------------------------------------------------ death hound

def death_hound():
    hide = hexc("3a3632")
    rot = hexc("5a2a26")
    bone = hexc("a49e90")
    red = hexc("ff2a18")

    def mat(part, idx, face, x, y, fw, fh, rnd):
        if part == "body":
            if face in ("left", "right") and 2 <= y <= 4 and x % 3 == 1:
                return jitter(bone, rnd, 8)  # ribs through the hide
            if (x * 3 + y * 5) % 17 == 0:
                return jitter(rot, rnd, 8)
            if face == "top":
                return jitter(shade(hide, 0.75), rnd, 6)  # the dark ridge of the back
            return jitter(hide, rnd, 8)
        if part == "head":
            if idx == 0 and face == "front":
                if y == 2 and x in (1, 4):
                    return glowing(red, red)
                return jitter(shade(hide, 0.9), rnd, 6)
            if idx == 1 and face == "front" and y == fh - 1:
                return bone if x % 2 == 0 else hexc("1a1414")
            if idx == 1 and face == "front" and y == 0:
                return hexc("141010")
            return jitter(hide, rnd, 8)
        if part == "jaw":
            if face in ("front", "left", "right") and y == 0:
                return bone if x % 2 == 0 else hexc("1a1414")
            return jitter(rot, rnd, 8) if face == "top" else jitter(hide, rnd, 8)
        if part.endswith("leg"):
            if face == "bottom" or y >= fh - 1:
                return hexc("1e1a18")
            return jitter(shade(hide, 0.9), rnd, 8)
        if part == "tail":
            return jitter(shade(hide, 0.8), rnd, 8)
        return jitter(hide, rnd, 8)

    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    eyes = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    paint(img, eyes, boxes("DeathHoundModel.java"), mat, 13)
    save(img, "entity", "curse", "death_hound.png")
    save(eyes, "entity", "curse", "death_hound_eyes.png")


# ------------------------------------------------------------------------------------------------ gargoyle

def gargoyle():
    stone = hexc("7a756c")
    dark = hexc("4e4a44")
    moss = hexc("5a6448")
    ember = hexc("ff7a20")

    def rock(x, y, rnd, base=stone):
        c = jitter(base, rnd, 10)
        n = math.sin(x * 1.7 + y * 0.6) + math.sin(x * 0.4 - y * 1.3)
        if n > 1.5:
            c = mix(c, moss, 0.45)
        if (x * 5 + y * 7) % 23 == 0 or (x - y) % 19 == 0:
            c = shade(c, 0.55)  # cracks
        return c

    def mat(part, idx, face, x, y, fw, fh, rnd):
        if part == "head":
            if idx == 0 and face == "front":
                if y == 2 and x in (1, 2, 4, 5):
                    return glowing(ember, ember)
                if y == 1 and 1 <= x <= 5:
                    return shade(stone, 0.6)
            if idx in (1, 2):
                return rock(x, y, rnd, dark)  # horns
            if idx == 3 and face == "front" and y == fh - 1:
                return hexc("cfc8b8") if x % 2 == 0 else dark
            return rock(x, y, rnd)
        if part == "jaw":
            if face in ("front", "left", "right") and y == 0:
                return hexc("cfc8b8") if x % 2 == 0 else dark
            return rock(x, y, rnd)
        if part in ("right_forearm", "left_forearm") and idx == 1:
            if face == "bottom" or y >= fh - 1:
                return claw(x, y, fw, fh, hexc("9a948a"), hexc("2a2622"))
            return rock(x, y, rnd)
        if part.endswith("shin") and idx == 1:
            if face == "front":
                return claw(x, y, fw, fh, hexc("9a948a"), hexc("2a2622"))
            return rock(x, y, rnd, dark)
        if "wing" in part:
            if idx == 0:
                return rock(x, y, rnd, dark)
            tip = part.endswith("tip")
            edge = fh - 1 - int(1 + 1.2 * math.sin(x * 1.1 + (2 if tip else 0)))
            if y > edge:
                return None
            return rock(x, y, rnd, mix(stone, dark, 0.35))
        if part == "body" and face == "front" and idx == 0 and 3 <= y <= 8 and y % 2 == 0:
            return shade(stone, 0.75)
        return rock(x, y, rnd)

    img = Image.new("RGBA", (128, 64), (0, 0, 0, 0))
    eyes = Image.new("RGBA", (128, 64), (0, 0, 0, 0))
    paint(img, eyes, boxes("GargoyleModel.java"), mat, 17)
    save(img, "entity", "curse", "gargoyle.png")
    save(eyes, "entity", "curse", "gargoyle_eyes.png")


# ------------------------------------------------------------------------------------------------ GUI and effects

def drop(img, ox, oy, color, rim):
    """A blood drop, 9x12."""
    for y in range(12):
        for x in range(9):
            cx, cy = 4, 8
            if y < 5:
                half = y * 0.8
                inside = abs(x - cx) <= half
            else:
                inside = (x - cx) ** 2 + (y - cy) ** 2 <= 16
            if not inside:
                continue
            c = color
            edge = (x - cx) ** 2 + (y - cy) ** 2 >= 9 and y >= 5
            if edge or (y < 5 and abs(x - cx) >= half - 0.8):
                c = rim
            if x == 3 and 6 <= y <= 8:
                c = mix(color, (255, 220, 220), 0.55)
            img.putpixel((ox + x, oy + y), c + (255,))


def moon(img, ox, oy):
    for y in range(11):
        for x in range(11):
            d = math.hypot(x - 5, y - 5)
            if d > 5:
                continue
            c = mix(hexc("f0e6c0"), hexc("b8a878"), d / 5)
            if (x * 3 + y * 7) % 11 == 0 and d < 4:
                c = shade(c, 0.82)
            img.putpixel((ox + x, oy + y), c + (255,))


def sun(img, ox, oy):
    for y in range(11):
        for x in range(11):
            d = math.hypot(x - 5, y - 5)
            ray = (x == 5 or y == 5 or x == y or x == 10 - y) and d <= 5.4
            if d <= 2.6:
                c = hexc("fff0a0")
            elif ray and d > 3.4:
                c = hexc("ffb030")
            else:
                continue
            img.putpixel((ox + x, oy + y), c + (255,))


def gui():
    icons = Image.new("RGBA", (64, 16), (0, 0, 0, 0))
    drop(icons, 0, 0, hexc("a01820"), hexc("4a0a0e"))
    moon(icons, 16, 0)
    sun(icons, 32, 0)
    save(icons, "gui", "curse_icons.png")

    v = Image.new("RGBA", (256, 256), (255, 255, 255, 0))
    for y in range(256):
        for x in range(256):
            dx, dy = (x - 127.5) / 127.5, (y - 127.5) / 127.5
            d = math.sqrt(dx * dx * 0.9 + dy * dy * 1.1)
            a = max(0.0, min(1.0, (d - 0.55) / 0.6)) ** 1.6
            v.putpixel((x, y), (255, 255, 255, clamp(a * 255)))
    save(v, "gui", "curse_vignette.png")

    sang = Image.new("RGBA", (18, 18), (0, 0, 0, 0))
    drop(sang, 4, 3, hexc("7a0e18"), hexc("3a060a"))
    for (x, y) in ((2, 2), (14, 4), (3, 14), (15, 13)):
        sang.putpixel((x, y), hexc("c03040") + (255,))
    save(sang, "mob_effect", "sanguinare_vampiris.png")
    sunfx = Image.new("RGBA", (18, 18), (0, 0, 0, 0))
    sun(sunfx, 3, 3)
    save(sunfx, "mob_effect", "sun_damage.png")


if __name__ == "__main__":
    werewolf()
    vampire_lord()
    death_hound()
    gargoyle()
    gui()
