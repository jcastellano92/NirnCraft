package com.nirncraft.quest.story;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Scripted moments: narrated scenes (lines spoken by quest NPCs, dragons or the narrator, one after another in the
 * chat) and choreography (a dragon circling over Helgen, then flying off). Everything is transient: a scene that
 * is interrupted by a restart simply isn't finished, the quest itself never waits for it.
 */
public final class Scenes {
    private record Pending(List<UUID> audience, Component line, long at) {
    }

    private record Circle(UUID entity, ServerLevel level, Vec3 center, double radius, double height, long start, long end) {
    }

    private static final List<Pending> LINES = new ArrayList<>();
    private static final List<Circle> CIRCLES = new ArrayList<>();

    private Scenes() {}

    private record Flyby(UUID entity, ServerLevel level, Vec3 from, Vec3 to, long start, long end) {
    }

    private static final List<Flyby> FLYBYS = new ArrayList<>();

    /** Whether a scripted opening (narration, flyby beats) or story scene is still playing for this player. */
    public static boolean playing(UUID player) {
        for (Beat b : INTRO) if (player.equals(b.player())) return true;
        for (Pending p : LINES) if (p.audience().contains(player)) return true;
        return false;
    }

    /**
     * A dragon crosses low over {@code p}: the path passes ~18 blocks to the side and ~22 up, 150 blocks in 7 s, so it
     * is inside entity tracking range and plainly visible. Roar, camera shake and fleeing NPCs are timed by
     * {@link #tick}; the entity is removed when it has crossed.
     */
    public static void flyby(ServerPlayer p, String entityType) {
        ServerLevel level = p.level();
        Entity e = com.nirncraft.quest.QuestSpawner.create(level, entityType);
        if (e == null) return;
        double ang = level.getRandom().nextDouble() * Math.PI * 2;
        Vec3 dir = new Vec3(Math.cos(ang), 0, Math.sin(ang));
        Vec3 side = new Vec3(-dir.z, 0, dir.x).scale(18);
        Vec3 c = p.position().add(side).add(0, 22, 0);
        Vec3 from = c.subtract(dir.scale(75));
        Vec3 to = c.add(dir.scale(75));
        if (e instanceof Mob mob) mob.setNoAi(true);
        e.setPermanentlyInvulnerable(true);
        e.setSilent(true);
        e.setNoGravity(true);
        float yaw = (float) Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90f;
        e.snapTo(from.x, from.y, from.z, yaw, 0f);
        if (!level.addFreshEntity(e)) return;
        long now = level.getGameTime();
        FLYBYS.add(new Flyby(e.getUUID(), level, from, to, now, now + 20 * 7));
        // the far roar as it appears, the near roar and the shake as it passes (see tick)
        com.nirncraft.magic.MagicFx.soundToPlayer(p, com.nirncraft.audio.AudioSounds.dragon("alduin", "roar"), SoundSource.HOSTILE, 1.4f, 0.8f);
        shake(p, 8);
        INTRO.add(new Beat(p.getUUID(), now + 20 * 3, 10));
        INTRO.add(new Beat(p.getUUID(), now + 20 * 7 + 10, 11));
    }

    // ---------------------------------------------------------------- the opening (once per player)

    private record Beat(UUID player, long at, int step) {
    }

    private static final List<Beat> INTRO = new ArrayList<>();
    private static final int NARRATION_LINES = 4;
    private static final int LINE_TICKS = 90;

