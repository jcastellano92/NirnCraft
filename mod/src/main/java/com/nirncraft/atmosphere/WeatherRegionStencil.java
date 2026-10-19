package com.nirncraft.atmosphere;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Bounded, worker-owned cache of static region IDs; dynamic climate values are never retained. */
public final class WeatherRegionStencil {
    public interface Lookup { String region(int x,int z); }
    private static final int BLEND=384;
    private static final int[][] OFFSETS={{0,0},{BLEND,0},{-BLEND,0},{0,BLEND},{0,-BLEND},
            {BLEND,BLEND},{-BLEND,BLEND},{BLEND,-BLEND},{-BLEND,-BLEND}};
    private static final float[] WEIGHTS={4f,2f,2f,2f,2f,1f,1f,1f,1f};
    private final Lookup lookup;
    private final int[] x,z;
    private final String[][] regions;
    private Object identity;
    private long hits,misses;

    public WeatherRegionStencil(int slots,Lookup lookup) {
        if(slots<=0) throw new IllegalArgumentException("Positive stencil slot count required");
        this.lookup=Objects.requireNonNull(lookup);
        x=new int[slots];z=new int[slots];regions=new String[slots][];
    }

    public String region(Object epoch,int slot,int worldX,int worldZ,int sample) {
        Objects.requireNonNull(epoch);
        if(identity!=epoch) { Arrays.fill(regions,null);identity=epoch; }
        String[] ids=regions[slot];
        if(ids==null || x[slot]!=worldX || z[slot]!=worldZ) {
            regions[slot]=ids=new String[OFFSETS.length];x[slot]=worldX;z[slot]=worldZ;
        }
        if(ids[sample]==null) {
            ids[sample]=Objects.requireNonNull(lookup.region(worldX+OFFSETS[sample][0],worldZ+OFFSETS[sample][1]));
            misses++;
        } else hits++;
        return ids[sample];
    }

    /** Exact original accumulation order/weights; mutable weather snapshots are sampled on every call. */
    public float[] blend(Object epoch,int slot,int worldX,int worldZ,Map<String,float[]> climate,float[] out) {
        String centreId=region(epoch,slot,worldX,worldZ,0);
        float[] centre=climate.get(centreId);
        if(centre==null) return null;
        Arrays.fill(out,0f);
        float total=0f;
        for(int sample=0;sample<OFFSETS.length;sample++) {
            float[] values=sample==0?centre:climate.get(region(epoch,slot,worldX,worldZ,sample));
            if(values==null) values=centre;
            float weight=WEIGHTS[sample];
            for(int k=0;k<out.length;k++) out[k]+=values[k]*weight;
            total+=weight;
        }
        for(int k=0;k<out.length;k++) out[k]/=total;
        return out;
    }

    public long hits() { return hits; }
    public long misses() { return misses; }
}
