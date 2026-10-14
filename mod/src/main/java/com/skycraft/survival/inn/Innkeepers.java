package com.skycraft.survival.inn;

import com.skycraft.Skycraft;
import com.skycraft.core.Buffs;
import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.economy.Shop;
import com.skycraft.roads.RoadsData;
import com.skycraft.roads.Settlement;
import com.skycraft.survival.OreVeins;
import com.skycraft.survival.SurvivalConfig;
import com.skycraft.survival.SurvivalEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Inns. Every settlement of the roads module's registry gets an innkeeper (a vanilla villager tagged
 * {@code skycraft_innkeeper}) near its meeting point the first time a player comes by. Innkeepers rent rooms
 * (stored per player in {@code data.module("survival").rent}), sell food and drink through the economy's barter
 * (their persistent {@code skycraft_shop} has the {@code food} pool, topped up from {@code #skycraft:inn_goods}),
 * and share rumors. Sleeping while a room is rented in that settlement adds the inn's rested bonus.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Innkeepers {
    /** Entity tag and persistent-data flag marking innkeepers (other modules may set it on their own NPCs). */
    public static final String TAG = "skycraft_innkeeper";
    public static final String SETTLEMENT_KEY = "skycraft_inn_settlement";
    public static final String INN_KEY = "skycraft_inn";
    public static final TagKey<Item> INN_GOODS = TagKey.create(Registries.ITEM, new ResourceLocation(Skycraft.MODID, "inn_goods"));
    /** Optional: a lang key suffix ({@code settlement.skycraft.name.<key>}) naming the inn, set by city plans. */
    public static final String INN_NAME_KEY = "skycraft_inn_name";
    public static final int INN_NAMES = 16;
    public static final int RUMORS = 14;
    /** Rented rooms count anywhere within this distance of the innkeeper when no settlement is known. */
    private static final int ROOM_RADIUS = 48;
    private static final String[] NAMES = {"Hulda", "Elda", "Delphine", "Keerava", "Corpulus", "Iddra", "Orgnar", "Mralki",
            "Haran", "Kleppr", "Ambarys", "Wilhelm", "Faida", "Hadring", "Talen-Jei", "Gilfre", "Belethor", "Ysolda"};

    private Innkeepers() {}

    public static boolean isInnkeeper(LivingEntity npc) {
        CompoundTag pd = npc.getPersistentData();
        return pd.getBoolean(TAG) || npc.getTags().contains(TAG) || "innkeeper".equals(pd.getString("skycraft_role"));
    }

    /** The translated name of the inn an innkeeper runs. */
    public static Component innName(LivingEntity npc) {
        CompoundTag pd = npc.getPersistentData();
        if (pd.contains(INN_NAME_KEY)) return Component.translatable("settlement.skycraft.name." + pd.getString(INN_NAME_KEY));
        int idx = pd.contains(INN_KEY) ? pd.getInt(INN_KEY) : Math.floorMod(npc.getUUID().hashCode(), INN_NAMES);
        return Component.translatable("survival.skycraft.inn." + Math.floorMod(idx, INN_NAMES));
    }

    // ------------------------------------------------------------------ spawning

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % 100 != 37 || !SurvivalConfig.INNKEEPERS.get()) return;
        ServerLevel overworld = server.overworld();
        if (overworld.players().isEmpty()) return;
        RoadsData roads = RoadsData.get(overworld);
        if (roads.settlements().isEmpty()) return;
        for (ServerPlayer player : overworld.players()) {
            if (player.isSpectator()) continue;
            Settlement s = roads.zoneAt(player.getBlockX(), player.getBlockZ());
            if (s != null) ensureInnkeeper(overworld, s);
        }
    }

    private static void ensureInnkeeper(ServerLevel level, Settlement s) {
        SurvivalData data = SurvivalData.get(level);
        SurvivalData.Inn inn = data.inn(s.id);
        long now = level.getGameTime();
        // Helgen is a ruin after the dragon (the opening): its inn is dead, nobody keeps it
        if (com.skycraft.quest.QuestConfig.RUINED_HELGEN.get() && com.skycraft.quest.story.StartCottage.nearHelgen(s.x, s.z, 40)) return;
        if (now - inn.lastCheck < 600) return;
        inn.lastCheck = now;
        BlockPos center = new BlockPos(s.x, Math.max(level.getMinBuildHeight(), s.y), s.z);
        if (!level.isPositionEntityTicking(center)) return;
        List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class, new AABB(center).inflate(96, 64, 96),
                e -> e.isAlive() && isInnkeeper(e));
        if (!found.isEmpty()) {
            inn.lastSeen = now;
            data.setDirty();
            return;
        }
        if (inn.spawned && now - inn.lastSeen < SurvivalConfig.INNKEEPER_RESPAWN_TICKS.get()) return;
        if (spawn(level, s, center)) {
            inn.spawned = true;
            inn.lastSeen = now;
            data.setDirty();
        }
    }

    private static boolean spawn(ServerLevel level, Settlement s, BlockPos center) {
        Optional<BlockPos> bell = level.getPoiManager().findClosest(h -> h.is(PoiTypes.MEETING), center, 64, PoiManager.Occupancy.ANY);
        BlockPos anchor = bell.orElse(center);
        BlockPos spot = findSpot(level, anchor, level.getRandom());
        if (spot == null) return false;
        // a Skycraft townsperson of the region's people (society module), who keeps the inn's hours
        com.skycraft.society.entity.NpcEntity v = com.skycraft.society.Npcs.create(level, spot, com.skycraft.society.NpcRole.INNKEEPER);
        if (v == null) return false;
        String name = v.hasCustomName() ? v.getCustomName().getString()
                : NAMES[Math.floorMod(s.id * 7 + (int) (s.key ^ (s.key >>> 32)), NAMES.length)];
        if (!v.hasCustomName()) v.setCustomName(Component.translatable("entity.skycraft.innkeeper", name));
        v.setHome(anchor, 24);
        v.setPersistenceRequired();
        v.addTag(TAG);
        CompoundTag pd = v.getPersistentData();
        pd.putBoolean(TAG, true);
        pd.putInt(SETTLEMENT_KEY, s.id);
        pd.putInt(INN_KEY, Math.floorMod(s.id * 5 + 3, INN_NAMES));
        CompoundTag shop = new CompoundTag();
        ListTag pools = new ListTag();
        pools.add(StringTag.valueOf("food"));
        shop.put("pools", pools);
        shop.putInt("level", 3);
        pd.put(Shop.KEY, shop);
        if (!level.addFreshEntity(v)) return false;
        Skycraft.LOGGER.debug("Spawned innkeeper {} in {} at {}", name, s.name, spot);
        return true;
    }

    private static BlockPos findSpot(ServerLevel level, BlockPos anchor, RandomSource random) {
        for (int attempt = 0; attempt < 32; attempt++) {
            int r = 2 + attempt / 4;
            int x = anchor.getX() + random.nextInt(2 * r + 1) - r;
            int z = anchor.getZ() + random.nextInt(2 * r + 1) - r;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            BlockState ground = level.getBlockState(pos.below());
            if (!ground.isFaceSturdy(level, pos.below(), Direction.UP) || !ground.getFluidState().isEmpty()) continue;
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) continue;
            if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) continue;
            if (!level.getFluidState(pos).isEmpty()) continue;
            return pos;
        }
        return null;
    }

    // ------------------------------------------------------------------ shop

    /**
     * The inn's own goods (mead, ale, stews, sweetrolls...) are part of the inn's shared stock: the economy's
     * {@code ShopStock} adds the province's {@link #INN_GOODS} to the food of an inn (a building with an INN anchor,
     * or an innkeeper), once per restock period, the same for the innkeeper and the cook. This only makes sure the
     * shop is loaded and restocked; it used to roll a second, per-NPC list.
     */
    public static void topUpStock(LivingEntity npc) {
        Shop.of(npc);
    }

    // ------------------------------------------------------------------ rooms

    private static String rentKey(LivingEntity npc) {
        CompoundTag pd = npc.getPersistentData();
        return pd.contains(SETTLEMENT_KEY) ? "s" + pd.getInt(SETTLEMENT_KEY) : "n" + npc.getStringUUID();
    }

    private static int settlementOf(LivingEntity npc) {
        CompoundTag pd = npc.getPersistentData();
        if (pd.contains(SETTLEMENT_KEY)) return pd.getInt(SETTLEMENT_KEY);
        if (npc.level() instanceof ServerLevel sl && sl.dimension() == Level.OVERWORLD) {
            Settlement s = RoadsData.get(sl).zoneAt(npc.getBlockX(), npc.getBlockZ());
            if (s != null) return s.id;
        }
        return -1;
    }

    /** Dialogue action: "I'd like a room for the night." */
    public static void rent(ServerPlayer player, LivingEntity npc) {
        if (!npc.isAlive() || player.distanceToSqr(npc) > 64) return;
        PlayerData data = SkyData.get(player);
        CompoundTag rent = data.module("survival").getCompound("rent");
        String key = rentKey(npc);
        long now = OreVeins.clock(player.level());
        if (rent.contains(key) && rent.getCompound(key).getLong("until") > now) {
            Dialogue.open(player, npc, Component.translatable("survival.skycraft.inn.already_rented"));
            return;
        }
        int price = SurvivalConfig.ROOM_PRICE.get();
        if (!Currency.take(player, price)) {
            Dialogue.open(player, npc, Component.translatable("survival.skycraft.inn.no_gold"));
            return;
        }
        CompoundTag room = new CompoundTag();
        room.putLong("until", now + 24000L);
        room.putInt("sid", settlementOf(npc));
        room.putInt("x", npc.getBlockX());
        room.putInt("z", npc.getBlockZ());
        room.putString("dim", npc.level().dimension().location().toString());
        room.putInt("inn", npc.getPersistentData().contains(INN_KEY) ? npc.getPersistentData().getInt(INN_KEY)
                : Math.floorMod(npc.getUUID().hashCode(), INN_NAMES));
        rent.put(key, room);
        data.module("survival").put("rent", rent);
        data.markDirty();
        data.addStat("rooms_rented", 1);
        Notifier.message(player, Component.translatable("message.skycraft.survival.room_rented", innName(npc)));
        Dialogue.open(player, npc, Component.translatable("survival.skycraft.inn.rented"));
    }

    /** The rented room the player is standing in (its tag), or null. Also drops long-expired rentals. */
    public static CompoundTag roomHere(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        CompoundTag rent = data.module("survival").getCompound("rent");
        if (rent.isEmpty()) return null;
        long now = OreVeins.clock(player.level());
        String dim = player.level().dimension().location().toString();
        Settlement here = null;
        boolean looked = false;
        CompoundTag match = null;
        List<String> expired = new ArrayList<>();
        for (String key : rent.getAllKeys()) {
            CompoundTag room = rent.getCompound(key);
            // a little grace: sleeping a full day straight after renting still counts
            if (room.getLong("until") + 1000L < now) {
                expired.add(key);
                continue;
            }
            if (match != null || !dim.equals(room.getString("dim"))) continue;
            int sid = room.getInt("sid");
            if (sid >= 0 && player.level() instanceof ServerLevel sl && sl.dimension() == Level.OVERWORLD) {
                if (!looked) {
                    here = RoadsData.get(sl).zoneAt(player.getBlockX(), player.getBlockZ());
                    looked = true;
                }
                if (here != null && here.id == sid) match = room;
            }
            if (match == null) {
                long dx = player.getBlockX() - room.getInt("x");
                long dz = player.getBlockZ() - room.getInt("z");
                if (dx * dx + dz * dz <= (long) ROOM_RADIUS * ROOM_RADIUS) match = room;
            }
        }
        if (!expired.isEmpty()) {
            for (String k : expired) rent.remove(k);
            data.module("survival").put("rent", rent);
            data.markDirty();
        }
        return match;
    }

    /** Called when the world module's sleep grants Well Rested: a rented room adds the inn's bonus. */
    public static void onSlept(ServerPlayer player, int duration) {
        CompoundTag room = roomHere(player);
        if (room == null) return;
        Buffs.apply(player, SurvivalEvents.INN_RESTED, Math.max(duration, 20 * 60 * 10));
        Notifier.message(player, Component.translatable("message.skycraft.survival.inn_rested",
                Component.translatable("survival.skycraft.inn." + Math.floorMod(room.getInt("inn"), INN_NAMES))));
    }

    /** Dialogue action: "Heard any rumors?" */
    public static void rumor(ServerPlayer player, LivingEntity npc) {
        Component loc = com.skycraft.world.Discovery.revealNear(player, player.blockPosition(), 1500);
        if (loc != null) {
            Dialogue.open(player, npc, Component.translatable("society.skycraft.rumor.revealed", loc));
        } else {
            // half the time the region's own rumours (lore module), if it has any
            Component local = player.getRandom().nextBoolean() ? com.skycraft.lore.place.PlaceLore.regionalRumour(
                    com.skycraft.tamriel.Regions.at(npc.level(), npc.blockPosition()).id(), player.getRandom()) : null;
            Dialogue.open(player, npc, local != null ? local : Component.translatable("survival.skycraft.rumor." + player.getRandom().nextInt(RUMORS)));
        }
    }
}
