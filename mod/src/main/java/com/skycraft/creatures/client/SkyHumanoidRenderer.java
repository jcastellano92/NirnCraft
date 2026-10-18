package com.skycraft.creatures.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

import java.util.function.Function;

/** Renderer for player-shaped creatures: skin chosen per entity, vanilla armor, held items, optional glowing eyes. */
public class SkyHumanoidRenderer<T extends Mob> extends HumanoidMobRenderer<T, net.minecraft.client.renderer.entity.state.AvatarRenderState, SkyHumanoidModel<T>> {
    public record Appearance(Identifier texture,boolean twoHanded,boolean shieldRaised) {}
    public static final net.minecraft.util.context.ContextKey<Appearance> APPEARANCE=new net.minecraft.util.context.ContextKey<>(Identifier.fromNamespaceAndPath("skycraft","humanoid_appearance"));
    private final java.util.List<com.skycraft.creatures.client.kit.CostumeLayer<T,SkyHumanoidModel<T>>> costumes=new java.util.ArrayList<>();
    private final Function<T, Identifier> texture;
    private final float scale;
    private final EntityRendererProvider.Context context;

    public SkyHumanoidRenderer(EntityRendererProvider.Context context, ModelLayerLocation layer, Function<T, Identifier> texture,
                               float shadow, float scale, boolean armor) {
        super(context, new SkyHumanoidModel<>(context.bakeLayer(layer)), shadow);
        this.texture = texture;
        this.scale = scale;
        this.context = context;
        if (armor) {
            this.addLayer(new HumanoidArmorLayer<>(this,
                net.minecraft.client.renderer.entity.ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(),
                    p -> new net.minecraft.client.model.player.PlayerModel(p,false)), context.getEquipmentRenderer()));
        }
    }

    /**
     * Adds costume pieces from a costume rig ({@code rig.defs.Costumes}); {@code optional} bones are shown when
     * {@code visible} says so (e.g. helmets by rank).
     */
    public SkyHumanoidRenderer<T> withCostume(String def, Function<T, Identifier> costumeTexture, java.util.List<String> optional,
                                              java.util.function.BiPredicate<T, String> visible) {
        if (!com.skycraft.creatures.client.rig.defs.Defs.ALL.containsKey(def)) return this;
        var costume=new com.skycraft.creatures.client.kit.CostumeLayer<T,SkyHumanoidModel<T>>(this,context.bakeLayer(BestiaryClient.layer(def)),
                com.skycraft.creatures.client.rig.defs.Defs.get(def),costumeTexture,optional,visible);
        costumes.add(costume);
        this.addLayer(costume);
        return this;
    }

    public SkyHumanoidRenderer<T> withCostume(String def) {
        Identifier tex = BestiaryClient.tex(def);
        return withCostume(def, e -> tex, java.util.List.of(), (e, n) -> true);
    }

    /** Adds a full-bright eyes layer (draugr). */
    public SkyHumanoidRenderer<T> withEyes(Identifier eyes) {
        this.addLayer(new GlowEyesLayer<>(this, eyes));
        return this;
    }

    @Override
    protected void scale(net.minecraft.client.renderer.entity.state.AvatarRenderState entity, PoseStack poseStack) {
        if (scale != 1.0f) poseStack.scale(scale, scale, scale);
    }

    @Override
    public Identifier getTextureLocation(net.minecraft.client.renderer.entity.state.AvatarRenderState state) { return state.getRenderData(APPEARANCE).texture(); }
    @Override public net.minecraft.client.renderer.entity.state.AvatarRenderState createRenderState() { return new net.minecraft.client.renderer.entity.state.AvatarRenderState(); }
    @Override public void extractRenderState(T entity,net.minecraft.client.renderer.entity.state.AvatarRenderState state,float partialTick) {
        super.extractRenderState(entity,state,partialTick);
        state.setRenderData(APPEARANCE,new Appearance(texture.apply(entity),com.skycraft.combat.WeaponClass.of(entity.getMainHandItem()).twoHanded(),entity.isAggressive()&&entity.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem));
        for (var costume:costumes) costume.extract(entity,state);
    }
    @Override protected net.minecraft.client.model.HumanoidModel.ArmPose getArmPose(T entity,net.minecraft.world.entity.HumanoidArm arm) {
        var stack=arm==entity.getMainArm()?entity.getMainHandItem():entity.getOffhandItem();
        if (stack.getItem() instanceof net.minecraft.world.item.BowItem && entity.isAggressive()) return net.minecraft.client.model.HumanoidModel.ArmPose.BOW_AND_ARROW;
        return stack.isEmpty()?net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY:net.minecraft.client.model.HumanoidModel.ArmPose.ITEM;
    }
}
