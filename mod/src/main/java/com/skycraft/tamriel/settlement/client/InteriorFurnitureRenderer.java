package com.skycraft.tamriel.settlement.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.furniture.FurnitureBlocks;
import com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock;
import com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlockEntity;
import com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureContainerBlock;
import com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureContainerBlockEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static com.skycraft.tamriel.settlement.client.ShopSignModels.*;

/**
 * BlockEntityRenderers for custom 3D interior furniture ({@link FurnitureBlockEntity}),
 * custom 3D storage furniture ({@link FurnitureContainerBlockEntity}), and 3D protruding
 * leather-bound tomes on occupied slots of {@link ChiseledBookShelfBlockEntity}.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class InteriorFurnitureRenderer {
    private static final ShopSignMesh[] BOOK_SLOT_MESHES = new ShopSignMesh[6];

    private InteriorFurnitureRenderer() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(FurnitureBlocks.SKY_FURNITURE_BE.get(), FurnitureBER::new);
        event.registerBlockEntityRenderer(FurnitureBlocks.SKY_FURNITURE_CONTAINER_BE.get(), FurnitureContainerBER::new);
        event.registerBlockEntityRenderer(BlockEntityType.CHISELED_BOOKSHELF, ChiseledBookshelfBER::new);
    }

    public static final class FurnitureBER implements BlockEntityRenderer<FurnitureBlockEntity> {
        public FurnitureBER(BlockEntityRendererProvider.Context ctx) {}

        @Override
        public void render(
                FurnitureBlockEntity be, float partialTick, PoseStack poseStack,
                MultiBufferSource bufferSource, int packedLight, int packedOverlay
        ) {
            Level level = be.getLevel();
            BlockPos pos = be.getBlockPos();
            int light = packedLight;
            if (level != null) {
                light = Math.max(packedLight, LevelRenderer.getLightColor(level, pos));
            }

            BlockState state = be.getBlockState();
            Direction facing = state.hasProperty(FurnitureBlock.FACING)
                    ? state.getValue(FurnitureBlock.FACING)
                    : Direction.SOUTH;
            Culture culture = be.culture();
            ShopSignMesh mesh = InteriorFurnitureModels.get(culture, be.kind(), be.isEmpty());
            VertexConsumer vc = bufferSource.getBuffer(RenderType.entityCutoutNoCull(ShopSignRenderer.ATLAS));

            poseStack.pushPose();
            poseStack.translate(0.5f, 0.5f, 0.5f);
            poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
            mesh.render(poseStack, vc, light);
            poseStack.popPose();
        }
    }

    public static final class FurnitureContainerBER implements BlockEntityRenderer<FurnitureContainerBlockEntity> {
        public FurnitureContainerBER(BlockEntityRendererProvider.Context ctx) {}

        @Override
        public void render(
                FurnitureContainerBlockEntity be, float partialTick, PoseStack poseStack,
                MultiBufferSource bufferSource, int packedLight, int packedOverlay
        ) {
            Level level = be.getLevel();
            BlockPos pos = be.getBlockPos();
            int light = packedLight;
            if (level != null) {
                light = Math.max(packedLight, LevelRenderer.getLightColor(level, pos));
            }

            BlockState state = be.getBlockState();
            Direction facing = state.hasProperty(FurnitureContainerBlock.FACING)
                    ? state.getValue(FurnitureContainerBlock.FACING)
                    : Direction.SOUTH;
            Culture culture = be.culture();
            ShopSignMesh mesh = InteriorFurnitureModels.get(culture, be.kind(), false);
            VertexConsumer vc = bufferSource.getBuffer(RenderType.entityCutoutNoCull(ShopSignRenderer.ATLAS));

            poseStack.pushPose();
            poseStack.translate(0.5f, 0.5f, 0.5f);
            poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
            mesh.render(poseStack, vc, light);
            poseStack.popPose();
        }
    }

    /**
     * Renders 3D protruding leather-bound book spines with gold/silver binding bands
     * for each occupied slot on a {@link ChiseledBookShelfBlockEntity}. When a player
     * grabs a book off the shelf, that slot becomes unoccupied and the 3D book disappears.
     */
    public static final class ChiseledBookshelfBER implements BlockEntityRenderer<ChiseledBookShelfBlockEntity> {
        public ChiseledBookshelfBER(BlockEntityRendererProvider.Context ctx) {}

        @Override
        public void render(
                ChiseledBookShelfBlockEntity be, float partialTick, PoseStack poseStack,
                MultiBufferSource bufferSource, int packedLight, int packedOverlay
        ) {
            BlockState state = be.getBlockState();
            if (!(state.getBlock() instanceof ChiseledBookShelfBlock)) return;

            boolean anyOccupied = false;
            for (int i = 0; i < 6; i++) {
                var prop = ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i);
                if (state.hasProperty(prop) && state.getValue(prop)) {
                    anyOccupied = true;
                    break;
                }
            }
            if (!anyOccupied) return;

            Level level = be.getLevel();
            BlockPos pos = be.getBlockPos();
            int light = packedLight;
            if (level != null) {
                light = Math.max(packedLight, LevelRenderer.getLightColor(level, pos));
            }

            Direction facing = state.hasProperty(HorizontalDirectionalBlock.FACING)
                    ? state.getValue(HorizontalDirectionalBlock.FACING)
                    : Direction.SOUTH;
            VertexConsumer vc = bufferSource.getBuffer(RenderType.entityCutoutNoCull(ShopSignRenderer.ATLAS));

            poseStack.pushPose();
            poseStack.translate(0.5f, 0.5f, 0.5f);
            poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
            for (int i = 0; i < 6; i++) {
                var prop = ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i);
                if (state.hasProperty(prop) && state.getValue(prop)) {
                    getSlotMesh(i).render(poseStack, vc, light);
                }
            }
            poseStack.popPose();
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
