package com.skycraft.lore;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.crime.Bounty;
import com.skycraft.crime.Crimes;
import com.skycraft.crime.Ownership;
import com.skycraft.crime.Theft;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Browsing and taking books from bookshelves ({@link Blocks#BOOKSHELF} and {@link Blocks#CHISELED_BOOKSHELF}).
 * On chiseled bookshelves, each occupied slot holds a visible 3D tome that can be grabbed directly off the shelf.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class LoreEvents {
    private static final String SHELVES = "shelves";
    private static final int MAX_REMEMBERED = 256;
    private static final float FIND_CHANCE = 0.75f;

    private LoreEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        Player player = event.getEntity();
        if (player.isShiftKeyDown() && !player.getMainHandItem().isEmpty()) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);

        if (state.is(Blocks.CHISELED_BOOKSHELF)) {
            // If player is holding a vanilla bookshelf book on a player-placed shelf, let vanilla insert it
            if (PlacedBlocks.isPlayerPlaced(level, pos) && player.getMainHandItem().is(ItemTags.BOOKSHELF_BOOKS)) {
                return;
            }
            int occupiedSlot = findOccupiedSlot(state);
            if (occupiedSlot < 0) {
                return;
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)) return;

            var prop = ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(occupiedSlot);
            sl.setBlock(pos, state.setValue(prop, false), 3);
            if (sl.getBlockEntity(pos) instanceof ChiseledBookShelfBlockEntity cbe) {
                ItemStack stored = cbe.getItem(occupiedSlot);
                if (!stored.isEmpty()) {
                    cbe.removeItem(occupiedSlot, 1);
                }
            }
            sl.playSound(null, pos, SoundEvents.CHISELED_BOOKSHELF_PICKUP, SoundSource.BLOCKS, 1.0f, 1.0f);
            giveShelfBook(sl, pos, sp);
            return;
        }

        if (!state.is(Blocks.BOOKSHELF)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)) return;
        sl.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1f, 0.9f);

        PlayerData data = SkyData.get(sp);
        CompoundTag module = data.module(StandingStones.MODULE);
        ListTag shelves = module.getList(SHELVES, Tag.TAG_STRING);
        String key = sl.dimension().location() + "@" + pos.asLong();
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

        if (PlacedBlocks.isPlayerPlaced(sl, pos) || sl.random.nextFloat() > FIND_CHANCE) {
            Notifier.message(sp, Component.translatable("message.skycraft.lore.shelf_nothing"));
            return;
        }
        giveShelfBook(sl, pos, sp);
    }

    public static int findOccupiedSlot(BlockState state) {
        if (!state.is(Blocks.CHISELED_BOOKSHELF)) return -1;
        for (int i = 0; i < 6; i++) {
            var prop = ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i);
            if (state.hasProperty(prop) && state.getValue(prop)) {
                return i;
            }
        }
        return -1;
    }

    private static void giveShelfBook(ServerLevel sl, BlockPos pos, ServerPlayer sp) {
        String province = com.skycraft.tamriel.Regions.at(sl, pos).province();
        LoreBooks.Book book = LoreBooks.randomFor(sl.random, province, b -> !b.category().isSheet());
        if (book == null) return;
        ItemStack stack = LoreBookItem.create(book);
        if (Ownership.isOwnedByOther(sp, sl, pos)) {
            Bounty.markStolen(stack);
            Bounty.increment(sp, "items_stolen", 1);
            if (Crimes.witnessed(sp, null)) {
                Theft.handleWitnessedTheft(sp, Crimes.findWitness(sp, null), pos, Bounty.MIN_THEFT);
            }
        }
        Notifier.message(sp, Component.translatable("message.skycraft.lore.shelf_found", book.title()));
        if (!ActionHandler.addToBags(sp, stack)) sp.drop(stack, false);
    }
}
