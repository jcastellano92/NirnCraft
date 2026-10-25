package com.nirncraft.atlas;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The block entities and mobs of the recorded structures, per chunk (AtlasSideCapture's output), kept beside the tapes
 * ({@code <id>.side.gz} in the overlay cache) and handed to the integrated server's chunks when they are filled from the
 * GPU store (AtlasServerGen): the GPU's tapes carry blocks only, so a chest's loot table, a sign's text and a dungeon's
 * guards come from here. Any thread.
 */
final class AtlasSideOps {
    private AtlasSideOps() {}

    /** Per structure id the chunks' ops it contributes (a chunk may be reached by more than one structure). */
    private static final Map<String, Map<Long, List<CompoundTag>>> BY_ID = new ConcurrentHashMap<>();

    static void register(String id, Map<Long, List<CompoundTag>> ops) {
        BY_ID.put(id, ops);
    }

    /** The ops of chunk (cx, cz) from every structure that reaches it (copies: the replay may change them). */
    static List<CompoundTag> at(int cx, int cz) {
        long k = net.minecraft.world.level.ChunkPos.pack(cx, cz);
        List<CompoundTag> out = null;
        for (Map<Long, List<CompoundTag>> m : BY_ID.values()) {
            List<CompoundTag> l = m.get(k);
            if (l == null) continue;
            if (out == null) out = new ArrayList<>();
            for (CompoundTag t : l) out.add(t.copy());
        }
        return out == null ? List.of() : out;
    }

    static void clear() {
        BY_ID.clear();
    }

    static void write(Path file, Map<Long, List<CompoundTag>> ops) throws IOException {
        CompoundTag root = new CompoundTag();
        ListTag chunks = new ListTag();
        for (Map.Entry<Long, List<CompoundTag>> e : ops.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("k", e.getKey());
            ListTag l = new ListTag();
            l.addAll(e.getValue());
            c.put("ops", l);
            chunks.add(c);
        }
        root.put("chunks", chunks);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(root, tmp);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** The side file, or null when there is none (the tapes were recorded without it: recorded again). */
    static Map<Long, List<CompoundTag>> read(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        Map<Long, List<CompoundTag>> out = new HashMap<>();
        for (int i = 0; i < root.getListOrEmpty("chunks").size(); i++) {
            CompoundTag c = root.getListOrEmpty("chunks").getCompoundOrEmpty(i);
            List<CompoundTag> l = new ArrayList<>();
            ListTag ops = c.getListOrEmpty("ops");
            for (int j = 0; j < ops.size(); j++) l.add(ops.getCompoundOrEmpty(j));
            out.put(c.getLongOr("k", 0L), l);
        }
        return out;
    }
}
