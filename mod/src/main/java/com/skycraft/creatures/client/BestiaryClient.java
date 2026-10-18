package com.skycraft.creatures.client;

import com.skycraft.Skycraft;
import com.skycraft.creatures.Bestiary;
import com.skycraft.creatures.client.kit.RigBaker;
import com.skycraft.creatures.client.kit.RigRenderer;
import com.skycraft.creatures.client.rig.defs.Defs;
import com.skycraft.creatures.entity.SkyCreature;
import com.skycraft.creatures.entity.anim.AnimatedCreature;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Client registration of the rig-model creatures: one model layer per creature definition
 * ({@link Defs}), and a {@link RigRenderer} per entity type with its textures
 * ({@code textures/entity/creatures/<name>[_variant].png}, glow layers {@code _glow}).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class BestiaryClient {
    private static final Map<String, ModelLayerLocation> LAYERS = new HashMap<>();

    private BestiaryClient() {}

    public static ModelLayerLocation layer(String def) {
        return LAYERS.computeIfAbsent(def, n -> new ModelLayerLocation(Identifier.fromNamespaceAndPath(Skycraft.MODID, "rig/" + n), "main"));
    }

    public static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath(Skycraft.MODID, "textures/entity/creatures/" + name + ".png");
    }

    /** Variant textures: index = variant, clamped. */
    public static Identifier[] texs(String... names) {
        Identifier[] out = new Identifier[names.length];
        for (int i = 0; i < names.length; i++) out[i] = tex(names[i]);
        return out;
    }

    public static <T extends Mob> Function<T, Identifier> byVariant(Identifier[] textures) {
        return e -> textures[Math.floorMod(e instanceof AnimatedCreature ac ? ac.animVariant() : 0, textures.length)];
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        for (String name : Defs.ALL.keySet()) {
            event.registerLayerDefinition(layer(name), () -> RigBaker.layer(Defs.get(name).rig));
        }
    }

    /** Registers a rig renderer: {@code def} names the model, {@code textures} the variant skins. */
    static <T extends Mob> void reg(EntityRenderersEvent.RegisterRenderers event, Supplier<? extends EntityType<T>> type,
                                                       String def, float shadow, Function<T, Identifier> texture,
                                                       Consumer<RigRenderer<T>> config) {
        event.registerEntityRenderer(type.get(), (EntityRendererProvider.Context ctx) -> {
            RigRenderer<T> r = new RigRenderer<>(ctx, layer(def), Defs.get(def), shadow, texture);
            config.accept(r);
            return r;
        });
    }

    static <T extends Mob> void simple(EntityRenderersEvent.RegisterRenderers event, Supplier<? extends EntityType<T>> type,
                                                     String def, float shadow, float scale, String... variants) {
        rig(event, type, def, shadow, scale, variants, null, r -> {});
    }

    /**
     * A rig renderer when the creature's model exists ({@link Defs}); otherwise the creature is drawn as nothing (and a
     * warning logged) rather than crashing the client. {@code glow}: glow texture names per variant (or one shared).
     */
    public static <T extends Mob> void rig(EntityRenderersEvent.RegisterRenderers event, Supplier<? extends EntityType<T>> type,
                                           String def, float shadow, float scale, String[] variants, String[] glow,
                                           Consumer<RigRenderer<T>> config) {
        if (!Defs.ALL.containsKey(def)) {
            Skycraft.LOGGER.warn("No creature model '{}' for {}: not drawn", def, EntityType.getKey(type.get()));
            event.registerEntityRenderer(type.get(), NoopRenderer::new);
            return;
        }
        Identifier[] t = texs(variants == null || variants.length == 0 ? new String[]{def} : variants);
        Identifier[] g = glow == null ? null : texs(glow);
        reg(event, type, def, shadow, byVariant(t), r -> {
            r.scale(scale);
            if (g != null) r.glow(e -> g[Math.floorMod(e instanceof AnimatedCreature ac ? ac.animVariant() : 0, g.length)]);
            config.accept(r);
        });
    }

    private static String[] v(String... names) {
        return names;
    }

    private static String[] numbered(String base, String... suffixes) {
        String[] out = new String[suffixes.length];
        for (int i = 0; i < suffixes.length; i++) out[i] = suffixes[i].isEmpty() ? base : base + "_" + suffixes[i];
        return out;
    }

    /**
     * Runs after every other renderer registration: Alduin replaces the End dragon's look, and the Dwemer automatons
     * (the dungeons module's entities) get their animated models: the sphere rolls closed and unfolds, the centurion
     * vents steam, all of them strike with their attack animations.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void overrides(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(net.minecraft.world.entity.EntityTypes.ENDER_DRAGON, AlduinRenderer::new);
        rig(event, com.skycraft.dungeons.DungeonsRegistry.DWARVEN_SPIDER, "dwarven_spider", 0.5f, 1.0f, null, v("dwarven_spider_glow"), r -> {});
        rig(event, com.skycraft.dungeons.DungeonsRegistry.DWARVEN_SPHERE, "dwarven_sphere", 0.5f, 1.0f, null, v("dwarven_sphere_glow"),
                r -> r.withExtra((m, sphere, swing, amount, age, yaw, pitch) -> {
                    if (sphere.deathTime > 0) return;
                    float partial = age - sphere.tickCount;
                    float open = net.minecraft.util.Mth.lerp(partial, sphere.openAnimO, sphere.openAnim);
                    if (open <= 0.01f) {
                        m.resetAll();
                        m.applySlot(com.skycraft.creatures.entity.anim.AnimSlot.SLEEP, age / 20f, 1f);
                    } else if (open < 0.99f) {
                        m.resetAll();
                        m.applySlot(com.skycraft.creatures.entity.anim.AnimSlot.EMERGE, open * 1.6f, 1f);
                    }
                }));
        rig(event, com.skycraft.dungeons.DungeonsRegistry.DWARVEN_CENTURION, "dwarven_centurion", 1.2f, 2.0f, null, v("dwarven_centurion_glow"),
                r -> r.withExtra((m, centurion, swing, amount, age, yaw, pitch) -> {
                    if (centurion.isSteaming() && centurion.deathTime <= 0) {
                        m.applySlot(com.skycraft.creatures.entity.anim.AnimSlot.SPECIAL, 1.2f, 1f);
                    }
                }));
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Bestiary.BOLT.get(), NoopRenderer::new);
        // Skyrim
        rig(event, Bestiary.WOLF, "wolf", 0.5f, 1.0f, v("wolf", "wolf_ice"), null, r -> {});
        rig(event, Bestiary.CHAURUS, "chaurus", 0.7f, 1.0f, null, null, r -> {});
        rig(event, Bestiary.CHAURUS_HUNTER, "chaurus_hunter", 0.6f, 1.0f, null, null, r -> r.bank().translucent());
        rig(event, Bestiary.RIEKLING, "riekling", 0.4f, 1.0f, null, null, r -> {});
        rig(event, Bestiary.ASH_SPAWN, "ash_spawn", 0.5f, 1.0f, null, v("ash_spawn_glow"), r -> {});
        rig(event, Bestiary.WISP, "wisp", 0.0f, 1.0f, null, v("wisp_glow"), RigRenderer::translucent);
        rig(event, Bestiary.WILL_O_WISP, "will_o_wisp", 0.0f, 1.0f, null, v("will_o_wisp_glow"), RigRenderer::translucent);
        rig(event, Bestiary.DWARVEN_BALLISTA, "dwarven_ballista", 0.7f, 1.0f, null, v("dwarven_ballista_glow"), r -> {});
        rig(event, Bestiary.DRAGON_PRIEST, "dragon_priest", 0.6f, 1.0f, numbered("dragon_priest", "", "morokei", "rahgot", "vokun",
                "volsung", "nahkriin", "otar", "krosis", "hevnoraak", "konahrik"), v("dragon_priest_glow"), r -> {});
        // daedra
        rig(event, Bestiary.DREMORA, "dremora", 0.5f, 1.0f, null, v("dremora_glow"), r -> {});
        rig(event, Bestiary.FLAME_ATRONACH, "flame_atronach", 0.4f, 1.0f, null, v("flame_atronach_glow"), r -> {});
        rig(event, Bestiary.FROST_ATRONACH, "frost_atronach", 0.9f, 1.1f, null, v("frost_atronach_glow"), r -> {});
        rig(event, Bestiary.STORM_ATRONACH, "storm_atronach", 0.6f, 1.0f, null, v("storm_atronach_glow"), r -> {});
        rig(event, Bestiary.SCAMP, "scamp", 0.4f, 1.0f, null, v("scamp_glow"), r -> {});
        rig(event, Bestiary.IMP, "imp", 0.3f, 1.0f, null, v("imp_glow"), RigRenderer::bank);
        // Morrowind
        rig(event, Bestiary.CLIFF_RACER, "cliff_racer", 0.4f, 1.0f, null, null, RigRenderer::bank);
        rig(event, Bestiary.GUAR, "guar", 0.7f, 1.1f, v("guar", "guar_pack"), null, r -> r.withExtra((m, e, a, b, c, d, f) -> {
            boolean pack = e.animVariant() == 1;
            var saddle = m.bone("saddle");
            if (saddle != null) saddle.visible = pack;
            var packs = m.bone("packs");
            if (packs != null) packs.visible = pack;
        }));
        rig(event, Bestiary.KAGOUTI, "kagouti", 0.6f, 0.85f, null, null, r -> {});
        rig(event, Bestiary.ALIT, "alit", 0.5f, 1.0f, null, null, r -> {});
        rig(event, Bestiary.NIX_HOUND, "nix_hound", 0.5f, 0.8f, null, null, r -> {});
        rig(event, Bestiary.NETCH, "netch", 1.0f, 1.35f, v("netch", "netch_betty"), null, r -> {});
        rig(event, Bestiary.SILT_STRIDER, "silt_strider", 2.0f, 3.75f, null, null, r -> {});
        // Cyrodiil, Black Marsh, Elsweyr
        rig(event, Bestiary.MINOTAUR, "minotaur", 0.7f, 1.1f, null, null, r -> {});
        rig(event, Bestiary.OGRE, "ogre", 0.8f, 1.25f, null, null, r -> {});
        rig(event, Bestiary.LAND_DREUGH, "land_dreugh", 0.6f, 0.95f, null, null, r -> {});
        rig(event, Bestiary.WAMASU, "wamasu", 0.8f, 1.0f, null, v("wamasu_glow"), r -> {});
        rig(event, Bestiary.SWAMP_LEVIATHAN, "swamp_leviathan", 1.2f, 2.5f, null, null, r -> {});
        rig(event, Bestiary.HACKWING, "hackwing", 0.4f, 1.1f, null, null, RigRenderer::bank);
        rig(event, Bestiary.SENCHE_TIGER, "senche_tiger", 0.7f, 1.0f, null, null, r -> {});
        rig(event, Bestiary.ALFIQ, "alfiq", 0.3f, 0.7f, null, v("alfiq_glow"), r -> {});
        // Hammerfell, High Rock, Valenwood, Summerset, the seas
        rig(event, Bestiary.SAND_SERPENT, "sand_serpent", 0.8f, 1.75f, null, null, r -> {});
        rig(event, Bestiary.GIANT_SCORPION, "giant_scorpion", 0.8f, 1.4f, null, null, r -> {});
        rig(event, Bestiary.HARPY, "harpy", 0.5f, 1.0f, null, null, RigRenderer::bank);
        rig(event, Bestiary.INDRIK, "indrik", 0.6f, 1.0f, null, null, r -> {});
        rig(event, Bestiary.SEA_SERPENT, "sea_serpent", 1.5f, 3.0f, null, null, r -> {});
        rig(event, Bestiary.ECHATERE, "echatere", 0.9f, 1.15f, null, null, r -> {});
        rig(event, Bestiary.DURZOG, "durzog", 0.5f, 1.0f, null, null, r -> {});
        // the foreign lands and the endless realms
        rig(event, Bestiary.FROST_GIANT, "frost_giant", 1.2f, 2.2f, null, v("frost_giant_glow"), r -> {});
        rig(event, Bestiary.KAMAL, "kamal", 0.5f, 1.0f, null, v("kamal_glow"), r -> {});
        rig(event, Bestiary.TSAESCI, "tsaesci", 0.5f, 1.0f, null, null, r -> {});
        rig(event, Bestiary.REALM_STALKER, "realm_stalker", 0.5f, 1.0f, null, v("realm_stalker_glow"), r -> r.tint(BestiaryClient::tintOf));
        rig(event, Bestiary.REALM_GRAZER, "realm_grazer", 0.7f, 1.0f, null, null, r -> r.tint(BestiaryClient::tintOf));
        rig(event, Bestiary.REALM_WYRM, "realm_wyrm", 0.6f, 1.2f, null, v("realm_wyrm_glow"), r -> r.tint(BestiaryClient::tintOf));
    }

    private static int tintOf(Mob mob) {
        int c = mob instanceof SkyCreature sc ? sc.getVariant() : 0xFFFFFF;
        return c == 0 ? 0xFFFFFF : c;
    }
}
