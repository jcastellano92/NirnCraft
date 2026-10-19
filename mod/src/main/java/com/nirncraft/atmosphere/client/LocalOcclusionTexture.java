package com.nirncraft.atmosphere.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.nirncraft.render.client.DataTexture;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.nirncraft.NirnCraft;
import com.nirncraft.atmosphere.LocalLightOcclusion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;

/** Bounded collision transport for portable point-light shadows. No chunks are requested or loaded. */
final class LocalOcclusionTexture {
    private static final Identifier LOCATION=Identifier.fromNamespaceAndPath(NirnCraft.MODID,"textures/environment/local_occlusion.png");
    private static final int WIDTH=256,HEIGHT=294,HEADER_ROW=260,SHAPE_ROW=262;
    private static LocalLightOcclusion grid=new LocalLightOcclusion();
    private static final IdentityHashMap<BlockState,Integer> SHAPES=new IdentityHashMap<>();
    private static final int[] sectionX=new int[64],sectionY=new int[64],sectionZ=new int[64];
    private static final boolean[] valid=new boolean[64];
    private static final BlockPos.MutableBlockPos POSITION=new BlockPos.MutableBlockPos();
    private static ClientLevel tracked;
    private static NativeImage image;
    private static GpuTexture uploaded;
    private static int sweep,minX,minY,minZ,publishedX=Integer.MIN_VALUE,publishedY,publishedZ;
    private static boolean dirty;
    private static long updateNanos;
    private static int blocksRead,unknownSections;
    private static int recoveryX=Integer.MIN_VALUE,recoveryY,recoveryZ,paletteRecoveries;
    private static boolean enabled=Boolean.getBoolean("nirncraft.localLightOcclusion");

    private LocalOcclusionTexture() {}

    static boolean enabled() { return enabled; }
    static void enabled(boolean value) { enabled=value;if(!value) clear(); }

    static void clear() {
        tracked=null;grid=new LocalLightOcclusion();SHAPES.clear();Arrays.fill(valid,false);
        publishedX=Integer.MIN_VALUE;sweep=0;dirty=true;
        if(image!=null && uploaded!=null) {
            try(var uploadState=TextureUploadState.preserve()) {
            image.setPixelABGR(3,HEADER_ROW,0);
            var texture=Minecraft.getInstance().getTextureManager().getTexture(LOCATION);
            if(!DataTexture.current(LOCATION, uploaded)) { uploaded=null;return; }

            DataTexture.writeRect(LOCATION, image, 3, HEADER_ROW, 1, 1);
            }
        }
    }

    static void tick(ClientLevel level,Vec3 camera) {
        long started=System.nanoTime();blocksRead=0;
        if(tracked!=level) { recoveryX=Integer.MIN_VALUE;clear();tracked=level; }
        var texture=Minecraft.getInstance().getTextureManager().getTexture(LOCATION);
        if(!DataTexture.current(LOCATION, uploaded)) dirty=true;
        minX=(Mth.floor(camera.x)>>4)-2;minY=(Mth.floor(camera.y)>>4)-2;minZ=(Mth.floor(camera.z)>>4)-2;
        for(int y=0;y<4;y++) for(int z=0;z<4;z++) for(int x=0;x<4;x++) {
            int sx=minX+x,sy=minY+y,sz=minZ+z,index=sectionIndex(sx,sy,sz);
            if(sectionX[index]!=sx || sectionY[index]!=sy || sectionZ[index]!=sz) valid[index]=false;
        }
        // Four sections per tick. Existing complete GL snapshot stays active until
        // all entering sections are ready, instead of exposing a partially cleared window.
        for(int job=0;job<4;job++) {
            int chosen=-1,best=Integer.MAX_VALUE;
            for(int y=0;y<4;y++) for(int z=0;z<4;z++) for(int x=0;x<4;x++) {
                int index=sectionIndex(minX+x,minY+y,minZ+z);
                int distance=(2*x-3)*(2*x-3)+(2*y-3)*(2*y-3)+(2*z-3)*(2*z-3);
                if(!valid[index] && distance<best) { best=distance;chosen=(y*4+z)*4+x; }
            }
            if(chosen<0) chosen=sweep++&63;
            int sx=minX+(chosen&3),sy=minY+(chosen>>4),sz=minZ+((chosen>>2)&3);
            refresh(level,sx,sy,sz);
        }
        unknownSections=0;
        for(boolean known:valid) if(!known) unknownSections++;
        if(unknownSections==0 && grid.overflow()>0 && (recoveryX!=minX || recoveryY!=minY || recoveryZ!=minZ)) {
            // Discard palette entries accumulated in previously visited areas and
            // retry this window once. A genuinely unsupported local scene stays
            // visibly diagnostic/failed instead of endlessly resetting or passing.
            recoveryX=minX;recoveryY=minY;recoveryZ=minZ;paletteRecoveries++;
            clear();tracked=level;updateNanos=System.nanoTime()-started;return;
        }
        if(unknownSections==0 && (dirty || publishedX!=minX || publishedY!=minY || publishedZ!=minZ)) upload();
        updateNanos=System.nanoTime()-started;
    }

    private static int sectionIndex(int x,int y,int z) { return ((y&3)*4+(z&3))*4+(x&3); }

