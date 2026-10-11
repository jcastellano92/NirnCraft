package com.skycraft.dungeons.qa;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.skycraft.dungeons.puzzle.ClawKeyholeBlock;
import com.skycraft.dungeons.puzzle.PuzzleLeverBlock;
import com.skycraft.dungeons.puzzle.SymbolStoneBlock;
import com.skycraft.dungeons.world.DormantMobs;
import com.skycraft.dungeons.world.DungeonPiece;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Writes one painted dungeon (the in-memory {@link QaLevel}, nothing generated in the world) to
 * {@code qa-exports/<id>.json.gz} for the offline tools ({@code tools/qa/render_dungeon.py},
 * {@code tools/qa/dungeon_reach.py}). Server-side only.
 * <p>
 * Format 1: {@code box} = [x1,y1,z1,x2,y2,z2]; {@code palette} = block state strings; {@code coll} = one character per
 * palette entry (a no collision, l low step, s stairs, f full, t tall (fence, wall), d door or gate that opens by
 * hand, m mechanism-locked (iron door, claw door ring), c climbable, w water, v lava); {@code rle} = base64 of
 * (palette index, run length) varint pairs in y, z, x order (x fastest). The rest is semantic markers.
 */
final class QaExport {
    private QaExport() {}

    static String safeName(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static Path write(QaDungeon d, String id, Path dir) throws IOException {
        QaLevel lvl = d.lvl;
        BoundingBox all = null;
        for (StructurePiece sp : d.pieces) {
            BoundingBox b = sp.getBoundingBox();
            all = all == null ? new BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()) : all.encapsulate(b);
        }
        if (all == null) throw new IOException("no pieces");
        BoundingBox a = lvl.area;
        int x1 = Math.max(a.minX(), all.minX() - 3), x2 = Math.min(a.maxX(), all.maxX() + 3);
        int z1 = Math.max(a.minZ(), all.minZ() - 3), z2 = Math.min(a.maxZ(), all.maxZ() + 3);
        int y1 = Math.max(a.minY(), all.minY() - 2), y2 = Math.min(a.maxY(), all.maxY() + 6);

        Map<BlockState, Integer> index = new HashMap<>();
        List<BlockState> states = new ArrayList<>();
        ByteArrayOutputStream rle = new ByteArrayOutputStream();
        int run = 0, cur = -1;
        List<String> kinds = new ArrayList<>();
        JsonArray mech = new JsonArray();
        for (int y = y1; y <= y2; y++) {
            for (int z = z1; z <= z2; z++) {
                for (int x = x1; x <= x2; x++) {
                    BlockState s = lvl.get(x, y, z);
                    Integer pi = index.get(s);
                    if (pi == null) {
                        pi = states.size();
                        index.put(s, pi);
                        states.add(s);
                        kinds.add(mechanismKind(s));
                    }
                    String k = kinds.get(pi);
                    if (k != null) {
                        JsonObject m = new JsonObject();
                        m.addProperty("kind", k);
                        m.addProperty("x", x);
                        m.addProperty("y", y);
                        m.addProperty("z", z);
                        mech.add(m);
                    }
                    if (pi == cur) {
                        run++;
                    } else {
                        if (run > 0) {
                            varint(rle, cur);
                            varint(rle, run);
                        }
                        cur = pi;
                        run = 1;
                    }
                }
            }
        }
        if (run > 0) {
            varint(rle, cur);
            varint(rle, run);
        }

        JsonObject o = new JsonObject();
        o.addProperty("format", 1);
        o.addProperty("id", id);
        o.addProperty("label", d.sample.label());
        o.addProperty("structure", d.sample.structureId().toString());
        o.addProperty("theme", String.valueOf(d.m.get("theme")));
        o.addProperty("kit", String.valueOf(d.m.get("kit")));
        o.addProperty("special", String.valueOf(d.m.get("special")));
        o.addProperty("surfaceY", d.surfaceY);
        JsonArray box = new JsonArray();
        for (int v : new int[]{x1, y1, z1, x2, y2, z2}) box.add(v);
        o.add("box", box);
        StringBuilder coll = new StringBuilder();
        JsonArray pal = new JsonArray();
        for (BlockState s : states) {
            pal.add(BlockStateParser.serialize(s));
            coll.append(shapeClass(lvl, s));
        }
        o.add("palette", pal);
        o.addProperty("coll", coll.toString());
        o.addProperty("rle", Base64.getEncoder().encodeToString(rle.toByteArray()));

        // pieces, entrances, set pieces
        JsonArray pieces = new JsonArray();
        JsonArray entrances = new JsonArray();
        for (StructurePiece sp : d.pieces) {
            if (!(sp instanceof DungeonPiece dp)) continue;
            BoundingBox b = dp.getBoundingBox();
            JsonObject p = new JsonObject();
            p.addProperty("kind", dp.kind.name().toLowerCase());
            p.addProperty("role", QaDungeon.roleName(dp.role));
            p.addProperty("flags", dp.flags);
            p.addProperty("floor", dp.floor);
            p.addProperty("aux", dp.aux);
            JsonArray bb = new JsonArray();
            for (int v : new int[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}) bb.add(v);
            p.add("box", bb);
            Set<String> f = d.features.get(b);
            if (f != null && !f.isEmpty()) {
                JsonArray fa = new JsonArray();
                f.forEach(fa::add);
                p.add("features", fa);
            }
            pieces.add(p);
            if (dp.kind == DungeonPiece.Kind.TUNNEL || dp.kind == DungeonPiece.Kind.PORTAL) {
                JsonObject e = new JsonObject();
                int t = dp.tunnelLength();
                e.addProperty("x", dp.frameX(t, 0));
                e.addProperty("y", dp.aux);
                e.addProperty("z", dp.frameZ(t, 0));
                e.addProperty("inner_x", dp.frameX(1, 0));
                e.addProperty("inner_y", dp.floor);
                e.addProperty("inner_z", dp.frameZ(1, 0));
                entrances.add(e);
            }
        }
        o.add("pieces", pieces);
        o.add("entrances", entrances);

        // mobs and minecarts
        JsonArray ents = new JsonArray();
        for (Entity e : lvl.spawned) {
            JsonObject m = new JsonObject();
            m.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            m.addProperty("x", e.getBlockX());
            m.addProperty("y", e.getBlockY());
            m.addProperty("z", e.getBlockZ());
            if (e instanceof LivingEntity le) {
                m.addProperty("dormant", DormantMobs.isDormant(le));
                m.addProperty("boss", e.getPersistentData().getBoolean(com.skycraft.dungeons.DungeonTelemetry.BOSS_TAG));
                m.addProperty("elite", e.getPersistentData().getBoolean("skycraft_dungeon_elite"));
                if (e.hasCustomName()) m.addProperty("name", e.getCustomName().getString());
            }
            ents.add(m);
        }
        o.add("entities", ents);

        // block entities: containers, lecterns, spawners
        JsonArray chests = new JsonArray(), lecterns = new JsonArray(), spawners = new JsonArray();
        for (Map.Entry<BlockPos, BlockEntity> e : lvl.blockEntities().entrySet()) {
            BlockPos p = e.getKey();
            BlockEntity be = e.getValue();
            JsonObject m = new JsonObject();
            m.addProperty("x", p.getX());
            m.addProperty("y", p.getY());
            m.addProperty("z", p.getZ());
            m.addProperty("block", BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString());
            if (be instanceof LecternBlockEntity lec) {
                m.addProperty("book", lec.hasBook());
                lecterns.add(m);
            } else if (be instanceof Container) {
                CompoundTag tag = be.saveWithoutMetadata();
                if (tag.contains("LootTable")) m.addProperty("loot", tag.getString("LootTable"));
                int n = 0;
                for (ItemStack st : QaDungeon.items(be)) if (!st.isEmpty()) n++;
                m.addProperty("items", n);
                chests.add(m);
            } else if (BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).getPath().contains("spawner")) {
                CompoundTag tag = be.saveWithoutMetadata();
                String ent = "?";
                if (tag.contains("SpawnData") && tag.getCompound("SpawnData").contains("entity")) {
                    ent = tag.getCompound("SpawnData").getCompound("entity").getString("id");
                }
                m.addProperty("entity", ent);
                spawners.add(m);
            }
        }
        o.add("containers", chests);
        o.add("lecterns", lecterns);
        o.add("spawners", spawners);
        o.add("mechanisms", mech);

