package com.nirncraft.homestead;

import com.nirncraft.NirnCraft;
import com.nirncraft.economy.Shop;
import com.nirncraft.society.NpcRole;
import com.nirncraft.society.Npcs;
import com.nirncraft.society.entity.NpcEntity;
import com.nirncraft.tamriel.settlement.Anchor;
import com.nirncraft.tamriel.settlement.AnchorType;
import com.nirncraft.tamriel.settlement.SettlementAnchors;
import com.nirncraft.tamriel.settlement.SettlementPopulation;
import com.nirncraft.world.FastTravelHandler;
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

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Makes sure every capital, city and large town has its builder (the person {@link HomesteadDialogue} talks through).
 * The settlement kit will place the builder's own shop and a {@code BUILDER} anchor (docs/world/BUILDING-KITS.md); until
 * then, and wherever a recorded settlement has no builder, the first player to come near gets one at the market
 * square: a mason's trade (the mason's stock, with the regional layer), tagged {@value HomesteadDialogue#TAG}.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class HomesteadBuilders {
    private static final int INTERVAL = 100;
    private static final long RECHECK_TICKS = 1200;
    private static final Map<String, Long> CHECKED = new HashMap<>();

    private HomesteadBuilders() {}

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()) return;
        if ((player.tickCount + player.getId()) % INTERVAL != 0 || player.level().dimension() != Level.OVERWORLD) return;
        try {
            ensureBuilder(player.level(), player.blockPosition());
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.debug("NirnCraft builder check failed", e);
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
        AABB box = new AABB(e.x - e.radius - 16, level.getMinY(), e.z - e.radius - 16,
                e.x + e.radius + 16, (level.getMaxY()+1), e.z + e.radius + 16);
        if (!level.getEntitiesOfClass(NpcEntity.class, box, n -> n.isAlive() && n.getPersistentData().getBooleanOr(HomesteadDialogue.TAG, false)).isEmpty()) {
            return false;
        }
        BlockPos at = spot(level, e);
        if (at == null) return false;
        NpcEntity npc = Npcs.create(level, at, NpcRole.MERCHANT);
        if (npc == null) return false;
        npc.setPersistenceRequired();
        String first = npc.getName().getString();
        npc.setCustomName(Component.translatable("homestead.nirncraft.builder.title", first));
        CompoundTag pd = npc.getPersistentData();
        pd.putBoolean(HomesteadDialogue.TAG, true);
        pd.putString("nirncraft_trade", "builder");
        pd.putString(Npcs.PROFESSION_KEY, "minecraft:mason");
        CompoundTag shop = new CompoundTag();
        ListTag pools = new ListTag();
        pools.add(StringTag.valueOf("mason"));
        shop.put("pools", pools);
        shop.putInt("level", 3);
        pd.put(Shop.KEY, shop);
        pd.putBoolean("nirncraft_settler", true);
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
