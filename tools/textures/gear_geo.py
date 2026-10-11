"""3D geometry of the worn armour: per style and slot, the humanoid parts it covers and the extra cubes that give it
its silhouette, with the UVs of every cube packed into the free areas of the 64x32 vanilla armour layout.

Coordinates are those of Minecraft's HumanoidModel (part-local, y down, -z = front): the head spans x -4..4, y -8..0,
z -4..4; the body x -4..4, y 0..12, z -2..2; the right arm x -3..1, y -2..10 (pivot -5, 2, 0); the right leg
x -2..2, y 0..12 (pivot -1.9, 12, 0). Left-side cubes are mirrors of the right-side ones and share their UVs.
Rotations are degrees, applied Z*Y*X like ModelPart (x > 0 tilts an upward cube forwards, z > 0 tilts it to +x).
"""
import copy

# --------------------------------------------------------------------------------------------- vanilla parts

BASE_UV = {"head": (0, 0, 8, 8, 8), "body": (16, 16, 8, 12, 4), "right_arm": (40, 16, 4, 12, 4), "left_arm": (40, 16, 4, 12, 4),
           "right_leg": (0, 16, 4, 12, 4), "left_leg": (0, 16, 4, 12, 4)}
BASE_BOX = {"head": (-4, -8, -4, 8, 8, 8), "body": (-4, 0, -2, 8, 12, 4), "right_arm": (-3, -2, -2, 4, 12, 4),
            "left_arm": (-1, -2, -2, 4, 12, 4), "right_leg": (-2, 0, -2, 4, 12, 4), "left_leg": (-2, 0, -2, 4, 12, 4)}
PART_PIVOT = {"head": (0, 0, 0), "hat": (0, 0, 0), "body": (0, 0, 0), "right_arm": (-5, 2, 0), "left_arm": (5, 2, 0),
              "right_leg": (-1.9, 12, 0), "left_leg": (1.9, 12, 0)}
MIRROR_PART = {"right_arm": "left_arm", "right_leg": "left_leg", "head": "head", "body": "body"}

# free texture areas per layer once the hat is dropped (layer 1) / for the inner layer (layer 2: head area unused,
# arms carry the gauntlet cube at 40,16)
FREE = {
    1: [(32, 0, 32, 16), (0, 0, 8, 8), (24, 0, 8, 8), (56, 16, 8, 16), (16, 16, 4, 4), (36, 16, 4, 4), (40, 16, 4, 4),
        (52, 16, 4, 4), (0, 16, 4, 4), (12, 16, 4, 4)],
    2: [(0, 0, 64, 16), (56, 16, 8, 16), (56, 26, 0, 0)],
}
GAUNTLET = dict(part="right_arm", frm=(-3, 4, -2), size=(4, 6, 4), inflate=1.15, uv=(40, 16), paint="gauntlet", fixed=True)


def footprint(size):
    w, h, d = size
    return 2 * (w + d), d + h


class Cube:
    def __init__(self, part, frm, size, paint, inflate=0.0, pivot=(0, 0, 0), rot=(0, 0, 0), mirrored=False, uv=None, sym=False):
        self.part, self.frm, self.size, self.paint = part, tuple(frm), tuple(int(v) for v in size), paint
        self.inflate, self.pivot, self.rot, self.mirrored, self.uv, self.sym = inflate, tuple(pivot), tuple(rot), mirrored, uv, sym

    def mirror(self):
        """The left-side twin (x mirrored, same UVs)."""
        c = copy.copy(self)
        c.part = MIRROR_PART[self.part]
        x, y, z = self.frm
        c.frm = (-x - self.size[0], y, z)
        px, py, pz = self.pivot
        c.pivot = (-px, py, pz)
        rx, ry, rz = self.rot
        c.rot = (rx, -ry, -rz)
        c.mirrored = True
        c.twin = self
        return c

    def json(self):
        d = {"part": self.part, "uv": list(self.uv), "from": [round(v, 3) for v in self.frm], "size": list(self.size)}
        if self.inflate:
            d["inflate"] = self.inflate
        if any(self.pivot):
            d["pivot"] = [round(v, 3) for v in self.pivot]
        if any(self.rot):
            d["rot"] = [round(v, 2) for v in self.rot]
        if self.mirrored:
            d["mirror"] = True
        return d


