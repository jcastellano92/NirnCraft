package com.skycraft.economy;

import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.Holds;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.combat.RespawnPoints;
import com.skycraft.crafting.arcane.ArcaneRegistry;
import com.skycraft.crime.Ownership;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Skyrim player homes and real estate.
 * Houses in town settlements can be bought from "For Sale" signs or from the Jarl/steward.
 * Purchasing a house claims all blocks in its boundary (contract 1), sets the player's respawn
 * point (contract 9), and unlocks furnishing upgrades (bedroom, living room, alchemy lab, enchanter).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Houses {
    public record HouseDef(
            String id,
            String name,
            String hold,
            int cost,
            BlockPos approxPos,
            int radius
    ) {}

    public static final List<HouseDef> HOUSES = List.of(
            new HouseDef("breezehome", "Breezehome", "whiterun", 5000, new BlockPos(12, 65, 18), 12),
            new HouseDef("honeyside", "Honeyside", "the_rift", 8000, new BlockPos(1400, 65, -800), 12),
            new HouseDef("vlindrel_hall", "Vlindrel Hall", "the_reach", 8000, new BlockPos(-1500, 75, 400), 14),
            new HouseDef("hjerim", "Hjerim", "eastmarch", 12000, new BlockPos(1800, 68, 1200), 15),
            new HouseDef("proudspire", "Proudspire Manor", "haafingar", 25000, new BlockPos(-800, 72, -1600), 16),
            new HouseDef("riverwood_cottage", "Riverwood Cottage", "whiterun", 2500, new BlockPos(40, 64, 80), 10),
            new HouseDef("falkreath_homestead", "Falkreath Homestead", "falkreath", 4000, new BlockPos(-400, 66, 1100), 12),
            new HouseDef("morthal_shack", "Morthal Shack", "hjaalmarch", 3000, new BlockPos(-600, 63, -400), 10),
            new HouseDef("dawnstar_sanctuary", "Dawnstar Sanctuary", "the_pale", 5000, new BlockPos(600, 65, -1400), 12),
            new HouseDef("winterhold_retreat", "Winterhold Retreat", "winterhold", 4000, new BlockPos(1600, 70, -1800), 12)
    );

    public record Furnishing(String id, String name, int cost) {}

    public static final List<Furnishing> UPGRADES = List.of(
            new Furnishing("bedroom", "Bedroom Suite", 500),
            new Furnishing("living_room", "Living Room & Kitchen", 400),
            new Furnishing("alchemy_lab", "Alchemy Lab", 600),
            new Furnishing("arcane_enchanter", "Arcane Enchanter", 800),
            new Furnishing("armory", "Armory & Storage", 500)
    );

    private Houses() {}

    @Nullable
    public static HouseDef houseForHold(String hold) {
        for (HouseDef h : HOUSES) {
            // region ids ("skyrim/whiterun") and the short hold names of the table both match
            if (Holds.base(h.hold()).equalsIgnoreCase(Holds.base(hold))) return h;
        }
        return null;
    }

    @Nullable
    public static HouseDef houseNear(BlockPos pos) {
        for (HouseDef h : HOUSES) {
            if (h.approxPos().distSqr(pos) <= (h.radius() + 8) * (h.radius() + 8)) {
                return h;
            }
        }
        return null;
    }

    public static boolean isHouseOwner(Player player, HouseDef house) {
        if (player.level() instanceof ServerLevel sl) {
            HousesData data = HousesData.get(sl.getServer());
            UUID owner = data.getOwner(house.id());
            return owner != null && owner.equals(player.getUUID());
        }
        return false;
    }

    public static boolean purchaseHouse(ServerPlayer player, ServerLevel level, HouseDef house, @Nullable BlockPos signPos) {
        HousesData data = HousesData.get(level.getServer());
        UUID currentOwner = data.getOwner(house.id());
        if (currentOwner != null) {
            if (currentOwner.equals(player.getUUID())) {
                Notifier.message(player, Component.translatable("dialogue.skycraft.economy.house.already_owned", house.name()));
            } else {
                Notifier.message(player, Component.literal("This home is already owned by someone else."));
            }
            return false;
        }

        if (!Currency.take(player, house.cost())) {
            Notifier.message(player, Component.translatable("message.skycraft.economy.house.not_enough_gold", house.cost(), house.name()));
            return false;
        }

        // Set owner in HousesData
        data.setOwner(house.id(), player.getUUID(), player.getScoreboardName());
        data.setDirty();

        // Claim house region (Contract 1 Ownership)
        BlockPos center = house.approxPos();
        int r = house.radius();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -4; dy <= 12; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!level.isEmptyBlock(p)) {
                        Ownership.setPlayerOwner(level, p, player.getUUID());
                    }
                }
            }
        }

        // Set respawn point (Contract 9 RespawnPoints): a real standing spot, never inside the walls or floor
        RespawnPoints.set(player, level.dimension(), safeStandingSpot(level, center, r));

        // Update sign if provided
        if (signPos != null) {
            updateHouseSign(level, signPos, house.name(), player.getScoreboardName());
        }

        level.playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f);
        Notifier.message(player, Component.translatable("message.skycraft.economy.house.bought", house.name()));
        SkyData.get(player).addStat("houses_owned", 1);
        return true;
    }

    /**
     * The free spot (two passable, dry blocks over a sturdy floor) closest to {@code center} within the house radius,
     * or the surface at the center when there is none. Stopgap until houses become region plots.
     */
    static BlockPos safeStandingSpot(ServerLevel level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int r = Math.max(2, Math.min(radius, 12));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!level.getChunkSource().hasChunk((center.getX() + dx) >> 4, (center.getZ() + dz) >> 4)) continue;
                for (int dy = -4; dy <= 8; dy++) {
                    p.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    double d = p.distSqr(center);
                    if (d >= bestDist || !standable(level, p)) continue;
                    best = p.immutable();
                    bestDist = d;
                }
            }
        }
        if (best != null) return best;
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
        return new BlockPos(center.getX(), y, center.getZ());
    }

    private static boolean standable(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)) return false;
        for (BlockPos q : new BlockPos[]{feet, feet.above()}) {
            if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty() || !level.getFluidState(q).isEmpty()) return false;
        }
        return true;
    }

    public static boolean purchaseFurnishing(ServerPlayer player, ServerLevel level, HouseDef house, Furnishing furn) {
        HousesData data = HousesData.get(level.getServer());
        if (!isHouseOwner(player, house)) {
            Notifier.message(player, Component.literal("You must own " + house.name() + " before decorating it."));
            return false;
        }
        if (data.hasFurnishing(house.id(), furn.id())) {
            Notifier.message(player, Component.literal(house.name() + " already has the " + furn.name() + "."));
            return false;
        }
        if (!Currency.take(player, furn.cost())) {
            Notifier.message(player, Component.translatable("message.skycraft.economy.house.not_enough_gold", furn.cost(), furn.name()));
            return false;
        }

        data.addFurnishing(house.id(), furn.id());
        data.setDirty();

        // Place furnishings preset inside house
        BlockPos center = house.approxPos();
        placeFurnishingPreset(level, center, furn.id(), player.getUUID());

        level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.4f);
        Notifier.message(player, Component.translatable("message.skycraft.economy.house.furnish_bought", furn.name(), house.name()));
        return true;
    }

    private static void placeFurnishingPreset(ServerLevel level, BlockPos center, String furnId, UUID player) {
        switch (furnId) {
            case "bedroom" -> {
                BlockPos bedPos = center.offset(2, 0, 2);
                level.setBlock(bedPos, Blocks.RED_BED.defaultBlockState(), 3);
                level.setBlock(bedPos.offset(1, 0, 0), Blocks.BARREL.defaultBlockState(), 3);
                Ownership.setPlayerOwner(level, bedPos, player);
                Ownership.setPlayerOwner(level, bedPos.offset(1, 0, 0), player);
            }
            case "living_room" -> {
                BlockPos tablePos = center.offset(-2, 0, 1);
                level.setBlock(tablePos, Blocks.OAK_FENCE.defaultBlockState(), 3);
                level.setBlock(tablePos.above(), Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), 3);
                level.setBlock(tablePos.offset(0, 0, 1), Blocks.OAK_STAIRS.defaultBlockState(), 3);
                level.setBlock(tablePos.offset(0, 0, -1), Blocks.OAK_STAIRS.defaultBlockState(), 3);
                Ownership.setPlayerOwner(level, tablePos, player);
            }
            case "alchemy_lab" -> {
                BlockPos labPos = center.offset(-2, 0, -2);
                level.setBlock(labPos, ArcaneRegistry.ALCHEMY_LAB.get().defaultBlockState(), 3);
                level.setBlock(labPos.above(), Blocks.BREWING_STAND.defaultBlockState(), 3);
                Ownership.setPlayerOwner(level, labPos, player);
                Ownership.setPlayerOwner(level, labPos.above(), player);
            }
            case "arcane_enchanter" -> {
                BlockPos enchPos = center.offset(2, 0, -2);
                level.setBlock(enchPos, ArcaneRegistry.ARCANE_ENCHANTER.get().defaultBlockState(), 3);
                Ownership.setPlayerOwner(level, enchPos, player);
            }
            case "armory" -> {
                BlockPos chestPos = center.offset(0, 0, 3);
                level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
                level.setBlock(chestPos.offset(1, 0, 0), Blocks.CHEST.defaultBlockState(), 3);
                Ownership.setPlayerOwner(level, chestPos, player);
                Ownership.setPlayerOwner(level, chestPos.offset(1, 0, 0), player);
            }
        }
    }

    private static void updateHouseSign(ServerLevel level, BlockPos pos, String houseName, String ownerName) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SignBlockEntity sbe) {
            SignText text = new SignText()
                    .setMessage(0, Component.literal("=== HOME ==="))
                    .setMessage(1, Component.literal(houseName))
                    .setMessage(2, Component.literal("Owned by:"))
                    .setMessage(3, Component.literal(ownerName));
            sbe.setText(text, true);
            sbe.setWaxed(true);
            sbe.setChanged();
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @SubscribeEvent
    public static void onRightClickSign(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SignBlock) && !(state.getBlock() instanceof WallSignBlock)) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SignBlockEntity sign)) return;

        // Check if sign is a Shop hours sign
        String line0 = sign.getText(true).getMessage(0, false).getString().toLowerCase();
        String line1 = sign.getText(true).getMessage(1, false).getString().toLowerCase();
        if (line0.contains("shop") || line1.contains("shop") || line0.contains("store") || line1.contains("store")) {
            boolean open = Shop.isOpen(level);
            if (event.getEntity() instanceof ServerPlayer sp) {
                Notifier.message(sp, Component.literal(open
                        ? "Shop is OPEN (Hours: 8:00 AM - 8:00 PM)"
                        : "Shop is CLOSED for the night (Hours: 8:00 AM - 8:00 PM)"));
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        // Check if sign is a "For Sale" house sign
        boolean isForSale = line0.contains("for sale") || line1.contains("for sale") || line0.contains("home");
        HouseDef house = houseNear(pos);
        if (house == null && !isForSale) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(level instanceof ServerLevel sl)) return;

        if (house == null) {
            // Generic procedural town house
            String hold = Holds.holdAt(level, pos);
            house = new HouseDef("house_" + pos.getX() + "_" + pos.getZ(), "Town House", hold, 3000, pos, 10);
        }

        HousesData data = HousesData.get(sl.getServer());
        UUID owner = data.getOwner(house.id());
        if (owner != null) {
            if (owner.equals(player.getUUID())) {
                Notifier.message(player, Component.literal("This is your home: " + house.name()));
            } else {
                String ownerName = data.getOwnerName(house.id());
                Notifier.message(player, Component.literal(house.name() + " is owned by " + (ownerName != null ? ownerName : "another resident") + "."));
            }
            return;
        }

        // Offer purchase
        purchaseHouse(player, sl, house, pos);
    }

    // ------------------------------------------------------------------ saved data

    public static class HousesData extends SavedData {
        private static final String NAME = "skycraft_houses";
        private final Map<String, UUID> owners = new HashMap<>();
        private final Map<String, String> ownerNames = new HashMap<>();
        private final Map<String, Set<String>> furnishings = new HashMap<>();

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
            return tag;
        }
    }
}

