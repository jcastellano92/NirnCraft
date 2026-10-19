package com.nirncraft.atmosphere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Portable loaded-block collision snapshot shared by local light receivers, independent of camera visibility. */
public final class LocalLightOcclusion {
    public static final int SIDE=64, UNKNOWN=255, MAX_BOXES=8;
    public record Box(double x0,double y0,double z0,double x1,double y1,double z1) {}
    private final byte[] cells=new byte[SIDE*SIDE*SIDE], macro=new byte[16*16*16];
    private final List<List<Box>> shapes=new ArrayList<>();
    private final Map<List<Box>,Integer> shapeIds=new HashMap<>();
    private int overflow;

    public LocalLightOcclusion() {
        Arrays.fill(cells,(byte)UNKNOWN);
        shapes.add(List.of());
        shapes.add(List.of(new Box(0,0,0,1,1,1)));
        shapeIds.put(shapes.get(0),0);shapeIds.put(shapes.get(1),1);
    }

    /** Returns an explicit conservative fallback when an unsupported collision shape exceeds this bounded packet. */
    public int shape(List<Box> boxes) {
        var key=List.copyOf(boxes);
        Integer known=shapeIds.get(key);
        if(known!=null) return known;
        if(boxes.size()>MAX_BOXES || shapes.size()>=UNKNOWN) { overflow++; return 1; }
        int id=shapes.size();shapes.add(key);shapeIds.put(key,id);return id;
    }

    public int shapeCount() { return shapes.size(); }
    public int overflow() { return overflow; }
    public List<Box> boxes(int id) { return shapes.get(id); }
    public byte[] cells() { return cells; }
    public byte[] macro() { return macro; }
    public static int index(int x,int y,int z) { return ((y&63)*64+(z&63))*64+(x&63); }
    public int get(int x,int y,int z) { return cells[index(x,y,z)]&255; }
    public boolean set(int x,int y,int z,int shape) {
        int index=index(x,y,z);
        if((cells[index]&255)==shape) return false;
        cells[index]=(byte)shape;return true;
    }

    /** Empty four-block cells skip 64 fine occupancy lookups during a receiver shadow ray. */
    public void updateMacro(int sx,int sy,int sz) {
        for(int my=0;my<4;my++) for(int mz=0;mz<4;mz++) for(int mx=0;mx<4;mx++) {
            int wx=sx*16+mx*4,wy=sy*16+my*4,wz=sz*16+mz*4;
            boolean empty=true;
            for(int y=0;y<4 && empty;y++) for(int z=0;z<4 && empty;z++) for(int x=0;x<4;x++)
                if(get(wx+x,wy+y,wz+z)!=0) { empty=false;break; }
            macro[(((wy>>2)&15)*16+((wz>>2)&15))*16+((wx>>2)&15)]=(byte)(empty?0:1);
        }
    }

    /** Exact block/box reference for the GPU DDA. Unknown samples remain explicitly unknown. */
    public boolean visible(double ax,double ay,double az,double bx,double by,double bz) {
        double dx=bx-ax,dy=by-ay,dz=bz-az;
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(length<0.12) return true;
        dx/=length;dy/=length;dz/=length;
        double distance=0.03;
        for(int step=0;step<128 && distance<length-0.06;step++) {
            int x=(int)Math.floor(ax+dx*distance),y=(int)Math.floor(ay+dy*distance),z=(int)Math.floor(az+dz*distance);
            int id=get(x,y,z);
            if(id==UNKNOWN) return true;
            if(id!=0) for(var box:boxes(id)) {
                double entry=0.03,exit=length-0.06;
                double[] origin={ax,ay,az},direction={dx,dy,dz};
                double[] low={x+box.x0,y+box.y0,z+box.z0},high={x+box.x1,y+box.y1,z+box.z1};
                boolean hit=true;
                for(int axis=0;axis<3;axis++) {
                    if(Math.abs(direction[axis])<1e-12) {
                        if(origin[axis]<=low[axis] || origin[axis]>=high[axis]) { hit=false;break; }
                    } else {
                        double a=(low[axis]-origin[axis])/direction[axis],b=(high[axis]-origin[axis])/direction[axis];
                        entry=Math.max(entry,Math.min(a,b));exit=Math.min(exit,Math.max(a,b));
                        if(exit<=entry) { hit=false;break; }
                    }
                }
                if(hit) return false;
            }
            double tx=dx>0?(x+1-ax)/dx:dx<0?(x-ax)/dx:Double.POSITIVE_INFINITY;
            double ty=dy>0?(y+1-ay)/dy:dy<0?(y-ay)/dy:Double.POSITIVE_INFINITY;
            double tz=dz>0?(z+1-az)/dz:dz<0?(z-az)/dz:Double.POSITIVE_INFINITY;
            distance=Math.min(tx,Math.min(ty,tz))+1e-5;
        }
        return true;
    }
}