def pair(part, frm, size, paint, **kw):
    """A right-side cube and its mirrored left twin."""
    c = Cube(part, frm, size, paint, **kw)
    return [c, c.mirror()]


# --------------------------------------------------------------------------------------------- features
# Each returns cubes. Heads are drawn with inflation 1.0 (surface at x = +-5, y = -9, z = +-5), chests 1.0, boots 1.0,
# gauntlets/greaves 0.5.

def horns(kind):
    if kind == "iron":       # short horns curling up and out from the temples (Iron, Ancient Nord)
        return (pair("head", (-1, -3, -0.5), (1, 3, 1), "horn", pivot=(-5, -6, 0), rot=(0, 0, -50))
                + pair("head", (-1, -3, -0.5), (1, 3, 1), "horn_tip", pivot=(-7.2, -7.6, 0), rot=(0, 0, -15)))
    if kind == "steel":      # horns sweeping back from the brow (Steel)
        return (pair("head", (-0.5, -3, -0.5), (1, 3, 1), "horn", pivot=(-4.6, -7, -1), rot=(-55, 0, -25))
                + pair("head", (-0.5, -3, -0.5), (1, 3, 1), "horn_tip", pivot=(-5.4, -8.6, 1.4), rot=(-100, 0, -15)))
    if kind == "big":        # Daedric: tall swept-back horns
        return (pair("head", (-1, -4, -1), (2, 4, 2), "horn", pivot=(-4.5, -7, 0), rot=(-25, 0, -30))
                + pair("head", (-0.5, -4, -0.5), (1, 4, 1), "horn_tip", pivot=(-6.4, -10.2, 1.2), rot=(-55, 0, -10)))
    if kind == "scaled":     # Scaled horn helmet: two horns rising straight up
        return pair("head", (-0.5, -4, -0.5), (1, 4, 1), "horn", pivot=(-3.5, -8.5, 0), rot=(0, 0, -12))
    if kind == "kuwagata":   # Akaviri / Blades: two flat horns rising from the brow
        return pair("head", (-0.5, -4, -0.5), (1, 4, 1), "trim", pivot=(-2.5, -8, -4.8), rot=(8, 0, -20))
    if kind == "antlers":    # Forsworn headdress
        return (pair("head", (-0.5, -4, -0.5), (1, 4, 1), "antler", pivot=(-3, -8.5, 0), rot=(0, 0, -30))
                + pair("head", (-0.5, -3, -0.5), (1, 3, 1), "antler", pivot=(-4.8, -11, 0), rot=(0, 0, 15))
                + pair("head", (-0.5, -2, -0.5), (1, 2, 1), "antler", pivot=(-4.5, -10.5, 0), rot=(0, 0, -70)))
    if kind == "ears":       # Wolf / hide: small animal ears
        return pair("head", (-1, -2, -0.5), (2, 2, 1), "fur", pivot=(-3, -8.6, 0.5))
    raise ValueError(kind)


