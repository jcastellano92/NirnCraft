package com.skycraft.quest;

import com.skycraft.Skycraft;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Quest items: the ancient tome, legendary monster trophies, the Black Hand letter and courier letters. */
public final class QuestItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);

    public static final RegistryObject<Item> ANCIENT_TOME = ITEMS.register("ancient_tome",
            () -> new QuestItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON), "ancient_tome"));
    public static final RegistryObject<Item> MONSTER_TROPHY = ITEMS.register("monster_trophy",
            () -> new TrophyItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
    public static final RegistryObject<Item> BLACK_HAND_LETTER = ITEMS.register("black_hand_letter",
            () -> new BlackHandLetter(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
    public static final RegistryObject<Item> COURIER_LETTER = ITEMS.register("courier_letter",
            () -> new QuestItem(new Item.Properties().stacksTo(16), "courier_letter"));
    /** Story relics (the Dragonstone, the Horn of Jurgen Windcaller, an Elder Scroll...): NBT {@value #RELIC_TAG} names which. */
    public static final RegistryObject<Item> QUEST_RELIC = ITEMS.register("quest_relic",
            () -> new RelicItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
    public static final String RELIC_TAG = "relic";

    private QuestItems() {}

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    /** Contract 2: an ItemStack is a quest item when its tag has the boolean skycraft_quest = true. */
    public static boolean isQuestItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var tag = stack.getTag();
        if (tag == null) return false;
        return tag.getBoolean("skycraft_quest") || tag.getBoolean("skycraft_quest_item");
    }

    public static ItemStack markQuest(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            stack.getOrCreateTag().putBoolean("skycraft_quest", true);
        }
        return stack;
    }

    /** Creates a trophy for a slain legendary monster. */
    public static ItemStack trophy(String monsterName, int value) {
        ItemStack stack = new ItemStack(MONSTER_TROPHY.get());
        stack.getOrCreateTag().putString("monster", monsterName);
        stack.getOrCreateTag().putInt("value", value);
        stack.getOrCreateTag().putBoolean("skycraft_quest", true);
        return stack;
    }

    /** A sealed letter addressed to {@code destination}. */
    public static ItemStack letter(String destination) {
        ItemStack stack = new ItemStack(COURIER_LETTER.get());
        stack.getOrCreateTag().putString("destination", destination);
        stack.getOrCreateTag().putBoolean("skycraft_quest", true);
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
        public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.translatable("tooltip.skycraft." + id).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            if (stack.getTag() != null && stack.getTag().contains("destination")) {
                tooltip.add(Component.translatable("tooltip.skycraft.courier_letter.to", stack.getTag().getString("destination"))
                        .withStyle(ChatFormatting.GOLD));
            }
            tooltip.add(Component.translatable("tooltip.skycraft.quest_item").withStyle(ChatFormatting.DARK_AQUA));
        }
    }

    /** The head (or claw, or tusk...) of a legendary beast. Very valuable to merchants. */
    public static class TrophyItem extends Item {
        public TrophyItem(Properties props) {
            super(props);
        }

        @Override
        public Component getName(ItemStack stack) {
            if (stack.getTag() != null && stack.getTag().contains("monster")) {
                return Component.translatable("item.skycraft.monster_trophy.named", stack.getTag().getString("monster"));
            }
            return super.getName(stack);
        }

        @Override
        public boolean isFoil(ItemStack stack) {
            return true;
        }

        @Override
        public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.translatable("tooltip.skycraft.monster_trophy").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            if (stack.getTag() != null && stack.getTag().getInt("value") > 0) {
                tooltip.add(Component.translatable("tooltip.skycraft.monster_trophy.value", stack.getTag().getInt("value"))
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
            return stack.getTag() == null ? "" : stack.getTag().getString(RELIC_TAG);
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
        public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
            String id = relic(stack);
            if (!id.isEmpty()) {
                tooltip.add(Component.translatable("item.skycraft.relic." + id + ".desc").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
            tooltip.add(Component.translatable("tooltip.skycraft.quest_item").withStyle(ChatFormatting.DARK_AQUA));
        }
    }

    /** "We know." Reading it reminds the murderer where to go. */
    public static class BlackHandLetter extends Item {
        public BlackHandLetter(Properties props) {
            super(props);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide) {
                player.sendSystemMessage(Component.translatable("item.skycraft.black_hand_letter.read")
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        @Override
        public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.translatable("tooltip.skycraft.black_hand_letter").withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
        }
    }
}
