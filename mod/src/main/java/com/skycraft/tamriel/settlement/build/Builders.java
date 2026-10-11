package com.skycraft.tamriel.settlement.build;

import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.SettlementPiece;
import com.skycraft.tamriel.settlement.build.Shells.Frame;
import com.skycraft.tamriel.settlement.build.Shells.Room;
import com.skycraft.tamriel.settlement.kit.Kits;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.kit.Shell;
import com.skycraft.tamriel.settlement.plan.Archetype;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/** Paints (or records) one settlement piece: dispatches on its archetype and the culture kit. */
public final class Builders {
    private Builders() {}

    public static void build(Canvas c, SettlementPiece piece) {
        Spec s = piece.spec;
        Culture culture = Culture.byOrdinal(s.culture);
        Palette p = Kits.palette(culture, s.style);
        switch (s.kind) {
            case STREET -> Works.street(c, p, s);
            case WALL -> Works.wall(c, p, s);
            case BRIDGE -> Works.bridge(c, p, s);
            case CANAL -> Works.canal(c, p, s);
            case PLAZA -> Works.plaza(c, p, s);
            case WELL -> {
                if (!c.recording()) c.clearPlants(-1, 1, -1, 3, 4, 3);
                Works.well(c, p, 0, 0);
            }
            case LAMP -> Works.lamp(c, p, 0, 0, 0);
            case STALL -> Works.stall(c, p, s);
            case FIELD -> Works.field(c, p, s);
            case GARDEN -> Works.garden(c, p, s);
            case GATE -> Works.gate(c, p, s);
            case DOCK -> Works.dock(c, p, s);
            case MINE -> Works.mine(c, p, s);
            case GRAVEYARD -> Works.graveyard(c, p, s);
            case TERRACE -> Works.terrace(c, p, s);
            case LUMBER -> Works.lumber(c, p, s);
            case STABLES -> Works.stables(c, p, s);
            case HOMESTEAD_PLOT -> Works.homesteadPlot(c, p, s);
            case LANDMARK -> Landmarks.build(c, p, s, piece.settlement);
            case SHRINE -> shrine(c, p, s);
            case GUILD_HALL -> GuildHalls.build(c, p, s, culture);
            case JAIL -> Jails.build(c, p, s, culture, piece.settlement);
            default -> building(c, p, s, culture);
        }
    }

    // ------------------------------------------------------------------ buildings

    static Shell shellFor(Palette p, Archetype kind) {
        return switch (kind) {
            case TOWER -> p.towerShell == Shell.TOWER || p.towerShell == Shell.MUSHROOM || p.towerShell == Shell.PAGODA ? p.towerShell : Shell.TOWER;
            case TENT -> Shell.TENT;
            case LONGHOUSE, PALACE, INN, TEMPLE, BARRACKS, MANOR, HALL_OF_DEAD -> p.hallShell;
            case SMITHY -> rectangular(p.shell) ? p.shell : Shell.GABLE;
            default -> p.shell;
        };
    }

    static boolean rectangular(Shell s) {
        return s == Shell.GABLE || s == Shell.HIP || s == Shell.FLAT || s == Shell.DOME;
    }

    /** Walls per storey of a building archetype. */
    static int wallHeight(Archetype kind) {
        return switch (kind) {
            case PALACE -> 7;
            case TEMPLE, HALL_OF_DEAD -> 6;
            case INN, LONGHOUSE, MANOR, MILL -> 5;
            default -> 4;
        };
    }

    /** Height a building needs above its floor: storeys, the steepest roof over its span, chimney and finials. */
    public static int height(Spec s) {
        if (!s.kind.building) return s.h;
        int storeys = s.kind == Archetype.TOWER ? Math.max(2, (s.h - 4) / 5) : s.has(Spec.UPPER) ? 2 : 1;
        int walls = s.kind == Archetype.TOWER ? storeys * 5 : storeys * (wallHeight(s.kind) + 1);
        int roof = (Math.max(s.w, s.d) / 2 + 2) * 2;
        return Math.max(s.h, walls + roof + 4);
    }

