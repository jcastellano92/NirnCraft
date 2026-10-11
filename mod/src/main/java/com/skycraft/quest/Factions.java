package com.skycraft.quest;

import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Faction membership, reputation, ranks and rank rewards. State lives in the player's {@code module("quest")}:
 * {@code factions} (id -> rank, the cross-module contract) and {@code faction_points} (id -> reputation).
 */
public final class Factions {
    public static final TagKey<Item> SPELL_TOMES = TagKey.create(Registries.ITEM, new ResourceLocation(Skycraft.MODID, "spell_tomes"));

    private Factions() {}

    // ------------------------------------------------------------------ state

    public static CompoundTag factionsTag(PlayerData data) {
        CompoundTag mod = data.module("quest");
        if (!mod.contains("factions", Tag.TAG_COMPOUND)) mod.put("factions", new CompoundTag());
        return mod.getCompound("factions");
    }

    private static CompoundTag pointsTag(PlayerData data) {
        CompoundTag mod = data.module("quest");
        if (!mod.contains("faction_points", Tag.TAG_COMPOUND)) mod.put("faction_points", new CompoundTag());
        return mod.getCompound("faction_points");
    }

    /** Rank in the faction, or -1 if not a member. */
    public static int rank(PlayerData data, Faction f) {
        CompoundTag t = data.module("quest").getCompound("factions");
        return t.contains(f.id) ? t.getInt(f.id) : -1;
    }

    public static boolean isMember(PlayerData data, Faction f) {
        return rank(data, f) >= 0;
    }

    public static int points(PlayerData data, Faction f) {
        return data.module("quest").getCompound("faction_points").getInt(f.id);
    }

