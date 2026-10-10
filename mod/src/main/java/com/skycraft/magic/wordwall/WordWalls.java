package com.skycraft.magic.wordwall;

import com.skycraft.core.Notifier;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.shout.Shout;
import com.skycraft.magic.spell.Element;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Learning Words of Power from Word Walls. */
public final class WordWalls {
    private WordWalls() {}

    /** Picks a shout for a new wall, weighted by {@link Shout#wallWeight}. */
    public static Shout randomShout(RandomSource random) {
        int total = 0;
        for (Shout s : Shout.VALUES) total += s.wallWeight;
        int roll = random.nextInt(total);
        for (Shout s : Shout.VALUES) {
            roll -= s.wallWeight;
            if (roll < 0) return s;
        }
        return Shout.UNRELENTING_FORCE;
    }

    /**
     * Placement API for structure/world code: makes the Word Wall at {@code pos} teach {@code shoutId} (a canonical id
     * from docs/FEATURES.md). Returns false if there is no Word Wall there; unknown ids are kept and degrade gracefully.
     */
    public static boolean assign(net.minecraft.world.level.LevelAccessor level, BlockPos pos, String shoutId) {
        if (!(level.getBlockEntity(pos) instanceof WordWallBlockEntity be)) return false;
        if (!be.setShoutId(shoutId)) {
            com.skycraft.Skycraft.LOGGER.warn("Word Wall at {} assigned unknown shout id '{}'; it will teach another shout", pos, shoutId);
        }
        return true;
    }

    /** A stable stand-in for an unknown shout id (same id, same shout). */
    public static Shout fallbackFor(String unknownId) {
        Shout[] all = Shout.VALUES;
        return all[Math.floorMod(unknownId.hashCode(), all.length)];
    }

    public static String wallKey(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "@" + pos.asLong();
    }

    /**
     * Teaches the next word of the wall's shout. If the player already knows all three, the wall teaches the next
     * word of another shout they haven't mastered (so no wall is ever wasted).
     *
     * @param explicit true when the player clicked the wall (shows "already read" feedback)
     */
    public static void teach(ServerPlayer p, ServerLevel level, BlockPos pos, Shout shout, boolean explicit) {
        String key = wallKey(level, pos);
        if (MagicData.hasReadWall(p, key)) {
            if (explicit) Notifier.message(p, Component.translatable("message.skycraft.wall_already_read"));
            return;
        }
        Shout target = MagicData.wordsLearned(p, shout) < 3 ? shout : fallback(p, pos);
        MagicData.markWallRead(p, key);
        if (target == null) {
            Notifier.message(p, Component.translatable("message.skycraft.all_words_known"));
            return;
        }
        int index = MagicData.wordsLearned(p, target);
        MagicData.setWordsLearned(p, target, index + 1);

        Vec3 from = Vec3.atCenterOf(pos);
        MagicFx.sendNear(level, from, 64, MagicFx.STREAM, Element.ARCANE, from, from, p.getId(), 70);
        level.playSound(null, pos, com.skycraft.world.WorldSounds.WORDWALL_LEARN.get(), SoundSource.BLOCKS, 1.8f, 1.0f);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), com.skycraft.world.WorldSounds.WORDWALL_LEARN.get(), SoundSource.PLAYERS, 1.2f, 1.0f);
        Notifier.title(p, Component.translatable("notify.skycraft.word_learned"), target.wordWithTranslation(index));
        if (MagicData.dragonSouls(p) > 0) {
            Notifier.message(p, Component.translatable("message.skycraft.word_learned_hint_soul", target.displayName(), Component.keybind("key.skycraft.magic_menu")));
        } else {
            Notifier.message(p, Component.translatable("message.skycraft.word_learned_hint", target.displayName(), Component.keybind("key.skycraft.magic_menu")));
        }
    }

    @Nullable
    private static Shout fallback(ServerPlayer p, BlockPos pos) {
        int n = Shout.VALUES.length;
        int start = Math.floorMod(Long.hashCode(pos.asLong()), n);
        for (int i = 0; i < n; i++) {
            Shout s = Shout.VALUES[(start + i) % n];
            if (MagicData.wordsLearned(p, s) < 3) return s;
        }
        return null;
    }
}
