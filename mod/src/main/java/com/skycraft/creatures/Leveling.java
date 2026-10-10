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

    /**
     * Contract 3 (docs/PLAYTEST-1.md): the difficulty level of a place, 1..60. Low near the world spawn and rising
     * with distance and dangerous terrain.
     */
    public static int regionLevel(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        net.minecraft.core.BlockPos spawn = level.getSharedSpawnPos();
        double dist = Math.sqrt(spawn.distSqr(new net.minecraft.core.BlockPos(pos.getX(), spawn.getY(), pos.getZ())));
        int distLevel = 1 + (int) (dist / 120.0);

        int terrainBonus = 0;
        if (pos.getY() < 0) {
            terrainBonus += Math.min(15, (-pos.getY()) / 6);
        }
        var biome = level.getBiome(pos);
        if (biome.is(BiomeTags.IS_MOUNTAIN)) {
            terrainBonus += 8;
        } else if (biome.is(Tags.Biomes.IS_SNOWY)) {
            terrainBonus += 4;
        }
        return Math.max(1, Math.min(60, distLevel + terrainBonus));
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
    }

    private static void modify(AttributeInstance attribute, UUID id, String name, double amount) {
        if (attribute == null) return;
        if (attribute.getModifier(id) != null) attribute.removeModifier(id);
        if (amount > 0) attribute.addPermanentModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.MULTIPLY_BASE));
    }
}
