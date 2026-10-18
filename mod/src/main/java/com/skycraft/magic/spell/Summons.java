package com.skycraft.magic.spell;

import com.skycraft.core.Notifier;
import com.skycraft.magic.MagicDamage;
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
import net.minecraft.world.entity.animal.golem.IronGolem;
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
    /** Shout allies that don't count against the summon limit. */
    public static final String FREE = "skycraft_summon_free";

    private Summons() {}

    @Nullable
    private static EntityType<?> entityTypeById(String value) {
        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(value);
        return id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }


    // ------------------------------------------------------------------ queries

    public static boolean isSummon(Entity e) {
        return e.entityTags().contains(TAG);
    }

    public static boolean isAlly(Entity e) {
        return e.entityTags().contains(ALLY_TAG);
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
        return pd.read(OWNER, net.minecraft.core.UUIDUtil.CODEC).isPresent() ? pd.read(OWNER, net.minecraft.core.UUIDUtil.CODEC).orElseThrow() : null;
    }

    public static List<Mob> summonsOf(ServerPlayer player) {
        UUID id = player.getUUID();
        return player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(160),
                m -> isSummon(m) && m.isAlive() && id.equals(ownerOf(m)));
    }

    // ------------------------------------------------------------------ entity types

    /**
     * The one place summon entity types are chosen: summon kind -> entity ids in order of preference. The first id
     * that is registered (and is a {@link Mob}) wins, so dedicated creatures (e.g. {@code skycraft:flame_atronach} from
     * the creatures module) replace the vanilla stand-ins as soon as they exist. Unknown kinds fall back to a zombie.
     */
    private static final java.util.Map<String, List<String>> TYPES = new java.util.HashMap<>();

    static {
        types("familiar", "skycraft:familiar", "minecraft:wolf");
        types("flaming_familiar", "skycraft:flaming_familiar", "skycraft:familiar", "minecraft:wolf");
        types("zombie", "skycraft:zombie", "minecraft:zombie");
        types("revenant", "skycraft:draugr", "minecraft:husk");
        types("dread_zombie", "skycraft:draugr_deathlord", "minecraft:wither_skeleton");
        types("dead_thrall", "skycraft:draugr_deathlord", "minecraft:wither_skeleton");
        types("flame_atronach", "skycraft:flame_atronach", "minecraft:blaze");
        types("frost_atronach", "skycraft:frost_atronach", "minecraft:iron_golem");
        types("storm_atronach", "skycraft:storm_atronach", "minecraft:iron_golem");
        types("flame_thrall", "skycraft:flame_atronach", "minecraft:blaze");
        types("frost_thrall", "skycraft:frost_atronach", "minecraft:iron_golem");
        types("storm_thrall", "skycraft:storm_atronach", "minecraft:iron_golem");
        types("dremora_lord", "skycraft:dremora_lord", "skycraft:dremora", "minecraft:wither_skeleton");
        types("seeker", "skycraft:seeker", "minecraft:vex");
        types("sovngarde_hero", "skycraft:sovngarde_hero", "minecraft:vindicator");
        types("odahviing", "skycraft:odahviing", "skycraft:dragon");
    }

    private static void types(String kind, String... ids) {
        TYPES.put(kind, List.of(ids));
    }

    /** Entity type used for a summon kind (see {@link #TYPES}); never null. */
    @SuppressWarnings("unchecked")
    public static EntityType<? extends Mob> entityType(String kind) {
        for (String id : TYPES.getOrDefault(kind, List.of())) {
            net.minecraft.resources.Identifier rl = net.minecraft.resources.Identifier.tryParse(id);
            if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) continue;
            EntityType<?> type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(rl);
            if (type != null && isMobType(type)) return (EntityType<? extends Mob>) type;
        }
        return net.minecraft.world.entity.EntityTypes.ZOMBIE;
    }

    private static final java.util.Map<EntityType<?>, Boolean> MOB_TYPES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether an entity type makes {@link Mob}s (tested once by creating an instance on the server). */
    private static boolean isMobType(EntityType<?> type) {
        Boolean known = MOB_TYPES.get(type);
        if (known != null) return known;
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        Entity e = type.create(server.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        boolean mob = e instanceof Mob;
        if (e != null) e.discard();
        MOB_TYPES.put(type, mob);
        return mob;
    }

    // ------------------------------------------------------------------ spells

    public static Spell.Result familiar(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, entityType("familiar"), "familiar", false, false, mob -> {
            setHealth(mob, 24);
            setAttack(mob, 5);
        });
    }

    /** Flaming Familiar (Dragonborn): a burning wolf that sets what it bites on fire and explodes when its time is up. */
    public static Spell.Result flamingFamiliar(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, entityType("flaming_familiar"), "flaming_familiar", false, false, mob -> {
            setHealth(mob, 30);
            setAttack(mob, 6);
            mob.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.FIRE_RESISTANCE), 20 * 60 * 30, 0, false, false));
        });
    }

    public static Spell.Result zombie(ServerPlayer p, Spell spell, int tick) {
        return reanimate(p, spell, "zombie", 30f, Perks.has(p, "conjuration.dark_souls") ? 40 : 20, 3);
    }

    public static Spell.Result reanimateCorpse(ServerPlayer p, Spell spell, int tick) {
        return reanimate(p, spell, "zombie", 60f, 40, 5);
    }

    public static Spell.Result revenant(ServerPlayer p, Spell spell, int tick) {
        return reanimate(p, spell, "revenant", 100f, 60, 7);
    }

    public static Spell.Result dreadZombie(ServerPlayer p, Spell spell, int tick) {
        return reanimate(p, spell, "dread_zombie", 180f, 90, 9);
    }

    /** Dead Thrall: a permanent undead servant from any corpse that isn't a dragon or a boss. */
    public static Spell.Result deadThrall(ServerPlayer p, Spell spell, int tick) {
        return reanimate(p, spell, "dead_thrall", 1000f, 120, 10);
    }

    public static Spell.Result flameAtronach(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, entityType("flame_atronach"), "flame_atronach", true, false, Summons::setupFlameAtronach);
    }

    public static Spell.Result frostAtronach(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, entityType("frost_atronach"), "frost_atronach", true, false, Summons::setupFrostAtronach);
    }

    public static Spell.Result stormAtronach(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, entityType("storm_atronach"), "storm_atronach", true, false, Summons::setupStormAtronach);
    }

    /** Flame/Frost/Storm Thrall: a permanent atronach (it stays until killed or you leave). */
    public static Spell.Result flameThrall(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, entityType("flame_thrall"), "flame_atronach", true, false, PERMANENT, Summons::setupFlameAtronach);
    }

    public static Spell.Result frostThrall(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, entityType("frost_thrall"), "frost_atronach", true, false, PERMANENT, Summons::setupFrostAtronach);
    }

    public static Spell.Result stormThrall(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, entityType("storm_thrall"), "storm_atronach", true, false, PERMANENT, Summons::setupStormAtronach);
    }

    private static void setupFlameAtronach(Mob mob) {
        setHealth(mob, 30);
        mob.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.FIRE_RESISTANCE), 20 * 60 * 60, 0, false, false));
    }

    private static void setupFrostAtronach(Mob mob) {
        if (mob instanceof IronGolem golem) golem.setPlayerCreated(true);
        setHealth(mob, 60);
        setAttack(mob, 8);
    }

    private static void setupStormAtronach(Mob mob) {
        if (mob instanceof IronGolem golem) golem.setPlayerCreated(true);
        setHealth(mob, 80);
        setAttack(mob, 12);
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0.32);
    }

    public static Spell.Result dremoraLord(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, entityType("dremora_lord"), "dremora_lord", true, false, mob -> {
            if (mob.getMainHandItem().isEmpty() || mob.getType() == net.minecraft.world.entity.EntityTypes.WITHER_SKELETON) {
                mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
                mob.setDropChance(EquipmentSlot.MAINHAND, 0f);
            }
            setHealth(mob, 70);
            setAttack(mob, 6);
            mob.setCanPickUpLoot(false);
        });
    }

    /** Conjure Seeker (Dragonborn): a flying servant of Hermaeus Mora. */
    public static Spell.Result seeker(ServerPlayer p, Spell spell, int tick) {
        return summon(p, spell, entityType("seeker"), "seeker", false, false, mob -> {
            setHealth(mob, 40);
            setAttack(mob, 6);
        });
    }

    /** Ticks a "permanent" summon (thralls) lasts: a full day of real time. */
    public static final int PERMANENT = 20 * 60 * 60 * 24;

    // ------------------------------------------------------------------ necromancy

    /**
     * Raise Zombie .. Dead Thrall. Aimed at a body ({@code skycraft:corpse}), it raises that very creature if it isn't
     * too powerful for the spell ({@code maxBodyHealth}); its belongings spill onto the ground. With no body in sight
     * it calls a generic undead of {@code fallbackKind} out of the earth instead.
     */
    private static Spell.Result reanimate(ServerPlayer p, Spell spell, String fallbackKind, float maxBodyHealth, double health, double attack) {
        ServerLevel level = p.level();
        boolean permanent = "dead_thrall".equals(fallbackKind);
        double range = 14 * (1 + 0.5 * Perks.rank(p, "conjuration.summoner"));
        com.skycraft.creatures.entity.CorpseEntity corpse = aimedCorpse(p, range);
        double hp = Perks.has(p, "conjuration.dark_souls") ? health * 2 : health;
        Consumer<Mob> undead = mob -> {
            mob.setCanPickUpLoot(false);
            mob.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.FIRE_RESISTANCE), 20 * 60 * 60, 0, false, false));
        };
        if (corpse != null) {
            CompoundTag saved = com.skycraft.platform.EntityData.saveWithoutId(corpse);
            EntityType<?> bodyType = entityTypeById(saved.getStringOr("BodyType", ""));
            Entity probe = bodyType == null || bodyType == net.minecraft.world.entity.EntityTypes.PLAYER ? null : bodyType.create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
            if (!(probe instanceof Mob bodyMob) || bodyType.builtInRegistryHolder().is(com.skycraft.magic.MagicRegistry.DRAGONS) || bodyType.builtInRegistryHolder().is(com.skycraft.combat.CombatHandler.BOSSES)) {
                if (probe != null) probe.discard();
                Notifier.message(p, Component.translatable("message.skycraft.corpse_cannot_rise"));
                return Spell.Result.FAILED;
            }
            float bodyHealth = bodyMob.getMaxHealth();
            bodyMob.discard();
            if (bodyHealth > maxBodyHealth * (spell.dual ? 1.5f : 1f)) {
                Notifier.message(p, Component.translatable("message.skycraft.corpse_too_powerful"));
                return Spell.Result.FAILED;
            }
            // what the body carried falls to the ground
            net.minecraft.nbt.ListTag loot = saved.getListOrEmpty("Loot");
            for (int i = 0; i < loot.size(); i++) {
                ItemStack stack = com.skycraft.inventory.ItemData.parse(loot.getCompoundOrEmpty(i));
                if (!stack.isEmpty()) corpse.spawnAtLocation(level, stack, 0.3f);
            }
            Vec3 at = corpse.position();
            corpse.discard();
            @SuppressWarnings("unchecked")
            EntityType<? extends Mob> mobType = (EntityType<? extends Mob>) bodyType;
            int duration = permanent ? PERMANENT : Perks.has(p, "conjuration.necromancy") ? spell.duration * 2 : spell.duration;
            Mob mob = conjureAt(p, mobType, "thrall", at, duration, m -> {
                undead.accept(m);
                if (m.getMaxHealth() < hp) setHealth(m, hp);
            });
            if (mob == null) return Spell.Result.FAILED;
            mob.setCustomName(Component.translatable("entity.skycraft.summon.thrall", mob.getType().getDescription()));
            raiseFx(level, mob, spell);
            return Spell.Result.EFFECT;
        }
        int extra = permanent ? PERMANENT : -1;
        return summon(p, spell, entityType(fallbackKind), fallbackKind, false, true, extra, mob -> {
            undead.accept(mob);
            setHealth(mob, hp);
            setAttack(mob, attack);
        });
    }

    private static void raiseFx(ServerLevel level, Mob mob, Spell spell) {
        Vec3 pos = mob.position();
        MagicFx.sendNear(level, pos, 64, MagicFx.SUMMON, Element.SOUL, pos, pos, mob.getId(), Math.round(mob.getBbHeight() * 10));
        SpellSounds.world(level, pos, SpellSounds.Slot.SUMMON, 1f, 0.7f);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.PLAYERS, 0.7f, 1.5f);
    }

    /** The corpse the player is looking at, within {@code range}. */
    @Nullable
    private static com.skycraft.creatures.entity.CorpseEntity aimedCorpse(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1f).scale(range));
        BlockHitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 limit = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        com.skycraft.creatures.entity.CorpseEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (com.skycraft.creatures.entity.CorpseEntity c : p.level().getEntitiesOfClass(com.skycraft.creatures.entity.CorpseEntity.class,
                new net.minecraft.world.phys.AABB(eye, limit).inflate(1.5))) {
            java.util.Optional<Vec3> hit = c.getBoundingBox().inflate(0.6).clip(eye, limit);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < bestDist) {
                    bestDist = d;
                    best = c;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ banish & command

    /** Summoned or daedric creatures: atronachs, dremora, seekers and familiars, or any creature tagged as daedra. */
    public static boolean isDaedra(Entity e) {
        if (isSummon(e)) {
            String kind = e.getPersistentData().getStringOr(KIND, "");
            if (kind.contains("atronach") || kind.contains("dremora") || kind.contains("seeker") || kind.contains("familiar")) return true;
        }
        String id = String.valueOf(EntityType.getKey(e.getType()));
        return id.contains("atronach") || id.contains("dremora") || id.contains("daedr") || id.contains("seeker");
    }

    /** Banish Daedra: summoned daedra (not your own) up to a high level are sent back to Oblivion. */
    public static void banishHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.CONJURE, 1.2f);
        SpellSounds.world(level, pos, SpellSounds.Slot.BANISH, 1f, 0.8f);
        if (!(target instanceof Mob mob) || !isDaedra(mob) || caster.getUUID().equals(ownerOf(mob))) return;
        if (target.getType().builtInRegistryHolder().is(com.skycraft.combat.CombatHandler.BOSSES) || target.getMaxHealth() > 150f * (spell.dual ? 1.5f : 1f)) {
            Notifier.message(caster, Component.translatable("message.skycraft.illusion_too_strong", target.getDisplayName()));
            return;
        }
        if (isSummon(mob)) {
            dismiss(mob);
        } else {
            Targeting.hurtNoKnockback(mob, MagicDamage.source(level, MagicDamage.SPELL, null, caster), mob.getHealth() + 20f);
        }
        Notifier.message(caster, Component.translatable("message.skycraft.banished"));
        SpellEffects.xp(caster, spell);
    }

    /** Command Daedra: a summoned or wild daedra fights for you for 60 seconds. */
    public static void commandHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.CONJURE, 1f);
        if (!(target instanceof Mob mob) || !isDaedra(mob) || caster.getUUID().equals(ownerOf(mob))) return;
        if (target.getType().builtInRegistryHolder().is(com.skycraft.combat.CombatHandler.BOSSES)) return;
        String kind = mob.getPersistentData().getStringOr(KIND, "");
        mob.addTag(TAG);
        CompoundTag pd = mob.getPersistentData();
        pd.store(OWNER, net.minecraft.core.UUIDUtil.CODEC, caster.getUUID());
        pd.putLong(UNTIL, com.skycraft.world.RestManager.liveTime(level) + spell.duration);
        pd.putString(KIND, kind.isEmpty() ? "commanded" : kind);
        mob.setTarget(null);
        mob.setPersistenceRequired();
        MagicFx.send(mob, MagicFx.AURA, Element.CONJURE, mob.position(), mob.position(), mob.getId(), 3);
        SpellSounds.world(level, pos, SpellSounds.Slot.SUMMON, 1f, 1.2f);
        Notifier.message(caster, Component.translatable("message.skycraft.commanded", mob.getDisplayName()));
        SpellEffects.xp(caster, spell);
    }

    /**
     * Conjures a summon of {@code kind} (see {@link #entityType}) for a shout or power: Call of Valor, Call Dragon.
     * Doesn't count against the conjuration summon limit only if {@code extraSlot} is set (shout allies).
     */
    @Nullable
    public static Mob conjureAlly(ServerPlayer p, String kind, String nameKind, double range, int duration, Consumer<Mob> setup) {
        Mob mob = conjureAt(p, entityType(kind), nameKind, null, range, duration, true, setup);
        if (mob != null) {
            Vec3 pos = mob.position();
            MagicFx.sendNear(p.level(), pos, 96, MagicFx.SUMMON, Element.HOLY, pos, pos, mob.getId(), Math.round(mob.getBbHeight() * 10));
        }
        return mob;
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
        return summon(p, spell, type, kind, atronach, undead, -1, setup);
    }

    /** {@code fixedDuration} > 0 overrides the spell's duration and its perks (permanent thralls). */
    private static Spell.Result summon(ServerPlayer p, Spell spell, EntityType<? extends Mob> type, String kind,
                                       boolean atronach, boolean undead, int fixedDuration, Consumer<Mob> setup) {
        ServerLevel level = p.level();
        double range = 14 * (1 + 0.5 * Perks.rank(p, "conjuration.summoner"));
        int duration = spell.duration;
        if (atronach && Perks.has(p, "conjuration.atromancy")) duration *= 2;
        if (undead && Perks.has(p, "conjuration.necromancy")) duration *= 2;
        if (fixedDuration > 0) duration = fixedDuration;
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
        return conjureAt(p, type, kind, null, range, duration, false, setup);
    }

    /** Like {@link #conjure} but at a fixed position (a raised corpse). */
    @Nullable
    public static Mob conjureAt(ServerPlayer p, EntityType<? extends Mob> type, String kind, Vec3 at, int duration, Consumer<Mob> setup) {
        return conjureAt(p, type, kind, at, 0, duration, false, setup);
    }

    @Nullable
    private static Mob conjureAt(ServerPlayer p, EntityType<? extends Mob> type, String kind, @Nullable Vec3 at, double range, int duration,
                                 boolean free, Consumer<Mob> setup) {
        ServerLevel level = p.level();
        Mob mob = type.create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        if (mob == null) return null;
        Vec3 pos = at != null ? at : spawnPos(p, range, mob);
        mob.snapTo(pos.x, pos.y, pos.z, p.getYRot(), 0f);
        mob.setYHeadRot(p.getYRot());
        setup.accept(mob);

        // Limit: one summon (two with Twin Souls). The oldest is dismissed, like Skyrim.
        // Shout allies (Call of Valor, Call Dragon) are "free": they neither count nor get dismissed.
        int max = Perks.has(p, "conjuration.twin_souls") ? 2 : 1;
        List<Mob> existing = summonsOf(p);
        existing.removeIf(m -> m.getPersistentData().getBooleanOr(FREE, false));
        existing.sort(Comparator.comparingLong(m -> m.getPersistentData().getLongOr(UNTIL, 0L)));
        while (!free && existing.size() >= max) dismiss(existing.remove(0));

        mob.addTag(TAG);
        CompoundTag pd = mob.getPersistentData();
        pd.store(OWNER, net.minecraft.core.UUIDUtil.CODEC, p.getUUID());
        pd.putLong(UNTIL, com.skycraft.world.RestManager.liveTime(level) + duration);
        pd.putString(KIND, kind);
        if (free) pd.putBoolean(FREE, true);
        mob.setPersistenceRequired();
        mob.setCustomName(Component.translatable("entity.skycraft.summon." + kind));
        level.addFreshEntity(mob);
        return mob;
    }

    /** Where the player is looking (up to {@code range}), dropped to the ground; falls back to just in front. */
    private static Vec3 spawnPos(ServerPlayer p, double range, Mob mob) {
        ServerLevel level = p.level();
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1f).scale(range));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        BlockPos pos = hit.getType() == HitResult.Type.MISS ? BlockPos.containing(end) : hit.getBlockPos().relative(hit.getDirection());
        for (int i = 0; i < 10 && level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty(); i++) pos = pos.below();
        Vec3 v = Vec3.atBottomCenterOf(pos);
        if (level.noCollision(mob, mob.getType().getDimensions().makeBoundingBox(v))) return v;
        Vec3 look = p.getViewVector(1f).multiply(1, 0, 1);
        if (look.lengthSqr() < 1e-4) look = new Vec3(0, 0, 1);
        Vec3 front = p.position().add(look.normalize().scale(1.5));
        if (level.noCollision(mob, mob.getType().getDimensions().makeBoundingBox(front))) return front;
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
        if (!pd.read(OWNER, net.minecraft.core.UUIDUtil.CODEC).isPresent() || !pd.contains(UNTIL)) return;
        e.addTag(TAG);
        if (!pd.contains(KIND)) pd.putString(KIND, "familiar");
    }

    /** Animal Allegiance / Command Animal: an animal fights for {@code owner} until {@code until}. */
    public static void makeAlly(Mob mob, ServerPlayer owner, long until) {
        long liveNow = com.skycraft.world.RestManager.liveTime(owner.level());
        long gameNow = owner.level().getGameTime();
        if (until < liveNow && until > gameNow) {
            until = liveNow + (until - gameNow);
        }
        mob.addTag(ALLY_TAG);
        CompoundTag pd = mob.getPersistentData();
        pd.store(OWNER, net.minecraft.core.UUIDUtil.CODEC, owner.getUUID());
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
        long now = com.skycraft.world.RestManager.liveTime(level);
        long until = mob.getPersistentData().getLongOr(UNTIL, 0L);
        boolean ownerOk = owner != null && owner.isAlive() && owner.level() == level;
        if (summon) {
            if (!ownerOk || now >= until) {
                if (owner != null && now >= until) Notifier.message(owner, Component.translatable("message.skycraft.summon_expired", mob.getDisplayName()));
                if (owner != null && now >= until && "flaming_familiar".equals(mob.getPersistentData().getStringOr(KIND, ""))) explode(level, mob, owner);
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
        double dist = mob.distanceToSqr(owner);
        if (summon) {
            if (dist > 32 * 32) {
                Vec3 behind = owner.position().subtract(owner.getViewVector(1f).multiply(2, 0, 2));
                mob.teleportTo(behind.x, owner.getY(), behind.z);
                mob.getNavigation().stop();
            } else if (mob.getTarget() == null && dist > 7 * 7) {
                mob.getNavigation().moveTo(owner, 1.2);
            }
            ambient(level, mob);
        } else if (mob.getTarget() == null && dist > 6 * 6 && dist < 36 * 36) {
            mob.getNavigation().moveTo(owner, 1.15);
        }
    }

    /** A Flaming Familiar's last act: it bursts into flame, burning its owner's enemies nearby. */
    private static void explode(ServerLevel level, Mob mob, ServerPlayer owner) {
        Vec3 c = mob.position().add(0, mob.getBbHeight() / 2, 0);
        MagicFx.burst(level, c, Element.FIRE, 3f);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1f, 1.3f);
        for (LivingEntity e : Targeting.around(level, c, 3.5, e -> Targeting.canHarm(owner, e))) {
            Targeting.hurtNoKnockback(e, MagicDamage.source(level, MagicDamage.FIRE, null, owner), 6f);
            e.igniteForSeconds(4);
        }
    }

    private static void ambient(ServerLevel level, Mob mob) {
        String kind = mob.getPersistentData().getStringOr(KIND, "");
        ParticleOptions particle = switch (kind) {
            case "flame_atronach", "flaming_familiar" -> ParticleTypes.FLAME;
            case "frost_atronach" -> ParticleTypes.SNOWFLAKE;
            case "storm_atronach" -> ParticleTypes.ELECTRIC_SPARK;
            case "dremora_lord" -> ParticleTypes.SMALL_FLAME;
            case "seeker" -> ParticleTypes.SQUID_INK;
            case "sovngarde_hero", "felldir", "gormlaith", "hakon" -> ParticleTypes.END_ROD;
            default -> ParticleTypes.SOUL;
        };
        level.sendParticles(particle, mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(), 3,
                mob.getBbWidth() * 0.4, mob.getBbHeight() * 0.3, mob.getBbWidth() * 0.4, 0.01);
    }
}
