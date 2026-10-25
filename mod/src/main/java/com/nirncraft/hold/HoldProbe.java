package com.nirncraft.hold;

import com.mojang.authlib.GameProfile;
import com.nirncraft.NirnCraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live half of the hold's tests ({@code -Dnirncraft.hold.probe=true}, harness scene hold-never-ready): while a REAL
 * player is held, a husk (hostile, does not burn) is placed beside them and a stand-in is the second player. After ten
 * seconds it logs "Hold probe: PASS" or "Hold probe: FAIL (reason)": the husk never targeted or hurt the player, the
 * player's health and position did not change, nobody else is sent the player, and the hold was still on.
 */
final class HoldProbe {
    static final boolean ON = Boolean.getBoolean("nirncraft.hold.probe");
    private static final int SPAWN_AT = 40, JUDGE_AT = 240;
    private static final Map<UUID, Run> RUNS = new ConcurrentHashMap<>();

    private static final class Run {
        Mob mob;
        float health;
        double x, y, z;
        boolean aggro;
        net.minecraft.world.Difficulty difficulty;
    }

    private HoldProbe() {}

    /** Called each server tick of a held real player with the ticks held so far. */
    static void tick(ServerPlayer p, long held) {
        if (!ON || p.connection == null) return;
        ServerLevel level = p.level();
        Run r = RUNS.get(p.getUUID());
        if (held == SPAWN_AT && r == null) {
            r = new Run();
            r.difficulty = level.getServer().getWorldData().getDifficulty();
            level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);  // (a peaceful world would remove the husk at once)
            r.health = p.getHealth();
            r.x = p.getX();
            r.y = p.getY();
            r.z = p.getZ();
            Mob m = (Mob) net.minecraft.world.entity.EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
            if (m == null) {
                NirnCraft.LOGGER.error("Hold probe: FAIL (could not make a husk)");
                return;
            }
            m.setPos(r.x + 1.5, r.y, r.z);
            m.setPersistenceRequired();
            level.addFreshEntity(m);
            r.mob = m;
            RUNS.put(p.getUUID(), r);
            NirnCraft.LOGGER.info("Hold probe: a husk stands beside the held player at {} {} {}", r.x, r.y, r.z);
            return;
        }
        if (r == null || r.mob == null) return;
        if (r.mob.getTarget() == p) r.aggro = true;
        if (held == JUDGE_AT) {
            ServerPlayer viewer = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("hold-probe-viewer".getBytes()), "[hold-probe-viewer]"));
            StringBuilder why = new StringBuilder();
            if (!PlayerHold.isHeld(p)) why.append("not held; ");
            if (r.aggro) why.append("the husk targeted the player; ");
            if (p.getHealth() < r.health) why.append("health ").append(r.health).append(" -> ").append(p.getHealth()).append("; ");
            if (p.distanceToSqr(r.x, r.y, r.z) > 0.01) why.append("moved to ").append(p.position()).append("; ");
            if (p.broadcastToPlayer(viewer)) why.append("another player is sent this player; ");
            if (!p.isInvisible() || !p.isSpectator()) why.append("not invisible and spectator; ");
            NirnCraft.LOGGER.info("Hold probe: {}", why.length() == 0
                    ? "PASS (husk beside the held player for " + (JUDGE_AT - SPAWN_AT) + " ticks: no target, no damage, not sent to another player, not moved)"
                    : "FAIL (" + why + ")");
            r.mob.discard();
            if (r.difficulty != null) level.getServer().setDifficulty(r.difficulty, true);
            RUNS.remove(p.getUUID());
        }
    }
}
