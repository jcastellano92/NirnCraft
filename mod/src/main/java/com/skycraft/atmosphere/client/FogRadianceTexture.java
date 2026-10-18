package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;

/** Small cache of Minecraft's propagated block light, for nearby illuminated mist.
 * Reads loaded chunks only; 256 probes per tick. A bounded walk up Minecraft's
 * propagated light gradient identifies the dominant emitter's colour. */
final class FogRadianceTexture {
    private static final ResourceLocation LOCATION = new ResourceLocation(Skycraft.MODID, "textures/environment/fog_radiance.png");
    private static int uploadedId = -1;
    private static final RoofShelterCache SHELTER = new RoofShelterCache();
    private static NativeImage image;
    private static int slice;
    private static final float[] target = new float[4096], display = new float[4096];
    private static final float[][] tintTarget = new float[3][4096], tintDisplay = new float[3][4096];
    private static final int[] cellX = new int[4096], cellY = new int[4096], cellZ = new int[4096];
    private static ClientLevel trackedLevel;
    private static final BlockPos.MutableBlockPos PROBE = new BlockPos.MutableBlockPos();
    private static final BlockPos.MutableBlockPos WALK = new BlockPos.MutableBlockPos(), NEIGHBOUR = new BlockPos.MutableBlockPos();
    private static long updateNanos;
    private static int gradientReads;
    private static final net.minecraft.core.Direction[] DIRECTIONS=net.minecraft.core.Direction.values();

    private FogRadianceTexture() {}

    static void tick(ClientLevel level, Vec3 camera) {
        long began=System.nanoTime(); gradientReads=0;
        if(trackedLevel!=level) {
            trackedLevel=level;
            java.util.Arrays.fill(target,0f); java.util.Arrays.fill(display,0f);
            java.util.Arrays.fill(cellX,Integer.MIN_VALUE);
            for(int channel=0;channel<3;channel++) {
                java.util.Arrays.fill(tintTarget[channel],0f); java.util.Arrays.fill(tintDisplay[channel],0f);
            }
        }
        var manager = Minecraft.getInstance().getTextureManager();
        if (image == null) image = new NativeImage(128, 128, true);
        // Update the original resource object; preserve cached Oculus sampler bindings.
        var texture = manager.getTexture(LOCATION, null);
        if (texture == null) { texture = new SimpleTexture(LOCATION); manager.register(LOCATION, texture); }
        if (texture.getId() != uploadedId) {
            uploadedId = texture.getId();
            TextureUtil.prepareImage(uploadedId, 0, 128, 128);
        }
        SHELTER.tick(level, camera);
        int cx = Mth.floor(camera.x / 4), cy = Mth.floor(camera.y / 4), cz = Mth.floor(camera.z / 4);
        int y = nearest(cy, slice);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            PROBE.set(nearest(cx, x) * 4 + 2, y * 4 + 2, nearest(cz, z) * 4 + 2);
            int index=(slice * 16 + z) * 16 + x;
            cellX[index]=PROBE.getX(); cellY[index]=PROBE.getY(); cellZ[index]=PROBE.getZ();
            target[index] = level.isInWorldBounds(PROBE) && level.hasChunkAt(PROBE)
                    ? level.getBrightness(LightLayer.BLOCK, PROBE) / 15f : 0f;
            int colour=target[index]>0 ? dominantColour(level,PROBE) : 0;
            for(int channel=0;channel<3;channel++)
                tintTarget[channel][index]=((colour>>(16-channel*8))&255)/255f*target[index];
        }
        slice = (slice + 1) & 15;
        for (int layer = 0; layer < 16; layer++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int index = (layer * 16 + z) * 16 + x;
            if(cellX[index]!=nearest(cx,x)*4+2 || cellY[index]!=nearest(cy,layer)*4+2
                    || cellZ[index]!=nearest(cz,z)*4+2) {
                target[index]=display[index]=0f;
                for(int channel=0;channel<3;channel++) tintTarget[channel][index]=tintDisplay[channel][index]=0f;
            }
            display[index] = Mth.lerp(0.25f, display[index], target[index]);
            int light = Math.round(display[index] * 255);
            int px = (layer & 3) * 16 + x, pz = (layer >> 2) * 16 + z;
            image.setPixelRGBA(px, pz, SHELTER.packed(px, pz) | light);
            int rgb=0xFF000000;
            for(int channel=0;channel<3;channel++) {
                tintDisplay[channel][index]=Mth.lerp(0.25f,tintDisplay[channel][index],tintTarget[channel][index]);
                rgb|=Mth.clamp(Math.round(tintDisplay[channel][index]*255),0,255)<<(channel*8);
            }
            image.setPixelRGBA(px+64,pz,rgb);
        }
        InteriorAtmosphere.write(image, level, camera);
        GlStateManager._bindTexture(uploadedId);
        image.upload(0, 0, 0, 0, 0, 128, 128, true, false, false, false);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        updateNanos=System.nanoTime()-began;
    }

    private static int dominantColour(ClientLevel level,BlockPos origin) {
        WALK.set(origin);
        int light=level.getBrightness(LightLayer.BLOCK,WALK);
        // Brightness decreases away from an emitter. Strict ascent terminates
        // after at most15 hops and never searches or requests an unloaded chunk.
        for(int hop=0;hop<15;hop++) {
            if(level.getBlockState(WALK).getLightEmission(level,WALK)>0) return emitterColour(level,WALK);
            int best=light,dx=0,dy=0,dz=0;
            for(var direction:DIRECTIONS) {
                NEIGHBOUR.setWithOffset(WALK,direction);
                if(!level.isInWorldBounds(NEIGHBOUR) || !level.hasChunkAt(NEIGHBOUR)) continue;
                int value=level.getBrightness(LightLayer.BLOCK,NEIGHBOUR); gradientReads++;
                if(value>best) { best=value;dx=direction.getStepX();dy=direction.getStepY();dz=direction.getStepZ(); }
            }
            if(best<=light) break;
            WALK.move(dx,dy,dz);light=best;
        }
        return 0xFFB070;
    }

    private static int emitterColour(ClientLevel level,BlockPos position) {
        String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock()).getPath();
        if(id.contains("soul") || id.contains("crimson_nirnroot")) return id.contains("crimson")?0xFF7290:0x71DBFF;
        if(id.contains("sea_lantern") || id.contains("crystal") || id.contains("glow_lichen")) return 0x91DFFF;
        if(id.contains("end_rod") || id.contains("pearlescent")) return 0xD9B7FF;
        if(id.contains("verdant_froglight")) return 0xBFFFA4;
        if(id.contains("redstone")) return 0xFF3D25;
        if(id.contains("lava") || id.contains("magma")) return 0xFF7028;
        return 0xFFB070;
    }

    static String describe() { return "fog_cache_ms="+updateNanos/1_000_000.0+" tint_gradient_reads="+gradientReads; }

    private static int nearest(int centre, int texel) {
        int start = centre - 8;
        return start + Math.floorMod(texel - start, 16);
    }
}
