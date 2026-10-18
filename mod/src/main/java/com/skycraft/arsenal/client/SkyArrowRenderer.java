package com.skycraft.arsenal.client;

import com.skycraft.arsenal.entity.SkyArrow;
import com.skycraft.arsenal.item.ArrowKind;
import net.minecraft.client.renderer.entity.ArrowRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.Identifier;

/** Skyrim arrows use the vanilla arrow model; elemental arrows and bolts glow like spectral arrows. */
public class SkyArrowRenderer extends ArrowRenderer<SkyArrow,SkyArrowRenderer.State> {
    public static class State extends net.minecraft.client.renderer.entity.state.ArrowRenderState { boolean elemental; }
    @Override public State createRenderState() { return new State(); }
    @Override public void extractRenderState(SkyArrow arrow,State state,float partialTick) { super.extractRenderState(arrow,state,partialTick);state.elemental=arrow.element()!=ArrowKind.Element.NONE; }
    private static final Identifier NORMAL = Identifier.parse("textures/entity/projectiles/arrow.png");
    private static final Identifier GLOWING = Identifier.parse("textures/entity/projectiles/spectral_arrow.png");

    public SkyArrowRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public Identifier getTextureLocation(State state) {
        return state.elemental?GLOWING:NORMAL;
    }
}
