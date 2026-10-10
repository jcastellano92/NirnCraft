package com.skycraft.society.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.skycraft.Skycraft;
import com.skycraft.society.NpcNames.Race;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * The parts a flat skin can't show: pointed elf (and Orc) ears, Khajiit ears and tail, Argonian horns and tail, Orc
 * tusks. One small model whose head and body follow the wearer's; a single greyscale texture tinted with the wearer's
 * skin, fur or scale colour (tusks and horns in bone). Used for townsfolk and for players (from their chosen race).
 */
public class RaceFeatureLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {
    private static final ResourceLocation TEXTURE = new ResourceLocation(Skycraft.MODID, "textures/entity/society/npc/features.png");
    private static final int BONE = 0xE8E0C8;

    /** What the layer needs to know about a wearer: race and tint (RGB), or null for nothing to draw. */
    public record Features(Race race, int tint) {}

    private final Function<T, Features> features;
    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart elfEarL, elfEarR, catEarL, catEarR, hornL, hornR, hornL2, hornR2, tuskL, tuskR;
    private final ModelPart tail, tailTip, scaleTail, scaleTailTip;

    public RaceFeatureLayer(RenderLayerParent<T, M> parent, ModelPart root, Function<T, Features> features) {
        super(parent);
        this.features = features;
        this.head = root.getChild("head");
        this.body = root.getChild("body");
        this.elfEarL = head.getChild("elf_ear_l");
        this.elfEarR = head.getChild("elf_ear_r");
        this.catEarL = head.getChild("cat_ear_l");
        this.catEarR = head.getChild("cat_ear_r");
        this.hornL = head.getChild("horn_l");
        this.hornR = head.getChild("horn_r");
        this.hornL2 = head.getChild("horn_l2");
        this.hornR2 = head.getChild("horn_r2");
        this.tuskL = head.getChild("tusk_l");
        this.tuskR = head.getChild("tusk_r");
        this.tail = body.getChild("tail");
        this.tailTip = tail.getChild("tip");
        this.scaleTail = body.getChild("scale_tail");
        this.scaleTailTip = scaleTail.getChild("tip");
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        head.addOrReplaceChild("elf_ear_l", CubeListBuilder.create().texOffs(0, 0).addBox(0.0F, -2.5F, -1.0F, 1, 3, 2),
                PartPose.offsetAndRotation(3.6F, -4.0F, 0.6F, 0.0F, -0.35F, 0.55F));
        head.addOrReplaceChild("elf_ear_r", CubeListBuilder.create().texOffs(6, 0).addBox(-1.0F, -2.5F, -1.0F, 1, 3, 2),
                PartPose.offsetAndRotation(-3.6F, -4.0F, 0.6F, 0.0F, 0.35F, -0.55F));
        head.addOrReplaceChild("cat_ear_l", CubeListBuilder.create().texOffs(12, 0).addBox(-1.0F, -2.0F, -0.5F, 2, 2, 1),
                PartPose.offsetAndRotation(2.6F, -8.0F, -0.5F, -0.1F, 0.0F, 0.25F));
        head.addOrReplaceChild("cat_ear_r", CubeListBuilder.create().texOffs(18, 0).addBox(-1.0F, -2.0F, -0.5F, 2, 2, 1),
                PartPose.offsetAndRotation(-2.6F, -8.0F, -0.5F, -0.1F, 0.0F, -0.25F));
        head.addOrReplaceChild("horn_l", CubeListBuilder.create().texOffs(32, 0).addBox(-0.5F, -0.5F, 0.0F, 1, 1, 4),
                PartPose.offsetAndRotation(2.0F, -7.0F, 3.2F, 0.6F, 0.15F, 0.0F));
        head.addOrReplaceChild("horn_r", CubeListBuilder.create().texOffs(32, 0).addBox(-0.5F, -0.5F, 0.0F, 1, 1, 4),
                PartPose.offsetAndRotation(-2.0F, -7.0F, 3.2F, 0.6F, -0.15F, 0.0F));
        head.addOrReplaceChild("horn_l2", CubeListBuilder.create().texOffs(42, 0).addBox(-0.5F, -0.5F, 0.0F, 1, 1, 3),
                PartPose.offsetAndRotation(3.2F, -5.0F, 3.2F, 0.3F, 0.3F, 0.0F));
        head.addOrReplaceChild("horn_r2", CubeListBuilder.create().texOffs(42, 0).addBox(-0.5F, -0.5F, 0.0F, 1, 1, 3),
                PartPose.offsetAndRotation(-3.2F, -5.0F, 3.2F, 0.3F, -0.3F, 0.0F));
        head.addOrReplaceChild("tusk_l", CubeListBuilder.create().texOffs(32, 8).addBox(-0.5F, -1.0F, -0.5F, 1, 1, 1),
                PartPose.offset(2.0F, -1.2F, -4.3F));
        head.addOrReplaceChild("tusk_r", CubeListBuilder.create().texOffs(32, 8).addBox(-0.5F, -1.0F, -0.5F, 1, 1, 1),
                PartPose.offset(-2.0F, -1.2F, -4.3F));
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition tail = body.addOrReplaceChild("tail", CubeListBuilder.create().texOffs(0, 6).addBox(-1.0F, -1.0F, 0.0F, 2, 2, 6),
                PartPose.offsetAndRotation(0.0F, 10.5F, 1.6F, -0.75F, 0.0F, 0.0F));
        tail.addOrReplaceChild("tip", CubeListBuilder.create().texOffs(16, 6).addBox(-1.0F, -1.0F, 0.0F, 2, 2, 6),
                PartPose.offsetAndRotation(0.0F, 0.0F, 5.5F, 0.9F, 0.0F, 0.0F));
        PartDefinition scaleTail = body.addOrReplaceChild("scale_tail", CubeListBuilder.create().texOffs(0, 16).addBox(-1.5F, -1.5F, 0.0F, 3, 3, 6),
                PartPose.offsetAndRotation(0.0F, 10.5F, 1.5F, -1.0F, 0.0F, 0.0F));
        scaleTail.addOrReplaceChild("tip", CubeListBuilder.create().texOffs(18, 16).addBox(-1.0F, -1.0F, 0.0F, 2, 2, 6),
                PartPose.offsetAndRotation(0.0F, 0.0F, 5.5F, 0.35F, 0.0F, 0.0F));
        return LayerDefinition.create(mesh, 64, 32);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, T entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (entity.isInvisible()) return;
        Features f = features.apply(entity);
        if (f == null) return;
        Race race = f.race();
        boolean elf = race == Race.ALTMER || race == Race.BOSMER || race == Race.DUNMER || race == Race.ORC;
        boolean cat = race == Race.KHAJIIT;
        boolean lizard = race == Race.ARGONIAN;
        if (!elf && !cat && !lizard) return;
        M parent = this.getParentModel();
        head.copyFrom(parent.head);
        body.copyFrom(parent.body);
        // a helmet hides the ears and horns
        boolean helmet = !entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty();
        head.visible = !helmet;
        float sway = Mth.sin(ageInTicks * 0.09F) * 0.3F + limbSwingAmount * Mth.sin(limbSwing * 0.6F) * 0.25F;
        tail.yRot = sway;
        scaleTail.yRot = sway * 0.6F;
        tailTip.xRot = 0.9F + Mth.cos(ageInTicks * 0.07F) * 0.15F;

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0F);
        float r = (f.tint() >> 16 & 255) / 255f, g = (f.tint() >> 8 & 255) / 255f, b = (f.tint() & 255) / 255f;
        // pass 1: skin, fur and scales
        show(elf, cat, lizard, false);
        head.render(poseStack, vc, light, overlay, r, g, b, 1.0F);
        body.render(poseStack, vc, light, overlay, r, g, b, 1.0F);
        // pass 2: bone (tusks, horns)
        if (race == Race.ORC || lizard) {
            show(false, false, false, true);
            elfEarL.visible = false;
            tuskL.visible = tuskR.visible = race == Race.ORC;
            hornL.visible = hornR.visible = hornL2.visible = hornR2.visible = lizard;
            float br = (BONE >> 16 & 255) / 255f, bg = (BONE >> 8 & 255) / 255f, bb = (BONE & 255) / 255f;
            if (lizard) {
                br *= 0.8f;
                bg *= 0.78f;
                bb *= 0.7f;
            }
            head.render(poseStack, vc, light, overlay, br, bg, bb, 1.0F);
        }
        head.visible = true;
    }

    private void show(boolean elf, boolean cat, boolean lizard, boolean bone) {
        elfEarL.visible = elfEarR.visible = elf;
        catEarL.visible = catEarR.visible = cat;
        tail.visible = cat;
        scaleTail.visible = lizard;
        hornL.visible = hornR.visible = hornL2.visible = hornR2.visible = false;
        tuskL.visible = tuskR.visible = false;
        if (bone) {
            tail.visible = false;
            scaleTail.visible = false;
        }
    }

    /** Features of a player from their chosen race (tinted with the race's middle tone). */
    @Nullable
    public static Features forPlayer(Player player) {
        com.skycraft.core.Race race = com.skycraft.client.RaceSkins.getEffectiveRace(player);
        if (race == null) return null;
        Race r = Race.of(race);
        return new Features(r, com.skycraft.society.NpcLook.TONES[r.ordinal()][1]);
    }
}
