package com.skycraft.tamriel.settlement.build;

import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.build.Shells.Frame;
import com.skycraft.tamriel.settlement.build.Shells.Room;
import com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.kit.Shell;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * A faction's guild hall in the culture's own building style ({@link Spec#name} = faction id): the hall's head at a
 * desk or dais under the faction's colours at the back, a practice area with the trainers on one side (training
 * dummies and targets for warriors, an enchanter and bookshelves for mages, an altar for the orders of the Nine...),
 * the guild vendor's counter and stock on the other, and the members' bunks upstairs. Records the
 * {@code GUILD_*} anchors the population fills with the faction's staff.
 */
public final class GuildHalls {
    private GuildHalls() {}

    /** What the hall is for, which decides the practice area. */
    enum Theme { MARTIAL, ARCANE, SHADOW, SACRED, TRADE, CAMP }

    static Theme theme(String faction) {
        return switch (faction) {
            case "mages_guild", "house_telvanni", "psijic_order", "thalmor", "aldmeri_dominion" -> Theme.ARCANE;
            case "thieves_guild", "dark_brotherhood", "morag_tong", "camonna_tong", "shadowscales" -> Theme.SHADOW;
            case "tribunal_temple", "knights_of_the_nine", "imperial_cult", "moon_priests", "vigilants_of_stendarr",
                 "green_pact" -> Theme.SACRED;
            case "house_hlaalu", "east_empire_company", "forebears" -> Theme.TRADE;
            case "ashlanders", "renrijra_krin" -> Theme.CAMP;
            default -> Theme.MARTIAL;
        };
    }

    /** The faction's colours as a wool block. */
    static BlockState colours(String faction) {
        return B.s(switch (faction) {
            case "fighters_guild", "renrijra_krin", "tribunal_temple" -> Blocks.ORANGE_WOOL;
            case "mages_guild", "east_empire_company" -> Blocks.BLUE_WOOL;
            case "thieves_guild", "blades" -> Blocks.GRAY_WOOL;
            case "dark_brotherhood", "camonna_tong" -> Blocks.BLACK_WOOL;
            case "imperial_legion", "arena", "house_redoran", "morag_tong", "knights_of_the_dragon" -> Blocks.RED_WOOL;
            case "knights_of_the_nine", "imperial_cult", "vigilants_of_stendarr" -> Blocks.WHITE_WOOL;
            case "house_hlaalu", "aldmeri_dominion", "crowns" -> Blocks.YELLOW_WOOL;
            case "house_telvanni", "moon_priests" -> Blocks.PURPLE_WOOL;
            case "psijic_order", "forebears" -> Blocks.CYAN_WOOL;
            case "thalmor", "shadowscales", "orc_strongholds" -> Blocks.GREEN_WOOL;
            case "green_pact" -> Blocks.LIME_WOOL;
            case "knights_of_the_rose" -> Blocks.PINK_WOOL;
            case "ashlanders", "undaunted" -> Blocks.BROWN_WOOL;
            default -> Blocks.LIGHT_GRAY_WOOL;
        });
    }

    public static void build(Canvas c, Palette p, Spec s, Culture culture) {
        String faction = s.name;
        Frame f = new Frame();
        f.w = s.w;
        f.d = s.d;
        f.shell = rectangular(p.hallShell) ? p.hallShell : rectangular(p.shell) ? p.shell : p.hallShell;
        f.storeys = s.has(Spec.UPPER) ? 2 : 1;
        f.wallH = 5;
        f.horns = p.horns;
        f.chimney = false;
        f.stilts = p.stilts;
        List<Room> rooms = Shells.build(c, p, f);
        if (rooms.isEmpty()) return;
        Room hall = rooms.get(0);
        Room upper = rooms.size() > 1 ? rooms.get(rooms.size() - 1) : null;
        Theme theme = theme(faction);
        BlockState colours = colours(faction);
        int doorX = (s.w - 1) / 2;

        ground(c, p, hall, theme, colours, doorX);
        if (upper != null && upper != hall) bunks(c, p, upper, colours);
        else c.anchor(AnchorType.GUILD_BED, hall.x0(), hall.y(), hall.z0() + 1);

        // the faction's colours flanking the door and its name over it
        c.set(doorX - 2, 3, -1, colours);
        c.set(doorX + 2, 3, -1, colours);
        if (!c.recording()) {
            int sx = Math.min(s.w - 1, doorX + 1);
            if (f.stilts) c.set(sx, 3, -1, B.s(Blocks.AIR));
            Component title = Component.translatableWithFallback("settlement.skycraft.name." + faction, faction);
            Props.shopSign(c, p, "guild_hall", sx, 3, -1, Direction.EAST, title, Component.literal("Guild Hall"));
        }
    }

    static boolean rectangular(Shell s) {
        return s == Shell.GABLE || s == Shell.HIP || s == Shell.FLAT || s == Shell.DOME;
    }

    /** The ground floor: desk and colours at the back, practice area left, vendor counter right. */
    static void ground(Canvas c, Palette p, Room r, Theme theme, BlockState colours, int doorX) {
        int y = r.y();
        int cx = r.cx();
        boolean roomy = r.w() >= 7 && r.d() >= 7;
        // the aisle and the head's desk
        Props.rug(c, p.carpet, cx, y, r.z0(), cx, r.z1() - 3);
        int deskZ = r.z1() - 2;
        Props.table(c, p, cx - 1, y, deskZ);
        Props.desk(c, p, cx, y, deskZ, Direction.SOUTH);
        Props.table(c, p, cx + 1, y, deskZ);
        Props.clutter(c, p, cx - 1, y + 1, deskZ, Direction.NORTH, FurnitureKind.CLUTTER_CANDELABRA);
        Props.clutter(c, p, cx + 1, y + 1, deskZ, Direction.NORTH,
                theme == Theme.ARCANE || theme == Theme.SACRED ? FurnitureKind.CLUTTER_LEDGER : FurnitureKind.CLUTTER_GOBLETS);
        c.keepClear(cx, y, y + 1, r.z1() - 1);
        c.anchor(AnchorType.GUILD_LEADER, cx, y, r.z1() - 1);
        // the colours on the back wall
        int back = r.z1() + 1;
        for (int dx = -2; dx <= 2; dx += 2) {
            c.set(cx + dx, y + 1, back, colours);
            c.set(cx + dx, y + 2, back, colours);
            c.set(cx + dx, y + 3, back, colours);
        }
        Props.lantern(c, p, cx, r.ceil() - 1, r.cz(), true);
        if (!roomy) {
            c.anchor(AnchorType.GUILD_TRAINER, r.x0(), y, r.cz());
            c.anchor(AnchorType.GUILD_VENDOR, r.x1(), y, r.cz());
            return;
        }
        practice(c, p, r, theme);
        counter(c, p, r, theme);
        Props.lantern(c, p, r.x0() + 1, r.ceil() - 1, r.z0() + 2, true);
        Props.lantern(c, p, r.x1() - 1, r.ceil() - 1, r.z0() + 2, true);
    }

    /** The practice area along the left wall with two trainers. */
    static void practice(Canvas c, Palette p, Room r, Theme theme) {
        int y = r.y();
        int x = r.x0();
        int z0 = r.z0() + 1, z1 = r.z1() - 4;
        for (int z = z0; z <= z1; z++) {
            boolean even = (z - z0) % 2 == 0;
            switch (theme) {
                case MARTIAL -> {
                    // straw dummies, targets and a weapon rack
                    if (z == z0) {
                        Props.weaponRack(c, p, x, y, z, Direction.EAST);
                    } else if (even) {
                        c.set(x, y, z, B.s(Blocks.HAY_BLOCK));
                        c.set(x, y + 1, z, B.s(Blocks.CARVED_PUMPKIN));
                    } else {
                        c.set(x, y + 1, z, B.s(Blocks.TARGET));
                        c.set(x, y, z, p.fence);
                    }
                }
                case ARCANE -> {
                    Props.bookshelf(c, x, y, z, Direction.EAST, !even);
                    Props.bookshelf(c, x, y + 1, z, Direction.EAST, true);
                }
                case SHADOW -> {
                    if (even) Props.cabinet(c, p, x, y, z, Direction.EAST, "store");
                    else c.set(x, y, z, B.s(Blocks.COBWEB));
                }
                case SACRED -> {
                    if (even) Props.clutter(c, p, x, y, z, Direction.EAST, FurnitureKind.CLUTTER_CANDELABRA);
                    else Props.bench(c, p, x, y, z, Direction.WEST);
                }
                case TRADE -> {
                    if (even) Props.cabinet(c, p, x, y, z, Direction.EAST, "store");
                    else c.set(x, y, z, B.s(Blocks.CRAFTING_TABLE));
                }
                case CAMP -> c.setIfOpen(x, y, z, p.carpet2);
            }
        }
        // the trainers' stations
        int zt = r.z0() + 2;
        BlockState station = switch (theme) {
            case MARTIAL -> B.s(Blocks.GRINDSTONE);
            case ARCANE -> Props.enchanter();
            case SHADOW -> B.s(Blocks.FLETCHING_TABLE);
            case SACRED -> Props.shrine(Props.DIVINES[Math.floorMod(c.owner.hashCode(), Props.DIVINES.length)]);
            case TRADE -> B.s(Blocks.CARTOGRAPHY_TABLE);
            case CAMP -> Props.tanningRack();
        };
        c.set(x + 2, y, zt, station);
        if (theme == Theme.ARCANE) c.set(x + 2, y, Math.min(z1, zt + 3), Props.alchemyLab());
        c.anchor(AnchorType.GUILD_TRAINER, x + 1, y, zt + 1);
        c.anchor(AnchorType.GUILD_TRAINER, x + 1, y, Math.min(z1, zt + 4));
    }

    /** The vendor's counter and stock along the right wall. */
    static void counter(Canvas c, Palette p, Room r, Theme theme) {
        int y = r.y();
        int cxz = r.x1() - 2;
        int z0 = r.z0() + 1, z1 = Math.max(z0 + 2, r.z1() - 5);
        for (int z = z0; z <= z1; z++) {
            if (z == z0) c.set(cxz, y, z, B.slab(p.slab, true));
            else Props.counter(c, p, cxz, y, z, Direction.WEST);
        }
        int vz = (z0 + z1) / 2;
        c.anchor(AnchorType.GUILD_VENDOR, r.x1() - 1, y, vz);
        String loot = switch (theme) {
            case ARCANE, SACRED -> "alchemist";
            case MARTIAL -> "smithy";
            default -> "store";
        };
        Props.chest(c, r.x1(), y, z0, Direction.WEST, loot);
        Props.cabinet(c, p, r.x1(), y, z1, Direction.WEST, loot);
        if (theme == Theme.MARTIAL) c.set(r.x1(), y, vz, Props.workbench());
        else if (theme == Theme.ARCANE || theme == Theme.SACRED) c.set(r.x1(), y, vz, B.s(Blocks.BREWING_STAND));
        Props.clutter(c, p, cxz, y + 1, vz, Direction.WEST, FurnitureKind.CLUTTER_SCALES);
    }

    /** The members' bunks upstairs. */
    static void bunks(Canvas c, Palette p, Room r, BlockState colours) {
        int y = r.y();
        for (int z = r.z0() + 1; z <= r.z1() - 1; z += 3) {
            c.bed(r.x1(), y, z, p.bed, Direction.WEST);
            c.anchor(AnchorType.GUILD_BED, r.x1(), y, z);
            Props.chest(c, r.x1(), y, z + 1, Direction.WEST, "barracks");
        }
        Props.wardrobe(c, p, r.x0(), y, r.z0() + 1, Direction.EAST, "barracks");
        Props.table(c, p, r.cx(), y, r.cz());
        Props.clutter(c, p, r.cx(), y + 1, r.cz(), Direction.SOUTH, FurnitureKind.CLUTTER_TANKARD);
        Props.chair(c, p, r.cx() - 1, y, r.cz(), Direction.WEST);
        Props.rug(c, colours.is(Blocks.WHITE_WOOL) ? p.carpet : p.carpet2, r.cx() - 1, y, r.z0() + 1, r.cx() + 1, r.z1() - 1);
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz() + 2, true);
    }
}
