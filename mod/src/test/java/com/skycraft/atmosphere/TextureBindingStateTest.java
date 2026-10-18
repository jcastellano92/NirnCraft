package com.skycraft.atmosphere;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextureBindingStateTest {
    private static final class GL implements TextureBindingState.Bindings {
        int actual=3,cached=0,reads;
        final int[] bound=new int[32],cache=new int[32];
        GL() { bound[0]=42;cache[0]=99;bound[3]=cache[3]=7; }
        public int actualActive() { reads++;return TextureBindingState.ZERO+actual; }
        public int cachedActive() { return TextureBindingState.ZERO+cached; }
        public int actualBinding() { reads++;return bound[actual]; }
        public void active(int unit) {
            int slot=unit-TextureBindingState.ZERO;
            if(cached!=slot) { cached=slot;actual=slot; }
        }
        public void bind(int texture) {
            if(cache[cached]!=texture) { cache[cached]=texture;bound[actual]=texture; }
        }
    }

    @Test void staleExternalActiveAndAtlasCachesAreRepairedWithoutChangingActualBindings() {
        var gl=new GL();
        try(var scope=new TextureBindingState(gl)) {
            assertTrue(scope.resynchronized());
            assertEquals(0,gl.actual);assertEquals(0,gl.cached);
            gl.bind(125);assertEquals(125,gl.bound[0]);
        }
        assertEquals(3,gl.actual);assertEquals(3,gl.cached);
        assertEquals(42,gl.bound[0]);assertEquals(42,gl.cache[0]);
        assertEquals(7,gl.bound[3]);assertEquals(2,gl.reads);
        gl.active(TextureBindingState.ZERO);gl.bind(42);
        assertEquals(42,gl.bound[0],"Mojang's cached fast-path now agrees with the actual atlas binding");
    }

    @Test void uploadExceptionStillRestoresBorrowedUnitAndAtlas() {
        var gl=new GL();
        assertThrows(IllegalStateException.class,()-> {
            try(var scope=new TextureBindingState(gl)) {
                gl.bind(125);throw new IllegalStateException("Simulated failed image upload");
            }
        });
        assertEquals(3,gl.actual);assertEquals(3,gl.cached);
        assertEquals(42,gl.bound[0]);assertEquals(7,gl.bound[3]);
    }

    @Test void irisHighTextureUnitIsRestoredWithoutUploadingIntoIt() {
        var gl=new GL();gl.actual=27;gl.bound[27]=88;
        try(var scope=new TextureBindingState(gl)) { gl.bind(125); }
        assertEquals(27,gl.actual);assertEquals(27,gl.cached);
        assertEquals(88,gl.bound[27]);assertEquals(42,gl.bound[0]);
    }
}
