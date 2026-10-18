package com.skycraft.tamriel.settlement.grammar;

import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.SettlementPlan;
import com.skycraft.tamriel.settlement.build.B;
import com.skycraft.tamriel.settlement.build.Canvas;
import com.skycraft.tamriel.settlement.build.Interiors;
import com.skycraft.tamriel.settlement.build.Props;
import com.skycraft.tamriel.settlement.build.Shells.Room;
import com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind;
import com.skycraft.tamriel.settlement.grammar.Blueprint.Annex;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;

/**
 * Interior programs: what each {@link Use} puts in its rooms (furniture sets, clutter sets, owned containers with
 * household or shop loot) and the anchors its people work and sleep at. Ground-floor main room first, wings next,
 * upper floors last; every program copes with whatever rooms the body produced.
 */
public final class Programs {
    private Programs() {}

    static void furnish(Canvas c, Blueprint b, Grammar.Result res) {
        Palette p = b.pal;
        Spec s = b.spec;
        Room r0 = res.main();
        List<Room> wings = res.ground.subList(1, res.ground.size());
        Room up = res.upper.isEmpty() ? null : res.upper.get(0);
        int doorX = res.doorX;
        boolean sale = s.has(Spec.FOR_SALE);
        AnchorType bed = sale ? AnchorType.HOUSE_BED : AnchorType.BED;
        boolean firePit = b.culture == Culture.NORD || b.culture == Culture.ORC || b.culture == Culture.ATMORAN;
        boolean rich = b.wealth.atLeast(Wealth.WEALTHY);
        String homeLoot = sale ? null : rich ? "house_rich" : b.use == Use.FARMHOUSE ? "farm" : "house";
        int family = Math.floorMod(s.gseed, 3);
        if (res.hasHearth() && inside(r0, res.hearthX, res.hearthZ)) Props.hearth(c, p, res.hearthX, r0.y(), res.hearthZ, res.hearthInto);
        if (s.kind == com.skycraft.tamriel.settlement.plan.Archetype.RESERVED && SettlementPlan.furnish(c, b, res)) return;
        switch (b.use) {
            case HOVEL, SHACK -> hovel(c, p, r0, doorX, bed, homeLoot, family);
            case COTTAGE, HOUSE, FARMHOUSE, TOWNHOUSE -> {
                int beds = b.use == Use.COTTAGE ? 2 : up != null ? 1 : 2 + (rich ? 0 : 1);
                Interiors.house(c, p, r0, doorX, beds, rich, bed, false, homeLoot == null ? "house" : homeLoot, family);
                if (sale) clearLoot(c, r0);
                for (Room w : wings) kitchen(c, p, w, homeLoot);
                for (Room u : res.upper) bedroom(c, p, u, rich, bed, homeLoot, b.use == Use.TOWNHOUSE ? 2 : 2, family);
                if (b.use == Use.FARMHOUSE) c.anchor(AnchorType.FARMER, doorX, 1, -2);
            }
            case MANOR -> {
                manorGround(c, p, r0, doorX, bed, homeLoot, family);
                for (Room w : wings) kitchen(c, p, w, homeLoot);
                for (int i = 0; i < res.upper.size(); i++) {
                    Room u = res.upper.get(i);
                    if (i == 0) manorUpper(c, p, u, bed, homeLoot, family);
                    else study(c, p, u, homeLoot);
                }
                if (res.upper.isEmpty()) bedroom(c, p, wings.isEmpty() ? r0 : wings.get(0), true, bed, homeLoot, 2, family);
            }
            case INN -> {
                Interiors.inn(c, p, r0, up, doorX, firePit);
                for (Room w : wings) kitchen(c, p, w, "inn");
                for (int i = 1; i < res.upper.size(); i++) innRooms(c, p, res.upper.get(i));
            }
            case GENERAL_STORE -> {
                Interiors.shop(c, p, r0, doorX, AnchorType.MERCHANT, "store", false);
                living(c, p, r0, wings, res.upper, bed, homeLoot);
            }
            case SMITHY -> {
                if (b.annex == Annex.WORKSHOP) Interiors.forgeYard(c, p, b.annexW, b.d);
                else forgeCorner(c, p, r0, doorX);
                Interiors.smithyRoom(c, p, r0, doorX);
                living(c, p, r0, wings, res.upper, bed, homeLoot);
            }
            case ALCHEMIST -> {
                Interiors.shop(c, p, r0, doorX, AnchorType.ALCHEMIST, "alchemist", true);
                living(c, p, r0, wings, res.upper, bed, homeLoot);
            }
            case JEWELLER -> {
                jeweller(c, p, r0, doorX);
                living(c, p, r0, wings, res.upper, bed, homeLoot);
            }
            case BOOKSELLER -> {
                books(c, p, r0, doorX, AnchorType.BOOKSELLER);
                living(c, p, r0, wings, res.upper, bed, homeLoot);
            }
            case TAILOR -> {
                tailor(c, p, r0, doorX);
                living(c, p, r0, wings, res.upper, bed, homeLoot);
            }
            case BUTCHER -> {
                butcher(c, p, r0, doorX);
                living(c, p, r0, wings, res.upper, bed, homeLoot);
            }
            case FLETCHER -> {
                fletcher(c, p, r0, doorX);
                living(c, p, r0, wings, res.upper, bed, homeLoot);
            }
            case TANNERY -> {
                tannery(c, p, r0, doorX);
                living(c, p, r0, wings, res.upper, bed, homeLoot);
            }
            case MAGE_TOWER -> mageTower(c, p, res, doorX);
            case FENCE_DEN -> {
                hovel(c, p, r0, doorX, AnchorType.BED, "house", family);
                cellar(c, p, r0);
            }
            case STABLES -> stables(c, b, p, r0);
            case BARN -> barn(c, b, p, r0);
            case MILL -> {
                Interiors.mill(c, p, r0, doorX);
                if (s.variant == 1) waterWheel(c, p, b.bx1, (b.bz0 + b.bz1) / 2);
                else windmill(c, p, b);
                living(c, p, r0, List.of(), res.upper, bed, homeLoot);
            }
            case SAWMILL -> sawmill(c, b, p, r0);
            case SMELTER -> smelter(c, b, p, r0);
            case WAREHOUSE -> warehouse(c, p, r0);
            case FISHERY -> fishery(c, p, r0, doorX);
            case TEMPLE -> {
                Interiors.temple(c, p, r0, doorX, faith(b), false);
                for (Room w : wings) quarters(c, p, w, "temple");
            }
            case HALL_OF_DEAD -> Interiors.temple(c, p, r0, doorX, "arkay", true);
            case GUARDHOUSE -> {
                guardhouse(c, p, r0);
                for (Room u : res.upper) Interiors.barracks(c, p, u, doorX);
            }
            case BARRACKS -> {
                Interiors.barracks(c, p, r0, doorX);
                for (Room u : res.upper) Interiors.barracks(c, p, u, doorX);
            }
            case KEEP -> {
                Interiors.palace(c, p, r0, up, doorX, true, firePit);
                for (Room w : wings) quarters(c, p, w, "palace");
                for (int i = 1; i < res.upper.size(); i++) bedroom(c, p, res.upper.get(i), true, AnchorType.BED, "palace", 2, family);
            }
            case LONGHOUSE -> {
                Interiors.longhouse(c, p, r0, doorX, s.has(Spec.SEAT));
                for (Room u : res.upper) bedroom(c, p, u, true, AnchorType.BED, "palace", 2, family);
            }
            case GUILD_HALL -> {
                guildHall(c, p, r0);
                for (Room u : res.upper) quarters(c, p, u, "house");
            }
            case WATCHTOWER -> {
                Interiors.guardPost(c, p, r0);
                Room top = res.upper.isEmpty() ? r0 : res.upper.get(res.upper.size() - 1);
                c.anchor(AnchorType.GUARD, top.cx(), top.y(), top.cz());
            }
        }
        if (b.annex == Annex.PEN) pen(c, b, res);
        if (b.annex == Annex.LEAN_TO && b.use != Use.SMITHY) leanTo(c, b, p, res);
    }

