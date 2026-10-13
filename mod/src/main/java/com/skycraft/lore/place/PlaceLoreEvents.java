package com.skycraft.lore.place;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreBooks;
import com.skycraft.quest.Quests;
import com.skycraft.quest.story.Story;
import com.skycraft.quest.story.StoryData;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.world.Discovery;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * What place readables do: reading one reveals the place it points at (a treasure note marks its cache's ruin on the
 * map) and starts the mini-quest it names; a note waiting on a lectern is read in place and a copy goes to the
 * reader's pack; discovering a place shows its hook. Per-player state in {@code module("place_lore")}: {@code found}
 * (note book ids the player was handed, so containers don't hand them out twice).
 */
public final class PlaceLoreEvents {
    public static final String MODULE = "place_lore";
    private static final String FOUND = "found";

    private PlaceLoreEvents() {}

    // ------------------------------------------------------------------ found notes

    /** Whether the player already has (or had) this note: handed to them, or read. */
    public static boolean hasFound(PlayerData d, String bookId) {
        if (LoreBookItem.hasRead(d, bookId)) return true;
        ListTag found = d.module(MODULE).getList(FOUND, Tag.TAG_STRING);
        for (int i = 0; i < found.size(); i++) if (found.getString(i).equals(bookId)) return true;
        return false;
    }

    public static void markFound(PlayerData d, String bookId) {
        if (hasFound(d, bookId)) return;
        CompoundTag mod = d.module(MODULE);
        ListTag found = mod.getList(FOUND, Tag.TAG_STRING);
        found.add(StringTag.valueOf(bookId));
        mod.put(FOUND, found);
        d.markDirty();
    }

    // ------------------------------------------------------------------ hooks called by other modules

    /**
     * {@link LoreBookItem#read} hook: a place readable was opened. Reveals the place it points at and starts its
     * mini-quest (if the reader can take it). Server side.
     */
    public static void onRead(ServerPlayer player, LoreBooks.Book book) {
        PlaceLore.Note note = PlaceLore.note(book.id());
        if (note == null) return;
        markFound(SkyData.get(player), note.bookId());
        if (!note.reveals().isEmpty()) {
            Optional<Place> target = Places.byId(note.reveals());
            if (target.isPresent() && Discovery.revealPlace(player, target.get())) {
                Notifier.message(player, Component.translatable("place_lore.skycraft.revealed", target.get().name()).withStyle(ChatFormatting.GOLD));
            }
        }
        if (!note.starts().isEmpty()) startQuest(player, note.starts(), false);
    }

    /**
     * Starts a place mini-quest for the player if they can take it now. Returns true if it started. {@code quiet}:
     * no message when it can't (a rumour offers the next one instead).
     */
    public static boolean startQuest(ServerPlayer player, String questId, boolean quiet) {
        StoryData.QuestDef def = StoryData.quest(questId);
        if (def == null || !Story.available(player, def)) return false;
        if (!def.isMain() && !Quests.canTakeMore(player)) {
            if (!quiet) Notifier.message(player, Component.translatable("place_lore.skycraft.too_busy", def.title.c()));
            return false;
        }
        return Story.start(player, def);
    }

    /** {@link Discovery} hook: the player discovered a named place. Shows its hook under the banner. */
    public static void onDiscovered(ServerPlayer player, Place place) {
        Component hook = PlaceLore.hook(place.id());
        if (hook != null) player.sendSystemMessage(hook.copy().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        PlaceLorePlacer.onEnter(player, place);
    }

    // notes on lecterns: StoryReader (a lectern note opens in the reading screen and the reader keeps a copy)
}
