package com.nirncraft.explore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unique named items hidden once per world at fixed minor sites (Skyrim's "Gauldur"-less uniques: the axe on a dead
 * adventurer by a mountain lake, the ring in a hermit's chest). Read from {@code nirncraft/explore/uniques.json}:
 * {@code {"id","kind","x","z","item","enchant":{id:level},"note"}}. The site of the cell holding (x, z) is forced to
 * {@code kind} and its chest holds the item (and the note, a lore book id, telling its story). Names and lore lines
 * are lang keys {@code explore.nirncraft.unique.<id>} and {@code explore.nirncraft.unique.<id>.lore}.
 */
public final class Uniques {
    public static final String TAG = "nirncraft_unique";
    private static final String FILE = "/nirncraft/explore/uniques.json";
    private static final String INDEX = "/nirncraft/explore/uniques_index.json";

    public record UniqueSite(String id, SiteKind kind, int x, int z, String item, Map<String, Integer> enchant,
                             @Nullable String note) {
    }

    private static final Map<String, UniqueSite> BY_ID;
    private static final Map<Long, UniqueSite> BY_CELL;

    static {
        Map<String, UniqueSite> byId = new LinkedHashMap<>();
        Map<Long, UniqueSite> byCell = new HashMap<>();
        // uniques.json plus every file named in uniques_index.json (a JSON array of file names, e.g. "uniques_cyrodiil.json")
        List<String> files = new ArrayList<>();
        files.add(FILE);
        try (InputStream in = Uniques.class.getResourceAsStream(INDEX)) {
            if (in != null) {
                for (JsonElement e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray()) {
                    files.add("/nirncraft/explore/" + e.getAsString());
                }
            }
        } catch (Exception ex) {
            NirnCraft.LOGGER.warn("Could not read {}", INDEX, ex);
        }
        for (String file : files) {
        try (InputStream in = Uniques.class.getResourceAsStream(file)) {
            if (in != null) {
                JsonElement rootEl = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                com.google.gson.JsonArray arr = rootEl.isJsonArray() ? rootEl.getAsJsonArray()
                        : (rootEl.isJsonObject() && rootEl.getAsJsonObject().has("uniques")
                        ? rootEl.getAsJsonObject().getAsJsonArray("uniques") : new com.google.gson.JsonArray());
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    Map<String, Integer> ench = new LinkedHashMap<>();
                    if (o.has("enchant") && o.get("enchant").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("enchant").entrySet()) {
                            if (en.getValue().isJsonPrimitive() && en.getValue().getAsJsonPrimitive().isNumber()) {
                                ench.put(en.getKey(), en.getValue().getAsInt());
                            }
                        }
                    }
                    String uid = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsString() : "";
                    if (uid.isEmpty()) continue;
                    String kindStr = o.has("kind") && !o.get("kind").isJsonNull() ? o.get("kind").getAsString() : "treasure_cache";
                    int ux = o.has("x") && !o.get("x").isJsonNull() ? o.get("x").getAsInt() : 0;
                    int uz = o.has("z") && !o.get("z").isJsonNull() ? o.get("z").getAsInt() : 0;
                    String uitem = o.has("item") && !o.get("item").isJsonNull() ? o.get("item").getAsString() : "nirncraft:iron_sword";
                    UniqueSite u = new UniqueSite(uid, SiteKind.byId(kindStr),
                            ux, uz, uitem, Collections.unmodifiableMap(ench),
                            o.has("note") && !o.get("note").isJsonNull() ? o.get("note").getAsString() : null);
                    long cell = net.minecraft.world.level.ChunkPos.pack(Math.floorDiv(u.x(), MinorSites.CELL), Math.floorDiv(u.z(), MinorSites.CELL));
                    if (byCell.putIfAbsent(cell, u) != null) {
                        NirnCraft.LOGGER.debug("Unique {} shares a minor-site cell with another unique; skipped", u.id());
                        continue;
                    }
                    byId.put(u.id(), u);
                }
            }
        } catch (Exception ex) {
            NirnCraft.LOGGER.warn("Could not read {}", file, ex);
        }
        }
        BY_ID = Collections.unmodifiableMap(byId);
        BY_CELL = byCell;
    }

    private Uniques() {}

    @Nullable
    public static UniqueSite forCell(int cellX, int cellZ) {
        return BY_CELL.get(net.minecraft.world.level.ChunkPos.pack(cellX, cellZ));
    }

    @Nullable
    public static UniqueSite get(String id) {
        return BY_ID.get(id);
    }

    public static List<UniqueSite> all() {
        return new ArrayList<>(BY_ID.values());
    }

    /** The named item, or empty if its base item is missing. */
    public static ItemStack stack(String id) {
        return stack(id, com.nirncraft.inventory.ItemData.currentRegistries());
    }

    /** World generation runs on worker threads: callers there pass the level's registries (WorldGenLevel.registryAccess()). */
    public static ItemStack stack(String id, net.minecraft.core.HolderLookup.Provider registries) {
        UniqueSite u = BY_ID.get(id);
        if (u == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(u.item()));
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            NirnCraft.LOGGER.warn("Unique {}: unknown base item {}", id, u.item());
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.translatable("explore.nirncraft.unique." + id)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)));
        stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
                java.util.List.of(Component.translatable("explore.nirncraft.unique." + id + ".lore")
                                .withStyle(s -> s.withColor(ChatFormatting.DARK_AQUA).withItalic(true)),
                        Component.translatable("explore.nirncraft.unique.tag")
                                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)))));
        for (Map.Entry<String, Integer> e : u.enchant().entrySet()) {
            Enchantment ench = com.nirncraft.platform.EnchantmentsAccess.lookup(Identifier.tryParse(e.getKey()), registries);
            if (ench != null) stack.enchant(com.nirncraft.platform.EnchantmentsAccess.holder(ench, registries), e.getValue());
        }
        com.nirncraft.inventory.ItemData.updateCustom(stack, customData263 -> customData263.putString(TAG, id));
        return stack;
    }
}
