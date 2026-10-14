package com.skycraft.creatures;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.core.Holds;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which guard stands where: the one table that maps a settlement (its place id, its region, its people's culture, its
 * province) to a guard profile (skin, name, weapon and shield, the set of things it says). The data is
 * {@code skycraft/creatures/guard_profiles.json}; {@code tools/textures/creatures.py} paints the skins from the same file
 * and {@code tools/qa/data_checks.py} checks that every profile has its skin, its name and its items.
 *
 * <p>A settlement is resolved in this order, the first row that matches wins: its place id ({@code settlements}), its
 * region id ({@code regions}), its place's culture ({@code cultures}), its province ({@code provinces}), the default.
 * Skyrim's holds each have their own livery (and the civil war's side is worn over it, see {@code CivilWar}); the
 * other provinces have their own watch (Imperial City Watch, Redoran, Hlaalu, Telvanni, Temple Ordinators, Breton,
 * Orsinium, Crown and Forebear Redguard, Dune and Lunar Khajiit, Aldmeri, Bosmer, Argonian). A province with no art of
 * its own wears the closest culture's.</p>
 *
 * <p>Common code: the client reads the same table (the profile's index is synched, its position in the file never
 * changes: append new profiles at the end).</p>
 */
public final class GuardProfiles {
    private static final String FILE = "/skycraft/creatures/guard_profiles.json";

    /** One guard look: {@code id} is also the skin ({@code guard_<id>.png}) and the lang key suffix. */
    public record Profile(String id, int index, String weapon, String shield, boolean skyrimLines) {
        public ResourceLocation texture() {
            return new ResourceLocation(Skycraft.MODID, "textures/entity/guard/guard_" + id + ".png");
        }

        public String nameKey() {
            return "entity.skycraft.guard." + id;
        }

        /** The sword this guard carries (the iron sword when the item is not in the game). */
        public Item weaponItem() {
            return item(weapon, Items.IRON_SWORD);
        }

        public Item shieldItem() {
            return item(shield, Items.SHIELD);
        }
    }

    /** What a lookup found and which rule of the table decided it ({@code "settlement caldera"}, {@code "region ..."}). */
    public record Match(Profile profile, String rule) {}

    private static final List<Profile> ALL;
    private static final Map<String, Profile> BY_ID = new LinkedHashMap<>();
    private static final Map<String, String> SETTLEMENTS = new LinkedHashMap<>();
    private static final Map<String, String> REGIONS = new LinkedHashMap<>();
    private static final Map<String, String> CULTURES = new LinkedHashMap<>();
    private static final Map<String, String> PROVINCES = new LinkedHashMap<>();
    private static final Profile DEFAULT;

