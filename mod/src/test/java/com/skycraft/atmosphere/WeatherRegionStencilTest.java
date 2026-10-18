package com.skycraft.atmosphere;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WeatherRegionStencilTest {
    private static final int[][] OFFSETS={{0,0},{384,0},{-384,0},{0,384},{0,-384},{384,384},{-384,384},{384,-384},{-384,-384}};
    private static final float[] WEIGHTS={4,2,2,2,2,1,1,1,1};
    private static String region(int x,int z) { return Math.floorDiv(x,257)+":"+Math.floorDiv(z,419); }

    /** Frozen former accumulation path, independent of the cache. */
    private static float[] original(int x,int z,Map<String,float[]> climate) {
        var centre=climate.get(region(x,z));if(centre==null) return null;
        var result=new float[13];float total=0;
        for(int i=0;i<OFFSETS.length;i++) {
            var values=i==0?centre:climate.get(region(x+OFFSETS[i][0],z+OFFSETS[i][1]));
            if(values==null) values=centre;
            for(int k=0;k<result.length;k++) result[k]+=values[k]*WEIGHTS[i];
            total+=WEIGHTS[i];
        }
        for(int k=0;k<result.length;k++) result[k]/=total;
        return result;
    }

    private static void bits(float[] expected,float[] actual) {
        assertNotNull(actual);assertEquals(expected.length,actual.length);
        var expectedBytes=java.nio.ByteBuffer.allocate(expected.length*4);
        var actualBytes=java.nio.ByteBuffer.allocate(actual.length*4);
        for(int i=0;i<expected.length;i++) { expectedBytes.putFloat(expected[i]);actualBytes.putFloat(actual[i]); }
        assertArrayEquals(expectedBytes.array(),actualBytes.array(),"All climate input bytes must remain identical");
    }

    @Test void warmCacheKeepsEveryClimateComponentBitExactAndAvoidsRepeatedGeographyReads() {
        AtomicInteger reads=new AtomicInteger();
        var cache=new WeatherRegionStencil(1,(x,z)->{reads.incrementAndGet();return region(x,z);});
        Object epoch=new Object();Map<String,float[]> climate=new HashMap<>();
        int x=-544,z=65504;
        for(int iteration=0;iteration<40;iteration++) {
            for(int i=0;i<OFFSETS.length;i++) {
                float[] values=new float[13];
                for(int k=0;k<values.length;k++) values[k]=(i-4)*0.137f+k*0.019f+iteration*0.013f;
                // Missing neighbours retain the same centre fallback as before.
                if(i!=3) climate.put(region(x+OFFSETS[i][0],z+OFFSETS[i][1]),values);
            }
            bits(original(x,z,climate),cache.blend(epoch,0,x,z,climate,new float[13]));
        }
        assertEquals(9,reads.get(),"360 region reads become nine without caching dynamic climate");
        assertEquals(9,cache.misses());assertEquals(351,cache.hits());
    }

    @Test void slotReuseAndWorldOrReloadIdentityCannotReuseOldGeography() {
        AtomicInteger generation=new AtomicInteger(),reads=new AtomicInteger();
        var cache=new WeatherRegionStencil(1,(x,z)->{reads.incrementAndGet();return generation+":"+region(x,z);});
        Object world=new Object();
        assertEquals("0:-1:-1",cache.region(world,0,-1,-1,0));
        assertEquals("0:255:156",cache.region(world,0,65535,65535,0));
        generation.incrementAndGet();
        assertEquals("1:-1:-1",cache.region(new Object(),0,-1,-1,0));
        generation.incrementAndGet();
        assertEquals("2:-1:-1",cache.region(new Object(),0,-1,-1,0));
        assertEquals(4,reads.get());
    }

    @Test void unknownCentreDoesNotSampleNeighboursAndCanBecomeKnownWithoutInvalidatingStaticIds() {
        AtomicInteger reads=new AtomicInteger();
        var cache=new WeatherRegionStencil(1,(x,z)->{reads.incrementAndGet();return region(x,z);});
        Object epoch=new Object();Map<String,float[]> climate=new HashMap<>();
        assertNull(cache.blend(epoch,0,32,32,climate,new float[13]));assertEquals(1,reads.get());
        climate.put(region(32,32),new float[]{-0f,0.1f,0.2f,0.3f,1f,0f,1f,0f,1f,0f,1f,0f,1f});
        bits(original(32,32,climate),cache.blend(epoch,0,32,32,climate,new float[13]));
        assertEquals(9,reads.get());
    }
}
