#ifndef NIRNCRAFT_ATLAS_WATER_GLSL
#define NIRNCRAFT_ATLAS_WATER_GLSL
// Water as a medium, one definition for everything seen from inside it (the near pass's resolve, the water surface from
// below, entities, particles): absorbed per channel along the path (red first, then green), filled with the water's own
// light, the sky and the sun scattered in it (atlas_far.slang's waterBody and near_forward's surface use the same
// constants). Needs NearInfo (Look, KeyDir, KeyRad, SkyUp) and atlas_look.glsl declared before it. KeyRad.w: the eye
// is in water.

const vec3 ATLAS_WATER_ABSORB = vec3(0.30, 0.075, 0.045);
const vec3 ATLAS_WATER_SCATTER = vec3(0.05, 0.11, 0.13);

bool atlasEyeInWater() {
    return KeyRad.w > 0.5;
}

// the water's own light as displayed: what fills a view through water as it grows long
vec3 atlasWaterLight() {
    vec3 inLight = (SkyUp.rgb * 3.0 + KeyRad.rgb * max(KeyDir.y, 0.0) * 0.5) * ATLAS_WATER_SCATTER;
    return atlasDisplay(inLight, Look.x, Look.y);
}

// displayed colour rgb seen through dist blocks of water
vec3 atlasUnderwater(vec3 rgb, float dist) {
    vec3 T = exp(-(ATLAS_WATER_ABSORB + 0.04) * dist);
    return rgb * T + atlasWaterLight() * (vec3(1.0) - T);
}

#endif