        Files.createDirectories(dir);
        Path out = dir.resolve(safeName(id) + ".json.gz");
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(out)), StandardCharsets.UTF_8)) {
            new Gson().toJson(o, w);
        }
        return out;
    }

    private static void varint(ByteArrayOutputStream o, int v) {
        while (v >= 0x80) {
            o.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        o.write(v);
    }

    /** Traps and puzzle parts, for the marker layer; null for everything else. */
    private static String mechanismKind(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof BasePressurePlateBlock) return "plate";
        if (b instanceof DispenserBlock) return "dispenser";
        if (b instanceof TripWireHookBlock) return "tripwire";
        if (b instanceof PuzzleLeverBlock) return "puzzle_lever";
        if (b instanceof LeverBlock) return "lever";
        if (b instanceof ClawKeyholeBlock) return "keyhole";
        if (b instanceof SymbolStoneBlock ss && ss.rotating()) return "symbol_ring";
        if (BuiltInRegistries.BLOCK.getKey(b).getPath().equals("word_wall")) return "word_wall";
        return null;
    }

    private static char shapeClass(QaLevel lvl, BlockState s) {
        if (s.isAir()) return 'a';
        Block b = s.getBlock();
        VoxelShape sh = s.getCollisionShape(lvl, BlockPos.ZERO);
        if (!s.getFluidState().isEmpty() && sh.isEmpty()) return s.getFluidState().is(FluidTags.LAVA) ? 'v' : 'w';
        if (s.is(BlockTags.CLIMBABLE)) return 'c';
        if (b instanceof ClawKeyholeBlock) return 'm';
        if (b instanceof SymbolStoneBlock ss && ss.rotating()) return 'm';
        if (b instanceof DoorBlock) return s.is(BlockTags.WOODEN_DOORS) ? 'd' : 'm';
        if (b instanceof FenceGateBlock) return 'd';
        if (b instanceof TrapDoorBlock && s.getValue(TrapDoorBlock.OPEN)) return 'd';
        if (sh.isEmpty()) return 'a';
        if (b instanceof StairBlock) return 's';
        double top = sh.max(Direction.Axis.Y);
        if (top > 1.0) return 't';
        if (top <= 0.55) return 'l';
        return 'f';
    }
}
