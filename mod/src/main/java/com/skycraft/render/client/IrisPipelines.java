package com.skycraft.render.client;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.skycraft.Skycraft;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Tells Iris which shader-pack program draws each of Skycraft's own render pipelines (Iris 1.11
 * {@code IrisApi.assignPipeline}). Without it Iris logs "Missing program ... in override list" and draws them with no
 * program at all: Helgen's smoke and flames, storm channels and atmosphere particles vanished under the pack.
 * Reflection only (no compile or runtime dependency on Iris); assignments made before Iris is up are queued and
 * applied on the first {@link #flush()}. The SkycraftAether pack's matching programs: PARTICLES_TRANSLUCENT ->
 * gbuffers_textured (blended, lit), EMISSIVE_ENTITIES -> gbuffers_spidereyes (blended, emissive), BASIC ->
 * gbuffers_basic (untextured, blended, emissive).
 */
public final class IrisPipelines {
    /** Iris {@code IrisProgram} constant names this mod uses. */
    public static final String PARTICLES_TRANSLUCENT = "PARTICLES_TRANSLUCENT", EMISSIVE = "EMISSIVE_ENTITIES",
            BASIC = "BASIC", TEXTURED = "TEXTURED", CLOUDS = "CLOUDS";

    private record Pending(RenderPipeline pipeline, String program) {}

    private static final List<Pending> PENDING = new ArrayList<>();
    private static boolean unavailable;
    private static Object api;
    private static Method assign;
    private static Class<?> programType;

    private IrisPipelines() {}

    /** Map one pipeline to an Iris program (see the constants). Safe to call from static initialisers. */
    public static synchronized void assign(RenderPipeline pipeline, String program) {
        if (unavailable || pipeline == null) return;
        PENDING.add(new Pending(pipeline, program));
        flush();
    }

    /** Apply queued assignments if Iris is ready; a no-op without Iris. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized void flush() {
        if (unavailable || PENDING.isEmpty()) return;
        try {
            if (api == null) {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = c.getMethod("getInstance").invoke(null);
                if (instance == null) return;  // not initialised yet: keep the queue
                programType = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
                assign = c.getMethod("assignPipeline", RenderPipeline.class, programType);
                api = instance;
            }
            for (Pending p : PENDING) assign.invoke(api, p.pipeline(), Enum.valueOf((Class<Enum>) programType, p.program()));
            PENDING.clear();
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            unavailable = true;  // no Iris, or an API without pipeline assignment
            PENDING.clear();
        } catch (Throwable t) {
            unavailable = true;
            PENDING.clear();
            Skycraft.LOGGER.warn("Iris pipeline assignment failed; Skycraft effects may not draw under shaders", t);
        }
    }

    /** The program for a {@link WorldDraw} material from its blend and texturing. */
    static String forWorldMaterial(WorldDraw.Blend blend, boolean textured) {
        if (!textured) return BASIC;
        return switch (blend) {
            case ADDITIVE -> EMISSIVE;
            case ALPHA -> PARTICLES_TRANSLUCENT;
            case REPLACE -> TEXTURED;
        };
    }
}