    static void building(Canvas c, Palette p, Spec s, Culture culture) {
        Archetype kind = s.kind;
        Frame f = new Frame();
        f.w = s.w;
        f.d = s.d;
        f.shell = shellFor(p, kind);
        f.storeys = kind == Archetype.TOWER ? Math.max(2, (s.h - 4) / 5) : s.has(Spec.UPPER) ? 2 : 1;
        f.wallH = wallHeight(kind);
        f.stilts = s.has(Spec.WATER) || p.stilts && (kind == Archetype.HOUSE || kind == Archetype.FARMHOUSE || kind == Archetype.STORE
                || kind == Archetype.INN || kind == Archetype.ALCHEMIST || kind == Archetype.MANOR);
        f.thatch = p.thatch && !s.has(Spec.RICH) && (kind == Archetype.HOUSE || kind == Archetype.FARMHOUSE || kind == Archetype.MILL)
                && c.chance(0, 0, 0, 77, 2);
        f.horns = p.horns && (kind == Archetype.LONGHOUSE || kind == Archetype.PALACE || kind == Archetype.INN || s.has(Spec.SEAT));
        f.openLeft = kind == Archetype.SMITHY ? 4 : 0;
        f.ruined = s.has(Spec.RUINED);
        f.chimney = rectangular(f.shell) && f.shell != Shell.FLAT && f.shell != Shell.DOME
                && kind != Archetype.TEMPLE && kind != Archetype.PALACE && kind != Archetype.LONGHOUSE && kind != Archetype.TOWER;
        List<Room> rooms = Shells.build(c, p, f);
        if (rooms.isEmpty() || f.ruined) return;
        int doorX = rectangular(f.shell) ? (f.openLeft + s.w - 1) / 2 : (s.w - 1) / 2;
        Room r0 = rooms.get(0);
        Room r1 = rooms.size() > 1 ? rooms.get(1) : null;
        boolean firePit = culture == Culture.NORD || culture == Culture.ORC || culture == Culture.ATMORAN;
        boolean rich = s.has(Spec.RICH);
        switch (kind) {
            case HOUSE, FARMHOUSE, MANOR -> {
                boolean sale = s.has(Spec.FOR_SALE);
                AnchorType bed = sale ? AnchorType.HOUSE_BED : AnchorType.BED;
                String loot = rich || kind == Archetype.MANOR ? "house_rich" : kind == Archetype.FARMHOUSE ? "farm" : "house";
                Interiors.house(c, p, r0, doorX, r1 != null ? 1 : 2, rich, bed, f.chimney, loot);
                if (r1 != null) Interiors.house(c, p, r1, -9, 2, rich, sale ? AnchorType.HOUSE_BED : AnchorType.BED, false, loot);
                if (kind == Archetype.FARMHOUSE) c.anchor(AnchorType.FARMER, doorX, 1, -1);
                if (sale) {
                    c.anchor(AnchorType.HOUSE_DOOR, doorX, 1, 0);
                    c.anchor(AnchorType.HOUSE_MIN, -1, 0, -1);
                    c.anchor(AnchorType.HOUSE_MAX, s.w, s.h, s.d);
                }
            }
            case INN -> Interiors.inn(c, p, r0, r1, doorX, firePit);
            case SMITHY -> {
                Interiors.forgeYard(c, p, f.openLeft, s.d);
                Interiors.smithyRoom(c, p, r0, doorX);
            }
            case STORE -> Interiors.shop(c, p, r0, doorX, AnchorType.MERCHANT, "store", false);
            case ALCHEMIST -> Interiors.shop(c, p, r0, doorX, AnchorType.ALCHEMIST, "alchemist", true);
            case TEMPLE -> Interiors.temple(c, p, r0, doorX, Props.DIVINES[Math.floorMod(s.variant, Props.DIVINES.length)], false);
            case HALL_OF_DEAD -> Interiors.temple(c, p, r0, doorX, "arkay", true);
            case PALACE -> Interiors.palace(c, p, r0, r1, doorX, true, firePit);
            case LONGHOUSE -> Interiors.longhouse(c, p, r0, doorX, s.has(Spec.SEAT));
            case BARRACKS -> Interiors.barracks(c, p, r0, doorX);
            case MILL -> {
                Interiors.mill(c, p, r0, doorX);
                waterWheel(c, p, s.w, s.d);
            }
            case TOWER -> {
                Interiors.guardPost(c, p, r0);
                Room top = rooms.get(rooms.size() - 1);
                c.anchor(AnchorType.GUARD, top.cx(), top.ceil() + 1, top.cz());
            }
            case TENT -> Interiors.tent(c, p, r0);
            default -> {
            }
        }
        if (s.has(Spec.SIGN) || kind == Archetype.INN || kind == Archetype.SMITHY || kind == Archetype.STORE || kind == Archetype.ALCHEMIST) {
            Component title = s.name.isEmpty() || s.has(Spec.FOR_SALE)
                    ? Component.translatable("settlement.skycraft.kind." + kind.name().toLowerCase(java.util.Locale.ROOT))
                    : Component.translatable("settlement.skycraft.name." + s.name);
            int sx = Math.min(s.w - 1, doorX + 1);
            if (f.stilts) c.set(sx, 3, -1, B.s(Blocks.AIR));
            c.sign(sx, 3, -1, p.sign, Direction.NORTH, Component.empty(), title);
        }
    }

