package com.skycraft.magic.client;

import com.skycraft.arsenal.ArsenalPackets;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicPackets;
import com.skycraft.magic.shout.Shout;
import com.skycraft.magic.spell.Element;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Client-side spell and shout visuals, driven by {@link MagicPackets.Fx} and {@link MagicPackets.ShoutFx}. */
public final class ClientFx {
    private static final List<Stream> STREAMS = new ArrayList<>();

    /** The last shout heard nearby, drawn by the HUD ("FUS RO DAH"). */
    public static String shoutText = "";
    public static long shoutTextStart;
    public static boolean shoutTextSelf;

    private ClientFx() {}

    private static final class Stream {
        final Vec3 from;
        final int target;
        final int duration;
        final Element element;
        int age;

        Stream(Vec3 from, int target, int duration, Element element) {
            this.from = from;
            this.target = target;
            this.duration = duration;
            this.element = element;
        }
    }

    // ------------------------------------------------------------------ packet handlers

    public static void handle(MagicPackets.Fx m) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Element el = Element.byOrdinal(m.element());
        Entity entity = m.entity() >= 0 ? level.getEntity(m.entity()) : null;
        switch (m.kind()) {
            case MagicFx.BEAM -> beam(level, el, m.a(), m.b(), m.extra() == 1);
            case MagicFx.BURST -> burst(level, el, m.a(), m.extra() / 10f);
            case MagicFx.ARC -> arc(level, m.a(), m.b(), m.extra() == 1 ? 3 : 1);
            case MagicFx.STREAM -> STREAMS.add(new Stream(m.a(), m.entity(), Math.max(1, m.extra()), el));
            case MagicFx.RING -> ring(level, el, m.a(), m.extra() / 10f);
            case MagicFx.CHARGE -> {
                if (entity != null) charge(level, el, entity, m.extra() / 100f);
            }
            case MagicFx.HEAL -> {
                if (entity != null) heal(level, entity, m.extra());
            }
            case MagicFx.SUMMON -> summon(level, el, m.a(), Math.max(1f, m.extra() / 10f));
            case MagicFx.WARD -> {
                if (entity != null) ward(level, entity, m.extra() == 1);
            }
            case MagicFx.LIGHTNING -> lightning(level, m.a());
            case MagicFx.TOTEM -> {
                if (entity != null) mc.particleEngine.createTrackingEmitter(entity, ParticleTypes.TOTEM_OF_UNDYING, 30);
            }
            case MagicFx.AURA -> {
                if (entity != null) aura(level, el, entity, m.extra());
            }
            case MagicFx.STAFF -> staff(level, m.extra(), m.a(), m.b());
            default -> {
            }
        }
    }

    public static void handleShout(MagicPackets.ShoutFx m) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Entity e = level.getEntity(m.entity());
        Shout shout = Shout.byOrdinal(m.shout());
        int words = Mth.clamp(m.words(), 1, 3);
        shoutText = shout.phrase(words);
        shoutTextStart = Util.getMillis();
        shoutTextSelf = e == mc.player;
        if (e != null) shoutParticles(level, e, shout, words);
    }

    /** Animated streams (dragon souls, word walls, soul trap). Called every client tick. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            STREAMS.clear();
            return;
        }
        if (mc.isPaused()) return;
        for (Iterator<Stream> it = STREAMS.iterator(); it.hasNext(); ) {
            Stream s = it.next();
            Entity target = level.getEntity(s.target);
            if (target == null || ++s.age > s.duration) {
                it.remove();
                continue;
            }
            stream(level, s, target);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final RandomSource R = RandomSource.create();

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    private static double j(double amount) {
        return (R.nextDouble() - 0.5) * 2 * amount;
    }

    private static ParticleOptions dust(int rgb, float scale) {
        return new DustParticleOptions(new Vector3f(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f), scale);
    }

    private static ParticleOptions elementParticle(Element el) {
        return switch (el) {
            case FIRE -> ParticleTypes.FLAME;
            case FROST -> ParticleTypes.SNOWFLAKE;
            case SHOCK -> ParticleTypes.ELECTRIC_SPARK;
            case HOLY -> dust(0xFFE08A, 1.2f);
            case SOUL -> ParticleTypes.SOUL_FIRE_FLAME;
            case MIND -> ParticleTypes.WITCH;
            case CONJURE -> ParticleTypes.REVERSE_PORTAL;
            case NATURE -> dust(0x8FD06A, 1.1f);
            case ARCANE -> ParticleTypes.END_ROD;
            default -> ParticleTypes.ENCHANT;
        };
    }

    private static int elementColor(Element el) {
        return switch (el) {
            case FIRE -> 0xFF8A30;
            case FROST -> 0xBFE8FF;
            case SHOCK -> 0xC8D8FF;
            case HOLY -> 0xFFE08A;
            case SOUL -> 0x60E0E8;
            case MIND -> 0xA070FF;
            case CONJURE -> 0xB070F0;
            case NATURE -> 0x8FD06A;
            case ARCANE -> 0x90F0C0;
            default -> 0xFFFFFF;
        };
    }

    // ------------------------------------------------------------------ effects

    private static void beam(ClientLevel level, Element el, Vec3 a, Vec3 b, boolean impact) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 0.01) return;
        Vec3 dir = d.scale(1 / len);
        switch (el) {
            case FIRE -> {
                for (int i = 0; i < 14; i++) {
                    double t = R.nextDouble() * 0.3;
                    Vec3 p = a.add(d.scale(t));
                    double speed = len * 0.06 + R.nextDouble() * 0.15;
                    level.addParticle(ParticleTypes.FLAME, p.x + j(0.05), p.y + j(0.05), p.z + j(0.05),
                            dir.x * speed + j(0.03), dir.y * speed + j(0.03), dir.z * speed + j(0.03));
                }
                for (int i = 0; i < 4; i++) {
                    Vec3 p = a.add(d.scale(R.nextDouble()));
                    level.addParticle(ParticleTypes.SMALL_FLAME, p.x + j(0.15), p.y + j(0.15), p.z + j(0.15), dir.x * 0.1, 0.02, dir.z * 0.1);
                }
                if (R.nextInt(3) == 0) level.addParticle(ParticleTypes.SMOKE, b.x, b.y, b.z, 0, 0.05, 0);
                if (impact) {
                    for (int i = 0; i < 4; i++) level.addParticle(ParticleTypes.FLAME, b.x + j(0.3), b.y + j(0.3), b.z + j(0.3), j(0.06), 0.05, j(0.06));
                    if (R.nextInt(4) == 0) level.addParticle(ParticleTypes.LAVA, b.x, b.y, b.z, 0, 0, 0);
                }
            }
            case FROST -> {
                for (int i = 0; i < 12; i++) {
                    double t = R.nextDouble() * 0.35;
                    Vec3 p = a.add(d.scale(t));
                    double speed = len * 0.055 + R.nextDouble() * 0.12;
                    level.addParticle(ParticleTypes.SNOWFLAKE, p.x + j(0.05), p.y + j(0.05), p.z + j(0.05),
                            dir.x * speed + j(0.03), dir.y * speed + j(0.03), dir.z * speed + j(0.03));
                }
                for (int i = 0; i < 6; i++) {
                    Vec3 p = a.add(d.scale(R.nextDouble()));
                    level.addParticle(dust(0xCFEFFF, 0.9f), p.x + j(0.12), p.y + j(0.12), p.z + j(0.12), 0, 0, 0);
                }
                if (impact) {
                    for (int i = 0; i < 4; i++) level.addParticle(ParticleTypes.ITEM_SNOWBALL, b.x + j(0.3), b.y + j(0.3), b.z + j(0.3), 0, 0, 0);
                    if (R.nextInt(3) == 0) level.addParticle(ParticleTypes.CLOUD, b.x, b.y, b.z, j(0.02), 0.02, j(0.02));
                }
            }
            case SHOCK -> {
                arc(level, a, b, 1);
                if (impact) for (int i = 0; i < 5; i++) level.addParticle(ParticleTypes.ELECTRIC_SPARK, b.x, b.y, b.z, j(0.4), j(0.4), j(0.4));
            }
            case HOLY -> {
                int n = (int) (len * 2.5);
                for (int i = 0; i < n; i++) {
                    Vec3 p = a.add(d.scale(R.nextDouble()));
                    level.addParticle(dust(0xFFE08A, 1.0f), p.x + j(0.06), p.y + j(0.06), p.z + j(0.06), 0, 0, 0);
                }
                if (R.nextInt(2) == 0) {
                    Vec3 p = a.add(d.scale(R.nextDouble()));
                    level.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, dir.x * 0.05, dir.y * 0.05, dir.z * 0.05);
                }
            }
            default -> {
                int n = (int) (len * 2);
                ParticleOptions particle = elementParticle(el);
                for (int i = 0; i < n; i++) {
                    Vec3 p = a.add(d.scale(R.nextDouble()));
                    level.addParticle(particle, p.x, p.y, p.z, 0, 0, 0);
                }
            }
        }
    }

    /** A jagged lightning arc between two points. */
    private static void arc(ClientLevel level, Vec3 a, Vec3 b, int strands) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 0.01) return;
        int segments = Math.max(3, (int) (len / 0.8));
        for (int s = 0; s < strands; s++) {
            Vec3 prev = a;
            for (int i = 1; i <= segments; i++) {
                double t = i / (double) segments;
                double wiggle = i == segments ? 0 : Math.min(0.6, len * 0.06);
                Vec3 next = a.add(d.scale(t)).add(j(wiggle), j(wiggle), j(wiggle));
                Vec3 seg = next.subtract(prev);
                int steps = Math.max(1, (int) (seg.length() / 0.25));
                for (int k = 0; k < steps; k++) {
                    Vec3 p = prev.add(seg.scale(k / (double) steps));
                    level.addParticle(dust(0xDDE6FF, 0.7f), p.x, p.y, p.z, 0, 0, 0);
                }
                if (R.nextInt(2) == 0) level.addParticle(ParticleTypes.ELECTRIC_SPARK, next.x, next.y, next.z, j(0.05), j(0.05), j(0.05));
                prev = next;
            }
        }
    }

    private static void burst(ClientLevel level, Element el, Vec3 c, float radius) {
        int n = (int) Mth.clamp(18 * radius * radius, 12, 160);
        double speed = 0.08 + radius * 0.06;
        for (int i = 0; i < n; i++) {
            Vec3 v = new Vec3(j(1), j(1), j(1));
            if (v.lengthSqr() > 1 || v.lengthSqr() < 1e-3) v = new Vec3(j(1), 0.3, j(1));
            v = v.normalize().scale(speed * (0.4 + R.nextDouble() * 0.6));
            switch (el) {
                case FIRE -> {
                    level.addParticle(ParticleTypes.FLAME, c.x, c.y, c.z, v.x, v.y, v.z);
                    if (i % 6 == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, v.x * 0.5, v.y * 0.5, v.z * 0.5);
                    if (i % 10 == 0) level.addParticle(ParticleTypes.LAVA, c.x, c.y, c.z, 0, 0, 0);
                }
                case FROST -> {
                    level.addParticle(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, v.x, v.y, v.z);
                    if (i % 3 == 0) level.addParticle(ParticleTypes.ITEM_SNOWBALL, c.x + v.x * 4, c.y + v.y * 4, c.z + v.z * 4, 0, 0, 0);
                    if (i % 8 == 0) level.addParticle(ParticleTypes.CLOUD, c.x, c.y, c.z, v.x * 0.4, v.y * 0.4, v.z * 0.4);
                }
                case SHOCK -> {
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, v.x * 3, v.y * 3, v.z * 3);
                    if (i % 4 == 0) level.addParticle(dust(0xDDE6FF, 1.2f), c.x + v.x * 5, c.y + v.y * 5, c.z + v.z * 5, 0, 0, 0);
                }
                case NONE -> {
                    level.addParticle(ParticleTypes.WHITE_ASH, c.x + v.x * 4, c.y + v.y * 4, c.z + v.z * 4, v.x, v.y, v.z);
                    if (i % 3 == 0) level.addParticle(ParticleTypes.POOF, c.x, c.y, c.z, v.x * 0.5, v.y * 0.5, v.z * 0.5);
                }
                default -> {
                    level.addParticle(elementParticle(el), c.x, c.y, c.z, v.x, v.y, v.z);
                    if (i % 3 == 0) level.addParticle(dust(elementColor(el), 1.2f), c.x + v.x * 4, c.y + v.y * 4, c.z + v.z * 4, 0, 0, 0);
                }
            }
        }
        if (el == Element.FIRE && radius >= 2.5f) {
            level.addParticle(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 0, 0, 0);
        } else if (radius >= 1f) {
            level.addParticle(ParticleTypes.FLASH, c.x, c.y, c.z, 0, 0, 0);
        }
    }

    private static void ring(ClientLevel level, Element el, Vec3 c, float radius) {
        int n = (int) Mth.clamp(radius * 10, 16, 140);
        for (int i = 0; i < n; i++) {
            double a = i / (double) n * Math.PI * 2 + R.nextDouble() * 0.1;
            double x = c.x + Math.cos(a) * radius, z = c.z + Math.sin(a) * radius;
            switch (el) {
                case FROST -> {
                    double tx = -Math.sin(a) * 0.25, tz = Math.cos(a) * 0.25;
                    level.addParticle(ParticleTypes.SNOWFLAKE, x, c.y + R.nextDouble() * 2.5, z, tx, -0.02, tz);
                    if (i % 4 == 0) {
                        double r2 = R.nextDouble() * radius;
                        level.addParticle(ParticleTypes.SNOWFLAKE, c.x + Math.cos(a) * r2, c.y + 3 + R.nextDouble(), c.z + Math.sin(a) * r2, 0, -0.15, 0);
                    }
                    if (i % 10 == 0) level.addParticle(ParticleTypes.CLOUD, x, c.y + 1.5, z, tx * 0.5, 0, tz * 0.5);
                }
                case FIRE -> {
                    level.addParticle(ParticleTypes.FLAME, c.x + Math.cos(a) * 0.5, c.y, c.z + Math.sin(a) * 0.5,
                            Math.cos(a) * radius * 0.09, 0.02, Math.sin(a) * radius * 0.09);
                    if (i % 5 == 0) level.addParticle(ParticleTypes.LAVA, x, c.y, z, 0, 0, 0);
                }
                case HOLY -> {
                    level.addParticle(dust(0xFFE08A, 1.3f), x, c.y + 0.05, z, 0, 0.0, 0);
                    if (i % 6 == 0) level.addParticle(ParticleTypes.END_ROD, x, c.y + 0.1, z, 0, 0.04, 0);
                }
                default -> level.addParticle(elementParticle(el), x, c.y + 0.1, z, 0, 0.03, 0);
            }
        }
    }

    private static void charge(ClientLevel level, Element el, Entity e, float progress) {
        double r = 1.4 - progress * 1.0;
        double y = e.getY() + e.getBbHeight() * 0.6;
        float t = (e.tickCount % 40) / 40f * (float) Math.PI * 2;
        for (int i = 0; i < 4; i++) {
            double a = t + i * Math.PI / 2;
            double x = e.getX() + Math.cos(a) * r, z = e.getZ() + Math.sin(a) * r;
            level.addParticle(elementParticle(el), x, y + j(0.3), z, (e.getX() - x) * 0.05, 0.01, (e.getZ() - z) * 0.05);
        }
        level.addParticle(dust(elementColor(el), 0.8f + progress), e.getX() + j(0.4), y + j(0.4), e.getZ() + j(0.4), 0, 0, 0);
    }

    private static void heal(ClientLevel level, Entity e, int strength) {
        int n = 6 + strength * 5;
        for (int i = 0; i < n; i++) {
            double a = R.nextDouble() * Math.PI * 2;
            double r = e.getBbWidth() * 0.7;
            level.addParticle(dust(0xFFE08A, 1.0f), e.getX() + Math.cos(a) * r, e.getY() + R.nextDouble() * e.getBbHeight(),
                    e.getZ() + Math.sin(a) * r, 0, 0.05, 0);
            if (i % 4 == 0) level.addParticle(ParticleTypes.END_ROD, e.getX() + Math.cos(a) * r, e.getY() + 0.1, e.getZ() + Math.sin(a) * r, 0, 0.06, 0);
        }
        if (strength >= 2) level.addParticle(ParticleTypes.HEART, e.getX(), e.getY() + e.getBbHeight() + 0.3, e.getZ(), 0, 0, 0);
    }

    private static void summon(ClientLevel level, Element el, Vec3 base, float height) {
        for (int i = 0; i < 50; i++) {
            double a = R.nextDouble() * Math.PI * 2;
            double r = 0.3 + R.nextDouble() * 0.7;
            double y = base.y + R.nextDouble() * height;
            level.addParticle(ParticleTypes.REVERSE_PORTAL, base.x + Math.cos(a) * r, y, base.z + Math.sin(a) * r, 0, 0.02, 0);
            if (i % 2 == 0) level.addParticle(elementParticle(el), base.x + Math.cos(a) * r, y, base.z + Math.sin(a) * r, 0, 0.03, 0);
            if (i % 5 == 0) level.addParticle(ParticleTypes.SOUL, base.x + j(0.6), base.y + 0.1, base.z + j(0.6), 0, 0.05, 0);
        }
        level.addParticle(ParticleTypes.FLASH, base.x, base.y + height / 2, base.z, 0, 0, 0);
    }

    private static void ward(ClientLevel level, Entity e, boolean flash) {
        Vec3 look = e.getViewVector(1f);
        Vec3 center = e.getEyePosition().add(look.scale(0.9)).add(0, -0.2, 0);
        Vec3 up = Math.abs(look.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 side = look.cross(up).normalize();
        Vec3 vert = side.cross(look).normalize();
        int n = flash ? 40 : 16;
        for (int i = 0; i < n; i++) {
            double a = R.nextDouble() * Math.PI * 2;
            double r = flash ? 0.9 : 0.75 + R.nextDouble() * 0.15;
            Vec3 p = center.add(side.scale(Math.cos(a) * r)).add(vert.scale(Math.sin(a) * r));
            level.addParticle(dust(flash ? 0xFFFFFF : 0xBFE0FF, flash ? 1.2f : 0.7f), p.x, p.y, p.z, 0, 0, 0);
        }
        if (flash) level.addParticle(ParticleTypes.ENCHANTED_HIT, center.x, center.y, center.z, look.x * 0.3, look.y * 0.3, look.z * 0.3);
    }

    private static void lightning(ClientLevel level, Vec3 ground) {
        Vec3 top = ground.add(j(3), 24, j(3));
        arc(level, top, ground, 2);
        for (int i = 0; i < 20; i++) level.addParticle(ParticleTypes.ELECTRIC_SPARK, ground.x, ground.y + 0.2, ground.z, j(0.8), R.nextDouble() * 0.6, j(0.8));
        level.addParticle(ParticleTypes.FLASH, ground.x, ground.y + 1, ground.z, 0, 0, 0);
    }

    private static void aura(ClientLevel level, Element el, Entity e, int strength) {
        int n = 10 + strength * 8;
        ParticleOptions particle = elementParticle(el);
        float t = e.tickCount * 0.3f;
        for (int i = 0; i < n; i++) {
            double h = i / (double) n;
            double a = t + h * Math.PI * 6;
            double r = e.getBbWidth() * 0.8 + 0.2;
            double x = e.getX() + Math.cos(a) * r, z = e.getZ() + Math.sin(a) * r;
            level.addParticle(particle, x, e.getY() + h * e.getBbHeight() * 1.1, z, 0, 0.02, 0);
            if (i % 3 == 0) level.addParticle(dust(elementColor(el), 1f), x, e.getY() + h * e.getBbHeight(), z, 0, 0, 0);
        }
    }

    private static void stream(ClientLevel level, Stream s, Entity target) {
        Vec3 to = target.position().add(0, target.getBbHeight() * 0.6, 0);
        Vec3 from = s.from;
        float progress = s.age / (float) s.duration;
        if (s.element == Element.ARCANE) {
            // Word Wall: glyphs drift out of the wall into the player.
            for (int i = 0; i < 6; i++) {
                Vec3 off = from.subtract(to).add(j(0.6), j(0.6), j(0.6));
                level.addParticle(ParticleTypes.ENCHANT, to.x, to.y, to.z, off.x, off.y, off.z);
            }
            Vec3 p = lerp(from, to, R.nextDouble());
            level.addParticle(dust(0x9FE8FF, 1.2f), p.x + j(0.2), p.y + j(0.2), p.z + j(0.2), 0, 0, 0);
            if (s.age % 4 == 0) level.addParticle(ParticleTypes.END_ROD, from.x + j(0.5), from.y + j(0.5), from.z + j(0.5),
                    (to.x - from.x) * 0.06, (to.y - from.y) * 0.06, (to.z - from.z) * 0.06);
            return;
        }
        // Dragon soul / soul trap: several glowing strands arcing from the corpse into the player.
        double dist = from.distanceTo(to);
        int strands = s.duration > 40 ? 4 : 2;
        for (int k = 0; k < strands; k++) {
            for (int i = 0; i < 5; i++) {
                double f = ((s.age * 0.035) + i * 0.2 + k * 0.05) % 1.0;
                Vec3 p = lerp(from, to, f);
                double arch = Math.sin(f * Math.PI) * Math.min(6, dist * 0.25);
                double twist = (k / (double) strands) * Math.PI * 2 + s.age * 0.2;
                double tw = Math.sin(f * Math.PI) * 0.8;
                p = p.add(Math.cos(twist) * tw, arch, Math.sin(twist) * tw);
                level.addParticle(k % 2 == 0 ? ParticleTypes.SOUL_FIRE_FLAME : dust(0xFFC870, 1.5f), p.x, p.y, p.z, 0, 0, 0);
            }
        }
        if (progress > 0.2f && R.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.END_ROD, to.x + j(0.5), to.y + j(0.8), to.z + j(0.5), 0, 0.02, 0);
        }
        if (target instanceof LivingEntity && s.age == s.duration - 1) {
            for (int i = 0; i < 30; i++) level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, to.x, to.y, to.z, j(0.3), j(0.3), j(0.3));
        }
    }

    // ------------------------------------------------------------------ shouts

    private static void shoutParticles(ClientLevel level, Entity e, Shout shout, int words) {
        Vec3 eye = e.getEyePosition();
        Vec3 look = e.getViewVector(1f);
        Vec3 mouth = eye.add(look.scale(0.6)).add(0, -0.1, 0);
        switch (shout) {
            case UNRELENTING_FORCE -> {
                double range = 6 + 3 * words;
                for (double d = 1.5; d <= range; d += 2.2) {
                    Vec3 p = eye.add(look.scale(d));
                    level.addParticle(ParticleTypes.SONIC_BOOM, p.x, p.y, p.z, 0, 0, 0);
                }
                cone(level, mouth, look, range, 40 * words, ParticleTypes.CLOUD, 0.6);
            }
            case FIRE_BREATH -> {
                cone(level, mouth, look, 7 + 2 * words, 70 * words, ParticleTypes.FLAME, 0.45);
                cone(level, mouth, look, 5 + 2 * words, 10 * words, ParticleTypes.LAVA, 0.3);
            }
            case FROST_BREATH -> {
                cone(level, mouth, look, 7 + 2 * words, 70 * words, ParticleTypes.SNOWFLAKE, 0.45);
                cone(level, mouth, look, 5 + 2 * words, 15 * words, ParticleTypes.CLOUD, 0.35);
            }
            case WHIRLWIND_SPRINT -> {
                for (int i = 0; i < 30; i++) level.addParticle(ParticleTypes.CLOUD, e.getX() + j(0.5), e.getY() + R.nextDouble() * 1.8,
                        e.getZ() + j(0.5), -look.x * 0.4, 0, -look.z * 0.4);
            }
            case BECOME_ETHEREAL -> {
                for (int i = 0; i < 60; i++) {
                    double a = i * 0.3;
                    level.addParticle(ParticleTypes.END_ROD, e.getX() + Math.cos(a) * 0.8, e.getY() + i * 0.035, e.getZ() + Math.sin(a) * 0.8, 0, 0.01, 0);
                }
            }
            case CLEAR_SKIES -> {
                for (int i = 0; i < 40; i++) level.addParticle(ParticleTypes.CLOUD, mouth.x + j(0.3), mouth.y, mouth.z + j(0.3), j(0.05), 0.4 + R.nextDouble() * 0.3, j(0.05));
                for (int i = 0; i < 20; i++) level.addParticle(ParticleTypes.END_ROD, e.getX() + j(1), e.getY() + 2, e.getZ() + j(1), 0, 0.5, 0);
            }
            case AURA_WHISPER -> ring(level, Element.ARCANE, e.position().add(0, 1, 0), 4f);
            case SLOW_TIME -> {
                for (int i = 0; i < 120; i++) {
                    Vec3 v = new Vec3(j(1), j(1), j(1)).normalize().scale(4 + R.nextDouble() * 6);
                    level.addParticle(ParticleTypes.REVERSE_PORTAL, e.getX() + v.x, e.getY() + 1 + v.y * 0.5, e.getZ() + v.z, 0, 0, 0);
                }
            }
            case MARKED_FOR_DEATH -> cone(level, mouth, look, 14, 50 * words, dust(0x8A0A0A, 1.4f), 0.25);
            case DISARM -> cone(level, mouth, look, 9, 30 * words, ParticleTypes.CRIT, 0.4);
            case ELEMENTAL_FURY -> {
                for (int i = 0; i < 40; i++) level.addParticle(ParticleTypes.ELECTRIC_SPARK, e.getX() + j(0.6), e.getY() + 0.6 + R.nextDouble(), e.getZ() + j(0.6), j(0.1), j(0.1), j(0.1));
                aura(level, Element.SHOCK, e, 3);
            }
            case KYNES_PEACE -> {
                ring(level, Element.NATURE, e.position().add(0, 0.2, 0), 5f);
                for (int i = 0; i < 20; i++) level.addParticle(ParticleTypes.HAPPY_VILLAGER, e.getX() + j(4), e.getY() + R.nextDouble() * 2, e.getZ() + j(4), 0, 0, 0);
            }
            case ANIMAL_ALLEGIANCE -> {
                ring(level, Element.NATURE, e.position().add(0, 0.2, 0), 6f);
                for (int i = 0; i < 8; i++) level.addParticle(ParticleTypes.HEART, e.getX() + j(3), e.getY() + 1 + R.nextDouble(), e.getZ() + j(3), 0, 0, 0);
            }
            case STORM_CALL -> {
                for (int i = 0; i < 50; i++) level.addParticle(ParticleTypes.CLOUD, mouth.x + j(0.4), mouth.y, mouth.z + j(0.4), j(0.1), 0.6 + R.nextDouble() * 0.4, j(0.1));
                arc(level, eye.add(0, 1, 0), eye.add(j(2), 14, j(2)), 2);
            }
            case DRAGONREND -> {
                for (double d = 1; d <= 30; d += 0.4) {
                    Vec3 p = eye.add(look.scale(d));
                    level.addParticle(d % 2 < 0.4 ? ParticleTypes.SOUL_FIRE_FLAME : dust(0x8A40C0, 1.6f), p.x + j(0.2), p.y + j(0.2), p.z + j(0.2), 0, 0, 0);
                }
            }
        }
        // The shout itself: a burst of air at the mouth.
        for (int i = 0; i < 6 * words; i++) {
            level.addParticle(ParticleTypes.POOF, mouth.x, mouth.y, mouth.z, look.x * 0.3 + j(0.08), look.y * 0.3 + j(0.08), look.z * 0.3 + j(0.08));
        }
    }

    private static void cone(ClientLevel level, Vec3 origin, Vec3 look, double range, int count, ParticleOptions particle, double spread) {
        for (int i = 0; i < count; i++) {
            Vec3 dir = look.add(j(spread), j(spread), j(spread)).normalize();
            double speed = range * (0.05 + R.nextDouble() * 0.04);
            level.addParticle(particle, origin.x, origin.y, origin.z, dir.x * speed, dir.y * speed, dir.z * speed);
        }
    }

    // ------------------------------------------------------------------ staves and artifacts (arsenal)

    private static DustParticleOptions dust(float r, float g, float b, float s) {
        return new DustParticleOptions(new Vector3f(r, g, b), s);
    }

    /** Staff/artifact visuals, {@code kind} from {@code ArsenalPackets.Fx}. */
    private static void staff(ClientLevel level, int kind, Vec3 a, Vec3 b) {
        RandomSource r = level.random;
        switch (kind) {
            case ArsenalPackets.Fx.FIRE_STREAM -> staffStream(level, r, a, b, ParticleTypes.FLAME, ParticleTypes.SMOKE);
            case ArsenalPackets.Fx.FROST_STREAM -> staffStream(level, r, a, b, ParticleTypes.SNOWFLAKE, dust(0.75f, 0.9f, 1f, 1.2f));
            case ArsenalPackets.Fx.SHOCK_STREAM -> {
                staffStream(level, r, a, b, ParticleTypes.ELECTRIC_SPARK, dust(0.7f, 0.8f, 1f, 0.9f));
                staffArc(level, r, a, b, dust(0.85f, 0.9f, 1f, 0.8f), 0.35);
            }
            case ArsenalPackets.Fx.HEAL_BEAM -> staffBeam(level, r, a, b, dust(1f, 0.92f, 0.55f, 1.1f), ParticleTypes.HAPPY_VILLAGER);
            case ArsenalPackets.Fx.MAGNUS_BEAM -> staffBeam(level, r, a, b, dust(0.45f, 0.6f, 1f, 1.3f), ParticleTypes.ENCHANT);
            case ArsenalPackets.Fx.LIGHTNING_ARC -> {
                staffArc(level, r, a, b, dust(0.8f, 0.85f, 1f, 1.2f), 0.6);
                staffArc(level, r, a, b, ParticleTypes.ELECTRIC_SPARK, 0.5);
            }
            case ArsenalPackets.Fx.FIRE_BURST -> staffBurst(level, r, a, ParticleTypes.FLAME, 60, 0.35);
            case ArsenalPackets.Fx.SUN_BURST -> {
                staffBurst(level, r, a, dust(1f, 0.95f, 0.6f, 1.6f), 50, 0.3);
                staffBurst(level, r, a, ParticleTypes.END_ROD, 25, 0.2);
                level.addParticle(ParticleTypes.FLASH, a.x, a.y, a.z, 0, 0, 0);
            }
            case ArsenalPackets.Fx.FROST_BURST -> staffBurst(level, r, a, ParticleTypes.SNOWFLAKE, 20, 0.15);
            case ArsenalPackets.Fx.WARD -> staffWard(level, r, a, b);
            case ArsenalPackets.Fx.SOUL_BURST -> staffBurst(level, r, a, ParticleTypes.SOUL, 20, 0.08);
            default -> {
            }
        }
    }

    /** A cone of particles flying from a to b (flames, frostbite, sparks). */
    private static void staffStream(ClientLevel level, RandomSource r, Vec3 a, Vec3 b, ParticleOptions main, ParticleOptions extra) {
        Vec3 d = b.subtract(a);
        double len = Math.max(0.5, d.length());
        Vec3 dir = d.scale(1 / len);
        for (int i = 0; i < 10; i++) {
            double speed = 0.35 + r.nextDouble() * 0.35;
            double spread = 0.08;
            level.addParticle(main, a.x, a.y, a.z,
                    (dir.x + (r.nextDouble() - 0.5) * spread * 2) * speed * len / 4,
                    (dir.y + (r.nextDouble() - 0.5) * spread * 2) * speed * len / 4,
                    (dir.z + (r.nextDouble() - 0.5) * spread * 2) * speed * len / 4);
        }
        for (int i = 0; i < 3; i++) {
            double f = r.nextDouble();
            level.addParticle(extra, a.x + d.x * f, a.y + d.y * f, a.z + d.z * f, 0, 0.01, 0);
        }
    }

    /** A straight line with a sparkle at the end (healing, Magnus). */
    private static void staffBeam(ClientLevel level, RandomSource r, Vec3 a, Vec3 b, ParticleOptions line, ParticleOptions end) {
        Vec3 d = b.subtract(a);
        int n = Math.max(2, (int) (d.length() * 4));
        for (int i = 0; i <= n; i++) {
            double f = i / (double) n;
            level.addParticle(line, a.x + d.x * f + (r.nextDouble() - 0.5) * 0.05, a.y + d.y * f + (r.nextDouble() - 0.5) * 0.05,
                    a.z + d.z * f + (r.nextDouble() - 0.5) * 0.05, 0, 0, 0);
        }
        for (int i = 0; i < 3; i++) level.addParticle(end, b.x + (r.nextDouble() - 0.5) * 0.4, b.y + (r.nextDouble() - 0.5) * 0.4,
                b.z + (r.nextDouble() - 0.5) * 0.4, 0, 0, 0);
    }

    /** A jagged lightning line. */
    private static void staffArc(ClientLevel level, RandomSource r, Vec3 a, Vec3 b, ParticleOptions p, double jag) {
        Vec3 d = b.subtract(a);
        int n = Math.max(3, (int) (d.length() * 3));
        Vec3 prev = a;
        for (int i = 1; i <= n; i++) {
            double f = i / (double) n;
            Vec3 next = i == n ? b : a.add(d.scale(f)).add((r.nextDouble() - 0.5) * jag, (r.nextDouble() - 0.5) * jag, (r.nextDouble() - 0.5) * jag);
            for (int k = 0; k < 3; k++) {
                double t = k / 3.0;
                level.addParticle(p, prev.x + (next.x - prev.x) * t, prev.y + (next.y - prev.y) * t, prev.z + (next.z - prev.z) * t, 0, 0, 0);
            }
            prev = next;
        }
    }

    private static void staffBurst(ClientLevel level, RandomSource r, Vec3 c, ParticleOptions p, int count, double speed) {
        for (int i = 0; i < count; i++) {
            double x = r.nextGaussian(), y = r.nextGaussian(), z = r.nextGaussian();
            double l = Math.sqrt(x * x + y * y + z * z) + 1e-6;
            double s = speed * (0.5 + r.nextDouble() * 0.5);
            level.addParticle(p, c.x, c.y, c.z, x / l * s, y / l * s, z / l * s);
        }
    }

    /** A disc of light in front of the blocker ({@code normal} = facing). */
    private static void staffWard(ClientLevel level, RandomSource r, Vec3 c, Vec3 normal) {
        Vec3 n = normal.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : normal.normalize();
        Vec3 u = n.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1e-6) u = new Vec3(1, 0, 0);
        u = u.normalize();
        Vec3 v = u.cross(n).normalize();
        for (int i = 0; i < 40; i++) {
            double ang = i / 40.0 * Math.PI * 2;
            double rad = 0.9;
            Vec3 p = c.add(u.scale(Math.cos(ang) * rad)).add(v.scale(Math.sin(ang) * rad));
            level.addParticle(dust(0.6f, 0.75f, 1f, 1f), p.x, p.y, p.z, 0, 0, 0);
        }
        for (int i = 0; i < 12; i++) {
            level.addParticle(ParticleTypes.ENCHANTED_HIT, c.x + (r.nextDouble() - 0.5), c.y + (r.nextDouble() - 0.5), c.z + (r.nextDouble() - 0.5),
                    n.x * 0.2, n.y * 0.2, n.z * 0.2);
        }
    }
}
