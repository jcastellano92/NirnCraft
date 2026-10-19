package com.nirncraft.creatures.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.creatures.GuardProfiles;
import com.nirncraft.creatures.ModEntities;
import com.nirncraft.creatures.client.kit.RigRenderer;
import com.nirncraft.creatures.entity.BanditChiefEntity;
import com.nirncraft.creatures.entity.BanditEntity;
import com.nirncraft.creatures.entity.DraugrDeathlordEntity;
import com.nirncraft.creatures.entity.DraugrEntity;
import com.nirncraft.creatures.entity.GuardEntity;
import com.nirncraft.creatures.entity.FalmerEntity;
import com.nirncraft.creatures.entity.ForswornEntity;
import com.nirncraft.creatures.entity.NecromancerEntity;
import com.nirncraft.creatures.entity.VampireEntity;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Mod-bus client registration for the creatures module: model layers and entity renderers. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class CreaturesClient {
    public static final ModelLayerLocation HUMANOID = layer("humanoid");

    private static final Identifier[] BANDIT = textures("bandit/bandit_", BanditEntity.SKINS);
    private static final Identifier BANDIT_CHIEF = tex("bandit/bandit_chief");
    private static final Identifier[] DRAUGR = textures("draugr/draugr_", DraugrEntity.SKINS);
    private static final Identifier DEATHLORD = tex("draugr/draugr_deathlord");
    private static final Identifier DRAUGR_EYES = tex("draugr/draugr_eyes");
    private static final Identifier[] GUARD = new Identifier[GuardProfiles.count()];
    private static final Identifier[] FORSWORN = textures("society/forsworn_", ForswornEntity.SKINS);
    private static final Identifier[] NECROMANCER = textures("society/necromancer_", NecromancerEntity.SKINS);
    private static final Identifier[] VAMPIRE = textures("society/vampire_", VampireEntity.SKINS);
    private static final Identifier FALMER = tex("creatures/falmer");
    private static final Identifier VAMPIRE_EYES = tex("creatures/vampire_eyes");
    private static final Identifier COSTUME_DRAUGR = BestiaryClient.tex("costume_draugr");
    /** Draugr costume pieces toggled by rank (0 restless .. 4 overlord). */
    private static final java.util.List<String> DRAUGR_PIECES = java.util.List.of("hood", "helm_iron", "helm_horned", "helm_scourge",
            "helm_overlord", "mantle", "pauldron_right", "pauldron_left");

    private static boolean draugrPiece(DraugrEntity d, String piece) {
        int rank = d.getRank();
        return switch (piece) {
            case "hood" -> rank == 0;
            case "helm_iron" -> rank == 1;
            case "helm_horned" -> rank == 2;
            case "helm_scourge" -> rank == 3;
            case "helm_overlord" -> rank >= 4;
            default -> rank >= 2;
        };
    }

    static {
        for (int i = 0; i < GUARD.length; i++) GUARD[i] = tex("guard/guard_" + GuardProfiles.byIndex(i).id());
    }

    private CreaturesClient() {}

    private static ModelLayerLocation layer(String name) {
        return new ModelLayerLocation(Identifier.fromNamespaceAndPath(NirnCraft.MODID, name), "main");
    }

    private static Identifier tex(String path) {
        return Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/entity/" + path + ".png");
    }

    private static Identifier[] textures(String prefix, int count) {
        Identifier[] out = new Identifier[count];
        for (int i = 0; i < count; i++) out[i] = tex(prefix + i);
        return out;
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(HUMANOID, SkyHumanoidModel::createLayer);
    }

    @SubscribeEvent
    public static void renderStates(net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent event) {
        event.registerEntityModifier(new com.google.common.reflect.TypeToken<SkyHumanoidRenderer<net.minecraft.world.entity.Mob>>() {},
                SkyHumanoidRenderer::extractAppearance);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.BANDIT.get(),
                ctx -> new SkyHumanoidRenderer<BanditEntity>(ctx, HUMANOID, b -> BANDIT[Math.floorMod(b.getSkin(), BANDIT.length)], 0.5f, 1.0f, true));
        event.registerEntityRenderer(ModEntities.BANDIT_CHIEF.get(),
                ctx -> new SkyHumanoidRenderer<BanditChiefEntity>(ctx, HUMANOID, b -> BANDIT_CHIEF, 0.55f, 1.05f, true));
        event.registerEntityRenderer(ModEntities.DRAUGR.get(),
                ctx -> new SkyHumanoidRenderer<DraugrEntity>(ctx, HUMANOID, d -> DRAUGR[Math.floorMod(d.getSkin(), DRAUGR.length)], 0.5f, 1.0f, true)
                        .withEyes(DRAUGR_EYES)
                        .withCostume("costume_draugr", d -> COSTUME_DRAUGR, DRAUGR_PIECES, CreaturesClient::draugrPiece));
        event.registerEntityRenderer(ModEntities.DRAUGR_DEATHLORD.get(),
                ctx -> new SkyHumanoidRenderer<DraugrDeathlordEntity>(ctx, HUMANOID, d -> DEATHLORD, 0.6f, 1.08f, true)
                        .withEyes(DRAUGR_EYES).withCostume("costume_deathlord"));
        event.registerEntityRenderer(ModEntities.GUARD.get(),
                ctx -> new SkyHumanoidRenderer<GuardEntity>(ctx, HUMANOID, g -> GUARD[Math.floorMod(g.getProfileIndex(), GUARD.length)], 0.5f, 1.0f, true));
        BestiaryClient.rig(event, ModEntities.GIANT, "giant", 1.4f, 2.0f, null, null, r -> {});
        BestiaryClient.rig(event, ModEntities.SKEEVER, "skeever", 0.35f, 1.0f, null, null, r -> {});
        BestiaryClient.rig(event, ModEntities.TROLL, "troll", 0.9f, 1.15f, new String[]{"troll", "troll_frost"}, null, r -> {});
        event.registerEntityRenderer(ModEntities.DRAGON.get(), DragonRenderer::new);
        event.registerEntityRenderer(ModEntities.CORPSE.get(), CorpseRenderer::new);

        event.registerEntityRenderer(ModEntities.FORSWORN.get(),
                ctx -> new SkyHumanoidRenderer<ForswornEntity>(ctx, HUMANOID, f -> FORSWORN[Math.floorMod(f.getSkin(), FORSWORN.length)], 0.5f, 1.0f, true)
                        .withCostume("costume_forsworn"));
        event.registerEntityRenderer(ModEntities.NECROMANCER.get(),
                ctx -> new SkyHumanoidRenderer<NecromancerEntity>(ctx, HUMANOID, n -> NECROMANCER[Math.floorMod(n.getSkin(), NECROMANCER.length)], 0.5f, 1.0f, true)
                        .withCostume("costume_necromancer"));
        event.registerEntityRenderer(ModEntities.VAMPIRE.get(),
                ctx -> new SkyHumanoidRenderer<VampireEntity>(ctx, HUMANOID, v -> VAMPIRE[Math.floorMod(v.getSkin(), VAMPIRE.length)], 0.5f, 1.0f, true)
                        .withEyes(VAMPIRE_EYES).withCostume("costume_vampire"));
        event.registerEntityRenderer(ModEntities.FALMER.get(),
                ctx -> new SkyHumanoidRenderer<FalmerEntity>(ctx, HUMANOID, f -> FALMER, 0.5f, 0.95f, true)
                        .withCostume("costume_falmer"));
        BestiaryClient.rig(event, ModEntities.SPRIGGAN, "spriggan", 0.5f, 1.0f, null, new String[]{"spriggan_glow"}, r -> {});
        BestiaryClient.rig(event, ModEntities.HAGRAVEN, "hagraven", 0.5f, 1.0f, null, new String[]{"hagraven_glow"}, r -> {});
        BestiaryClient.rig(event, ModEntities.WISPMOTHER, "wispmother", 0.0f, 1.0f, null, new String[]{"wispmother_glow"},
                RigRenderer::translucent);
        BestiaryClient.rig(event, ModEntities.FROSTBITE_SPIDER, "frostbite_spider", 0.8f, 1.0f,
                new String[]{"frostbite_spider", "frostbite_spider_giant"}, null,
                r -> r.scaleBy(e -> e.animVariant() == 1 ? 2.0f : 1.0f));
        BestiaryClient.rig(event, ModEntities.ICE_WRAITH, "ice_wraith", 0.0f, 1.0f, null, new String[]{"ice_wraith_glow"},
                RigRenderer::translucent);
    }
}
