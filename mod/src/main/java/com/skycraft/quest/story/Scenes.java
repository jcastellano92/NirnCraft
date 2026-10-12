package com.skycraft.quest.story;

import net.minecraft.ChatFormatting;
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

    /**
     * One quiet, distant crossing: a dragon passes {@code p} at ~100 blocks and 50 up, once, with a single soft roar
     * (to that player only, never repeating) and no ambient sound. Removed when it has crossed.
     */
    public static void flyby(ServerPlayer p, String entityType) {
        ServerLevel level = p.serverLevel();
        Entity e = com.skycraft.quest.QuestSpawner.create(level, entityType);
        if (e == null) return;
        double side = level.random.nextBoolean() ? 1 : -1;
        Vec3 c = p.position();
        Vec3 from = new Vec3(c.x - 130 * side, c.y + 55, c.z - 90);
        Vec3 to = new Vec3(c.x + 130 * side, c.y + 60, c.z - 90);
        if (e instanceof Mob mob) mob.setNoAi(true);
        e.setInvulnerable(true);
        e.setSilent(true);
        e.setNoGravity(true);
        float yaw = (float) Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90f;
        e.moveTo(from.x, from.y, from.z, yaw, 0f);
        if (!level.addFreshEntity(e)) return;
        long now = level.getGameTime();
        FLYBYS.add(new Flyby(e.getUUID(), level, from, to, now, now + 20 * 22));
        p.playNotifySound(SoundEvents.ENDER_DRAGON_GROWL, SoundSource.AMBIENT, 0.5f, 0.75f);
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
        e.setInvulnerable(true);
        long now = level.getGameTime();
        CIRCLES.add(new Circle(e.getUUID(), level, center, radius, height, now, now + ticks));
        level.playSound(null, center.x, center.y, center.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 2.5f, 0.8f);
    }

    public static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (!LINES.isEmpty()) {
            Iterator<Pending> it = LINES.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                if (p.at() > now) continue;
                it.remove();
                for (UUID id : p.audience()) {
                    ServerPlayer pl = server.getPlayerList().getPlayer(id);
                    if (pl != null) pl.sendSystemMessage(p.line());
                }
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
                e.moveTo(at.x, at.y, at.z, e.getYRot(), 0f);
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
                e.moveTo(x, y, z, yaw, 0f);
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
        CIRCLES.clear();
    }
}
