package com.skycraft.tamriel.settlement.grammar;

import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.kit.Kits;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.kit.Shell;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the grammar decides about one building, derived deterministically from its spec (culture, kit style,
 * {@link Use}, {@link Wealth}, grammar seed, footprint and planning-time slope): the material variant, the body (a
 * rectangular grammar body split into footprint parts, or an exotic culture shell), storeys, roof, foundation, porch,
 * annex, outside stairs, balcony, chimney, windows and decoration. The planner derives the same blueprint to keep
 * neighbours different ({@link #signature}); the painter ({@link Grammar}) draws it chunk by chunk.
 *
 * <p>Local frame as in {@link com.skycraft.tamriel.settlement.build.Canvas}: x along the front (0..w-1), z from the
 * front (0) to the back (d-1), y = 0 the floor block.</p>
 */
public final class Blueprint {
    public enum Shape {RECT, L, T, U}

    public enum Roof {GABLE, STEEP, HIP, FLAT, DOME, GAMBREL, SLATE, SHED, THATCH, SPIRE, CRENEL}

    public enum Body {RECT, ROUND, CHITIN, MUSHROOM, TREE, PAGODA, TENT, TOWER}

    public enum Foundation {FLAT, PLINTH, STILTS}

    public enum Annex {NONE, LEAN_TO, WORKSHOP, WOODSHED, TOWER, PEN}

    /** One rectangular part of the body (inclusive local bounds); {@code alongZ}: its ridge runs front to back. */
    public record Part(int x0, int z0, int x1, int z1, boolean main, boolean alongZ) {
        public int w() {
            return x1 - x0 + 1;
        }

        public int d() {
            return z1 - z0 + 1;
        }

        public boolean contains(int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }
    }

    public final Spec spec;
    public final Use use;
    public final Wealth wealth;
    public final Culture culture;
    public final Style style;
    public final Palette pal;
    public final int variant;
    public final int w, d;
    public Body body = Body.RECT;
    public Shape shape = Shape.RECT;
    public Roof roof = Roof.GABLE;
    public Foundation foundation = Foundation.FLAT;
    public Annex annex = Annex.NONE;
    /** -1 left (x = 0 side), +1 right, 0 none. */
    public int annexSide;
    public int annexW;
    /** Outside stair to the upper floor: side (-1/+1) or 0. */
    public int stairSide;
    public int porchD;
    public int storeys = 1;
    public int wallH = 4;
    public int pitch = 1;
    public boolean timber;
    public boolean balcony;
    public boolean chimney;
    public boolean shutters;
    public boolean flowerBoxes;
    public boolean banner;
    public boolean lanterns;
    public boolean awning;
    public boolean dormers;
    public boolean horns;
    public BlockState window;
    public String color = "red";
    /** Body bounds (inside annex / stair / porch strips). */
    public int bx0, bx1, bz0, bz1;
    public final List<Part> parts = new ArrayList<>();
    public int doorX;
    /** The style's faith (temples and shrines). */
    public String faith = "mara";

    private Blueprint(Spec s) {
        this.spec = s;
        this.use = Use.byOrdinal(s.use);
        this.wealth = Wealth.byOrdinal(s.wealth);
        this.culture = Culture.byOrdinal(s.culture);
        this.style = Styles.of(culture, s.style);
        this.w = s.w;
        this.d = s.d;
        RandomSource r = RandomSource.create(seed(s));
        List<Style.Variant> vs = style.variants(wealth);
        this.variant = r.nextInt(vs.size());
        Palette kit = Kits.palette(culture, s.style);
        this.pal = vs.get(variant).apply(kit);
        derive(r, kit);
    }

    public static Blueprint of(Spec s) {
        return new Blueprint(s);
    }

    static long seed(Spec s) {
        return (s.gseed * 0x9E3779B97F4A7C15L) ^ ((long) s.use << 40) ^ ((long) s.wealth << 52) ^ ((long) s.culture << 20) ^ s.style;
    }

    /** Footprint + roof + palette + body: what neighbours must not share. */
    public int signature() {
        return ((((shape.ordinal() * 16 + roof.ordinal()) * 16 + variant) * 8 + body.ordinal()) * 4 + Math.min(3, storeys)) * 64 + w * 8 + d;
    }

    // ------------------------------------------------------------------ derivation

    private void derive(RandomSource r, Palette kit) {
        faith = style.faith(r);
        color = style.color(r.nextInt(16));
        // ---- body
        String b = use.hall ? style.hallBody : style.pick(style.bodies, wealth, "rect", r);
        body = bodyOf(b, kit);
        if (use == Use.WATCHTOWER) body = Body.TOWER;
        if (use == Use.STABLES || use == Use.BARN || use == Use.SAWMILL || use == Use.SMELTER || use == Use.WAREHOUSE
                || use == Use.SMITHY || use == Use.KEEP || use == Use.MILL || use == Use.FENCE_DEN && body == Body.TREE) body = Body.RECT;
        if (use == Use.MAGE_TOWER && body != Body.MUSHROOM) body = Body.RECT;
        if (body == Body.TENT && !use.home() && use != Use.SHACK) body = Body.RECT;
        // ---- storeys and walls
        int[] st = style.storeys(wealth);
        storeys = Math.max(use.stMin, Math.min(use.stMax, st[0] + (st[1] > st[0] ? r.nextInt(st[1] - st[0] + 1) : 0)));
        if (spec.has(Spec.UPPER)) storeys = Math.max(storeys, 2);
        if (use.stMax == 1) storeys = 1;
        wallH = use.wallH + (wealth == Wealth.NOBLE && !use.hall ? 1 : 0);
        if (use == Use.HOVEL || use == Use.SHACK) wallH = 3;
        // ---- roof
        String rf = use.hall ? Style.weighted(style.hallRoofs, null, r) : null;
        if (rf == null) rf = style.pick(style.roofs, wealth, defaultRoof(kit), r);
        roof = roofOf(rf);
        pitch = style.pitch > 0 ? style.pitch : kit.pitch;
        if (pal.pitch != kit.pitch) pitch = pal.pitch;
        switch (use) {
            case BARN -> roof = r.nextInt(3) == 0 || roof == Roof.FLAT || roof == Roof.DOME ? Roof.GAMBREL : roof == Roof.HIP ? Roof.HIP : Roof.GAMBREL;
            case MAGE_TOWER -> roof = roof == Roof.DOME || roof == Roof.FLAT ? Roof.DOME : Roof.SPIRE;
            case KEEP -> roof = roof == Roof.STEEP || roof == Roof.SLATE ? roof : Roof.CRENEL;
            case SAWMILL, SMELTER -> roof = roof == Roof.FLAT || roof == Roof.DOME ? Roof.FLAT : Roof.SHED;
            case STABLES -> roof = roof == Roof.FLAT || roof == Roof.DOME ? Roof.FLAT : Roof.GABLE;
            case HOVEL, SHACK -> {
                if (roof == Roof.DOME) roof = Roof.FLAT;
                if (roof == Roof.HIP || roof == Roof.SLATE) roof = Roof.GABLE;
            }
            default -> {
            }
        }
        if (roof == Roof.STEEP || roof == Roof.SLATE) pitch = 2;
        horns = style.horns && (roof == Roof.STEEP || use.hall || use == Use.MANOR) && roof != Roof.FLAT && roof != Roof.DOME;
        // ---- timber framing and windows
        timber = pal.timber && r.nextDouble() < style.rule("timber", 0.8);
        BlockState win = Style.windowBlock(style.windows, pal);
        window = win != null && r.nextBoolean() ? win : pal.window;
        if (wealth.atLeast(Wealth.WEALTHY) && window.is(Blocks.SPRUCE_FENCE)) window = pal.fence;
        // ---- foundation
        if (spec.has(Spec.WATER) || style.stilts || kit.stilts) foundation = Foundation.STILTS;
        else if (spec.slope >= style.rule("plinth", 3) || use == Use.KEEP || use == Use.TEMPLE && wealth.atLeast(Wealth.WEALTHY)) foundation = Foundation.PLINTH;
        // ---- strips: annex, outside stairs, porch
        annex = annexFor(r);
        if (annex != Annex.NONE) {
            annexSide = use == Use.SMITHY ? -1 : r.nextBoolean() ? -1 : 1;
            annexW = switch (annex) {
                case WORKSHOP -> Math.max(4, Math.min(5, w / 3));
                case TOWER -> 5;
                default -> 3;
            };
            if (w - annexW < 7) {
                annex = Annex.NONE;
                annexSide = 0;
                annexW = 0;
            }
        }
        if (storeys >= 2 && body == Body.RECT && r.nextDouble() < style.rule("outsideStairs", 0) && use.home()) {
            stairSide = annexSide == 0 ? (r.nextBoolean() ? -1 : 1) : -annexSide;
            if (w - annexW - 2 < 7) stairSide = 0;
        }
        boolean porchy = use.home() || use == Use.INN || use.trade && use != Use.SMITHY;
        if (porchy && body == Body.RECT && d >= 9 && r.nextDouble() < style.rule("porch", 0.3) * (use == Use.INN ? 1.5 : 1)) porchD = 2;
        bx0 = (annexSide < 0 ? annexW : 0) + (stairSide < 0 ? 2 : 0);
        bx1 = w - 1 - (annexSide > 0 ? annexW : 0) - (stairSide > 0 ? 2 : 0);
        bz0 = porchD;
        bz1 = d - 1;
        // ---- footprint
        String sh = use == Use.TEMPLE ? (r.nextInt(3) == 0 ? "t" : "rect")
                : use == Use.HOVEL || use == Use.SHACK || use == Use.MAGE_TOWER || use == Use.FENCE_DEN || use == Use.BARN
                || use == Use.STABLES || use == Use.SAWMILL || use == Use.SMELTER || use == Use.WAREHOUSE || use == Use.SMITHY
                || use == Use.WATCHTOWER || use == Use.LONGHOUSE || use == Use.BARRACKS ? "rect"
                : style.pick(style.footprints, wealth, "rect", r);
        shape = shapeOf(sh);
        if (body != Body.RECT) shape = Shape.RECT;
        layout(r);
        // ---- extras
        chimney = style.hearth && body == Body.RECT && roof != Roof.FLAT && roof != Roof.DOME && roof != Roof.CRENEL && roof != Roof.SPIRE
                && use != Use.TEMPLE && use != Use.STABLES && use != Use.BARN && use != Use.WAREHOUSE && use != Use.KEEP
                && r.nextDouble() < style.rule("chimney", 0.8);
        balcony = storeys >= 2 && body == Body.RECT && porchD == 0 && wealth.atLeast(Wealth.COMMON) && r.nextDouble() < style.rule("balcony", 0.2);
        shutters = body == Body.RECT && r.nextDouble() < style.rule("shutters", 0.3);
        flowerBoxes = body == Body.RECT && wealth.atLeast(Wealth.COMMON) && r.nextDouble() < style.rule("flowerBoxes", 0.3);
        banner = (use.sign || wealth.atLeast(Wealth.WEALTHY)) && r.nextDouble() < style.rule("banners", 0.5);
        lanterns = wealth.atLeast(Wealth.COMMON) && r.nextDouble() < style.rule("lanterns", 0.6);
        awning = porchD == 0 && (use.trade || wealth.atLeast(Wealth.COMMON)) && r.nextDouble() < style.rule("awning", 0.4);
        dormers = roof == Roof.SLATE || (roof == Roof.GABLE || roof == Roof.HIP) && storeys >= 2 && r.nextDouble() < style.rule("dormers", 0.15);
    }

    private Annex annexFor(RandomSource r) {
        if (body != Body.RECT) return Annex.NONE;
        return switch (use) {
            case SMITHY -> Annex.WORKSHOP;
            case FARMHOUSE -> r.nextBoolean() ? Annex.PEN : Annex.WOODSHED;
            case MANOR, KEEP -> r.nextDouble() < style.rule("tower", 0.5) ? Annex.TOWER : Annex.NONE;
            case TANNERY, FLETCHER, BUTCHER, SAWMILL -> Annex.LEAN_TO;
            case HOVEL, SHACK, MAGE_TOWER, WATCHTOWER, TEMPLE, HALL_OF_DEAD, STABLES, BARN, WAREHOUSE, MILL, FENCE_DEN, BARRACKS, GUARDHOUSE -> Annex.NONE;
            default -> r.nextDouble() < style.rule("annex", 0.3) ? (r.nextBoolean() ? Annex.WOODSHED : Annex.LEAN_TO) : Annex.NONE;
        };
    }

    /** Splits the body into its footprint parts (the main bar first, then wings). */
    private void layout(RandomSource r) {
        int bw = bx1 - bx0 + 1, bd = bz1 - bz0 + 1;
        if (shape != Shape.RECT && (bw < 9 || bd < 9)) shape = Shape.RECT;
        if (shape == Shape.U && bw < 13) shape = Shape.L;
        boolean barAlongZ = bd > bw;
        switch (shape) {
            case RECT -> parts.add(new Part(bx0, bz0, bx1, bz1, true, bd >= bw));
            case L -> {
                int fd = Math.max(5, Use.odd((int) Math.round(bd * 0.55)) );
                if (fd >= bd - 2) fd = bd - 3;
                int ww = Math.max(5, Use.odd((int) Math.round(bw * 0.45)));
                boolean left = r.nextBoolean();
                parts.add(new Part(bx0, bz0, bx1, bz0 + fd - 1, true, false));
                int zb = bz0 + fd - 1;
                parts.add(left ? new Part(bx0, zb, bx0 + ww - 1, bz1, false, true) : new Part(bx1 - ww + 1, zb, bx1, bz1, false, true));
            }
            case T -> {
                int fd = Math.max(5, Use.odd((int) Math.round(bd * 0.5)));
                if (fd >= bd - 2) fd = bd - 3;
                int ws = Math.max(5, Use.odd((int) Math.round(bw * 0.45)));
                int cx = (bx0 + bx1) / 2;
                parts.add(new Part(bx0, bz0, bx1, bz0 + fd - 1, true, false));
                parts.add(new Part(cx - ws / 2, bz0 + fd - 1, cx + ws / 2, bz1, false, true));
            }
            case U -> {
                int fd = Math.max(5, Use.odd((int) Math.round(bd * 0.5)));
                if (fd >= bd - 2) fd = bd - 3;
                int ww = Math.max(5, Use.odd((int) Math.round(bw * 0.3)));
                parts.add(new Part(bx0, bz0, bx1, bz0 + fd - 1, true, false));
                parts.add(new Part(bx0, bz0 + fd - 1, bx0 + ww - 1, bz1, false, true));
                parts.add(new Part(bx1 - ww + 1, bz0 + fd - 1, bx1, bz1, false, true));
            }
        }
        if (shape == Shape.RECT && !barAlongZ && parts.size() == 1) {
            Part p = parts.get(0);
            parts.set(0, new Part(p.x0(), p.z0(), p.x1(), p.z1(), true, false));
        }
        Part m = parts.get(0);
        doorX = (m.x0() + m.x1()) / 2;
    }

    static Body bodyOf(String s, Palette kit) {
        return switch (s == null ? "rect" : s.toLowerCase(Locale.ROOT)) {
            case "round", "cone" -> Body.ROUND;
            case "chitin" -> Body.CHITIN;
            case "mushroom" -> Body.MUSHROOM;
            case "tree" -> Body.TREE;
            case "pagoda" -> Body.PAGODA;
            case "tent" -> Body.TENT;
            case "kit" -> switch (kit.shell) {
                case ROUND -> Body.ROUND;
                case CHITIN -> Body.CHITIN;
                case MUSHROOM -> Body.MUSHROOM;
                case TREE -> Body.TREE;
                case PAGODA -> Body.PAGODA;
                case TENT -> Body.TENT;
                default -> Body.RECT;
            };
            default -> Body.RECT;
        };
    }

    static String defaultRoof(Palette kit) {
        return switch (kit.shell) {
            case HIP -> "hip";
            case FLAT -> "flat";
            case DOME -> "dome";
            default -> kit.pitch >= 2 ? "steep" : "gable";
        };
    }

    static Roof roofOf(String s) {
        try {
            return Roof.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Roof.GABLE;
        }
    }

    static Shape shapeOf(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "l" -> Shape.L;
            case "t" -> Shape.T;
            case "u" -> Shape.U;
            default -> Shape.RECT;
        };
    }

    // ------------------------------------------------------------------ geometry

    public int floorH() {
        return wallH + 1;
    }

    /** y of the top wall course. */
    public int wallTop() {
        return storeys * floorH() - 1;
    }

    /** How far a roof over a part (eaves included) rises above its base. */
    public int rise(Part p) {
        int span = (p.alongZ() ? p.w() : p.d()) + 2;
        return switch (roof) {
            case FLAT, CRENEL -> 2;
            case DOME -> Math.min(p.w(), p.d()) / 2 + 3;
            case SHED -> span / 2 + 1;
            case GAMBREL -> span / 2 + 2;
            case SPIRE -> Math.max(p.w(), p.d()) + 4;
            default -> (span / 2 + 1) * Math.max(1, pitch);
        };
    }

    /** Height the building needs above its floor (the structure piece's bounding box). */
    public int height() {
        int h;
        if (body == Body.RECT) {
            int rise = 0;
            for (Part p : parts) rise = Math.max(rise, rise(p));
            h = wallTop() + 1 + rise + 4;
            if (annex == Annex.TOWER) h = Math.max(h, wallTop() + floorH() + 10);
        } else {
            Shell shell = shell();
            int st = Math.max(storeys, body == Body.TOWER ? 3 : 1);
            h = st * (wallH + 1) + (Math.max(w, d) / 2 + 2) * 2 + 4;
            if (shell == Shell.TREE) h = Math.max(h, 6 + wallH + Math.max(3, Math.min(w, d) / 2 - 1) + 8);
            if (shell == Shell.MUSHROOM) h = Math.max(h, wallH * storeys + 3 + 8);
        }
        return Math.max(h, spec.h);
    }

    /** The legacy shell drawing an exotic body. */
    public Shell shell() {
        return switch (body) {
            case ROUND -> Shell.ROUND;
            case CHITIN -> Shell.CHITIN;
            case MUSHROOM -> Shell.MUSHROOM;
            case TREE -> Shell.TREE;
            case PAGODA -> Shell.PAGODA;
            case TENT -> Shell.TENT;
            case TOWER -> Shell.TOWER;
            default -> Shell.GABLE;
        };
    }

    /** True if local (x, z) lies inside the body footprint. */
    public boolean inBody(int x, int z) {
        for (Part p : parts) if (p.contains(x, z)) return true;
        return false;
    }

    @Override
    public String toString() {
        return use.id() + "/" + wealth.id() + " " + culture.id() + ":" + style.name + " v" + variant + " " + body + " " + shape + " " + roof
                + " st" + storeys + " " + foundation + (annex != Annex.NONE ? " +" + annex : "") + (porchD > 0 ? " porch" : "")
                + (stairSide != 0 ? " stairs" : "") + (balcony ? " balcony" : "");
    }
}
