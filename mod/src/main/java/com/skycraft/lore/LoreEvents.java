package com.skycraft.lore;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Browsing bookshelves: sneak + right-click a (naturally generated) bookshelf with an empty hand to look through
 * it. Each shelf can be searched once per character; about a third of them hold a readable book.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class LoreEvents {
    private static final String SHELVES = "shelves";
    private static final int MAX_REMEMBERED = 256;
    private static final float FIND_CHANCE = 0.35f;

    private LoreEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (event.getHand() != InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(Blocks.BOOKSHELF)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (level.isClientSide || !(player instanceof ServerPlayer sp)) return;
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1f, 0.9f);

        PlayerData data = SkyData.get(sp);
        CompoundTag module = data.module(StandingStones.MODULE);
        ListTag shelves = module.getList(SHELVES, Tag.TAG_STRING);
        String key = level.dimension().location() + "@" + pos.asLong();
        for (int i = 0; i < shelves.size(); i++) {
            if (shelves.getString(i).equals(key)) {
                Notifier.message(sp, Component.translatable("message.skycraft.lore.shelf_searched"));
                return;
            }
        }
        shelves.add(StringTag.valueOf(key));
        while (shelves.size() > MAX_REMEMBERED) shelves.remove(0);
        module.put(SHELVES, shelves);
        data.markDirty();

        // Shelves you built yourself only hold the books you put there.
        if (PlacedBlocks.isPlayerPlaced(level, pos) || level.random.nextFloat() > FIND_CHANCE) {
            Notifier.message(sp, Component.translatable("message.skycraft.lore.shelf_nothing"));
            return;
        }
        // a shelf holds mostly the books of its own land
        String province = com.skycraft.tamriel.Regions.at(level, pos).province();
        LoreBooks.Book book = LoreBooks.randomFor(level.random, province, b -> !b.category().isSheet());
        if (book == null) return;
        ItemStack stack = LoreBookItem.create(book);
        Notifier.message(sp, Component.translatable("message.skycraft.lore.shelf_found", book.title()));
        if (!ActionHandler.addToBags(sp, stack)) sp.drop(stack, false);
    }
}
