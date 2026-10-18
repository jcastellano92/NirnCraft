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
import com.skycraft.tamriel.settlement.plan.HousePlots;
import com.skycraft.economy.homes.ProvincialHomes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;

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
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
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
        // the other provinces' homes: plots reserved in their cities, built by economy.homes (approxPos is the
        // preferred spot; the plot may have moved a little, so lookups go through the recorded site)
        for (HousePlots.Plot h : HousePlots.PLOTS) {
            Optional<Place> p = Places.byId(h.place());
            if (p.isEmpty()) continue;
            int[] w = Cities.world(p.get(), h.lx(), h.lz());
            out.add(new HouseDef(h.id(), pretty(h.id()), p.get().regionId(), h.cost(), h.place(), new BlockPos(w[0], 64, w[1]),
                    Math.max(h.w(), h.d()) / 2 + 1, false));
        }
        // Hearthfire homestead land plots across all provinces (Skyrim's 9 holds + all provincial regions)
        for (var plot : com.skycraft.homestead.Homesteads.PLOTS) {
            String id = plot.id();
            Places.byId(id).ifPresent(p -> out.add(new HouseDef(id, pretty(id), p.regionId(), 5000, id,
                    new BlockPos(p.x(), 64, p.z()), 12, true)));
        }
        return List.copyOf(out);
    }

    /**
     * Where a house stands as built: the settlement's recorded bounds and door, with the owner's bed (for the
     * provinces' homes, those of {@link ProvincialHomes}). Empty until its city has been generated.
     */
    public static Optional<HouseSite> site(ServerLevel level, String houseId) {
        Optional<HouseSite> s = SettlementAnchors.houseSite(level, houseId);
        if (s.isEmpty() || !ProvincialHomes.isProvincial(houseId)) return s;
        BlockPos bed = ProvincialHomes.bed(level, houseId);
        BlockPos door = ProvincialHomes.door(level, houseId);
        HouseSite h = s.get();
        BlockPos max = h.max();
        if (bed != null && bed.getY() + 6 > max.getY()) max = new BlockPos(max.getX(), bed.getY() + 6, max.getZ());
        return Optional.of(new HouseSite(h.houseId(), h.settlement(), h.min(), max, door != null ? door : h.door(), bed));
    }

    /** The house whose built bounds hold {@code pos} (server side; homes in the provinces may stand off their plan). */
    @Nullable
    public static HouseDef houseAt(ServerLevel level, BlockPos pos) {
        for (HouseDef h : HOUSES) {
            if (h.homestead()) continue;
            if (Math.abs(pos.getX() - h.approxPos().getX()) > 140 || Math.abs(pos.getZ() - h.approxPos().getZ()) > 140) continue;
            if (!ProvincialHomes.isProvincial(h.id())) {
                if (h.contains(pos)) return h;
                continue;
            }
            Optional<HouseSite> s = SettlementAnchors.houseSite(level, h.id());
            if (s.isPresent() && s.get().contains(pos)) return h;
        }
        return null;
    }

    /** The homes sold in the settlement at {@code pos} (city homes in that settlement plus homestead plots in its region). */
    public static List<HouseDef> housesFor(Level level, BlockPos pos) {
        List<HouseDef> out = new ArrayList<>();
        Optional<Place> here = Places.nearest(null, pos.getX(), pos.getZ(), 260);
        if (here.isPresent() && here.get().type().isSettlement()) {
            for (HouseDef d : HOUSES) if (!d.homestead() && d.placeId().equals(here.get().id())) out.add(d);
            for (HouseDef d : HOUSES) {
                if (d.homestead() && d.hold().equals(here.get().regionId()) && !out.contains(d)) out.add(d);
            }
        }
        if (out.isEmpty()) {
            HouseDef d = houseFor(level, pos);
            if (d != null) out.add(d);
        }
        return out;
    }

    /** All homes (city homes and homestead plots) sold in a hold/region. */
    public static List<HouseDef> housesForHold(String hold) {
        if (hold == null || hold.isEmpty()) return List.of();
        String h = hold.toLowerCase(Locale.ROOT);
        List<HouseDef> out = new ArrayList<>();
        for (HouseDef d : HOUSES) {
            String region = d.hold();
            String shortId = region.substring(region.lastIndexOf('/') + 1);
            if (region.equals(h) || shortId.equals(h) || h.endsWith("/" + shortId)) out.add(d);
        }
        return List.copyOf(out);
    }

    /** Where a married player's spouse should live: inside the home the player owns nearest to them (or null). */
    @Nullable
    public static BlockPos homeOf(ServerPlayer player) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (HouseDef h : HOUSES) {
            if (h.homestead() || !isHouseOwner(player, h)) continue;
            BlockPos at = site(player.level(), h.id()).map(s -> s.bed() != null ? s.bed() : s.center()).orElse(h.approxPos());
            double d = at.distSqr(player.blockPosition());
            if (d < bestD) {
                bestD = d;
                best = at;
            }
        }
        return best;
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
        if (id == null) return null;
        String canon = com.skycraft.homestead.Homesteads.canonPlotId(id);
        for (HouseDef h : HOUSES) if (h.id().equals(id) || h.id().equals(canon)) return h;
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
        Component refusal = ProvincialHomes.refusal(player, house.id());
        if (refusal != null) {
            Notifier.message(player, refusal);
            return false;
        }
        if (!Currency.take(player, house.cost())) {
            Notifier.message(player, Component.translatable("message.skycraft.economy.house.not_enough_gold", house.cost(), house.displayName()));
            return false;
        }
        data.setOwner(house.id(), player.getUUID(), player.getScoreboardName());
        ProvincialHomes.onBought(player, house.id());
        data.setDirty();

        if (house.homestead()) {
            // the land deed: a drafting table to found the homestead on the plot
            give(player, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(Skycraft.MODID, "drafting_table"))));
            give(player, HouseKeyItem.forHouse(house.id()));
            Notifier.message(player, Component.translatable("settlement.skycraft.house.homestead_bought", house.displayName()));
        } else {
            give(player, HouseKeyItem.forHouse(house.id()));
            Optional<HouseSite> site = site(level, house.id());
            if (site.isPresent()) {
                claim(level, site.get(), player.getUUID());
                if (site.get().bed() != null) RespawnPoints.setOwned(player, Level.OVERWORLD, site.get().bed());
                else data.pendingRespawn.add(player.getUUID());
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
        com.skycraft.inventory.Pack.giveOrDrop(player, stack);
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
        site(level, houseId).ifPresent(site -> {
            claim(level, site, owner);
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
            if (player != null && site.bed() != null && data.pendingRespawn.remove(owner)) {
                RespawnPoints.setOwned(player, Level.OVERWORLD, site.bed());
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
        Optional<HouseSite> site = site(level, house.id());
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
                    level.setBlock(bedPos, com.skycraft.worldgen.VanillaBlocks.RED_BED.defaultBlockState(), 3);
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
        HouseDef house = houseAt(level, event.getPos());
        if (house == null) return;
        Optional<HouseSite> site = site(level, house.id());
        if (site.isEmpty() || !site.get().contains(event.getPos())) return;
        HousesData data = HousesData.get(level.getServer());
        UUID owner = data.getOwner(house.id());
        if (owner != null && (owner.equals(player.getUUID()) || hasKey(player, house.id()))) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (owner == null && player.isSecondaryUseActive()) {
            // the deed can be bought at the door too (sneak-use twice): a city without a steward still sells its home
            long now = level.getGameTime();
            String[] pending = DOOR_OFFERS.get(player.getUUID());
            if (pending != null && pending[0].equals(house.id()) && now - Long.parseLong(pending[1]) < 200) {
                DOOR_OFFERS.remove(player.getUUID());
                purchaseHouse(player, level, house, null);
            } else {
                DOOR_OFFERS.put(player.getUUID(), new String[]{house.id(), Long.toString(now)});
                Notifier.message(player, Component.translatable("economy.skycraft.home.door_offer", house.displayName(), Currency.formatCompact(house.cost())));
            }
            return;
        }
        Notifier.message(player, owner == null
                ? Component.translatable("settlement.skycraft.house.for_sale", house.displayName())
                : Component.translatable("settlement.skycraft.house.locked", house.displayName()));
    }

    /** Door purchase offers awaiting the second sneak-use: player -> {house id, game time}. */
    private static final Map<UUID, String[]> DOOR_OFFERS = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean hasKey(Player player, String houseId) {
        return com.skycraft.inventory.Inv.has(player, s -> s.getItem() instanceof HouseKeyItem && houseId.equals(HouseKeyItem.houseOf(s)));
    }

    private static void updateHouseSign(ServerLevel level, BlockPos pos, Component houseName, String ownerName) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SignBlockEntity sbe) {
            net.minecraft.world.level.block.entity.SignText text = net.minecraft.world.level.block.entity.SignText.EMPTY
                    .asMutable().setLine(0, Component.literal("=== HOME ===")).asImmutable()
                    .asMutable().setLine(1, houseName).asImmutable()
                    .asMutable().setLine(2, Component.literal("Owned by:")).asImmutable()
                    .asMutable().setLine(3, Component.literal(ownerName)).asImmutable();
            sbe.setText(text, net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
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
        String line0 = sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).get(0).getString().toLowerCase(Locale.ROOT);
        String line1 = sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).get(1).getString().toLowerCase(Locale.ROOT);
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
            return com.skycraft.platform.SavedDataTypes.get(server.overworld(), NAME, HousesData::load, HousesData::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
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
            CompoundTag ownersTag = tag.getCompoundOrEmpty("owners");
            for (String k : ownersTag.keySet()) {
                CompoundTag entry = ownersTag.getCompoundOrEmpty(k);
                if (entry.read("uuid", net.minecraft.core.UUIDUtil.CODEC).isPresent()) {
                    data.owners.put(k, entry.read("uuid", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
                    data.ownerNames.put(k, entry.getStringOr("name",""));
                }
            }
            CompoundTag furnTag = tag.getCompoundOrEmpty("furnishings");
            for (String k : furnTag.keySet()) {
                ListTag list = furnTag.getListOrEmpty(k);
                Set<String> set = new HashSet<>();
                for (int i = 0; i < list.size(); i++) set.add(list.getStringOr(i, ""));
                data.furnishings.put(k, set);
            }
            ListTag pending = tag.getListOrEmpty("pending");
            for (int i = 0; i < pending.size(); i++) data.pendingRespawn.add(net.minecraft.core.UUIDUtil.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, pending.get(i)).getOrThrow());
            return data;
        }

        public CompoundTag save(CompoundTag tag) {
            CompoundTag ownersTag = new CompoundTag();
            for (Map.Entry<String, UUID> e : owners.entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.store("uuid", net.minecraft.core.UUIDUtil.CODEC,  e.getValue());
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
            for (UUID u : pendingRespawn) pending.add(net.minecraft.core.UUIDUtil.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, u).getOrThrow());
            tag.put("pending", pending);
            return tag;
        }
    }
}
