package com.skycraft.crime;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * People who are not under arrest in a jail (docs/JAIL.md "Visitors", "Talking", "Jailbreak").
 *
 * <ul>
 *     <li><b>Visitors</b> walk into a jail's front hall without penalty. Beyond it (the guard room's desks, the gate,
 *     the cell block) is for guards: a guard who sees a visitor there asks them to leave, and a visitor who stays
 *     past the grace period is trespassing ({@link Bounty#TRESPASS} through {@link Crimes#report}, which is only
 *     added if someone sees it).</li>
 *     <li><b>Talking</b> through the bars: the grate window by every cell door leaves the line open both ways (the
 *     server lets a player interact with an entity he has no line of sight to within 3 blocks anyway); NPC prisoners
 *     talk like any NPC and give a rumour.</li>
 *     <li><b>Jailbreak</b>: opening a cell door (picked, a guard's key, or already open) frees what is in the cell. A
 *     player prisoner is simply out: the existing guard re-arrest and escape rules apply to them. A rescuer who is
 *     seen by a guard gets an aiding-escape bounty and the prisoner a longer sentence; nobody seeing means nothing is
 *     reported.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
final class JailVisitors {
    private JailVisitors() {}

    /** Bounty for aiding an escape that a guard saw. */
    static final int AIDING_ESCAPE = 150;
    /** Seconds added to a freed prisoner's sentence when their rescuer was seen. */
    static final int FREED_SENTENCE_SECONDS = 60;
    /** Ticks a visitor has to leave once a guard told them. */
    static final long GRACE_TICKS = 100;
    /** Blocks of the front hall (from the front door) that anyone may stand in. */
    static final int HALL_DEPTH = 3;

    /** Game time each player was told to leave. */
    private static final Map<UUID, Long> WARNED = new HashMap<>();

    enum Zone { OUTSIDE, HALL, RESTRICTED }

    enum Offence { NONE, WARN, TRESPASS }

    // ------------------------------------------------------------------ the rules (pure)

    /** Which part of a jail a point is in, from its distance behind the front door. */
    static Zone zoneFor(boolean insideJail, double alongFromFront) {
        if (!insideJail || alongFromFront < -0.5) return Zone.OUTSIDE;
        return alongFromFront < HALL_DEPTH ? Zone.HALL : Zone.RESTRICTED;
    }

    /**
     * What a visitor's standing where they are comes to: nothing in the hall or unseen; a warning the first time a
     * guard sees them in the restricted part; trespass once the grace has run out. {@code warnedAt} is -1 when not told.
     */
    static Offence visitorStep(Zone zone, boolean witnessed, long warnedAt, long now) {
        if (zone != Zone.RESTRICTED || !witnessed) return Offence.NONE;
        if (warnedAt < 0) return Offence.WARN;
        return now - warnedAt >= GRACE_TICKS ? Offence.TRESPASS : Offence.NONE;
    }

    /** The bounty a rescuer earns: only when a guard saw. */
    static int rescueBounty(boolean witnessed) {
        return witnessed ? AIDING_ESCAPE : 0;
    }

    // ------------------------------------------------------------------ visitors

    /** The zone of a position in a hold jail of the overworld. */
    static Zone zoneAt(ServerLevel level, double x, double y, double z) {
        if (level.dimension() != Level.OVERWORLD) return Zone.OUTSIDE;
        var near = SettlementAnchors.near(level, BlockPos.containing(x, y, z), 0);
        if (near.isEmpty() || near.get().first(AnchorType.JAIL_CELL) == null) return Zone.OUTSIDE;
        JailPatrol.Route r = JailPatrol.routeOf(level, near.get());
        if (r == null) return Zone.OUTSIDE;
        boolean inside = r.bounds().contains(x, y, z);
        double along = (x - (r.frontDoor().getX() + 0.5)) * r.ux() + (z - (r.frontDoor().getZ() + 0.5)) * r.uz();
        return zoneFor(inside, along);
    }

    /** Every second, for every player: a free player in a jail's restricted part. */
    static void tick(ServerPlayer player) {
        if (player.isCreative() || player.isSpectator() || Jail.isJailed(player) || Jail.isLoadingJail(player)) return;
        ServerLevel level = player.serverLevel();
        Zone zone = zoneAt(level, player.getX(), player.getY(), player.getZ());
        long now = level.getGameTime();
        if (zone != Zone.RESTRICTED) {
            WARNED.remove(player.getUUID());
            return;
        }
        LivingEntity guard = Crimes.findGuardWitness(player);
        Long warned = WARNED.get(player.getUUID());
        switch (visitorStep(zone, guard != null, warned == null ? -1 : warned, now)) {
            case WARN -> {
                WARNED.put(player.getUUID(), now);
                if (guard instanceof Mob mob) {
                    JailPatrol.busy(mob, 200);
                    mob.getNavigation().moveTo(player, 1.0);
                    mob.getLookControl().setLookAt(player, 30f, 30f);
                }
                Notifier.message(player, Component.translatable("crime.skycraft.jail.visitor_warn", guard.getDisplayName()));
            }
            case TRESPASS -> {
                WARNED.put(player.getUUID(), now);
                Crimes.report(player, player.blockPosition(), Bounty.TRESPASS, true, null);
                Notifier.message(player, Component.translatable("crime.skycraft.jail.visitor_trespass"));
            }
            default -> {
            }
        }
    }

    static void forget(UUID player) {
        WARNED.remove(player);
    }

    // ------------------------------------------------------------------ jailbreak

    /** The cell index of the door at {@code pos} (lower half) in the hold jail around it, or -1. */
    static int cellOfDoor(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof DoorBlock) || level.dimension() != Level.OVERWORLD) return -1;
        var near = SettlementAnchors.near(level, pos, 0);
        if (near.isEmpty()) return -1;
        List<Anchor> doors = near.get().anchors(AnchorType.JAIL_DOOR);
        for (int i = 0; i < doors.size(); i++) if (doors.get(i).pos().equals(pos)) return i;
        return -1;
    }

    /**
     * A free player opened a cell door (picked it, used a guard's key, or it was open): what is in the cell is free.
     * Called on the server from the interact handler and the lockpick's success.
     */
    static void freed(ServerPlayer rescuer, ServerLevel level, BlockPos doorPos) {
        if (rescuer.isCreative() || rescuer.isSpectator() || Jail.isJailed(rescuer)) return;
        BlockPos pos = Locks.normalizePos(level, doorPos);
        int cell = cellOfDoor(level, pos);
        if (cell < 0) return;
        var near = SettlementAnchors.near(level, pos, 0);
        if (near.isEmpty()) return;
        SettlementAnchors.Entry e = near.get();
        boolean helped = false;
        // a player prisoner of that cell
        Jail.JailData jd = Jail.JailData.get(level.getServer());
        UUID inside = jd.occupied.get(e.key + "#" + cell);
        ServerPlayer prisoner = inside == null ? null : level.getServer().getPlayerList().getPlayer(inside);
        boolean witnessed = Crimes.findGuardWitness(rescuer) != null;
        if (prisoner != null && !prisoner.getUUID().equals(rescuer.getUUID()) && Jail.isJailed(prisoner)) {
            helped = true;
            CompoundTag j = Bounty.state(prisoner).getCompound("jail");
            j.putBoolean("freed", true);
            if (witnessed) j.putInt("remaining", Math.min(Jail.MAX_SECONDS, j.getInt("remaining") + FREED_SENTENCE_SECONDS));
            SkyData.get(prisoner).markDirty();
            Notifier.message(prisoner, Component.translatable("crime.skycraft.jail.freed_by", rescuer.getDisplayName()));
            Notifier.message(rescuer, Component.translatable("crime.skycraft.jail.freed_player", prisoner.getDisplayName()));
        }
        // an NPC prisoner of that cell slips away
        List<Anchor> stands = e.anchors(AnchorType.JAIL_CELL);
        if (cell < stands.size()) {
            AABB cellBox = new AABB(stands.get(cell).pos()).inflate(2.5, 1.5, 2.5);
            for (LivingEntity npc : level.getEntitiesOfClass(LivingEntity.class, cellBox,
                    n -> n.isAlive() && !(n instanceof Player) && !Crimes.isGuard(n) && Crimes.isCivilian(n))) {
                helped = true;
                Notifier.message(rescuer, Component.translatable("crime.skycraft.jail.freed_npc", npc.getDisplayName()));
                npc.discard();
            }
        }
        if (!helped) return;
        Bounty.increment(rescuer, "jailbreaks", 1);
        int bounty = rescueBounty(witnessed);
        if (bounty > 0) {
            Crimes.report(rescuer, pos, bounty, true, null);
            Notifier.message(rescuer, Component.translatable("crime.skycraft.jail.aiding_seen"));
        }
    }

    // ------------------------------------------------------------------ talking through the bars

    /** Prisoners (anyone but a guard standing in a jail) give a rumour like any NPC. */
    static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (Crimes.isGuard(npc) || npc instanceof Player || !Jail.isJailArea(npc.level(), npc.blockPosition())) return;
        if (!Crimes.isCivilian(npc)) return;
        out.add(new DialogueOption("crime.prisoner.rumor", Component.translatable("dialogue.skycraft.crime.prisoner_rumor"), 300,
                (p, n) -> Dialogue.open(p, n, Component.translatable("crime.skycraft.prisoner.rumor." + p.getRandom().nextInt(6)))));
        out.add(new DialogueOption("crime.prisoner.why", Component.translatable("dialogue.skycraft.crime.prisoner_why"), 310,
                (p, n) -> Dialogue.open(p, n, Component.translatable("crime.skycraft.prisoner.why." + p.getRandom().nextInt(4)))));
    }
}
