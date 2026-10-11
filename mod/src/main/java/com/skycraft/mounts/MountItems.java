package com.skycraft.mounts;

import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.HorseArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Mount gear: culture bardings for horses (vanilla horse armour slots, own textures), beast barding for the ridden
 * beasts, Black Marsh's marsh-shoes (no mount crosses the marsh: these speed you through mud and shallows), and the
 * tokens of the two legendary steeds (quest and Soul Cairn hooks): Shadowmere's bridle and Arvak's Skull.
 */
public final class MountItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Skycraft.MODID);

    public static final RegistryObject<Item> NORDIC_BARDING = barding("nordic_barding", 7);
    public static final RegistryObject<Item> IMPERIAL_BARDING = barding("imperial_barding", 9);
    public static final RegistryObject<Item> BRETON_BARDING = barding("breton_barding", 11);
    public static final RegistryObject<Item> REDGUARD_BARDING = barding("redguard_barding", 8);
    public static final RegistryObject<Item> ELVEN_BARDING = barding("elven_barding", 10);
    public static final RegistryObject<Item> BEAST_BARDING = ITEMS.register("beast_barding",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> MARSH_SHOES = ITEMS.register("marsh_shoes",
            () -> new MarshShoes(new Item.Properties()));
    public static final RegistryObject<Item> SHADOWMERE_BRIDLE = ITEMS.register("shadowmere_bridle",
            () -> new Token(MountKind.SHADOWMERE, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));
    public static final RegistryObject<Item> ARVAKS_SKULL = ITEMS.register("arvaks_skull",
            () -> new Token(MountKind.ARVAK, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    private MountItems() {}

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private static RegistryObject<Item> barding(String id, int protection) {
        return ITEMS.register(id, () -> new HorseArmorItem(protection,
                new ResourceLocation(Skycraft.MODID, "textures/entity/mounts/horse_armor_" + id + ".png"),
                new Item.Properties().stacksTo(1)));
    }

    /** The horse barding a province's stablemasters sell. */
    public static Item bardingFor(String province) {
        return switch (province) {
            case "skyrim", "atmora" -> NORDIC_BARDING.get();
            case "high_rock" -> BRETON_BARDING.get();
            case "hammerfell", "elsweyr", "yokuda" -> REDGUARD_BARDING.get();
            case "summerset", "valenwood" -> ELVEN_BARDING.get();
            default -> IMPERIAL_BARDING.get();
        };
    }

    /**
     * A legendary steed's token: using it binds the steed to you (it joins your stable and comes when called).
     * Shadowmere's bridle is given by the Dark Brotherhood (or the story flag {@code mount_shadowmere}); Arvak's Skull
     * is found in the Soul Cairn (or flag {@code mount_arvak}). The token stays: using it again calls the steed.
     */
    public static class Token extends Item {
        private final MountKind kind;

        public Token(MountKind kind, Properties props) {
            super(props);
            this.kind = kind;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide && player instanceof ServerPlayer sp) {
                if (Mounts.owns(sp, kind) && Mounts.active(sp) == kind) {
                    Mounts.call(sp);
                } else if (Mounts.owns(sp, kind)) {
                    Mounts.switchTo(sp, kind, sp.blockPosition());
                } else {
                    Mounts.grant(sp, kind, null);
                    com.skycraft.core.Notifier.title(sp, kind.displayName(), Component.translatable("mounts.skycraft.legend_bound"));
                }
                player.getCooldowns().addCooldown(this, 100);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        @Override
        public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.translatable("item.skycraft." + (kind == MountKind.ARVAK ? "arvaks_skull" : "shadowmere_bridle") + ".tooltip"));
        }
    }

    /** Argonian mud-walkers: leather-light boots that keep you swift through mud, reeds and shallow water. */
    public static final class MarshShoes extends ArmorItem {
        private static final java.util.UUID SPEED = java.util.UUID.fromString("3c0ad0e4-9b6f-4b62-8d5e-6a3f0f2d7b21");
        private static final java.util.UUID SWIM = java.util.UUID.fromString("8e5f1b77-2d4a-4c0e-a9c3-1f6e2b9d4a55");
        private com.google.common.collect.Multimap<net.minecraft.world.entity.ai.attributes.Attribute,
                net.minecraft.world.entity.ai.attributes.AttributeModifier> feet;

        public MarshShoes(Properties props) {
            super(MATERIAL, Type.BOOTS, props);
        }

        @Override
        public com.google.common.collect.Multimap<net.minecraft.world.entity.ai.attributes.Attribute, net.minecraft.world.entity.ai.attributes.AttributeModifier>
        getDefaultAttributeModifiers(net.minecraft.world.entity.EquipmentSlot slot) {
            if (slot != net.minecraft.world.entity.EquipmentSlot.FEET) return super.getDefaultAttributeModifiers(slot);
            if (feet == null) {
                var b = com.google.common.collect.ImmutableMultimap.<net.minecraft.world.entity.ai.attributes.Attribute,
                        net.minecraft.world.entity.ai.attributes.AttributeModifier>builder();
                b.putAll(super.getDefaultAttributeModifiers(slot));
                b.put(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                        SPEED, "Marsh-shoes", 0.08, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_BASE));
                b.put(net.minecraftforge.common.ForgeMod.SWIM_SPEED.get(), new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                        SWIM, "Marsh-shoes", 0.6, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_BASE));
                feet = b.build();
            }
            return feet;
        }

        public static final ArmorMaterial MATERIAL = new ArmorMaterial() {
            @Override
            public int getDurabilityForType(ArmorItem.Type type) {
                return 195;
            }

            @Override
            public int getDefenseForType(ArmorItem.Type type) {
                return 1;
            }

            @Override
            public int getEnchantmentValue() {
                return 12;
            }

            @Override
            public SoundEvent getEquipSound() {
                return SoundEvents.ARMOR_EQUIP_LEATHER;
            }

            @Override
            public Ingredient getRepairIngredient() {
                return Ingredient.of(Items.LEATHER);
            }

            @Override
            public String getName() {
                return Skycraft.MODID + ":marsh_shoes";
            }

            @Override
            public float getToughness() {
                return 0;
            }

            @Override
            public float getKnockbackResistance() {
                return 0;
            }
        };

    }
}
