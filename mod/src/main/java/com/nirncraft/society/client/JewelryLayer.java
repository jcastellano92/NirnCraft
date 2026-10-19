package com.nirncraft.society.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nirncraft.NirnCraft;
import com.nirncraft.core.SkyData;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * 3D Render Layer for Jewelry (Circlets, Necklaces, Amulets, Pendants, Rings) and Layered Clothing.
 * Renders gleaming jewelry and authentic layered garments over or under armor.
 */
public class JewelryLayer extends RenderLayer<net.minecraft.client.renderer.entity.state.AvatarRenderState, PlayerModel> {
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/entity/society/jewelry.png");

    private final ModelPart circlet;
    private final ModelPart torso;
    private final ModelPart necklaceChain;
    private final ModelPart medallion;
    private final ModelPart clothingTrim;
    private final ModelPart ringRight;
    private final ModelPart ringLeft;
    private final com.nirncraft.client.CapturedPartModel circletModel, torsoModel, rightModel, leftModel;
    private record Jewelry(boolean circlet, boolean necklace, boolean ring) {}
    private static final net.minecraft.util.context.ContextKey<Jewelry> JEWELRY = new net.minecraft.util.context.ContextKey<>(
            Identifier.fromNamespaceAndPath("nirncraft", "jewelry"));

    public JewelryLayer(RenderLayerParent<net.minecraft.client.renderer.entity.state.AvatarRenderState, PlayerModel> parent, EntityModelSet models) {
        super(parent);
        ModelPart root = models.bakeLayer(SocietyClient.JEWELRY_LAYER);
        this.circlet = root.getChild("circlet");
        this.torso = root.getChild("torso");
        this.necklaceChain = torso.getChild("necklace_chain");
        this.medallion = torso.getChild("medallion");
        this.clothingTrim = torso.getChild("clothing_trim");
        this.ringRight = root.getChild("ring_right");
        this.ringLeft = root.getChild("ring_left");
        circletModel = new com.nirncraft.client.CapturedPartModel(circlet);
        torsoModel = new com.nirncraft.client.CapturedPartModel(torso);
        rightModel = new com.nirncraft.client.CapturedPartModel(ringRight);
        leftModel = new com.nirncraft.client.CapturedPartModel(ringLeft);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // 1. Circlet: sits on forehead
        root.addOrReplaceChild("circlet", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-4.2f, -6.5f, -4.2f, 8.4f, 1.6f, 8.4f, new CubeDeformation(0.35f))
                .texOffs(14, 0).addBox(-0.75f, -7.0f, -4.6f, 1.5f, 1.5f, 0.8f), PartPose.ZERO);

        // 2. Torso with necklace and layered clothing trim
        PartDefinition torso = root.addOrReplaceChild("torso", CubeListBuilder.create(), PartPose.ZERO);
        torso.addOrReplaceChild("necklace_chain", CubeListBuilder.create().texOffs(0, 16)
                .addBox(-4.1f, 0.0f, -2.2f, 8.2f, 4.0f, 4.4f, new CubeDeformation(0.2f)), PartPose.ZERO);
        torso.addOrReplaceChild("medallion", CubeListBuilder.create().texOffs(0, 32)
                .addBox(-1.0f, 3.0f, -2.45f, 2.0f, 2.5f, 0.5f), PartPose.ZERO);
        torso.addOrReplaceChild("clothing_trim", CubeListBuilder.create().texOffs(0, 40)
                .addBox(-4.05f, 0.0f, -2.05f, 8.1f, 12.0f, 4.1f, new CubeDeformation(0.12f)), PartPose.ZERO);

        // 3. Rings on hands/wrists
        root.addOrReplaceChild("ring_right", CubeListBuilder.create().texOffs(36, 0)
                .addBox(-3.1f, 8.0f, -2.1f, 4.2f, 1.4f, 4.2f, new CubeDeformation(0.15f)), PartPose.ZERO);
        root.addOrReplaceChild("ring_left", CubeListBuilder.create().texOffs(36, 10)
                .addBox(-1.1f, 8.0f, -2.1f, 4.2f, 1.4f, 4.2f, new CubeDeformation(0.15f)), PartPose.ZERO);

