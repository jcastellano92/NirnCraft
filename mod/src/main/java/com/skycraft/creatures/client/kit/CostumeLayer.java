package com.skycraft.creatures.client.kit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.skycraft.creatures.client.rig.Bone;
import com.skycraft.creatures.client.rig.CreatureDef;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Costume pieces (helmets, hoods, ears, antlers, capes, skirts) worn over a humanoid model: a costume rig
 * ({@code rig.defs.Costumes}) whose six top-level parts copy the humanoid's head, body, arms and legs every frame.
 * Optional pieces (named bones) are shown or hidden per entity, e.g. the draugr's helmet by rank.
 */
public class CostumeLayer<T extends Mob, M extends HumanoidModel<T>> extends RenderLayer<T, M> {
    private static final String[] PARTS = {"head", "body", "right_arm", "left_arm", "right_leg", "left_leg"};
    private final ModelPart[] parts = new ModelPart[PARTS.length];
    private final Map<String, ModelPart> optional = new HashMap<>();
    private final Function<T, ResourceLocation> texture;
    private final BiPredicate<T, String> visible;

    /**
     * @param optionalBones names of the costume bones that are toggled by {@code visible}
     */
    public CostumeLayer(RenderLayerParent<T, M> parent, ModelPart bakedRoot, CreatureDef def, Function<T, ResourceLocation> texture,
                        List<String> optionalBones, BiPredicate<T, String> visible) {
        super(parent);
        this.texture = texture;
        this.visible = visible;
        ModelPart root = bakedRoot.getChild("root");
        for (int i = 0; i < PARTS.length; i++) parts[i] = root.getChild(PARTS[i]);
        for (String name : optionalBones) {
            ModelPart p = find(root, def, name);
            if (p != null) optional.put(name, p);
        }
    }

    private static ModelPart find(ModelPart root, CreatureDef def, String name) {
        if (!def.rig.has(name)) return null;
        List<String> path = new ArrayList<>();
        for (Bone b = def.rig.bone(name); b != null && b.parent != null; b = b.parent) path.add(0, b.name);
        ModelPart p = root;
        for (String n : path) {
            if (!p.hasChild(n)) return null;
            p = p.getChild(n);
        }
        return p;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, T entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (entity.isInvisible()) return;
        M model = this.getParentModel();
        ModelPart[] src = {model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
        for (int i = 0; i < parts.length; i++) parts[i].copyFrom(src[i]);
        optional.forEach((name, part) -> part.visible = visible.test(entity, name));
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(texture.apply(entity)));
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0f);
        for (ModelPart p : parts) p.render(pose, vc, light, overlay, 1f, 1f, 1f, 1f);
    }
}
