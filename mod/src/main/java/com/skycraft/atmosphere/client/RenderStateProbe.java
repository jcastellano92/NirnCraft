package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Requested QA snapshots only. Depth readback may synchronize the GPU; never used for frame timing. */
final class RenderStateProbe {
    private RenderStateProbe() {}

    static String snapshot(RenderLevelStageEvent event) {
        RenderSystem.assertOnRenderThread();
        var mc=Minecraft.getInstance();
        try { org.lwjgl.opengl.GL.getCapabilities(); }
        catch (IllegalStateException unavailable) {
            return "stage=" + event.getClass().getSimpleName() + " backend=" + RenderSystem.getDevice().getDeviceInfo()
                    + " gl_depth_readback=unavailable gpu_debug=" + RenderSystem.getDevice().getLastDebugMessages()
                    + " " + SkyGeometry.diagnostics();
        }
        int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        var color=BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,color);
        var range=BufferUtils.createFloatBuffer(2);
        GL11.glGetFloatv(GL11.GL_DEPTH_RANGE,range);
        var viewport=BufferUtils.createIntBuffer(4);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        var target = mc.gameRenderer.mainRenderTarget();
        return "stage=" + event.getClass().getSimpleName() + " shaders=" + ShaderCompat.shadersActive()
                + " draw_fbo=" + draw + " read_fbo=" + GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
                + " draw_color=" + attachment(draw, GL30.GL_COLOR_ATTACHMENT0) + " draw_depth=" + attachment(draw, GL30.GL_DEPTH_ATTACHMENT)
                + " target_color=" + target.getColorTexture().getFormat() + " target_depth=" + target.getDepthTexture().getFormat()
                + " viewport=" + viewport.get(0) + "," + viewport.get(1) + "," + viewport.get(2) + "," + viewport.get(3)
                + " depth=" + GL11.glIsEnabled(GL11.GL_DEPTH_TEST) + " depth_func=" + GL11.glGetInteger(GL11.GL_DEPTH_FUNC)
                + " depth_write=" + GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) + " depth_clear=" + GL11.glGetFloat(GL11.GL_DEPTH_CLEAR_VALUE)
                + " depth_clamp=" + GL11.glIsEnabled(org.lwjgl.opengl.GL32.GL_DEPTH_CLAMP) + " depth_range=" + range.get(0) + "," + range.get(1)
                + " color_write=" + color.get(0) + "," + color.get(1) + "," + color.get(2) + "," + color.get(3)
                + " cull=" + GL11.glIsEnabled(GL11.GL_CULL_FACE) + " cull_mode=" + GL11.glGetInteger(GL11.GL_CULL_FACE_MODE)
                + " front_face=" + GL11.glGetInteger(GL11.GL_FRONT_FACE) + " blend=" + GL11.glIsEnabled(GL11.GL_BLEND)
                + " blend_rgb=" + GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB) + "," + GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
                + " clip_control=" + clipControl() + " depth_samples=" + depthSamples(draw,viewport.get(2),viewport.get(3))
                + " device=" + RenderSystem.getDevice().getDeviceInfo()
                + " gpu_debug=" + RenderSystem.getDevice().getLastDebugMessages()
                + " " + iris() + " " + embeddium() + " " + SkyGeometry.diagnostics();
    }

    private static String clipControl() {
        var caps=org.lwjgl.opengl.GL.getCapabilities();
        return caps.OpenGL45 || caps.GL_ARB_clip_control
                ?GL11.glGetInteger(org.lwjgl.opengl.GL45.GL_CLIP_ORIGIN)+","+GL11.glGetInteger(org.lwjgl.opengl.GL45.GL_CLIP_DEPTH_MODE)
                :"unsupported";
    }

    private static int attachment(int framebuffer,int slot) {
        return framebuffer==0?-1:GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,slot,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
    }

    private static String iris() {
        try {
            var settings=Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
            Object state=settings.getField("INSTANCE").get(null);
            Object manager=Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
            Object pipeline=manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            return "iris_pipeline="+(pipeline==null?"null":pipeline.getClass().getSimpleName())
                    +" extended_vertices="+settings.getMethod("shouldUseExtendedVertexFormat").invoke(state)
                    +" iris_reload_required="+settings.getMethod("isReloadRequired").invoke(state)
                    +" iris_depth_color_locked="+Class.forName("net.irisshaders.iris.gl.blending.DepthColorStorage")
                            .getMethod("isDepthColorLocked").invoke(null)
                    +" iris_blend_locked="+Class.forName("net.irisshaders.iris.gl.blending.BlendModeStorage")
                            .getMethod("isBlendLocked").invoke(null);
        } catch(ReflectiveOperationException|LinkageError e) { return "iris_probe="+e.getClass().getSimpleName(); }
    }

    private static String embeddium() {
        try {
            var type=Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            Object renderer=type.getMethod("instanceNullable").invoke(null);
            if(renderer==null) return "sodium_renderer=null";
            return "sodium_visible="+type.getMethod("getVisibleChunkCount").invoke(renderer)
                    +" sodium_complete="+type.getMethod("isTerrainRenderComplete").invoke(renderer)
                    +" sodium_chunks="+type.getMethod("getChunksDebugString").invoke(renderer);
        } catch(ReflectiveOperationException|LinkageError e) { return "sodium_probe="+e.getClass().getSimpleName(); }
    }

    private static String depthSamples(int framebuffer,int width,int height) {
        // Non-default pack skips could write outside this one-float buffer. Do not
        // change shared pixel-pack state merely to obtain an optional QA reading.
        if(width<=0 || height<=0 || GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING)!=0
                || GL11.glGetBoolean(GL11.GL_PACK_SWAP_BYTES) || GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_SAMPLES)>0
                || (framebuffer!=0 && attachment(framebuffer,GL30.GL_DEPTH_ATTACHMENT)==0)
                || GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS)!=0
                || GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS)!=0 || GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH)!=0)
            return "skipped_pack_layout";
        int old=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        var value=BufferUtils.createFloatBuffer(1);
        StringBuilder result=new StringBuilder();
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,framebuffer);
            for(int y:new int[]{height/8,height/3,height/2}) for(int x:new int[]{width/4,width/2,width*3/4}) {
                GL11.glReadPixels(x,y,1,1,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,value);
                if(result.length()>0) result.append(',');
                result.append(value.get(0));
            }
        } finally { GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,old); }
        return result.toString();
    }
}