    private static void refresh(ClientLevel level,int sx,int sy,int sz) {
        int index=sectionIndex(sx,sy,sz);
        int by=sy*16;
        var chunk=level.getChunkSource().getChunk(sx,sz,ChunkStatus.FULL,false);
        if((chunk==null || chunk instanceof net.minecraft.world.level.chunk.EmptyLevelChunk)
                && by>=level.getMinY() && by<(level.getMaxY()+1)) return;
        var section=by<level.getMinY() || by>=(level.getMaxY()+1)?null:
                chunk.getSections()[level.getSectionIndex(by)];
        boolean changed=false;
        for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            int wx=sx*16+x,wy=by+y,wz=sz*16+z,id=0;
            if(section!=null) {
                POSITION.set(wx,wy,wz);
                id=shape(level,section.getBlockState(x,y,z));blocksRead++;
                // Fences and walls extend into the block above. Clip those actual
                // boxes into this cell as well, so rays above their base still hit.
                if(id!=1 && wy>level.getMinY()) {
                    BlockState below=y>0?section.getBlockState(x,y-1,z):level.getBlockState(POSITION.below());
                    POSITION.set(wx,wy-1,wz);
                    int lower=shape(level,below);blocksRead++;
                    if(lower>1 && grid.boxes(lower).stream().anyMatch(box->box.y1()>1)) {
                        var boxes=new ArrayList<>(grid.boxes(id));
                        for(var box:grid.boxes(lower)) if(box.y1()>1)
                            boxes.add(new LocalLightOcclusion.Box(box.x0(),Math.max(0,box.y0()-1),box.z0(),
                                    box.x1(),Math.min(1,box.y1()-1),box.z1()));
                        id=grid.shape(boxes);
                    }
                }
            }
            changed|=grid.set(wx,wy,wz,id);
        }
        sectionX[index]=sx;sectionY[index]=sy;sectionZ[index]=sz;valid[index]=true;
        if(changed) { grid.updateMacro(sx,sy,sz);dirty=true; }
    }

    private static int shape(ClientLevel level,BlockState state) {
        Integer known=SHAPES.get(state);
        if(known!=null) return known;
        var boxes=state.getCollisionShape(level,POSITION).toAabbs();
        var packet=new ArrayList<LocalLightOcclusion.Box>(boxes.size());
        for(var box:boxes) packet.add(new LocalLightOcclusion.Box(box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ));
        int id=grid.shape(packet);SHAPES.put(state,id);return id;
    }

    private static void upload() {
        try(var uploadState=TextureUploadState.preserve()) {
        if(image==null) image=new NativeImage(WIDTH,HEIGHT,true);
        var manager=Minecraft.getInstance().getTextureManager();
        uploaded = DataTexture.target(LOCATION, image);
        byte[] data=grid.cells(),macro=grid.macro();
        for(int i=0;i<data.length;i+=4) image.setPixelABGR((i>>2)&255,i>>10,packed(data,i));
        for(int i=0;i<macro.length;i+=4) image.setPixelABGR((i>>2)&255,256+(i>>10),packed(macro,i));
        image.setPixelABGR(0,HEADER_ROW,minX*16);image.setPixelABGR(1,HEADER_ROW,minY*16);
        image.setPixelABGR(2,HEADER_ROW,minZ*16);image.setPixelABGR(3,HEADER_ROW,1);
        for(int id=0;id<grid.shapeCount();id++) {
            var boxes=grid.boxes(id);image.setPixelABGR(id,261,boxes.size());
            for(int b=0;b<boxes.size();b++) {
                var box=boxes.get(b);int start=id*32+b*4;
                point(start,box.x0(),box.y0(),box.z0());point(start+2,box.x1(),box.y1(),box.z1());
            }
        }

        DataTexture.write(LOCATION, image);
        publishedX=minX;publishedY=minY;publishedZ=minZ;dirty=false;
        }
    }

    private static int packed(byte[] data,int index) {
        return (data[index]&255) | (data[index+1]&255)<<8 | (data[index+2]&255)<<16 | (data[index+3]&255)<<24;
    }

    private static void point(int address,double x,double y,double z) {
        // Signed quarter-sixteenth offsets permit wall/fence collision boxes that
        // extend outside their block. Precision is 1/16384 block, not a full voxel.
        int ix=Mth.clamp((int)Math.round(x*16384)+16384,0,65535);
        int iy=Mth.clamp((int)Math.round(y*16384)+16384,0,65535);
        int iz=Mth.clamp((int)Math.round(z*16384)+16384,0,65535);
        image.setPixelABGR(address&255,SHAPE_ROW+(address>>8),(ix&255) | (ix>>>8)<<8 | (iy&255)<<16 | (iy>>>8)<<24);
        address++;
        image.setPixelABGR(address&255,SHAPE_ROW+(address>>8),iz);
    }

    static boolean ready() { return tracked!=null && publishedX==minX && publishedY==minY && publishedZ==minZ && grid.overflow()==0; }
    static String describe() { return "occlusion_enabled="+enabled+" occlusion_ms="+updateNanos/1e6+" collision_reads="+blocksRead
            +" unknown_sections="+unknownSections+" collision_shapes="+grid.shapeCount()+" collision_overflow="+grid.overflow()
            +" collision_palette_recoveries="+paletteRecoveries; }
}
