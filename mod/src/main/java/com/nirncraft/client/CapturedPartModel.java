package com.nirncraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/** Captures each animated part before submission, including visibility for layered skin/bone/cloth passes. */
public final class CapturedPartModel extends Model<CapturedPartModel.State> {
    public record State(PartPose[] poses, boolean[] visible, boolean[] skipDraw) {}
    private final List<ModelPart> parts;

    public CapturedPartModel(ModelPart root) {
        super(root, RenderTypes::entityCutout);
        parts = root.getAllParts();
    }

    public void submit(SubmitNodeCollector collector, PoseStack pose, RenderType type, int light, int overlay, int color) {
        PartPose[] poses = new PartPose[parts.size()];
        boolean[] visible = new boolean[parts.size()], skip = new boolean[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            ModelPart part = parts.get(i);
            poses[i] = part.storePose();
            visible[i] = part.visible;
            skip[i] = part.skipDraw;
        }
        collector.submitModel(this, new State(poses, visible, skip), pose, type, light, overlay, color, null, 0);
    }

    @Override
    public void setupAnim(State state) {
        for (int i = 0; i < parts.size(); i++) {
            ModelPart part = parts.get(i);
            part.loadPose(state.poses()[i]);
            part.visible = state.visible()[i];
            part.skipDraw = state.skipDraw()[i];
        }
    }
}
