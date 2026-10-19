package com.nirncraft.creatures;

import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.Holder;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Items of the bestiary: the Dragon Priest masks (helmets with their old powers) and the creature ingredients and
 * trophies the new creatures drop (alchemy reagents, hides, scales, horns).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class CreatureItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, NirnCraft.MODID);

    /** Each mask's power while worn (refreshed every two seconds). */
    public enum Mask {
        MOROKEI("morokei", MobEffects.HASTE, 0),
        RAHGOT("rahgot", MobEffects.HEALTH_BOOST, 1),
        VOKUN("vokun", MobEffects.LUCK, 0),
        VOLSUNG("volsung", MobEffects.WATER_BREATHING, 0),
        NAHKRIIN("nahkriin", MobEffects.STRENGTH, 0),
        OTAR("otar", MobEffects.FIRE_RESISTANCE, 0),
        KROSIS("krosis", MobEffects.LUCK, 1),
        HEVNORAAK("hevnoraak", MobEffects.RESISTANCE, 0),
        KONAHRIK("konahrik", MobEffects.REGENERATION, 0);

        public final String id;
        final Holder<MobEffect> effect;
        final int amplifier;

        Mask(String id, Holder<MobEffect> effect, int amplifier) {
            this.id = id;
            this.effect = effect;
            this.amplifier = amplifier;
        }
    }

    private static final Map<String, Supplier<Item>> MASKS = new LinkedHashMap<>();

    static {
        for (Mask m : Mask.values()) {
            MASKS.put(m.id, ITEMS.register("mask_" + m.id, () -> new MaskItem(m, com.nirncraft.platform.SkyProperties.item("mask_" + m.id))));
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
        for (String id : INGREDIENTS) ITEMS.register(id, () -> new Item(com.nirncraft.platform.SkyProperties.item(id)));
    }

    private CreatureItems() {}

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    public static Optional<Item> mask(String id) {
        Supplier<Item> ro = MASKS.get(id);
        return ro == null ? Optional.empty() : Optional.of(ro.get());
    }

    /** Worn masks grant their power. */
    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || event.getEntity().level().isClientSide() || event.getEntity().tickCount % 40 != 0) return;
        Player p = event.getEntity();
        if (!(p.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof MaskItem mask)) return;
        Mask m = mask.mask;
        if (m == Mask.KONAHRIK && p.getHealth() > p.getMaxHealth() * 0.4f) return; // Konahrik's gift answers peril
        p.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(m.effect), 60, m.amplifier, true, false, true));
        if (m == Mask.HEVNORAAK) p.removeEffect(com.nirncraft.platform.Effects.holder(MobEffects.POISON));
    }

    /** A Dragon Priest mask: a helmet of ancient metal. */
    public static final class MaskItem extends Item {
        final Mask mask;

        MaskItem(Mask mask, Properties properties) {
            super(properties.humanoidArmor(material(mask), ArmorType.HELMET).rarity(mask == Mask.KONAHRIK ? Rarity.EPIC : Rarity.RARE).fireResistant());
            this.mask = mask;
        }

        @Override
        public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
            tooltip.accept(Component.translatable("item.nirncraft.mask_" + mask.id + ".desc").withStyle(net.minecraft.ChatFormatting.GOLD));
        }
    }

    /** The original helmet defense, 440 durability, toughness and knockback resistance. */
    private static ArmorMaterial material(Mask mask) {
        return new ArmorMaterial(40, Map.of(ArmorType.HELMET, mask == Mask.KONAHRIK ? 5 : 4), 18,
                SoundEvents.ARMOR_EQUIP_GOLD, 3f, 0.05f,
                net.minecraft.tags.TagKey.create(Registries.ITEM, net.minecraft.resources.Identifier.fromNamespaceAndPath(NirnCraft.MODID, "repair_none")),
                net.minecraft.resources.ResourceKey.create(net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID,
                        net.minecraft.resources.Identifier.fromNamespaceAndPath(NirnCraft.MODID, "mask_" + mask.id)));
    }
}
