package com.nirncraft.atlas;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the precise upstream omission; actual deferred destruction still needs a Vulkan validation run. */
class VkPipelineDestroyCompatibilityTest {
    @Test
    void officialInstanceStillUsesTheExactDebugFactoryAtConstruction() throws Exception {
        int[] calls = {0};
        String owner = "com/mojang/renderpearl/backend/vulkan/VulkanInstance";
        try (var stream = getClass().getClassLoader().getResourceAsStream(owner + ".class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("<init>") || !descriptor.equals("(IZZ)V")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String callOwner, String method, String type, boolean isInterface) {
                            if (callOwner.equals("com/mojang/renderpearl/backend/vulkan/VulkanDebug") && method.equals("create") &&
                                    type.equals("(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;")) calls[0]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(1, calls[0], "recheck diagnostic-label injection after a Minecraft update");
        try (var stream = getClass().getClassLoader().getResourceAsStream("nirncraft.mixins.json")) {
            assertNotNull(stream);
            var client = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("client").asList();
            for (String mixin : new String[]{"VkDiagnosticLabels", "VkEncoderMemory", "VkCommandPoolMemory"})
                assertEquals(1, client.stream().filter(e -> e.getAsString().equals(mixin)).count());
        }
    }

    @Test
    void officialDestructorStillOmitsExactlyTheTwoStencilVariants() throws Exception {
        String owner = "com/mojang/renderpearl/backend/vulkan/VulkanRenderPipeline";
        Set<String> storedPipelines = new HashSet<>(), destroyedPipelines = new HashSet<>();
        int[] destroyCalls = {0};
        try (var stream = getClass().getClassLoader().getResourceAsStream(owner + ".class")) {
            assertNotNull(stream, "the exact Minecraft target must be available");
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("<init>") && !(name.equals("destroy") && descriptor.equals("()V"))) return null;
                    boolean destroy = name.equals("destroy");
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String fieldOwner, String field, String type) {
                            if (!fieldOwner.equals(owner) || !type.equals("J") || !field.endsWith("Pipeline")) return;
                            if (!destroy && opcode == Opcodes.PUTFIELD) storedPipelines.add(field);
                            if (destroy && opcode == Opcodes.GETFIELD) destroyedPipelines.add(field);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String callOwner, String method, String type, boolean isInterface) {
                            if (destroy && callOwner.equals("org/lwjgl/vulkan/VK12") && method.equals("vkDestroyPipeline")) destroyCalls[0]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(Set.of("withStencilPipeline", "withDepthStencilPipeline", "withDepthPipeline", "withoutDepthPipeline"), storedPipelines);
        assertEquals(Set.of("withDepthPipeline", "withoutDepthPipeline"), destroyedPipelines,
                "upstream destruction changed: recheck/remove the workaround before using this Minecraft version");
        assertEquals(2, destroyCalls[0]);
    }

    @Test
    void officialFrontendStillOmitsProfilerClose() throws Exception {
        String owner = "com/mojang/renderpearl/frontend/FrontendGpuDevice";
        Set<String> closedOwners = new HashSet<>();
        try (var stream = getClass().getClassLoader().getResourceAsStream(owner + ".class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("close") || !descriptor.equals("()V")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String callOwner, String method, String type, boolean isInterface) {
                            if (method.equals("close") && type.equals("()V")) closedOwners.add(callOwner);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(Set.of("com/mojang/renderpearl/frontend/shaders/PipelineBuilder",
                "com/mojang/renderpearl/backend/api/GpuDeviceBackend"), closedOwners,
                "frontend shutdown changed: recheck/remove the profiler workaround");
    }

    @Test
    void correctionIsRegisteredOnlyForTheClient() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("nirncraft.mixins.json")) {
            assertNotNull(stream);
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("com.nirncraft.mixin", config.get("package").getAsString());
            for (String mixin : new String[]{"VkPipelineDestroy", "GpuProfilerClose"}) {
                assertEquals(1, config.getAsJsonArray("client").asList().stream()
                        .filter(e -> e.getAsString().equals(mixin)).count());
                for (String section : new String[]{"mixins", "server"}) {
                    if (config.has(section)) assertTrue(config.getAsJsonArray(section).asList().stream()
                            .noneMatch(e -> e.getAsString().equals(mixin)));
                }
            }
        }
    }
}
