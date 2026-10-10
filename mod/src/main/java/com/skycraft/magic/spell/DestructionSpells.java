package com.skycraft.magic.spell;

import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicRegistry;
import com.skycraft.magic.MagicScheduler;
import com.skycraft.magic.Targeting;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import com.skycraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Destruction spells added on top of the bolts and beams of {@link SpellEffects}: runes, elemental cloaks (and
 * Stendarr's Aura, which works the same way against the undead), walls and the Lightning Storm beam.
 */
public final class DestructionSpells {
    /** Persistent-data keys on the caster: cloak magnitude (health/s) and the spell that made it, per element. */
    private static final String CLOAK_MAG = "skycraft_cloak_mag_";
    private static final String CLOAK_SPELL = "skycraft_cloak_spell_";
    /** At most this many runes per caster (Skyrim lets you litter a corridor, but not a whole dungeon). */
    private static final int MAX_RUNES = 3;
    /** Last game time a caster's wall hurt something (concentration XP only while the wall is doing work). */
    private static final Map<UUID, Long> WALL_HIT = new HashMap<>();

    private DestructionSpells() {}

    // ================================================================== runes

    /** Fire/Frost/Lightning Rune: a glyph on the surface you aim at that explodes when an enemy comes near. */
    public static Spell.Result rune(ServerPlayer p, Spell spell, int tick) {
        ServerLevel level = p.serverLevel();
        double range = Perks.has(p, "destruction.rune_master") ? 50 : 10;
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1f).scale(range));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) {
            Notifier.message(p, Component.translatable("message.skycraft.rune_no_surface"));
            return Spell.Result.FAILED;
        }
        Vec3 at = hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(0.1));
        // Keep the newest runes only.
        List<MagicScheduler.Area> runes = MagicScheduler.areas(p.getUUID(), "rune#");
        while (runes.size() >= MAX_RUNES) MagicScheduler.remove(runes.remove(0));
        Element element = spell.element;
        float damage = spell.magnitude;
        String kind = "rune#" + level.getGameTime() + "#" + p.getRandom().nextInt(1000);
        MagicScheduler.area(p, at, false, spell.radius, spell.duration, 2, kind, (lvl, owner, area) -> {
            if (area.age % 30 == 0) {
                MagicFx.sendNear(lvl, area.center, 48, MagicFx.RUNE, element, area.center, area.center, -1, 10);
            }
            if (owner == null) return;
            List<LivingEntity> near = Targeting.around(lvl, area.center, 1.8, e -> Targeting.canHarm(owner, e));
            if (near.isEmpty()) return;
            area.ticksLeft = 0;
            MagicFx.burst(lvl, area.center, element, (float) area.radius);
            MagicFx.worldSound(lvl, area.center, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.2f, 1.3f);
            MagicFx.worldSound(lvl, area.center, element == Element.FIRE ? SoundEvents.BLAZE_SHOOT
                    : element == Element.FROST ? SoundEvents.GLASS_BREAK : SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 1f, 0.8f);
            if (SpellEffects.areaDamage(owner, spell, area.center, area.radius, damage, null, 0.6) > 0) SpellEffects.xp(owner, spell);
        });
        MagicFx.sendNear(level, at, 48, MagicFx.RUNE, element, at, at, -1, 20);
        SpellSounds.cast(p, spell);
        MagicFx.sound(p, at, SoundEvents.ENCHANTMENT_TABLE_USE, 1f, 0.7f);
        // The rune trains the skill when it goes off, not when it is drawn.
        return Spell.Result.CAST;
    }

    // ================================================================== cloaks

    private static MobEffect cloakEffect(Element element) {
        return switch (element) {
            case FIRE -> MagicRegistry.FLAME_CLOAK.get();
            case FROST -> MagicRegistry.FROST_CLOAK.get();
            case SHOCK -> MagicRegistry.LIGHTNING_CLOAK.get();
            default -> MagicRegistry.STENDARRS_AURA.get();
        };
    }

    /** Flame/Frost/Lightning Cloak and Stendarr's Aura: a damaging aura around the caster for a minute. */
    public static Spell.Result cloak(ServerPlayer p, Spell spell, int tick) {
        // Only one cloak at a time, like Skyrim.
        for (Element e : new Element[]{Element.FIRE, Element.FROST, Element.SHOCK, Element.HOLY}) {
            if (e != spell.element) p.removeEffect(cloakEffect(e));
        }
        MobEffect effect = cloakEffect(spell.element);
        boolean wasActive = p.hasEffect(effect);
        CompoundTag pd = p.getPersistentData();
        pd.putFloat(CLOAK_MAG + spell.element.name(), spell.magnitude);
        pd.putString(CLOAK_SPELL + spell.element.name(), spell.id);
        p.addEffect(new MobEffectInstance(effect, spell.duration, 0, false, false, true));
        MagicFx.send(p, MagicFx.AURA, spell.element, p.position(), p.position(), p.getId(), 2);
        MagicFx.sendNear(p.serverLevel(), p.position(), 48, MagicFx.RING, spell.element, p.position().add(0, 0.2, 0), p.position(), p.getId(),
                Math.round(spell.radius * 10));
        SpellSounds.cast(p, spell);
        // XP comes from the cloak burning enemies (pulse), not from putting it on.
        return wasActive ? Spell.Result.CAST : SpellEffects.buffResult(p, false);
    }

    /** One cloak pulse (every half second while the cloak effect lasts). */
    public static void cloakPulse(ServerPlayer p, Element element) {
        CompoundTag pd = p.getPersistentData();
        float perSecond = pd.getFloat(CLOAK_MAG + element.name());
        Spell spell = Spells.byId(pd.getString(CLOAK_SPELL + element.name()));
        if (spell == null || perSecond <= 0) return;
        ServerLevel level = p.serverLevel();
        if (p.level().getGameTime() % 20 < 10) {
            MagicFx.sendNear(level, p.position(), 32, MagicFx.RING, element, p.position().add(0, 0.6, 0), p.position(), p.getId(),
                    Math.round(spell.radius * 10));
        }
        int hits = 0;
        for (LivingEntity e : Targeting.around(p, p.position().add(0, 1, 0), spell.radius + 0.5, e -> Targeting.canHarm(p, e))) {
            if (element == Element.HOLY) {
                // Stendarr's Aura only burns the undead (and vampires, which are undead here).
                if (e.getMobType() != MobType.UNDEAD) continue;
                if (Targeting.hurtNoKnockback(e, SpellEffects.damage(p, Element.HOLY, null), perSecond / 2f * SpellMath.undeadMult(p, e))) {
                    e.setSecondsOnFire(2);
                    hits++;
                }
            } else if (SpellEffects.hit(p, spell, e, perSecond / 2f, null, true, false)) {
                hits++;
            }
        }
        if (hits > 0 && p.level().getGameTime() % 40 < 10) {
            // a cloak trains the skill while it works: a tenth of the casting cost every two seconds of use
            Progression.addSkillXp(p, spell.school.skill, spell.cost * 0.1f);
        }
    }

    // ================================================================== walls

    /** Wall of Flames/Frost/Storms: hold to draw a burning line on the ground where you aim. */
    public static Spell.Result wall(ServerPlayer p, Spell spell, int tick) {
        ServerLevel level = p.serverLevel();
        if (tick % 3 == 0) {
            Vec3 eye = p.getEyePosition();
            Vec3 end = eye.add(p.getViewVector(1f).scale(spell.radius));
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() != HitResult.Type.MISS) {
                BlockPos ground = hit.getBlockPos();
                if (hit.getDirection() != net.minecraft.core.Direction.UP) ground = ground.below();
                for (int i = 0; i < 3 && level.getBlockState(ground).getCollisionShape(level, ground).isEmpty(); i++) ground = ground.below();
                Vec3 at = Vec3.atBottomCenterOf(ground.above());
                Element element = spell.element;
                float perSecond = spell.magnitude;
                String kind = "wall#" + ground.getX() + "_" + ground.getZ();
                MagicScheduler.area(p, at, false, 1.3, 70, 5, kind, (lvl, owner, area) -> {
                    MagicFx.sendNear(lvl, area.center, 48, MagicFx.PILLAR, element, area.center, area.center, -1, 22);
                    if (owner == null || area.age % 10 != 0) return;
                    for (LivingEntity e : Targeting.around(lvl, area.center.add(0, 1, 0), area.radius + 0.4, e -> Targeting.canHarm(owner, e))) {
                        if (SpellEffects.hit(owner, spell, e, perSecond / 2f, null, true, false)) {
                            WALL_HIT.put(owner.getUUID(), lvl.getGameTime());
                        }
                    }
                });
            }
        }
        if (tick % 10 == 0) SpellSounds.loop(p, spell);
        Long last = WALL_HIT.get(p.getUUID());
        return last != null && level.getGameTime() - last < 20 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    // ================================================================== Lightning Storm

    /** Lightning Storm: after a two second charge, a massive beam that does 75 points of shock damage per second. */
    public static Spell.Result lightningStorm(ServerPlayer p, Spell spell, int tick) {
        ServerLevel level = p.serverLevel();
        Targeting.Ray ray = Targeting.ray(p, spell.radius, e -> Targeting.canHarm(p, e));
        Vec3 hand = Targeting.handPos(p);
        if (tick % 2 == 0) MagicFx.sendNear(level, hand, 96, MagicFx.ARC, Element.SHOCK, hand, ray.end(), -1, 1);
        if (tick % 6 == 0) MagicFx.burst(level, ray.end(), Element.SHOCK, 1.2f);
        if (tick % 10 == 0) {
            MagicFx.worldSound(level, ray.end(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 1.2f, 1.2f + p.getRandom().nextFloat() * 0.3f);
            SpellSounds.loop(p, spell);
        }
        if (tick % 5 != 0) return Spell.Result.CAST;
        Vitals.markInCombat(p);
        boolean hurt = false;
        if (ray.entity() != null) hurt = SpellEffects.hit(p, spell, ray.entity(), spell.magnitude / 4f, null, true, false);
        // the bolt's splash crackles over anything right next to the impact
        for (LivingEntity e : Targeting.around(p, ray.end(), 1.5, e -> e != ray.entity() && Targeting.canHarm(p, e))) {
            hurt |= SpellEffects.hit(p, spell, e, spell.magnitude / 12f, null, true, false);
        }
        if (hurt && spell.dual && Perks.has(p, "destruction.impact") && ray.entity() != null && tick % 20 == 0) {
            CombatHandler.stagger(p, ray.entity(), 15);
        }
        return hurt ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    public static void forget(UUID id) {
        WALL_HIT.remove(id);
    }
}
