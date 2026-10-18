package com.skycraft.realms.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.util.Optional;

/** Exact post-lightmap realm tint: one 16x16 GPU draw/copy when vanilla refreshes light, without CPU readback. */
final class RealmLightmap {
    private static final RenderPipeline PIPELINE=RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("skycraft","pipeline/realm_lightmap"))
        .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("skycraft","core/realm_lightmap"))
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS).withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
        .withColorTargetState(ColorTargetState.DEFAULT).build();
    private static GpuTexture scratch;
    private static GpuTextureView scratchView;
    private static RealmsClient.Frame appliedFrame;
    static void register(RegisterRenderPipelinesEvent event){event.registerPipeline(PIPELINE);}
    static void apply(RealmsClient.Frame frame){
        if(appliedFrame==frame)return;
        var lightmap=Minecraft.getInstance().gameRenderer.levelLightmap();
        if(lightmap==null || lightmap.isClosed())return;
        var device=RenderSystem.getDevice();
        if(scratch==null || scratch.isClosed()){
            scratch=device.createTexture("Skycraft realm lightmap tint",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,16,16,1,1);
            scratchView=device.createTextureView(scratch);
        }
        Vector4f tint=switch(frame.realm()){
            case DEADLANDS -> new Vector4f(1.10f,.82f,.70f,1);
            case SOVNGARDE -> new Vector4f(1.06f,1.02f,.92f,1);
            case APOCRYPHA -> new Vector4f(.80f,1.05f,.85f,1);
            case SOUL_CAIRN -> new Vector4f(.95f,.85f,1.12f,1);
            case COLDHARBOUR -> new Vector4f(.82f,.88f,1.10f,1);
            default -> new Vector4f(1,1,1,1);
        };
        var encoder=device.createCommandEncoder();
        try(RenderPass pass=encoder.createRenderPass(()->"Skycraft realm lightmap tint",scratchView,Optional.empty())){
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms",RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(),tint));
            pass.setUniform("Sampler0",lightmap,RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,false));
            pass.draw(3,1,0,0);
        }
        encoder.copyTextureToTexture(scratch,lightmap.texture(),0,0,0,0,0,16,16);
        appliedFrame=frame;
    }
    static void clear(){
        appliedFrame=null;
        if(scratchView!=null){scratchView.close();scratchView=null;}
        if(scratch!=null){scratch.close();scratch=null;}
    }
    private RealmLightmap(){}
}