def crest(kind):
    if kind == "fin":        # Glass / Elven: a thin fin from brow to nape
        return [Cube("head", (-0.5, -2, -3), (1, 2, 6), "crest", pivot=(0, -8.6, 0))]
    if kind == "plume":      # Imperial / Breton: a plume along the top
        return [Cube("head", (-0.5, -2, -3), (1, 2, 6), "plume", pivot=(0, -8.8, 0), inflate=0.3)]
    if kind == "ridge":      # Dragonplate / Dragonscale: a ridge of plates down the skull and back spikes
        return ([Cube("head", (-0.5, -2, -3), (1, 2, 6), "ridge", pivot=(0, -8.6, 0))]
                + pair("head", (-0.5, -0.5, 0), (1, 1, 4), "horn", pivot=(-3, -6.5, 4.5), rot=(25, 0, 0)))
    if kind == "spikes":     # Orcish / Daedric: spikes along the top
        return [Cube("head", (-0.5, -2, -0.5), (1, 2, 1), "spike", pivot=(0, -9, z)) for z in (-2.5, 0, 2.5)]
    if kind == "dwemer":     # Dwarven: crest and side discs
        return ([Cube("head", (-0.5, -2, -3), (1, 2, 6), "crest", pivot=(0, -8.6, 0))]
                + pair("head", (-1, -1.5, -1.5), (1, 3, 3), "trim", pivot=(-5.2, -4, 0)))
    if kind == "wings":      # Nordic carved / Stormcloak: wings swept back at the temples
        return pair("head", (-0.5, -2, 0), (1, 2, 4), "crest", pivot=(-5.2, -6, -1), rot=(-20, -15, 0))
    if kind == "ice":        # Stalhrim: ice shards
        return (pair("head", (-0.5, -4, -0.5), (1, 4, 1), "crystal", pivot=(-3.4, -8.6, -1), rot=(-15, 0, -25))
                + [Cube("head", (-0.5, -4, -0.5), (1, 4, 1), "crystal", pivot=(0, -8.8, 1), rot=(-30, 0, 0))])
    if kind == "chitin":     # Chitin / Falmer: a segmented crest sweeping back
        return ([Cube("head", (-0.5, -2, -0.5), (1, 2, 1), "spike", pivot=(0, -9, z), rot=(-35, 0, 0)) for z in (-3, -0.5, 2)]
                + pair("head", (-0.5, -1.5, -2), (1, 2, 4), "crest", pivot=(-5.2, -5, 1), rot=(-10, 0, 0)))
    if kind == "frills":     # Argonian / Maormer: frills fanning at the back of the head
        return pair("head", (-0.5, -3, 0), (1, 3, 3), "crest", pivot=(-4, -6, 4.2), rot=(-30, 0, -25))
    if kind == "ordinator":  # Ordinator: a tall triangular crest
        return [Cube("head", (-0.5, -2, -3), (1, 2, 6), "crest", pivot=(0, -8.6, 0), inflate=0.4)]
    raise ValueError(kind)


def head_extra(kind):
    if kind == "hood":       # cloth hood falling over the nape
        return [Cube("head", (-4, 0, 0), (8, 3, 1), "cloth", pivot=(0, -1, 4.8), rot=(12, 0, 0))]
    if kind == "kabuto":     # flared neck guard
        return ([Cube("head", (-5, 0, 0), (10, 3, 1), "lamellar", pivot=(0, -3, 4.6), rot=(25, 0, 0))]
                + pair("head", (-1, 0, -2.5), (1, 3, 5), "lamellar", pivot=(-4.6, -3, 0.6), rot=(0, 0, 25)))
    if kind == "nose":       # nose guard
        return [Cube("head", (-0.5, 0, -1), (1, 3, 1), "trim", pivot=(0, -6, -4.6))]
    if kind == "mask":       # face plate (Ordinator, Dwarven)
        return [Cube("head", (-3, 0, -1), (6, 4, 1), "mask", pivot=(0, -5, -4.6))]
    if kind == "veil":       # Ra Gada: cloth veil over the lower face
        return [Cube("head", (-4, 0, -1), (8, 3, 1), "cloth", pivot=(0, -3, -4.7))]
    if kind == "cheeks":     # cheek guards
        return pair("head", (0, 0, -1), (1, 3, 3), "trim", pivot=(-5, -4, -2.2))
    if kind == "brim":       # hat brim / visor ridge over the brow
        return [Cube("head", (-5, 0, -1), (10, 1, 2), "trim", pivot=(0, -6, -4.6))]
    if kind == "turban":     # wrapped cloth knot on top
        return [Cube("head", (-2, -2, -2), (4, 2, 4), "cloth", pivot=(0, -8.6, 0.5), inflate=0.4)]
    if kind == "fur_trim":   # fur rim round the cap
        return ([Cube("head", (-5, 0, -1), (10, 2, 1), "fur", pivot=(0, -4.5, -4.6))]
                + pair("head", (-1, 0, -3), (1, 2, 6), "fur", pivot=(-4.6, -4.5, 0)))
    raise ValueError(kind)


