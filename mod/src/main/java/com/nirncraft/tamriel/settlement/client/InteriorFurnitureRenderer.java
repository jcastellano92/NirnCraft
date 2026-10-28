package com.nirncraft.tamriel.settlement.client;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks;
import com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.*;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.util.concurrent.ConcurrentHashMap;

import static com.nirncraft.tamriel.settlement.client.ShopSignModels.*;

/** Authored furniture and occupied bookshelf geometry on native extracted block entity states. */
@EventBusSubscriber(modid="nirncraft",value=Dist.CLIENT)
public final class InteriorFurnitureRenderer {
    private static final RenderType CUTOUT = RenderTypes.entityCutout(ShopSignRenderer.ATLAS);
    private static final ShopSignMesh[] EMPTY_MESHES = new ShopSignMesh[0];
    private static final ShopSignMesh[] BOOK_SLOT_MESHES = new ShopSignMesh[6];
    private static final ShopSignMesh[][] BOOK_MASK_MESHES = new ShopSignMesh[64][];
    private static final ConcurrentHashMap<ShopSignMesh, ShopSignMesh[]> SINGLE_MESH_CACHE = new ConcurrentHashMap<>();

    static {
        BOOK_MASK_MESHES[0] = EMPTY_MESHES;
    }

    private static ShopSignMesh[] singleMesh(ShopSignMesh m) {
        return m == null ? EMPTY_MESHES : SINGLE_MESH_CACHE.computeIfAbsent(m, k -> new ShopSignMesh[]{k});
    }

    public static class State extends BlockEntityRenderState { Direction facing;ShopSignMesh[] meshes; }
    @SubscribeEvent public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(FurnitureBlocks.SKY_FURNITURE_BE.get(),FurnitureBER::new);
        event.registerBlockEntityRenderer(FurnitureBlocks.SKY_FURNITURE_CONTAINER_BE.get(),FurnitureContainerBER::new);
        event.registerBlockEntityRenderer(BlockEntityTypes.CHISELED_BOOKSHELF,ChiseledBookshelfBER::new);
    }
    private abstract static class MeshRenderer<T extends BlockEntity> implements BlockEntityRenderer<T,State> {
        @Override public State createRenderState(){return new State();}
        protected abstract ShopSignMesh[] meshes(T be);
        @Override public void extractRenderState(T be,State state,float partial,Vec3 camera,ModelFeatureRenderer.CrumblingOverlay breaking) {
            BlockEntityRenderer.super.extractRenderState(be,state,partial,camera,breaking);
            var block=be.getBlockState();
            state.facing=block.hasProperty(HorizontalDirectionalBlock.FACING)?block.getValue(HorizontalDirectionalBlock.FACING):Direction.SOUTH;
            state.meshes=meshes(be);
        }
        @Override public void submit(State state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera) {
            ShopSignMesh[] meshes = state.meshes;
            if(meshes==null||meshes.length==0)return;
            int light = state.lightCoords;
            pose.pushPose();pose.translate(.5,.5,.5);pose.rotateDegrees(Axis.YP,-state.facing.toYRot());
            if (meshes.length == 1) {
                ShopSignMesh mesh = meshes[0];
                collector.submitCustomGeometry(pose, CUTOUT, (p, v) -> mesh.render(p, v, light));
            } else {
                collector.submitCustomGeometry(pose, CUTOUT, (p, v) -> {
                    for (int i = 0, len = meshes.length; i < len; i++) meshes[i].render(p, v, light);
                });
            }
            pose.popPose();
        }
    }
    public static final class FurnitureBER extends MeshRenderer<FurnitureBlockEntity> {
        public FurnitureBER(BlockEntityRendererProvider.Context ctx){}
        @Override protected ShopSignMesh[] meshes(FurnitureBlockEntity be){return singleMesh(InteriorFurnitureModels.get(be.culture(),be.kind(),be.isEmpty()));}
    }
    public static final class FurnitureContainerBER extends MeshRenderer<FurnitureContainerBlockEntity> {
        public FurnitureContainerBER(BlockEntityRendererProvider.Context ctx){}
        @Override protected ShopSignMesh[] meshes(FurnitureContainerBlockEntity be){return singleMesh(InteriorFurnitureModels.get(be.culture(),be.kind(),false));}
    }
    public static final class ChiseledBookshelfBER extends MeshRenderer<ChiseledBookShelfBlockEntity> {
        public ChiseledBookshelfBER(BlockEntityRendererProvider.Context ctx){}
        @Override protected ShopSignMesh[] meshes(ChiseledBookShelfBlockEntity be){
            var block=be.getBlockState();
            int mask = 0;
            for(int i=0;i<6;i++){
                var property=ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i);
                if(block.hasProperty(property)&&block.getValue(property)) mask |= (1 << i);
            }
            return getMaskMeshes(mask);
        }
        private static synchronized ShopSignMesh[] getMaskMeshes(int mask) {
            ShopSignMesh[] cached = BOOK_MASK_MESHES[mask];
            if (cached != null) return cached;
            ShopSignMesh merged = new ShopSignMesh();
            for (int i = 0; i < 6; i++) {
                if ((mask & (1 << i)) != 0) merged.addAll(getSlotMesh(i));
            }
            cached = new ShopSignMesh[]{merged};
            BOOK_MASK_MESHES[mask] = cached;
            return cached;
        }
        private static synchronized ShopSignMesh getSlotMesh(int slot) {
            if (BOOK_SLOT_MESHES[slot] != null) return BOOK_SLOT_MESHES[slot];
            ShopSignMesh m = new ShopSignMesh();
            int col = slot % 3;
            int row = slot / 3;
            float cx = (col - 1) * 4.4f;
            float y0 = (row == 0) ? 1.0f : -7.0f;
            float y1 = y0 + 5.8f;
            int[] jackets = {MAT_CRIMSON, MAT_LEATHER_WARM, MAT_COBALT, MAT_EMERALD, MAT_PURPLE_GEM, MAT_WALNUT};
            int[] bands = {MAT_GOLD, MAT_BRASS, MAT_STEEL_BRIGHT, MAT_GOLD, MAT_GOLD, MAT_COPPER};
            int jacket = jackets[slot % jackets.length];
            int band = bands[slot % bands.length];

            // Protruding 3D book spine + gilded ribs + page edge at top
            m.box(cx - 1.4f, y0, 6.9f, cx + 1.4f, y1, 8.45f, jacket);
            m.box(cx - 1.48f, y0 + 1.0f, 7.0f, cx + 1.48f, y0 + 1.55f, 8.52f, band);
            m.box(cx - 1.48f, y1 - 1.55f, 7.0f, cx + 1.48f, y1 - 1.0f, 8.52f, band);
            m.box(cx - 1.15f, y1 - 0.35f, 6.8f, cx + 1.15f, y1 + 0.05f, 8.2f, MAT_PARCHMENT);
            BOOK_SLOT_MESHES[slot] = m;
            return m;
        }
    }
}
