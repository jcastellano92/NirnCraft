package com.skycraft.curse;

import com.skycraft.core.Notifier;
import com.skycraft.network.NotifyKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Werewolf and Vampire Lord perk trees (eleven perks each, Dawnguard style). They don't use perk points from
 * leveling: feeding in Beast Form (on corpses) or as the Vampire Lord (bites and Drain Life kills) earns them.
 * Stored in {@code module("curse")}: {@code feeds_werewolf}, {@code feeds_lord} and {@code perks} (id -> rank).
 */
public final class CursePerks {
    public enum Tree {
        WEREWOLF("werewolf", 0xC89A5A), VAMPIRE_LORD("vampire_lord", 0xC8303A);

        public final String id;
        public final int color;

        Tree(String id, int color) {
            this.id = id;
            this.color = color;
        }

        String feedsKey() {
            return "feeds_" + id;
        }

        public Component displayName() {
            return Component.translatable("curse.skycraft.tree." + id);
        }
    }

    /**
     * A star of a curse constellation.
     *
     * @param x horizontal position -1..1; {@code y} 0 (bottom) .. 1 (top)
     */
    public record CursePerk(String id, Tree tree, int maxRank, List<String> parents, float x, float y) {
        public Component displayName() {
            return Component.translatable("curse.skycraft.perk." + id);
        }

        public Component description() {
            return Component.translatable("curse.skycraft.perk." + id + ".desc");
        }
    }

    // Werewolf (Hircine's hunt)
    public static final String BESTIAL_STRENGTH = "bestial_strength";
    public static final String ANIMAL_VIGOR = "animal_vigor";
    public static final String THICK_HIDE = "thick_hide";
    public static final String GORGING = "gorging";
    public static final String SAVAGE_FEEDING = "savage_feeding";
    public static final String HIRCINES_FAVOR = "hircines_favor";
    public static final String TOTEM_OF_TERROR = "totem_of_terror";
    public static final String TOTEM_OF_THE_PREDATOR = "totem_of_the_predator";
    public static final String TOTEM_OF_ICE_BROTHERS = "totem_of_ice_brothers";
    public static final String TOTEM_OF_THE_MOON = "totem_of_the_moon";
    public static final String BLOOD_FRENZY = "blood_frenzy";
    // Vampire Lord (Molag Bal's gift)
    public static final String POWER_OF_THE_GRAVE = "power_of_the_grave";
    public static final String DETECT_LIFE = "detect_life";
    public static final String MIST_FORM = "mist_form";
    public static final String SUPERNATURAL_REFLEXES = "supernatural_reflexes";
    public static final String BLOOD_HEALING = "blood_healing";
    public static final String UNEARTHLY_WILL = "unearthly_will";
    public static final String VAMPIRIC_GRIP = "vampiric_grip";
    public static final String POISON_TALONS = "poison_talons";
    public static final String NIGHT_CLOAK = "night_cloak";
    public static final String CORPSE_CURSE = "corpse_curse";
    public static final String SUMMON_GARGOYLE = "summon_gargoyle";

    private static final Map<String, CursePerk> BY_ID = new LinkedHashMap<>();

    static {
        Tree w = Tree.WEREWOLF;
        p(w, BESTIAL_STRENGTH, 4, 0f, 0.05f);
        p(w, ANIMAL_VIGOR, 1, -0.45f, 0.28f, BESTIAL_STRENGTH);
        p(w, THICK_HIDE, 1, -0.8f, 0.5f, ANIMAL_VIGOR);
        p(w, GORGING, 1, 0.45f, 0.28f, BESTIAL_STRENGTH);
        p(w, SAVAGE_FEEDING, 1, 0.8f, 0.5f, GORGING);
        p(w, HIRCINES_FAVOR, 1, 0.5f, 0.72f, SAVAGE_FEEDING);
        p(w, TOTEM_OF_TERROR, 1, 0f, 0.4f, BESTIAL_STRENGTH);
        p(w, TOTEM_OF_THE_PREDATOR, 1, -0.3f, 0.62f, TOTEM_OF_TERROR);
        p(w, TOTEM_OF_ICE_BROTHERS, 1, -0.55f, 0.82f, TOTEM_OF_THE_PREDATOR);
        p(w, TOTEM_OF_THE_MOON, 1, 0f, 0.95f, TOTEM_OF_ICE_BROTHERS, HIRCINES_FAVOR);
        p(w, BLOOD_FRENZY, 1, 0.15f, 0.7f, TOTEM_OF_TERROR);

        Tree l = Tree.VAMPIRE_LORD;
        p(l, POWER_OF_THE_GRAVE, 1, 0f, 0.05f);
        p(l, DETECT_LIFE, 1, -0.5f, 0.25f, POWER_OF_THE_GRAVE);
        p(l, MIST_FORM, 1, -0.8f, 0.48f, DETECT_LIFE);
        p(l, SUPERNATURAL_REFLEXES, 1, -0.55f, 0.72f, MIST_FORM);
        p(l, BLOOD_HEALING, 1, 0.5f, 0.25f, POWER_OF_THE_GRAVE);
        p(l, UNEARTHLY_WILL, 1, 0.8f, 0.48f, BLOOD_HEALING);
        p(l, VAMPIRIC_GRIP, 1, 0.05f, 0.4f, POWER_OF_THE_GRAVE);
        p(l, POISON_TALONS, 1, 0.5f, 0.66f, UNEARTHLY_WILL, VAMPIRIC_GRIP);
        p(l, NIGHT_CLOAK, 1, -0.15f, 0.66f, VAMPIRIC_GRIP);
        p(l, CORPSE_CURSE, 1, 0.3f, 0.88f, POISON_TALONS);
        p(l, SUMMON_GARGOYLE, 1, -0.25f, 0.95f, SUPERNATURAL_REFLEXES, NIGHT_CLOAK);
    }

