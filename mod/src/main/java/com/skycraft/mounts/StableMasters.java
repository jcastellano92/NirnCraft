package com.skycraft.mounts;

import com.skycraft.Skycraft;
import com.skycraft.society.NpcNames;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Npcs;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

/**
 * Puts a stablemaster wherever mounts are sold: at every settlement stable (the {@code STABLES} anchor of Skyrim's
 * city stables, roadside inns and farms), and, in the cities and capitals of the other provinces, a horse trader at
 * the market square. They carry the fauna module's {@code skycraft_stable_master} flag, so their dialogue is the
 * stables' ({@link com.skycraft.fauna.Stables}). One per settlement; a dead one is replaced after a day.
 */
public final class StableMasters {
    private static final long RESPAWN = 24000L;

    private StableMasters() {}

    static void tick(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        Optional<SettlementAnchors.Entry> here = SettlementAnchors.near(level, player.blockPosition(), 16);
        if (here.isEmpty()) return;
        SettlementAnchors.Entry e = here.get();
        BlockPos at = spot(e);
        if (at == null || !level.isPositionEntityTicking(at)) return;
        if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(at).inflate(40, 16, 40),
                l -> l.isAlive() && l.getPersistentData().getBoolean("skycraft_stable_master")).isEmpty()) return;
        Data data = Data.get(level);
        long now = level.getGameTime();
        if (data.tag.contains(e.key) && now - data.tag.getLong(e.key) < RESPAWN) return;
        NpcEntity npc = Npcs.create(level, standing(level, at), NpcRole.FARMER);
        if (npc == null) return;
        npc.setPersistenceRequired();
        String name = NpcNames.generate(NpcNames.raceFor(NpcRole.FARMER, level.getRandom()), npc.isFemale(), level.getRandom());
        npc.setCustomName(Component.translatable("entity.skycraft.stablemaster", name));
        npc.getPersistentData().putBoolean("skycraft_stable_master", true);
        npc.getPersistentData().putBoolean("skycraft_settler", true);
        npc.setHome(at, 8);
        if (level.addFreshEntity(npc)) {
            data.tag.putLong(e.key, now);
            data.setDirty();
            Skycraft.LOGGER.debug("Stablemaster for {} at {}", e.key, at);
        }
    }

    /** Where the stablemaster stands: the stable, else (provincial cities) beside the market square. */
    static BlockPos spot(SettlementAnchors.Entry e) {
        Anchor st = e.first(AnchorType.STABLES);
        if (st != null) return st.pos();
        Optional<Place> p = e.place();
        if (p.isEmpty() || p.get().type() != PlaceType.CITY && p.get().type() != PlaceType.CAPITAL) return null;
        if (p.get().regionId().startsWith("skyrim/")) return null; // Skyrim's cities have their own stables outside
        Anchor m = e.first(AnchorType.MEETING);
        return m == null ? null : m.pos().offset(4, 0, 4);
    }

    static BlockPos standing(ServerLevel level, BlockPos p) {
        for (int dy = 0; dy < 4; dy++) {
            BlockPos q = p.above(dy);
            if (level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty()) return q;
        }
        return p;
    }

    /** When each settlement last got a stablemaster. */
    static final class Data extends SavedData {
        final CompoundTag tag;

        Data(CompoundTag tag) {
            this.tag = tag;
        }

        static Data get(ServerLevel level) {
            return level.getServer().overworld().getDataStorage().computeIfAbsent(
                    t -> new Data(t.getCompound("spawned")), () -> new Data(new CompoundTag()), "skycraft_stablemasters");
        }

        @Override
        public CompoundTag save(CompoundTag out) {
            out.put("spawned", tag);
            return out;
        }
    }
}
