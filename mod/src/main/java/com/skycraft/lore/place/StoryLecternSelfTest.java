package com.skycraft.lore.place;

import com.skycraft.Skycraft;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreBooks;
import com.skycraft.lore.LorePackets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless check of the story reader ({@code /skylore selftest lecterns}, also run by {@code qa.ps1 -Server}): each kind
 * of story block is placed high above the spawn, a fake player's right-click is posted on the Forge event bus (the real
 * handler chain: locks, houses, the reader), and the open request that would go to the client is compared with what the
 * block holds. The vanilla cases (a lectern a player placed, a sneaking click) must pass through untouched.
 */
final class StoryLecternSelfTest {
    private StoryLecternSelfTest() {}

    private static final class Result {
        int checks, problems;
        final List<String> lines = new ArrayList<>();

        void check(boolean ok, String what) {
            checks++;
            if (!ok) {
                problems++;
                lines.add("FAIL " + what);
            }
        }
    }

    /** Runs the self-test; returns the summary line (also logged as "Story lectern selftest: ..."). */
    static String run(MinecraftServer server) {
        Result r = new Result();
        ServerLevel level = server.overworld();
        List<Runnable> cleanup = new ArrayList<>();
        List<String> openedBooks = new ArrayList<>();
        List<LorePackets.OpenStory> openedStories = new ArrayList<>();
        java.util.function.BiConsumer<ServerPlayer, String> oldHook = LoreBookItem.openHook;
        StoryReader.Sink oldSink = StoryReader.testSink;
        try {
            LoreBookItem.openHook = (p, id) -> openedBooks.add(id);
            StoryReader.testSink = openedStories::add;
            ServerPlayer player = FakePlayerFactory.getMinecraft(level);
            BlockPos base = level.getRespawnData().pos();
            int y = (level.getMaxY()+1) - 12;
            level.getChunk(base.getX() >> 4, base.getZ() >> 4);

            // a place note without side effects (no reveal, no quest) if there is one
            PlaceLore.Note note = null;
            for (PlaceLore.Content c : PlaceLore.all()) {
                for (PlaceLore.Note n : c.notes()) {
                    if (n.reveals().isEmpty() && n.starts().isEmpty()) {
                        note = n;
                        break;
                    }
                }
                if (note != null) break;
            }
            r.check(note != null, "a place note exists to test with");
            if (note == null) return finish(r);
            String noteId = note.bookId();

            // 1. a lectern holding the lore book item itself (PlaceLorePlacer's lectern)
            BlockPos a = at(level, base, 0, y, cleanup);
            lectern(level, a, LoreBookItem.create(note.book()));
            openedBooks.clear();
            boolean canceled = click(player, a, false);
            r.check(canceled, "skycraft:book lectern: click handled");
            r.check(openedBooks.equals(List.of(noteId)), "skycraft:book lectern: opens note " + noteId + " (got " + openedBooks + ")");

            // 2. a written book tagged SkyNote (a dungeon's lectern)
            BlockPos b = at(level, base, 2, y, cleanup);
            ItemStack written = new ItemStack(Items.WRITTEN_BOOK);
            CompoundTag t = com.skycraft.inventory.ItemData.customOrEmpty(written);
            t.putString("title", "Note");
            t.putString("author", "Someone");
            ListTag pages = new ListTag();
            pages.add(StringTag.valueOf(com.skycraft.platform.ArcaneData.writeComponent(Component.literal("Words."))));
            t.put("pages", pages);
            t.putBoolean("resolved", true);
            t.putString(StoryReader.NOTE_TAG, noteId);
            com.skycraft.inventory.ItemData.setCustom(written, t);
            lectern(level, b, written);
            openedBooks.clear();
            canceled = click(player, b, false);
            r.check(canceled && openedBooks.equals(List.of(noteId)), "SkyNote lectern: opens note " + noteId + " (got " + openedBooks + ")");

            // 3. a written book of the world builders (SkyStory) and 4. an untagged one on a lectern no player placed
            BlockPos c = at(level, base, 4, y, cleanup);
            ItemStack story = written.copy();
            com.skycraft.inventory.ItemData.updateCustom(story, customData263 -> customData263.remove(StoryReader.NOTE_TAG));
            com.skycraft.inventory.ItemData.updateCustom(story, customData263 -> customData263.putBoolean(StoryReader.STORY_TAG, true));
            com.skycraft.inventory.ItemData.updateCustom(story, customData263 -> customData263.putString("title", "A Journal"));
            lectern(level, c, story);
            openedStories.clear();
            canceled = click(player, c, false);
            r.check(canceled && openedStories.size() == 1 && openedStories.get(0).title().equals("A Journal")
                    && openedStories.get(0).pagesJson().size() == 1 && openedStories.get(0).kind().equals("lectern"),
                    "SkyStory lectern: opens the inline story (got " + openedStories + ")");

            BlockPos d = at(level, base, 6, y, cleanup);
            ItemStack plain = story.copy();
            com.skycraft.inventory.ItemData.updateCustom(plain, customData263 -> customData263.remove(StoryReader.STORY_TAG));
            com.skycraft.inventory.ItemData.updateCustom(plain, customData263 -> customData263.putString("title", "Old Scene Note"));
            lectern(level, d, plain);
            openedStories.clear();
            canceled = click(player, d, false);
            r.check(canceled && openedStories.size() == 1 && openedStories.get(0).title().equals("Old Scene Note"),
                    "untagged world lectern: opens the inline story (got " + openedStories + ")");

            // 5. the same book on a lectern a player placed: vanilla (not handled)
            BlockPos e = at(level, base, 8, y, cleanup);
            lectern(level, e, plain.copy());
            PlacedBlocks.mark(level, e, true);
            cleanup.add(() -> PlacedBlocks.mark(level, e, false));
            openedStories.clear();
            openedBooks.clear();
            canceled = click(player, e, false);
            r.check(!canceled && openedStories.isEmpty() && openedBooks.isEmpty(), "player-placed lectern: left to vanilla");

            // 6. sneaking on a marked sign is the normal use
            BlockPos f = at(level, base, 10, y, cleanup);
            level.setBlock(f, Blocks.SPRUCE_SIGN.defaultBlockState(), 3);
            BlockEntity sign = level.getBlockEntity(f);
            r.check(sign != null, "sign block entity exists");
            if (sign != null) {
                StoryReader.mark(sign, "A Notice", "", List.of("Beware the road past the old tower."));
                openedStories.clear();
                canceled = click(player, f, false);
                r.check(canceled && openedStories.size() == 1 && openedStories.get(0).kind().equals("sign")
                        && openedStories.get(0).title().equals("A Notice"), "marked sign: opens the clue (got " + openedStories + ")");
                openedStories.clear();
                canceled = click(player, f, true);
                r.check(!canceled && openedStories.isEmpty(), "marked sign, sneaking: normal use");
            }

            // 7. a note barrel
            BlockPos g = at(level, base, 12, y, cleanup);
            level.setBlock(g, Blocks.BARREL.defaultBlockState(), 3);
            if (level.getBlockEntity(g) instanceof BarrelBlockEntity barrel) {
                barrel.setItem(3, LoreBookItem.create(note.book()));
                StoryReader.markNoteContainer(barrel);
                openedBooks.clear();
                canceled = click(player, g, false);
                r.check(canceled && openedBooks.equals(List.of(noteId)), "note barrel: opens note " + noteId + " (got " + openedBooks + ")");
                openedBooks.clear();
                canceled = click(player, g, true);
                r.check(openedBooks.isEmpty(), "note barrel, sneaking: the normal use (no reader)");
            } else {
                r.check(false, "barrel block entity exists");
            }

            // 8. an ordinary barrel and an empty lectern are never touched
            BlockPos h = at(level, base, 14, y, cleanup);
            level.setBlock(h, Blocks.BARREL.defaultBlockState(), 3);
            canceled = click(player, h, false);
            r.check(!canceled, "plain barrel: untouched");
            BlockPos i = at(level, base, 16, y, cleanup);
            level.setBlock(i, Blocks.LECTERN.defaultBlockState(), 3);
            canceled = click(player, i, false);
            r.check(!canceled, "empty lectern: untouched");

            // Native written content must reach the same reader, including translated/component pages.
            BlockPos nativeLectern = at(level, base, 18, y, cleanup);
            ItemStack nativeBook = com.skycraft.worldgen.AuthoredBooks.create("Native Journal", "Someone",
                    List.of(Component.literal("Words from the native component.")));
            lectern(level, nativeLectern, nativeBook);
            openedStories.clear();
            canceled = click(player, nativeLectern, false);
            r.check(canceled && openedStories.size() == 1 && openedStories.get(0).title().equals("Native Journal")
                    && openedStories.get(0).author().equals("Someone")
                    && openedStories.get(0).pagesJson().size() == 1
                    && openedStories.get(0).pagesJson().get(0).contains("Words from the native component."),
                    "native written-book lectern: preserves component title, author and page (got " + openedStories + ")");

            // 9. the lore book list knows the note was read (the lore flow ran)
            r.check(LoreBookItem.hasRead(com.skycraft.core.SkyData.get(player), noteId), "reading marks the note as read");
        } catch (RuntimeException ex) {
            Skycraft.LOGGER.error("Story lectern selftest threw", ex);
            r.check(false, "no exception (" + ex + ")");
        } finally {
            LoreBookItem.openHook = oldHook;
            StoryReader.testSink = oldSink;
            for (Runnable c : cleanup) {
                try {
                    c.run();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
        }
        return finish(r);
    }

    private static String finish(Result r) {
        for (String l : r.lines) Skycraft.LOGGER.warn("[lore] {}", l);
        String line = r.checks - r.problems + "/" + r.checks + " checks passed, " + r.problems + " problems";
        Skycraft.LOGGER.info("Story lectern selftest: {}", line);
        return line;
    }

    private static BlockPos at(ServerLevel level, BlockPos base, int dx, int y, List<Runnable> cleanup) {
        BlockPos p = new BlockPos(base.getX() + dx, y, base.getZ());
        level.getChunk(p.getX() >> 4, p.getZ() >> 4);
        cleanup.add(() -> level.setBlock(p, Blocks.AIR.defaultBlockState(), 3));
        return p;
    }

    private static void lectern(ServerLevel level, BlockPos pos, ItemStack book) {
        BlockState state = Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.NORTH).setValue(LecternBlock.HAS_BOOK, true);
        level.setBlock(pos, state, 3);
        if (level.getBlockEntity(pos) instanceof LecternBlockEntity le) le.setBook(book);
    }

    /** Posts a right-click on the Forge bus; true when a handler cancelled it (the vanilla use did not run). */
    private static boolean click(ServerPlayer player, BlockPos pos, boolean sneak) {
        player.setShiftKeyDown(sneak);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
        NeoForge.EVENT_BUS.post(event);
        player.setShiftKeyDown(false);
        return event.isCanceled();
    }
}
