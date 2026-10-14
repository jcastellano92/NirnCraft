package com.skycraft.mounts;

import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.perk.Perks;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How an individual mount lives its stats: where they are kept, how they change the animal, and what grows, steadies
 * or damages them.
 *
 * <p><b>Where.</b> The player's mount record holds the stats of every mount they own ({@code mstats.<kind>}, the source
 * of truth: a mount is rebuilt from it when it is called, brought out or loaded); the entity carries a copy
 * ({@link MountStats#TAG}) so a mount in the world needs no lookup. Anything that changes the stats goes through
 * {@link #commit}, which writes both and re-applies the attributes.</p>
 *
 * <p><b>Growth</b> is accumulated on the server a tick at a time and paid out once a second ({@link #ride}): distance
 * (a gallop is worth three times a walk), jumps, hauling a load, blows struck and taken. Everything is a handful of
 * float additions a tick.</p>
 */
public final class MountGrowth {
    public static final String RECORDS = "mstats";
    private static final UUID SPEED_ID = UUID.fromString("7a0e5f10-3b1c-4a77-8c0d-61a7d2b9e101");
    private static final UUID HEALTH_ID = UUID.fromString("7a0e5f10-3b1c-4a77-8c0d-61a7d2b9e102");
    private static final UUID JUMP_ID = UUID.fromString("7a0e5f10-3b1c-4a77-8c0d-61a7d2b9e103");
    /** Experience a second is capped at this (a farm for it would be a long walk). */
    private static final float MAX_XP_PER_SECOND = 2.5f;
    private static final long FEED_COOLDOWN = 600;

    private static final Map<UUID, Acc> ACC = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ABUSE = new ConcurrentHashMap<>();

    private MountGrowth() {}

    /** A rider's distance and jumps since the last payout. */
    private static final class Acc {
        float walk, gallop;
        int jumps, moveTicks;
        float blows, hits;
        long lastBlow, lastHit;
    }

    // ------------------------------------------------------------------ the record

    /** The recorded stats of a kind the player owns, or null. */
    @Nullable
    public static MountStats recorded(Player p, MountKind kind) {
        CompoundTag all = Mounts.data(p).getCompound(RECORDS);
        return all.contains(kind.id, 10) ? MountStats.fromTag(kind, all.getCompound(kind.id)) : null;
    }

    public static void record(Player p, MountKind kind, MountStats s) {
        CompoundTag tag = Mounts.data(p);
        CompoundTag all = tag.getCompound(RECORDS);
        all.put(kind.id, s.toTag());
        tag.put(RECORDS, all);
        SkyData.get(p).markDirty();
    }

    public static void forget(Player p, MountKind kind) {
        CompoundTag tag = Mounts.data(p);
        if (tag.getCompound(RECORDS).contains(kind.id)) {
            CompoundTag all = tag.getCompound(RECORDS);
            all.remove(kind.id);
            tag.put(RECORDS, all);
        }
    }

    /**
     * The stats of the player's active mount: its record (the entity may be far away or unloaded). A mount from a save
     * that predates individuals has none yet: its stats are what its UUID rolls (the same ones the entity computes), and
     * they are recorded from here on.
     */
    @Nullable
    public static MountStats active(Player p) {
        MountKind k = Mounts.active(p);
        if (k == null) return null;
        MountStats s = recorded(p, k);
        CompoundTag tag = Mounts.data(p);
        if (s == null && tag.hasUUID("uuid")) {
            s = MountStats.roll(k, MountStats.seedOf(tag.getUUID("uuid"), k), 0f, false);
            record(p, k, s);
        }
        return s;
    }

    /** Stats of a mount kind the player owns (the active one may be derived, see {@link #active}). */
    @Nullable
    public static MountStats known(Player p, MountKind kind) {
        return kind == Mounts.active(p) ? active(p) : recorded(p, kind);
    }

    /** Binds a mount that is about to join the world to its owner's record, rolling (and recording) a new animal. */
    static MountStats bind(ServerPlayer owner, Mob mob, MountKind kind) {
        MountStats s = recorded(owner, kind);
        if (s == null) {
            s = MountStats.roll(kind, MountStats.seedOf(mob.getUUID(), kind), 0f, false);
            record(owner, kind, s);
        }
        MountStats.store(mob, s);
        applyAttributes(mob, kind);
        return s;
    }

    /** A mount of the record loading from disk takes the record's numbers (training may have happened while it slept). */
    static void syncFromRecord(Mob mob) {
        MountKind kind = Mounts.kindOf(mob);
        UUID owner = Mounts.ownerOf(mob);
        if (kind == null || owner == null || mob.level().getServer() == null) return;
        ServerPlayer p = mob.level().getServer().getPlayerList().getPlayer(owner);
        if (p == null || !Mounts.isActive(p, mob)) return;
        MountStats rec = recorded(p, kind);
        if (rec != null) MountStats.store(mob, rec);
    }

    /** Writes changed stats to the entity and the owner's record, and re-applies what they do to the animal. */
    static void commit(Mob mob, MountKind kind, MountStats s, @Nullable ServerPlayer owner, boolean attributes) {
        MountStats.store(mob, s);
        if (owner != null && Mounts.isActive(owner, mob)) record(owner, kind, s);
        if (attributes) applyAttributes(mob, kind);
    }

    // ------------------------------------------------------------------ what the stats do

    /** Speed, health and jump of the animal from its stats (stamina and carry are read where they are used). */
    public static void applyAttributes(Mob mob, MountKind kind) {
        if (mob.level().isClientSide) return;
        MountStats s = MountStats.of(mob, kind);
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            MountGear.setModifier(speed, SPEED_ID, "Individual speed", s.speedMult() - 1.0, AttributeModifier.Operation.MULTIPLY_TOTAL);
        }
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) {
            float ratio = mob.getMaxHealth() <= 0 ? 1f : mob.getHealth() / mob.getMaxHealth();
            if (MountGear.setModifier(hp, HEALTH_ID, "Individual health", s.mult(MountProfile.HEALTH) - 1.0, AttributeModifier.Operation.MULTIPLY_BASE)) {
                mob.setHealth(Math.max(1f, Math.min(mob.getMaxHealth(), ratio * mob.getMaxHealth())));
            }
        }
        if (mob instanceof MountBeast b) b.setJumpMult(s.mult(MountProfile.JUMP));
        AttributeInstance jump = mob.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null) {
            MountGear.setModifier(jump, JUMP_ID, "Individual jump", s.mult(MountProfile.JUMP) - 1.0, AttributeModifier.Operation.MULTIPLY_TOTAL);
        }
    }

    /** Multiplier of a rolled stat for a mount in the world. */
    public static float mult(Entity mount, int stat) {
        MountKind kind = Mounts.kindOf(mount);
        return kind == null ? 1f : MountStats.of(mount, kind).mult(stat);
    }

    /** The online owner of a mount, or null. */
    @Nullable
    static ServerPlayer ownerPlayer(Entity mount) {
        UUID o = Mounts.ownerOf(mount);
        if (o == null || mount.level().getServer() == null) return null;
        return mount.level().getServer().getPlayerList().getPlayer(o);
    }

    // ------------------------------------------------------------------ perks (Riding)

    public static int rank(@Nullable Player p, String perk) {
        return p == null ? 0 : Perks.rank(p, "riding." + perk);
    }

    /** Pack Master: +10% carry and saddlebag capacity a rank. */
    public static float packMult(@Nullable Player p) {
        return 1f + 0.10f * rank(p, "pack_master");
    }

    /** Endurance: the gallop burns 12% less a rank. */
    public static float drainMult(@Nullable Player p) {
        return 1f - 0.12f * rank(p, "endurance");
    }

    /** Trick Rider: +6% mounted blows and 15% cheaper jumps a rank. */
    public static float trickBlow(@Nullable Player p) {
        return 0.06f * rank(p, "trick_rider");
    }

    public static float trickJumpCost(@Nullable Player p) {
        return 1f - 0.15f * rank(p, "trick_rider");
    }

    // ------------------------------------------------------------------ riding

    /**
     * Called every tick the owner steers a mount: collects distance and jumps and, once a second, pays them out as
     * experience, training and a little trust.
     */
    static void ride(ServerPlayer p, LivingEntity mount, MountKind kind, double speed, boolean gallop, boolean jumped) {
        if (!(mount instanceof Mob mob) || !Mounts.isOwner(p, mount)) return;
        Acc a = ACC.computeIfAbsent(p.getUUID(), k -> new Acc());
        boolean moving = speed > 0.08;
        if (moving) {
            a.moveTicks++;
            if (gallop) a.gallop += (float) speed;
            else a.walk += (float) speed;
        }
        if (jumped) a.jumps++;
        if (p.tickCount % 20 != 0) return;

        // pay out the second: what was ridden, jumped, hauled and fought is experience and training
        float walk = a.walk / 48f, gal = a.gallop / 16f, jumps = a.jumps * 1.5f, blows = a.blows, hits = a.hits, load = 0f;
        boolean moved = a.moveTicks > 0;
        if (a.moveTicks > 10) {
            float cap = MountGear.bagCapacity(mob, kind);
            if (cap > 0 && Saddlebags.load(p) >= cap * 0.2f) load = 0.1f * a.moveTicks / 20f;
        }
        a.walk = a.gallop = 0f;
        a.jumps = a.moveTicks = 0;
        a.blows = a.hits = 0f;
        float total = walk + gal + jumps + load + blows + hits;
        if (total <= 0.0001f && !moved) return;

        MountStats s = MountStats.of(mob, kind);
        float scale = total > MAX_XP_PER_SECOND ? MAX_XP_PER_SECOND / total : 1f;
        s.credit(MountProfile.SPEED, (gal * 0.6f + blows * 0.5f) * scale);
        s.credit(MountProfile.STAMINA, (gal * 0.4f + walk + load * 0.3f + blows * 0.5f) * scale);
        s.credit(MountProfile.JUMP, jumps * scale);
        s.credit(MountProfile.CARRY, load * 0.7f * scale);
        s.credit(MountProfile.HEALTH, hits * scale);
        if (moved) s.addTrust(1f / 360f); // a point in about six minutes of riding
        int[] grew = s.addXp(total * scale);
        commit(mob, kind, s, p, grew.length > 0);
        for (int stat : grew) levelUp(p, mob, kind, s, stat);
    }

    private static void levelUp(ServerPlayer p, Mob mob, MountKind kind, MountStats s, int stat) {
        Component name = mob.hasCustomName() ? mob.getCustomName() : kind.displayName();
        if (stat >= 0) {
            Notifier.message(p, Component.translatable("mounts.skycraft.levelup", name, s.level(),
                    Component.translatable(MountProfile.of(kind).labelKey(stat))));
        } else {
            Notifier.message(p, Component.translatable("mounts.skycraft.levelup.plain", name, s.level()));
        }
        if (mob.level() instanceof ServerLevel sl) {
            sl.playSound(null, mob.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.5f, 1.5f);
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getY() + mob.getBbHeight() * 0.8, mob.getZ(), 6, 0.4, 0.3, 0.4, 0.02);
        }
    }

    /** A blow struck from the saddle, or one the mount took (at most one of each counts a second). */
    static void struck(ServerPlayer p, LivingEntity mount, boolean tookHit, float amount) {
        if (!Mounts.isOwner(p, mount)) return;
        Acc a = ACC.computeIfAbsent(p.getUUID(), k -> new Acc());
        long now = p.level().getGameTime();
        if (tookHit) {
            if (now - a.lastHit < 20) return;
            a.lastHit = now;
            a.hits += 0.8f + Math.min(amount, 8f) * 0.2f;
        } else {
            if (now - a.lastBlow < 20) return;
            a.lastBlow = now;
            a.blows += 1f;
        }
    }

    public static void forgetRider(ServerPlayer p) {
        ACC.remove(p.getUUID());
    }

    // ------------------------------------------------------------------ care and neglect

    /** Every ten seconds for every player: an owned mount left hurt and far away loses trust. */
    static void care(ServerPlayer p) {
        Entity e = Mounts.activeEntity(p);
        if (!(e instanceof Mob m) || m.isVehicle()) return;
        MountKind kind = Mounts.kindOf(m);
        if (kind == null || kind.unique()) return;
        if (m.getHealth() >= m.getMaxHealth() * 0.35f || m.distanceToSqr(p) < 16 * 16) return;
        MountStats s = MountStats.of(m, kind);
        if (s.trust <= 0f) return;
        s.addTrust(-0.5f);
        commit(m, kind, s, p, false);
    }

    /** The owner swung at their own mount (the blow itself never lands): it flinches and trusts a little less. */
    static void abuse(ServerPlayer p, LivingEntity mount) {
        MountKind kind = Mounts.kindOf(mount);
        if (kind == null || !(mount instanceof Mob mob)) return;
        long now = p.level().getGameTime();
        Long last = ABUSE.get(mount.getUUID());
        if (last != null && now - last < 100) return;
        ABUSE.put(mount.getUUID(), now);
        MountStats s = MountStats.of(mob, kind);
        s.addTrust(-3f);
        commit(mob, kind, s, p, true);
        Notifier.message(p, Component.translatable("mounts.skycraft.flinch", kind.displayName()));
        if (mob.level() instanceof ServerLevel sl) {
            sl.playSound(null, mob.blockPosition(), SoundEvents.HORSE_ANGRY, SoundSource.NEUTRAL, 0.7f, 1.2f);
        }
    }

    /** A night in a stable's care: a mount left there for a day is rested, and trusts its owner the more for it. */
    public static void stableRest(ServerPlayer p, MountKind kind) {
        MountStats s = recorded(p, kind);
        if (s == null || s.stabledAt < 0) return;
        long now = p.level().getGameTime();
        boolean rested = now - s.stabledAt >= 24000L;
        s.stabledAt = -1;
        if (rested) {
            s.addTrust(6f);
            Notifier.message(p, Component.translatable("mounts.skycraft.rested", kind.displayName()));
        }
        record(p, kind, s);
    }

    /** Marks a mount of the record as left at a stable now. */
    static void leftAtStable(ServerPlayer p, MountKind kind) {
        MountStats s = recorded(p, kind);
        if (s == null) return;
        s.stabledAt = p.level().getGameTime();
        record(p, kind, s);
    }

    // ------------------------------------------------------------------ feeding

    /** Trust a feeding gives, 0 for something that is no feed. */
    static float feedTrust(ItemStack stack) {
        if (stack.is(Items.GOLDEN_CARROT) || stack.is(Items.GOLDEN_APPLE)) return 8f;
        if (stack.is(Items.CARROT) || stack.is(Items.APPLE) || stack.is(Items.SUGAR)) return 4f;
        if (stack.is(Items.HAY_BLOCK)) return 3f;
        if (stack.is(Items.WHEAT)) return 2f;
        return 0f;
    }

    /**
     * Feeds a mount from the hand: trust rises (more with a wild one that is not used to a hand), it heals a little,
     * and it will not take more than one feed in half a minute. True when the item was a feed (handled).
     */
    static boolean feed(ServerPlayer p, Mob mount, MountKind kind, ItemStack held, boolean own) {
        float gain = feedTrust(held);
        if (gain <= 0f) return false;
        MountStats s = MountStats.of(mount, kind);
        long now = p.level().getGameTime();
        if (now - s.fedAt < FEED_COOLDOWN || own && s.trust >= 99.5f) {
            // your own mount that has had enough just lets you up (with a feed in the hand the use is still "ride")
            if (own) return false;
            Notifier.message(p, Component.translatable("mounts.skycraft.fed_enough", kind.displayName()));
            return true;
        }
        if (!p.getAbilities().instabuild) held.shrink(1);
        s.fedAt = now;
        s.addTrust(own ? gain : gain * 1.5f);
        mount.heal(gain);
        commit(mount, kind, s, own ? p : null, true);
        if (mount.level() instanceof ServerLevel sl) {
            sl.playSound(null, mount.blockPosition(), SoundEvents.HORSE_EAT, SoundSource.NEUTRAL, 0.8f, 1.0f);
            sl.sendParticles(ParticleTypes.HEART, mount.getX(), mount.getY() + mount.getBbHeight(), mount.getZ(), 3, 0.3, 0.2, 0.3, 0.02);
        }
        Notifier.message(p, Component.translatable(own ? "mounts.skycraft.fed" : "mounts.skycraft.fed_wild", kind.displayName()));
        return true;
    }

    // ------------------------------------------------------------------ training (stable hand)

    /**
     * One paid training session of the active mount in a stat (the caller took the gold). Returns false when the
     * stable hand would not (cap, weekly limit, no mount). Updates the record and the entity if it is loaded.
     */
    public static boolean train(ServerPlayer p, int stat) {
        MountKind kind = Mounts.active(p);
        MountStats s = active(p);
        if (kind == null || s == null || stat < 0 || stat > MountProfile.TEMPER) return false;
        long week = MountStock.weekOf(p.level().getGameTime());
        if (MountStock.canTrain(s, stat, week) != MountStock.Train.OK) return false;
        MountStock.applyTraining(s, stat, week);
        record(p, kind, s);
        Entity e = Mounts.activeEntity(p);
        if (e instanceof Mob m) commit(m, kind, s, p, true);
        return true;
    }
}