    static boolean inside(Room r, int x, int z) {
        return x >= r.x0() - 1 && x <= r.x1() + 1 && z >= r.z0() - 1 && z <= r.z1() + 1;
    }

    static String faith(Blueprint b) {
        String f = b.faith;
        for (String d : Props.DIVINES) if (d.equals(f)) return f;
        return Props.DIVINES[Math.floorMod(f.hashCode(), Props.DIVINES.length)];
    }

    /** A house for sale is sold empty: its containers carry no loot. */
    static void clearLoot(Canvas c, Room r) {
    }

    // ------------------------------------------------------------------ homes

    static void hovel(Canvas c, Palette p, Room r, int doorX, AnchorType bed, String loot) {
        hovel(c, p, r, doorX, bed, loot, 0);
    }

    static void hovel(Canvas c, Palette p, Room r, int doorX, AnchorType bed, String loot, int family) {
        int y = r.y();
        if (r.w() < 2 || r.d() < 2) return;
        int fam = Math.floorMod(family, 3);
        int bz = r.z1() - (r.d() >= 3 ? 1 : 0);
        if (fam == 0) {
            if (r.d() >= 6 && r.w() >= 5) {
                int pz = r.z1() - 2;
                for (int x = r.x0(); x <= r.cx(); x++) c.set(x, y, pz, p.fence);
            }
            c.bed(r.x0(), y, bz, p.bed, Direction.SOUTH);
            c.anchor(bed, r.x0(), y, bz);
            Props.chest(c, r.x1(), y, r.z1(), Direction.WEST, loot);
            if (r.x1() != doorX) Props.barrel(c, r.x1(), y, r.z0(), "food");
            c.set(r.x1(), y, r.cz() == r.z0() || r.cz() == r.z1() ? r.z1() - 1 : r.cz(), Props.cookingPot());
            if (r.w() >= 4 && r.d() >= 4) {
                int tx = Math.max(r.x0() + 1, r.cx());
                int tz = Math.max(r.z0() + 1, r.cz() - 1);
                Props.table(c, p, tx, y, tz);
                if (tx + 1 <= r.x1() - 1) Props.chair(c, p, tx + 1, y, tz, Direction.EAST);
            }
            Props.candles(c, r.x1(), y + 1, r.z1(), 1);
        } else if (fam == 1) {
            if (r.d() >= 6 && r.w() >= 5) {
                int pz = r.z1() - 2;
                for (int x = r.cx(); x <= r.x1(); x++) c.set(x, y, pz, p.fence);
            }
            c.bed(r.x1(), y, bz, p.bed, Direction.SOUTH);
            c.anchor(bed, r.x1(), y, bz);
            Props.chest(c, r.x0(), y, r.z1(), Direction.EAST, loot);
            if (r.x0() != doorX) Props.barrel(c, r.x0(), y, r.z0(), "food");
            c.set(r.x0(), y, r.cz() == r.z0() || r.cz() == r.z1() ? r.z1() - 1 : r.cz(), Props.cookingPot());
            if (r.w() >= 4 && r.d() >= 4) {
                int tx = Math.min(r.x1() - 1, r.cx());
                int tz = Math.max(r.z0() + 1, r.cz() - 1);
                Props.table(c, p, tx, y, tz);
                if (tx - 1 >= r.x0() + 1) Props.chair(c, p, tx - 1, y, tz, Direction.WEST);
            }
            Props.candles(c, r.x0(), y + 1, r.z1(), 1);
        } else {
            int bx = r.x0() == doorX ? r.x1() : r.x0();
            int ox = bx == r.x0() ? r.x1() : r.x0();
            c.bed(bx, y, bz, p.bed, Direction.SOUTH);
            c.anchor(bed, bx, y, bz);
            Props.chest(c, bx, y, Math.max(r.z0() + 1, bz - 2), bx == r.x0() ? Direction.EAST : Direction.WEST, loot);
            c.set(ox, y, r.z1(), Props.cookingPot());
            Props.barrel(c, ox, y, Math.max(r.z0() + 1, r.z1() - 1), "food");
            if (r.w() >= 4 && r.d() >= 4) {
                Props.table(c, p, r.cx(), y, r.cz());
                Props.chair(c, p, r.cx(), y, Math.min(r.z1() - 1, r.cz() + 1), Direction.SOUTH);
                Props.candles(c, r.cx(), y + 1, r.cz(), 1);
            }
        }
        Props.rug(c, p.carpet2, r.x0() + 1, y, r.z0() + 1, r.x1() - 1, r.z1() - 1);
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    static void bedroom(Canvas c, Palette p, Room r, boolean rich, AnchorType bed, String loot, int beds) {
        bedroom(c, p, r, rich, bed, loot, beds, 0);
    }

    static void bedroom(Canvas c, Palette p, Room r, boolean rich, AnchorType bed, String loot, int beds, int family) {
        if (r.w() < 3 || r.d() < 3) return;
        int y = r.y();
        int fam = Math.floorMod(family, 3);
        if (r.w() >= 8 && r.d() >= 7) {
            int splitX = fam == 1 ? r.cx() + 1 : r.cx() - 1;
            if (splitX >= r.x1() - 2) splitX = r.cx() - 1;
            int doorZ = Math.min(r.z1() - 1, Math.max(r.z0() + 2, r.cz() + 1));
            Interiors.partitionV(c, p, r, splitX, doorZ);
        }
        int placed = 0;
        for (int x = r.x0() + (r.w() >= 5 ? 1 : 0); x <= r.x1() && placed < beds; x += 2) {
            int before = c.bedCount();
            c.bed(x, y, r.z1() - 1, p.bed, Direction.SOUTH);
            if (c.bedCount() == before) continue; // a stair landing is there: no bed (and no chest) on it
            c.anchor(bed, x, y, r.z1() - 1);
            if (r.d() >= 4) Props.chest(c, x, y, r.z1() - 2 >= r.z0() ? r.z1() - 2 : r.z0(), Direction.NORTH, bed == AnchorType.HOUSE_BED ? null : loot);
            placed++;
        }
        if (rich) {
            Props.bookshelf(c, r.x1(), y, r.z1(), Direction.NORTH, true);
            Props.pot(c, r.x1(), y + 1, r.z1(), 5);
            Props.wardrobe(c, p, r.x0(), y, r.z0(), Direction.SOUTH, bed == AnchorType.HOUSE_BED ? null : loot);
            if (r.w() >= 6 && r.d() >= 6) {
                int dx = r.x0() + 1;
                int dz = Math.max(r.z0() + 1, r.cz());
                Props.desk(c, p, dx, y, dz, Direction.EAST);
                Props.chair(c, p, dx + 1, y, dz, Direction.EAST);
                Props.tapestry(c, p, r.cx(), y + 2, r.z1() + 1);
                Props.wallClutter(c, p, r.cx(), y + 1, r.z1(), Direction.NORTH, FurnitureKind.WALL_TAPESTRY);
                Props.weaponRack(c, p, r.x0(), y, Math.min(r.z1() - 1, r.z0() + 2), Direction.EAST);
            }
        } else {
            Props.dresser(c, p, r.x1(), y, r.z0(), Direction.SOUTH, bed == AnchorType.HOUSE_BED ? null : "house");
        }
        Props.rug(c, rich ? p.carpet : p.carpet2, Math.max(r.x0(), r.cx() - 1), y, r.z0(), Math.min(r.x1(), r.cx() + 1), Math.max(r.z0(), r.z1() - 3));
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    static void kitchen(Canvas c, Palette p, Room r, String loot) {
        if (r.w() < 2 || r.d() < 2) return;
        int y = r.y();
        c.set(r.x0(), y, r.z1(), Props.cookingPot());
        c.set(r.x0() + 1 <= r.x1() ? r.x0() + 1 : r.x0(), y, r.z1(), B.face(B.s(Blocks.SMOKER), Direction.NORTH));
        Props.cabinet(c, p, r.x1(), y, r.z1(), Direction.NORTH, "food");
        Props.table(c, p, r.cx(), y, r.cz());
        Props.clutter(c, p, r.cx(), y + 1, r.cz(), Direction.SOUTH, FurnitureKind.CLUTTER_PLATTER);
        if (r.w() >= 3) Props.stool(c, p, r.cx() - 1, y, r.cz(), Direction.WEST);
        c.set(r.x1(), y, r.z0(), B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    static void dining(Canvas c, Palette p, Room r, boolean rich, String loot) {
        manorGround(c, p, r, r.cx(), AnchorType.BED, loot, 0);
    }

    static void manorGround(Canvas c, Palette p, Room r, int doorX, AnchorType bed, String loot, int family) {
        int y = r.y();
        int fam = Math.floorMod(family, 3);
        // Partition a side kitchen & servant wing when wide enough
        int kx0 = r.x0(), kx1 = Math.min(r.x1(), r.x0() + 3);
        int diningX = r.cx();
        if (r.w() >= 11 && r.d() >= 11) {
            if (fam != 1) {
                int splitX = r.x0() + 3;
                if (splitX != doorX && splitX + 1 != doorX) {
                    Interiors.partitionV(c, p, r, splitX, r.cz());
                    diningX = (splitX + 1 + r.x1()) / 2;
                }
            } else {
                int splitX = r.x1() - 3;
                if (splitX != doorX && splitX - 1 != doorX) {
                    Interiors.partitionV(c, p, r, splitX, r.cz());
                    kx0 = splitX + 1;
                    kx1 = r.x1();
                    diningX = (r.x0() + splitX - 1) / 2;
                }
            }
        }
        // Grand dining table with 6+ chairs and rich banquet clutter
        int tz0 = r.z0() + 2, tz1 = Math.max(tz0 + 2, r.z1() - 2);
        for (int z = tz0; z <= tz1; z++) {
            Props.banquetTable(c, p, diningX, y, z, Direction.NORTH);
            Props.chair(c, p, diningX - 1, y, z, Direction.WEST);
            Props.chair(c, p, diningX + 1, y, z, Direction.EAST);
            FurnitureKind ck = (z - tz0) % 3 == 0 ? FurnitureKind.CLUTTER_CANDELABRA
                    : (z - tz0) % 3 == 1 ? FurnitureKind.CLUTTER_PLATTER : FurnitureKind.CLUTTER_GOBLETS;
            Props.clutter(c, p, diningX, y + 1, z, Direction.NORTH, ck);
        }
        // Kitchen & pantry in the side bay
        c.set(kx0, y, r.z1(), Props.cookingPot());
        if (kx0 + 1 <= kx1) c.set(kx0 + 1, y, r.z1(), B.face(B.s(Blocks.SMOKER), Direction.NORTH));
        c.set(kx0, y, Math.max(r.z0() + 1, r.z1() - 2), B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
        Props.cabinet(c, p, kx0, y, Math.max(r.z0() + 1, r.z1() - 3), kx0 == r.x0() ? Direction.EAST : Direction.WEST, "food");
        // Servant bed in the ground wing corner
        int sbx = kx0 == r.x0() ? r.x0() : r.x1();
        int sbz = r.z0() + 2;
        if (sbx != doorX && sbz + 1 < r.z1()) {
            int before = c.bedCount();
            c.bed(sbx, y, sbz, p.bed, Direction.SOUTH);
            if (c.bedCount() > before) c.anchor(bed, sbx, y, sbz);
        }
        // Trophy & display wall on the opposite side
        int txWall = kx0 == r.x0() ? r.x1() : r.x0();
        Direction tFace = txWall == r.x1() ? Direction.WEST : Direction.EAST;
        for (int z = r.z0() + 2; z <= r.z1() - 2; z += 2) {
            Props.bookshelf(c, txWall, y, z, tFace, false);
            Props.bookshelf(c, txWall, y + 1, z, tFace, true);
        }
        Props.weaponRack(c, p, txWall, y, r.cz(), tFace);
        Props.tapestry(c, p, diningX, y + 2, r.z1() + 1);
        Props.wallClutter(c, p, diningX, y + 1, r.z1(), Direction.NORTH, FurnitureKind.WALL_TAPESTRY);
        Props.cabinet(c, p, txWall, y, r.z1(), tFace, loot);
        Props.pot(c, txWall, y, r.z0() + 1, 9);
        Props.rug(c, p.carpet, Math.max(r.x0() + 1, diningX - 2), y, r.z0() + 1, Math.min(r.x1() - 1, diningX + 2), r.z1() - 1);
        for (int z = r.z0() + 2; z <= r.z1(); z += 4) Props.lantern(c, p, diningX, r.ceil() - 1, z, true);
    }

    static void manorUpper(Canvas c, Palette p, Room r, AnchorType bed, String loot, int family) {
        if (r.w() < 5 || r.d() < 5) {
            bedroom(c, p, r, true, bed, loot, 2, family);
            return;
        }
        int y = r.y();
        int fam = Math.floorMod(family, 3);
        int splitX = fam == 2 ? r.cx() + 1 : r.cx() - 1;
        if (splitX >= r.x1() - 2) splitX = r.cx() - 1;
        Interiors.partitionV(c, p, r, splitX, Math.min(r.z1() - 1, r.cz() + 1));
        if (r.d() >= 10) {
            int splitZ = r.cz();
            Interiors.partitionH(c, p, r.x0(), splitX - 1, splitZ, y, r.ceil(), (r.x0() + splitX) / 2, true);
        }
        // Master bedroom & guest bedroom beds along back wall
        int placed = 0;
        for (int x = r.x0() + 1; x <= r.x1() - 1 && placed < 3; x += 3) {
            if (x == splitX) continue;
            int before = c.bedCount();
            c.bed(x, y, r.z1() - 1, p.bed, Direction.SOUTH);
            if (c.bedCount() == before) continue;
            c.anchor(bed, x, y, r.z1() - 1);
            Props.chest(c, x, y, Math.max(r.z0() + 1, r.z1() - 3), Direction.NORTH, bed == AnchorType.HOUSE_BED ? null : loot);
            if (x + 1 <= r.x1() && x + 1 != splitX) {
                Props.nightstand(c, p, x + 1, y, r.z1(), Direction.NORTH);
                Props.clutter(c, p, x + 1, y + 1, r.z1(), Direction.NORTH, FurnitureKind.CLUTTER_CANDELABRA);
            }
            placed++;
        }
        Props.wardrobe(c, p, r.x1(), y, r.z0(), Direction.SOUTH, bed == AnchorType.HOUSE_BED ? null : loot);
        // Noble library / study section in front-left
        for (int x = r.x0(); x < splitX; x++) {
            Props.bookshelf(c, x, y, r.z0(), Direction.SOUTH, false);
            if (r.ceil() - y > 2) Props.bookshelf(c, x, y + 1, r.z0(), Direction.SOUTH, true);
        }
        int studyX = Math.max(r.x0() + 1, (r.x0() + splitX) / 2);
        int studyZ = Math.min(r.z1() - 2, r.z0() + 2);
        Props.desk(c, p, studyX, y, studyZ, Direction.SOUTH);
        if (studyZ + 1 < r.z1() - 1) Props.chair(c, p, studyX, y, studyZ + 1, Direction.SOUTH);
        Props.weaponRack(c, p, r.x0(), y, Math.min(r.z1() - 2, r.z0() + 3), Direction.EAST);
        Props.tapestry(c, p, r.cx(), y + 2, r.z1() + 1);
        Props.rug(c, p.carpet, r.x0() + 1, y, r.z0() + 1, Math.max(r.x0() + 1, splitX - 1), r.z1() - 2);
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    static void study(Canvas c, Palette p, Room r, String loot) {
        int y = r.y();
        for (int x = r.x0(); x <= r.x1(); x++) {
            Props.bookshelf(c, x, y, r.z1(), Direction.NORTH, (x & 1) == 0);
            if (r.ceil() - y > 2) Props.bookshelf(c, x, y + 1, r.z1(), Direction.NORTH, true);
        }
        Props.desk(c, p, r.cx(), y, r.cz(), Direction.SOUTH);
        Props.chair(c, p, r.cx(), y, r.cz() + 1 <= r.z1() - 1 ? r.cz() + 1 : r.cz(), Direction.SOUTH);
        Props.cabinet(c, p, r.x0(), y, r.z0(), Direction.EAST, loot);
        Props.clutter(c, p, r.x1(), y, r.z0(), Direction.WEST, FurnitureKind.CLUTTER_CANDELABRA);
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    static void quarters(Canvas c, Palette p, Room r, String loot) {
        bedroom(c, p, r, false, AnchorType.BED, loot, Math.max(1, r.w() / 3));
    }

    /** Upstairs living for a shop: the keeper's bedroom; a wing becomes a store room. */
    static void living(Canvas c, Palette p, List<Room> wings, List<Room> upper, AnchorType bed, String loot) {
        for (Room w : wings) storeRoom(c, p, w);
        for (Room u : upper) bedroom(c, p, u, false, bed, loot == null ? "house" : loot, 2);
    }

    static void living(Canvas c, Palette p, Room r0, List<Room> wings, List<Room> upper, AnchorType bed, String loot) {
        living(c, p, wings, upper, bed, loot);
        if (upper.isEmpty()) {
            Room target = wings.isEmpty() ? r0 : wings.get(0);
            int y = target.y();
            int bx = target == r0 ? (target.x0() + 1 != target.cx() ? Math.min(target.x1() - 1, target.x0() + 1) : target.x0()) : target.x0();
            int bz = target.z1() - 1;
            int before = c.bedCount();
            c.bed(bx, y, bz, p.bed, Direction.SOUTH);
            if (c.bedCount() > before) {
                c.air(bx, y + 1, target.z1());
                c.air(bx, y + 2, target.z1());
                c.anchor(bed, bx, y, bz);
            } else {
                c.anchor(bed, bx, y, target.z1());
            }
        }
    }

    static void storeRoom(Canvas c, Palette p, Room r) {
        int y = r.y();
        for (int x = r.x0(); x <= r.x1(); x++) {
            for (int z : new int[]{r.z0(), r.z1()}) {
                if (c.chance(x, y, z, 71, 3)) continue;
                Props.barrel(c, x, y, z, c.chance(x, y, z, 73, 3) ? "store" : null);
                if (c.chance(x, y, z, 75, 2)) Props.barrel(c, x, y + 1, z, null);
            }
        }
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    /** More guest floors of an inn: a corridor with locked private rooms (the dormitory is on the first guest floor). */
    static void innRooms(Canvas c, Palette p, Room r) {
        Interiors.innUpper(c, p, r, false);
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    // ------------------------------------------------------------------ trades

    /** A shop counter across the back third with an open passage at x1 and a clear keeper standing spot behind it. */
    static int counter(Canvas c, Palette p, Room r, AnchorType who) {
        int y = r.y();
        int cz = Math.max(r.z0() + 2, r.z1() - 2);
        // always leave a free row behind the counter for the keeper to stand on
        if (cz >= r.z1()) cz = Math.max(r.z0() + 1, r.z1() - 1);
        int cx1 = Math.max(r.x0() + 1, r.x1() - 1);
        for (int x = r.x0() + 1; x <= cx1; x++) Props.counter(c, p, x, y, cz, Direction.NORTH);
        if (r.x1() > cx1) {
            c.set(r.x1(), y, cz, B.with(B.face(p.trapdoor, Direction.WEST), BlockStateProperties.OPEN, true));
            for (int kz = Math.max(r.z0(), cz - 1); kz <= Math.min(r.z1(), cz + 1); kz++) {
                c.keepClear(r.x1(), y, y + 1, kz);
            }
        }
        Props.clutter(c, p, r.x0() + 1, y + 1, cz, Direction.NORTH, FurnitureKind.CLUTTER_SCALES);
        if (cx1 > r.x0() + 1) Props.clutter(c, p, cx1, y + 1, cz, Direction.NORTH, FurnitureKind.CLUTTER_LEDGER);
        int seatZ = Math.min(r.z1(), cz + 1);
        c.keepClear(r.cx(), y, y + 1, seatZ);
        c.anchor(who, r.cx(), y, seatZ);
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
        return cz;
    }

    static void jeweller(Canvas c, Palette p, Room r, int doorX) {
        int y = r.y();
        int cz = counter(c, p, r, AnchorType.JEWELER);
        int cx1 = Math.max(r.x0() + 1, r.x1() - 1);
        BlockState[] wares = {B.s(Blocks.AMETHYST_CLUSTER), B.s(Blocks.DECORATED_POT), B.s(com.skycraft.worldgen.VanillaBlocks.CHAIN), B.s(Blocks.SMALL_AMETHYST_BUD)};
        for (int x = r.x0() + 1; x <= cx1; x += 2) c.set(x, y + 1, cz, wares[c.roll(x, y, cz, 41, wares.length)]);
        // Locked glass display cases along the side walls
        for (int z = r.z0() + 1; z < cz - 1; z += 2) {
            c.set(r.x0(), y, z, B.s(Blocks.GLASS));
            c.set(r.x0(), y + 1, z, B.s(Blocks.AMETHYST_CLUSTER));
            if (r.x1() != doorX) {
                c.set(r.x1(), y, z, B.s(Blocks.GLASS));
                c.set(r.x1(), y + 1, z, B.s(Blocks.LARGE_AMETHYST_BUD));
            }
        }
        c.set(r.x0(), y, r.z1(), B.s(Blocks.SMITHING_TABLE));
        if (r.x0() + 2 < r.cx()) c.set(r.x0() + 2, y, r.z1(), B.s(Blocks.ANVIL));
        c.set(r.x1(), y, r.z1(), B.face(B.s(Blocks.GRINDSTONE), Direction.WEST));
        Props.chest(c, Math.min(r.x1() - 1, r.cx() + 2), y, r.z1(), Direction.NORTH, "house_rich");
        Props.rug(c, p.carpet, r.cx() - 1, y, r.z0() + 1, r.cx() + 1, cz - 1);
    }

    static void books(Canvas c, Palette p, Room r, int doorX, AnchorType who) {
        int y = r.y();
        int cz = counter(c, p, r, who);
        int seatZ = Math.min(r.z1(), cz + 1);
        for (int z = r.z0(); z < cz - 1; z++) {
            if (r.x0() == doorX && z == r.z0()) continue;
            Props.bookshelf(c, r.x0(), y, z, Direction.EAST, (z & 1) == 0);
            Props.bookshelf(c, r.x0(), y + 1, z, Direction.EAST, true);
            if (r.x1() != doorX) {
                Props.bookshelf(c, r.x1(), y, z, Direction.WEST, (z & 1) != 0);
                Props.bookshelf(c, r.x1(), y + 1, z, Direction.WEST, true);
            }
        }
        for (int x = r.x0(); x <= r.x1(); x++) {
            if (x == r.cx() && seatZ == r.z1()) continue;
            Props.bookshelf(c, x, y, r.z1(), Direction.NORTH, true);
            if (r.ceil() - y > 2) Props.bookshelf(c, x, y + 1, r.z1(), Direction.NORTH, false);
        }
        Props.clutter(c, p, r.cx(), y + 1, cz, Direction.NORTH, FurnitureKind.CLUTTER_LEDGER);
        if (r.w() >= 7 && cz - r.z0() >= 4) {
            int rz = r.z0() + 2;
            Props.desk(c, p, r.cx(), y, rz, Direction.WEST);
            Props.chair(c, p, r.cx() - 1, y, rz, Direction.WEST);
        }
        Props.chest(c, Math.min(r.x1() - 1, r.cx() + 2), y, r.z1(), Direction.NORTH, "store");
        Props.clutter(c, p, r.x0() + 1, y + 1, cz, Direction.NORTH, FurnitureKind.CLUTTER_CANDELABRA);
    }

    static void tailor(Canvas c, Palette p, Room r, int doorX) {
        int y = r.y();
        int cz = counter(c, p, r, AnchorType.TAILOR);
        c.set(r.x0(), y, r.z1(), B.face(B.s(Blocks.LOOM), Direction.EAST));
        c.set(r.x1(), y, r.z1(), B.face(B.s(Blocks.LOOM), Direction.WEST));
        String[] cloth = {"red", "blue", "white", "brown", "green", "yellow", "black", "purple"};
        for (int z = r.z0(); z < cz - 1; z++) {
            if (r.x0() == doorX && z == r.z0()) continue;
            c.set(r.x0(), y, z, Materials.wool(cloth[c.roll(r.x0(), y, z, 43, cloth.length)]));
            c.set(r.x0(), y + 1, z, Materials.carpet(cloth[c.roll(r.x0(), y + 1, z, 44, cloth.length)]));
        }
        // Dress forms, wardrobe & cutting table
        if (r.x1() != doorX && cz - r.z0() >= 3) {
            Props.armorStand(c, p, r.x1(), y, r.z0() + 1, Direction.WEST);
        }
        if (r.x0() != doorX && cz - r.z0() >= 3) {
            Props.wardrobe(c, p, r.x0(), y, r.z0() + 1, Direction.EAST, "store");
        }
        c.set(r.x1(), y, r.z0(), Props.tanningRack());
        Props.chest(c, Math.min(r.x1() - 1, r.cx() + 2), y, r.z1(), Direction.NORTH, "store");
    }

    static void butcher(Canvas c, Palette p, Room r, int doorX) {
        int y = r.y();
        int cz = counter(c, p, r, AnchorType.BUTCHER);
        c.set(r.x0(), y, r.z1(), B.face(B.s(Blocks.SMOKER), Direction.NORTH));
        c.set(r.x1(), y, r.z1(), B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
        c.set(Math.max(r.x0() + 1, r.cx() - 1), y, r.z1(), B.axis(B.s(Blocks.STRIPPED_OAK_LOG), Direction.Axis.Y));
        c.set(Math.max(r.x0() + 1, r.cx() - 1), y + 1, r.z1(), B.s(com.skycraft.worldgen.VanillaBlocks.CHAIN));
        for (int z = r.z0(); z < cz - 1; z++) {
            if (r.x1() == doorX && z == r.z0()) continue;
            c.set(r.x1(), y, z, c.chance(r.x1(), y, z, 47, 2) ? B.s(Blocks.PACKED_ICE) : B.face(B.s(Blocks.BARREL), Direction.UP));
        }
        Props.barrel(c, r.x0(), y, r.x0() == doorX ? r.z0() + 1 : r.z0(), "food");
        Props.chest(c, Math.min(r.x1() - 1, r.cx() + 2), y, r.z1(), Direction.NORTH, "food");
    }

    static void fletcher(Canvas c, Palette p, Room r, int doorX) {
        int y = r.y();
        int cz = counter(c, p, r, AnchorType.FLETCHER);
        c.set(r.x0(), y, r.z1(), B.s(Blocks.FLETCHING_TABLE));
        c.set(r.x1(), y, r.z1(), B.s(Blocks.TARGET));
        if (r.x1() - 1 > r.cx()) c.set(r.x1() - 1, y, r.z1(), B.s(Blocks.HAY_BLOCK));
        for (int z = r.z0(); z < cz - 1; z++) {
            if (r.x0() == doorX && z == r.z0()) continue;
            Props.weaponRack(c, p, r.x0(), y, z, Direction.EAST);
        }
        if (r.x1() != doorX && cz - r.z0() >= 3) {
            Props.weaponRack(c, p, r.x1(), y, r.z0() + 1, Direction.WEST);
        }
        Props.barrel(c, Math.min(r.x1() - 1, r.cx() + 2), y, r.z1(), "store");
        c.set(r.x1(), y, r.z0(), B.s(com.skycraft.worldgen.VanillaBlocks.WHITE_WOOL));
    }

    static void tannery(Canvas c, Palette p, Room r, int doorX) {
        int y = r.y();
        int cz = counter(c, p, r, AnchorType.HUNTER);
        int seatZ = Math.min(r.z1(), cz + 1);
        for (int x = r.x0(); x <= r.x1(); x += 2) {
            if (x == r.cx() && seatZ == r.z1()) continue;
            c.set(x, y, r.z1(), Props.tanningRack());
        }
        c.set(r.x0(), y, r.z0() + 1, B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
        if (r.z0() + 2 < cz - 1) c.set(r.x0(), y, r.z0() + 2, Props.workbench());
        for (int z = r.z0(); z < cz - 1; z += 2) c.set(r.x1(), y, z, Materials.carpet(c.chance(r.x1(), y, z, 5, 2) ? "brown" : "white"));
        Props.chest(c, Math.min(r.x1() - 1, r.cx() + 2), y, r.z1(), Direction.NORTH, "store");
    }

    static void forgeCorner(Canvas c, Palette p, Room r) {
        forgeCorner(c, p, r, r.cx());
    }

    static void forgeCorner(Canvas c, Palette p, Room r, int doorX) {
        int y = r.y();
        int z0 = r.x0() == doorX ? r.z0() + 2 : r.z0() + 1;
        c.set(r.x0(), y, z0, B.face(Props.forge(), Direction.EAST));
        if (z0 + 1 <= r.z1() - 2) c.set(r.x0(), y, z0 + 1, B.s(Blocks.ANVIL));
        if (z0 + 2 <= r.z1() - 2) c.set(r.x0(), y, z0 + 2, B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
        if (z0 + 3 <= r.z1() - 2) c.set(r.x0(), y, z0 + 3, B.face(Props.grindstone(), Direction.EAST));
        if (z0 + 4 <= r.z1() - 2) c.set(r.x0(), y, z0 + 4, B.face(Props.smelter(), Direction.EAST));
        c.anchor(AnchorType.SMITH, r.x0() + 1, y, z0);
    }

    static void mageTower(Canvas c, Palette p, Grammar.Result res, int doorX) {
        Room r0 = res.main();
        int y = r0.y();
        c.set(r0.x0(), y, r0.z1(), B.face(Props.alchemyLab(), Direction.EAST));
        c.set(r0.x1(), y, r0.z1(), B.s(Blocks.BREWING_STAND));
        Props.pot(c, r0.x1(), y, r0.z0(), 13);
        c.set(r0.x0(), y, r0.z0() + (r0.x0() == doorX ? 1 : 0), B.s(Blocks.AMETHYST_BLOCK));
        c.anchor(AnchorType.WIZARD, r0.cx(), y, r0.cz());
        Props.lantern(c, p, r0.cx(), r0.ceil() - 1, r0.cz(), true);
        int i = 0;
        for (Room u : res.upper) {
            int uy = u.y();
            switch (i++ % 3) {
                case 0 -> {
                    c.set(u.cx(), uy, u.cz(), B.face(Props.enchanter(), Direction.SOUTH));
                    for (int x = u.x0(); x <= u.x1(); x++) Props.bookshelf(c, x, uy, u.z1(), Direction.NORTH, (x & 1) == 0);
                    Props.clutter(c, p, u.x0(), uy, u.z0(), Direction.EAST, FurnitureKind.CLUTTER_CANDELABRA);
                }
                case 1 -> {
                    c.bed(u.x0(), uy, u.z1() - 1, p.bed, Direction.SOUTH);
                    c.anchor(AnchorType.BED, u.x0(), uy, u.z1() - 1);
                    Props.cabinet(c, p, u.x1(), uy, u.z1(), Direction.WEST, "alchemist");
                    Props.desk(c, p, u.x1(), uy, u.z0(), Direction.SOUTH);
                }
                default -> {
                    c.set(u.cx(), uy, u.cz(), B.s(Blocks.RESPAWN_ANCHOR));
                    c.set(u.x0(), uy, u.z1(), B.s(Blocks.AMETHYST_CLUSTER));
                    Props.desk(c, p, u.x1(), uy, u.z1(), Direction.NORTH);
                }
            }
            Props.lantern(c, p, u.cx(), u.ceil() - 1, u.cz(), true);
        }
    }

    /** A hidden cellar under the floor: trapdoor and ladder in a corner, the fence's stash and table. */
    static void cellar(Canvas c, Palette p, Room r) {
        int y0 = -4, y1 = -1;
        int x0 = r.x0(), x1 = r.x1(), z0 = r.z0(), z1 = r.z1();
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                boolean edge = x == x0 - 1 || x == x1 + 1 || z == z0 - 1 || z == z1 + 1;
                c.set(x, y0 - 1, z, p.foundation);
                for (int y = y0; y <= y1; y++) {
                    if (edge) c.set(x, y, z, p.foundation);
                    else c.set(x, y, z, B.s(Blocks.AIR));
                }
            }
        }
        // trapdoor under a rug-less corner, ladder down
        c.set(x0, 0, z0, B.with(B.face(p.trapdoor, Direction.SOUTH), BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.TOP));
        for (int y = y0; y <= y1; y++) c.set(x0, y, z0, B.face(B.s(Blocks.LADDER), Direction.SOUTH));
        c.air(x0, 1, z0);
        Props.chest(c, x1, y0, z1, Direction.WEST, "store");
        Props.chest(c, x1, y0, z1 - 1 >= z0 ? z1 - 1 : z1, Direction.WEST, "house_rich");
        Props.barrel(c, x0, y0, z1, null);
        Props.table(c, p, (x0 + x1) / 2, y0, (z0 + z1) / 2);
        Props.candles(c, (x0 + x1) / 2, y0 + 1, (z0 + z1) / 2, 2);
        c.anchor(AnchorType.FENCE, (x0 + x1) / 2 + 1 <= x1 ? (x0 + x1) / 2 + 1 : (x0 + x1) / 2, y0, (z0 + z1) / 2);
    }

    static void stables(Canvas c, Blueprint b, Palette p, Room r) {
        int y = r.y();
        // stalls along the front (open to the yard through gates), tack room at the back
        int sz = r.z0();
        for (int x = r.x0(); x <= r.x1(); x++) {
            if ((x - r.x0()) % 3 == 0) {
                c.set(x, y, sz, p.fence);
                c.set(x, y, sz + 1, p.fence);
            } else {
                c.set(x, y - 1, sz + 1, B.s(Blocks.COARSE_DIRT));
                c.set(x, y, sz + 1, (x - r.x0()) % 3 == 1 ? B.s(Blocks.HAY_BLOCK) : B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
            }
        }
        // open front with gates (the front wall row is z0 - 1)
        for (int x = r.x0(); x <= r.x1(); x++) {
            if ((x - r.x0()) % 3 == 0) continue;
            c.set(x, y, r.z0() - 1, B.face(p.gate, Direction.NORTH));
            c.air(x, y + 1, r.z0() - 1);
            Outdoors.animal(c, x, y, sz, c.chance(x, y, sz, 5, 3) ? net.minecraft.world.entity.EntityTypes.DONKEY : net.minecraft.world.entity.EntityTypes.HORSE);
        }
        Props.chest(c, r.x1(), y, r.z1(), Direction.WEST, "farm");
        c.bed(r.x0(), y, r.z1() - 1, p.bed, Direction.SOUTH);
        c.anchor(AnchorType.BED, r.x0(), y, r.z1() - 1);
        c.set(r.cx(), y, r.z1(), Props.tanningRack());
        c.anchor(AnchorType.STABLES, b.doorX, 1, -1);
        c.anchor(AnchorType.CARRIAGE, b.doorX, 1, -4);
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    static void barn(Canvas c, Blueprint b, Palette p, Room r) {
        int y = r.y();
        // wide doors
        for (int x = b.doorX - 1; x <= b.doorX + 1; x++) for (int yy = 1; yy <= 3; yy++) c.air(x, yy, r.z0() - 1);
        // a hay loft at the back half
        int loftY = r.ceil() - 1;
        for (int x = r.x0(); x <= r.x1(); x++) {
            for (int z = r.cz() + 1; z <= r.z1(); z++) {
                c.set(x, loftY, z, B.slab(p.slab, true));
                if (c.chance(x, loftY, z, 21, 2)) c.set(x, loftY + 1, z, B.s(Blocks.HAY_BLOCK));
            }
            c.set(x, loftY + 1, r.cz() + 1, p.fence);
        }
        for (int yy = y; yy <= loftY; yy++) c.set(r.x0(), yy, r.cz() + 2, B.face(B.s(Blocks.LADDER), Direction.EAST));
        // pens down the sides
        for (int z = r.z0() + 1; z < r.cz(); z++) {
            c.set(r.x0() + 2, y, z, p.fence);
            c.set(r.x1() - 2, y, z, p.fence);
        }
        for (int z = r.z0() + 1; z <= r.cz(); z += 2) {
            c.set(r.x0(), y, z, B.s(Blocks.HAY_BLOCK));
            c.set(r.x1(), y, z, B.s(Blocks.COMPOSTER));
        }
        List<String> animals = b.style.animals();
        for (int i = 0; i < 3; i++) {
            String a = animals.isEmpty() ? "cow" : animals.get(Math.floorMod(c.roll(i, 0, 0, 51, 64), animals.size()));
            Outdoors.animal(c, i % 2 == 0 ? r.x0() + 1 : r.x1() - 1, y, r.z0() + 1 + i, Outdoors.animalType(a));
        }
        Props.chest(c, r.cx(), y, r.z1(), Direction.NORTH, "farm");
        c.anchor(AnchorType.FARMER, b.doorX, 1, r.z0());
    }

    static void sawmill(Canvas c, Blueprint b, Palette p, Room r) {
        int y = r.y();
        // the front stands open on posts
        for (int x = r.x0(); x <= r.x1(); x++) for (int yy = 1; yy <= 3; yy++) if ((x - r.x0()) % 3 != 2) c.air(x, yy, r.z0() - 1);
        BlockState sawn = B.id("skycraft:sawn_log", Blocks.STRIPPED_SPRUCE_LOG);
        BlockState log = B.axis(B.s(Blocks.SPRUCE_LOG), Direction.Axis.X);
        for (int x = r.x0(); x <= r.x1() - 2; x++) {
            c.set(x, y, r.z1(), log);
            c.set(x, y + 1, r.z1(), log);
            c.set(x, y, r.z1() - 1, B.axis(sawn, Direction.Axis.X));
        }
        c.set(r.cx(), y, r.cz(), B.s(Blocks.STONECUTTER));
        c.set(r.cx() + 1, y, r.cz(), B.axis(B.s(Blocks.STRIPPED_SPRUCE_LOG), Direction.Axis.X));
        c.set(r.x1(), y, r.z1(), B.s(Blocks.CRAFTING_TABLE));
        Props.chest(c, r.x1(), y, r.z0(), Direction.WEST, "mill");
        c.anchor(AnchorType.LUMBERJACK, r.cx(), y, r.cz() - 1);
        if (b.spec.variant == 1) waterWheel(c, p, b.bx1, (b.bz0 + b.bz1) / 2);
    }

    static void smelter(Canvas c, Blueprint b, Palette p, Room r) {
        int y = r.y();
        for (int x = r.x0(); x <= r.x1(); x++) for (int yy = 1; yy <= 3; yy++) if ((x - r.x0()) % 3 != 2) c.air(x, yy, r.z0() - 1);
        for (int x = r.x0(); x <= r.x1(); x++) {
            BlockState f = (x - r.x0()) % 2 == 0 ? B.face(Props.smelter(), Direction.NORTH) : B.face(B.s(Blocks.BLAST_FURNACE), Direction.NORTH);
            c.set(x, y, r.z1(), f);
            c.set(x, y + 1, r.z1(), p.foundation);
        }
        BlockState[] ore = {B.s(Blocks.RAW_IRON_BLOCK), B.s(Blocks.COAL_BLOCK), B.s(Blocks.GRAVEL), B.s(Blocks.RAW_COPPER_BLOCK)};
        for (int z = r.z0(); z < r.z1() - 1; z++) c.set(r.x0(), y, z, ore[c.roll(r.x0(), y, z, 53, ore.length)]);
        Props.chest(c, r.x1(), y, r.z0(), Direction.WEST, "mine");
        c.anchor(AnchorType.WORKER, r.cx(), y, r.z1() - 1);
    }

    static void warehouse(Canvas c, Palette p, Room r) {
        int y = r.y();
        for (int x = r.x0(); x <= r.x1(); x++) {
            for (int z = r.z0() + 1; z <= r.z1(); z++) {
                boolean aisle = (x - r.x0()) % 3 == 1 || z == r.z0() + 1;
                if (aisle) continue;
                int stack = 1 + c.roll(x, y, z, 55, 3);
                for (int k = 0; k < stack && y + k < r.ceil(); k++) {
                    if (c.chance(x, y + k, z, 57, 4)) Props.chest(c, x, y + k, z, Direction.NORTH, k == 0 && c.chance(x, y, z, 59, 3) ? "store" : null);
                    else Props.barrel(c, x, y + k, z, k == 0 && c.chance(x, y, z, 59, 4) ? "food" : null);
                }
            }
        }
        c.anchor(AnchorType.WORKER, r.cx(), y, r.z0());
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    static void fishery(Canvas c, Palette p, Room r, int doorX) {
        int y = r.y();
        int cz = counter(c, p, r, AnchorType.FISHER);
        c.set(r.x0(), y, r.z1(), B.face(B.s(Blocks.SMOKER), Direction.NORTH));
        for (int x = r.x0() + 1; x <= r.x1(); x++) Props.barrel(c, x, y, r.z1(), c.chance(x, y, r.z1(), 3, 2) ? "food" : null);
        c.set(r.x0(), y, r.z0() + (r.x0() == doorX ? 1 : 0), B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
        c.set(r.x1(), y + 1, cz, B.s(Blocks.DRIED_KELP_BLOCK));
    }

    static void guardhouse(Canvas c, Palette p, Room r) {
        int y = r.y();
        Props.desk(c, p, r.cx(), y, r.cz(), Direction.SOUTH);
        Props.chair(c, p, r.cx(), y, r.cz() + 1, Direction.SOUTH);
        for (int z = r.z0(); z <= r.z1(); z += 2) {
            Props.weaponRack(c, p, r.x0(), y, z, Direction.EAST);
        }
        Props.armorStand(c, p, r.x1(), y, r.z0() + 1 <= r.z1() ? r.z0() + 1 : r.z0(), Direction.WEST);
        Props.chest(c, r.x1(), y, r.z1(), Direction.WEST, "barracks");
        c.anchor(AnchorType.GUARD, r.cx(), y, r.cz() - 1);
        c.anchor(AnchorType.GUARD, r.cx() + 1, y, r.z1());
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    static void guildHall(Canvas c, Palette p, Room r) {
        int y = r.y();
        for (int z = r.z0() + 2; z <= r.z1() - 2; z++) {
            Props.banquetTable(c, p, r.cx(), y, z, Direction.NORTH);
            Props.chair(c, p, r.cx() - 1, y, z, Direction.WEST);
            Props.chair(c, p, r.cx() + 1, y, z, Direction.EAST);
            FurnitureKind ck = (z - r.z0()) % 3 == 0 ? FurnitureKind.CLUTTER_CANDELABRA
                    : (z - r.z0()) % 3 == 1 ? FurnitureKind.CLUTTER_TANKARD : FurnitureKind.CLUTTER_PLATTER;
            Props.clutter(c, p, r.cx(), y + 1, z, Direction.NORTH, ck);
        }
        Props.weaponRack(c, p, r.x0(), y, r.cz(), Direction.EAST);
        Props.weaponRack(c, p, r.x1(), y, r.cz(), Direction.WEST);
        for (int x = r.x0(); x <= r.x1(); x += 2) Props.tapestry(c, p, x, y + 3, r.z1() + 1);
        Props.chest(c, r.x0(), y, r.z1(), Direction.EAST, "store");
        Props.chest(c, r.x1(), y, r.z1(), Direction.WEST, "store");
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    // ------------------------------------------------------------------ outside work

    /** A water wheel turning against the right wall (local x = {@code wallX}) at depth {@code cz}. */
    static void waterWheel(Canvas c, Palette p, int wallX, int cz) {
        int wx = wallX + 2;
        int cy = 3;
        for (int x = wallX; x <= wx; x++) c.set(x, cy, cz, B.axis(p.beam, Direction.Axis.X));
        for (int dy = -3; dy <= 3; dy++) {
            for (int dz = -3; dz <= 3; dz++) {
                double r = Math.sqrt(dy * dy + dz * dz);
                if (r > 3.4) continue;
                if (r > 2.5) c.set(wx, cy + dy, cz + dz, p.planks);
                else if (dy == 0 || dz == 0) c.set(wx, cy + dy, cz + dz, B.axis(p.beam, Direction.Axis.Y));
            }
        }
        for (int z = cz - 4; z <= cz + 4; z++) {
            for (int x = wx - 1; x <= wx + 1; x++) {
                c.set(x, -1, z, p.foundation);
                c.set(x, 0, z, B.s(Blocks.WATER));
            }
        }
    }

    /** Windmill sails on the front gable. */
    static void windmill(Canvas c, Palette p, Blueprint b) {
        int cx = b.doorX, cy = b.wallTop() + 2, z = b.bz0 - 1;
        c.set(cx, cy, z, B.axis(p.beam, Direction.Axis.Z));
        c.set(cx, cy, z - 1, B.axis(p.beam, Direction.Axis.Z));
        BlockState sail = B.s(com.skycraft.worldgen.VanillaBlocks.WHITE_WOOL);
        for (int k = 1; k <= 5; k++) {
            c.set(cx + k, cy + k, z - 1, B.axis(p.beam, Direction.Axis.Y));
            c.set(cx - k, cy - k, z - 1, B.axis(p.beam, Direction.Axis.Y));
            c.set(cx - k, cy + k, z - 1, B.axis(p.beam, Direction.Axis.Y));
            c.set(cx + k, cy - k, z - 1, B.axis(p.beam, Direction.Axis.Y));
            if (k >= 2) {
                c.set(cx + k - 1, cy + k, z - 1, sail);
                c.set(cx - k + 1, cy - k, z - 1, sail);
                c.set(cx - k, cy + k - 1, z - 1, sail);
                c.set(cx + k, cy - k + 1, z - 1, sail);
            }
        }
    }

    static void pen(Canvas c, Blueprint b, Grammar.Result res) {
        List<String> animals = b.style.animals();
        int n = 2 + c.roll(0, 0, 0, 63, 2);
        for (int i = 0; i < n; i++) {
            String a = animals.isEmpty() ? "chicken" : animals.get(Math.floorMod(c.roll(i, 1, 0, 65, 64), animals.size()));
            int x = res.ax0 + 1 + i % Math.max(1, res.ax1 - res.ax0 - 1);
            int z = res.az0 + 1 + i / 2;
            if (z >= res.az1) z = res.az1 - 1;
            Outdoors.animal(c, x, 1, z, Outdoors.animalType(a));
        }
    }

    static void leanTo(Canvas c, Blueprint b, Palette p, Grammar.Result res) {
        int inner = b.annexSide < 0 ? res.ax1 : res.ax0;
        for (int z = res.az0; z <= res.az1; z++) {
            switch (b.use) {
                case TANNERY -> c.set(inner, 1, z, z % 2 == 0 ? Props.tanningRack() : B.s(Blocks.AIR));
                case FLETCHER -> c.set(inner, 1, z, z == res.az1 ? B.s(Blocks.TARGET) : B.s(Blocks.HAY_BLOCK));
                case BUTCHER -> c.set(inner, 1, z, z % 2 == 0 ? B.s(com.skycraft.worldgen.VanillaBlocks.CHAIN) : B.face(B.s(Blocks.BARREL), Direction.UP));
                default -> {
                    BlockState log = B.axis(B.s(Blocks.SPRUCE_LOG), Direction.Axis.Z);
                    c.set(inner, 1, z, log);
                    if (c.chance(inner, 2, z, 3, 2)) c.set(inner, 2, z, log);
                }
            }
        }
    }
}
