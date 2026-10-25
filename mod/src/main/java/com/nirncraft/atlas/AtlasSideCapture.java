package com.nirncraft.atlas;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a recorded structure leaves besides blocks: its block entities (a chest's loot table and seed, a sign's text, a
 * lectern's book, a spawner) and the mobs its builders place. The overlay recording runs the real builders over proxy
 * levels backed by proto chunks (AtlasOverlayTapes.Window); with a capture on, the proxy makes the block entities a
 * WorldGenRegion would make, hands the builders the real server level for the mobs they create, and keeps the mobs the way
 * a proto chunk keeps them (their NBT). {@link #finish} lists both per chunk, as NBT with a {@code kind} ("be" or
 * "entity"), for the cache's side file (AtlasSideOps) and the integrated server's replay (AtlasChunkGenerator).
 */
final class AtlasSideCapture {
    final ServerLevel level;

    AtlasSideCapture(ServerLevel level) {
        this.level = level;
    }

    RegistryAccess registries() {
        return level.registryAccess();
    }

    /** A block was written to the proto chunk (the proxy's setBlock): its block entity made, or the old one dropped. */
    void blockSet(ProtoChunk c, BlockPos p, BlockState s) {
        if (s.hasBlockEntity() && s.getBlock() instanceof EntityBlock eb) {
            BlockEntity be = eb.newBlockEntity(p, s);
            if (be != null) {
                c.setBlockEntity(be);
                return;
            }
        }
        c.removeBlockEntity(p);
    }

    DifficultyInstance difficulty() {
        return new DifficultyInstance(Difficulty.NORMAL, 0L, 0L, 0f);
    }

    /** Per chunk (ChunkPos.pack) the block entities and mobs the window's chunks hold, as NBT with a kind. */
    Map<Long, List<CompoundTag>> finish(Iterable<ProtoChunk> chunks) {
        Map<Long, List<CompoundTag>> out = new HashMap<>();
        RegistryAccess access = registries();
        for (ProtoChunk c : chunks) {
            if (c == null) continue;
            List<CompoundTag> l = null;
            for (BlockEntity be : c.getBlockEntities().values()) {
                CompoundTag t = be.saveWithFullMetadata(access);
                t.putString("nirnKind", "be");
                if (l == null) l = new ArrayList<>();
                l.add(t);
            }
            for (CompoundTag e : c.getEntities()) {
                CompoundTag t = e.copy();
                t.putString("nirnKind", "entity");
                if (l == null) l = new ArrayList<>();
                l.add(t);
            }
            if (l != null) out.put(ChunkPos.pack(c.getPos().x(), c.getPos().z()), l);
        }
        return out;
    }

    static boolean isBe(CompoundTag t) {
        return "be".equals(t.getStringOr("nirnKind", ""));
    }

    static ListTag toList(List<CompoundTag> tags) {
        ListTag l = new ListTag();
        l.addAll(tags);
        return l;
    }
}