def pauldron(kind):
    # right shoulder: the arm spans x -3..1; with inflation 1.0 the shoulder top is at y -3
    if kind == "plate":
        return pair("right_arm", (-4.2, -4.2, -2.7), (5, 3, 5), "pauldron", inflate=0.45)
    if kind == "layered":
        return (pair("right_arm", (-4.2, -4.4, -2.7), (5, 3, 5), "pauldron", inflate=0.45)
                + pair("right_arm", (-4.6, -1.6, -2.5), (2, 2, 5), "pauldron2", inflate=0.4))
    if kind == "round":
        return pair("right_arm", (-4.0, -4.0, -2.5), (5, 3, 5), "pauldron", inflate=0.6, rot=(0, 0, 8))
    if kind == "spiked":
        return (pair("right_arm", (-4.2, -4.2, -2.7), (5, 3, 5), "pauldron", inflate=0.45)
                + pair("right_arm", (-0.5, -3, -0.5), (1, 3, 1), "spike", pivot=(-2.2, -4.2, 0), rot=(0, 0, -30)))
    if kind == "horned":     # Daedric: spikes rising off the shoulders
        return (pair("right_arm", (-4.2, -4.2, -2.7), (5, 3, 5), "pauldron", inflate=0.5)
                + pair("right_arm", (-0.5, -4, -0.5), (1, 4, 1), "spike", pivot=(-2.4, -4.2, -1), rot=(10, 0, -20))
                + pair("right_arm", (-0.5, -3, -0.5), (1, 3, 1), "spike", pivot=(-2.0, -4.2, 1.6), rot=(-20, 0, -15)))
    if kind == "fur":        # pelt over the shoulders
        return pair("right_arm", (-4.2, -4.0, -2.8), (5, 2, 5), "fur", inflate=0.6)
    if kind == "small":
        return pair("right_arm", (-3.8, -3.6, -2.8), (4, 2, 5), "pauldron", inflate=0.1)
    if kind == "lamellar":   # Akaviri sode: square plates hanging off the shoulder
        return pair("right_arm", (-1, 0, -2.5), (1, 3, 5), "lamellar", pivot=(-3.9, -3.2, 0), rot=(0, 0, 18), inflate=0.3)
    if kind == "chitin":
        return (pair("right_arm", (-4.0, -4.0, -2.5), (5, 2, 5), "pauldron", inflate=0.5)
                + pair("right_arm", (-4.4, -2.0, -2.5), (2, 2, 5), "pauldron2", inflate=0.4))
    if kind == "crystal":    # Glass / Stalhrim: a ridge rising from the shoulder
        return (pair("right_arm", (-4.2, -4.2, -2.7), (5, 3, 5), "pauldron", inflate=0.4)
                + pair("right_arm", (-0.5, -2.5, -2.5), (1, 3, 5), "crystal", pivot=(-2.4, -4.4, 0), rot=(0, 0, -20)))
    if kind == "dragon":     # Dragonplate: overlapping scaled plates and a claw
        return (pair("right_arm", (-4.2, -4.2, -2.7), (5, 3, 5), "pauldron", inflate=0.45)
                + pair("right_arm", (-0.5, -3, -0.5), (1, 3, 1), "horn", pivot=(-3.8, -3.4, 2.4), rot=(-40, 0, -50)))
    raise ValueError(kind)


