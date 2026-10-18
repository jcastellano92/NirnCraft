package com.skycraft.magic;

import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.lore.StandingStone;
import com.skycraft.lore.StandingStones;
import com.skycraft.magic.shout.Shout;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.Spells;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Accessors for the {@code "magic"} module tag of {@link PlayerData}. The tag is synced to the owning client, so the
 * client reads the same keys for the HUD and the magic menu.
 *
 * <p>Cross-module contract keys: {@code spells} (list of spell ids), {@code words} (shout id -> words learned),
 * {@code dragon_souls}. Our own keys: {@code right_spell} (alias {@code selected_spell}), {@code left_spell}, {@code selected_shout}, {@code unlocked} (shout id ->
 * words unlocked with dragon souls), {@code walls} (word walls already read), {@code favorites},
 * {@code shout_ready}/{@code shout_total} (voice cooldown), {@code dragonborn}, {@code avoid_death}.</p>
 */
public final class MagicData {
    public static final String MODULE = "magic";

    private MagicData() {}

    public static CompoundTag tag(Player player) {
        return SkyData.get(player).module(MODULE);
    }

    private static void dirty(Player player) {
        SkyData.get(player).markDirty();
    }

    // ------------------------------------------------------------------ spells

    public static List<String> knownSpells(Player player) {
        ListTag list = tag(player).getListOrEmpty("spells");
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) out.add(list.getStringOr(i, ""));
        return out;
    }

    public static boolean knows(Player player, String spellId) {
        ListTag list = tag(player).getListOrEmpty("spells");
        for (int i = 0; i < list.size(); i++) {
            if (list.getStringOr(i, "").equals(spellId)) return true;
        }
        return false;
    }

    /**
     * Adds a spell to the spell list; returns false if it was already known. Learning never equips: the spell does
     * not go into a hand (equip it from the magic menu or the favorites, see {@link Hands}).
     */
    public static boolean learn(Player player, String spellId) {
        if (knows(player, spellId)) return false;
        CompoundTag tag = tag(player);
        ListTag list = tag.getListOrEmpty("spells");
        list.add(StringTag.valueOf(spellId));
        tag.put("spells", list);
        dirty(player);
        return true;
    }

    public static boolean isFavorite(Player player, String id) {
        return favorites(player).contains(id);
    }

    /** Removes a spell (made spells can be forgotten); it leaves the hands and favorites too. */
    public static void forget(Player player, String spellId) {
        CompoundTag tag = tag(player);
        ListTag list = tag.getListOrEmpty("spells");
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.getStringOr(i, "").equals(spellId)) list.remove(i);
        }
        tag.put("spells", list);
        if (spellId.equals(tag.getStringOr("right_spell", "")) || spellId.equals(tag.getStringOr("selected_spell", ""))) {
            tag.putString("right_spell", "");
            tag.putString("selected_spell", "");
        }
        if (spellId.equals(tag.getStringOr("left_spell", ""))) tag.putString("left_spell", "");
        ListTag favs = tag.getListOrEmpty("favorites");
        for (int i = favs.size() - 1; i >= 0; i--) {
            if (favs.getStringOr(i, "").equals(spellId)) favs.remove(i);
        }
        tag.put("favorites", favs);
        dirty(player);
    }

    /** The right-hand spell ({@code right_spell}; {@code selected_spell} is kept as an alias for older data). */
    public static String selectedSpell(Player player) {
        CompoundTag tag = tag(player);
        String right = tag.getStringOr("right_spell", "");
        return right.isEmpty() ? tag.getStringOr("selected_spell", "") : right;
    }

    /** Equips the right-hand spell. */
    public static void setSelectedSpell(Player player, String spellId) {
        CompoundTag tag = tag(player);
        tag.putString("right_spell", spellId);
        tag.putString("selected_spell", spellId);
        dirty(player);
    }

    /** The left-hand spell, or "" (casting with an empty left hand then uses the right-hand spell). */
    public static String leftSpell(Player player) {
        return tag(player).getStringOr("left_spell", "");
    }

    /** Marks the left hand as deliberately empty (an empty {@code left_spell} falls back to the right-hand spell). */
    public static final String NONE = "none";

    /** The spell the left hand actually casts: its own, else the right-hand spell, or "" when unequipped on purpose. */
    public static String leftEffective(Player player) {
        String left = leftSpell(player);
        if (NONE.equals(left)) return "";
        return left.isEmpty() ? selectedSpell(player) : left;
    }

    /** Empties the right hand (both the new key and the older alias). */
    public static void clearRightSpell(Player player) {
        CompoundTag tag = tag(player);
        tag.putString("right_spell", "");
        tag.putString("selected_spell", "");
        dirty(player);
    }

    public static void setLeftSpell(Player player, String spellId) {
        tag(player).putString("left_spell", spellId);
        dirty(player);
    }

    public static List<String> favorites(Player player) {
        ListTag list = tag(player).getListOrEmpty("favorites");
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) out.add(list.getStringOr(i, ""));
        return out;
    }

    public static void toggleFavorite(Player player, String id) {
        CompoundTag tag = tag(player);
        ListTag list = tag.getListOrEmpty("favorites");
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.getStringOr(i, "").equals(id)) {
                list.remove(i);
                removed = true;
            }
        }
        if (!removed) {
            if (list.size() >= MAX_FAVORITES) return;
            list.add(StringTag.valueOf(id));
        }
        tag.put("favorites", list);
        dirty(player);
    }

    /** Favorites can hold every spell, shout and power, but no more than this. */
    public static final int MAX_FAVORITES = 128;

    /** Whether {@code id} names a spell the player knows, a shout it has learned or a power it has. */
    public static boolean canFavorite(Player player, String id) {
        Spell spell = Spells.byId(id);
        if (spell != null) return knows(player, id) || player.isCreative();
        Shout shout = Shout.byId(id);
        if (shout != null) return wordsLearned(player, shout) > 0 || player.isCreative();
        return hasPower(player, id);
    }

    /** Whether {@code voice} is a power the player has: "power:racial" or its current standing stone's power. */
    public static boolean hasPower(Player player, String voice) {
        if ("power:racial".equals(voice)) return SkyData.get(player).getRace() != null;
        if (voice.startsWith("power:stone:")) {
            StandingStone stone = StandingStones.current(player);
            return stone != null && stone.hasPower && voice.equals("power:stone:" + stone.id());
        }
        // curse module: Beast Form, the Vampire Lord and the vampire's powers
        if (voice.startsWith(com.skycraft.curse.CursePowers.PREFIX)) {
            return com.skycraft.curse.CursePowers.has(player, voice.substring(com.skycraft.curse.CursePowers.PREFIX.length()));
        }
        return false;
    }

    // ------------------------------------------------------------------ shouts

    public static int wordsLearned(Player player, Shout shout) {
        return Math.min(3, tag(player).getCompoundOrEmpty("words").getIntOr(shout.id(), 0));
    }

    public static void setWordsLearned(Player player, Shout shout, int words) {
        CompoundTag tag = tag(player);
        CompoundTag w = tag.getCompoundOrEmpty("words");
        w.putInt(shout.id(), Math.max(0, Math.min(3, words)));
        tag.put("words", w);
        if (tag.getStringOr("selected_shout", "").isEmpty()) tag.putString("selected_shout", shout.id());
        dirty(player);
    }

    public static int wordsUnlocked(Player player, Shout shout) {
        return Math.min(3, tag(player).getCompoundOrEmpty("unlocked").getIntOr(shout.id(), 0));
    }

    public static void setWordsUnlocked(Player player, Shout shout, int words) {
        CompoundTag tag = tag(player);
        CompoundTag w = tag.getCompoundOrEmpty("unlocked");
        w.putInt(shout.id(), Math.max(0, Math.min(3, words)));
        tag.put("unlocked", w);
        dirty(player);
    }

    /** Words that can actually be shouted: learned on a word wall and unlocked with a dragon soul. */
    public static int usableWords(Player player, Shout shout) {
        return Math.min(wordsLearned(player, shout), wordsUnlocked(player, shout));
    }

    public static int dragonSouls(Player player) {
        return tag(player).getIntOr("dragon_souls", 0);
    }

    public static void setDragonSouls(Player player, int souls) {
        tag(player).putInt("dragon_souls", Math.max(0, souls));
        dirty(player);
    }

    public static String selectedShout(Player player) {
        return tag(player).getStringOr("selected_shout", "");
    }

    public static void setSelectedShout(Player player, String shoutId) {
        CompoundTag tag = tag(player);
        tag.putString("selected_shout", shoutId);
        tag.putString("voice", "shout:" + shoutId);
        dirty(player);
    }

    /** The currently equipped Voice slot: "shout:<id>", "power:racial", or "power:stone:<id>". */
    public static String selectedVoice(Player player) {
        CompoundTag tag = tag(player);
        String voice = tag.getStringOr("voice", "");
        if (!voice.isEmpty()) return voice;
        String shout = tag.getStringOr("selected_shout", "");
        if (!shout.isEmpty()) return "shout:" + shout;
        return "power:racial";
    }

    public static void setSelectedVoice(Player player, String voice) {
        CompoundTag tag = tag(player);
        tag.putString("voice", voice);
        if (voice.startsWith("shout:")) {
            tag.putString("selected_shout", voice.substring("shout:".length()));
        }
        dirty(player);
    }

    /** Game time at which the voice is ready again. */
    public static long shoutReadyAt(Player player) {
        return tag(player).getLongOr("shout_ready", 0L);
    }

    /** Length of the current voice cooldown in ticks (for the HUD meter). */
    public static long shoutCooldownTotal(Player player) {
        return tag(player).getLongOr("shout_total", 0L);
    }

    public static void setShoutCooldown(Player player, long readyAt, long total) {
        CompoundTag tag = tag(player);
        tag.putLong("shout_ready", readyAt);
        tag.putLong("shout_total", total);
        dirty(player);
    }

    // ------------------------------------------------------------------ word walls

    public static boolean hasReadWall(Player player, String wallKey) {
        ListTag list = tag(player).getListOrEmpty("walls");
        for (int i = 0; i < list.size(); i++) {
            if (list.getStringOr(i, "").equals(wallKey)) return true;
        }
        return false;
    }

    public static void markWallRead(Player player, String wallKey) {
        CompoundTag tag = tag(player);
        ListTag list = tag.getListOrEmpty("walls");
        list.add(StringTag.valueOf(wallKey));
        tag.put("walls", list);
        dirty(player);
    }
}
