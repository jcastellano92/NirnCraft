package com.skycraft.quest;

import com.skycraft.Skycraft;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Quest items: the ancient tome, legendary monster trophies, the Black Hand letter and courier letters. */
public final class QuestItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, Skycraft.MODID);

    public static final Supplier<Item> ANCIENT_TOME = ITEMS.register("ancient_tome",
            () -> new QuestItem(com.skycraft.platform.SkyProperties.item("ancient_tome").stacksTo(1).rarity(Rarity.UNCOMMON), "ancient_tome"));
    public static final Supplier<Item> MONSTER_TROPHY = ITEMS.register("monster_trophy",
            () -> new TrophyItem(com.skycraft.platform.SkyProperties.item("monster_trophy").stacksTo(1).rarity(Rarity.RARE)));
    public static final Supplier<Item> BLACK_HAND_LETTER = ITEMS.register("black_hand_letter",
            () -> new BlackHandLetter(com.skycraft.platform.SkyProperties.item("black_hand_letter").stacksTo(1).rarity(Rarity.UNCOMMON)));
    public static final Supplier<Item> COURIER_LETTER = ITEMS.register("courier_letter",
            () -> new QuestItem(com.skycraft.platform.SkyProperties.item("courier_letter").stacksTo(16), "courier_letter"));
    /** Story relics (the Dragonstone, the Horn of Jurgen Windcaller, an Elder Scroll...): NBT {@value #RELIC_TAG} names which. */
    public static final Supplier<Item> QUEST_RELIC = ITEMS.register("quest_relic",
            () -> new RelicItem(com.skycraft.platform.SkyProperties.item("quest_relic").stacksTo(1).rarity(Rarity.RARE)));
    public static final String RELIC_TAG = "relic";
    /** The one-use key to the start cottage's door (bound to the player it was made for; see quest.story.IntroFlow). */
    public static final Supplier<Item> COTTAGE_KEY = ITEMS.register("cottage_key",
            () -> new QuestItem(com.skycraft.platform.SkyProperties.item("cottage_key").stacksTo(1).rarity(Rarity.UNCOMMON), "cottage_key"));

    private QuestItems() {}

    /** The cottage key made for {@code owner}: a soulbound quest item that only opens that player's way out. */
    public static ItemStack cottageKey(java.util.UUID owner) {
        ItemStack stack = new ItemStack(COTTAGE_KEY.get());
        com.skycraft.inventory.ItemData.updateCustom(stack, customData263 -> customData263.store("owner", net.minecraft.core.UUIDUtil.CODEC,  owner));
        com.skycraft.inventory.ItemData.updateCustom(stack, customData263 -> customData263.putBoolean("skycraft_quest", true));
        return stack;
    }

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    /** Contract 2: an ItemStack is a quest item when its tag has the boolean skycraft_quest = true. */
    public static boolean isQuestItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var tag = com.skycraft.inventory.ItemData.custom(stack);
        if (tag == null) return false;
        return tag.getBooleanOr("skycraft_quest",false) || tag.getBooleanOr("skycraft_quest_item",false);
    }

    public static ItemStack markQuest(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            com.skycraft.inventory.ItemData.updateCustom(stack, customData263 -> customData263.putBoolean("skycraft_quest", true));
        }
        return stack;
    }

    /** Creates a trophy for a slain legendary monster. */
    public static ItemStack trophy(String monsterName, int value) {
        ItemStack stack = new ItemStack(MONSTER_TROPHY.get());
        com.skycraft.inventory.ItemData.updateCustom(stack, customData263 -> customData263.putString("monster", monsterName));
        com.skycraft.inventory.ItemData.updateCustom(stack, customData263 -> customData263.putInt("value", value));
        com.skycraft.inventory.ItemData.updateCustom(stack, customData263 -> customData263.putBoolean("skycraft_quest", true));
        return stack;
    }

    /** A sealed letter addressed to {@code destination}. */
    public static ItemStack letter(String destination) {
        ItemStack stack = new ItemStack(COURIER_LETTER.get());
        com.skycraft.inventory.ItemData.updateCustom(stack, customData263 -> customData263.putString("destination", destination));
        com.skycraft.inventory.ItemData.updateCustom(stack, customData263 -> customData263.putBoolean("skycraft_quest", true));
        return stack;
    }

    /** An item with a one-line flavor tooltip ({@code tooltip.skycraft.<id>}). */
    public static class QuestItem extends Item {
        private final String id;

        public QuestItem(Properties props, String id) {
            super(props);
            this.id = id;
        }

        @Override
        public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
            tooltip.accept(Component.translatable("tooltip.skycraft." + id).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            if (com.skycraft.inventory.ItemData.custom(stack) != null && com.skycraft.inventory.ItemData.custom(stack).contains("destination")) {
                tooltip.accept(Component.translatable("tooltip.skycraft.courier_letter.to", com.skycraft.inventory.ItemData.custom(stack).getStringOr("destination",""))
                        .withStyle(ChatFormatting.GOLD));
            }
            tooltip.accept(Component.translatable("tooltip.skycraft.quest_item").withStyle(ChatFormatting.DARK_AQUA));
        }
    }

    /** The head (or claw, or tusk...) of a legendary beast. Very valuable to merchants. */
    public static class TrophyItem extends Item {
        public TrophyItem(Properties props) {
            super(props);
        }

        @Override
        public Component getName(ItemStack stack) {
            if (com.skycraft.inventory.ItemData.custom(stack) != null && com.skycraft.inventory.ItemData.custom(stack).contains("monster")) {
                return Component.translatable("item.skycraft.monster_trophy.named", com.skycraft.inventory.ItemData.custom(stack).getStringOr("monster",""));
            }
            return super.getName(stack);
        }

        @Override
        public boolean isFoil(ItemStack stack) {
            return true;
        }

        @Override
        public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
            tooltip.accept(Component.translatable("tooltip.skycraft.monster_trophy").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            if (com.skycraft.inventory.ItemData.custom(stack) != null && com.skycraft.inventory.ItemData.custom(stack).getIntOr("value",0) > 0) {
                tooltip.accept(Component.translatable("tooltip.skycraft.monster_trophy.value", com.skycraft.inventory.ItemData.custom(stack).getIntOr("value",0))
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }

    /**
     * A story relic. Name and tooltip come from the relic id ({@code item.skycraft.relic.<id>}); the model picks its
     * look from {@code CustomModelData}. Relics are quest items: they can't be sold.
     */
    public static class RelicItem extends Item {
        public RelicItem(Properties props) {
            super(props);
        }

        private static String relic(ItemStack stack) {
            return com.skycraft.inventory.ItemData.custom(stack) == null ? "" : com.skycraft.inventory.ItemData.custom(stack).getStringOr(RELIC_TAG, "");
        }

        @Override
        public Component getName(ItemStack stack) {
            String id = relic(stack);
            if (id.isEmpty()) return super.getName(stack);
            return Component.translatable("item.skycraft.relic." + id);
        }

        @Override
        public boolean isFoil(ItemStack stack) {
            return true;
        }

        @Override
        public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
            String id = relic(stack);
            if (!id.isEmpty()) {
                tooltip.accept(Component.translatable("item.skycraft.relic." + id + ".desc").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
            tooltip.accept(Component.translatable("tooltip.skycraft.quest_item").withStyle(ChatFormatting.DARK_AQUA));
        }
    }

    /** "We know." Reading it reminds the murderer where to go. */
    public static class BlackHandLetter extends Item {
        public BlackHandLetter(Properties props) {
            super(props);
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide()) {
                Component text = Component.translatable("item.skycraft.black_hand_letter.read")
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC);
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    com.skycraft.core.Notifier.message(sp, text);
                } else {
                    player.sendOverlayMessage(text);
                }
            }
            return com.skycraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
        }

        @Override
        public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
            tooltip.accept(Component.translatable("tooltip.skycraft.black_hand_letter").withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
        }
    }
}
