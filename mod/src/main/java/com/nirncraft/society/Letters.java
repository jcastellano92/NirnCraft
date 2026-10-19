package com.nirncraft.society;

import com.nirncraft.core.Currency;
import com.nirncraft.core.Notifier;
import com.nirncraft.society.entity.NpcEntity;
import com.nirncraft.vitals.ActionHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Courier letters: sealed written books whose text lives in the language file
 * ({@code society.nirncraft.letter.<kind>.title/.page.<n>}). An inheritance comes with gold.
 */
public final class Letters {
    public static final int INHERITANCE = 0;
    public static final int INVITATION = 1;
    public static final int WARNING = 2;
    public static final int THANKS = 3;
    public static final int COLLEGE = 4;
    public static final int MAP = 5;
    private static final String[] KINDS = {"inheritance", "invitation", "warning", "thanks", "college", "map"};
    private static final int[] PAGES = {2, 2, 2, 1, 2, 2};

    private Letters() {}

    /** Which letter a courier brings this player. */
    public static int pick(Player player, RandomSource r) {
        for (String f : Reputation.FACTIONS) {
            if (Reputation.hostile(player, f) && r.nextInt(5) < 2) return WARNING;
        }
        if (!Npcs.isMember(player, "college") && r.nextInt(5) == 0) return COLLEGE;
        int[] common = {INHERITANCE, INVITATION, THANKS, MAP};
        return common[r.nextInt(common.length)];
    }

    /** The courier hands over its letter, then heads off. */
    public static void deliver(NpcEntity courier, ServerPlayer player) {
        int kind = Math.max(0, Math.min(KINDS.length - 1, courier.getLetter()));
        courier.setLetter(-1);
        Barks.sayLineNow(courier, "courier_arrive", player.getDisplayName());
        RandomSource r = courier.getRandom();
        String sender = NpcNames.generate(NpcNames.raceFor(NpcRole.JARL, r), r.nextBoolean(), r);
        int gold = kind == INHERITANCE ? 50 + r.nextInt(151) : 0;
        ItemStack letter = letter(kind, player, sender, gold);
        if (!ActionHandler.addToBags(player, letter)) player.drop(letter, false, net.minecraft.util.Prediction.SERVER_ONLY);
        player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0f, 1.0f);
        Notifier.message(player, Component.translatable("society.nirncraft.letter.received"));
        if (gold > 0) Currency.give(player, gold);
        // off they go
        double dx = courier.getX() - player.getX();
        double dz = courier.getZ() - player.getZ();
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        courier.setDestination(net.minecraft.core.BlockPos.containing(courier.getX() + dx / len * 80, courier.getY(), courier.getZ() + dz / len * 80));
    }

    public static ItemStack letter(int kind, Player player, String sender, int gold) {
        String key = "society.nirncraft.letter." + KINDS[kind];
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        java.util.List<net.minecraft.server.network.Filterable<Component>> pages = new java.util.ArrayList<>();
        for (int i = 0; i < PAGES[kind]; i++) {
            Component page = Component.translatable(key + ".page." + i, player.getName(), sender, gold);
            pages.add(net.minecraft.server.network.Filterable.passThrough(page));
        }
        book.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT,
                new net.minecraft.world.item.component.WrittenBookContent(
                        net.minecraft.server.network.Filterable.passThrough("Letter"), sender, 0, pages, true));
        book.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.translatable(key + ".title"));
        return book;
    }
}
