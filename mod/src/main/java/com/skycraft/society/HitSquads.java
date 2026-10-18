package com.skycraft.society;

import com.skycraft.Skycraft;
import com.skycraft.crime.Crimes;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Factions that hate the player send someone: every 20-40 minutes of play (config) the most hated faction may
 * dispatch a squad of 2-4 that spawns 40-60 blocks away and hunts the player down. Justiciars for the Thalmor,
 * Forsworn ambushers, Stormcloak or Legion patrols, Dark Brotherhood assassins, bandits, or hired thugs when the
 * townsfolk have had enough of the player's thieving and brawling.
 */
public final class HitSquads {
    private static final String TIMER = "skycraft_squad_secs";

    private HitSquads() {}

    @Nullable
    static NpcRole roleFor(String faction) {
        return switch (faction) {
            case Reputation.THALMOR -> NpcRole.THALMOR;
            case Reputation.FORSWORN -> NpcRole.FORSWORN;
            case Reputation.STORMCLOAKS -> NpcRole.STORMCLOAK_SOLDIER;
            case Reputation.IMPERIAL_LEGION -> NpcRole.IMPERIAL_SOLDIER;
            case Reputation.DARK_BROTHERHOOD -> NpcRole.ASSASSIN;
            case Reputation.VAMPIRES -> NpcRole.VAMPIRE;
            case Reputation.TOWNSFOLK, Reputation.THIEVES_GUILD, Reputation.BANDITS -> NpcRole.THUG;
            default -> null;
        };
    }

    /** Called every second per player. */
    static void tick(ServerPlayer player) {
        if (!SocietyConfig.HIT_SQUADS.get()) return;
        CompoundTag t = Cosmetics.persistedTag(player);
        RandomSource r = player.getRandom();
        int left = t.contains(TIMER) ? t.getIntOr(TIMER, 0) : interval(r);
        if (--left > 0) {
            t.putInt(TIMER, left);
            return;
        }
        t.putInt(TIMER, interval(r));
        try {
            send(player, null);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Skycraft society: hit squad failed", e);
        }
    }

    private static int interval(RandomSource r) {
        return 60 * SocietyConfig.between(SocietyConfig.SQUAD_MIN_MINUTES.get(), SocietyConfig.SQUAD_MAX_MINUTES.get(), r);
    }

    /**
     * Sends a squad after the player.
     *
     * @param forced a faction id to send regardless of reputation and chance (admin command), or null to roll
     * @return the faction that sent it, or null
     */
    @Nullable
    public static String send(ServerPlayer player, @Nullable String forced) {
        ServerLevel level = player.level();
        if (player.isCreative() || player.isSpectator() || !Crimes.canCommitCrime(player)) return null;
        if (level.dimension() != Level.OVERWORLD || level.getDifficulty() == Difficulty.PEACEFUL) return null;
        if (forced == null && Encounters.hunted(player)) return null;

        String faction = forced;
        if (faction == null) {
            int threshold = SocietyConfig.HOSTILE_THRESHOLD.get();
            int worst = 0;
            for (String f : Reputation.FACTIONS) {
                int rep = Reputation.get(player, f);
                if (rep > threshold || rep >= worst || roleFor(f) == null) continue;
                if (f.equals(Reputation.THIEVES_GUILD) && rep > -60) continue;
                worst = rep;
                faction = f;
            }
            if (faction == null) return null;
            float chance = Math.min(0.9f, (-worst - 30) / 70f + 0.1f);
            if (player.getRandom().nextFloat() >= chance) return null;
        }
        NpcRole role = roleFor(faction);
        if (role == null) return null;
        if (role == NpcRole.VAMPIRE && !Npcs.isNight(level) && forced == null) return null; // they come at night

        BlockPos center = Encounters.findSpawn(level, player, 40, 60, true);
        if (center == null) return null;
        Encounters.Group g = new Encounters.Group(level, player, center);
        RandomSource r = player.getRandom();
        int count = switch (role) {
            case ASSASSIN -> 1 + r.nextInt(2);
            case THUG -> 2 + r.nextInt(2);
            default -> 2 + r.nextInt(3);
        };
        boolean bandits = faction.equals(Reputation.BANDITS) && Encounters.exists(Encounters.BANDIT);
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            BlockPos p = Encounters.near(level, center, 3, r);
            if (bandits) {
                Mob bandit = g.mob(Encounters.BANDIT, p);
                if (bandit != null) {
                    Encounters.tagHunt(bandit, player.getUUID());
                    spawned++;
                }
                continue;
            }
            NpcEntity npc = g.npc(role, p);
            if (npc == null) continue;
            npc.setHuntTarget(player.getUUID());
            spawned++;
        }
        if (spawned > 0) {
            Skycraft.LOGGER.debug("Skycraft society: {} hit squad ({}) sent after {}", faction, spawned, player.getGameProfile().name());
            return faction;
        }
        return null;
    }
}
