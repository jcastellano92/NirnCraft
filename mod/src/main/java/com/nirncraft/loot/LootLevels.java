package com.nirncraft.loot;

import com.nirncraft.core.SkyData;
import com.nirncraft.economy.EconomyLoot;
import com.nirncraft.tamriel.Region;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The level a piece of loot is rolled at. Skyrim locks a dungeon's leveled lists to its encounter zone: the player's
 * level, clamped by the zone. NirnCraft does the same with the region's level band:
 * <ul>
 *   <li><b>gear level</b> = the player's level, never above it, capped at the region's top level + {@value #REGION_SLACK}
 *   (a level 50 hero clearing Whiterun's barrows finds the gear of a level 25 zone);</li>
 *   <li>corpses roll at the creature's level (its {@code nirncraft_level}), never above the killer's;</li>
 *   <li><b>danger</b>: a region whose bottom level is above the player pays more gold (+2% per level of gap, at most
 *   +40%), never better gear: rushing into Akavir at level 5 is not a jackpot.</li>
 * </ul>
 */
public final class LootLevels {
    /** Levels above a region's band that its loot can still reach. */
    public static final int REGION_SLACK = 15;

    private LootLevels() {}

    /** Loot level parameters of one roll. */
    public record Level(int player, int gear, float danger, @Nullable Region region) {}

    /** Dev tooling ({@code /skyloot}): rolls on this thread use this loot level, uncapped. */
    private static final ThreadLocal<Integer> OVERRIDE = new ThreadLocal<>();

    public static void override(@Nullable Integer level) {
        if (level == null) OVERRIDE.remove();
        else OVERRIDE.set(Math.max(1, level));
    }

    public static Level of(LootContext context) {
        Integer forced = OVERRIDE.get();
        if (forced != null) return new Level(forced, forced, 1f, EconomyLoot.regionOf(context));
        Player player = player(context);
        int pl = player == null ? 1 : Math.max(1, SkyData.get(player).getLevel());
        Region region = EconomyLoot.regionOf(context);
        int gear = pl;
        if (region != null && region.maxLevel() > 0) gear = Math.min(gear, region.maxLevel() + REGION_SLACK);
        Entity self = context.getOptional(LootContextParams.THIS_ENTITY);
        if (self instanceof Mob mob) {
            int mobLevel = mob.getPersistentData().getIntOr("nirncraft_level",0);
            if (mobLevel > 0) gear = Math.min(gear, mobLevel);
        }
        float danger = 1f;
        if (region != null && region.minLevel() > pl) danger = Math.min(1.4f, 1f + 0.02f * (region.minLevel() - pl));
        return new Level(pl, Math.max(1, gear), danger, region);
    }

    /** Gear level only (see {@link #of}). */
    public static int gear(LootContext context) {
        return of(context).gear();
    }

    /**
     * The player the loot is for: THIS_ENTITY if it's a player (chests opened by a player, Lootr's per-player rolls),
     * else the killer or last damaging player, else the nearest player within 64 blocks of the origin.
     */
    @Nullable
    public static Player player(LootContext context) {
        Entity self = context.getOptional(LootContextParams.THIS_ENTITY);
        if (self instanceof Player p) return p;
        if (context.getOptional(LootContextParams.ATTACKING_ENTITY) instanceof Player p) return p;
        Player last = context.getOptional(LootContextParams.LAST_DAMAGE_PLAYER);
        if (last != null) return last;
        Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
        if (origin == null && self != null) origin = self.position();
        return origin == null ? null : context.getLevel().getNearestPlayer(origin.x, origin.y, origin.z, 64, false);
    }

    // ------------------------------------------------------------------ curves (mirrored by tools/loot_sim.py)

    /** Gold multiplier at a loot level: +{@code growth} per level above 1. */
    public static float goldScale(int level, float growth) {
        return 1f + Math.max(0, growth) * (Math.max(1, level) - 1);
    }

    /**
     * Chance that a gear drop is enchanted: the table's base times 0.35 at level 1, ~1 at 20, 1.6 at 40, 2.2 from 60
     * (a dungeon's gear: ~6% enchanted at level 1, ~25% at 40, ~35% at 60, like Skyrim's leveled lists), capped at 85%.
     */
    public static float enchantChance(float base, int level) {
        if (base <= 0) return 0;
        return Math.min(0.85f, base * Mth.clamp(0.35f + level / 32f, 0.35f, 2.2f));
    }

    /** Enchantment level of a leveled enchanted drop: 1 at low level, up to 4 around level 45. */
    public static int enchantLevel(int level, int maxLevel, net.minecraft.util.RandomSource random) {
        int lvl = 1 + level / 15 + (random.nextInt(4) == 0 ? 1 : 0) - (random.nextInt(4) == 0 ? 1 : 0);
        return Mth.clamp(lvl, 1, Math.max(1, maxLevel));
    }
}
