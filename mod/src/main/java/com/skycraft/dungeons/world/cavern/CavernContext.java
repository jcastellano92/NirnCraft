package com.skycraft.dungeons.world.cavern;

import com.skycraft.dungeons.world.DungeonPiece;
import com.skycraft.dungeons.world.Kit;
import com.skycraft.dungeons.world.Theme;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Everything the selector knows about a cavern: where it is (region, province, culture, climate), what dungeon it
 * belongs to (theme, kit, cave roster, depth) and which of the dungeon's caverns it is. Plain data, so selection can
 * be run offline for any combination (see {@link CavernQa}).
 */
public final class CavernContext {
    public final String regionId;
    public final String regionShort;
    public final String province;
    public final String culture;
    public final String weather;
    /** cold, mild, hot, wet or volcanic. */
    public final String temp;
    public final Theme theme;
    public final Kit kit;
    /** The cave's inhabitants (bandit, den, spider, troll, falmer, forsworn, spriggan, ice, skeever, smuggler), or null. */
    @Nullable
    public final String roster;
    /** Walking level of the cavern's rim. */
    public final int y;
    /** Depth on the dungeon's room tree, 0 (entrance) to 7. */
    public final int tier;
    public final int x, z;
    /** Interior height and the piece's half spans. */
    public final int height, hx, hz;
    /** Which of the dungeon's grand caverns this is (0 = first). */
    public final int ordinal;
    /** The dungeon's colour in the neighbourhood lattice (0..7). */
    public final int color;
    /** A key shared by the caverns of one dungeon. */
    public final long key;

    public CavernContext(String regionId, String province, String culture, String weather, Theme theme, Kit kit,
                         @Nullable String roster, int y, int tier, int x, int z, int height, int hx, int hz, int ordinal,
                         int color, long key) {
        this.regionId = regionId;
        int slash = regionId.lastIndexOf('/');
        this.regionShort = slash < 0 ? regionId : regionId.substring(slash + 1);
        this.province = province;
        this.culture = culture;
        this.weather = weather;
        this.temp = tempOf(weather);
        this.theme = theme;
        this.kit = kit;
        this.roster = roster;
        this.y = y;
        this.tier = tier;
        this.x = x;
        this.z = z;
        this.height = height;
        this.hx = hx;
        this.hz = hz;
        this.ordinal = ordinal;
        this.color = color;
        this.key = key;
    }

    /** Climate class of a region weather id. */
    public static String tempOf(String weather) {
        return switch (weather) {
            case "frozen", "skyrim_cold" -> "cold";
            case "desert", "savanna" -> "hot";
            case "tropical", "jungle", "swamp" -> "wet";
            case "ash" -> "volcanic";
            default -> "mild";
        };
    }

    /** Roster name of a cave or mine flavor, or null. */
    @Nullable
    public static String rosterOf(Theme theme, int flavor) {
        if (theme == Theme.MINE) return flavor % 2 == 1 ? "bandit" : "skeever";
        if (theme != Theme.CAVE) return null;
        int r = flavor >= 100 ? new int[]{0, 1, 2, 3, 8, 0, 1, 9}[Math.floorMod(flavor - 100, 8)] : Math.floorMod(flavor, 10);
        return switch (r) {
            case 0 -> "bandit";
            case 1 -> "den";
            case 2 -> "spider";
            case 3 -> "troll";
            case 4 -> "falmer";
            case 5 -> "forsworn";
            case 6 -> "spriggan";
            case 7 -> "ice";
            case 8 -> "skeever";
            default -> "smuggler";
        };
    }

    /** The context of a piece being painted at its own position. */
    public static CavernContext of(WorldGenLevel level, DungeonPiece pc) {
        var b = pc.getBoundingBox();
        int cx = (b.minX() + b.maxX()) / 2, cz = (b.minZ() + b.maxZ()) / 2;
        Region r;
        try {
            r = Regions.at(level.getLevel(), new BlockPos(cx, pc.floor, cz));
        } catch (RuntimeException e) {
            r = Regions.fallback();
        }
        int tag = CavernTag.of(pc.flags);
        long key = tag != 0 ? CavernTag.key(pc.flags) : mix(pc.seed);
        int color = tag != 0 ? CavernTag.color(pc.flags) : CavernTag.colorAt(cx, cz);
        int tier = (pc.variant >> 4) & 7;
        return new CavernContext(r.id(), r.province(), r.culture(), r.weather(), pc.theme, pc.kit,
                rosterOf(pc.theme, pc.flavor), pc.floor, tier, cx, cz, b.maxY() - pc.floor, (b.maxX() - b.minX()) / 2,
                (b.maxZ() - b.minZ()) / 2, CavernTag.ordinal(pc.flags), color, key);
    }

    static long mix(long v) {
        v ^= v >>> 33;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 33;
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= v >>> 33;
        return v;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s/%s %s %s tier %d y %d #%d", regionId, theme.id, kit.id, roster, tier, y, ordinal);
    }
}
