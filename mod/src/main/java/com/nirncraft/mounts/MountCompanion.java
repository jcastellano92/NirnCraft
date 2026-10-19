package com.nirncraft.mounts;

import com.nirncraft.core.Notifier;
import com.nirncraft.core.SkyData;
import com.nirncraft.creatures.spec.Attacks;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Downed instead of dead, and companion combat, for every owned mount kind ({@link MountKind}).
 *
 * <p><b>Downed.</b> An owned mount that would die (damage past its health, a death event) never dies: the event is
 * cancelled and the mount lies down with a sliver of health. While down it is invulnerable and silent (it whimpers
 * now and then), has no AI, cannot be targeted (hostiles drop it, {@code LivingChangeTargetEvent} refuses it), takes
 * no blows (aimed or area), loses its rider and cannot be mounted. It rises when the area is calm: nobody hostile
 * within {@link #CALM_RADIUS} blocks is after the owner or the mount, the owner has not been in combat for
 * {@link Vitals#COMBAT_TIMEOUT} ticks (8 s), or the owner is gone (offline, dead, another dimension, far away). It
 * then stands at {@link #RISE_FRACTION} of its health and may not be ridden for {@link #REST_TICKS}. State lives in
 * the entity's persistent data ({@link #DOWNED}), so it survives saves, chunk unloads and restarts; a mount that
 * loads with its owner gone, and one whose owner logs out, rises quietly. A mount that falls out of the world is put
 * back beside its owner. Only an explicit kill ({@code /kill}, discard) still removes a mount; the old death branch
 * ({@link Mounts#onDeath}, {@link Mounts#DEATH_COOLDOWN_TICKS}) then applies.</p>
 *
 * <p><b>Companion combat.</b> A mount whose {@link Profile#fights} is set and that is not ridden fights beside its
 * owner while the owner is in combat: it takes up the mobs that hit the owner or that the owner hits (only monsters or
 * mobs that target the owner, never players or the owner's own), stays within {@link #LEASH} blocks of the owner,
 * and lets go of a foe more than {@link #CHASE} blocks from the owner. Beasts use their own attack goal (the spec
 * attacks, so the creature's own damage attribute and animations); horses and Arvak kick, damage = attack attribute
 * (or the profile's base) x the profile's factor x the mount's power (persistent {@link #POWER} / {@link #LEVEL},
 * written by the stat system). Non-fighters keep {@link #KEEP_AWAY} blocks from hostiles near the owner and never
 * take a target. Stance (follow or stay) is a flag in the mount record, toggled with sneak plus the call key or
 * {@code /nirncraft-mounts stance}.</p>
 */
public final class MountCompanion {
    /** Entity persistent data: game time the mount went down (key present = downed). */
    public static final String DOWNED = "nirncraft_downed";
    /** Bits of what the mount was before going down: 1 invulnerable, 2 silent, 4 no-AI. */
    private static final String PREV = "nirncraft_downed_prev";
    /** Entity persistent data: game time before which it may not be ridden (it just got up). */
    public static final String REST = "nirncraft_mount_rest";
    /** Optional float multiplier of the mount's attack from the stat system (default 1). */
    public static final String POWER = "nirncraft_mount_power";
    /** Optional int mount level (+4% attack per level above 1). */
    public static final String LEVEL = "nirncraft_mount_level";
    /** Mount record flag: true = stay (do not follow into a fight). */
    public static final String STAY = "stay";

    /** It lies at least this long (so the fall is seen) before it may rise. */
    public static final int MIN_DOWN_TICKS = 60;
    public static final int CALM_RADIUS = 24;
    public static final int REST_TICKS = 100;
    public static final float RISE_FRACTION = 0.35f;
    /** An owner this far away (blocks) has left: the mount does not wait for calm. */
    public static final double FAR = 64;
    /** A fighter stays within this of its owner while the owner fights. */
    public static final double LEASH = 12;
    /** A fighter lets go of a foe farther than this from the owner. */
    public static final double CHASE = 20;
    /** Non-fighters keep this far from hostiles. */
    public static final double KEEP_AWAY = 8;
    private static final int RECENT = 100;
    /** Self-test only: a fake owner that {@link #owner} resolves although it is not in the player list. */
    static volatile ServerPlayer TEST_OWNER;

    private MountCompanion() {}

    // ------------------------------------------------------------------ profiles

    /**
     * How a kind helps in a fight. {@code damage} scales the attack attribute (beasts use their spec attacks and only
     * the {@code fights} flag), {@code base} is the attack when the entity has no attack attribute (horses).
     */
    public record Profile(boolean fights, float damage, float base, int cooldown, double knock) {
        public static final Profile PEACEFUL = new Profile(false, 0, 0, 0, 0);
    }

    public static Profile profile(MountKind kind) {
        return switch (kind) {
            case SENCHE -> new Profile(true, 1.0f, 7, 24, 0.5);
            case AKAVIRI_TIGER -> new Profile(true, 1.1f, 8, 22, 0.5);
            case GIANT_WOLF -> new Profile(true, 0.9f, 6, 20, 0.3);
            case MAMMOTH -> new Profile(true, 1.0f, 12, 40, 1.2);
            case REALM_GRAZER -> new Profile(true, 0.6f, 4, 30, 0.4);
            case ARVAK -> new Profile(true, 1.4f, 8, 20, 0.6);
            case SHADOWMERE -> new Profile(true, 1.0f, 6, 22, 0.5);
            case BRETON_DESTRIER -> new Profile(true, 0.8f, 5, 26, 0.5);
            case ALIKR_STEED -> new Profile(true, 0.6f, 4, 26, 0.4);
            case WHITERUN_BAY, RIFTEN_CHESTNUT, CYRODILIC_BAY, COLOVIAN_PAINT -> new Profile(true, 0.5f, 4, 30, 0.4);
            default -> Profile.PEACEFUL; // ponies, greys, camel, guar, indrik: they keep clear of a fight
        };
    }

    // ------------------------------------------------------------------ facts

    public static boolean isDowned(@Nullable Entity e) {
        return e instanceof Mob && !e.level().isClientSide() && e.getPersistentData().contains(DOWNED);
    }

    /** An owned mount of ours (a NirnCraft mount kind with a player owner): the only thing this layer touches. */
    public static boolean isCompanion(@Nullable Entity e) {
        return e instanceof Mob && Mounts.kindOf(e) != null && Mounts.ownerOf(e) != null;
    }

    /** The owner's server player when online in the mount's dimension and alive, else null. */
    @Nullable
    public static ServerPlayer owner(Entity mount) {
        UUID id = Mounts.ownerOf(mount);
        // the self-test's fake rider is not in the player list
        ServerPlayer test = TEST_OWNER;
        if (test != null && id != null && id.equals(test.getUUID()) && test.level() == mount.level()) return test;
        if (id == null || mount.level().getServer() == null || mount.level().isClientSide()) return null;
        ServerPlayer p = mount.level().getServer().getPlayerList().getPlayer(id);
        return p != null && p.level() == mount.level() && p.isAlive() ? p : null;
    }

    private static boolean real(Player p) {
        return p instanceof ServerPlayer && !(p instanceof FakePlayer);
    }

    // ------------------------------------------------------------------ going down

    /** True when this blow or death would be the mount's end and is turned into going down instead. */
    public static boolean mayDown(LivingEntity e, DamageSource src) {
        return isCompanion(e) && !isDowned(e) && !src.is(DamageTypes.GENERIC_KILL);
    }

    /** Lays the mount down. Returns false when it already is (or is not a companion). */
    public static boolean down(Mob mob, long now, @Nullable Player owner) {
        if (isDowned(mob) || mob.level().isClientSide()) return false;
        CompoundTag t = mob.getPersistentData();
        t.putLong(DOWNED, Math.max(1, now));
        t.putInt(PREV, (mob.isInvulnerable() ? 1 : 0) | (mob.isSilent() ? 2 : 0) | (mob.isNoAi() ? 4 : 0));
        mob.ejectPassengers();
        mob.setHealth(Math.min(1f, mob.getMaxHealth()));
        mob.setPermanentlyInvulnerable(true);
        mob.setSilent(true);
        mob.setNoAi(true);
        mob.setTarget(null);
        mob.setLastHurtByMob(null);
        mob.clearFire();
        mob.removeAllEffects();
        mob.setSprinting(false);
        mob.getNavigation().stop();
        mob.setDeltaMovement(0, Math.min(0, mob.getDeltaMovement().y), 0);
        clearAggro(mob);
        mob.level().playSound(null, mob.blockPosition(), whimper(mob), SoundSource.NEUTRAL, 1.0f, 0.6f);
        if (mob.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.CLOUD, mob.getX(), mob.getY() + 0.3, mob.getZ(), 8, 0.5, 0.1, 0.5, 0.01);
        }
        MountKind kind = Mounts.kindOf(mob);
        if (owner instanceof ServerPlayer sp && real(sp)) {
            Notifier.message(sp, Component.translatable("mounts.nirncraft.downed", kind != null ? kind.displayName() : mob.getName()));
            sp.level().playSound(null, sp.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.NEUTRAL, 0.6f, 0.6f);
        }
        sync(mob, true, owner);
        return true;
    }

    /** The hostiles around that were after the mount forget it. */
    private static void clearAggro(Mob mob) {
        for (Mob m : mob.level().getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(CALM_RADIUS), m -> m != mob && m.getTarget() == mob)) {
            m.setTarget(null);
            m.getNavigation().stop();
        }
    }

    private static SoundEvent whimper(Mob mob) {
        MountKind k = Mounts.kindOf(mob);
        if (k != null && k.backing == MountKind.Backing.BEAST && k != MountKind.INDRIK) return SoundEvents.WOLF_SOUNDS.get(net.minecraft.world.entity.animal.wolf.WolfSoundVariants.SoundSet.CLASSIC).adultSounds().whineSound().value();
        if (k == MountKind.ARVAK) return SoundEvents.SKELETON_HORSE_AMBIENT;
        return SoundEvents.HORSE_BREATHE;
    }

    // ------------------------------------------------------------------ rising

    /**
     * Gets the mount back on its feet at {@link #RISE_FRACTION} of its health. {@code announce} false is the quiet
     * variant (logout, loading with no owner around): no sound, message or packet.
     */
    public static boolean rise(Mob mob, long now, @Nullable Player owner, boolean announce) {
        if (!isDowned(mob)) return false;
        CompoundTag t = mob.getPersistentData();
        int prev = t.getIntOr(PREV, 0);
        t.remove(DOWNED);
        t.remove(PREV);
        mob.setPermanentlyInvulnerable((prev & 1) != 0);
        mob.setSilent((prev & 2) != 0);
        mob.setNoAi((prev & 4) != 0);
        mob.setHealth(Math.max(mob.getHealth(), mob.getMaxHealth() * RISE_FRACTION));
        t.putLong(REST, now + REST_TICKS);
        if (!announce) return true;
        MountKind kind = Mounts.kindOf(mob);
        mob.level().playSound(null, mob.blockPosition(), riseSound(mob), SoundSource.NEUTRAL, 1.0f, 1.0f);
        if (mob.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getY() + 0.8, mob.getZ(), 10, 0.5, 0.5, 0.5, 0.0);
        }
        if (owner instanceof ServerPlayer sp && real(sp)) {
            Notifier.message(sp, Component.translatable("mounts.nirncraft.rose", kind != null ? kind.displayName() : mob.getName()));
        }
        sync(mob, false, owner);
        return true;
    }

    private static SoundEvent riseSound(Mob mob) {
        MountKind k = Mounts.kindOf(mob);
        if (k == MountKind.ARVAK) return SoundEvents.SKELETON_HORSE_AMBIENT;
        if (k == MountKind.MAMMOTH) return SoundEvents.RAVAGER_AMBIENT;
        if (k != null && k.backing == MountKind.Backing.BEAST && k != MountKind.INDRIK) return SoundEvents.WOLF_SOUNDS.get(net.minecraft.world.entity.animal.wolf.WolfSoundVariants.SoundSet.CLASSIC).adultSounds().growlSound().value();
        return SoundEvents.HORSE_AMBIENT;
    }

    /** True while the mount is still resting from a fall (it has just risen). */
    public static boolean resting(Entity mount, long now) {
        return mount.getPersistentData().contains(REST) && now < mount.getPersistentData().getLongOr(REST, 0L);
    }

    // ------------------------------------------------------------------ the downed tick

    /**
     * One server tick of a downed mount (called every tick for the owner's active mount, cheap when it is not down).
     * Keeps it lying, settles it on the ground, brings it back from the void, and rises it once calm. Returns true on
     * the tick it rose.
     */
    public static boolean tickDowned(Mob mob, @Nullable Player owner, long now) {
        if (!isDowned(mob)) return false;
        mob.setTarget(null);
        if (!mob.onGround() && !mob.isInWater() && !mob.isInLava()) {
            // no-AI mobs do not fall by themselves
            mob.move(MoverType.SELF, new Vec3(0, -0.4, 0));
        }
        ServerLevel level = (ServerLevel) mob.level();
        if (mob.getY() < level.getMinY() - 8) rescue(mob, owner);
        if (mob.tickCount % 80 == 17) mob.level().playSound(null, mob.blockPosition(), whimper(mob), SoundSource.NEUTRAL, 0.5f, 0.5f);
        if (mob.tickCount % 10 != 3) return false;
        clearAggro(mob);
        return tryRise(mob, owner, now);
    }

    /** Rises the mount when it has lain long enough and the area is calm. */
    static boolean tryRise(Mob mob, @Nullable Player owner, long now) {
        if (!isDowned(mob)) return false;
        long since = mob.getPersistentData().getLongOr(DOWNED, 0L);
        if (now - since < MIN_DOWN_TICKS || !calm(mob, owner)) return false;
        if (owner != null && owner.level() == mob.level() && mob.distanceTo(owner) > FAR) rescue(mob, owner);
        return rise(mob, now, owner, true);
    }

    /** Puts a mount that fell out of the world (or was left far away) back beside its owner, or at the world spawn. */
    static void rescue(Mob mob, @Nullable Player owner) {
        ServerLevel level = (ServerLevel) mob.level();
        BlockPos near;
        if (owner != null && owner.level() == level) {
            near = owner.blockPosition();
        } else {
            BlockPos s = level.getRespawnData().pos();
            int x = mob.getBlockX(), z = mob.getBlockZ();
            near = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (near.getY() <= level.getMinY()) near = s;
        }
        BlockPos at = Mounts.safeSpot(level, near);
        mob.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.fallDistance = 0;
        mob.getNavigation().stop();
    }

    /**
     * The area is calm: the owner is gone (offline, dead, elsewhere, far), or the owner has not been in combat for
     * 8 s and no hostile within {@link #CALM_RADIUS} blocks of the mount or the owner is after either of them.
     */
    public static boolean calm(Mob mob, @Nullable Player owner) {
        if (owner == null || !owner.isAlive() || owner.level() != mob.level() || mob.distanceTo(owner) > FAR) return true;
        if (Vitals.inCombat(owner)) return false;
        AABB box = mob.getBoundingBox().inflate(CALM_RADIUS);
        if (!mob.level().getEntitiesOfClass(Mob.class, box, m -> m != mob && m.isAlive() && (m.getTarget() == owner || m.getTarget() == mob)).isEmpty()) return false;
        AABB near = owner.getBoundingBox().inflate(CALM_RADIUS);
        return mob.level().getEntitiesOfClass(Mob.class, near, m -> m != mob && m.isAlive() && (m.getTarget() == owner || m.getTarget() == mob)).isEmpty();
    }

    /** Called from the owner's player tick for their active mount. */
    static void tick(ServerPlayer player) {
        // cheap: one record lookup and a map get
        Entity e = Mounts.activeEntity(player);
        if (e instanceof Mob mob && isDowned(mob)) tickDowned(mob, player, player.level().getGameTime());
    }

    // ------------------------------------------------------------------ edge cases

    /**
     * A mount that is downed as it loads from disk: kept lying only when its owner is online beside it and it is the
     * owner's recorded mount (the owner's tick will rise it when calm); otherwise it rises quietly, so a restart or
     * a login never leaves one down forever.
     */
    public static void resolveLoaded(Mob mob, @Nullable ServerPlayer owner) {
        if (!isDowned(mob)) return;
        if (owner != null && owner.level() == mob.level() && Mounts.isActive(owner, mob)) return;
        rise(mob, mob.level().getGameTime(), owner, false);
    }

    /** The owner logs out: whatever is lying down gets up quietly (it comes back standing). */
    static void onLogout(ServerPlayer player) {
        Entity e = Mounts.activeEntity(player);
        if (e instanceof Mob mob && isDowned(mob)) rise(mob, player.level().getGameTime(), player, false);
    }

    /**
     * The owner calls (H) while the mount is down: true when it stays down (a message says so). When it is calm it
     * gets up first and the call goes on.
     */
    static boolean onCall(ServerPlayer player, Entity mount) {
        if (!(mount instanceof Mob mob) || !isDowned(mob)) return false;
        long now = player.level().getGameTime();
        if (calm(mob, player) && mount.level() == player.level()) {
            if (mob.distanceTo(player) > FAR || mob.getY() < ((ServerLevel) mob.level()).getMinY()) rescue(mob, player);
            return !rise(mob, now, player, true);
        }
        MountKind kind = Mounts.kindOf(mob);
        Notifier.message(player, Component.translatable("mounts.nirncraft.downed.call", kind != null ? kind.displayName() : mob.getName()));
        return true;
    }

    /** Mounting a downed or just-risen mount is refused (the rider is told). */
    static boolean denyMount(Entity mounting, Mob mount) {
        long now = mount.level().getGameTime();
        boolean down = isDowned(mount);
        if (!down && !resting(mount, now)) return false;
        if (mounting instanceof ServerPlayer sp && real(sp)) {
            MountKind kind = Mounts.kindOf(mount);
            Component name = kind != null ? kind.displayName() : mount.getName();
            Notifier.message(sp, Component.translatable(down ? "mounts.nirncraft.downed.cannot_ride" : "mounts.nirncraft.rest", name));
        }
        return true;
    }

    // ------------------------------------------------------------------ stance

    public static boolean stays(@Nullable Player owner) {
        return owner != null && Mounts.data(owner).getBooleanOr(STAY, false);
    }

    /** Sneak plus the call key: follow into fights or hold. True when there was a mount close by to tell. */
    static boolean toggleStance(ServerPlayer player) {
        Entity e = Mounts.activeEntity(player);
        if (!(e instanceof Mob mob) || mob.level() != player.level() || mob.distanceToSqr(player) > 48 * 48) return false;
        setStance(player, !stays(player));
        return true;
    }

    public static void setStance(ServerPlayer player, boolean stay) {
        CompoundTag rec = Mounts.data(player);
        if (stay) rec.putBoolean(STAY, true);
        else rec.remove(STAY);
        SkyData.get(player).markDirty();
        MountKind kind = Mounts.active(player);
        Notifier.message(player, Component.translatable(stay ? "mounts.nirncraft.stance.stay" : "mounts.nirncraft.stance.follow",
                kind != null ? kind.displayName() : Component.translatable("mounts.nirncraft.mount")));
    }

    // ------------------------------------------------------------------ sync to clients

    private static void sync(Mob mob, boolean down, @Nullable Player owner) {
        try {
            SkyNetwork.sendToTracking(mob, new CompanionPackets.Downed(mob.getId(), down, false, mob.getX(), mob.getY(), mob.getZ()));
            if (owner instanceof ServerPlayer sp && real(sp)) {
                SkyNetwork.sendToPlayer(sp, new CompanionPackets.Downed(mob.getId(), down, true, mob.getX(), mob.getY(), mob.getZ()));
            }
        } catch (RuntimeException ignored) {
            // not tracked yet (loading): the start-tracking hook sends the state
        }
    }

    /** A player starts tracking a downed mount: tell their client it lies. */
    static void syncTo(ServerPlayer viewer, Entity mount) {
        if (!isDowned(mount)) return;
        boolean mine = Mounts.isOwner(viewer, mount);
        SkyNetwork.sendToPlayer(viewer, new CompanionPackets.Downed(mount.getId(), true, mine, mount.getX(), mount.getY(), mount.getZ()));
    }

    // ------------------------------------------------------------------ companion combat

    /** The mount's attack when it kicks: attribute (or the profile's base) x the profile factor x the stat power. */
    public static float kickDamage(Mob mob, Profile p) {
        float base = mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE) ? (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE) : p.base();
        if (base <= 0) base = p.base();
        return base * p.damage() * power(mob);
    }

    /** The stat system's multiplier of a mount's blows (1 when it writes none). */
    public static float power(Entity mob) {
        CompoundTag t = mob.getPersistentData();
        float m = t.contains(POWER) ? Mth.clamp(t.getFloatOr(POWER, 0f), 0.25f, 4f) : 1f;
        if (t.contains(LEVEL)) m *= 1f + 0.04f * Math.max(0, t.getIntOr(LEVEL, 0) - 1);
        return m;
    }

    /** Whether this mob is in the state in which it helps its owner: owned, loose, standing, owner online. */
    @Nullable
    static ServerPlayer helping(Mob mob) {
        if (mob.isVehicle() || mob.isPassenger() || isDowned(mob) || !mob.isAlive()) return null;
        return owner(mob);
    }

    /** May this entity be fought by a companion on behalf of the owner. */
    static boolean validFoe(Mob mob, Player owner, @Nullable LivingEntity e) {
        if (e == null || e == mob || e == owner || !e.isAlive() || e instanceof Player || isDowned(e)) return false;
        if (Mounts.isMount(e) && Mounts.ownerOf(e) != null) return false;
        if (e instanceof OwnableEntity o && owner.getUUID().equals(com.nirncraft.platform.EntityData.ownerId(o))) return false;
        if (Mounts.isFriend(mob, e)) return false;
        if (e.distanceToSqr(owner) > CHASE * CHASE) return false;
        return e instanceof Enemy || e instanceof Mob m && m.getTarget() == owner;
    }

    /** The mob a fighter should take on now: what hit or is hit by the owner, else hostiles after the owner. */
    @Nullable
    public static LivingEntity pickFoe(Mob mob, Player owner) {
        long t = owner.tickCount;
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        LivingEntity a = owner.getLastHurtByMob();
        if (a != null && t - owner.getLastHurtByMobTimestamp() < RECENT && validFoe(mob, owner, a)) {
            best = a;
            bd = mob.distanceToSqr(a);
        }
        LivingEntity b = owner.getLastHurtMob();
        if (b != null && t - owner.getLastHurtMobTimestamp() < RECENT && validFoe(mob, owner, b) && mob.distanceToSqr(b) < bd) {
            best = b;
            bd = mob.distanceToSqr(b);
        }
        if (best != null) return best;
        for (Mob m : mob.level().getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(LEASH + 4, 6, LEASH + 4), x -> x.getTarget() == owner)) {
            double d = mob.distanceToSqr(m);
            if (d < bd && validFoe(mob, owner, m)) {
                best = m;
                bd = d;
            }
        }
        return best;
    }

    /** A hostile within {@link #KEEP_AWAY} blocks of a non-fighter that is after its owner or it, or null. */
    @Nullable
    public static Mob threatNear(Mob mob, Player owner) {
        Mob best = null;
        double bd = Double.MAX_VALUE;
        for (Mob m : mob.level().getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(KEEP_AWAY, 4, KEEP_AWAY), x -> x != mob && x.isAlive())) {
            LivingEntity tg = m.getTarget();
            if (!(m instanceof Enemy) && tg != owner && tg != mob) continue;
            if (Mounts.isMount(m)) continue;
            double d = mob.distanceToSqr(m);
            if (d < bd) {
                best = m;
                bd = d;
            }
        }
        return best;
    }

    private static boolean fightContext(Player owner) {
        return Vitals.inCombat(owner);
    }

    /** Adds the companion goals to a loading mount (once). Server side, any mount entity with a kind. */
    static void addGoals(Mob mob) {
        MountKind kind = Mounts.kindOf(mob);
        if (kind == null) return;
        for (var w : mob.goalSelector.getAvailableGoals()) if (w.getGoal() instanceof MoveGoal) return;
        Profile p = profile(kind);
        boolean beast = mob instanceof MountBeast || kind.backing == MountKind.Backing.GUAR;
        if (p.fights()) {
            mob.targetSelector.addGoal(2, new FoeGoal(mob));
            if (!beast) mob.goalSelector.addGoal(2, new KickGoal(mob, p));
        }
        mob.goalSelector.addGoal(beast ? 4 : 3, new MoveGoal(mob, p.fights()));
    }

    /** Picks the foe a fighter takes up (sets the mob's target) and drops it when it is out of leash. */
    static final class FoeGoal extends Goal {
        private final Mob mob;
        private LivingEntity foe;

        FoeGoal(Mob mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            if ((mob.tickCount + mob.getId()) % 10 != 0) return false;
            ServerPlayer owner = helping(mob);
            if (owner == null || !fightContext(owner)) return false;
            LivingEntity cur = mob.getTarget();
            if (cur != null && cur.isAlive()) return false;
            LivingEntity f = pickFoe(mob, owner);
            if (f == null) return false;
            // a mount told to stay only takes on what comes to it
            if (stays(owner) && mob.distanceToSqr(f) > 6 * 6) return false;
            foe = f;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            ServerPlayer owner = helping(mob);
            LivingEntity t = mob.getTarget();
            return owner != null && t != null && t == foe && validFoe(mob, owner, t) && mob.distanceToSqr(owner) <= (CHASE + LEASH) * (CHASE + LEASH);
        }

        @Override
        public void start() {
            mob.setTarget(foe);
        }

        @Override
        public void stop() {
            if (mob.getTarget() == foe) mob.setTarget(null);
            foe = null;
        }
    }

    /** The kick of a horse or Arvak: close in on the target and strike it with a cooldown. */
    static final class KickGoal extends Goal {
        private final Mob mob;
        private final Profile profile;
        private int cooldown;
        private int repath;

        KickGoal(Mob mob, Profile profile) {
            this.mob = mob;
            this.profile = profile;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = mob.getTarget();
            ServerPlayer owner = helping(mob);
            return owner != null && t != null && t.isAlive() && validFoe(mob, owner, t);
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            LivingEntity t = mob.getTarget();
            if (t == null) return;
            if (cooldown > 0) cooldown--;
            mob.getLookControl().setLookAt(t, 30f, 30f);
            double gap = Attacks.gap(mob, t);
            if (gap > 1.4) {
                if (--repath <= 0) {
                    repath = 8;
                    mob.getNavigation().moveTo(t, 1.3);
                }
                return;
            }
            mob.getNavigation().stop();
            if (cooldown > 0) return;
            cooldown = profile.cooldown();
            float dmg = kickDamage(mob, profile);
            mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            if (com.nirncraft.platform.Effects.hurt(t, mob.damageSources().mobAttack(mob), dmg)) {
                double kb = profile.knock();
                t.knockback(kb, Mth.sin(mob.getYRot() * Mth.DEG_TO_RAD), -Mth.cos(mob.getYRot() * Mth.DEG_TO_RAD), mob.damageSources().mobAttack(mob), dmg);
                mob.setLastHurtMob(t);
            }
            mob.level().playSound(null, mob.blockPosition(), SoundEvents.HORSE_ANGRY, SoundSource.NEUTRAL, 0.8f, 1.0f);
        }
    }

    /**
     * Movement of a companion in a fight. A fighter without a foe comes to within its leash of the owner (unless told
     * to stay); a non-fighter keeps {@link #KEEP_AWAY} blocks from hostiles and drifts toward the owner when far.
     */
    static final class MoveGoal extends Goal {
        private final Mob mob;
        private final boolean fighter;
        private int repath;
        private Mob threat;

        MoveGoal(Mob mob, boolean fighter) {
            this.mob = mob;
            this.fighter = fighter;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if ((mob.tickCount + mob.getId()) % 5 != 0) return false;
            ServerPlayer owner = helping(mob);
            if (owner == null || !fightContext(owner)) return false;
            if (fighter) {
                if (mob.getTarget() != null || stays(owner)) return false;
                double d = mob.distanceToSqr(owner);
                return d > LEASH * LEASH && d < 40 * 40;
            }
            threat = threatNear(mob, owner);
            return threat != null;
        }

        @Override
        public boolean canContinueToUse() {
            ServerPlayer owner = helping(mob);
            if (owner == null || !fightContext(owner)) return false;
            if (fighter) return mob.getTarget() == null && !stays(owner) && mob.distanceToSqr(owner) > 5 * 5;
            return threat != null && threat.isAlive() && mob.distanceToSqr(threat) < (KEEP_AWAY + 2) * (KEEP_AWAY + 2);
        }

        @Override
        public void stop() {
            threat = null;
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            ServerPlayer owner = helping(mob);
            if (owner == null || --repath > 0) return;
            repath = 8;
            if (fighter) {
                mob.getNavigation().moveTo(owner, 1.25);
                return;
            }
            if (threat == null) return;
            Vec3 away = DefaultRandomPos.getPosAway((net.minecraft.world.entity.PathfinderMob) mob, 12, 6, threat.position());
            if (away != null && away.distanceToSqr(owner.position()) <= 18 * 18) {
                mob.getNavigation().moveTo(away.x, away.y, away.z, 1.3);
            } else {
                // too far from the owner: tuck in behind them, away from the threat
                Vec3 back = owner.position().subtract(threat.position().subtract(owner.position()).normalize().scale(3));
                mob.getNavigation().moveTo(back.x, back.y, back.z, 1.3);
            }
        }
    }

    /** Test helper: the goal instances of a mob (for the self-test). */
    static List<Goal> companionGoals(Mob mob) {
        List<Goal> out = new java.util.ArrayList<>();
        for (var w : mob.goalSelector.getAvailableGoals()) if (w.getGoal() instanceof MoveGoal || w.getGoal() instanceof KickGoal) out.add(w.getGoal());
        for (var w : mob.targetSelector.getAvailableGoals()) if (w.getGoal() instanceof FoeGoal) out.add(w.getGoal());
        return out;
    }
}
