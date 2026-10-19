package com.nirncraft.client.pinned;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.model.data.ModelData;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a pin keeps of the client level, taken in one tick: the box's blocks, light and blended tints
 * (PinVolume), the model data of its block entities, the dimension's face shading, and (rendered by their own
 * renderers) its block entities and, when asked, its entities. Entities and block entities are kept as the render
 * states their renderers extract (EntityRenderDispatcher.extractEntity, BlockEntityRenderer.extractRenderState), once,
 * on the next frame's ExtractLevelRenderStateEvent: frozen in the pose, animation and light they had, drawn by the same
 * renderers wherever the copy goes. No entity is created or kept alive for it.
 */
final class PinSnapshot {
    final String name;
    final PinVolume volume;
    final boolean withEntities;
    final CardinalLighting cardinalLighting;
    final int minY, height;
    final long dayTime, gameTime;
    final Long2ObjectOpenHashMap<ModelData> modelData = new Long2ObjectOpenHashMap<>();
    /** Mods' own colour resolvers: what the level answered for them at the pin (filled while the pin is meshed). */
    private final Map<ColorResolver, Long2IntOpenHashMap> otherTints = new IdentityHashMap<>();
    /** The level the pin came from while it is still current (null once it is gone: no more live answers). */
    ClientLevel source;

    /** Taken, waiting for the next frame's extraction: the live objects, dropped once their states exist. */
    private List<Entity> pendingEntities = new ArrayList<>();
    private List<BlockEntity> pendingBlockEntities = new ArrayList<>();
    final List<PinnedEntity> entities = new ArrayList<>();
    final List<PinnedBlockEntity> blockEntities = new ArrayList<>();
    volatile boolean extracted;

    /** An entity as its renderer extracted it, and what Minecraft's own culling would need to decide on it. */
    record PinnedEntity(String type, EntityRenderState state, Entity entity, double x, double y, double z) {
        /** Whether Minecraft draws the entity at this distance (Entity.shouldRenderAtSqrDistance, the entity distance option). */
        boolean vanillaDraws(double distance) {
            return entity.shouldRenderAtSqrDistance(distance * distance);
        }
    }

    /** A block entity as its renderer extracted it, with that renderer's view distance (BlockEntityRenderer.shouldRender). */
    record PinnedBlockEntity(String type, BlockPos pos, BlockEntityRenderState state, int viewDistance) {
        boolean vanillaDraws(double distance) {
            return distance < viewDistance;
        }
    }

    private PinSnapshot(String name, PinBox box, ClientLevel level, boolean withEntities) {
        this.name = name;
        this.volume = new PinVolume(box);
        this.withEntities = withEntities;
        this.cardinalLighting = level.cardinalLighting();
        this.minY = level.getMinY();
        this.height = level.getHeight();
        this.dayTime = level.getOverworldClockTime();
        this.gameTime = level.getGameTime();
        this.source = level;
    }

    /** The box of the client level now. Client thread. */
    static PinSnapshot take(String name, PinBox box, ClientLevel level, boolean withEntities) {
        PinSnapshot p = new PinSnapshot(name, box, level, withEntities);
        PinVolume v = p.volume;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        ColorResolver[] resolvers = {BiomeColors.GRASS_COLOR_RESOLVER, BiomeColors.FOLIAGE_COLOR_RESOLVER,
                BiomeColors.WATER_COLOR_RESOLVER, BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER};
        PinBox lb = v.lightBox;
        for (int y = lb.y0(); y <= lb.y1(); y++)
            for (int z = lb.z0(); z <= lb.z1(); z++)
                for (int x = lb.x0(); x <= lb.x1(); x++) {
                    m.set(x, y, z);
                    v.setLight(x, y, z, level.getBrightness(LightLayer.SKY, m), level.getBrightness(LightLayer.BLOCK, m));
                    if (!box.contains(x, y, z)) continue;
                    var st = level.getBlockState(m);
                    v.setState(x, y, z, Block.getId(st));
                    for (int k = 0; k < PinVolume.TINTS; k++) v.setTint(k, x, y, z, level.getBlockTint(m, resolvers[k]));
                    if (st.hasBlockEntity()) {
                        BlockEntity be = level.getBlockEntity(m);
                        if (be != null) p.pendingBlockEntities.add(be);
                        ModelData md = level.getModelData(m);
                        if (md != null && md != ModelData.EMPTY) p.modelData.put(m.asLong(), md);
                    }
                }
        if (withEntities) {
            AABB bb = new AABB(box.x0(), box.y0(), box.z0(), box.x1() + 1, box.y1() + 1, box.z1() + 1);
            // the entities whose position is in the box (not merely touching it); the camera's own player never
            for (Entity e : level.getEntities((Entity) null, bb, e -> !(e instanceof Player) && box.contains(
                    net.minecraft.util.Mth.floor(e.getX()), net.minecraft.util.Mth.floor(e.getY()), net.minecraft.util.Mth.floor(e.getZ()))))
                p.pendingEntities.add(e);
        }
        return p;
    }

