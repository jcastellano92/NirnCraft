package com.skycraft.quest.story;

import com.skycraft.core.Holds;
import com.skycraft.quest.QuestDialogue;
import com.skycraft.quest.QuestSpawner;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who holds each hold of Skyrim in the civil war: a per-world control flag per region ({@code imperial},
 * {@code stormcloak} or neutral). It starts as in the lore (the western holds Imperial, the eastern ones Stormcloak,
 * Whiterun neutral) and changes when a fort or capital falls in a civil-war quest. Guards wear their side's livery:
 * a red (Legion) or blue (Stormcloak) cuirass over the hold colors.
 */
public final class CivilWar {
    public static final String IMPERIAL = "imperial";
    public static final String STORMCLOAK = "stormcloak";
    public static final int IMPERIAL_RED = 0x8B1E1E;
    public static final int STORMCLOAK_BLUE = 0x2F4F8F;
    private static final String LIVERY = "skycraft_livery";

    /** The holds' fort places, used to pick the next battle (the fallback place table knows them all). */
    private static final Map<String, String> FORTS = new LinkedHashMap<>();

    static {
        FORTS.put("skyrim/the_reach", "fort_sungard");
        FORTS.put("skyrim/falkreath", "fort_neugrad");
        FORTS.put("skyrim/hjaalmarch", "fort_snowhawk");
        FORTS.put("skyrim/haafingar", "fort_hraggstad");
        FORTS.put("skyrim/the_pale", "fort_dunstad");
        FORTS.put("skyrim/winterhold", "fort_kastav");
        FORTS.put("skyrim/eastmarch", "fort_amol");
        FORTS.put("skyrim/the_rift", "fort_greenwall");
        FORTS.put("skyrim/whiterun", "fort_greymoor");
    }

    private CivilWar() {}

    // ------------------------------------------------------------------ state

    public static final class Store extends SavedData {
        private static final String NAME = "skycraft_civil_war";
        final Map<String, String> control = new LinkedHashMap<>();

        static Store get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(Store::load, Store::create, NAME);
        }

        static Store create() {
            Store s = new Store();
            for (String h : new String[]{"haafingar", "hjaalmarch", "the_reach", "falkreath"}) s.control.put("skyrim/" + h, IMPERIAL);
            for (String h : new String[]{"eastmarch", "winterhold", "the_pale", "the_rift"}) s.control.put("skyrim/" + h, STORMCLOAK);
            return s;
        }

        static Store load(CompoundTag tag) {
            Store s = create();
            for (String k : tag.getAllKeys()) s.control.put(k, tag.getString(k));
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            control.forEach(tag::putString);
            return tag;
        }
    }

    /** The side holding a region: {@link #IMPERIAL}, {@link #STORMCLOAK} or empty (neutral / not in Skyrim). */
    public static String controller(MinecraftServer server, String regionId) {
        return Store.get(server).control.getOrDefault(Holds.migrate(regionId), "");
    }

    public static void setController(MinecraftServer server, String regionId, String side) {
        Store s = Store.get(server);
        String id = Holds.migrate(regionId);
        if (side.equals(s.control.get(id))) return;
        s.control.put(id, side);
        s.setDirty();
        Component msg = Component.translatable("quest.skycraft.civil_war.taken",
                Component.translatable("quest.skycraft.civil_war.side." + side), Holds.displayName(id)).withStyle(ChatFormatting.GOLD);
        for (net.minecraft.server.level.ServerPlayer sp : server.getPlayerList().getPlayers()) {
            com.skycraft.core.Notifier.message(sp, msg);
        }
    }

    /** The region of the enemy's next fort for {@code side}, or null when the enemy holds nothing. */
    @Nullable
    public static String enemyFort(MinecraftServer server, String side, RandomSource r) {
        String enemy = IMPERIAL.equals(side) ? STORMCLOAK : IMPERIAL;
        List<String> pool = new ArrayList<>();
        Store s = Store.get(server);
        for (Map.Entry<String, String> e : FORTS.entrySet()) {
            if (enemy.equals(s.control.get(e.getKey()))) pool.add(e.getValue());
        }
        if (pool.isEmpty()) return null;
        return pool.get(r.nextInt(pool.size()));
    }

    /** Region id of a fort place id. */
    public static String fortRegion(String fortPlace) {
        for (Map.Entry<String, String> e : FORTS.entrySet()) if (e.getValue().equals(fortPlace)) return e.getKey();
        Place p = Places.byId(fortPlace).orElse(null);
        return p != null ? p.regionId() : "";
    }

    /** All forts (for validation and commands). */
    public static Iterable<String> forts() {
        return FORTS.values();
    }

    static boolean isFort(Place p) {
        return p.type() == PlaceType.FORT;
    }

    // ------------------------------------------------------------------ livery

    /** Every few seconds per player: guards around them wear the livery of whoever holds their hold. */
    public static void dressGuards(ServerPlayer player) {
        MinecraftServer server = player.server;
        for (Mob guard : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(40),
                m -> m.isAlive() && m.getType().is(QuestDialogue.GUARDS))) {
            String side = controller(server, Holds.holdAt(guard.level(), guard.blockPosition()));
            ItemStack chest = guard.getItemBySlot(EquipmentSlot.CHEST);
            boolean ours = chest.isEmpty() || chest.getTag() != null && chest.getTag().contains(LIVERY);
            if (!ours) continue; // a guard given real armor keeps it
            String worn = chest.isEmpty() ? "" : chest.getTag().getString(LIVERY);
            if (worn.equals(side)) continue;
            if (side.isEmpty()) {
                guard.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                continue;
            }
            ItemStack livery = QuestSpawner.dyed(Items.LEATHER_CHESTPLATE, IMPERIAL.equals(side) ? IMPERIAL_RED : STORMCLOAK_BLUE);
            livery.getOrCreateTag().putString(LIVERY, side);
            guard.setItemSlot(EquipmentSlot.CHEST, livery);
            guard.setDropChance(EquipmentSlot.CHEST, 0f);
        }
    }
}
