#ifndef NIRNCRAFT_ATLAS_WATER_GLSL
#define NIRNCRAFT_ATLAS_WATER_GLSL
// Water as a medium, one definition for everything seen from inside it (the near pass's resolve, the water surface from
// below, entities, particles) and for the light under it (caustics, shafts). Needs NearInfo (Look, KeyDir, KeyRad, SkyUp,
// SunDir, CamPos) and minecraft:fog.glsl declared before it.
//  KeyRad.w: 0 the eye is in air, else 1 + its depth under the water's surface (blocks)
//  WaterTint.rgb: the biomes' water colour around the camera (averaged over ~150 blocks and eased over seconds, so a
//  biome border is a long blend): the water's character, its hue and how murky it is
//  CamPos.xyz: the camera's world position modulo 6144 (world-anchored patterns without float precision loss)
// More water between the eye and a thing: thicker, filled with the water's own lit colour (fog); deeper under the
// surface: darker, the light that gets down there (drop-offs go dark, the shallows stay bright).

const vec3 ATLAS_WATER_ABSORB = vec3(0.30, 0.075, 0.045);  // clear water, per block (as atlas_far.slang's)

bool atlasEyeInWater() {
    return KeyRad.w > 0.5;
}

float atlasEyeDepth() {
    return max(KeyRad.w - 1.0, 0.0);
}

// how murky the water is (1 a clear mountain lake .. 5 a swamp): a clear water colour is a saturated blue, murky
// water a dull green, brown or grey
float atlasWaterMurk() {
    vec3 t = WaterTint.rgb;
    float mx = max(t.r, max(t.g, t.b)), mn = min(t.r, min(t.g, t.b));
    float sat = mx > 1e-3 ? (mx - mn) / mx : 0.0;
    float greenish = clamp((t.g - t.b) * 3.0 + 0.3, 0.0, 1.0);
    return 1.0 + 4.0 * clamp((1.0 - sat) * 1.3 + greenish * 0.6 - 0.35, 0.0, 1.0);
}

// what a block of this water scatters back per channel: clear water's blue-green, turned towards the biome's hue
vec3 atlasWaterScatter() {
    vec3 t = WaterTint.rgb / max(max(WaterTint.r, max(WaterTint.g, WaterTint.b)), 1e-3);
    return vec3(0.05, 0.11, 0.13) * mix(vec3(1.0), t * 1.3, 0.55);
}

vec3 atlasWaterExtinction() {
    return (ATLAS_WATER_ABSORB + 0.04) * atlasWaterMurk();
}

// the water's own light at depth d under the surface, as displayed: the sky and the sun let in at the surface, scattered
// in the water's colour, dimmed by the light lost on the way down (drop-offs go dark; the shallows stay bright)
vec3 atlasWaterLightAt(float d) {
    vec3 L = normalize(KeyDir.xyz);
    vec3 inLight = (SkyUp.rgb + KeyRad.rgb * max(L.y, 0.0) * 0.6) * atlasWaterScatter();  // (the sky physical: the look's ambient 1)
    return atlasDisplay(inLight * exp(-ATLAS_WATER_ABSORB * atlasWaterMurk() * max(d, 0.0) * 0.6), Look.x, Look.y);
}

// displayed colour rgb seen from the eye through dist blocks of water along unit camera-relative direction dir
vec3 atlasUnderwater(vec3 rgb, float dist, vec3 dir) {
    vec3 ext = atlasWaterExtinction();
    vec3 T = exp(-ext * dist);
    // the water's glow along a ray is its light about one scattering length out that way (it settles within a few
    // blocks), the same for every distance: things fade into exactly the open water's colour, no edge (the depth
    // halfway to the thing made a wall 15 blocks off brighter than the water beside it)
    float reach = 1.0 / max(dot(ext, vec3(0.2126, 0.7152, 0.0722)), 1e-3);
    float at = atlasEyeDepth() - dir.y * min(reach, 24.0);
    return rgb * T + atlasWaterLightAt(at) * (vec3(1.0) - T);
}

// water caustics (a tileable pattern, after Dave Hoskins' "Tileable Water Caustic"): bright lines where the waves
// focus the sun, 0..~1, at world xz (blocks), time t (seconds)
float atlasCaustic(vec2 xz, float t) {
    const float TAU = 6.28318530718;
    vec2 p = mod(xz / 7.0 * TAU, TAU) - 250.0;
    vec2 i = p;
    float c = 1.0;
    const float inten = 0.005;
    for (int n = 0; n < 4; n++) {
        float tt = t * 0.45 * (1.0 - (3.5 / float(n + 1)));
        i = p + vec2(cos(tt - i.x) + sin(tt + i.y), sin(tt - i.y) + cos(tt + i.x));
        c += 1.0 / length(vec2(p.x / (sin(i.x + tt) / inten), p.y / (cos(i.y + tt) / inten)));
    }
    c /= 4.0;
    c = 1.17 - pow(c, 1.4);
    return clamp(pow(abs(c), 8.0), 0.0, 1.5);
}

// how the sun reaching a point under chord blocks of water is shaped by the waves over it: focused lines near the
// surface, evening out deeper (mean about 1)
float atlasCausticFactor(vec3 pWorld, float chord) {
    vec3 L = normalize(KeyDir.xyz);
    vec2 s = (pWorld + L * chord).xz;  // where the sun ray left the water
    float c = atlasCaustic(s, SunDir.w);
    return mix(1.0, 0.25 + 4.0 * c, exp(-chord / 16.0));
}

// light shafts: the sun scattered towards the eye inside the water, shaped by the waves overhead (the caustic pattern
// seen edge-on), lost with depth; additive, displayed. dir unit camera-relative, dist blocks along it
vec3 atlasWaterShafts(vec3 dir, float dist) {
    // (off: a repeating pattern along the view ray read as fake, tiling and resetting, not along the sun; real shafts
    // need the sun's visibility through the waves at points in the water, from atlas)
    if (true) return vec3(0.0);
    vec3 L = normalize(KeyDir.xyz);
    if (L.y <= 0.05) return vec3(0.0);
    float len = min(dist, 40.0);
    const int N = 10;
    float dt = len / float(N);
    float jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    vec3 ext = atlasWaterExtinction();
    vec3 sum = vec3(0.0);
    float eye = atlasEyeDepth();
    for (int k = 0; k < N; k++) {
        float t = (float(k) + jitter) * dt;
        vec3 x = dir * t;
        float d = eye - x.y;  // depth under the surface there
        if (d < 0.0) continue;
        float up = d / L.y;   // the sun's path down to it
        vec2 s = CamPos.xz + x.xz + L.xz * up;
        float beam = smoothstep(0.15, 0.9, atlasCaustic(s * 0.35, SunDir.w * 0.5) * 2.0);
        sum += exp(-ext * (up + t)) * beam;
    }
    float g = 0.6, cosT = dot(dir, L);
    float phase = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * cosT, 1.5) / 12.566;
    vec3 inLight = KeyRad.rgb * sum * dt * phase * 0.35 * atlasWaterMurk();
    return atlasDisplay(inLight * atlasWaterScatter() * 8.0, Look.x, Look.y);
}

#endif
