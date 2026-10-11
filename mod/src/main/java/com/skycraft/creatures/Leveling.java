package com.skycraft.creatures;

import com.skycraft.Skycraft;
import com.skycraft.combat.CombatHandler;
import com.skycraft.core.SkyData;
import com.skycraft.creatures.entity.Ranked;
import com.skycraft.magic.spell.Summons;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.tags.BiomeTags;
import net.minecraftforge.common.Tags;

import java.util.UUID;

/**
 * Skyrim leveled lists: every hostile creature gets a level close to the nearest player's level the first time it
 * joins the world (persistent data {@code skycraft_level}), with more health (+6%/level, max +300%) and damage
 * (+4%/level, max +200%). Creatures of this module also get a rank name ("Bandit Thug", "Draugr Wight").
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Leveling {
    /** Persistent-data key holding a creature's level (other modules may read it). */
    public static final String LEVEL_KEY = "skycraft_level";
    private static final UUID HEALTH_MOD = UUID.fromString("5b0c2a4e-1f6d-4c1e-9b8e-3a5d1c7e2f01");
    private static final UUID DAMAGE_MOD = UUID.fromString("5b0c2a4e-1f6d-4c1e-9b8e-3a5d1c7e2f02");

    private Leveling() {}

    /** Blocks from a region's capital at which its level band reaches its top. */
    private static final double BAND_REACH = 1600.0;

    /**
     * Contract 3 (docs/PLAYTEST-1.md): the difficulty level of a place, 1..60. The region's level band
     * ({@link Region#minLevel()}..{@link Region#maxLevel()}, e.g. Whiterun 1-12, the Pale higher) is low near the
     * region's capital and rises towards its wild edges; caves and deep places, high mountains and dungeons add a
     * bonus on top.
     */
    public static int regionLevel(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        Region region = Regions.at(level, pos);
        int min = Math.max(1, region.minLevel());
        int max = Math.max(min, region.maxLevel());
        double t;
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            // realms: deeper in (farther from their entry point) is deadlier
            net.minecraft.core.BlockPos spawn = level.getSharedSpawnPos();
            double dx = pos.getX() - spawn.getX();
            double dz = pos.getZ() - spawn.getZ();
            t = Math.sqrt(dx * dx + dz * dz) / (BAND_REACH * 1.5);
        } else {
            t = wildness(region, pos.getX(), pos.getZ());
        }
        t = Math.max(0, Math.min(1, t));
        int base = min + (int) Math.round((max - min) * t * t * (3 - 2 * t)); // smoothstep: safe around the capital

        int bonus = 0;
        if (pos.getY() < 40) bonus += Math.min(10, (40 - pos.getY()) / 8 + 1);
        if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            float macro = TamrielMap.elevation(pos.getX(), pos.getZ());
            if (macro > 150) bonus += Math.min(5, (int) ((macro - 150) / 40) + 1);
        }
        var biome = level.getBiome(pos);
        if (biome.is(BiomeTags.IS_MOUNTAIN)) {
            bonus += 2;
        } else if (biome.is(Tags.Biomes.IS_SNOWY)) {
            bonus += 1;
        }
        // (no structure lookups here: this runs while chunks load their entities)
        Place named = Places.nearest(PlaceType.DUNGEON_FIXED, pos.getX(), pos.getZ(), 160).orElse(null);
        if (named != null && named.distSqr(pos.getX(), pos.getZ()) <= (long) named.radius() * named.radius()) bonus += 3;
        return Math.max(1, Math.min(60, base + bonus));
    }

    /** 0 at the region's capital (or its nearest town), 1 at {@link #BAND_REACH} blocks and beyond. */
    private static double wildness(Region region, int x, int z) {
        Place capital = region.capital() == null ? null : Places.byId(region.capital()).orElse(null);
        if (capital == null) {
            capital = Places.nearest(PlaceType.CAPITAL, x, z, (int) BAND_REACH).orElse(null);
            if (capital == null) capital = Places.nearest(PlaceType.CITY, x, z, (int) BAND_REACH).orElse(null);
            if (capital == null) capital = Places.nearest(PlaceType.TOWN, x, z, (int) BAND_REACH).orElse(null);
        }
        if (capital == null) return 0.75;
        double d = Math.sqrt(capital.distSqr(x, z)) - capital.radius();
        return d / BAND_REACH;
    }

    /** The creature's level, or 0 if it isn't leveled. */
    public static int levelOf(Entity entity) {
        return entity.getPersistentData().getInt(LEVEL_KEY);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof Mob mob) || !(mob instanceof Enemy)) return;
        CompoundTag data = mob.getPersistentData();
        if (data.contains(LEVEL_KEY)) return;
        // Player summons (conjured zombies, atronachs, dremora) and charmed allies keep the stats their spell gave them.
        if (Summons.isCompanion(mob)) return;
        if (mob.getType().is(CombatHandler.BOSSES) || mob instanceof EnderDragon || mob instanceof WitherBoss) return;
        if (!CreaturesConfig.LEVELED_ENEMIES.get()) return;

        int regLevel = (mob.level() instanceof net.minecraft.server.level.ServerLevel sl)
                ? regionLevel(sl, mob.blockPosition()) : 1;
        Player nearest = event.getLevel().getNearestPlayer(mob, 128);
        int playerLevel = nearest != null ? SkyData.get(nearest).getLevel() : 1;
        int base = Math.max(regLevel, (regLevel + playerLevel) / 2);
        int level = Math.max(1, Math.min(60, base + mob.getRandom().nextInt(5) - 2));
        apply(mob, level);
    }

    /** Sets a creature's level: attribute bonuses, full heal, rank name. */
    public static void apply(Mob mob, int level) {
        mob.getPersistentData().putInt(LEVEL_KEY, level);
        modify(mob.getAttribute(Attributes.MAX_HEALTH), HEALTH_MOD, "Skycraft level health", Math.min(3.0, 0.06 * level));
        modify(mob.getAttribute(Attributes.ATTACK_DAMAGE), DAMAGE_MOD, "Skycraft level damage", Math.min(2.0, 0.04 * level));
        mob.setHealth(mob.getMaxHealth());
        if (mob instanceof Ranked ranked && !mob.hasCustomName()) {
            Component name = ranked.rankName(level);
            if (name != null) mob.setCustomName(name);
        }
        if (mob instanceof Ranked ranked) ranked.onLevelApplied(level);
    }

    private static void modify(AttributeInstance attribute, UUID id, String name, double amount) {
        if (attribute == null) return;
        if (attribute.getModifier(id) != null) attribute.removeModifier(id);
        if (amount > 0) attribute.addPermanentModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.MULTIPLY_BASE));
    }
}