def chest_extra(kind):
    if kind == "fauld":      # plates hanging below the belt over the thighs (follow the legs)
        return pair("right_leg", (-2.5, 0, -3.2), (5, 4, 1), "fauld")
    if kind == "fauld_back":
        return pair("right_leg", (-2.5, 0, -3.2), (5, 4, 1), "fauld") + pair("right_leg", (-2.5, 0, 2.2), (5, 4, 1), "fauld")
    if kind == "skirt":      # robes / long coats: front and back panels down to the knee
        return pair("right_leg", (-2.4, 0, -3.1), (5, 7, 1), "skirt", inflate=0.1) + pair("right_leg", (-2.4, 0, 2.1), (5, 7, 1), "skirt", inflate=0.1)
    if kind == "spine":      # dragon armour: spines down the back
        return [Cube("body", (-0.5, -2, -0.5), (1, 2, 1), "horn", pivot=(0, y, 3.2), rot=(-50, 0, 0)) for y in (1.5, 4.5, 7.5)]
    if kind == "cape":       # short cape off the shoulders
        return [Cube("body", (-4, 0, 0), (8, 7, 1), "cape", pivot=(0, 0.5, 3.0), rot=(6, 0, 0), inflate=0.3)]
    if kind == "collar":     # fur collar / mantle round the neck
        return [Cube("body", (-4.5, -2, -2.5), (9, 2, 5), "fur", pivot=(0, 1, 0), inflate=0.35)]
    if kind == "gorget":     # raised plate collar
        return [Cube("body", (-4, -1, -2.7), (8, 2, 5), "pauldron", pivot=(0, 0.6, 0), inflate=0.15)]
    if kind == "belt_pouch":
        return [Cube("body", (0, 0, -1), (3, 2, 1), "leather", pivot=(1, 9.5, -2.6))]
    raise ValueError(kind)


def boots_extra(kind):
    # boots: leg spans y 0..12, inflated 1.0; the boot top is around y 6
    if kind == "cuff":
        return pair("right_leg", (-2.5, 0, -2.5), (5, 2, 5), "cuff", pivot=(0, 5.2, 0), inflate=0.3)
    if kind == "fur_cuff":
        return pair("right_leg", (-2.5, 0, -2.5), (5, 2, 5), "fur", pivot=(0, 5.0, 0), inflate=0.45)
    if kind == "knee":
        return pair("right_leg", (-1.5, 0, -1), (3, 3, 1), "cuff", pivot=(0, 3.4, -3.0))
    if kind == "spike":
        return pair("right_leg", (-0.5, -2, -0.5), (1, 2, 1), "spike", pivot=(0, 3.6, -3.4), rot=(40, 0, 0))
    if kind == "toe":
        return pair("right_leg", (-1.5, 0, -1), (3, 2, 2), "cuff", pivot=(0, 10.2, -3.6))
    raise ValueError(kind)


def gauntlet_extra(kind):
    if kind == "cuff":
        return pair("right_arm", (-3.5, 0, -2.5), (5, 2, 5), "gcuff", pivot=(0, 3.4, 0), inflate=0.35)
    if kind == "spikes":
        return pair("right_arm", (-0.5, -2, -0.5), (1, 2, 1), "spike", pivot=(-4.2, 5, 0), rot=(0, 0, -60))
    if kind == "fins":
        return pair("right_arm", (-1, 0, -1.5), (1, 3, 3), "gcuff", pivot=(-3.6, 4, 0), rot=(0, 0, 20))
    raise ValueError(kind)


def greaves_extra(kind):
    if kind == "knee":
        return pair("right_leg", (-1.5, 0, -1), (3, 2, 1), "knee", pivot=(0, 4, -2.6))
    if kind == "tasset":
        return [Cube("body", (-4.5, 0, -2.6), (9, 3, 5), "belt", pivot=(0, 9.5, 0), inflate=0.1)]
    raise ValueError(kind)


# --------------------------------------------------------------------------------------------- styles

def style(head=(), chest=(), legs=(), feet=(), head_inflate=1.0, hood=False):
    return dict(head=list(head), chest=list(chest), legs=list(legs), feet=list(feet), head_inflate=head_inflate, hood=hood)


H, CR, HX = horns, crest, head_extra
PA, CX, BX, GX, LX = pauldron, chest_extra, boots_extra, gauntlet_extra, greaves_extra

