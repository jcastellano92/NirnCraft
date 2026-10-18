package com.skycraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** Dynamic UI images with explicit sampling; map interpolation survives renderer reloads. */
public final class UiDynamicTexture extends DynamicTexture {
    public UiDynamicTexture(String label,NativeImage image,boolean linear) {
        super(()->label,image);
        if(linear) sampler=RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT,AddressMode.REPEAT,FilterMode.LINEAR,FilterMode.LINEAR,false);
    }
    public UiDynamicTexture(String label,int width,int height,boolean useStb,boolean linear) {
        super(label,width,height,useStb);
        if(linear) sampler=RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT,AddressMode.REPEAT,FilterMode.LINEAR,FilterMode.LINEAR,false);
    }
}
