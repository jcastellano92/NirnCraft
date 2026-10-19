package com.nirncraft.loot.container;

import com.nirncraft.NirnCraft;
import com.nirncraft.dig.PlacedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Where NirnCraft's loot containers come from (docs/CONTAINERS.md section 9) and the one place every other module asks
 * about loot containers.
 *
 * <ul>
 *     <li><b>Placement.</b> Builders keep placing a vanilla chest or barrel and then give it a loot table through
 *     {@link #setLootTable} (world generation's {@code worldgen.WorldgenData#setLootTable} routes here). When NirnCraft
 *     runs containers ({@link ContainersConfig#nirncraftRuns}) the chest becomes ours on the spot: the kind from the
 *     table and the block ({@link ContainerKind#forPlacement}), the style from the table's theme or the region's
 *     culture ({@link ContainerStyle}), the facing kept. Otherwise it stays vanilla with its table, as before.</li>
 *     <li><b>Conversion.</b> Whatever placed a vanilla loot chest some other way (a vanilla structure, a builder not
 *     routed here) is caught when its chunk first loads ({@code convertNewChunks}); old chunks' unopened vanilla loot
 *     chests and every Lootr container are converted on load with {@code convertExisting}. Conversions run on the
 *     server thread, a bounded number per tick, never during the chunk event itself.</li>
 *     <li><b>Questions.</b> {@link #lootTableOf} answers "what loot table does this container have" for ours and for
 *     vanilla ones alike ({@code crime.Theft}, {@code crime.Locks}, the dungeon QA).</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class LootContainers {
    /** Conversions and normalisations per level tick. */
    private static final int PER_TICK = 64;
    /** Filled from chunk loads and block-entity loads, drained on the level tick (both on the server thread today; concurrent to be safe). */
    private static final Map<ServerLevel, java.util.concurrent.ConcurrentLinkedDeque<BlockPos>> QUEUE = new java.util.concurrent.ConcurrentHashMap<>();

    private LootContainers() {}

    // ------------------------------------------------------------------ questions

    /**
     * The loot table a container belongs to: ours always know theirs; a vanilla (or Lootr) container's only while it
     * is still to be generated. Null for anything else.
     */
    @Nullable
    public static String lootTableOf(@Nullable BlockEntity be) {
        if (be instanceof LootContainerBlockEntity lc) return lc.table();
        if (be instanceof RandomizableContainerBlockEntity r && r.getLootTable() != null) return r.getLootTable().identifier().toString();
        return null;
    }

    /** True for one of NirnCraft's world loot containers (a lock candidate, finders-keepers unless its table is owned). */
    public static boolean isWorldLoot(@Nullable BlockEntity be) {
        return be instanceof LootContainerBlockEntity lc && lc.isWorldLoot();
    }

    // ------------------------------------------------------------------ placement

    /**
     * Gives the container a builder just placed at {@code pos} its loot table: ours when NirnCraft runs containers
     * (converted on the spot), the vanilla table otherwise. Safe in world generation (only {@code pos} is written).
     */
    public static void setLootTable(LevelAccessor level, BlockPos pos, Identifier table, long seed) {
        BlockEntity be = level.getBlockEntity(pos);
        if (ContainersConfig.nirncraftRuns() && convertible(level.getBlockState(pos), be)) {
            if (convert(level, pos, level.getBlockState(pos), table.toString()) != null) return;
        }
        if (be instanceof RandomizableContainerBlockEntity c) c.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, table), seed);
    }

    /**
     * Places one of our containers of {@code kind} at {@code pos} (facing {@code facing}), a world loot container of
     * {@code table}; {@code style} null picks it from the table and the region. For builders that want a particular
     * kind (a shelf, remains, an urn) whatever the config says.
     */
    @Nullable
    public static LootContainerBlockEntity place(LevelAccessor level, BlockPos pos, ContainerKind kind, Direction facing,
                                                 @Nullable Identifier table, @Nullable ContainerStyle style) {
        String t = table == null ? null : table.toString();
        ContainerStyle s = style != null ? style : styleAt(level, pos, t);
        BlockState state = stateFor(kind, s, facing, t);
        LootContainerBlockEntity be = setOurs(level, pos, state);
        if (be != null) {
            be.setWorldLoot(t);
            be.normalized();
        }
        return be;
    }

    /** Whether the block at a position is a vanilla (or Lootr) container we know how to replace. */
    static boolean convertible(BlockState state, @Nullable BlockEntity be) {
        if (!(be instanceof RandomizableContainerBlockEntity)) return false;
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL) || LootrCompat.isLootrBlock(state);
    }

    /** The style a container at {@code pos} gets: its table's theme, else the culture of the region it stands in. */
    public static ContainerStyle styleAt(LevelAccessor level, BlockPos pos, @Nullable String table) {
        ContainerStyle s = ContainerStyle.ofTable(table);
        if (s != null) return s;
        try {
            com.nirncraft.tamriel.Region r = level instanceof ServerLevelAccessor sla
                    ? com.nirncraft.tamriel.Regions.at(sla.getLevel(), pos) : com.nirncraft.tamriel.Regions.at(pos.getX(), pos.getZ());
            return ContainerStyle.ofCulture(r == null ? null : r.culture());
        } catch (RuntimeException e) {
            return ContainerStyle.NORDIC;
        }
    }

    /** The block state of a kind in a style, facing {@code facing}; shelves get their book count from the table. */
    public static BlockState stateFor(ContainerKind kind, ContainerStyle style, Direction facing, @Nullable String table) {
        BlockState s = ContainerRegistry.block(kind).defaultBlockState();
        if (s.hasProperty(LootContainerBlock.Barrel.FACING6)) {
            s = s.setValue(LootContainerBlock.Barrel.FACING6, facing);
        } else if (s.hasProperty(LootContainerBlock.FACING)) {
            s = s.setValue(LootContainerBlock.FACING, facing.getAxis().isHorizontal() ? facing : Direction.NORTH);
        }
        if (s.hasProperty(LootContainerBlock.STYLE)) s = s.setValue(LootContainerBlock.STYLE, style);
        if (s.hasProperty(LootContainerBlock.Bookshelf.BOOKS)) s = s.setValue(LootContainerBlock.Bookshelf.BOOKS, shelfBooks(table));
        return s;
    }

    /** How full a world shelf looks: a poor home's shelf is sparse, a scholar's full. */
    static int shelfBooks(@Nullable String table) {
        if (table == null) return 1;
        if (table.contains("poor") || table.contains("ruin")) return 1;
        if (table.contains("common")) return 2;
        return 3;
    }

    /**
     * Replaces the vanilla or Lootr container at {@code pos} with ours, keeping its facing; returns the new block entity
     * (null if the position cannot be written). The old container's table is dropped first so nothing is generated
     * or spilled by its removal.
     */
    @Nullable
    static LootContainerBlockEntity convert(LevelAccessor level, BlockPos pos, BlockState source, @Nullable String table) {
        ContainerStyle style = styleAt(level, pos, table);
        ContainerKind kind = ContainerKind.forPlacement(table, source, style);
        Direction facing = source.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? source.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : source.hasProperty(BlockStateProperties.FACING) ? source.getValue(BlockStateProperties.FACING) : Direction.NORTH;
        if (kind == ContainerKind.BARREL && !source.is(Blocks.BARREL) && !source.hasProperty(BlockStateProperties.FACING)) facing = Direction.UP;
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity old) {
            old.setLootTable((ResourceKey<LootTable>) null);
            old.clearContent();
        }
        LootContainerBlockEntity be = setOurs(level, pos, stateFor(kind, style, facing, table));
        if (be != null) {
            be.setWorldLoot(table);
            be.normalized();
        }
        return be;
    }

    /** Writes our block at {@code pos} and returns its fresh block entity, in world generation and at run time alike. */
    @Nullable
    private static LootContainerBlockEntity setOurs(LevelAccessor level, BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel server) {
            // a live chunk: the old block entity goes without its side effects (no drops, no Lootr hooks)
            if (!server.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS)) return null;
        } else {
            // a chunk being generated: a proto chunk keeps the old block entity unless it is removed first
            level.getChunk(pos).removeBlockEntity(pos);
            if (!level.setBlock(pos, state, Block.UPDATE_CLIENTS)) return null;
        }
        return level.getBlockEntity(pos) instanceof LootContainerBlockEntity be ? be : null;
    }

    // ------------------------------------------------------------------ conversion on chunk load

    /** A block entity loaded from data we did not write asks to be fixed up (kind and style) on the next tick. */
    static void queueNormalize(ServerLevel level, BlockPos pos) {
        QUEUE.computeIfAbsent(level, k -> new java.util.concurrent.ConcurrentLinkedDeque<>()).add(pos.immutable());
    }

    @SubscribeEvent
    public static void chunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!ContainersConfig.nirncraftRuns()) return;
        boolean fresh = event.isNewChunk();
        if (!(fresh ? ContainersConfig.convertNewChunks() : ContainersConfig.convertExisting())) return;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (candidate(level, be, fresh)) queueNormalize(level, be.getBlockPos());
        }
    }

    /**
     * A container to convert: a vanilla chest, trapped chest or barrel whose loot table has not been generated (only
     * NirnCraft's tables unless {@code convertVanillaTables}), or any Lootr container; never one a player placed.
     */
    static boolean candidate(ServerLevel level, BlockEntity be, boolean fresh) {
        if (!convertible(be.getBlockState(), be)) return false;
        boolean lootr = LootrCompat.isLootrBlock(be.getBlockState());
        String table = lootTableOf(be);
        if (table == null && !lootr) return false;
        if (!lootr && !table.startsWith(NirnCraft.MODID + ":") && !ContainersConfig.convertVanillaTables()) return false;
        return fresh || !PlacedBlocks.isPlayerPlaced(level, be.getBlockPos());
    }

    @SubscribeEvent
    public static void levelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        java.util.concurrent.ConcurrentLinkedDeque<BlockPos> q = QUEUE.get(level);
        if (q == null) return;
        for (int i = 0; i < PER_TICK && !q.isEmpty(); i++) {
            BlockPos pos = q.pollFirst();
            if (pos == null) break;
            if (!level.isLoaded(pos)) continue;
            try {
                process(level, pos);
            } catch (RuntimeException e) {
                NirnCraft.LOGGER.warn("Loot container at {} could not be converted: {}", pos, e.toString());
            }
        }
        if (q.isEmpty()) QUEUE.remove(level);
    }

    @SubscribeEvent
    public static void levelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) QUEUE.remove(level);
    }

    /** Converts a vanilla/Lootr container, or fixes the kind and style of one of ours loaded from Lootr's data. */
    static void process(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof LootContainerBlockEntity ours) {
            if (!ours.pendingNormalize()) return;
            String table = ours.table();
            ContainerStyle style = styleAt(level, pos, table);
            ContainerKind kind = ours.kind() == ContainerKind.CHEST || ours.kind() == ContainerKind.BARREL
                    ? ContainerKind.forPlacement(table, ours.kind() == ContainerKind.BARREL ? Blocks.BARREL.defaultBlockState() : null, style)
                    : ours.kind();
            Direction facing = state.hasProperty(LootContainerBlock.FACING) ? state.getValue(LootContainerBlock.FACING)
                    : state.hasProperty(LootContainerBlock.Barrel.FACING6) ? state.getValue(LootContainerBlock.Barrel.FACING6) : Direction.NORTH;
            BlockState want = stateFor(kind, style, facing, table);
            if (want.getBlock() == state.getBlock()) {
                if (!want.equals(state)) level.setBlock(pos, want, Block.UPDATE_CLIENTS);
                ours.normalized();
                return;
            }
            // a different kind (a Lootr chest that holds a boss hoard): a new block, the same identity
            LootContainerBlockEntity snapshot = new LootContainerBlockEntity(pos, state);
            snapshot.copyFrom(ours);
            LootContainerBlockEntity fresh = setOurs(level, pos, want);
            if (fresh != null) {
                fresh.copyFrom(snapshot);
                fresh.normalized();
            }
            return;
        }
        if (be == null || !candidate(level, be, true)) return;
        convert(level, pos, state, lootTableOf(be));
    }

    /** Converts every candidate in loaded chunks within {@code radius} chunks of {@code center} now (the command). */
    public static int convertAround(ServerLevel level, BlockPos center, int radius) {
        int n = 0;
        net.minecraft.world.level.ChunkPos c = net.minecraft.world.level.ChunkPos.containing(center);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(c.x() + dx, c.z() + dz);
                if (chunk == null) continue;
                for (BlockEntity be : java.util.List.copyOf(chunk.getBlockEntities().values())) {
                    if (be instanceof LootContainerBlockEntity ours ? ours.pendingNormalize() : candidate(level, be, false)) {
                        process(level, be.getBlockPos());
                        n++;
                    }
                }
            }
        }
        return n;
    }
}