STYLES = {
    # light
    "hide": style(HX("fur_trim") + H("ears"), PA("fur") + CX("collar"), GX("cuff"), BX("fur_cuff")),
    "leather": style(HX("brim"), PA("small") + CX("belt_pouch"), GX("cuff"), BX("cuff")),
    "fur": style(HX("hood") + HX("fur_trim"), PA("fur") + CX("collar"), GX("cuff"), BX("fur_cuff"), head_inflate=1.2, hood=True),
    "studded": style(HX("brim"), PA("small") + CX("fauld"), GX("cuff"), BX("cuff")),
    "elven": style(CR("fin") + HX("cheeks"), PA("round") + CX("fauld"), GX("fins"), BX("cuff") + BX("knee")),
    "elven_gilded": style(CR("fin") + HX("cheeks"), PA("layered") + CX("fauld") + CX("gorget"), GX("fins"), BX("cuff") + BX("knee")),
    "scaled": style(H("scaled") + HX("nose"), PA("layered") + CX("fauld"), GX("cuff"), BX("cuff")),
    "glass": style(CR("fin") + HX("cheeks"), PA("crystal") + CX("fauld"), GX("fins"), BX("cuff") + BX("knee")),
    "stalhrim_light": style(CR("ice"), PA("crystal") + CX("fauld"), GX("cuff"), BX("cuff")),
    "dragonscale": style(CR("ridge") + HX("cheeks"), PA("dragon") + CX("spine") + CX("fauld"), GX("spikes"), BX("cuff") + BX("spike")),
    "forsworn": style(H("antlers") + HX("fur_trim"), PA("fur") + CX("fauld"), GX("cuff"), BX("fur_cuff")),
    "thieves_guild": style(HX("hood"), PA("small") + CX("belt_pouch") + CX("fauld"), GX("cuff"), BX("cuff"), head_inflate=1.25, hood=True),
    "nightingale": style(HX("hood") + HX("veil"), PA("small") + CX("cape"), GX("cuff"), BX("cuff") + BX("knee"), head_inflate=1.25, hood=True),
    "shrouded": style(HX("hood"), CX("skirt"), GX("cuff"), BX("cuff"), head_inflate=1.3, hood=True),
    "vampire": style(HX("hood"), PA("small") + CX("skirt"), GX("cuff"), BX("cuff"), head_inflate=1.25, hood=True),
    "chitin": style(CR("chitin"), PA("chitin") + CX("fauld"), GX("fins"), BX("cuff")),
    "imperial_light": style(CR("plume") + HX("cheeks"), PA("small") + CX("fauld") + CX("cape"), GX("cuff"), BX("cuff")),
    # heavy
    "iron": style(H("iron") + HX("nose"), PA("plate") + CX("fauld"), GX("cuff"), BX("cuff")),
    "banded_iron": style(HX("nose") + HX("cheeks"), PA("layered") + CX("fauld_back"), GX("cuff"), BX("cuff") + BX("knee")),
    "steel": style(H("steel") + HX("nose"), PA("layered") + CX("fauld"), GX("cuff"), BX("cuff") + BX("knee")),
    "steel_plate": style(HX("brim") + HX("cheeks"), PA("layered") + CX("fauld_back") + CX("gorget"), GX("cuff"), BX("cuff") + BX("knee") + BX("toe")),
    "dwarven": style(CR("dwemer") + HX("mask"), PA("layered") + CX("fauld_back") + CX("gorget"), GX("cuff"), BX("cuff") + BX("toe")),
    "orcish": style(CR("spikes") + HX("cheeks"), PA("spiked") + CX("fauld"), GX("cuff") + GX("spikes"), BX("cuff") + BX("spike")),
    "ebony": style(CR("wings") + HX("cheeks"), PA("layered") + CX("fauld_back") + CX("gorget"), GX("cuff") + GX("fins"), BX("cuff") + BX("knee") + BX("toe")),
    "stalhrim_heavy": style(CR("ice") + HX("cheeks"), PA("crystal") + CX("fauld_back"), GX("cuff") + GX("fins"), BX("cuff") + BX("knee")),
    "daedric": style(H("big") + CR("spikes"), PA("horned") + CX("fauld_back") + CX("spine"), GX("cuff") + GX("spikes"), BX("cuff") + BX("spike") + BX("toe")),
    "dragonplate": style(CR("ridge") + HX("cheeks"), PA("dragon") + CX("spine") + CX("fauld_back"), GX("cuff") + GX("spikes"), BX("cuff") + BX("spike") + BX("toe")),
    "nordic": style(CR("wings") + HX("nose"), PA("round") + CX("fauld") + CX("collar"), GX("cuff"), BX("cuff") + BX("knee")),
    "bonemold": style(HX("cheeks") + CR("fin"), PA("round") + CX("fauld"), GX("cuff"), BX("cuff")),
    "ancient_nord": style(H("iron") + HX("nose"), PA("plate") + CX("fauld"), GX("cuff"), BX("cuff")),
    "falmer": style(CR("chitin") + HX("mask"), PA("chitin") + CX("fauld_back"), GX("fins") + GX("spikes"), BX("cuff") + BX("spike")),
    "blades": style(H("kuwagata") + HX("kabuto"), PA("lamellar") + CX("fauld_back"), GX("cuff"), BX("cuff") + BX("knee")),
    "imperial": style(CR("plume") + HX("cheeks"), PA("layered") + CX("fauld") + CX("cape"), GX("cuff"), BX("cuff") + BX("knee")),
    "stormcloak": style(CR("wings") + HX("nose") + HX("fur_trim"), PA("fur") + CX("collar") + CX("fauld"), GX("cuff"), BX("fur_cuff")),
    "dawnguard": style(CR("fin") + HX("cheeks") + HX("nose"), PA("layered") + CX("fauld_back") + CX("cape"), GX("cuff"), BX("cuff") + BX("knee")),
    "wolf": style(H("ears") + HX("nose") + HX("cheeks"), PA("plate") + CX("collar") + CX("fauld"), GX("cuff"), BX("cuff")),
    # the provinces
    "netch": style(CR("chitin") + HX("brim"), PA("round") + CX("fauld"), GX("cuff"), BX("cuff")),
    "ordinator": style(CR("ordinator") + HX("mask"), PA("layered") + CX("skirt") + CX("gorget"), GX("cuff"), BX("cuff") + BX("knee")),
    "thalmor": style(HX("hood"), PA("small") + CX("skirt"), GX("cuff"), BX("cuff"), head_inflate=1.25, hood=True),
    "bosmer": style(H("antlers") + HX("brim"), PA("small") + CX("fauld") + CX("belt_pouch"), GX("cuff"), BX("cuff")),
    "ra_gada": style(HX("turban") + HX("veil"), PA("small") + CX("skirt"), GX("cuff"), BX("cuff"), head_inflate=1.15),
    "breton": style(CR("plume") + HX("cheeks"), PA("layered") + CX("fauld_back") + CX("gorget"), GX("cuff"), BX("cuff") + BX("knee") + BX("toe")),
    "argonian": style(CR("frills"), PA("chitin") + CX("fauld"), GX("fins"), BX("cuff")),
    "khajiit": style(CR("fin") + HX("cheeks"), PA("small") + CX("skirt"), GX("cuff"), BX("cuff")),
    "orsinium": style(H("iron") + CR("spikes") + HX("cheeks"), PA("spiked") + CX("fauld_back") + CX("collar"), GX("cuff") + GX("spikes"), BX("cuff") + BX("spike")),
    "akaviri": style(H("kuwagata") + HX("kabuto"), PA("lamellar") + CX("fauld_back"), GX("cuff"), BX("cuff")),
    "atmoran": style(H("steel") + HX("nose") + HX("cheeks"), PA("fur") + CX("collar") + CX("fauld"), GX("cuff"), BX("fur_cuff")),
    "maormer": style(CR("frills") + CR("fin"), PA("crystal") + CX("fauld"), GX("fins"), BX("cuff")),
}


