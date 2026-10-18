package com.skycraft.society.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.skycraft.Skycraft;
import com.skycraft.society.NpcLook;
import com.skycraft.society.NpcNames.Race;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * The parts a flat skin can't show: pointed elf ears, cat, fox and ape ears, cat, fox, monkey, Argonian and serpent
 * tails, Argonian horns, Orc tusks, the Tsaesci hood and fangs, the muzzles of the Ka Po' Tun and Imga, and the
 * second pair of arms of the Tang Mo. One small model whose head and body follow the wearer's; a single greyscale
 * texture tinted with the wearer's skin, fur or scale colour (tusks, horns and fangs in bone, fox tips and muzzles in
 * a lighter shade). Tails sway, the serpent tail slithers as its owner walks. Used for townsfolk and for players
 * (from their chosen race and look). Texture: {@code tools/textures/races.py} (FEATURE_BOXES).
 */
public class RaceFeatureLayer<S extends net.minecraft.client.renderer.entity.state.HumanoidRenderState, M extends HumanoidModel<S>> extends RenderLayer<S, M> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Skycraft.MODID, "textures/entity/society/npc/features.png");
    private static final int BONE = 0xE8E0C8;

    // feature bits
    private static final int ELF_EARS = 1, CAT_EARS = 1 << 1, FOX_EARS = 1 << 2, ROUND_EARS = 1 << 3, CAT_TAIL = 1 << 4,
            FOX_TAIL = 1 << 5, MONKEY_TAIL = 1 << 6, SCALE_TAIL = 1 << 7, SNAKE_TAIL = 1 << 8, HORNS = 1 << 9,
            SMALL_HORNS = 1 << 10, TUSKS = 1 << 11, FANGS = 1 << 12, HOOD = 1 << 13, MUZZLE = 1 << 14, EXTRA_ARMS = 1 << 15;
    private static final int BONE_PARTS = HORNS | SMALL_HORNS | TUSKS | FANGS;

    /** What the layer needs to know about a wearer: race, tint (RGB) and sub-people, or null for nothing to draw. */
    public record Features(Race race, int tint, int variant) {
        public Features(Race race, int tint) {
            this(race, tint, 0);
        }

        /** Features of a packed {@link NpcLook}. */
        public static Features of(int look) {
            return new Features(NpcLook.race(look), NpcLook.toneColor(look), NpcLook.raceVariant(look));
        }
    }

    private final Function<S, Features> features;
    private final ModelPart head;
    private final ModelPart body;
    private final com.skycraft.client.CapturedPartModel headModel, bodyModel;
    private final ModelPart elfEarL, elfEarR, catEarL, catEarR, foxEarL, foxEarR, roundEarL, roundEarR;
    private final ModelPart hornL, hornR, hornL2, hornR2, tuskL, tuskR, fangL, fangR, hood, muzzle;
    private final ModelPart tail, tailTip, scaleTail, scaleTailTip, foxTail, foxTip, monkeyTail, monkey2, monkey3;
    private final ModelPart snake1, snake2, snake3, armL, armR;

    public RaceFeatureLayer(RenderLayerParent<S, M> parent, ModelPart root, Function<S, Features> features) {
        super(parent);
        this.features = features;
        this.head = root.getChild("head");
        this.body = root.getChild("body");
        this.headModel = new com.skycraft.client.CapturedPartModel(head);
        this.bodyModel = new com.skycraft.client.CapturedPartModel(body);
        this.elfEarL = head.getChild("elf_ear_l");
        this.elfEarR = head.getChild("elf_ear_r");
        this.catEarL = head.getChild("cat_ear_l");
        this.catEarR = head.getChild("cat_ear_r");
        this.foxEarL = head.getChild("fox_ear_l");
        this.foxEarR = head.getChild("fox_ear_r");
        this.roundEarL = head.getChild("round_ear_l");
        this.roundEarR = head.getChild("round_ear_r");
        this.hornL = head.getChild("horn_l");
        this.hornR = head.getChild("horn_r");
        this.hornL2 = head.getChild("horn_l2");
        this.hornR2 = head.getChild("horn_r2");
        this.tuskL = head.getChild("tusk_l");
        this.tuskR = head.getChild("tusk_r");
        this.fangL = head.getChild("fang_l");
        this.fangR = head.getChild("fang_r");
        this.hood = head.getChild("hood");
        this.muzzle = head.getChild("muzzle");
        this.tail = body.getChild("tail");
        this.tailTip = tail.getChild("tip");
        this.scaleTail = body.getChild("scale_tail");
        this.scaleTailTip = scaleTail.getChild("tip");
        this.foxTail = body.getChild("fox_tail");
        this.foxTip = foxTail.getChild("tip");
        this.monkeyTail = body.getChild("monkey_tail");
        this.monkey2 = monkeyTail.getChild("mid");
        this.monkey3 = monkey2.getChild("tip");
        this.snake1 = body.getChild("snake_tail");
        this.snake2 = snake1.getChild("mid");
        this.snake3 = snake2.getChild("tip");
        this.armL = body.getChild("arm2_l");
        this.armR = body.getChild("arm2_r");
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
        head.addOrReplaceChild("fox_ear_l", CubeListBuilder.create().texOffs(36, 8).addBox(-1.5F, -4.0F, -0.5F, 3, 4, 1),
                PartPose.offsetAndRotation(2.5F, -7.6F, -0.5F, -0.15F, 0.0F, 0.22F));
        head.addOrReplaceChild("fox_ear_r", CubeListBuilder.create().texOffs(36, 8).mirror().addBox(-1.5F, -4.0F, -0.5F, 3, 4, 1),
                PartPose.offsetAndRotation(-2.5F, -7.6F, -0.5F, -0.15F, 0.0F, -0.22F));
        head.addOrReplaceChild("round_ear_l", CubeListBuilder.create().texOffs(46, 8).addBox(0.0F, -1.0F, -1.0F, 1, 2, 2),
                PartPose.offsetAndRotation(4.0F, -4.5F, 0.0F, 0.0F, -0.3F, 0.0F));
        head.addOrReplaceChild("round_ear_r", CubeListBuilder.create().texOffs(46, 8).mirror().addBox(-1.0F, -1.0F, -1.0F, 1, 2, 2),
                PartPose.offsetAndRotation(-4.0F, -4.5F, 0.0F, 0.0F, 0.3F, 0.0F));
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
        head.addOrReplaceChild("fang_l", CubeListBuilder.create().texOffs(36, 14).addBox(-0.5F, 0.0F, -0.5F, 1, 1, 1),
                PartPose.offset(1.3F, -1.8F, -3.9F));
        head.addOrReplaceChild("fang_r", CubeListBuilder.create().texOffs(36, 14).addBox(-0.5F, 0.0F, -0.5F, 1, 1, 1),
                PartPose.offset(-1.3F, -1.8F, -3.9F));
        // the cobra hood flares behind the head and down the neck
        head.addOrReplaceChild("hood", CubeListBuilder.create().texOffs(0, 26).addBox(-7.0F, -9.0F, 0.0F, 14, 12, 1),
                PartPose.offsetAndRotation(0.0F, 0.0F, 3.6F, 0.1F, 0.0F, 0.0F));
        head.addOrReplaceChild("muzzle", CubeListBuilder.create().texOffs(50, 0).addBox(-2.0F, -3.5F, -6.0F, 4, 3, 2),
                PartPose.ZERO);

        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition tail = body.addOrReplaceChild("tail", CubeListBuilder.create().texOffs(0, 6).addBox(-1.0F, -1.0F, 0.0F, 2, 2, 6),
                PartPose.offsetAndRotation(0.0F, 10.5F, 1.6F, -0.75F, 0.0F, 0.0F));
        tail.addOrReplaceChild("tip", CubeListBuilder.create().texOffs(16, 6).addBox(-1.0F, -1.0F, 0.0F, 2, 2, 6),
                PartPose.offsetAndRotation(0.0F, 0.0F, 5.5F, 0.9F, 0.0F, 0.0F));
        PartDefinition scaleTail = body.addOrReplaceChild("scale_tail", CubeListBuilder.create().texOffs(0, 16).addBox(-1.5F, -1.5F, 0.0F, 3, 3, 6),
                PartPose.offsetAndRotation(0.0F, 10.5F, 1.5F, -1.0F, 0.0F, 0.0F));
        scaleTail.addOrReplaceChild("tip", CubeListBuilder.create().texOffs(18, 16).addBox(-1.0F, -1.0F, 0.0F, 2, 2, 6),
                PartPose.offsetAndRotation(0.0F, 0.0F, 5.5F, 0.35F, 0.0F, 0.0F));
        PartDefinition fox = body.addOrReplaceChild("fox_tail", CubeListBuilder.create().texOffs(30, 26).addBox(-1.5F, -1.5F, 0.0F, 3, 3, 7),
                PartPose.offsetAndRotation(0.0F, 10.5F, 1.6F, -0.55F, 0.0F, 0.0F));
        fox.addOrReplaceChild("tip", CubeListBuilder.create().texOffs(50, 26).addBox(-1.5F, -1.5F, 0.0F, 3, 3, 4),
                PartPose.offsetAndRotation(0.0F, 0.0F, 6.5F, 0.25F, 0.0F, 0.0F));
        PartDefinition monkey = body.addOrReplaceChild("monkey_tail", CubeListBuilder.create().texOffs(36, 16).addBox(-0.5F, -0.5F, 0.0F, 1, 1, 6),
                PartPose.offsetAndRotation(0.0F, 10.5F, 1.8F, -0.35F, 0.0F, 0.0F));
        PartDefinition monkeyMid = monkey.addOrReplaceChild("mid", CubeListBuilder.create().texOffs(36, 16).addBox(-0.5F, -0.5F, 0.0F, 1, 1, 6),
                PartPose.offsetAndRotation(0.0F, 0.0F, 5.5F, 0.7F, 0.0F, 0.0F));
        monkeyMid.addOrReplaceChild("tip", CubeListBuilder.create().texOffs(36, 16).addBox(-0.5F, -0.5F, 0.0F, 1, 1, 6),
                PartPose.offsetAndRotation(0.0F, 0.0F, 5.5F, 1.1F, 0.0F, 0.0F));
        // the Tsaesci's serpent tail: from the small of the back down to the ground and trailing behind
        PartDefinition snake = body.addOrReplaceChild("snake_tail", CubeListBuilder.create().texOffs(0, 40).addBox(-2.0F, -2.0F, 0.0F, 4, 4, 8),
                PartPose.offsetAndRotation(0.0F, 10.5F, 1.2F, -1.15F, 0.0F, 0.0F));
        PartDefinition snakeMid = snake.addOrReplaceChild("mid", CubeListBuilder.create().texOffs(24, 40).addBox(-1.5F, -1.5F, 0.0F, 3, 3, 8),
                PartPose.offsetAndRotation(0.0F, 0.0F, 7.5F, 0.5F, 0.0F, 0.0F));
        snakeMid.addOrReplaceChild("tip", CubeListBuilder.create().texOffs(0, 52).addBox(-1.0F, -1.0F, 0.0F, 2, 2, 8),
                PartPose.offsetAndRotation(0.0F, 0.0F, 7.5F, 0.55F, 0.0F, 0.0F));
        // Tang Mo legends give some of them more arms than two: a slimmer pair below and behind the first
        body.addOrReplaceChild("arm2_l", CubeListBuilder.create().texOffs(24, 52).mirror().addBox(-1.5F, 0.0F, -1.5F, 3, 9, 3),
                PartPose.offsetAndRotation(4.6F, 4.5F, 1.6F, 0.0F, 0.0F, -0.22F));
        body.addOrReplaceChild("arm2_r", CubeListBuilder.create().texOffs(24, 52).addBox(-1.5F, 0.0F, -1.5F, 3, 9, 3),
                PartPose.offsetAndRotation(-4.6F, 4.5F, 1.6F, 0.0F, 0.0F, 0.22F));
        return LayerDefinition.create(mesh, 64, 64);
    }

    /** The parts a race (and sub-people) has. */
    private static int parts(Race race, int variant) {
        return switch (race) {
            case ALTMER, BOSMER, DUNMER, MAORMER, FALMER, DWEMER, AYLEID -> ELF_EARS;
            case ORC -> ELF_EARS | TUSKS;
            case KHAJIIT, OHMES_RAHT -> CAT_EARS | CAT_TAIL;
            case KA_PO_TUN -> CAT_EARS | CAT_TAIL | MUZZLE;
            case ARGONIAN -> SCALE_TAIL | (variant == 2 ? SMALL_HORNS : HORNS);
            case LILMOTHIIT -> FOX_EARS | FOX_TAIL;
            case IMGA -> ROUND_EARS | MUZZLE;
            case TANG_MO -> ROUND_EARS | MONKEY_TAIL | EXTRA_ARMS;
            case TSAESCI -> HOOD | FANGS | SNAKE_TAIL;
            default -> 0;
        };
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector buffer, int light, S entity, float netHeadYaw, float headPitch) {
        if (entity.isInvisible) return;
        float limbSwing = entity.walkAnimationPos, limbSwingAmount = entity.walkAnimationSpeed, ageInTicks = entity.ageInTicks;
        Features f = features.apply(entity);
        if (f == null) return;
        Race race = f.race();
        int parts = parts(race, f.variant());
        if (parts == 0) return;
        M parent = this.getParentModel();
        head.loadPose(parent.head.storePose());
        body.loadPose(parent.body.storePose());
        // a helmet hides the ears, horns and hood
        boolean helmet = !entity.headEquipment.isEmpty();
        head.visible = !helmet;
        animate(parent, limbSwing, limbSwingAmount, ageInTicks);

        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0F);
        // pass 1: skin, fur and scales
        show(parts & ~BONE_PARTS & ~MUZZLE, false);
        foxTip.visible = false;
        draw(poseStack, buffer, light, overlay, f.tint(), true);
        // pass 2: bone (tusks, horns, fangs)
        if ((parts & BONE_PARTS) != 0) {
            show(parts & BONE_PARTS, true);
            int bone = race == Race.ARGONIAN ? scale(BONE, 0.8f, 0.78f, 0.7f) : BONE;
            draw(poseStack, buffer, light, overlay, bone, false);
        }
        // pass 3: the light parts (fox tail tip, muzzles)
        if ((parts & (FOX_TAIL | MUZZLE)) != 0) {
            show(parts & MUZZLE, true);
            if ((parts & FOX_TAIL) != 0) {
                foxTail.visible = true;
                foxTip.visible = true;
                foxTail.skipDraw = true; // only its tip
            }
            int light2 = race == Race.IMGA ? mix(f.tint(), 0x7A6E66, 0.55f) : mix(f.tint(), 0xF6EEE0, 0.8f);
            draw(poseStack, buffer, light, overlay, light2, (parts & FOX_TAIL) != 0);
            foxTail.skipDraw = false;
        }
        head.visible = true;
    }

    private void draw(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, int rgb, boolean withBody) {
        var type = RenderTypes.entityCutout(TEXTURE);
        headModel.submit(collector, pose, type, light, overlay, 0xFF000000 | rgb);
        if (withBody) bodyModel.submit(collector, pose, type, light, overlay, 0xFF000000 | rgb);
    }

    /** Tails sway when idle and swing with the stride; the serpent tail slithers; the second arms swing. */
    private void animate(M parent, float limbSwing, float limbSwingAmount, float ageInTicks) {
        float idle = Mth.sin(ageInTicks * 0.09F);
        float stride = limbSwingAmount * Mth.sin(limbSwing * 0.6F);
        float sway = idle * 0.3F + stride * 0.25F;
        tail.yRot = sway;
        tailTip.xRot = 0.9F + Mth.cos(ageInTicks * 0.07F) * 0.15F;
        scaleTail.yRot = sway * 0.6F;
        foxTail.yRot = idle * 0.22F + stride * 0.2F;
        foxTail.xRot = -0.55F + Mth.cos(ageInTicks * 0.05F) * 0.06F - limbSwingAmount * 0.25F;
        monkeyTail.yRot = sway * 0.8F;
        monkey2.xRot = 0.7F + Mth.sin(ageInTicks * 0.08F) * 0.2F;
        monkey3.xRot = 1.1F + Mth.sin(ageInTicks * 0.08F + 1.2F) * 0.3F;
        float slither = limbSwing * 0.45F;
        float amp = 0.12F + limbSwingAmount * 0.35F;
        snake1.yRot = Mth.sin(slither + ageInTicks * 0.03F) * amp;
        snake2.yRot = Mth.sin(slither + ageInTicks * 0.03F - 1.3F) * amp * 1.4F;
        snake3.yRot = Mth.sin(slither + ageInTicks * 0.03F - 2.6F) * amp * 1.8F;
        armL.xRot = -parent.leftArm.xRot * 0.7F + Mth.sin(ageInTicks * 0.06F) * 0.05F;
        armR.xRot = -parent.rightArm.xRot * 0.7F - Mth.sin(ageInTicks * 0.06F) * 0.05F;
    }

    private void show(int parts, boolean pass) {
        elfEarL.visible = elfEarR.visible = (parts & ELF_EARS) != 0;
        catEarL.visible = catEarR.visible = (parts & CAT_EARS) != 0;
        foxEarL.visible = foxEarR.visible = (parts & FOX_EARS) != 0;
        roundEarL.visible = roundEarR.visible = (parts & ROUND_EARS) != 0;
        hornL.visible = hornR.visible = (parts & HORNS) != 0;
        hornL2.visible = hornR2.visible = (parts & (HORNS | SMALL_HORNS)) != 0;
        tuskL.visible = tuskR.visible = (parts & TUSKS) != 0;
        fangL.visible = fangR.visible = (parts & FANGS) != 0;
        hood.visible = (parts & HOOD) != 0;
        muzzle.visible = (parts & MUZZLE) != 0;
        tail.visible = (parts & CAT_TAIL) != 0;
        scaleTail.visible = (parts & SCALE_TAIL) != 0;
        foxTail.visible = (parts & FOX_TAIL) != 0;
        foxTip.visible = foxTail.visible;
        monkeyTail.visible = (parts & MONKEY_TAIL) != 0;
        snake1.visible = (parts & SNAKE_TAIL) != 0;
        armL.visible = armR.visible = (parts & EXTRA_ARMS) != 0;
    }

    private static int scale(int rgb, float r, float g, float b) {
        return (int) ((rgb >> 16 & 255) * r) << 16 | (int) ((rgb >> 8 & 255) * g) << 8 | (int) ((rgb & 255) * b);
    }

    private static int mix(int a, int b, float t) {
        int r = (int) Mth.lerp(t, a >> 16 & 255, b >> 16 & 255);
        int g = (int) Mth.lerp(t, a >> 8 & 255, b >> 8 & 255);
        int bl = (int) Mth.lerp(t, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    /** Features of a player from their chosen race and look (see {@code client.RaceSkins}). */
    @Nullable
    public static Features forPlayer(net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
        Integer look = state.getRenderData(com.skycraft.client.RaceSkins.LOOK);
        return look == null || look < 0 ? null : Features.of(look);
    }
}
