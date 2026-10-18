package com.skycraft.lore;

import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.economy.ItemValues;
import com.skycraft.lore.client.LoreClient;
import com.skycraft.network.SkyNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import com.skycraft.platform.Sides;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@code skycraft:book}: one item for every readable book and note; the NBT string {@code book} names which one
 * (see {@link LoreBooks}). Using it opens the reading screen (the server sends {@link LorePackets.OpenBook}).
 * A book without an id (e.g. from the creative tab) becomes a random book the first time it is opened.
 */
public class LoreBookItem extends Item {
    public static final String TAG = "book";
    public static final String READ_KEY = "read";
    /** Test seam: when set, the open-book request goes here instead of the network (self-tests, headless). */
    public static volatile java.util.function.BiConsumer<ServerPlayer, String> openHook;

    public LoreBookItem(Properties props) {
        super(props);
    }

    public static ItemStack create(LoreBooks.Book book) {
        ItemStack stack = new ItemStack(LoreModule.BOOK.get());
        setBook(stack, book);
        return stack;
    }

    private static void setBook(ItemStack stack, LoreBooks.Book book) {
        CompoundTag tag = com.skycraft.inventory.ItemData.customOrEmpty(stack);
        tag.putString(TAG, book.id());
        tag.putInt(ItemValues.VALUE_NBT, book.value());
        com.skycraft.inventory.ItemData.setCustom(stack, tag);
    }

    @Nullable
    public static LoreBooks.Book bookOf(ItemStack stack) {
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
        return tag == null ? null : LoreBooks.get(tag.getStringOr(TAG, ""));
    }

    /** Cover index for the item model override (client). */
    public static int coverOf(ItemStack stack) {
        LoreBooks.Book book = bookOf(stack);
        return book == null ? LoreBooks.BROWN : book.cover();
    }

    public static boolean hasRead(PlayerData data, String id) {
        ListTag read = data.module(StandingStones.MODULE).getListOrEmpty(READ_KEY);
        for (int i = 0; i < read.size(); i++) {
            if (read.getString(i).orElse("").equals(id)) return true;
        }
        return false;
    }

    /** Records that the player read a book (first time only) and opens it on their screen. */
    public static void read(ServerPlayer player, LoreBooks.Book book) {
        PlayerData data = SkyData.get(player);
        if (!hasRead(data, book.id())) {
            CompoundTag module = data.module(StandingStones.MODULE);
            ListTag read = module.getListOrEmpty(READ_KEY);
            read.add(StringTag.valueOf(book.id()));
            module.put(READ_KEY, read);
            data.addStat("lore_books_read", 1);
            data.markDirty();
        }
        java.util.function.BiConsumer<ServerPlayer, String> hook = openHook;
        if (hook != null) hook.accept(player, book.id());
        else SkyNetwork.sendToPlayer(player, new LorePackets.OpenBook(book.id()));
        // place readables reveal what they point at and start their place's mini-quest
        com.skycraft.lore.place.PlaceLoreEvents.onRead(player, book);
    }

    @Override
    public Component getName(ItemStack stack) {
        LoreBooks.Book book = bookOf(stack);
        return book != null ? book.title() : super.getName(stack);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            readStack(sp, stack);
        }
        return com.skycraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
    }

    /** Reads this lore book directly without moving it into the player's hand. */
    public void readStack(ServerPlayer sp, ItemStack stack) {
        LoreBooks.Book book = bookOf(stack);
        if (book == null) {
            book = LoreBooks.random(sp.level().getRandom(), b -> !b.category().isSheet());
            if (book == null) return;
            setBook(stack, book);
        }
        read(sp, book);
        sp.level().playSound(null, sp.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1f);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        Level level = context263.level();
        LoreBooks.Book book = bookOf(stack);
        if (book == null) {
            tooltip.accept(Component.translatable("tooltip.skycraft.lore.unknown_book").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            return;
        }
        tooltip.accept(book.category().displayName().copy().withStyle(ChatFormatting.GRAY));
        if (level != null && level.isClientSide()) {
            String author = com.skycraft.platform.Sides.callOn(Dist.CLIENT, () -> () -> LoreClient.author(book.id()));
            if (author != null && !author.isEmpty()) {
                tooltip.accept(Component.translatable("tooltip.skycraft.lore.author", author).withStyle(ChatFormatting.DARK_GRAY));
            }
            Boolean read = com.skycraft.platform.Sides.callOn(Dist.CLIENT, () -> () -> LoreClient.hasRead(book.id()));
            if (Boolean.TRUE.equals(read)) {
                tooltip.accept(Component.translatable("tooltip.skycraft.lore.read").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            }
        }
    }
}