# --------------------------------------------------------------------------------------------- UV swatches
# The vanilla 64x32 layout leaves too little room to unwrap every extra cube, so cubes of one material share a
# "swatch": a region painted with that material's pattern, at whose corner every cube of the material starts its UVs.

SWATCH_OF = {
    "pauldron": "plate", "pauldron2": "plate", "cuff": "plate", "fauld": "plate", "gorget": "plate", "knee": "plate",
    "mask": "plate", "gcuff": "plate", "belt": "plate", "trim": "trim", "crest": "crest", "ridge": "crest",
    "horn": "horn", "horn_tip": "horn", "spike": "horn", "antler": "horn", "fur": "fur", "cloth": "cloth", "cape": "cloth",
    "skirt": "cloth", "plume": "plume", "crystal": "crystal", "lamellar": "lamellar", "leather": "leather",
}
REGIONS = {
    1: [(32, 0, 32, 16), (56, 16, 8, 16), (0, 0, 8, 8), (24, 0, 8, 8), (16, 16, 4, 4), (36, 16, 4, 4),
        (52, 16, 4, 4), (0, 16, 4, 4), (12, 16, 4, 4)],
    2: [(0, 0, 64, 16), (56, 16, 8, 16)],
}


class Packer:
    """Guillotine packer over a list of free rectangles."""

    def __init__(self, free):
        self.free = [r for r in free if r[2] > 0 and r[3] > 0]

    def place(self, w, h):
        best = None
        for i, (x, y, fw, fh) in enumerate(self.free):
            if w <= fw and h <= fh:
                waste = fw * fh - w * h
                if best is None or waste < best[0]:
                    best = (waste, i)
        if best is None:
            return None
        x, y, fw, fh = self.free.pop(best[1])
        # keep the larger leftover strip whole
        if (fw - w) * fh >= fw * (fh - h):
            if fw - w > 0:
                self.free.append((x + w, y, fw - w, fh))
            if fh - h > 0:
                self.free.append((x, y + h, w, fh - h))
        else:
            if fh - h > 0:
                self.free.append((x, y + h, fw, fh - h))
            if fw - w > 0:
                self.free.append((x + w, y, fw - w, h))
        return x, y


