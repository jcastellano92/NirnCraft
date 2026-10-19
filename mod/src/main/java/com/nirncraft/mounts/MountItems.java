package com.nirncraft.mounts;

import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Mount gear: culture bardings for horses (vanilla horse armour slots, own textures), beast barding for the ridden
 * beasts, Black Marsh's marsh-shoes (no mount crosses the marsh: these speed you through mud and shallows), and the
 * tokens of the two legendary steeds (quest and Soul Cairn hooks): Shadowmere's bridle and Arvak's Skull.
 */
public final class MountItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, NirnCraft.MODID);

    public static final Supplier<Item> NORDIC_BARDING = barding("nordic_barding", 7);
    public static final Supplier<Item> IMPERIAL_BARDING = barding("imperial_barding", 9);
    public static final Supplier<Item> BRETON_BARDING = barding("breton_barding", 11);
    public static final Supplier<Item> REDGUARD_BARDING = barding("redguard_barding", 8);
    public static final Supplier<Item> ELVEN_BARDING = barding("elven_barding", 10);
    // armour tiers that follow the smithing ladder (protection in the horse-armour scale: leather 3, iron 5, gold 7, diamond 11)
    public static final Supplier<Item> LEATHER_BARDING = barding("leather_barding", 3);
    public static final Supplier<Item> IRON_BARDING = barding("iron_barding", 5);
    public static final Supplier<Item> STEEL_BARDING = barding("steel_barding", 6);
    public static final Supplier<Item> DWARVEN_BARDING = barding("dwarven_barding", 12);
    public static final Supplier<Item> ORCISH_BARDING = barding("orcish_barding", 13);
    public static final Supplier<Item> EBONY_BARDING = barding("ebony_barding", 15);
    public static final Supplier<Item> BEAST_BARDING = ITEMS.register("beast_barding",
            () -> new Item(com.nirncraft.platform.SkyProperties.item("beast_barding").stacksTo(1)));
    // saddles: PLAIN is the vanilla saddle (SaddleType)
    public static final Supplier<Item> PACK_SADDLE = saddle("pack_saddle", SaddleType.PACK);
    public static final Supplier<Item> WAR_SADDLE = saddle("war_saddle", SaddleType.WAR);
    public static final Supplier<Item> NOBLE_SADDLE = saddle("noble_saddle", SaddleType.NOBLE);
    public static final Supplier<Item> MARSH_SHOES = ITEMS.register("marsh_shoes",
            () -> new MarshShoes(com.nirncraft.platform.SkyProperties.item("marsh_shoes")));
    public static final Supplier<Item> SHADOWMERE_BRIDLE = ITEMS.register("shadowmere_bridle",
            () -> new Token(MountKind.SHADOWMERE, com.nirncraft.platform.SkyProperties.item("shadowmere_bridle").stacksTo(1).rarity(Rarity.EPIC)));
    public static final Supplier<Item> ARVAKS_SKULL = ITEMS.register("arvaks_skull",
            () -> new Token(MountKind.ARVAK, com.nirncraft.platform.SkyProperties.item("arvaks_skull").stacksTo(1).rarity(Rarity.EPIC)));

    private MountItems() {}

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private static Supplier<Item> barding(String id, int protection) {
        return ITEMS.register(id, () -> new BardingItem(id, protection, com.nirncraft.platform.SkyProperties.item(id)));
    }

    private static Supplier<Item> saddle(String id, SaddleType type) {
        return ITEMS.register(id, () -> new SaddleItem(type, com.nirncraft.platform.SkyProperties.item(id).stacksTo(1)));
    }

    public static boolean isHorseArmor(ItemStack stack) {
        var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        return com.nirncraft.inventory.EquipmentData.isBodyArmor(stack) && equippable != null
                && equippable.canBeEquippedBy(net.minecraft.world.entity.EntityTypes.HORSE.builtInRegistryHolder());
    }

    public static int armorProtection(ItemStack stack) {
        return Math.round(com.nirncraft.inventory.EquipmentData.defense(stack));
    }

    /** True for any saddle item (the vanilla one included). */
    public static boolean isSaddle(ItemStack stack) {
        return SaddleType.of(stack) != null;
    }

    /**
     * A saddle of the mount system. Not the vanilla {@code SaddleItem}: it does not saddle pigs or striders, only the
     * mounts ({@link Mounts#interact}).
     */
    public static final class SaddleItem extends Item {
        public final SaddleType type;

        public SaddleItem(SaddleType type, Properties props) {
            super(props);
            this.type = type;
        }

        @Override
        public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
            tooltip.accept(Component.translatable("item.nirncraft." + type.id + "_saddle.tooltip"));
            tooltip.accept(Component.translatable("mounts.nirncraft.saddle.stats", Math.round(type.carryKg), Math.round(type.bagKg),
                    Math.round(type.staminaMult * 100f)));
        }
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
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide() && player instanceof ServerPlayer sp) {
                if (Mounts.owns(sp, kind) && Mounts.active(sp) == kind) {
                    Mounts.call(sp);
                } else if (Mounts.owns(sp, kind)) {
                    Mounts.switchTo(sp, kind, sp.blockPosition());
                } else {
                    Mounts.grant(sp, kind, null);
                    com.nirncraft.core.Notifier.title(sp, kind.displayName(), Component.translatable("mounts.nirncraft.legend_bound"));
                }
                player.getCooldowns().addCooldown(stack, 100);
            }
            return com.nirncraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
        }

        @Override
        public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
            tooltip.accept(Component.translatable("item.nirncraft." + (kind == MountKind.ARVAK ? "arvaks_skull" : "shadowmere_bridle") + ".tooltip"));
        }
    }

    /** Native horse armor with the original province-specific protection and texture. */
    public static final class BardingItem extends Item {
        private final int protection;
        public BardingItem(String id, int protection, Properties properties) {
            super(properties.horseArmor(new ArmorMaterial(1, java.util.Map.of(net.minecraft.world.item.equipment.ArmorType.BODY, protection),
                    0, SoundEvents.HORSE_ARMOR, 0f, 0f,
                    net.minecraft.tags.TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "repair_none")),
                    net.minecraft.resources.ResourceKey.create(net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID,
                            Identifier.fromNamespaceAndPath(NirnCraft.MODID, id)))));
            this.protection = protection;
        }
        public int getProtection() { return protection; }
    }

    /** Argonian mud-walkers: original leather-light armor, walking and swimming bonuses. */
    public static final class MarshShoes extends Item {
        private static final Identifier SPEED = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "marsh_shoes_speed");
        private static final Identifier SWIM = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "marsh_shoes_swim");
        public static final ArmorMaterial MATERIAL = new ArmorMaterial(15,
                java.util.Map.of(net.minecraft.world.item.equipment.ArmorType.BOOTS, 1), 12,
                SoundEvents.ARMOR_EQUIP_LEATHER, 0f, 0f, net.minecraft.tags.ItemTags.REPAIRS_LEATHER_ARMOR,
                net.minecraft.resources.ResourceKey.create(net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID,
                        Identifier.fromNamespaceAndPath(NirnCraft.MODID, "marsh_shoes")));

        public MarshShoes(Properties props) {
            super(props.humanoidArmor(MATERIAL, net.minecraft.world.item.equipment.ArmorType.BOOTS)
                    .attributes(MATERIAL.createAttributes(net.minecraft.world.item.equipment.ArmorType.BOOTS)
                            .withModifierAdded(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED,
                                    new net.minecraft.world.entity.ai.attributes.AttributeModifier(SPEED, 0.08,
                                            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
                                    net.minecraft.world.entity.EquipmentSlotGroup.FEET)
                            .withModifierAdded(net.neoforged.neoforge.common.NeoForgeMod.SWIM_SPEED,
                                    new net.minecraft.world.entity.ai.attributes.AttributeModifier(SWIM, 0.6,
                                            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
                                    net.minecraft.world.entity.EquipmentSlotGroup.FEET)));
        }
    }
}