        return LayerDefinition.create(mesh, 64, 64);
    }

    private static boolean matchKeyword(ItemStack stack, String keyword) {
        if (stack.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id != null && id.getPath().toLowerCase(java.util.Locale.ROOT).contains(keyword)) return true;
        String desc = stack.getItem().getDescriptionId().toLowerCase(java.util.Locale.ROOT);
        if (desc.contains(keyword)) return true;
        String name = stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        return name.contains(keyword);
    }

    private static boolean isCirclet(ItemStack stack) {
        return matchKeyword(stack, "circlet") || matchKeyword(stack, "crown") || matchKeyword(stack, "tiara") || matchKeyword(stack, "diadem");
    }

    private static boolean isNecklace(ItemStack stack) {
        return matchKeyword(stack, "necklace") || matchKeyword(stack, "amulet") || matchKeyword(stack, "pendant") || matchKeyword(stack, "choker") || matchKeyword(stack, "locket");
    }

    private static boolean isRing(ItemStack stack) {
        return matchKeyword(stack, "ring") || matchKeyword(stack, "band");
    }

    public static void extract(net.minecraft.world.entity.player.Player player,
                               net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
        ItemStack headStack = player.getItemBySlot(EquipmentSlot.HEAD);
        ItemStack chestStack = player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack offhandStack = player.getItemBySlot(EquipmentSlot.OFFHAND);
        ItemStack mainhandStack = player.getItemBySlot(EquipmentSlot.MAINHAND);

        CompoundTag apparel = SkyData.get(player).module("apparel");
        String appCirclet = apparel.getStringOr("circlet","");
        String appNecklace = apparel.getStringOr("necklace","");
        String appRing = apparel.getStringOr("ring","");

        boolean hasCirclet = isCirclet(headStack) || !appCirclet.isEmpty();
        boolean hasNecklace = isNecklace(chestStack) || isNecklace(offhandStack) || isNecklace(mainhandStack) || !appNecklace.isEmpty();
        boolean hasRing = isRing(offhandStack) || isRing(mainhandStack) || !appRing.isEmpty();
        boolean hasClothing = true; // Layered clothing trim under/over armor

        if (!hasCirclet || !hasNecklace || !hasRing) {
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) continue;
                if (!hasCirclet && isCirclet(stack)) hasCirclet = true;
                if (!hasNecklace && isNecklace(stack)) hasNecklace = true;
                if (!hasRing && isRing(stack)) hasRing = true;
            }
        }
        state.setRenderData(JEWELRY, new Jewelry(hasCirclet, hasNecklace, hasRing));
    }

    @Override
    public void submit(PoseStack pose, SubmitNodeCollector buffers, int packedLight,
                       net.minecraft.client.renderer.entity.state.AvatarRenderState player, float netHeadYaw, float headPitch) {
        Jewelry jewelry = player.getRenderData(JEWELRY);
        if (player.isInvisible || jewelry == null) return;
        boolean hasCirclet = jewelry.circlet(), hasNecklace = jewelry.necklace(), hasRing = jewelry.ring();
        boolean hasClothing = true;
        ItemStack headStack = player.headEquipment, chestStack = player.chestEquipment;
        PlayerModel model = this.getParentModel();
        circlet.loadPose(model.head.storePose());
        torso.loadPose(model.body.storePose());
        ringRight.loadPose(model.rightArm.storePose());
        ringLeft.loadPose(model.leftArm.storePose());

        circlet.visible = hasCirclet;
        necklaceChain.visible = hasNecklace;
        medallion.visible = hasNecklace;
        clothingTrim.visible = hasClothing;
        ringRight.visible = hasRing;
        ringLeft.visible = hasRing;

        var type = RenderTypes.entityCutout(TEXTURE);
        int overlay = OverlayTexture.NO_OVERLAY;

        boolean hasHelmet = !headStack.isEmpty();
        if (hasCirclet) {
            pose.pushPose();
            if (hasHelmet) {
                pose.scale(1.08f, 1.08f, 1.12f);
            }
            circletModel.submit(buffers, pose, type, packedLight, overlay, -1);
            pose.popPose();
        }

        boolean hasArmor = !chestStack.isEmpty();
        if (hasNecklace || hasClothing) {
            pose.pushPose();
            if (hasArmor) {
                pose.scale(1.08f, 1.02f, 1.16f);
            }
            torsoModel.submit(buffers, pose, type, packedLight, overlay, -1);
            pose.popPose();
        }

        if (hasRing) {
            rightModel.submit(buffers, pose, type, packedLight, overlay, -1);
            leftModel.submit(buffers, pose, type, packedLight, overlay, -1);
        }
    }
}

