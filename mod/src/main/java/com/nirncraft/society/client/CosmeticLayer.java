package com.nirncraft.society.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nirncraft.society.Cosmetic;
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
import net.minecraft.world.entity.EquipmentSlot;

/**
 * Draws a player's faction regalia (tabard, cloak, wolf pelt, sash, hood) over their skin and armor. Being a plain
 * render layer it stacks with other other cosmetic render layers instead of replacing the skin.
 *
 * <p>Texture layout (64x64): hood 8x8x8 at (0,0), torso 8x12x4 at (0,16), tabard front/back panels 9x9x1 at (0,32)
 * and (24,32), cloak 10x19x1 at (0,44), shoulder pelts 5x4x5 at (32,0) and (32,10).</p>
 */
public class CosmeticLayer extends RenderLayer<net.minecraft.client.renderer.entity.state.AvatarRenderState, PlayerModel> {
    private final Parts normal;
    private final Parts armored;
    private final com.nirncraft.client.CapturedPartModel normalModel, armoredModel;
    private static final net.minecraft.util.context.ContextKey<Cosmetic> COSMETIC = new net.minecraft.util.context.ContextKey<>(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "cosmetic"));

    public static void extract(net.minecraft.world.entity.player.Player player, net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
        state.setRenderData(COSMETIC, Cosmetic.byId(ClientCosmetics.get(player.getUUID())));
    }

    private record Parts(ModelPart root, ModelPart hood, ModelPart torso, ModelPart skirtFront, ModelPart skirtBack,
                         ModelPart cloak, ModelPart rightPelt, ModelPart leftPelt) {
        static Parts of(ModelPart root) {
            ModelPart torso = root.getChild("torso");
            return new Parts(root, root.getChild("hood"), torso, torso.getChild("skirt_front"), torso.getChild("skirt_back"),
                    torso.getChild("cloak"), root.getChild("right_pelt"), root.getChild("left_pelt"));
        }
    }

    public CosmeticLayer(RenderLayerParent<net.minecraft.client.renderer.entity.state.AvatarRenderState, PlayerModel> parent, EntityModelSet models) {
        super(parent);
        this.normal = Parts.of(models.bakeLayer(SocietyClient.COSMETIC_LAYER));
        this.armored = Parts.of(models.bakeLayer(SocietyClient.COSMETIC_ARMORED_LAYER));
        normalModel = new com.nirncraft.client.CapturedPartModel(normal.root());
        armoredModel = new com.nirncraft.client.CapturedPartModel(armored.root());
    }

    /** {@code armored}: inflated to sit over a chestplate. */
    public static LayerDefinition createLayer(boolean armored) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        float t = armored ? 1.15f : 0.55f;
        root.addOrReplaceChild("hood", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-4.0f, -8.0f, -4.0f, 8, 8, 8, new CubeDeformation(armored ? 1.2f : 0.75f)), PartPose.ZERO);
        PartDefinition torso = root.addOrReplaceChild("torso", CubeListBuilder.create().texOffs(0, 16)
                .addBox(-4.0f, 0.0f, -2.0f, 8, 12, 4, new CubeDeformation(t)), PartPose.ZERO);
        float z = 2.0f + t + 0.2f;
        torso.addOrReplaceChild("skirt_front", CubeListBuilder.create().texOffs(0, 32)
                .addBox(-4.5f, 0.0f, -0.5f, 9, 9, 1), PartPose.offset(0.0f, 11.5f, -z));
        torso.addOrReplaceChild("skirt_back", CubeListBuilder.create().texOffs(24, 32)
                .addBox(-4.5f, 0.0f, -0.5f, 9, 9, 1), PartPose.offset(0.0f, 11.5f, z));
        torso.addOrReplaceChild("cloak", CubeListBuilder.create().texOffs(0, 44)
                .addBox(-5.0f, 0.0f, 0.0f, 10, 19, 1), PartPose.offset(0.0f, -0.4f, z + 0.1f));
        float p = armored ? 0.65f : 0.3f;
        root.addOrReplaceChild("right_pelt", CubeListBuilder.create().texOffs(32, 0)
                .addBox(-3.5f, -2.6f, -2.5f, 5, 4, 5, new CubeDeformation(p)), PartPose.ZERO);
        root.addOrReplaceChild("left_pelt", CubeListBuilder.create().texOffs(32, 10)
                .addBox(-1.5f, -2.6f, -2.5f, 5, 4, 5, new CubeDeformation(p)), PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void submit(PoseStack pose, SubmitNodeCollector buffers, int packedLight,
                       net.minecraft.client.renderer.entity.state.AvatarRenderState player, float netHeadYaw, float headPitch) {
        if (player.isInvisible) return;
        float limbSwingAmount = player.walkAnimationSpeed;
        Cosmetic cosmetic = player.getRenderData(COSMETIC);
        if (cosmetic == null) return;
        Parts parts = player.chestEquipment.isEmpty() ? normal : armored;
        PlayerModel model = this.getParentModel();

        parts.hood().loadPose(model.head.storePose());
        parts.torso().loadPose(model.body.storePose());
        parts.rightPelt().loadPose(model.rightArm.storePose());
        parts.leftPelt().loadPose(model.leftArm.storePose());

        parts.hood().visible = cosmetic.has(Cosmetic.Part.HOOD) && player.headEquipment.isEmpty();
        parts.torso().visible = true; // carries the skirt and cloak children
        boolean torsoBox = cosmetic.has(Cosmetic.Part.TORSO);
        boolean skirt = cosmetic.has(Cosmetic.Part.SKIRT);
        parts.skirtFront().visible = skirt;
        parts.skirtBack().visible = skirt;
        parts.cloak().visible = cosmetic.has(Cosmetic.Part.CLOAK) && !player.isFallFlying;
        parts.rightPelt().visible = cosmetic.has(Cosmetic.Part.PELTS);
        parts.leftPelt().visible = cosmetic.has(Cosmetic.Part.PELTS);

        // tabard panels swing out with the legs; the cloak streams behind when moving
        float forward = Math.min(0.0f, Math.min(model.rightLeg.xRot, model.leftLeg.xRot));
        float backward = Math.max(0.0f, Math.max(model.rightLeg.xRot, model.leftLeg.xRot));
        parts.skirtFront().xRot = forward * 0.9f;
        parts.skirtBack().xRot = backward * 0.9f;
        float stream = Math.min(1.0f, limbSwingAmount) * 0.55f + (player.isCrouching ? 0.15f : 0.06f);
        parts.cloak().xRot = stream + backward * 0.4f;

        parts.torso().skipDraw = !torsoBox;
        (parts == normal ? normalModel : armoredModel).submit(buffers, pose,
                RenderTypes.entityCutout(cosmetic.texture), packedLight, OverlayTexture.NO_OVERLAY, -1);
        parts.torso().skipDraw = false;
    }
}
