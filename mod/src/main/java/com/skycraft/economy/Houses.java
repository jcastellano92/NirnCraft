package com.skycraft.economy;

import com.skycraft.Skycraft;
import com.skycraft.combat.RespawnPoints;
import com.skycraft.core.Currency;
import com.skycraft.core.Holds;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.crafting.arcane.ArcaneRegistry;
import com.skycraft.crime.Ownership;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import com.skycraft.tamriel.settlement.SettlementAnchors.HouseSite;
import com.skycraft.tamriel.settlement.plan.Cities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Skyrim player homes. Each hold capital's steward sells its house (Breezehome, Proudspire Manor, Hjerim, Honeyside,
 * Vlindrel Hall); Falkreath, Morthal and the Pale sell the homestead land of Lakeview, Windstad and Heljarchen. The
 * houses are built by the settlement generator ({@link Cities#HOUSE_SPOTS}); their bounds, door and bed come from
 * {@link SettlementAnchors}. Buying one gives its key, makes its blocks the player's (contract 1) and sets the
 * player's rest point on its bed (contract 9); its doors open only to the owner and key holders. Homestead land comes
 * with a drafting table to found the homestead on the plot. Furnishing upgrades can be bought from the steward.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Houses {
    /**
     * A home for sale.
     *
     * @param hold      region id of the hold whose steward sells it ({@code skyrim/whiterun})
     * @param placeId   the place it stands in (the capital, or the homestead plot)
     * @param approxPos centre of its footprint (y is a guess; use {@link #contains})
     * @param homestead true for homestead land (a plot, not a built house)
     */
    public record HouseDef(String id, String name, String hold, int cost, String placeId, BlockPos approxPos, int radius,
                           boolean homestead) {
        public Component displayName() {
            return Component.translatableWithFallback("settlement.skycraft.name." + id, name);
        }

        /** Horizontal footprint test (client-safe: uses the planned position). */
        public boolean contains(BlockPos pos) {
            return Math.abs(pos.getX() - approxPos.getX()) <= radius && Math.abs(pos.getZ() - approxPos.getZ()) <= radius;
        }
    }

    public static final List<HouseDef> HOUSES = build();

    public record Furnishing(String id, String name, int cost) {}

    public static final List<Furnishing> UPGRADES = List.of(
            new Furnishing("bedroom", "Bedroom Suite", 500),
            new Furnishing("living_room", "Living Room & Kitchen", 400),
            new Furnishing("alchemy_lab", "Alchemy Lab", 600),
            new Furnishing("arcane_enchanter", "Arcane Enchanter", 800),
            new Furnishing("armory", "Armory & Storage", 500)
    );

    private Houses() {}

    private static List<HouseDef> build() {
        List<HouseDef> out = new ArrayList<>();
        for (Cities.HouseSpot h : Cities.HOUSE_SPOTS) {
            Optional<Place> p = Places.byId(h.place());
            if (p.isEmpty()) continue;
            int[] w = Cities.world(p.get(), h.lx(), h.lz());
            out.add(new HouseDef(h.id(), pretty(h.id()), p.get().regionId(), h.cost(), h.place(), new BlockPos(w[0], 64, w[1]),
                    Math.max(h.w(), h.d()) / 2 + 1, false));
        }
        for (String id : new String[]{"lakeview", "windstad", "heljarchen"}) {
            Places.byId(id).ifPresent(p -> out.add(new HouseDef(id, pretty(id), p.regionId(), 5000, id,
                    new BlockPos(p.x(), 64, p.z()), 12, true)));
        }
        return List.copyOf(out);
    }

    private static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ lookups

    @Nullable
    public static HouseDef byId(String id) {
        for (HouseDef h : HOUSES) if (h.id().equals(id)) return h;
        return null;
    }

    /** The home the steward of a hold sells; accepts a region id ({@code skyrim/whiterun}) or a short hold id. */
    @Nullable
    public static HouseDef houseForHold(String hold) {
        if (hold == null) return null;
        String h = hold.toLowerCase(Locale.ROOT);
        for (HouseDef d : HOUSES) {
            String region = d.hold();
            String shortId = region.substring(region.lastIndexOf('/') + 1);
            if (region.equals(h) || shortId.equals(h) || h.endsWith("/" + shortId)) return d;
        }
        return null;
    }

    /** The home sold by the steward standing at {@code pos}: that of the settlement there, else of the hold. */
    @Nullable
    public static HouseDef houseFor(Level level, BlockPos pos) {
        Optional<Place> here = Places.nearest(null, pos.getX(), pos.getZ(), 200);
        if (here.isPresent() && here.get().type().isSettlement() && here.get().distSqr(pos.getX(), pos.getZ()) <= (long) here.get().radius() * here.get().radius()) {
            for (HouseDef d : HOUSES) if (!d.homestead() && d.placeId().equals(here.get().id())) return d;
            HouseDef byRegion = houseForHold(here.get().regionId());
            if (byRegion != null) return byRegion;
        }
        return houseForHold(Holds.holdAt(level, pos));
    }

    @Nullable
    public static HouseDef houseNear(BlockPos pos) {
        for (HouseDef h : HOUSES) if (!h.homestead() && h.contains(pos)) return h;
        return null;
    }

    public static boolean isHouseOwner(Player player, HouseDef house) {
        if (player.level() instanceof ServerLevel sl) {
            UUID owner = HousesData.get(sl.getServer()).getOwner(house.id());
            return owner != null && owner.equals(player.getUUID());
        }
        return false;
    }

    // ------------------------------------------------------------------ buying

    public static boolean purchaseHouse(ServerPlayer player, ServerLevel level, HouseDef house, @Nullable BlockPos signPos) {
        HousesData data = HousesData.get(level.getServer());
        UUID currentOwner = data.getOwner(house.id());
        if (currentOwner != null) {
            if (currentOwner.equals(player.getUUID())) {
                Notifier.message(player, Component.translatable("dialogue.skycraft.economy.house.already_owned", house.displayName()));
            } else {
                Notifier.message(player, Component.translatable("settlement.skycraft.house.owned_by", house.displayName(),
                        String.valueOf(data.getOwnerName(house.id()))));
            }
            return false;
        }
        if (!Currency.take(player, house.cost())) {
            Notifier.message(player, Component.translatable("message.skycraft.economy.house.not_enough_gold", house.cost(), house.displayName()));
            return false;
        }
        data.setOwner(house.id(), player.getUUID(), player.getScoreboardName());
        data.setDirty();

        if (house.homestead()) {
            // the land deed: a drafting table to found the homestead on the plot
            give(player, new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(Skycraft.MODID, "drafting_table"))));
            give(player, HouseKeyItem.forHouse(house.id()));
            Notifier.message(player, Component.translatable("settlement.skycraft.house.homestead_bought", house.displayName()));
        } else {
            give(player, HouseKeyItem.forHouse(house.id()));
            Optional<HouseSite> site = SettlementAnchors.houseSite(level, house.id());
            if (site.isPresent()) {
                claim(level, site.get(), player.getUUID());
                if (site.get().bed() != null) RespawnPoints.set(player, Level.OVERWORLD, site.get().bed());
            } else {
                // not generated yet: the claim is applied when the house is built (see onSiteBuilt)
                data.pendingRespawn.add(player.getUUID());
            }
            Notifier.message(player, Component.translatable("message.skycraft.economy.house.bought", house.displayName()));
        }
        if (signPos != null) updateHouseSign(level, signPos, house.displayName(), player.getScoreboardName());
        level.playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f);
        SkyData.get(player).addStat("houses_owned", 1);
        return true;
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    /** Every built block of the house becomes the player's (contract 1). */
    static void claim(ServerLevel level, HouseSite site, UUID player) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = site.min().getX(); x <= site.max().getX(); x++) {
            for (int z = site.min().getZ(); z <= site.max().getZ(); z++) {
                for (int y = site.min().getY(); y <= site.max().getY(); y++) {
                    p.set(x, y, z);
                    if (!level.isEmptyBlock(p)) Ownership.setPlayerOwner(level, p.immutable(), player);
                }
            }
        }
    }

    /** Called by the settlement module when a house for sale has been generated and recorded. */
    public static void onSiteBuilt(ServerLevel level, String houseId) {
        HousesData data = HousesData.get(level.getServer());
        UUID owner = data.getOwner(houseId);
        if (owner == null) return;
        SettlementAnchors.houseSite(level, houseId).ifPresent(site -> {
            claim(level, site, owner);
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
            if (player != null && site.bed() != null && data.pendingRespawn.remove(owner)) {
                RespawnPoints.set(player, Level.OVERWORLD, site.bed());
                data.setDirty();
            }
        });
    }

    // ------------------------------------------------------------------ furnishing

    public static boolean purchaseFurnishing(ServerPlayer player, ServerLevel level, HouseDef house, Furnishing furn) {
        HousesData data = HousesData.get(level.getServer());
        if (!isHouseOwner(player, house)) {
            Notifier.message(player, Component.translatable("settlement.skycraft.house.not_owner", house.displayName()));
            return false;
        }
        if (data.hasFurnishing(house.id(), furn.id())) {
            Notifier.message(player, Component.translatable("settlement.skycraft.house.has_furnishing", house.displayName(), furn.name()));
            return false;
        }
        Optional<HouseSite> site = SettlementAnchors.houseSite(level, house.id());
        if (house.homestead() || site.isEmpty()) {
            Notifier.message(player, Component.translatable("settlement.skycraft.house.cannot_furnish", house.displayName()));
            return false;
        }
        if (!Currency.take(player, furn.cost())) {
            Notifier.message(player, Component.translatable("message.skycraft.economy.house.not_enough_gold", furn.cost(), furn.name()));
            return false;
        }
        data.addFurnishing(house.id(), furn.id());
        data.setDirty();
        placeFurnishingPreset(level, site.get(), furn.id(), player.getUUID());
        level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.4f);
        Notifier.message(player, Component.translatable("message.skycraft.economy.house.furnish_bought", furn.name(), house.displayName()));
        return true;
    }

    /** Places a furnishing preset on free floor near the house's centre (never inside walls). */
    private static void placeFurnishingPreset(ServerLevel level, HouseSite site, String furnId, UUID player) {
        BlockPos center = site.center();
        switch (furnId) {
            case "bedroom" -> {
                BlockPos bedPos = free(level, site, center, 2, 2);
                if (bedPos != null && level.isEmptyBlock(bedPos.east())) {
                    level.setBlock(bedPos, Blocks.RED_BED.defaultBlockState(), 3);
                    level.setBlock(bedPos.east(), Blocks.BARREL.defaultBlockState(), 3);
                    Ownership.setPlayerOwner(level, bedPos, player);
                    Ownership.setPlayerOwner(level, bedPos.east(), player);
                }
            }
            case "living_room" -> {
                BlockPos t = free(level, site, center, -2, 1);
                if (t != null) {
                    level.setBlock(t, Blocks.OAK_FENCE.defaultBlockState(), 3);
                    level.setBlock(t.above(), Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), 3);
                    Ownership.setPlayerOwner(level, t, player);
                }
            }
            case "alchemy_lab" -> {
                BlockPos lab = free(level, site, center, -2, -2);
                if (lab != null) {
                    level.setBlock(lab, ArcaneRegistry.ALCHEMY_LAB.get().defaultBlockState(), 3);
                    Ownership.setPlayerOwner(level, lab, player);
                }
            }
            case "arcane_enchanter" -> {
                BlockPos ench = free(level, site, center, 2, -2);
                if (ench != null) {
                    level.setBlock(ench, ArcaneRegistry.ARCANE_ENCHANTER.get().defaultBlockState(), 3);
                    Ownership.setPlayerOwner(level, ench, player);
                }
            }
            case "armory" -> {
                BlockPos chest = free(level, site, center, 0, 2);
                if (chest != null) {
                    level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
                    Ownership.setPlayerOwner(level, chest, player);
                }
            }
            default -> {
            }
        }
    }

    /** A free floor spot inside the house near centre + (dx, dz), searching outwards. */
    @Nullable
    private static BlockPos free(ServerLevel level, HouseSite site, BlockPos center, int dx, int dz) {
        for (int r = 0; r <= 4; r++) {
            for (int ox = -r; ox <= r; ox++) {
                for (int oz = -r; oz <= r; oz++) {
                    BlockPos p = center.offset(dx + ox, 0, dz + oz);
                    if (!site.contains(p)) continue;
                    if (level.isEmptyBlock(p) && level.isEmptyBlock(p.above())
                            && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) return p;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ doors and signs

    /** House doors: locked until bought, then open only to the owner and whoever carries its key. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickDoor(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (level.dimension() != Level.OVERWORLD || player.isCreative() || player.isSpectator()) return;
        BlockState state = level.getBlockState(event.getPos());
        if (!(state.getBlock() instanceof DoorBlock) && !(state.getBlock() instanceof TrapDoorBlock)) return;
        HouseDef house = houseNear(event.getPos());
        if (house == null) return;
        Optional<HouseSite> site = SettlementAnchors.houseSite(level, house.id());
        if (site.isEmpty() || !site.get().contains(event.getPos())) return;
        HousesData data = HousesData.get(level.getServer());
        UUID owner = data.getOwner(house.id());
        if (owner != null && (owner.equals(player.getUUID()) || hasKey(player, house.id()))) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        Notifier.message(player, owner == null
                ? Component.translatable("settlement.skycraft.house.for_sale", house.displayName())
                : Component.translatable("settlement.skycraft.house.locked", house.displayName()));
    }

    public static boolean hasKey(Player player, String houseId) {
        for (ItemStack s : player.getInventory().items) {
            if (s.getItem() instanceof HouseKeyItem && houseId.equals(HouseKeyItem.houseOf(s))) return true;
        }
        return houseId.equals(HouseKeyItem.houseOf(player.getOffhandItem()));
    }

    private static void updateHouseSign(ServerLevel level, BlockPos pos, Component houseName, String ownerName) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SignBlockEntity sbe) {
            net.minecraft.world.level.block.entity.SignText text = new net.minecraft.world.level.block.entity.SignText()
                    .setMessage(0, Component.literal("=== HOME ==="))
                    .setMessage(1, houseName)
                    .setMessage(2, Component.literal("Owned by:"))
                    .setMessage(3, Component.literal(ownerName));
            sbe.setText(text, true);
            sbe.setWaxed(true);
            sbe.setChanged();
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Shop signs tell the opening hours. */
    @SubscribeEvent
    public static void onRightClickSign(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SignBlock) && !(state.getBlock() instanceof WallSignBlock)) return;
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        String line0 = sign.getText(true).getMessage(0, false).getString().toLowerCase(Locale.ROOT);
        String line1 = sign.getText(true).getMessage(1, false).getString().toLowerCase(Locale.ROOT);
        if (line0.contains("shop") || line1.contains("shop") || line0.contains("store") || line1.contains("store")) {
            boolean open = Shop.isOpen(level);
            if (event.getEntity() instanceof ServerPlayer sp && event.getHand() == InteractionHand.MAIN_HAND) {
                Notifier.message(sp, Component.translatable(open ? "settlement.skycraft.shop.open" : "settlement.skycraft.shop.closed"));
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    // ------------------------------------------------------------------ saved data

    public static class HousesData extends SavedData {
        private static final String NAME = "skycraft_houses";
        private final Map<String, UUID> owners = new HashMap<>();
        private final Map<String, String> ownerNames = new HashMap<>();
        private final Map<String, Set<String>> furnishings = new HashMap<>();
        /** Buyers whose house was not generated yet: their rest point is set once it is. */
        final Set<UUID> pendingRespawn = new HashSet<>();

        public static HousesData get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(HousesData::load, HousesData::new, NAME);
        }

        @Nullable
        public UUID getOwner(String houseId) {
            return owners.get(houseId);
        }

        @Nullable
        public String getOwnerName(String houseId) {
            return ownerNames.get(houseId);
        }

        public void setOwner(String houseId, UUID owner, String name) {
            owners.put(houseId, owner);
            ownerNames.put(houseId, name);
        }

        public boolean hasFurnishing(String houseId, String furnId) {
            Set<String> set = furnishings.get(houseId);
            return set != null && set.contains(furnId);
        }

        public void addFurnishing(String houseId, String furnId) {
            furnishings.computeIfAbsent(houseId, k -> new HashSet<>()).add(furnId);
        }

        public static HousesData load(CompoundTag tag) {
            HousesData data = new HousesData();
            CompoundTag ownersTag = tag.getCompound("owners");
            for (String k : ownersTag.getAllKeys()) {
                CompoundTag entry = ownersTag.getCompound(k);
                if (entry.hasUUID("uuid")) {
                    data.owners.put(k, entry.getUUID("uuid"));
                    data.ownerNames.put(k, entry.getString("name"));
                }
            }
            CompoundTag furnTag = tag.getCompound("furnishings");
            for (String k : furnTag.getAllKeys()) {
                ListTag list = furnTag.getList(k, Tag.TAG_STRING);
                Set<String> set = new HashSet<>();
                for (int i = 0; i < list.size(); i++) set.add(list.getString(i));
                data.furnishings.put(k, set);
            }
            ListTag pending = tag.getList("pending", Tag.TAG_INT_ARRAY);
            for (int i = 0; i < pending.size(); i++) data.pendingRespawn.add(net.minecraft.nbt.NbtUtils.loadUUID(pending.get(i)));
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CompoundTag ownersTag = new CompoundTag();
            for (Map.Entry<String, UUID> e : owners.entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("uuid", e.getValue());
                String name = ownerNames.get(e.getKey());
                if (name != null) entry.putString("name", name);
                ownersTag.put(e.getKey(), entry);
            }
            tag.put("owners", ownersTag);
            CompoundTag furnTag = new CompoundTag();
            for (Map.Entry<String, Set<String>> e : furnishings.entrySet()) {
                ListTag list = new ListTag();
                for (String f : e.getValue()) list.add(StringTag.valueOf(f));
                furnTag.put(e.getKey(), list);
            }
            tag.put("furnishings", furnTag);
            ListTag pending = new ListTag();
            for (UUID u : pendingRespawn) pending.add(net.minecraft.nbt.NbtUtils.createUUID(u));
            tag.put("pending", pending);
            return tag;
        }
    }
}