    /** What the level answered for a mod's colour resolver here (asked while the pin's level is still current). */
    int otherTint(BlockPos pos, ColorResolver resolver) {
        PinBox b = volume.box;
        BlockPos at = new BlockPos(Math.max(b.x0(), Math.min(b.x1(), pos.getX())), Math.max(b.y0(), Math.min(b.y1(), pos.getY())),
                Math.max(b.z0(), Math.min(b.z1(), pos.getZ())));
        Long2IntOpenHashMap memo = otherTints.computeIfAbsent(resolver, r -> {
            Long2IntOpenHashMap mm = new Long2IntOpenHashMap();
            mm.defaultReturnValue(Integer.MIN_VALUE);
            return mm;
        });
        int c = memo.get(at.asLong());
        if (c != Integer.MIN_VALUE) return c;
        ClientLevel l = source;
        if (l == null || !l.hasChunkAt(at))  // never asked while the level was here: the grass tint stands in
            return volume.tint(PinVolume.TINT_GRASS, at.getX(), at.getY(), at.getZ());
        c = l.getBlockTint(at, resolver);
        memo.put(at.asLong(), c);
        return c;
    }

    /** Whether the render states still have to be extracted (the next frame does it). */
    boolean pending() {
        return !extracted;
    }

    /**
     * The render states of the pin's entities and block entities, by their own renderers, as the frame's extraction
     * would make them (the dispatchers are prepared for this frame's camera). Render thread, inside
     * ExtractLevelRenderStateEvent. Name tags are dropped: the copy is a picture of the world, and the offline reference
     * draws no text. Block entities skip their renderer's own distance check (shouldRender): a pin draws them at any
     * distance, and the receipt says whether Minecraft would.
     */
    void extract(EntityRenderDispatcher entities, BlockEntityRenderDispatcher blockEntities, Vec3 camera, float partialTicks) {
        if (extracted) return;
        for (Entity e : pendingEntities) {
            try {
                EntityRenderState st = entities.extractEntity(e, partialTicks);
                st.nameTag = null;
                this.entities.add(new PinnedEntity(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
                        st, e, st.x, st.y, st.z));
            } catch (RuntimeException ex) {
                com.nirncraft.NirnCraft.LOGGER.warn("pin {}: entity {} not extracted: {}", name, e, ex.toString());
            }
        }
        for (BlockEntity be : pendingBlockEntities) {
            try {
                PinnedBlockEntity pb = extractBlockEntity(blockEntities, be, camera, partialTicks);
                if (pb != null) this.blockEntities.add(pb);
            } catch (RuntimeException ex) {
                com.nirncraft.NirnCraft.LOGGER.warn("pin {}: block entity at {} not extracted: {}", name, be.getBlockPos(), ex.toString());
            }
        }
        pendingEntities = List.of();
        pendingBlockEntities = List.of();
        extracted = true;
    }

    private static <E extends BlockEntity, S extends BlockEntityRenderState> PinnedBlockEntity extractBlockEntity(
            BlockEntityRenderDispatcher dispatcher, E be, Vec3 camera, float partialTicks) {
        BlockEntityRenderer<E, S> renderer = dispatcher.getRenderer(be);
        if (renderer == null || !be.hasLevel() || !be.getType().isValid(be.getBlockState())) return null;
        S state = renderer.createRenderState();
        renderer.extractRenderState(be, state, partialTicks, camera, null);
        return new PinnedBlockEntity(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString(),
                be.getBlockPos().immutable(), state, renderer.getViewDistance());
    }

    int pendingEntityCount() {
        return pendingEntities.size();
    }

    int pendingBlockEntityCount() {
        return pendingBlockEntities.size();
    }
}
