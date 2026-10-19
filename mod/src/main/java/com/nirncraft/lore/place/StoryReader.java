package com.nirncraft.lore.place;

import com.nirncraft.NirnCraft;
import com.nirncraft.SkyConfig;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.dig.PlacedBlocks;
import com.nirncraft.lore.LoreBookItem;
import com.nirncraft.lore.LoreBooks;
import com.nirncraft.lore.LorePackets;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.quest.Quests;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Stories on lecterns, clue signs and note barrels open in NirnCraft's own reading screen (the one lore books use)
 * instead of the vanilla lectern book, which only ever showed a vanilla written book (and nothing at all for a
 * {@code nirncraft:book}).
 *
 * <p>What counts as a story, checked server side on a right-click (or the [F] interact key, which is the same click):</p>
 * <ul>
 *   <li>a <b>lectern</b> whose book is a lore book ({@code nirncraft:book}), a written book tagged {@code SkyNote}
 *   (a place's note, see {@code Identity}) or {@code SkyStory} (a story the world builders wrote), or any written
 *   book on a lectern no player placed (older worlds: scene and dungeon lecterns carry untagged written books);</li>
 *   <li>a <b>sign</b> whose block entity carries the {@value #MARK} marker (the scene's clue sign: the full clue text);</li>
 *   <li>a <b>barrel</b> with the marker holding a lore book (the scene's note barrel).</li>
 * </ul>
 * <p>The block keeps its book (the click never reaches the vanilla menu, so there is no "take book" button) unless
 * {@code lecternTakeable} is on and the player sneaks. Reading records the story for the player ({@code stories}
 * module data), counts it, and for place notes runs the whole lore flow (read list, reveal, mini-quest, a copy for
 * the pack). Nothing here trusts the client: the server decides, then sends the text.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class StoryReader {
    /** Written-book tag naming the place note a lectern stands for. */
    public static final String NOTE_TAG = "SkyNote";
    /** Written-book tag of any story the world builders wrote. */
    public static final String STORY_TAG = "SkyStory";
    /** Persistent block-entity tag ({@code ForgeData}) of a sign or barrel that opens the reader. */
    public static final String MARK = "SkyRead";

    private static final String MODULE = "stories";
    private static final String READ = "read";
    private static final int MAX_REMEMBERED = 512;
    private static final int SHEET_MAX_PAGES = 2, SHEET_MAX_CHARS = 1500;

    private StoryReader() {}

    /** What a reader shows: a lore book (by id) or an inline story. */
    public record Story(@Nullable LoreBooks.Book book, String title, String author, List<String> pagesJson, boolean sheet,
                        String kind) {
        static Story lore(LoreBooks.Book b, String kind) {
            return new Story(b, "", "", List.of(), true, kind);
        }

        public String bookId() {
            return book == null ? "" : book.id();
        }
    }

    // ------------------------------------------------------------------ test seam

    /**
     * Receives the inline stories that would be sent to the player (lore books go through
     * {@link LoreBookItem#openHook}). The self-test installs one; null = the network.
     */
    public interface Sink {
        void openStory(LorePackets.OpenStory story);
    }

    static volatile Sink testSink;

    private static void sendStory(ServerPlayer p, LorePackets.OpenStory m) {
        Sink s = testSink;
        if (s != null) s.openStory(m);
        else SkyNetwork.sendToPlayer(p, m);
    }

    // ------------------------------------------------------------------ what is a story

    /** The story a book stack tells, or null when the stack is not one of ours (a plain vanilla book). */
    @Nullable
    public static Story ofBook(ItemStack stack, boolean worldMade, String kind) {
        if (stack.isEmpty()) return null;
        LoreBooks.Book lore = LoreBookItem.bookOf(stack);
        if (lore != null) return Story.lore(lore, kind);
        if (!stack.is(Items.WRITTEN_BOOK) || com.nirncraft.inventory.ItemData.custom(stack) == null) return null;
        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(stack);
        if (com.nirncraft.platform.NbtData.hasType(tag, NOTE_TAG, Tag.TAG_STRING)) {
            LoreBooks.Book note = PlaceLore.book(tag.getStringOr(NOTE_TAG, ""));
            if (note != null) return Story.lore(note, kind);
        }
        if (!worldMade && !tag.getBooleanOr(STORY_TAG, false)) return null;
        var content = stack.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
        ListTag legacyPages = tag.getListOrEmpty("pages");
        int size = content != null ? content.pages().size() : legacyPages.size();
        List<String> out = new ArrayList<>(Math.min(size, LorePackets.OpenStory.MAX_PAGES));
        int chars = 0;
        for (int i = 0; i < size && i < LorePackets.OpenStory.MAX_PAGES; i++) {
            String p = content != null ? com.nirncraft.platform.ArcaneData.writeComponent(content.pages().get(i).raw())
                    : legacyPages.getString(i).orElse("");
            if (p.length() > LorePackets.OpenStory.MAX_PAGE) p = p.substring(0, LorePackets.OpenStory.MAX_PAGE);
            chars += p.length();
            out.add(p);
        }
        if (out.isEmpty()) return null;
        String author = content != null ? content.author() : tag.getStringOr("author", "");
        if (author.equals("?")) author = "";
        boolean sheet = out.size() <= SHEET_MAX_PAGES && chars <= SHEET_MAX_CHARS;
        String title = content != null ? content.title().raw() : tag.getStringOr("title", "");
        return new Story(null, title, author, out, sheet, kind);
    }

    /** The story of a sign or barrel marked by the world builders (see {@link #mark}); null when unmarked. */
    @Nullable
    private static Story ofMarked(BlockEntity be, String kind) {
        CompoundTag data = be.getPersistentData();
        if (!com.nirncraft.platform.NbtData.hasType(data, MARK, Tag.TAG_COMPOUND)) return null;
        CompoundTag m = data.getCompoundOrEmpty(MARK);
        ListTag pages = m.getListOrEmpty("pages");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < pages.size() && i < LorePackets.OpenStory.MAX_PAGES; i++) {
            String p = pages.getString(i).orElse("");
            out.add(com.nirncraft.platform.ArcaneData.writeComponent(Component.literal(p.length() > 4000 ? p.substring(0, 4000) : p)));
        }
        if (out.isEmpty()) return null;
        return new Story(null, m.getStringOr("title",""), m.getStringOr("author",""), out, true, kind);
    }

    /**
     * Marks a sign or a note barrel so a right-click reads it in the reader. Safe during world generation (only the
     * block entity's persistent data is touched).
     */
    public static void mark(BlockEntity be, String title, String author, List<String> pages) {
        CompoundTag m = new CompoundTag();
        m.putString("title", title);
        m.putString("author", author);
        ListTag list = new ListTag();
        for (String p : pages) list.add(StringTag.valueOf(p));
        m.put("pages", list);
        be.getPersistentData().put(MARK, m);
        be.setChanged();
    }

    /** Marks a block entity as "reads its first lore book" (a barrel with a note in it). */
    public static void markNoteContainer(BlockEntity be) {
        be.getPersistentData().put(MARK, new CompoundTag());
    }

    // ------------------------------------------------------------------ the click

    /** The outcome of a click, before anything is sent (the self-test reads it). */
    public record Click(Story story, String key) {}

    /** Resolves what a click on the block at {@code pos} would read, or null (the vanilla use goes on). */
    @Nullable
    public static Click resolve(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        String key = level.dimension().identifier() + "@" + pos.asLong();
        if (be instanceof LecternBlockEntity lectern && level.getBlockState(pos).getBlock() instanceof LecternBlock) {
            // sneaking takes the book the vanilla way, when the server allows it
            if (player.isShiftKeyDown() && SkyConfig.STORY_LECTERN_TAKEABLE.get()) return null;
            Story s = ofBook(lectern.getBook(), !PlacedBlocks.isPlayerPlaced(level, pos), "lectern");
            return s == null ? null : new Click(s, key);
        }
        if (player.isShiftKeyDown()) return null; // signs and barrels: sneaking is the normal use
        if (be instanceof SignBlockEntity) {
            Story s = ofMarked(be, "sign");
            return s == null ? null : new Click(s, key);
        }
        if (be instanceof BarrelBlockEntity barrel && com.nirncraft.platform.NbtData.hasType(be.getPersistentData(), MARK, Tag.TAG_COMPOUND)) {
            if (com.nirncraft.crime.Locks.blocks(player, level, pos) || com.nirncraft.crime.Ownership.isOwnedByOther(player, level, pos)) return null;
            for (int i = 0; i < barrel.getContainerSize(); i++) {
                Story s = ofBook(barrel.getItem(i), false, "note");
                if (s != null && s.book() != null) return new Click(s, key);
            }
        }
        return null;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || event.isCanceled()) return;
        Level level = event.getLevel();
        if (level.isClientSide() || !(event.getEntity() instanceof ServerPlayer player) || !(level instanceof ServerLevel sl)) return;
        Click click = resolve(player, sl, event.getPos());
        if (click == null) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        read(player, sl, event.getPos(), click);
    }

    // ------------------------------------------------------------------ reading

    /** Reads a story for a player: marks it, runs the lore flow for place notes, and opens the reader. */
    public static void read(ServerPlayer player, ServerLevel level, BlockPos pos, Click click) {
        Story story = click.story();
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1f);
        if (story.book() != null) {
            PlayerData d = SkyData.get(player);
            LoreBooks.Book book = story.book();
            PlaceLore.Note note = PlaceLore.note(book.id());
            // the reader takes copies of every lectern note of the place they haven't found yet (this one first)
            List<PlaceLore.Note> take = new ArrayList<>();
            if (note != null) {
                if (!PlaceLoreEvents.hasFound(d, book.id())) take.add(note);
                PlaceLore.contentFor(note.placeId()).ifPresent(c -> {
                    for (PlaceLore.Note n : PlaceLorePlacer.lecternNotes(c)) {
                        if (n != note && !PlaceLoreEvents.hasFound(d, n.bookId())) take.add(n);
                    }
                });
            }
            // LoreBookItem.read records the book as read, runs the place hooks and sends the open-book packet
            LoreBookItem.read(player, book);
            for (PlaceLore.Note n : take) {
                Quests.give(player, LoreBookItem.create(n.book()));
                PlaceLoreEvents.markFound(d, n.bookId());
            }
            return;
        }
        PlayerData d = SkyData.get(player);
        CompoundTag module = d.module(MODULE);
        ListTag read = module.getListOrEmpty(READ);
        boolean first = true;
        for (int i = 0; i < read.size(); i++) if (read.getStringOr(i, "").equals(click.key())) first = false;
        if (first) {
            read.add(StringTag.valueOf(click.key()));
            while (read.size() > MAX_REMEMBERED) read.remove(0);
            module.put(READ, read);
            d.addStat("stories_read", 1);
            d.markDirty();
            if (!story.title().isBlank()) {
                Notifier.message(player, Component.translatable("message.nirncraft.lore.story_read", story.title()));
            }
        }
        sendStory(player, new LorePackets.OpenStory(clip(story.title(), 120), clip(story.author(), 100), story.pagesJson(), story.sheet(),
                story.kind()));
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
