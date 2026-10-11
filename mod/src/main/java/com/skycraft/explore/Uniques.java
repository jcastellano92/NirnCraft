package com.skycraft.explore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.registries.ForgeRegistries;
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
 * adventurer by a mountain lake, the ring in a hermit's chest). Read from {@code skycraft/explore/uniques.json}:
 * {@code {"id","kind","x","z","item","enchant":{id:level},"note"}}. The site of the cell holding (x, z) is forced to
 * {@code kind} and its chest holds the item (and the note, a lore book id, telling its story). Names and lore lines
 * are lang keys {@code explore.skycraft.unique.<id>} and {@code explore.skycraft.unique.<id>.lore}.
 */
public final class Uniques {
    public static final String TAG = "skycraft_unique";
    private static final String FILE = "/skycraft/explore/uniques.json";

    public record UniqueSite(String id, SiteKind kind, int x, int z, String item, Map<String, Integer> enchant,
                             @Nullable String note) {
    }

    private static final Map<String, UniqueSite> BY_ID;
    private static final Map<Long, UniqueSite> BY_CELL;

    static {
        Map<String, UniqueSite> byId = new LinkedHashMap<>();
        Map<Long, UniqueSite> byCell = new HashMap<>();
        try (InputStream in = Uniques.class.getResourceAsStream(FILE)) {
            if (in != null) {
                for (JsonElement e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray()) {
                    JsonObject o = e.getAsJsonObject();
                    Map<String, Integer> ench = new LinkedHashMap<>();
                    if (o.has("enchant")) {
                        for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("enchant").entrySet()) {
                            ench.put(en.getKey(), en.getValue().getAsInt());
                        }
                    }
                    UniqueSite u = new UniqueSite(o.get("id").getAsString(), SiteKind.byId(o.get("kind").getAsString()),
                            o.get("x").getAsInt(), o.get("z").getAsInt(), o.get("item").getAsString(), Collections.unmodifiableMap(ench),
                            o.has("note") ? o.get("note").getAsString() : null);
                    long cell = net.minecraft.world.level.ChunkPos.asLong(Math.floorDiv(u.x(), MinorSites.CELL), Math.floorDiv(u.z(), MinorSites.CELL));
                    if (byCell.putIfAbsent(cell, u) != null) {
                        Skycraft.LOGGER.warn("Unique {} shares a minor-site cell with another unique; skipped", u.id());
                        continue;
                    }
                    byId.put(u.id(), u);
                }
            }
        } catch (Exception ex) {
            Skycraft.LOGGER.warn("Could not read {}", FILE, ex);
        }
        BY_ID = Collections.unmodifiableMap(byId);
        BY_CELL = byCell;
    }

    private Uniques() {}

    @Nullable
    public static UniqueSite forCell(int cellX, int cellZ) {
        return BY_CELL.get(net.minecraft.world.level.ChunkPos.asLong(cellX, cellZ));
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
        UniqueSite u = BY_ID.get(id);
        if (u == null) return ItemStack.EMPTY;
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(u.item()));
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            Skycraft.LOGGER.warn("Unique {}: unknown base item {}", id, u.item());
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        stack.setHoverName(Component.translatable("explore.skycraft.unique." + id)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)));
        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.translatable("explore.skycraft.unique." + id + ".lore")
                .withStyle(s -> s.withColor(ChatFormatting.DARK_AQUA).withItalic(true)))));
        lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.translatable("explore.skycraft.unique.tag")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)))));
        display.put("Lore", lore);
        for (Map.Entry<String, Integer> e : u.enchant().entrySet()) {
            Enchantment ench = ForgeRegistries.ENCHANTMENTS.getValue(ResourceLocation.tryParse(e.getKey()));
            if (ench != null) stack.enchant(ench, e.getValue());
        }
        stack.getOrCreateTag().putString(TAG, id);
        return stack;
    }
}
