package com.skycraft.inventory.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/** Rotatable inventory models extracted through the public picture-in-picture pipeline. */
public final class SkyItemRender extends PictureInPictureRenderer<SkyItemRender.State> {
    public record State(ItemStackRenderState item, float rotationX, float rotationY,
                        int x0, int y0, int x1, int y1, float scale, int tint, Matrix3x2fc pose,
                        @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
            implements PictureInPictureRenderState {}

    public static void extract(GuiGraphicsExtractor graphics, ItemStack stack, float cx, float cy,
                               float scale, float rotationX, float rotationY) {
        extract(graphics,stack,cx,cy,scale,rotationX,rotationY,0xffffffff);
    }
    public static void extract(GuiGraphicsExtractor graphics,ItemStack stack,float cx,float cy,float scale,float rotationX,float rotationY,int tint){
        if (stack.isEmpty() || scale <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        ItemStackRenderState item = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(item, stack, ItemDisplayContext.FIXED, mc.level, mc.player, 0);
        int radius = Math.max(16, (int) Math.ceil(scale * 1.5f));
        int x0 = (int) cx - radius, y0 = (int) cy - radius, x1 = (int) cx + radius, y1 = (int) cy + radius;
        Matrix3x2f pose = new Matrix3x2f(graphics.pose());
        ScreenRectangle scissor = graphics.peekScissorStack();
        graphics.submitPictureInPictureRenderState(new State(item, rotationX, rotationY, x0, y0, x1, y1,
                scale, tint, pose, scissor, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, pose, scissor)));
    }

    @Override protected void blitTexture(State state,net.minecraft.client.renderer.state.gui.GuiRenderState output){
        if(state.tint()==0xffffffff){super.blitTexture(state,output);return;}
        // Use the native PiP blit and texture ownership, changing only its explicit per-widget colour.
        super.blitTexture(state,new net.minecraft.client.renderer.state.gui.GuiRenderState(){
            @Override public void addBlitToCurrentLayer(net.minecraft.client.renderer.state.gui.BlitRenderState blit){
                output.addBlitToCurrentLayer(new net.minecraft.client.renderer.state.gui.BlitRenderState(blit.pipeline(),blit.textureSetup(),blit.pose(),
                    blit.x0(),blit.y0(),blit.x1(),blit.y1(),blit.u0(),blit.u1(),blit.v0(),blit.v1(),state.tint(),blit.scissorArea(),blit.bounds()));
            }
        });
    }

    @Override
    public Class<State> getRenderStateClass() {
        return State.class;
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height / 2f;
    }

    @Override
    protected void renderToTexture(State state, PoseStack pose, SubmitNodeCollector collector) {
        Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
        pose.scale(1, -1, -1);
        pose.rotateDegrees(Axis.XP, state.rotationX());
        pose.rotateDegrees(Axis.YP, state.rotationY());
        state.item().submit(pose, collector, 0xF000F0, OverlayTexture.NO_OVERLAY, 0);
    }

    @Override
    protected String getTextureLabel() {
        return "skycraft item preview";
    }
}
