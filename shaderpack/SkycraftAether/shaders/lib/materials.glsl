// Material ids (block.properties: mc_Entity.x = 10000 + id) and their defaults when no labPBR specular map is
// present: smoothness, F0 (reflectance at normal incidence; metals 230+ in labPBR), light through the surface
// (leaves, grass), emission. Stored in the G-buffer as id / 255.
#ifndef SKYCRAFT_MATERIALS
#define SKYCRAFT_MATERIALS

const int MAT_NONE = 0, MAT_LEAVES = 1, MAT_PLANT = 2, MAT_PLANT_TOP = 3, MAT_HANGING = 4, MAT_WATER = 5, MAT_LAVA = 6,
          MAT_FIRE = 7, MAT_LIGHT = 8, MAT_GLASS = 9, MAT_SNOW = 10, MAT_HOTROCK = 11, MAT_PORTAL = 12;

int materialOf(float mcEntity) {
    int e = int(mcEntity + 0.5);
    return e >= 10001 && e <= 10012 ? e - 10000 : MAT_NONE;
}

bool isFoliage(int m) { return m >= MAT_LEAVES && m <= MAT_HANGING; }

// x smoothness, y F0, z subsurface, w emission
vec4 materialDefaults(int m, vec3 albedo) {
    float lum = dot(albedo, vec3(0.2126, 0.7152, 0.0722));
    if (m == MAT_LEAVES) return vec4(0.35, 0.03, 0.6, 0.0);
    if (isFoliage(m)) return vec4(0.25, 0.03, 0.5, 0.0);
    if (m == MAT_WATER) return vec4(0.96, 0.02, 0.0, 0.0);
    if (m == MAT_GLASS) return vec4(0.93, 0.04, 0.0, 0.0);
    if (m == MAT_SNOW) return vec4(0.45, 0.02, 0.2, 0.0);
    // emitters glow by their own brightness, so dark texels (a lantern's frame) stay dark
    if (m == MAT_LAVA) return vec4(0.2, 0.03, 0.0, 1.0);
    if (m == MAT_FIRE) return vec4(0.0, 0.0, 0.0, 1.0);
    if (m == MAT_LIGHT) return vec4(0.3, 0.04, 0.0, smoothstep(0.35, 0.8, lum));
    if (m == MAT_HOTROCK) return vec4(0.2, 0.03, 0.0, smoothstep(0.3, 0.7, albedo.r - albedo.b) * 0.6);
    if (m == MAT_PORTAL) return vec4(0.6, 0.04, 0.0, 0.9);
    return vec4(0.15 + 0.2 * lum, 0.03, 0.0, 0.0);
}

// how bright an emitter is (linear radiance multiplier on its albedo)
float emissionStrength(int m) {
    if (m == MAT_LAVA) return 6.0;
    if (m == MAT_FIRE) return 5.0;
    if (m == MAT_PORTAL) return 4.0;
    if (m == MAT_HOTROCK) return 3.0;
    return 4.0;
}

#endif
