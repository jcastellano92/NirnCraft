package com.nirncraft.realms.worldgen.site;

import com.nirncraft.realms.RealmBlocks;
import com.nirncraft.realms.block.RealmPortalBlock;
import com.nirncraft.realms.block.SpikeBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The great gates shared by several sites: the Oblivion gate (a black, thorned, pointed arch around a sheet of fire,
 * horns sweeping up from its shoulders) and simpler framed portals for the other realms.
 */
public final class Gates {
    private Gates() {}

    static BlockState st(java.util.function.Supplier<Block> b) {
        return b.get().defaultBlockState();
    }

    public static Draw.Pal daedric() {
        return Draw.Pal.weighted(new BlockState[]{st(RealmBlocks.DAEDRIC_BRICKS), st(RealmBlocks.DEADLANDS_ROCK),
                st(RealmBlocks.DAEDRIC_GLYPH_BRICKS)}, new int[]{14, 3, 1});
    }

    public static BlockState spike(Direction d) {
        return st(RealmBlocks.DAEDRIC_SPIKE).setValue(SpikeBlock.FACING, d);
    }

    /**
     * An Oblivion gate arch centred on (cx, base, cz): the opening is {@code 2*hw+1} wide and {@code h} tall, its plane
     * along x ({@code axisX}) or z. {@code ret}: a Deadlands-side gate leading home.
     */
    public static void oblivionArch(Draw d, int cx, int base, int cz, boolean axisX, int hw, int h, boolean ret) {
        Draw.Pal pal = daedric();
        BlockState portal = st(RealmBlocks.OBLIVION_PORTAL).setValue(RealmPortalBlock.RETURN, ret)
                .setValue(RealmPortalBlock.AXIS_X, axisX);
        // the fire sheet: a rectangle with a pointed top
        for (int u = -hw; u <= hw; u++) {
            int top = base + h - (int) Math.round(Math.abs(u) * 0.6);
            for (int y = base + 1; y <= top; y++) d.set(ax(cx, u, axisX), y, az(cz, u, axisX), portal);
        }
        // threshold
        for (int u = -hw - 1; u <= hw + 1; u++) {
            d.set(ax(cx, u, axisX), base, az(cz, u, axisX), st(RealmBlocks.DAEDRIC_GLYPH_BRICKS));
        }
        for (int s = -1; s <= 1; s += 2) {
            double lx = s * (hw + 2.2);
            // legs: thick at the foot, leaning in
            line(d, cx, cz, axisX, lx * 1.12, base - 2, lx, base + h * 0.7, 1.9, pal);
            // shoulders curving to the pointed apex
            curve(d, cx, cz, axisX, lx, base + h * 0.7, lx * 0.95, base + h + 2.5, 0, base + h + 4.5, 1.7, 1.1, pal);
            // horns sweeping up and out from the shoulders
            curve(d, cx, cz, axisX, lx * 0.95, base + h * 0.85, lx * 1.9, base + h + 3, lx * 1.55, base + h + 10, 1.2, 0.5, pal);
            // a lower barb on each leg
            curve(d, cx, cz, axisX, lx * 1.05, base + h * 0.35, lx * 1.7, base + h * 0.4, lx * 1.9, base + h * 0.7, 0.9, 0.4, pal);
            // spikes along the outer edge of the legs
            for (int k = 0; k < 4; k++) {
                int y = base + 2 + k * Math.max(2, h / 4);
                int u = (int) Math.round(lx * 1.1 + s * 1.6);
                Direction out = axisX ? (s > 0 ? Direction.EAST : Direction.WEST) : (s > 0 ? Direction.SOUTH : Direction.NORTH);
                d.set(ax(cx, u, axisX), y, az(cz, u, axisX), spike(out));
            }
            // glowing runes set into the legs' faces
            for (int y = base + 1; y < base + h * 0.7; y += 3) {
                int u = (int) Math.round(lx);
                d.set(ax(cx, u, axisX), y, az(cz, u, axisX) + (axisX ? 1 : 0), st(RealmBlocks.DAEDRIC_GLYPH_BRICKS));
            }
        }
        // the apex spike
        d.line(cx, base + h + 4.5, cz, cx, base + h + 9, cz, 0.7, pal);
        d.set(cx, base + h + 10, cz, spike(Direction.UP));
    }

    private static int ax(int cx, int u, boolean axisX) {
        return axisX ? cx + u : cx;
    }

    private static int az(int cz, int u, boolean axisX) {
        return axisX ? cz : cz + u;
    }

    private static void line(Draw d, int cx, int cz, boolean axisX, double u0, double y0, double u1, double y1, double r, Draw.Pal pal) {
        if (axisX) d.line(cx + u0 + 0.5, y0, cz + 0.5, cx + u1 + 0.5, y1, cz + 0.5, r, pal);
        else d.line(cx + 0.5, y0, cz + u0 + 0.5, cx + 0.5, y1, cz + u1 + 0.5, r, pal);
    }

    private static void curve(Draw d, int cx, int cz, boolean axisX, double u0, double y0, double uc, double yc, double u1,
                              double y1, double r0, double r1, Draw.Pal pal) {
        if (axisX) {
            d.curve(cx + u0 + 0.5, y0, cz + 0.5, cx + uc + 0.5, yc, cz + 0.5, cx + u1 + 0.5, y1, cz + 0.5, r0, r1, pal);
        } else {
            d.curve(cx + 0.5, y0, cz + u0 + 0.5, cx + 0.5, yc, cz + uc + 0.5, cx + 0.5, y1, cz + u1 + 0.5, r0, r1, pal);
        }
    }

    /**
     * A framed portal for the other realms: two pillars and a lintel of {@code frame} around a sheet of
     * {@code portal}, {@code 2*hw+1} wide and {@code h} tall, with a stepped plinth.
     */
    public static void framedPortal(Draw d, int cx, int base, int cz, boolean axisX, int hw, int h, BlockState portal,
                                    Draw.Pal frame, Draw.Pal plinth) {
        for (int u = -hw - 2; u <= hw + 2; u++)
            for (int v = -2; v <= 2; v++) {
                int x = axisX ? cx + u : cx + v, z = axisX ? cz + v : cz + u;
                d.set(x, base, z, plinth.at(d.p, x, base, z));
                if (Math.abs(u) <= hw + 1 && Math.abs(v) <= 1) d.set(x, base + 1, z, plinth.at(d.p, x, base + 1, z));
            }
        for (int u = -hw - 1; u <= hw + 1; u++)
            for (int y = base + 2; y <= base + h + 2; y++) {
                int x = axisX ? cx + u : cx, z = axisX ? cz : cz + u;
                boolean edge = Math.abs(u) == hw + 1 || y == base + h + 2;
                d.set(x, y, z, edge ? frame.at(d.p, x, y, z) : portal);
            }
        // capstone
        for (int u = -hw - 2; u <= hw + 2; u++) {
            int x = axisX ? cx + u : cx, z = axisX ? cz : cz + u;
            d.set(x, base + h + 3, z, frame.at(d.p, x, base + h + 3, z));
        }
        d.set(axisX ? cx : cx, base + h + 4, axisX ? cz : cz, frame.at(d.p, cx, base + h + 4, cz));
    }

    public static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }
}