def assign_swatches(style_id, layer, cubes):
    """Gives every non-fixed cube the UV of its material's swatch; returns {swatch kind: region}."""
    need = {}
    for c in cubes:
        if c.uv is not None and getattr(c, "fixed", False):
            continue
        kind = SWATCH_OF[c.paint]
        fw, fh = footprint(c.size)
        w, h = need.get(kind, (0, 0))
        need[kind] = (max(w, fw), max(h, fh))
    packer = Packer(REGIONS[layer])
    out = {}
    for kind, (w, h) in sorted(need.items(), key=lambda kv: (-kv[1][0] * kv[1][1], kv[0])):
        pos = packer.place(w, h)
        if pos is None:
            raise RuntimeError(f"style {style_id}: no layer-{layer} swatch region for {kind} {w}x{h}")
        out[kind] = (pos[0], pos[1], w, h)
    for c in cubes:
        if getattr(c, "fixed", False):
            continue
        r = out[SWATCH_OF[c.paint]]
        c.uv = (r[0], r[1])
    return out


def build(style_id):
    """Returns the slots (base parts + cubes), the cubes per layer and the swatch regions per layer."""
    st = STYLES[style_id]
    slots = {
        "head": dict(base={"head": st["head_inflate"]}, cubes=[copy.copy(c) for c in st["head"]]),
        "chest": dict(base={"body": 1.0, "right_arm": 1.0, "left_arm": 1.0}, cubes=[copy.copy(c) for c in st["chest"]]),
        "feet": dict(base={"right_leg": 1.0, "left_leg": 1.0}, cubes=[copy.copy(c) for c in st["feet"]]),
        "legs": dict(base={"body": 0.5, "right_leg": 0.5, "left_leg": 0.5}, cubes=[]),
    }
    g = Cube(GAUNTLET["part"], GAUNTLET["frm"], GAUNTLET["size"], GAUNTLET["paint"], inflate=GAUNTLET["inflate"], uv=GAUNTLET["uv"])
    g.fixed = True
    gm = g.mirror()
    gm.fixed = True
    slots["legs"]["cubes"] = [g, gm] + [copy.copy(c) for c in st["legs"]]
    l1 = [c for s in ("head", "chest", "feet") for c in slots[s]["cubes"]]
    l2 = slots["legs"]["cubes"]
    sw1 = assign_swatches(style_id, 1, l1)
    sw2 = assign_swatches(style_id, 2, l2)
    return dict(slots=slots, layer1=l1, layer2=l2, swatches={1: sw1, 2: sw2})


def to_json(built):
    out = {}
    for slot, d in built["slots"].items():
        out[slot] = {"base": d["base"], "cubes": [c.json() for c in d["cubes"]]}
    return out
