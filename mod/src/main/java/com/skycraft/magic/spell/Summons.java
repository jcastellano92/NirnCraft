package com.skycraft.magic.spell;

import com.skycraft.core.Notifier;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.Targeting;
import com.skycraft.perk.Perks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Conjured creatures and temporary animal allies: the one summon/ally system, used by spells and staves alike.
 *
 * <ul>
 *     <li><b>Summoned</b> ({@link #TAG}): spawned by magic (Conjure spells, the Staff of Conjuring). Vanishes when its
 *     time is up or its owner leaves, and leaves no drops or experience.</li>
 *     <li><b>Allied</b> ({@link #ALLY_TAG}): an existing creature charmed by magic (Animal Allegiance). When its time is
 *     up it just goes back to its own business: it is never removed and dies with its normal drops.</li>
 * </ul>
 *
 * <p>Both carry persistent data {@link #OWNER}/{@link #UNTIL} (summons also {@link #KIND}). They never target their
 * owner, the owner's other allies or other players (unless they attacked the owner or the summon), fight what the
 * owner fights and hostile mobs near the owner.</p>
 */
public final class Summons {
    public static final String TAG = "skycraft.summon";
    public static final String ALLY_TAG = "skycraft.ally";
    public static final String OWNER = "skycraft_summon_owner";
    public static final String UNTIL = "skycraft_summon_until";
    public static final String KIND = "skycraft_summon_kind";

    private Summons() {}

    // ------------------------------------------------------------------ queries

    public static boolean isSummon(Entity e) {
        return e.getTags().contains(TAG);
    }

    public static boolean isAlly(Entity e) {
        return e.getTags().contains(ALLY_TAG);
    }

    /** A summon or an ally: it fights for a player, so it isn't a wild creature (no leveling, no loot body). */
    public static boolean isCompanion(Entity e) {
        return isSummon(e) || isAlly(e);
    }

    /** Owner of a summon or ally, or null. Cheap for ordinary entities (scoreboard tag check first). */
    @Nullable
    public static UUID ownerOf(Entity e) {
        if (!isSummon(e) && !isAlly(e)) return null;
        CompoundTag pd = e.getPersistentData();
        return pd.hasUUID(OWNER) ? pd.getUUID(OWNER) : null;
    }

    public static List<Mob> summonsOf(ServerPlayer player) {
        UUID id = player.getUUID();
        return player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(160),
                m -> isSummon(m) && m.isAlive() && id.equals(ownerOf(m)));
    }

    // ------------------------------------------------------------------ spells

    public static Spell.Result familiar(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, EntityType.WOLF, "familiar", false, false, mob -> {
            setHealth(mob, 24);
            setAttack(mob, 5);
        });
    }

    public static Spell.Result zombie(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, EntityType.ZOMBIE, "zombie", false, true, mob -> {
            setHealth(mob, Perks.has(p, "conjuration.dark_souls") ? 40 : 20);
            mob.setCanPickUpLoot(false);
            mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20 * 60 * 30, 0, false, false));
        });
    }

    public static Spell.Result flameAtronach(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, EntityType.BLAZE, "flame_atronach", true, false, mob -> setHealth(mob, 30));
    }

    public static Spell.Result frostAtronach(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, EntityType.IRON_GOLEM, "frost_atronach", true, false, mob -> {
            if (mob instanceof IronGolem golem) golem.setPlayerCreated(true);
            setHealth(mob, 60);
            setAttack(mob, 8);
        });
    }

    public static Spell.Result stormAtronach(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, EntityType.IRON_GOLEM, "storm_atronach", true, false, mob -> {
            if (mob instanceof IronGolem golem) golem.setPlayerCreated(true);
            setHealth(mob, 80);
            setAttack(mob, 12);
            AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0.32);
        });
    }

    public static Spell.Result dremoraLord(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, EntityType.WITHER_SKELETON, "dremora_lord", true, false, mob -> {
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
            mob.setDropChance(EquipmentSlot.MAINHAND, 0f);
            setHealth(mob, 70);
            setAttack(mob, 6);
            mob.setCanPickUpLoot(false);
        });
    }

    private static void setHealth(Mob mob, double health) {
        AttributeInstance inst = mob.getAttribute(Attributes.MAX_HEALTH);
        if (inst != null) inst.setBaseValue(health);
        mob.setHealth(mob.getMaxHealth());
    }

    private static void setAttack(Mob mob, double damage) {
        AttributeInstance inst = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (inst != null) inst.setBaseValue(damage);
    }

    private static Spell.Result summon(ServerPlayer p, Spell spell, EntityType<? extends Mob> type, String kind,
                                       boolean atronach, boolean undead, Consumer<Mob> setup) {
        ServerLevel level = p.serverLevel();
        double range = 14 * (1 + 0.5 * Perks.rank(p, "conjuration.summoner"));
        int duration = spell.duration;
        if (atronach && Perks.has(p, "conjuration.atromancy")) duration *= 2;
        if (undead && Perks.has(p, "conjuration.necromancy")) duration *= 2;
        Mob mob = conjure(p, type, kind, range, duration, setup);
        if (mob == null) return Spell.Result.FAILED;

        Vec3 pos = mob.position();
        Vec3 c = pos.add(0, mob.getBbHeight() / 2, 0);
        MagicFx.sendNear(level, c, 64, MagicFx.SUMMON, spell.element, pos, pos, mob.getId(), Math.round(mob.getBbHeight() * 10));
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.PLAYERS, 1f, 1f);
        level.playSound(null, pos.x, pos.y, pos.z, undead ? SoundEvents.ZOMBIE_VILLAGER_CURE : SoundEvents.ILLUSIONER_MIRROR_MOVE,
                SoundSource.PLAYERS, 0.7f, undead ? 1.5f : 0.8f);
        return Spell.Result.EFFECT;
    }

    /**
     * Spawns a summon of {@code p} where it is looking (up to {@code range} blocks) for {@code duration} ticks; used by
     * Conjure spells and conjuring staves. The owner keeps one summon (two with Twin Souls): the oldest is dismissed.
     * Returns the summon, or null if it couldn't be created.
     */
    @Nullable
    public static Mob conjure(ServerPlayer p, EntityType<? extends Mob> type, String kind, double range, int duration, Consumer<Mob> setup) {
        ServerLevel level = p.serverLevel();
        Mob mob = type.create(level);
        if (mob == null) return null;
        Vec3 pos = spawnPos(p, range, mob);
        mob.moveTo(pos.x, pos.y, pos.z, p.getYRot(), 0f);
        mob.setYHeadRot(p.getYRot());
        setup.accept(mob);

        // Limit: one summon (two with Twin Souls). The oldest is dismissed, like Skyrim.
        int max = Perks.has(p, "conjuration.twin_souls") ? 2 : 1;
        List<Mob> existing = summonsOf(p);
        existing.sort(Comparator.comparingLong(m -> m.getPersistentData().getLong(UNTIL)));
        while (existing.size() >= max) dismiss(existing.remove(0));

        mob.addTag(TAG);
        CompoundTag pd = mob.getPersistentData();
        pd.putUUID(OWNER, p.getUUID());
        pd.putLong(UNTIL, level.getGameTime() + duration);
        pd.putString(KIND, kind);
        mob.setPersistenceRequired();
        mob.setCustomName(Component.translatable("entity.skycraft.summon." + kind));
        level.addFreshEntity(mob);
        return mob;
    }

    /** Where the player is looking (up to {@code range}), dropped to the ground; falls back to just in front. */
    private static Vec3 spawnPos(ServerPlayer p, double range, Mob mob) {
        ServerLevel level = p.serverLevel();
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1f).scale(range));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        BlockPos pos = hit.getType() == HitResult.Type.MISS ? BlockPos.containing(end) : hit.getBlockPos().relative(hit.getDirection());
        for (int i = 0; i < 10 && level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty(); i++) pos = pos.below();
        Vec3 v = Vec3.atBottomCenterOf(pos);
        if (level.noCollision(mob, mob.getType().getAABB(v.x, v.y, v.z))) return v;
        Vec3 look = p.getViewVector(1f).multiply(1, 0, 1);
        if (look.lengthSqr() < 1e-4) look = new Vec3(0, 0, 1);
        Vec3 front = p.position().add(look.normalize().scale(1.5));
        if (level.noCollision(mob, mob.getType().getAABB(front.x, front.y, front.z))) return front;
        return p.position();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Banishes a summon with a puff of smoke. */
    public static void dismiss(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        Vec3 c = mob.position().add(0, mob.getBbHeight() / 2, 0);
        level.sendParticles(ParticleTypes.SOUL, c.x, c.y, c.z, 14, mob.getBbWidth() / 2, mob.getBbHeight() / 3, mob.getBbWidth() / 2, 0.03);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 10, mob.getBbWidth() / 2, mob.getBbHeight() / 3, mob.getBbWidth() / 2, 0.02);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.NEUTRAL, 0.8f, 0.6f);
        mob.discard();
    }

    /**
     * Older worlds: Staff of Conjuring familiars only carried {@link #OWNER}/{@link #UNTIL}. Turns them into proper
     * summons so they still expire. Called when an entity joins the level.
     */
    public static void upgradeLegacy(Entity e) {
        if (isCompanion(e)) return;
        CompoundTag pd = e.getPersistentData();
        if (!pd.hasUUID(OWNER) || !pd.contains(UNTIL)) return;
        e.addTag(TAG);
        if (!pd.contains(KIND)) pd.putString(KIND, "familiar");
    }

    /** Animal Allegiance: an animal fights for {@code owner} until {@code until}. */
    public static void makeAlly(Mob mob, ServerPlayer owner, long until) {
        mob.addTag(ALLY_TAG);
        CompoundTag pd = mob.getPersistentData();
        pd.putUUID(OWNER, owner.getUUID());
        pd.putLong(UNTIL, until);
        mob.setTarget(null);
    }

    public static void clearAlly(Mob mob) {
        mob.removeTag(ALLY_TAG);
        CompoundTag pd = mob.getPersistentData();
        pd.remove(OWNER);
        pd.remove(UNTIL);
        mob.setTarget(null);
    }

    /** Whether a summon/ally of {@code owner} may attack {@code target}. */
    public static boolean mayTarget(Mob mob, UUID ownerId, LivingEntity target, @Nullable ServerPlayer owner) {
        if (target.getUUID().equals(ownerId)) return false;
        UUID otherOwner = ownerOf(target);
        if (ownerId.equals(otherOwner)) return false;
        if (owner != null) {
            if (Targeting.isFriendly(owner, target)) return false;
            boolean provoked = owner.getLastHurtByMob() == target || mob.getLastHurtByMob() == target
                    || owner.getLastHurtMob() == target && owner.tickCount - owner.getLastHurtMobTimestamp() < 200;
            if (target instanceof Player other) return provoked && owner.canHarmPlayer(other);
            if (provoked) return true;
        } else if (target instanceof Player) {
            return false;
        }
        return target instanceof Enemy || mob.getLastHurtByMob() == target;
    }

    /** Called every 10 ticks for each summon and ally. */
    public static void tick(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        UUID ownerId = ownerOf(mob);
        boolean summon = isSummon(mob);
        ServerPlayer owner = ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
        long now = level.getGameTime();
        long until = mob.getPersistentData().getLong(UNTIL);
        boolean ownerOk = owner != null && owner.isAlive() && owner.level() == level;
        if (summon) {
            if (!ownerOk || now >= until) {
                if (owner != null && now >= until) Notifier.message(owner, Component.translatable("message.skycraft.summon_expired", mob.getDisplayName()));
                dismiss(mob);
                return;
            }
        } else if (!ownerOk || now >= until) {
            clearAlly(mob);
            return;
        }

        // Pick a fight: what the owner hit, what hit the owner, or a hostile mob near the owner.
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive() || !mayTarget(mob, ownerId, target, owner)) {
            LivingEntity pick = null;
            LivingEntity hitByOwner = owner.getLastHurtMob();
            if (hitByOwner != null && hitByOwner.isAlive() && owner.tickCount - owner.getLastHurtMobTimestamp() < 200
                    && hitByOwner != mob && mayTarget(mob, ownerId, hitByOwner, owner)) {
                pick = hitByOwner;
            }
            LivingEntity hurtOwner = owner.getLastHurtByMob();
            if (pick == null && hurtOwner != null && hurtOwner.isAlive() && hurtOwner != mob && mayTarget(mob, ownerId, hurtOwner, owner)) {
                pick = hurtOwner;
            }
            if (pick == null) {
                double best = Double.MAX_VALUE;
                for (Mob m : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(16),
                        m -> m instanceof Enemy && m.isAlive() && m != mob && mayTarget(mob, ownerId, m, owner))) {
                    double d = m.distanceToSqr(owner);
                    if (d < best && mob.hasLineOfSight(m)) {
                        best = d;
                        pick = m;
                    }
                }
            }
            if (target != null && pick == null) mob.setTarget(null);
            if (pick != null) mob.setTarget(pick);
        }

        // Stay close to the owner.
        if (summon) {
            double dist = mob.distanceToSqr(owner);
            if (dist > 32 * 32) {
                Vec3 behind = owner.position().subtract(owner.getViewVector(1f).multiply(2, 0, 2));
                mob.teleportTo(behind.x, owner.getY(), behind.z);
                mob.getNavigation().stop();
            } else if (mob.getTarget() == null && dist > 7 * 7) {
                mob.getNavigation().moveTo(owner, 1.2);
            }
            ambient(level, mob);
        }
    }

    private static void ambient(ServerLevel level, Mob mob) {
        String kind = mob.getPersistentData().getString(KIND);
        ParticleOptions particle = switch (kind) {
            case "flame_atronach" -> ParticleTypes.FLAME;
            case "frost_atronach" -> ParticleTypes.SNOWFLAKE;
            case "storm_atronach" -> ParticleTypes.ELECTRIC_SPARK;
            case "dremora_lord" -> ParticleTypes.SMALL_FLAME;
            default -> ParticleTypes.SOUL;
        };
        level.sendParticles(particle, mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(), 3,
                mob.getBbWidth() * 0.4, mob.getBbHeight() * 0.3, mob.getBbWidth() * 0.4, 0.01);
    }
}
