package com.skycraft.client;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;

/** Frame-local pose and appearance, captured before deferred entity submission. */
public class PoseRenderState extends LivingEntityRenderState {
    public PartPose[] partPoses;
    public boolean[] partVisible, partSkipDraw;
    public Identifier texture, glowTexture;
    public int modelColor = -1;
    public boolean bankFlying, translucentBody, hideBody, skeletalVariant;
    public float bankPitch, bankRoll, bankHeight, burn;
}
