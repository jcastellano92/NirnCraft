import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import java.nio.file.*;
import java.util.*;

/** Offline native shaderc/SPIR-V reflection; uses RenderPearl's exact compiler options without creating a GPU device. */
public final class NativeShaderAudit263 {
    static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    static long compiler;
    static JsonObject compile(JsonObject unit) throws Exception {
        long options=Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options,0,4202496);
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options,true);
        Shaderc.shaderc_compile_options_set_preserve_bindings(options,false);
        Shaderc.shaderc_compile_options_set_generate_debug_info(options);
        Shaderc.shaderc_compile_options_set_optimization_level(options,0);
        for(var macro:unit.getAsJsonObject("defines").entrySet())Shaderc.shaderc_compile_options_add_macro_definition(options,macro.getKey(),macro.getValue().getAsString());
        String name=unit.get("name").getAsString();boolean fragment=unit.get("stage").getAsString().equals("fragment");
        long result=0;
        try{
            result=Shaderc.shaderc_compile_into_spv(compiler,Files.readString(Path.of(unit.get("source").getAsString())),fragment?1:0,name,"main",options);
            int status=Shaderc.shaderc_result_get_compilation_status(result);
            if(status!=0)throw new IllegalStateException(name+": "+Shaderc.shaderc_result_get_error_message(result));
            var spv=Shaderc.shaderc_result_get_bytes(result);var copy=MemoryUtil.memCalloc(spv.remaining());MemoryUtil.memCopy(spv,copy);
            try(var module=new SPIRVModule(copy,fragment?ShaderType.FRAGMENT:ShaderType.VERTEX)){
                var reflection=module.reflect();var data=new JsonObject();data.addProperty("name",name);
                var inputs=new JsonArray();var outputs=new JsonArray();var uniforms=new JsonArray();
                for(var variable:reflection.inputs()){var item=new JsonObject();item.addProperty("name",variable.name());item.addProperty("location",variable.location());item.addProperty("base",variable.type().baseType());item.addProperty("size",variable.type().vectorSize());inputs.add(item);}
                for(var variable:reflection.outputs()){var item=new JsonObject();item.addProperty("name",variable.name());item.addProperty("location",variable.location());item.addProperty("base",variable.type().baseType());item.addProperty("size",variable.type().vectorSize());outputs.add(item);}
                for(var uniform:reflection.descriptors()){var item=new JsonObject();item.addProperty("name",uniform.name());item.addProperty("kind",uniform.resourceType());item.addProperty("dimensions",uniform.type().dimensions());uniforms.add(item);}
                data.add("inputs",inputs);data.add("outputs",outputs);data.add("uniforms",uniforms);return data;
            }
        }finally{if(result!=0)Shaderc.shaderc_result_release(result);Shaderc.shaderc_compile_options_release(options);}
    }
    static JsonObject pipeline(com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline){
        var data=new JsonObject();data.addProperty("id",pipeline.getLocation().toString());
        var shaders=new JsonObject();pipeline.getShaders().forEach((type,id)->shaders.addProperty(type.toString().toLowerCase(java.util.Locale.ROOT),id.toString()));data.add("shaders",shaders);
        var defines=new JsonObject();pipeline.getShaderDefines().values().forEach(defines::addProperty);pipeline.getShaderDefines().flags().forEach(flag->defines.addProperty(flag,""));data.add("defines",defines);
        var uniforms=new JsonObject();for(var uniform:com.mojang.renderpearl.api.pipeline.BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())){var value=new JsonObject();value.addProperty("kind",com.mojang.renderpearl.frontend.shaders.SpvUtil.resourceType(uniform.type()));value.addProperty("type",uniform.type().toString());uniforms.add(uniform.name(),value);}data.add("uniforms",uniforms);
        var attributes=new JsonObject();for(var format:pipeline.getVertexFormatBindings())if(format!=null)for(var attribute:format.getElements()){var item=new JsonObject();item.addProperty("components",attribute.format().componentCount());item.addProperty("type",attribute.format().componentType().toString());attributes.add(attribute.name(),item);}data.add("attributes",attributes);
        data.addProperty("colorTargets",pipeline.getColorTargetStates().size());return data;
    }
    static void describe(Path output) throws Exception{
        var pipelines=new JsonArray();int index=0;
        for(boolean textured:new boolean[]{false,true})for(boolean fog:new boolean[]{false,true})for(boolean far:new boolean[]{false,true})for(var blend:com.skycraft.render.client.WorldDraw.Blend.values()){
            var material=com.skycraft.render.client.WorldDraw.material(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft","audit_"+(index++)),
                textured?com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR:com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
                com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS,blend,true,fog,far);
            pipelines.add(pipeline(material.pipeline()));
            for(var stage:net.minecraft.client.renderer.oit.OitStage.values())pipelines.add(pipeline(material.oit().getPipeline(stage)));
        }
        for(String type:List.of("com.skycraft.fire.client.FireRenderTypes","com.skycraft.client.UiPipelines","com.skycraft.realms.client.RealmLightmap")){
            for(var field:Class.forName(type).getDeclaredFields()){
                if(!java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;field.setAccessible(true);
                if(field.getType()==com.mojang.renderpearl.api.pipeline.RenderPipeline.class)pipelines.add(pipeline((com.mojang.renderpearl.api.pipeline.RenderPipeline)field.get(null)));
                if(field.getType()==net.minecraft.client.renderer.oit.OitPipelineSet.class){var oit=(net.minecraft.client.renderer.oit.OitPipelineSet)field.get(null);for(var stage:net.minecraft.client.renderer.oit.OitStage.values())pipelines.add(pipeline(oit.getPipeline(stage)));}
            }
        }
        Files.writeString(output,JSON.toJson(pipelines));System.out.println("Native pipeline metadata: "+pipelines.size());
    }
    public static void main(String[] args) throws Exception {
        if(args[0].equals("describe")){describe(Path.of(args[1]));return;}
        var manifest=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        var report=new JsonObject();var units=new JsonArray();var failures=new JsonArray();compiler=Shaderc.shaderc_compiler_initialize();
        if(compiler==0)throw new IllegalStateException("Native shaderc compiler unavailable");
        try{
            for(var entry:manifest){var unit=entry.getAsJsonObject();try{units.add(compile(unit));}catch(Exception failure){failures.add(failure.toString());}}
            var metadata=JsonParser.parseString(Files.readString(Path.of(args[2]))).getAsJsonObject().getAsJsonObject("pack");
            var formats=new JsonObject();
            for(var type:PackType.values()){
                var range=PackFormat.packCodec(type).codec().parse(JsonOps.INSTANCE,metadata).getOrThrow();
                var expected=type==PackType.CLIENT_RESOURCES?PackFormat.of(97,1):PackFormat.of(121,0);
                if(!range.isValueInRange(expected))throw new IllegalStateException("pack.mcmeta excludes "+type+" "+expected);
                formats.addProperty(type.toString(),range.toString());
            }
            report.add("packFormats",formats);
            if(args.length>3){
                var rules=JsonParser.parseString(Files.readString(Path.of(args[3]))).getAsJsonObject().getAsJsonObject("pack");
                var range=PackFormat.packCodec(PackType.SERVER_DATA).codec().parse(JsonOps.INSTANCE,rules).getOrThrow();
                if(!range.isValueInRange(PackFormat.of(121,0)))throw new IllegalStateException("Recipe-rules pack excludes native data121.0");
                report.addProperty("recipeRulesFormat",range.toString());
            }
        }finally{Shaderc.shaderc_compiler_release(compiler);}
        report.add("compiled",units);report.add("failures",failures);Files.writeString(Path.of(args[1]),JSON.toJson(report));
        System.out.println("Native shaderc compiled "+units.size()+" units; failures "+failures.size());
        if(!failures.isEmpty()){System.err.println(failures);System.exit(1);}
    }
}
