package com.skycraft.creatures.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

import java.util.function.Function;

/** Renderer for player-shaped creatures: skin chosen per entity, vanilla armor, held items, optional glowing eyes. */
public class SkyHumanoidRenderer<T extends Mob> extends HumanoidMobRenderer<T, SkyHumanoidModel<T>> {
    private final Function<T, ResourceLocation> texture;
    private final float scale;
    private final EntityRendererProvider.Context context;

    public SkyHumanoidRenderer(EntityRendererProvider.Context context, ModelLayerLocation layer, Function<T, ResourceLocation> texture,
                               float shadow, float scale, boolean armor) {
        super(context, new SkyHumanoidModel<>(context.bakeLayer(layer)), shadow);
        this.texture = texture;
        this.scale = scale;
        this.context = context;
        if (armor) {
            this.addLayer(new HumanoidArmorLayer<>(this,
                    new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                    context.getModelManager()));
        }
    }

    /**
     * Adds costume pieces from a costume rig ({@code rig.defs.Costumes}); {@code optional} bones are shown when
     * {@code visible} says so (e.g. helmets by rank).
     */
    public SkyHumanoidRenderer<T> withCostume(String def, Function<T, ResourceLocation> costumeTexture, java.util.List<String> optional,
                                              java.util.function.BiPredicate<T, String> visible) {
        if (!com.skycraft.creatures.client.rig.defs.Defs.ALL.containsKey(def)) return this;
        this.addLayer(new com.skycraft.creatures.client.kit.CostumeLayer<>(this, context.bakeLayer(BestiaryClient.layer(def)),
                com.skycraft.creatures.client.rig.defs.Defs.get(def), costumeTexture, optional, visible));
        return this;
    }

    public SkyHumanoidRenderer<T> withCostume(String def) {
        ResourceLocation tex = BestiaryClient.tex(def);
        return withCostume(def, e -> tex, java.util.List.of(), (e, n) -> true);
    }

    /** Adds a full-bright eyes layer (draugr). */
    public SkyHumanoidRenderer<T> withEyes(ResourceLocation eyes) {
        this.addLayer(new GlowEyesLayer<>(this, eyes));
        return this;
    }

    @Override
    protected void scale(T entity, PoseStack poseStack, float partialTick) {
        if (scale != 1.0f) poseStack.scale(scale, scale, scale);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return texture.apply(entity);
    }
}
