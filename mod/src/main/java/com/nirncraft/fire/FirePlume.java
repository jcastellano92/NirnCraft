package com.nirncraft.fire;

/** Shared world-space dimensions for the volume and fallback smoke sprites. */
public final class FirePlume {
    private FirePlume() {}
    public static float base(int flameHeight) { return flameHeight*0.8f; }
    public static float height(int flameHeight,int smoke) { return 14f+18f*smoke+flameHeight*0.8f; }
    public static float radius(int flameHeight,float intensity) {
        return (1.5f+flameHeight*0.30f)*(0.8f+0.25f*Math.min(2f,intensity))*1.3f;
    }
}
