package com.skycraft.creatures;

import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Items of the bestiary: the Dragon Priest masks (helmets with their old powers) and the creature ingredients and
 * trophies the new creatures drop (alchemy reagents, hides, scales, horns).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class CreatureItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Skycraft.MODID);

    /** Each mask's power while worn (refreshed every two seconds). */
    public enum Mask {
        MOROKEI("morokei", MobEffects.DIG_SPEED, 0),
        RAHGOT("rahgot", MobEffects.HEALTH_BOOST, 1),
        VOKUN("vokun", MobEffects.LUCK, 0),
        VOLSUNG("volsung", MobEffects.WATER_BREATHING, 0),
        NAHKRIIN("nahkriin", MobEffects.DAMAGE_BOOST, 0),
        OTAR("otar", MobEffects.FIRE_RESISTANCE, 0),
        KROSIS("krosis", MobEffects.LUCK, 1),
        HEVNORAAK("hevnoraak", MobEffects.DAMAGE_RESISTANCE, 0),
        KONAHRIK("konahrik", MobEffects.REGENERATION, 0);

        public final String id;
        final MobEffect effect;
        final int amplifier;

        Mask(String id, MobEffect effect, int amplifier) {
            this.id = id;
            this.effect = effect;
            this.amplifier = amplifier;
        }
    }

    private static final Map<String, RegistryObject<Item>> MASKS = new LinkedHashMap<>();

    static {
        for (Mask m : Mask.values()) {
            MASKS.put(m.id, ITEMS.register("mask_" + m.id, () -> new MaskItem(m)));
        }
    }

    /** Creature ingredients and trophies. */
    public static final List<String> INGREDIENTS = List.of(
            "chaurus_eggs", "frostbite_venom", "spriggan_sap", "hagraven_feathers", "hagraven_claw", "ice_wraith_teeth",
            "wolf_pelt", "ectoplasm", "glow_dust", "netch_jelly", "guar_hide", "scamp_skin",
            "cliff_racer_plumes", "kagouti_hide", "alit_hide", "ash_salts", "harpy_feather", "scorpion_stinger",
            "sea_serpent_scale", "kamal_horn", "tsaesci_scale", "indrik_feather", "minotaur_horn", "ogre_tooth",
            "dreugh_wax", "echatere_hide", "leviathan_tooth", "wamasu_scale", "gargoyle_shard", "durzog_hide",
            "nix_hound_meat", "dwemer_gear");

    static {
        for (String id : INGREDIENTS) ITEMS.register(id, () -> new Item(new Item.Properties()));
    }

    private CreatureItems() {}

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    public static Optional<Item> mask(String id) {
        RegistryObject<Item> ro = MASKS.get(id);
        return ro == null ? Optional.empty() : Optional.of(ro.get());
    }

    /** Worn masks grant their power. */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide || event.player.tickCount % 40 != 0) return;
        Player p = event.player;
        if (!(p.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof MaskItem mask)) return;
        Mask m = mask.mask;
        if (m == Mask.KONAHRIK && p.getHealth() > p.getMaxHealth() * 0.4f) return; // Konahrik's gift answers peril
        p.addEffect(new MobEffectInstance(m.effect, 60, m.amplifier, true, false, true));
        if (m == Mask.HEVNORAAK) p.removeEffect(MobEffects.POISON);
    }

    /** A Dragon Priest mask: a helmet of ancient metal. */
    public static final class MaskItem extends ArmorItem {
        final Mask mask;

        MaskItem(Mask mask) {
            super(new MaskMaterial(mask), Type.HELMET, new Item.Properties().rarity(mask == Mask.KONAHRIK ? Rarity.EPIC : Rarity.RARE).fireResistant());
            this.mask = mask;
        }

        @Override
        public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.translatable("item.skycraft.mask_" + mask.id + ".desc").withStyle(net.minecraft.ChatFormatting.GOLD));
        }
    }

    /** Masks are as strong as dragonplate helmets; each its own material so it has its own worn texture. */
    record MaskMaterial(Mask mask) implements ArmorMaterial {
        @Override
        public int getDurabilityForType(ArmorItem.Type type) {
            return 11 * 40;
        }

        @Override
        public int getDefenseForType(ArmorItem.Type type) {
            return mask == Mask.KONAHRIK ? 5 : 4;
        }

        @Override
        public int getEnchantmentValue() {
            return 18;
        }

        @Override
        public SoundEvent getEquipSound() {
            return SoundEvents.ARMOR_EQUIP_GOLD;
        }

        @Override
        public Ingredient getRepairIngredient() {
            return Ingredient.EMPTY;
        }

        @Override
        public String getName() {
            return Skycraft.MODID + ":mask_" + mask.id;
        }

        @Override
        public float getToughness() {
            return 3.0f;
        }

        @Override
        public float getKnockbackResistance() {
            return 0.05f;
        }
    }
}