    static {
        List<Profile> list = new ArrayList<>();
        String defaultId = "whiterun";
        try (InputStream in = GuardProfiles.class.getResourceAsStream(FILE)) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("default")) defaultId = root.get("default").getAsString();
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("profiles").entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    Profile p = new Profile(e.getKey(), list.size(),
                            o.has("weapon") ? o.get("weapon").getAsString() : "minecraft:iron_sword",
                            o.has("shield") ? o.get("shield").getAsString() : "minecraft:shield",
                            !o.has("lines") || "skyrim".equals(o.get("lines").getAsString()));
                    list.add(p);
                    BY_ID.put(p.id(), p);
                }
                fill(root, "settlements", SETTLEMENTS);
                fill(root, "regions", REGIONS);
                fill(root, "cultures", CULTURES);
                fill(root, "provinces", PROVINCES);
            } else {
                Skycraft.LOGGER.error("Guard profile table {} is missing", FILE);
            }
        } catch (Exception ex) {
            Skycraft.LOGGER.error("Guard profile table {} could not be read", FILE, ex);
        }
        if (list.isEmpty()) {
            Profile p = new Profile("whiterun", 0, "minecraft:iron_sword", "minecraft:shield", true);
            list.add(p);
            BY_ID.put(p.id(), p);
        }
        ALL = Collections.unmodifiableList(list);
        DEFAULT = BY_ID.getOrDefault(defaultId, list.get(0));
    }

    private GuardProfiles() {}

    private static void fill(JsonObject root, String key, Map<String, String> into) {
        if (!root.has(key)) return;
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject(key).entrySet()) {
            if (!BY_ID.containsKey(e.getValue().getAsString())) {
                Skycraft.LOGGER.warn("Guard profile table: {} {} names an unknown profile {}", key, e.getKey(), e.getValue().getAsString());
                continue;
            }
            into.put(e.getKey(), e.getValue().getAsString());
        }
    }

    private static Item item(String id, Item fallback) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return fallback;
        Item it = ForgeRegistries.ITEMS.getValue(rl);
        return it == null || it == Items.AIR ? fallback : it;
    }

    // ------------------------------------------------------------------ lookups

    public static List<Profile> all() {
        return ALL;
    }

    public static int count() {
        return ALL.size();
    }

    public static Profile byIndex(int index) {
        return index >= 0 && index < ALL.size() ? ALL.get(index) : DEFAULT;
    }

    /** The profile of that id, or null. */
    @Nullable
    public static Profile byId(String id) {
        return BY_ID.get(id);
    }

    public static Profile fallback() {
        return DEFAULT;
    }

    /** The table's own rows, for the tests and the doc: place id, region id, culture, province to profile id. */
    public static Map<String, String> settlementRows() {
        return Collections.unmodifiableMap(SETTLEMENTS);
    }

    public static Map<String, String> regionRows() {
        return Collections.unmodifiableMap(REGIONS);
    }

    public static Map<String, String> cultureRows() {
        return Collections.unmodifiableMap(CULTURES);
    }

    public static Map<String, String> provinceRows() {
        return Collections.unmodifiableMap(PROVINCES);
    }

    /**
     * The guard of a settlement: any argument may be null or empty. {@code province} is taken from the region id's
     * prefix ({@code "morrowind/west_gash"}) when it is not given.
     */
    public static Match resolve(@Nullable String placeId, @Nullable String regionId, @Nullable String culture, @Nullable String province) {
        if (placeId != null && !placeId.isEmpty()) {
            String id = SETTLEMENTS.get(placeId);
            if (id != null) return new Match(BY_ID.get(id), "settlement " + placeId);
        }
        String region = regionId == null ? "" : Holds.migrate(regionId);
        if (!region.isEmpty()) {
            String id = REGIONS.get(region);
            if (id != null) return new Match(BY_ID.get(id), "region " + region);
        }
        if (culture != null && !culture.isEmpty()) {
            String id = CULTURES.get(culture.toLowerCase(java.util.Locale.ROOT));
            if (id != null) return new Match(BY_ID.get(id), "culture " + culture);
        }
        String prov = province;
        if ((prov == null || prov.isEmpty()) && region.indexOf('/') > 0) prov = region.substring(0, region.indexOf('/'));
        if (prov != null && !prov.isEmpty()) {
            String id = PROVINCES.get(prov);
            if (id != null) return new Match(BY_ID.get(id), "province " + prov);
        }
        return new Match(DEFAULT, "default");
    }

    /** The profile of a place on the map (its culture and region as the places file gives them). */
    public static Match of(Place place) {
        return resolve(place.id(), place.regionId(), place.culture(), null);
    }

    /** The profile of the guards a settlement entry keeps. */
    public static Match of(SettlementAnchors.Entry entry) {
        Place p = entry.place().orElse(null);
        if (p != null) return of(p);
        return resolve(entry.key, null, null, null);
    }

    /** The guard for a position in the world: the settlement it is in (if any), else the region it is in. */
    public static Profile at(Level level, BlockPos pos) {
        String region = Holds.holdAt(level, pos);
        String place = null, culture = null;
        if (level instanceof ServerLevel sl) {
            var near = SettlementAnchors.near(sl, pos, 24);
            if (near.isPresent() && !near.get().homestead()) {
                place = near.get().key;
                culture = near.get().place().map(Place::culture).orElse(null);
            }
        }
        return resolve(place, region, culture, null).profile();
    }

    /** The profile of a guard saved before profiles existed: the livery of Skyrim's hold at that index. */
    public static Profile ofHoldIndex(int hold) {
        if (hold >= 0 && hold < Holds.NAMES.length) {
            Profile p = BY_ID.get(Holds.NAMES[hold]);
            if (p != null) return p;
        }
        return DEFAULT;
    }
}
