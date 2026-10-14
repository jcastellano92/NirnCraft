package com.skycraft.homestead;

import com.skycraft.Skycraft;
import com.skycraft.economy.Shop;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Npcs;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import com.skycraft.tamriel.settlement.SettlementPopulation;
import com.skycraft.world.FastTravelHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Makes sure every capital, city and large town has its builder (the person {@link HomesteadDialogue} talks through).
 * The settlement kit will place the builder's own shop and a {@code BUILDER} anchor (docs/world/BUILDING-KITS.md); until
 * then, and wherever a recorded settlement has no builder, the first player to come near gets one at the market
 * square: a mason's trade (the mason's stock, with the regional layer), tagged {@value HomesteadDialogue#TAG}.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class HomesteadBuilders {
    private static final int INTERVAL = 100;
    private static final long RECHECK_TICKS = 1200;
    private static final Map<String, Long> CHECKED = new HashMap<>();

    private HomesteadBuilders() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player) || player.isSpectator()) return;
        if ((player.tickCount + player.getId()) % INTERVAL != 0 || player.level().dimension() != Level.OVERWORLD) return;
        try {
            ensureBuilder(player.serverLevel(), player.blockPosition());
        } catch (RuntimeException e) {
            Skycraft.LOGGER.debug("Skycraft builder check failed", e);
        }
    }

    /** Spawns the builder of the major settlement at {@code pos}, if it has none; returns true if one was made. */
    public static boolean ensureBuilder(ServerLevel level, BlockPos pos) {
        Optional<SettlementAnchors.Entry> near = SettlementAnchors.near(level, pos, 8);
        if (near.isEmpty() || near.get().homestead()) return false;
        SettlementAnchors.Entry e = near.get();
        if (SettlementPopulation.tier(e) > 1) return false; // capitals, cities and large towns
        long now = level.getGameTime();
        Long last = CHECKED.get(e.key);
        if (last != null && now - last < RECHECK_TICKS && now >= last) return false;
        CHECKED.put(e.key, now);
        AABB box = new AABB(e.x - e.radius - 16, level.getMinBuildHeight(), e.z - e.radius - 16,
                e.x + e.radius + 16, level.getMaxBuildHeight(), e.z + e.radius + 16);
        if (!level.getEntitiesOfClass(NpcEntity.class, box, n -> n.isAlive() && n.getPersistentData().getBoolean(HomesteadDialogue.TAG)).isEmpty()) {
            return false;
        }
        BlockPos at = spot(level, e);
        if (at == null) return false;
        NpcEntity npc = Npcs.create(level, at, NpcRole.MERCHANT);
        if (npc == null) return false;
        npc.setPersistenceRequired();
        String first = npc.getName().getString();
        npc.setCustomName(Component.translatable("homestead.skycraft.builder.title", first));
        CompoundTag pd = npc.getPersistentData();
        pd.putBoolean(HomesteadDialogue.TAG, true);
        pd.putString("skycraft_trade", "builder");
        pd.putString(Npcs.PROFESSION_KEY, "minecraft:mason");
        CompoundTag shop = new CompoundTag();
        ListTag pools = new ListTag();
        pools.add(StringTag.valueOf("mason"));
        shop.put("pools", pools);
        shop.putInt("level", 3);
        pd.put(Shop.KEY, shop);
        pd.putBoolean("skycraft_settler", true);
        npc.setHome(at, 20);
        return level.addFreshEntity(npc);
    }

    /** A free standing spot by the market square (the meeting anchor), else a gate, else the settlement's heart. */
    private static BlockPos spot(ServerLevel level, SettlementAnchors.Entry e) {
        BlockPos base = null;
        for (AnchorType t : new AnchorType[]{AnchorType.MEETING, AnchorType.GATE, AnchorType.CARRIAGE, AnchorType.INN}) {
            Anchor a = e.first(t);
            if (a != null) {
                base = a.pos();
                break;
            }
        }
        if (base == null) base = new BlockPos(e.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, e.x, e.z), e.z);
        if (!level.isLoaded(base)) return null;
        for (int r = 1; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos p = base.offset(dx, 0, dz);
                    if (!level.isLoaded(p)) continue;
                    for (int dy = 1; dy >= -2; dy--) {
                        BlockPos q = p.above(dy);
                        if (FastTravelHandler.isSafe(level, q)) return q;
                    }
                }
            }
        }
        return null;
    }
}