    /** Joins a faction. Fails (with a message) if already a member or enemy of it. */
    public static boolean join(ServerPlayer player, Faction f) {
        PlayerData data = SkyData.get(player);
        if (isMember(data, f)) return false;
        Faction rival = f.rival();
        if (rival != null && isMember(data, rival)) {
            Notifier.message(player, Component.translatable("faction.skycraft.rival_member", rival.displayName()));
            return false;
        }
        factionsTag(data).putInt(f.id, 0);
        pointsTag(data).putInt(f.id, 0);
        data.markDirty();
        Notifier.title(player, Component.translatable("faction.skycraft.joined", f.displayName()), f.rankName(0));
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.6f, 1.0f);
        rankReward(player, f, 0);
        return true;
    }

    /** Adds reputation; promotes through every rank reached. */
    public static void addPoints(ServerPlayer player, Faction f, int amount) {
        PlayerData data = SkyData.get(player);
        int rank = rank(data, f);
        if (rank < 0 || amount <= 0) return;
        int pts = points(data, f) + amount;
        pointsTag(data).putInt(f.id, pts);
        Notifier.message(player, Component.translatable("faction.skycraft.reputation", f.displayName(), amount));
        while (rank < f.maxRank() && pts >= Faction.pointsFor(rank + 1)) {
            rank++;
            factionsTag(data).putInt(f.id, rank);
            Notifier.title(player, Component.translatable("faction.skycraft.promoted", f.rankName(rank)), f.displayName());
            rankReward(player, f, rank);
        }
        data.markDirty();
    }

    public static void setRank(ServerPlayer player, Faction f, int rank) {
        PlayerData data = SkyData.get(player);
        if (rank < 0) {
            factionsTag(data).remove(f.id);
            pointsTag(data).remove(f.id);
        } else {
            int r = Math.min(f.maxRank(), rank);
            factionsTag(data).putInt(f.id, r);
            pointsTag(data).putInt(f.id, Math.max(points(data, f), Faction.pointsFor(r)));
        }
        data.markDirty();
    }

    /** Applies the faction part of a completed quest. */
    public static void onQuestReward(ServerPlayer player, Quest q) {
        Faction f = Faction.byId(q.faction);
        if (f == null) return;
        PlayerData data = SkyData.get(player);
        if (q.factionJoin && !isMember(data, f)) {
            join(player, f);
        } else if (isMember(data, f)) {
            addPoints(player, f, Math.max(1, q.factionPoints));
        }
    }

    // ------------------------------------------------------------------ rank rewards

    private static ItemStack named(Item item, String nameKey) {
        ItemStack stack = new ItemStack(item);
        stack.setHoverName(Component.translatable("item.skycraft.reward." + nameKey));
        return stack;
    }

    private static ItemStack enchanted(ItemStack stack, Enchantment e, int level) {
        stack.enchant(e, level);
        return stack;
    }

    private static ItemStack dyedNamed(Item item, int color, String nameKey) {
        ItemStack s = QuestSpawner.dyed(item, color);
        s.setHoverName(Component.translatable("item.skycraft.reward." + nameKey));
        return s;
    }

    private static void armorSet(ServerPlayer p, int color, String nameKey, @Nullable Enchantment e, int level) {
        Item[] pieces = {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
        for (Item piece : pieces) {
            ItemStack s = dyedNamed(piece, color, nameKey);
            if (e != null) s.enchant(e, level);
            Quests.give(p, s);
        }
    }

    /** A random spell tome from {@code #skycraft:spell_tomes} (magic module), if any exist. */
    public static ItemStack randomSpellTome(RandomSource random) {
        Optional<Item> item = BuiltInRegistries.ITEM.getTag(SPELL_TOMES)
                .flatMap(set -> set.getRandomElement(random))
                .map(Holder::value);
        return item.map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    private static void giveTome(ServerPlayer p) {
        ItemStack tome = randomSpellTome(p.getRandom());
        if (tome.isEmpty()) Currency.give(p, 150);
        else Quests.give(p, tome);
    }

    static void rankReward(ServerPlayer p, Faction f, int rank) {
        switch (f) {
            case COMPANIONS -> {
                switch (rank) {
                    case 0 -> Quests.give(p, named(Items.IRON_SWORD, "companion_blade"));
                    case 1 -> {
                        Quests.give(p, named(Items.IRON_CHESTPLATE, "skyforge_armor"));
                        Currency.give(p, 200);
                    }
                    case 2 -> {
                        // the curse module grants lycanthropy (and sets the quest flag)
                        com.skycraft.curse.Curses.grantBeastBlood(p);
                        Notifier.message(p, Component.translatable("faction.skycraft.companions.beast_blood"));
                        Currency.give(p, 500);
                    }
                    default -> {
                        Quests.give(p, enchanted(named(Items.DIAMOND_AXE, "wuuthrad"), Enchantments.SHARPNESS, 3));
                        Currency.give(p, 1000);
                    }
                }
            }
            case COLLEGE -> {
                if (rank == 0) Quests.give(p, dyedNamed(Items.LEATHER_CHESTPLATE, 0x3A4C8C, "novice_robes"));
                giveTome(p);
                if (rank >= 2) Currency.give(p, 150L * rank);
                if (rank == f.maxRank()) {
                    giveTome(p);
                    giveTome(p);
                    Quests.give(p, enchanted(named(Items.DIAMOND_HELMET, "morokei"), Enchantments.ALL_DAMAGE_PROTECTION, 3));
                    Currency.give(p, 1000);
                }
            }
            case THIEVES_GUILD -> {
                if (rank == 0) armorSet(p, 0x2B2B2B, "thieves_guild_armor", null, 0);
                else if (rank == 3) armorSet(p, 0x1E1A24, "nightingale_armor", Enchantments.ALL_DAMAGE_PROTECTION, 2);
                else if (rank == f.maxRank()) {
                    Quests.give(p, enchanted(named(Items.DIAMOND_SWORD, "chillrend"), Enchantments.SHARPNESS, 4));
                    Currency.give(p, 2000);
                } else Currency.give(p, 200L * rank);
            }
            case DARK_BROTHERHOOD -> {
                if (rank == 0) {
                    armorSet(p, 0x3A1212, "shrouded_armor", null, 0);
                    Quests.give(p, enchanted(named(Items.IRON_SWORD, "blade_of_woe"), Enchantments.SHARPNESS, 2));
                } else if (rank == f.maxRank()) {
                    Quests.give(p, enchanted(named(Items.NETHERITE_SWORD, "ebony_blade"), Enchantments.SHARPNESS, 4));
                    Currency.give(p, 2500);
                } else Currency.give(p, 250L * rank);
            }
            case IMPERIAL_LEGION, STORMCLOAKS -> {
                boolean imperial = f == Faction.IMPERIAL_LEGION;
                if (rank == 0) armorSet(p, imperial ? 0x8B1E1E : 0x2F4F8F, imperial ? "imperial_armor" : "stormcloak_armor", null, 0);
                else if (rank == 1) Quests.give(p, named(Items.IRON_CHESTPLATE, imperial ? "imperial_officer_armor" : "stormcloak_officer_armor"));
                else if (rank == f.maxRank()) {
                    Quests.give(p, enchanted(named(imperial ? Items.DIAMOND_SWORD : Items.DIAMOND_AXE, imperial ? "legate_blade" : "blade_of_the_bear"),
                            Enchantments.SHARPNESS, 3));
                    Currency.give(p, 1500);
                } else Currency.give(p, 250L * rank);
            }
            case BARDS_COLLEGE -> {
                if (rank == 0) Quests.give(p, new ItemStack(Items.JUKEBOX));
                else if (rank == 1) {
                    Quests.give(p, new ItemStack(Items.MUSIC_DISC_CAT));
                    Currency.give(p, 300);
                } else {
                    Quests.give(p, named(Items.GOAT_HORN, "horn_of_the_bard"));
                    Currency.give(p, 800);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Dark Brotherhood whisper

    /** Murderers hear the Night Mother's voice at night and find a letter: "We know." */
    public static void tickWhisper(ServerPlayer player) {
        if (!QuestConfig.DARK_WHISPER.get()) return;
        PlayerData data = SkyData.get(player);
        CompoundTag mod = data.module("quest");
        if (mod.getBoolean("db_whispered") || isMember(data, Faction.DARK_BROTHERHOOD)) return;
        if (data.module("crime").getInt("murders") < 1) return;
        Level level = player.level();
        if (level.dimension() != Level.OVERWORLD) return;
        long time = level.getDayTime() % 24000L;
        if (time < 13000 || time > 23000) return;
        mod.putBoolean("db_whispered", true);
        data.markDirty();
        Notifier.title(player, Component.translatable("quest.skycraft.db.whisper_title"), Component.translatable("quest.skycraft.db.whisper_sub"));
        Notifier.message(player, Component.translatable("quest.skycraft.db.whisper_hint"));
        level.playSound(null, player.blockPosition(), SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 1.0f, 0.6f);
        Quests.give(player, new ItemStack(QuestItems.BLACK_HAND_LETTER.get()));
    }

    public static boolean isNight(Level level) {
        long time = level.getDayTime() % 24000L;
        return time >= 13000 && time <= 23000;
    }
}
