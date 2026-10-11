package com.skycraft.survival;

import com.skycraft.Skycraft;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Status effects of the survival module: food effects, the Skyrim diseases (see {@link Diseases}) and the shrine
 * blessings of the Nine Divines (see {@link Divine}). Diseases and blessings are not cured by milk.
 */
public final class SurvivalEffects {
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, Skycraft.MODID);

    // ---------------------------------------------------------------- food
    /** Restores 3 stamina per second per level (cooked food, mead). */
    public static final RegistryObject<MobEffect> REGENERATE_STAMINA_FOOD = EFFECTS.register("regenerate_stamina_food",
            () -> new StaminaFoodEffect(MobEffectCategory.BENEFICIAL, 0x8CCB5E));
    /** Stews: stamina regenerates 25% faster per level (applied by {@link SurvivalEvents}). */
    public static final RegistryObject<MobEffect> FORTIFY_STAMINA_REGEN = EFFECTS.register("fortify_stamina_regen",
            () -> new SurvivalEffect(MobEffectCategory.BENEFICIAL, 0xC8A050, true));

    // ---------------------------------------------------------------- survival mode (survival/mode)
    /** A hot stew or soup: the cold can't reach you as easily (and you warmed up when you ate it). */
    public static final RegistryObject<MobEffect> WARMING_MEAL = EFFECTS.register("warming_meal",
            () -> new SurvivalEffect(MobEffectCategory.BENEFICIAL, 0xE08A40, false));
    /**
     * Slept in a bedroll or tent: skills improve 5% faster (a proper bed gives the core's Well Rested instead, and
     * sleeping near your spouse its Lover's Comfort).
     */
    public static final RegistryObject<MobEffect> RESTED = EFFECTS.register("rested",
            () -> new SurvivalEffect(MobEffectCategory.BENEFICIAL, 0x9AB0D8, true));

    // ---------------------------------------------------------------- diseases (Diseases lists them)
    public static final RegistryObject<MobEffect> ATAXIA = disease("ataxia", 0x7A8A5A);
    public static final RegistryObject<MobEffect> BONE_BREAK_FEVER = disease("bone_break_fever", 0xB8A890);
    public static final RegistryObject<MobEffect> BRAIN_ROT = disease("brain_rot", 0x8A6A8A);
    public static final RegistryObject<MobEffect> RATTLES = disease("rattles", 0x9A9A60);
    public static final RegistryObject<MobEffect> ROCKJOINT = disease("rockjoint", 0x707070);
    public static final RegistryObject<MobEffect> WITBANE = disease("witbane", 0x5A6A9A);
    public static final RegistryObject<MobEffect> SWAMP_ROT = disease("swamp_rot", 0x4A6A3A);
    public static final RegistryObject<MobEffect> COLLYWOBBLES = EFFECTS.register("collywobbles",
            () -> new SurvivalEffect(MobEffectCategory.HARMFUL, 0xA0A040, true)
                    .addAttributeModifier(Attributes.ATTACK_SPEED, "5b8e2c1a-3d4f-4e6a-9b7c-1d2e3f4a5b01", -0.10, AttributeModifier.Operation.MULTIPLY_TOTAL));
    public static final RegistryObject<MobEffect> GREENSPORE = disease("greenspore", 0x5AA05A);
    public static final RegistryObject<MobEffect> DROOPS = EFFECTS.register("droops",
            () -> new SurvivalEffect(MobEffectCategory.HARMFUL, 0x8A7A6A, true)
                    .addAttributeModifier(Attributes.MOVEMENT_SPEED, "5b8e2c1a-3d4f-4e6a-9b7c-1d2e3f4a5b02", -0.10, AttributeModifier.Operation.MULTIPLY_TOTAL));

    // ---------------------------------------------------------------- blessings of the Divines
    public static final Map<Divine, RegistryObject<MobEffect>> BLESSINGS = new EnumMap<>(Divine.class);

    static {
        for (Divine d : Divine.values()) {
            BLESSINGS.put(d, EFFECTS.register("blessing_" + d.id(), () -> new SurvivalEffect(MobEffectCategory.BENEFICIAL, d.color, true)));
        }
    }

    private SurvivalEffects() {}

    private static RegistryObject<MobEffect> disease(String id, int color) {
        return EFFECTS.register(id, () -> new SurvivalEffect(MobEffectCategory.HARMFUL, color, true));
    }

    public static void init(IEventBus modBus) {
        EFFECTS.register(modBus);
    }

    // ================================================================ effect classes

    /** An effect whose meaning is implemented by event handlers. {@code noMilk}: milk buckets don't remove it. */
    public static class SurvivalEffect extends MobEffect {
        private final boolean noMilk;

        public SurvivalEffect(MobEffectCategory category, int color, boolean noMilk) {
            super(category, color);
            this.noMilk = noMilk;
        }

        @Override
        public List<ItemStack> getCurativeItems() {
            return noMilk ? new ArrayList<>() : super.getCurativeItems();
        }
    }

    /** Restores {@code 3 * level} stamina every second. */
    public static class StaminaFoodEffect extends MobEffect {
        public StaminaFoodEffect(MobEffectCategory category, int color) {
            super(category, color);
        }

        @Override
        public boolean isDurationEffectTick(int duration, int amplifier) {
            return duration % 20 == 0;
        }

        @Override
        public void applyEffectTick(LivingEntity entity, int amplifier) {
            if (entity.level().isClientSide || !(entity instanceof Player player)) return;
            PlayerData data = SkyData.get(player);
            data.setStamina(data.getStamina() + 3f * (amplifier + 1));
        }
    }
}
