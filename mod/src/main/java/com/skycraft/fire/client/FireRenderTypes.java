package com.skycraft.fire.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/** Native entity lighting and OIT, explicit soft additive blending, and no duplicate distance fog. */
@EventBusSubscriber(modid="skycraft",value=Dist.CLIENT)
public final class FireRenderTypes {
    static final Identifier FLAME_TEX=texture("far_flame"),SMOKE_TEX=texture("far_smoke"),GLOW_TEX=texture("far_glow");
    private static Identifier texture(String name){return Identifier.fromNamespaceAndPath("skycraft","textures/fire/"+name+".png");}
    private static final RenderPipeline SMOKE_PIPELINE=smokePipeline();
    private static final RenderPipeline FLAME_PIPELINE=flamePipeline();
    private static final OitPipelineSet SMOKE_OIT=OitPipelineSet.builder(id("fire_smoke"),
        oitEntity().withShaderDefine("ALPHA_CUTOUT",0.1f))
        .withAccumulateModifier(p->p.withShaderDefine("PER_FACE_LIGHTING").withBindGroupLayout(BindGroupLayouts.SAMPLER1).withBindGroupLayout(BindGroupLayouts.SAMPLER2)).build();
    private static final OitPipelineSet FLAME_OIT=OitPipelineSet.builder(id("fire_flame"),
        oitEntity(RenderPipelines.EYES_SNIPPET).withShaderDefine("OIT_ADDITIVE")).build();
    public static final RenderType SMOKE=type("skycraft_far_smoke",SMOKE_TEX,SMOKE_PIPELINE,SMOKE_OIT,true);
    public static final RenderType FLAME=type("skycraft_far_flame",FLAME_TEX,FLAME_PIPELINE,FLAME_OIT,false);
    public static final RenderType GLOW=type("skycraft_far_glow",GLOW_TEX,FLAME_PIPELINE,FLAME_OIT,false);
    public static final RenderType FLAME_NATIVE=FLAME,GLOW_NATIVE=GLOW;
    private static Identifier id(String path){return Identifier.fromNamespaceAndPath("skycraft",path);}
    private static RenderPipeline.Builder oitEntity(RenderPipeline.Snippet... snippets){
        return RenderPipeline.builder(snippets).withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.FOG).withBindGroupLayout(BindGroupLayouts.LIGHTING).withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexShader(Identifier.withDefaultNamespace("core/entity")).withFragmentShader(id("core/fire_entity"))
            .withVertexBinding(0,com.mojang.blaze3d.vertex.DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,false)).withCull(false);
    }
    private static RenderPipeline smokePipeline(){
        return RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET).withLocation(id("pipeline/fire_smoke"))
            .withFragmentShader(id("core/fire_entity")).withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withShaderDefine("PER_FACE_LIGHTING").withShaderDefine("ALPHA_CUTOUT",0.1f)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,false)).withCull(false).build();
    }
    private static RenderPipeline flamePipeline(){
        return RenderPipeline.builder(RenderPipelines.ENTITY_NO_LIGHTMAP_SNIPPET,RenderPipelines.EYES_SNIPPET)
            .withLocation(id("pipeline/fire_flame")).withFragmentShader(id("core/fire_entity"))
            .withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.SRC_ALPHA,BlendFactor.ONE,BlendFactor.ONE,BlendFactor.ONE_MINUS_SRC_ALPHA)))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,false)).withCull(false).build();
    }
    private static RenderType type(String name,Identifier texture,RenderPipeline pipeline,OitPipelineSet oit,boolean lit){
        var builder=RenderSetup.builder(pipeline).setOitPipelines(oit).sortOnUpload()
            .withTexture("Sampler0",texture,()->RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.LINEAR,FilterMode.LINEAR,false));
        if(lit)builder.useLightmap().useOverlay();
        return RenderType.create(name,builder.createRenderSetup());
    }
    @SubscribeEvent public static void register(RegisterRenderPipelinesEvent event){
        event.registerPipeline(SMOKE_PIPELINE);event.registerPipeline(FLAME_PIPELINE);
        for(var oit:java.util.List.of(SMOKE_OIT,FLAME_OIT)){
            event.registerPipeline(oit.depthBoundsPipeline());event.registerPipeline(oit.transmittancePipeline());event.registerPipeline(oit.accumulatePipeline());
        }
    }
    private FireRenderTypes(){}
}
