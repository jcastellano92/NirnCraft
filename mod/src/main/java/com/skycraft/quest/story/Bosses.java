package com.skycraft.quest.story;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.creatures.Leveling;
import com.skycraft.loot.LegendaryUniques;
import com.skycraft.loot.LootApi;
import com.skycraft.quest.Quest;
import com.skycraft.quest.QuestSpawner;
import com.skycraft.quest.Quests;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unique named bosses of the side quests ({@link StoryData.BossDef}): an existing creature made into a character
 * with a level, gear and a drop of its own, plus the loot-module rewards side quests hand out
 * ({@link LootApi#questReward reward tiers} and {@link LegendaryUniques legendary uniques}).
 *
 * <p>Spawn-spec fields beyond the usual name/hp/dmg/weapon/armor: {@code level} (a floor: the boss is at least this
 * level, more if the party is stronger), {@code gear} (item ids by slot: head, chest, legs, feet, offhand) and
 * {@code drop} ({@code unique:<id>}, {@code loot:<tier>} or an item id; several separated by commas).</p>
 */
public final class Bosses {
    /** Persistent-data key of a boss's drop spec (read when it dies). */
    public static final String DROP = "skycraft_boss_drop";
    public static final Set<String> TIERS = Set.of("minor", "standard", "major", "legendary");
    private static final Map<String, EquipmentSlot> SLOTS = Map.of("head", EquipmentSlot.HEAD, "chest", EquipmentSlot.CHEST,
            "legs", EquipmentSlot.LEGS, "feet", EquipmentSlot.FEET, "offhand", EquipmentSlot.OFFHAND, "mainhand", EquipmentSlot.MAINHAND);

    private Bosses() {}

    // ------------------------------------------------------------------ spawn specs

    /** Copies the boss-only fields of a JSON spawn spec into its NBT spec. */
    static void extraSpec(JsonObject s, CompoundTag t) {
        if (s.has("level")) t.putInt("level", s.get("level").getAsInt());
        if (s.has("drop")) t.putString("drop", s.get("drop").getAsString());
        if (s.has("gear") && s.get("gear").isJsonObject()) {
            CompoundTag gear = new CompoundTag();
            for (Map.Entry<String, JsonElement> e : s.getAsJsonObject("gear").entrySet()) gear.putString(e.getKey(), e.getValue().getAsString());
            t.put("gear", gear);
        }
    }

    /**
     * Called by {@link QuestSpawner} for every quest entity before it joins the world: level, gear and drop of
     * a boss spec. Does nothing for ordinary spawns.
     */
    public static void apply(Entity e, CompoundTag spec) {
        if (!(e instanceof Mob mob)) return;
        if (spec.contains("gear")) {
            CompoundTag gear = spec.getCompoundOrEmpty("gear");
            for (String slot : gear.keySet()) {
                EquipmentSlot s = SLOTS.get(slot);
                Item item = Story.item(gear.getStringOr(slot, ""));
                if (s == null || item == null) continue;
                mob.setItemSlot(s, new ItemStack(item));
                mob.setDropChance(s, 0.0f);
            }
        }
        if (spec.contains("drop")) mob.getPersistentData().putString(DROP, spec.getStringOr("drop",""));
        if (spec.contains("level")) {
            int level = spec.getIntOr("level",0);
            if (mob.level() instanceof ServerLevel sl) {
                Player near = sl.getNearestPlayer(mob.getX(), mob.getY(), mob.getZ(), 96, false);
                if (near != null) level = Math.max(level, SkyData.get(near).getLevel() + 2);
            }
            Leveling.apply(mob, Math.max(1, Math.min(60, level)));
        }
    }

    /** {@code boss:<id>}: the boss rises a few blocks from the player, tagged to the quest like its other foes. */
    static void ambush(ServerPlayer p, String key, Quest q, String bossId) {
        StoryData.BossDef b = StoryData.boss(bossId);
        if (b == null) {
            Skycraft.LOGGER.warn("Story action names unknown boss {}", bossId);
            return;
        }
        JsonObject json = new JsonObject();
        StoryData.bossSpec(b, json);
        CompoundTag spec = new CompoundTag();
        spec.putString("type", StoryData.str(json, "type", "skycraft:draugr"));
        spec.putString("name", Quest.writeComponent(b.name().c()));
        spec.putBoolean("boss", true);
        if (json.has("hp")) spec.putFloat("hp", json.get("hp").getAsFloat());
        if (json.has("dmg")) spec.putFloat("dmg", json.get("dmg").getAsFloat());
        if (json.has("weapon")) spec.putString("weapon", json.get("weapon").getAsString());
        if (json.has("glow") && json.get("glow").getAsBoolean()) spec.putBoolean("glow", true);
        extraSpec(json, spec);
        ServerLevel level = p.level();
        double a = p.getRandom().nextDouble() * Math.PI * 2;
        BlockPos at = QuestSpawner.surface(level, p.getBlockX() + (int) (Math.cos(a) * 7), p.getBlockZ() + (int) (Math.sin(a) * 7));
        QuestSpawner.spawnLoose(level, key, q, spec, at);
    }

    // ------------------------------------------------------------------ drops and rewards

    /** A quest boss with a drop spec leaves it on death (for the killer, or the nearest player). */
    static void onDrops(LivingDropsEvent event) {
        String drop = event.getEntity().getPersistentData().getStringOr(DROP, "");
        if (drop.isEmpty() || !(event.getEntity().level() instanceof ServerLevel level)) return;
        ServerPlayer p = Quests.asPlayer(level.getServer(), event.getSource().getEntity());
        if (p == null && level.getNearestPlayer(event.getEntity(), 64) instanceof ServerPlayer sp) p = sp;
        for (String part : drop.split(",")) {
            for (ItemStack s : dropStacks(part.trim(), p)) {
                ItemEntity ie = new ItemEntity(level, event.getEntity().getX(), event.getEntity().getY() + 0.5, event.getEntity().getZ(), s);
                ie.setDefaultPickUpDelay();
                event.getDrops().add(ie);
            }
        }
    }

    private static List<ItemStack> dropStacks(String spec, ServerPlayer p) {
        if (spec.startsWith("unique:")) {
            ItemStack s = LegendaryUniques.create(spec.substring(7));
            return s.isEmpty() ? List.of() : List.of(s);
        }
        if (spec.startsWith("loot:")) return p == null || !TIERS.contains(spec.substring(5)) ? List.of() : LootApi.questReward(p, spec.substring(5));
        ItemStack s = Story.stack(spec);
        return s.isEmpty() ? List.of() : List.of(s);
    }

    /** Rolls a loot-module quest reward of a tier for the player. */
    static void giveLoot(ServerPlayer p, String tier) {
        if (!TIERS.contains(tier)) {
            Skycraft.LOGGER.warn("Unknown quest loot tier {}", tier);
            return;
        }
        try {
            for (ItemStack s : LootApi.questReward(p, tier)) {
                if (s.isEmpty()) continue;
                Notifier.message(p, Component.translatable("quest.skycraft.item_found", s.getHoverName()));
                Quests.give(p, s);
            }
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Quest loot tier {} failed", tier, e);
        }
    }

    /** Hands the player a legendary unique. */
    static void giveUnique(ServerPlayer p, String id) {
        ItemStack s = LegendaryUniques.create(id);
        if (s.isEmpty()) {
            Skycraft.LOGGER.warn("Unknown legendary unique {}", id);
            return;
        }
        Notifier.message(p, Component.translatable("story.skycraft.reward.unique", s.getHoverName()).withStyle(ChatFormatting.GOLD));
        Quests.give(p, s);
    }

    /** Whether a boss drop spec names only things that exist (for the selftest). */
    static boolean validDrop(String drop) {
        for (String part : drop.split(",")) {
            String s = part.trim();
            if (s.startsWith("unique:")) {
                if (LegendaryUniques.get(s.substring(7)) == null) return false;
            } else if (s.startsWith("loot:")) {
                if (!TIERS.contains(s.substring(5))) return false;
            } else {
                if (Story.item(s.contains("*") ? s.substring(0, s.indexOf('*')) : s) == null) return false;
            }
        }
        return true;
    }
}
