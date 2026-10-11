package com.skycraft.crafting.arcane.effect;

import com.skycraft.Skycraft;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Alchemy status effects that vanilla lacks. Pool effects act on the core's Magicka/Stamina pools; resist/weakness,
 * fortify-combat and fortify-sneak effects are applied by {@link com.skycraft.crafting.arcane.ArcaneEvents}; the
 * other fortify effects are markers that the owning modules may read by registry id
 * (e.g. {@code skycraft:fortify_smithing}). Fortify Enchanting/Alchemy are used by the arcane module itself.
 */
public final class ArcaneEffects {
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, Skycraft.MODID);

    private static final MobEffectCategory GOOD = MobEffectCategory.BENEFICIAL;
    private static final MobEffectCategory BAD = MobEffectCategory.HARMFUL;

    // ---------------------------------------------------------------- Magicka / Stamina pools
    public static final RegistryObject<MobEffect> RESTORE_MAGICKA = EFFECTS.register("restore_magicka", () -> new PoolInstantEffect(GOOD, 0x2E6BE6, true, 1));
    public static final RegistryObject<MobEffect> RESTORE_STAMINA = EFFECTS.register("restore_stamina", () -> new PoolInstantEffect(GOOD, 0x3FAF3F, false, 1));
    public static final RegistryObject<MobEffect> DAMAGE_MAGICKA = EFFECTS.register("damage_magicka", () -> new PoolInstantEffect(BAD, 0x23305E, true, -1));
    public static final RegistryObject<MobEffect> DAMAGE_STAMINA = EFFECTS.register("damage_stamina", () -> new PoolInstantEffect(BAD, 0x4F5E23, false, -1));
    public static final RegistryObject<MobEffect> REGENERATE_MAGICKA = EFFECTS.register("regenerate_magicka", () -> new PoolTickEffect(GOOD, 0x5A8CFF, true, 3f));
    public static final RegistryObject<MobEffect> REGENERATE_STAMINA = EFFECTS.register("regenerate_stamina", () -> new PoolTickEffect(GOOD, 0x7AD36A, false, 3f));
    public static final RegistryObject<MobEffect> DAMAGE_MAGICKA_REGEN = EFFECTS.register("damage_magicka_regen", () -> new PoolTickEffect(BAD, 0x404880, true, -1.5f));
    public static final RegistryObject<MobEffect> DAMAGE_STAMINA_REGEN = EFFECTS.register("damage_stamina_regen", () -> new PoolTickEffect(BAD, 0x607040, false, -1.5f));
    public static final RegistryObject<MobEffect> RAVAGE_MAGICKA = EFFECTS.register("ravage_magicka", () -> new PoolTickEffect(BAD, 0x302060, true, -4f));
    public static final RegistryObject<MobEffect> RAVAGE_STAMINA = EFFECTS.register("ravage_stamina", () -> new PoolTickEffect(BAD, 0x505020, false, -4f));
    /** Skyrim's "Lingering Damage Magicka/Stamina" poisons: a slow drain (Ravage drains faster but for less time). */
    public static final RegistryObject<MobEffect> LINGERING_DAMAGE_MAGICKA = EFFECTS.register("lingering_damage_magicka", () -> new PoolTickEffect(BAD, 0x283868, true, -2.5f));
    public static final RegistryObject<MobEffect> LINGERING_DAMAGE_STAMINA = EFFECTS.register("lingering_damage_stamina", () -> new PoolTickEffect(BAD, 0x48562A, false, -2.5f));
    /** Fortify Magicka/Stamina: +25 to the pool's maximum per level, kept in the core's {@code module("bonus")} by ArcaneEvents. */
    public static final RegistryObject<MobEffect> FORTIFY_MAGICKA = marker("fortify_magicka", GOOD, 0x3C78F0);
    public static final RegistryObject<MobEffect> FORTIFY_STAMINA = marker("fortify_stamina", GOOD, 0x4CC04C);

    // ---------------------------------------------------------------- resistances & weaknesses (ArcaneEvents)
    public static final RegistryObject<MobEffect> RESIST_FROST = marker("resist_frost", GOOD, 0x9AD8F0);
    public static final RegistryObject<MobEffect> RESIST_SHOCK = marker("resist_shock", GOOD, 0xD8D060);
    public static final RegistryObject<MobEffect> RESIST_POISON = marker("resist_poison", GOOD, 0x60A040);
    public static final RegistryObject<MobEffect> RESIST_MAGIC = marker("resist_magic", GOOD, 0xB080E0);
    public static final RegistryObject<MobEffect> WEAKNESS_TO_FIRE = marker("weakness_to_fire", BAD, 0xC04020);
    public static final RegistryObject<MobEffect> WEAKNESS_TO_FROST = marker("weakness_to_frost", BAD, 0x6090B0);
    public static final RegistryObject<MobEffect> WEAKNESS_TO_SHOCK = marker("weakness_to_shock", BAD, 0x909030);
    public static final RegistryObject<MobEffect> WEAKNESS_TO_POISON = marker("weakness_to_poison", BAD, 0x406020);
    public static final RegistryObject<MobEffect> WEAKNESS_TO_MAGIC = marker("weakness_to_magic", BAD, 0x704090);

    // ---------------------------------------------------------------- fortify skills
    public static final RegistryObject<MobEffect> FORTIFY_ONE_HANDED = marker("fortify_one_handed", GOOD, 0xC0A060);
    public static final RegistryObject<MobEffect> FORTIFY_TWO_HANDED = marker("fortify_two_handed", GOOD, 0xA08040);
    public static final RegistryObject<MobEffect> FORTIFY_ARCHERY = marker("fortify_archery", GOOD, 0x80A040);
    public static final RegistryObject<MobEffect> FORTIFY_LIGHT_ARMOR = marker("fortify_light_armor", GOOD, 0x70A070);
    public static final RegistryObject<MobEffect> FORTIFY_HEAVY_ARMOR = marker("fortify_heavy_armor", GOOD, 0x707090);
    public static final RegistryObject<MobEffect> FORTIFY_SNEAK = marker("fortify_sneak", GOOD, 0x505050);
    public static final RegistryObject<MobEffect> FORTIFY_SMITHING = marker("fortify_smithing", GOOD, 0xB06030);
    public static final RegistryObject<MobEffect> FORTIFY_ENCHANTING = marker("fortify_enchanting", GOOD, 0x8060D0);
    public static final RegistryObject<MobEffect> FORTIFY_ALCHEMY = marker("fortify_alchemy", GOOD, 0x50B080);
    public static final RegistryObject<MobEffect> FORTIFY_DESTRUCTION = marker("fortify_destruction", GOOD, 0xE05030);
    public static final RegistryObject<MobEffect> FORTIFY_RESTORATION = marker("fortify_restoration", GOOD, 0xF0E080);
    public static final RegistryObject<MobEffect> FORTIFY_CONJURATION = marker("fortify_conjuration", GOOD, 0x9050C0);
    public static final RegistryObject<MobEffect> FORTIFY_ILLUSION = marker("fortify_illusion", GOOD, 0xC070D0);
    public static final RegistryObject<MobEffect> FORTIFY_ALTERATION = marker("fortify_alteration", GOOD, 0x50A0C0);
    public static final RegistryObject<MobEffect> FORTIFY_LOCKPICKING = marker("fortify_lockpicking", GOOD, 0x909090);
    public static final RegistryObject<MobEffect> FORTIFY_PICKPOCKET = marker("fortify_pickpocket", GOOD, 0x60A060);
    public static final RegistryObject<MobEffect> FORTIFY_BARTER = marker("fortify_barter", GOOD, 0xE0C040);
    /**
     * +25 carry weight per level (kept in the core's {@code module("bonus").carry} by ArcaneEvents), and it offsets
     * heavy-armor slowness a little (+5% speed per level).
     */
    public static final RegistryObject<MobEffect> FORTIFY_CARRY_WEIGHT = EFFECTS.register("fortify_carry_weight",
            () -> new MarkerEffect(GOOD, 0xA07850).addAttributeModifier(Attributes.MOVEMENT_SPEED,
                    "8a3c1f52-6b0e-4c1d-9e57-2f4a7b3c9d01", 0.05, AttributeModifier.Operation.MULTIPLY_TOTAL));

    // ---------------------------------------------------------------- behaviour
    public static final RegistryObject<MobEffect> FEAR = EFFECTS.register("fear", () -> new FearEffect(BAD, 0x502040));
    public static final RegistryObject<MobEffect> FRENZY = EFFECTS.register("frenzy", () -> new FrenzyEffect(BAD, 0xC02020));
    public static final RegistryObject<MobEffect> CURE_DISEASE = EFFECTS.register("cure_disease", () -> new CureEffect(GOOD, 0xE0E0C0));
    public static final RegistryObject<MobEffect> CURE_POISON = EFFECTS.register("cure_poison", () -> new CurePoisonEffect(GOOD, 0x9CE07A));

    private ArcaneEffects() {}

    private static RegistryObject<MobEffect> marker(String id, MobEffectCategory category, int color) {
        return EFFECTS.register(id, () -> new MarkerEffect(category, color));
    }

    public static void init(IEventBus modBus) {
        EFFECTS.register(modBus);
    }

    /** Amplifier + 1 of the effect on the entity, or 0 when absent. */
    public static int level(LivingEntity entity, RegistryObject<MobEffect> effect) {
        MobEffectInstance inst = entity.getEffect(effect.get());
        return inst == null ? 0 : inst.getAmplifier() + 1;
    }

    // ================================================================ effect classes

    /** An effect with no ticking behaviour; its meaning is implemented by event handlers. */
    public static class MarkerEffect extends MobEffect {
        public MarkerEffect(MobEffectCategory category, int color) {
            super(category, color);
        }
    }

    /** Instantly restores (sign +1) or damages (sign -1) the target's Magicka or Stamina: 25 points per level. */
    public static class PoolInstantEffect extends MobEffect {
        private final boolean magicka;
        private final int sign;

        public PoolInstantEffect(MobEffectCategory category, int color, boolean magicka, int sign) {
            super(category, color);
            this.magicka = magicka;
            this.sign = sign;
        }

        @Override
        public boolean isInstantenous() {
            return true;
        }

        @Override
        public boolean isDurationEffectTick(int duration, int amplifier) {
            return duration >= 1;
        }

        @Override
        public void applyEffectTick(LivingEntity entity, int amplifier) {
            apply(entity, amplifier, 1.0);
        }

        @Override
        public void applyInstantenousEffect(@Nullable Entity source, @Nullable Entity indirectSource, LivingEntity target, int amplifier, double health) {
            apply(target, amplifier, health);
        }

        private void apply(LivingEntity target, int amplifier, double scale) {
            if (target.level().isClientSide) return;
            float amount = (float) (25.0 * (amplifier + 1) * scale);
            if (target instanceof Player player) {
                PlayerData data = SkyData.get(player);
                if (magicka) data.setMagicka(data.getMagicka() + sign * amount);
                else data.setStamina(data.getStamina() + sign * amount);
            } else if (sign < 0) {
                // creatures have no pools: drained creatures are briefly weakened / slowed instead
                int ticks = (int) (60 + 20 * amplifier * scale);
                target.addEffect(new MobEffectInstance(magicka ? MobEffects.WEAKNESS : MobEffects.MOVEMENT_SLOWDOWN, ticks, 0));
            }
        }
    }

    /** Changes the target's Magicka or Stamina by {@code perSecond * level} every second. */
    public static class PoolTickEffect extends MobEffect {
        private final boolean magicka;
        private final float perSecond;

        public PoolTickEffect(MobEffectCategory category, int color, boolean magicka, float perSecond) {
            super(category, color);
            this.magicka = magicka;
            this.perSecond = perSecond;
        }

        @Override
        public boolean isDurationEffectTick(int duration, int amplifier) {
            return duration % 20 == 0;
        }

        @Override
        public void applyEffectTick(LivingEntity entity, int amplifier) {
            if (entity.level().isClientSide || !(entity instanceof Player player)) return;
            PlayerData data = SkyData.get(player);
            float delta = perSecond * (amplifier + 1);
            if (magicka) data.setMagicka(data.getMagicka() + delta);
            else data.setStamina(data.getStamina() + delta);
        }
    }

    /** Creatures stop attacking and flee from the nearest player. */
    public static class FearEffect extends MobEffect {
        public FearEffect(MobEffectCategory category, int color) {
            super(category, color);
        }

        @Override
        public boolean isDurationEffectTick(int duration, int amplifier) {
            return true;
        }

        @Override
        public void applyEffectTick(LivingEntity entity, int amplifier) {
            if (entity.level().isClientSide || !(entity instanceof Mob mob)) return;
            mob.setTarget(null);
            if (mob instanceof PathfinderMob pathfinder && mob.tickCount % 10 == 0) {
                Player threat = mob.level().getNearestPlayer(mob, 16.0);
                if (threat != null) {
                    Vec3 away = DefaultRandomPos.getPosAway(pathfinder, 16, 7, threat.position());
                    if (away != null) pathfinder.getNavigation().moveTo(away.x, away.y, away.z, 1.35);
                }
            }
        }
    }

    /** Creatures attack the nearest other creature. */
    public static class FrenzyEffect extends MobEffect {
        public FrenzyEffect(MobEffectCategory category, int color) {
            super(category, color);
        }

        @Override
        public boolean isDurationEffectTick(int duration, int amplifier) {
            return duration % 20 == 0;
        }

        @Override
        public void applyEffectTick(LivingEntity entity, int amplifier) {
            if (entity.level().isClientSide || !(entity instanceof Mob mob)) return;
            LivingEntity current = mob.getTarget();
            if (current != null && current.isAlive() && !(current instanceof Player)) return;
            List<LivingEntity> near = mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(12.0),
                    e -> e != mob && e.isAlive() && !(e instanceof Player));
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (LivingEntity e : near) {
                double d = e.distanceToSqr(mob);
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
            if (best != null) mob.setTarget(best);
        }
    }

    /** Instantly cures "diseases": hunger, nausea, weakness and mining fatigue. */
    public static class CureEffect extends MobEffect {
        public CureEffect(MobEffectCategory category, int color) {
            super(category, color);
        }

        @Override
        public boolean isInstantenous() {
            return true;
        }

        @Override
        public void applyInstantenousEffect(@Nullable Entity source, @Nullable Entity indirectSource, LivingEntity target, int amplifier, double health) {
            if (target.level().isClientSide) return;
            target.removeEffect(MobEffects.HUNGER);
            target.removeEffect(MobEffects.CONFUSION);
            target.removeEffect(MobEffects.WEAKNESS);
            target.removeEffect(MobEffects.DIG_SLOWDOWN);
        }

        // applyEffectTick intentionally does nothing: removing effects while the entity iterates its effects would
        // throw a ConcurrentModificationException (only reachable through /effect).
    }

    /** Instantly cures poisons: vanilla poison and the lingering pool drains. */
    public static class CurePoisonEffect extends MobEffect {
        public CurePoisonEffect(MobEffectCategory category, int color) {
            super(category, color);
        }

        @Override
        public boolean isInstantenous() {
            return true;
        }

        @Override
        public void applyInstantenousEffect(@Nullable Entity source, @Nullable Entity indirectSource, LivingEntity target, int amplifier, double health) {
            if (target.level().isClientSide) return;
            target.removeEffect(MobEffects.POISON);
            target.removeEffect(LINGERING_DAMAGE_MAGICKA.get());
            target.removeEffect(LINGERING_DAMAGE_STAMINA.get());
        }
    }
}