    private CursePerks() {}

    private static void p(Tree tree, String id, int maxRank, float x, float y, String... parents) {
        BY_ID.put(id, new CursePerk(id, tree, maxRank, List.of(parents), x, y));
    }

    public static CursePerk get(String id) {
        return BY_ID.get(id);
    }

    public static List<CursePerk> of(Tree tree) {
        List<CursePerk> out = new ArrayList<>();
        for (CursePerk p : BY_ID.values()) if (p.tree() == tree) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ ranks and points (both sides)

    public static int rank(Player player, String id) {
        return Curses.tag(player).getCompound("perks").getInt(id);
    }

    public static boolean has(Player player, String id) {
        return rank(player, id) > 0;
    }

    public static int feeds(Player player, Tree tree) {
        return Curses.tag(player).getInt(tree.feedsKey());
    }

    /** Feedings needed for the {@code n}-th perk point of a tree (1-based): 2, 5, 9, 14, 20, ... */
    public static int feedsFor(int n) {
        return n * (n + 3) / 2;
    }

    /** Perk points earned so far in a tree. */
    public static int earned(Player player, Tree tree) {
        int feeds = feeds(player, tree);
        int n = 0;
        while (feedsFor(n + 1) <= feeds && n < 64) n++;
        return n;
    }

    public static int spent(Player player, Tree tree) {
        CompoundTag perks = Curses.tag(player).getCompound("perks");
        int total = 0;
        for (CursePerk p : of(tree)) total += perks.getInt(p.id());
        return total;
    }

    public static int available(Player player, Tree tree) {
        return Math.max(0, earned(player, tree) - spent(player, tree));
    }

    public static boolean canUnlock(Player player, CursePerk perk) {
        int current = rank(player, perk.id());
        if (current >= perk.maxRank()) return false;
        if (perk.parents().isEmpty() || current > 0) return true;
        for (String parent : perk.parents()) if (has(player, parent)) return true;
        return false;
    }

    /** Whether the player may look at a tree at all (has the curse that goes with it). */
    public static boolean hasTree(Player player, Tree tree) {
        return tree == Tree.WEREWOLF ? Curses.isWerewolf(player) : Curses.hasVampireLord(player);
    }

    // ------------------------------------------------------------------ server

    /** One more feeding towards the tree's next perk. */
    static void addFeed(ServerPlayer player, Tree tree) {
        CompoundTag t = Curses.tag(player);
        int before = earned(player, tree);
        t.putInt(tree.feedsKey(), t.getInt(tree.feedsKey()) + 1);
        Curses.dirty(player);
        if (earned(player, tree) > before) {
            Notifier.send(player, NotifyKind.MESSAGE, Component.translatable("curse.skycraft.perk.earned", tree.displayName()), Component.empty());
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 0.6f);
        }
    }

    /** Validated unlock request from the perk screen. */
    static void unlock(ServerPlayer player, String id) {
        CursePerk perk = get(id);
        if (perk == null || !hasTree(player, perk.tree())) return;
        if (available(player, perk.tree()) <= 0 && !player.isCreative()) return;
        if (!canUnlock(player, perk)) return;
        CompoundTag t = Curses.tag(player);
        CompoundTag perks = t.getCompound("perks");
        perks.putInt(id, perks.getInt(id) + 1);
        t.put("perks", perks);
        Curses.dirty(player);
        Vampirism.refreshBonuses(player);
        if (Curses.form(player) == Form.BEAST) Lycanthropy.applyAttributes(player, true);
        player.level().playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 0.8f, 0.7f);
    }
}