    /** A water wheel on the right side wall (local x = w), turning in a mill race. */
    static void waterWheel(Canvas c, Palette p, int w, int d) {
        int cz = (d - 1) / 2;
        int wx = w + 1;
        int cy = 3;
        for (int x = w - 1; x <= w + 1; x++) c.set(x, cy, cz, B.axis(p.beam, c.dir(Direction.EAST).getAxis()));
        for (int dy = -3; dy <= 3; dy++) {
            for (int dz = -3; dz <= 3; dz++) {
                double r = Math.sqrt(dy * dy + dz * dz);
                if (r > 3.4) continue;
                boolean rim = r > 2.5;
                boolean spoke = dy == 0 || dz == 0;
                if (rim) c.set(wx, cy + dy, cz + dz, p.planks);
                else if (spoke) c.set(wx, cy + dy, cz + dz, B.axis(p.beam, Direction.Axis.Y));
            }
        }
        for (int z = cz - 4; z <= cz + 4; z++) {
            for (int x = wx - 1; x <= wx + 1; x++) {
                c.set(x, -1, z, p.foundation);
                c.set(x, 0, z, B.s(Blocks.WATER));
            }
            c.set(wx - 2 < w ? w : wx - 2, 0, z, p.foundation);
            c.set(wx + 2, 0, z, p.foundation);
        }
    }

    /** A small wayside shrine: a roofed niche with a Divine's shrine and candles. */
    static void shrine(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        if (!c.recording()) c.clearPlants(-1, 1, -1, w, 6, d);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            c.set(x, 0, z, p.plaza);
            c.foundation(x, -1, z, p.foundation, 6);
        }
        int cx = (w - 1) / 2;
        for (int y = 1; y <= 3; y++) {
            c.set(0, y, d - 1, p.column);
            c.set(w - 1, y, d - 1, p.column);
            c.set(0, y, 0, p.column);
            c.set(w - 1, y, 0, p.column);
        }
        for (int x = 0; x < w; x++) c.set(x, 1, d - 1, p.wallLow);
        c.set(cx, 1, d - 2, Props.shrine(Props.DIVINES[Math.floorMod(s.variant, Props.DIVINES.length)]));
        Props.candles(c, cx - 1, 1, d - 2, 3);
        Props.candles(c, cx + 1, 1, d - 2, 2);
        Shells.hipRoof(c, p, -1, -1, w, d, 4);
    }
}