    /**
     * The opening: fade from black, four narration lines (title overlay, repeated in chat), the gate guard's line and
     * a pointer to Riverwood; 10-20 s later the dragon flyby ({@link #flyby}). Per player, nothing blocks the quest.
     */
    public static void intro(ServerPlayer p, String entityType) {
        long now = p.level().getGameTime();
        // v2 (Tamriel worlds): the cottage opening is a per-player state machine, see IntroFlow; this action only
        // marks it played. The legacy road-side opening below stays for worlds without a cottage site.
        if (StartCottage.site(p.level()) != null) return;
        int fade = LINE_TICKS * NARRATION_LINES;
        p.addEffect(new net.minecraft.world.effect.MobEffectInstance(com.nirncraft.platform.Effects.holder(net.minecraft.world.effect.MobEffects.BLINDNESS), 70, 0, false, false, false));
        p.addEffect(new net.minecraft.world.effect.MobEffectInstance(com.nirncraft.platform.Effects.holder(net.minecraft.world.effect.MobEffects.SLOWNESS), fade, 6, false, false, false));
        for (int i = 0; i < NARRATION_LINES; i++) INTRO.add(new Beat(p.getUUID(), now + 20 + (long) i * LINE_TICKS, i));
        long after = now + 20 + fade;
        INTRO.add(new Beat(p.getUUID(), after, 4));
        // 10-20 s after the narration ends
        INTRO.add(new Beat(p.getUUID(), after + 20 * (10 + p.getRandom().nextInt(11)), 5));
        introType = entityType;
    }

    private static String introType = "nirncraft:alduin";

    /** One burning point of the town: where smoke columns and fire rise (never real fire blocks: nothing is griefed). */
    private record Burn(double x, double y, double z) {
    }

    private static final double[][] BURN_OFFSETS = {{22, -22}, {-26, -26}, {30, 30}, {-14, -10}, {-30, -8}, {-12, -30}, {4, -44}, {-6, 20}, {14, 8}, {-40, 14}};

    private static List<Burn> smolder;
    private static long smolderSeed;

    /** Helgen keeps smoking for anyone who comes near, long after the attack (only particles, nothing burns). */
    private static void tickSmolder(MinecraftServer server) {
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        if (now % 20 != 7 || level.players().isEmpty() || !com.nirncraft.quest.QuestConfig.RUINED_HELGEN.get()) return;
        int[] h = StartCottage.helgen();
        List<ServerPlayer> near = new ArrayList<>();
        for (ServerPlayer pl : level.players()) {
            double dx = pl.getX() - h[0], dz = pl.getZ() - h[1];
            // nobody sees the town smoke before their own opening's attack
            if (dx * dx + dz * dz < 190 * 190 && !IntroFlow.beforeAttack(pl)) near.add(pl);
        }
        if (near.isEmpty() || !com.nirncraft.tamriel.terrain.TamrielSpawn.isTamriel(level)) return;
        if (smolder == null || smolderSeed != level.getSeed()) {
            smolder = new ArrayList<>();
            smolderSeed = level.getSeed();
            for (int k = 0; k < BURN_OFFSETS.length; k += 2) {
                int x = h[0] + (int) BURN_OFFSETS[k][0], z = h[1] + (int) BURN_OFFSETS[k][1];
                smolder.add(new Burn(x + 0.5, StartCottage.groundY(level, x, z), z + 0.5));
            }
        }
        for (Burn b : smolder) {
            for (ServerPlayer w : near) {
                level.sendParticles(w, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, false, b.x(), b.y() + 1, b.z(), 0, 0, 1, 0, 0.08);
                level.sendParticles(w, ParticleTypes.LARGE_SMOKE, true, false, b.x(), b.y() + 2, b.z(), 1, 1.0, 0.5, 1.0, 0.01);
                if (level.getRandom().nextInt(3) == 0) level.sendParticles(w, ParticleTypes.FLAME, true, false, b.x(), b.y() + 1, b.z(), 2, 0.8, 0.3, 0.8, 0.01);
            }
        }
    }

    /** Ambient smoke over the ruined town for whoever is near (the attack itself is the client cutscene, see IntroFlow). */
    private static void tickAttacks(MinecraftServer server) {
        tickSmolder(server);
    }

    /** A short camera shake: vanilla hurt-tilt packets (no damage), one every 3 ticks. */
    private static void shake(ServerPlayer p, int pulses) {
        long now = p.level().getGameTime();
        for (int i = 0; i < pulses; i++) INTRO.add(new Beat(p.getUUID(), now + (long) i * 3, 20));
    }

