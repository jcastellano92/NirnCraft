package com.skycraft.realms.client.creatures;

import com.skycraft.Skycraft;
import com.skycraft.creatures.client.BestiaryClient;
import com.skycraft.creatures.client.kit.RigModel;
import com.skycraft.creatures.client.kit.RigRenderer;
import com.skycraft.creatures.entity.anim.AnimatedCreature;
import com.skycraft.realms.client.rig.RealmDefs;
import com.skycraft.realms.creatures.RealmCreatures;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client side of the realm creatures: their rig models join the bestiary's model definitions (so
 * {@link BestiaryClient}'s own layer registration bakes them), and each type gets its rig renderer with its variant
 * skins, glow layers, ghostly translucency and per-variant gear.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RealmCreaturesClient {
    private RealmCreaturesClient() {}

    /** Runs before the bestiary's layer loop so the realm definitions are part of it. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        RealmDefs.register();
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        RealmDefs.register();
        // the Deadlands
        BestiaryClient.rig(event, RealmCreatures.CLANNFEAR, "clannfear", 0.5f, 1.0f, v("clannfear", "clannfear_runt"),
                v("clannfear_glow"), r -> r.scaleBy(e -> variant(e) == 1 ? 0.78f : 1f));
        BestiaryClient.rig(event, RealmCreatures.DAEDROTH, "daedroth", 0.8f, 1.15f, null, v("daedroth_glow"), r -> {});
        BestiaryClient.rig(event, RealmCreatures.SPIDER_DAEDRA, "spider_daedra", 0.9f, 1.1f, null, v("spider_daedra_glow"), r -> {});
        // the Shivering Isles
        ResourceLocation magusGlow = BestiaryClient.tex("grummite_magus_glow");
        BestiaryClient.rig(event, RealmCreatures.GRUMMITE, "grummite", 0.4f, 1.0f, v("grummite", "grummite_magus"), null,
                r -> r.glow(e -> variant(e) == 1 ? magusGlow : null)
                        .withExtra((m, e, a, b, c, d, f) -> {
                            boolean magus = variant(e) == 1;
                            show(m, "club_right", !magus);
                            show(m, "staff_right", magus);
                            show(m, "magus_robe", magus);
                            show(m, "magus_hood", magus);
                        }));
        BestiaryClient.rig(event, RealmCreatures.BALIWOG, "baliwog", 0.4f, 1.2f, null, null, r -> {});
        BestiaryClient.rig(event, RealmCreatures.GNARL, "gnarl", 0.6f, 1.0f, v("gnarl", "gnarl_elder", "gnarl_ancient"),
                v("gnarl_glow", "gnarl_elder_glow", "gnarl_ancient_glow"), r -> r.scaleBy(e -> variant(e) == 2 ? 1.12f : variant(e) == 1 ? 1.06f : 1f));
        BestiaryClient.rig(event, RealmCreatures.ELYTRA, "elytra", 0.6f, 1.0f, v("elytra", "elytra_hatchling", "elytra_noble"),
                v("elytra_glow", "elytra_hatchling_glow", "elytra_noble_glow"),
                r -> r.scaleBy(e -> variant(e) == 1 ? 0.62f : variant(e) == 2 ? 1.25f : 1f));
        BestiaryClient.rig(event, RealmCreatures.FLESH_ATRONACH, "flesh_atronach", 0.8f, 1.05f, null, v("flesh_atronach_glow"), r -> {});
        // Apocrypha
        BestiaryClient.rig(event, RealmCreatures.SEEKER, "seeker", 0.0f, 0.9f, null, v("seeker_glow"), RigRenderer::translucent);
        BestiaryClient.rig(event, RealmCreatures.LURKER, "lurker", 1.1f, 1.35f, null, v("lurker_glow"), r -> {});
        // the Soul Cairn
        BestiaryClient.rig(event, RealmCreatures.KEEPER, "keeper", 0.6f, 1.15f, null, v("keeper_glow"), r -> {});
        BestiaryClient.rig(event, RealmCreatures.BONEMAN, "boneman", 0.4f, 1.0f, null, v("boneman_glow"),
                r -> r.withExtra((m, e, a, b, c, d, f) -> {
                    boolean archer = variant(e) == 1;
                    show(m, "sword_right", !archer);
                    show(m, "shield_left", !archer);
                    show(m, "bow_left", archer);
                    show(m, "quiver", archer);
                }));
        BestiaryClient.rig(event, RealmCreatures.MISTMAN, "mistman", 0.0f, 1.0f, null, v("mistman_glow"), RigRenderer::translucent);
        BestiaryClient.rig(event, RealmCreatures.WRATHMAN, "wrathman", 0.6f, 1.15f, null, v("wrathman_glow"), RigRenderer::translucent);
        BestiaryClient.rig(event, RealmCreatures.WANDERING_SOUL, "wandering_soul", 0.0f, 1.0f, null, v("wandering_soul_glow"),
                RigRenderer::translucent);
        // Sovngarde
        BestiaryClient.rig(event, RealmCreatures.SOVNGARDE_HERO, "sovngarde_hero", 0.4f, 0.95f,
                v("sovngarde_hero", "sovngarde_hero_shieldmaiden", "sovngarde_hero_archer"),
                v("sovngarde_hero_glow", "sovngarde_hero_shieldmaiden_glow", "sovngarde_hero_archer_glow"),
                r -> r.translucent().withExtra((m, e, a, b, c, d, f) -> {
                    int k = variant(e);
                    show(m, "helm_horned", k == 0);
                    show(m, "axe_right", k == 0);
                    show(m, "beard", k != 1);
                    show(m, "braids", k == 1);
                    show(m, "circlet", k == 1);
                    show(m, "sword_right", k == 1);
                    show(m, "round_shield_left", k == 1);
                    show(m, "hood", k == 2);
                    show(m, "bow_left", k == 2);
                    show(m, "quiver", k == 2);
                }));
        BestiaryClient.rig(event, RealmCreatures.TSUN, "tsun", 1.0f, 1.35f, null, v("tsun_glow"), r -> {});
        // Coldharbour and Miraak
        BestiaryClient.rig(event, RealmCreatures.HARVESTER, "harvester", 0.7f, 1.0f, null, v("harvester_glow"), r -> {});
        BestiaryClient.rig(event, RealmCreatures.MIRAAK, "miraak", 0.5f, 1.0f, null, v("miraak_glow"), r -> {});
    }

    private static String[] v(String... names) {
        return names;
    }

    private static int variant(Mob e) {
        return e instanceof AnimatedCreature ac ? ac.animVariant() : 0;
    }

    private static <T extends Mob> void show(RigModel<T> m, String bone, boolean visible) {
        ModelPart p = m.bone(bone);
        if (p != null) p.visible = visible;
    }
}