    private static void beat(MinecraftServer server, ServerPlayer p, int step) {
        switch (step) {
            case 0, 1, 2, 3 -> {
                Component line = Component.translatable("quest.nirncraft.intro." + step).withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY);
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(12, LINE_TICKS - 30, 18));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.empty()));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(line));
            }
            case 4 -> {
                com.nirncraft.core.Notifier.message(p, Component.translatable("quest.nirncraft.intro.guard").withStyle(ChatFormatting.WHITE));
                com.nirncraft.core.Notifier.message(p, Component.translatable("quest.nirncraft.intro.pointer").withStyle(ChatFormatting.YELLOW));
                p.removeEffect(com.nirncraft.platform.Effects.holder(net.minecraft.world.effect.MobEffects.SLOWNESS));
            }
            case 5 -> flyby(p, introType);
            case 10 -> {
                p.level().playSound(null, p.getX(), p.getY() + 20, p.getZ(),
                        com.nirncraft.audio.AudioSounds.dragon("alduin", "roar"), SoundSource.HOSTILE, 4f, 0.9f);
                p.level().playSound(null, p.getX(), p.getY() + 20, p.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 2f, 0.7f);
                shake(p, 14);
                scare(p);
            }
            case 11 -> {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 50, 20));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                        Component.translatable("quest.nirncraft.intro.sighted").withStyle(ChatFormatting.GOLD)));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                        Component.translatable("quest.nirncraft.intro.sighted_sub").withStyle(ChatFormatting.GRAY)));
                journal(server, p);
            }
            case 20 -> p.connection.send(new net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket(p));
            default -> {
            }
        }
    }

    /** NPCs within 40 blocks run from the player's spot (they are looking up at the thing, not at them). */
    private static void scare(ServerPlayer p) {
        for (com.nirncraft.society.entity.NpcEntity n : p.level().getEntitiesOfClass(
                com.nirncraft.society.entity.NpcEntity.class, p.getBoundingBox().inflate(40))) {
            Vec3 away = n.position().subtract(p.position());
            away = away.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : away.normalize();
            Vec3 goal = n.position().add(away.scale(14));
            n.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.3);
        }
    }

    /** The journal entry 'Dragon sighted' on the opening quest (if it is there and active). */
    static void journal(MinecraftServer server, ServerPlayer p) {
        com.nirncraft.quest.Quest q = com.nirncraft.quest.QuestStore.get(server)
                .find(com.nirncraft.quest.Quests.personalKey(p.getUUID()), "main_unbound");
        if (q == null) return;
        Story.addJournal(q, Component.translatable("quest.nirncraft.intro.journal"));
        com.nirncraft.quest.Quests.markPlayerDirty(p.getUUID());
        Story.setFlag(com.nirncraft.core.SkyData.get(p), "dragon_sighted", true);
    }

    /** Plays scene {@code id} to the given players. */
    public static void play(MinecraftServer server, List<ServerPlayer> audience, String id) {
        List<StoryData.SceneLine> lines = StoryData.scene(id);
        if (lines.isEmpty() || audience.isEmpty()) return;
        List<UUID> ids = new ArrayList<>();
        for (ServerPlayer p : audience) ids.add(p.getUUID());
        long t = server.overworld().getGameTime() + 10;
        for (StoryData.SceneLine l : lines) {
            Component text = l.text().c();
            Component line;
            if ("narrator".equals(l.speaker())) {
                line = text.copy().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
            } else {
                line = Component.empty().append(StoryData.speaker(l.speaker()).copy().withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(": ").withStyle(ChatFormatting.GOLD))
                        .append(text.copy().withStyle(ChatFormatting.WHITE));
            }
            LINES.add(new Pending(ids, line, t));
            t += Math.min(140, 30 + l.text().text().length() / 2);
        }
    }

    /** Flies {@code e} in circles around {@code center} for {@code ticks}, then removes it. */
    public static void circle(Entity e, Vec3 center, double radius, double height, int ticks) {
        if (!(e.level() instanceof ServerLevel level)) return;
        // never more than a few scenery dragons at once (their wing beats are what the player hears)
        if (CIRCLES.size() >= 3) {
            e.discard();
            return;
        }
        if (e instanceof Mob mob) mob.setNoAi(true);
        e.setPermanentlyInvulnerable(true);
        long now = level.getGameTime();
        CIRCLES.add(new Circle(e.getUUID(), level, center, radius, height, now, now + ticks));
        level.playSound(null, center.x, center.y, center.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 2.5f, 0.8f);
    }

    public static void tick(MinecraftServer server) {
        if (server.overworld() == null) return;
        long now = server.overworld().getGameTime();
        tickAttacks(server);
        if (!LINES.isEmpty()) {
            Iterator<Pending> it = LINES.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                if (p.at() > now) continue;
                it.remove();
                for (UUID id : p.audience()) {
                    ServerPlayer pl = server.getPlayerList().getPlayer(id);
                    if (pl != null) com.nirncraft.core.Notifier.message(pl, p.line());
                }
            }
        }
        if (!INTRO.isEmpty()) {
            Iterator<Beat> it = INTRO.iterator();
            List<Beat> due = new ArrayList<>();
            while (it.hasNext()) {
                Beat b = it.next();
                if (b.at() > now) continue;
                it.remove();
                due.add(b);
            }
            for (Beat b : due) {
                ServerPlayer pl = server.getPlayerList().getPlayer(b.player());
                if (pl != null && pl.isAlive()) beat(server, pl, b.step());
            }
        }
        if (!FLYBYS.isEmpty()) {
            Iterator<Flyby> it = FLYBYS.iterator();
            while (it.hasNext()) {
                Flyby f = it.next();
                Entity e = f.level().getEntity(f.entity());
                long t = f.level().getGameTime();
                if (e == null || !e.isAlive() || t >= f.end()) {
                    if (e != null) e.discard();
                    it.remove();
                    continue;
                }
                double k = (t - f.start()) / (double) (f.end() - f.start());
                Vec3 at = f.from().lerp(f.to(), k);
                e.snapTo(at.x, at.y, at.z, e.getYRot(), 0f);
            }
        }
        if (!CIRCLES.isEmpty()) {
            Iterator<Circle> it = CIRCLES.iterator();
            while (it.hasNext()) {
                Circle c = it.next();
                Entity e = c.level().getEntity(c.entity());
                if (e == null || !e.isAlive()) {
                    it.remove();
                    continue;
                }
                long t = c.level().getGameTime();
                if (t >= c.end()) {
                    // climbs away and is gone
                    c.level().sendParticles(ParticleTypes.LARGE_SMOKE, e.getX(), e.getY(), e.getZ(), 40, 2, 2, 2, 0.05);
                    e.discard();
                    it.remove();
                    continue;
                }
                double angle = (t - c.start()) * 0.035;
                double x = c.center().x + Math.cos(angle) * c.radius();
                double z = c.center().z + Math.sin(angle) * c.radius();
                double y = c.center().y + c.height() + Math.sin(angle * 2.3) * 4;
                double nx = c.center().x + Math.cos(angle + 0.05) * c.radius();
                double nz = c.center().z + Math.sin(angle + 0.05) * c.radius();
                float yaw = (float) Math.toDegrees(Math.atan2(nz - z, nx - x)) - 90f;
                e.snapTo(x, y, z, yaw, 0f);
                e.setYHeadRot(yaw);
                if ((t - c.start()) % 60 == 30) {
                    c.level().playSound(null, x, y, z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 1.2f, 0.7f);
                    c.level().sendParticles(ParticleTypes.FLAME, x, y - 2, z, 30, 1.5, 1.0, 1.5, 0.08);
                }
            }
        }
    }

    public static void reset() {
        LINES.clear();
        FLYBYS.clear();
        INTRO.clear();
        CIRCLES.clear();
        smolder = null;
        StartCottage.reset();
    }
}
