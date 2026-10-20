#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:projection.glsl>
#include <nirncraft:atlas_look.glsl>
// Infinite ATLAS near pass, the resolve (com.nirncraft.atlas.AtlasNearPass): one full-screen triangle in the frame's
// main pass right after opaque terrain would have been drawn. Near pixels are lit from the G-buffer, far pixels take
// atlas's frame, and both write the frame's depth (reverse-Z, vanilla's projection), so entities, block entities,
// particles, water, clouds and weather drawn after it depth-test against our terrain, near and far.
//  Sampler0: G-buffer albedo (sRGB, alpha 1 where a surface is)   Sampler1: G-buffer light (r block, g sky, ba normal)
//  Sampler2: vanilla's lightmap (16 x 16: block x sky)              Sampler3: G-buffer depth (reverse-Z, 0 = nothing)
//  Sampler4: atlas colour: far pixels linear HDR premultiplied; near pixels (near light) aerial inscatter, transmittance
//  Sampler5: atlas depth (view distance, 1e30 = none)               Sampler6: atlas near light (irradiance, sun vis)
// Look: x exposure (linear), y night vision strength, z mode (0: vanilla's lightmap, stage A; 1: atlas's near light,
// one light near and far), w 1 when atlas's frame lags the camera (the CPU backend)
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;
layout(std140) uniform NearInfo {
    vec4 Look;     // x exposure, y night vision, z mode, w atlas lags the camera (CPU backend)
    vec4 KeyDir;   // (near_forward.fsh: the key light, the sky and ground radiance)
    vec4 KeyRad;
    vec4 SkyUp;
    vec4 Ground;
    vec4 SunDir;      // w: time in seconds
    vec4 WaterStill;
    vec4 WaterFlow;
    mat4 ShadowMat;
    mat4 InvViewRot;  // view -> camera-relative world
    vec4 CamPos;      // camera world position, x z modulo 6144
    vec4 WaterTint;   // the biomes' water colour around the camera
};
#include <nirncraft:atlas_water.glsl>
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 viewRay(vec2 uv, float depth) {
    vec4 v = inverse(ProjMat) * vec4(uv * 2.0 - 1.0, depth, 1.0);
    return v.xyz / v.w;
}

vec3 octDecode(vec2 e) {
    e = e * 2.0 - 1.0;
    vec3 n = vec3(e.x, 1.0 - abs(e.x) - abs(e.y), e.y);
    if (n.y < 0.0) n.xz = (1.0 - abs(n.zx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.z >= 0.0 ? 1.0 : -1.0);
    return normalize(n);
}

// atlas's near light with its sky part filtered: the key light (its radiance x N.L x the traced visibility atlas returns
// in a) stays per pixel, crisp shadow edges; the rest (the sky, traced with one dithered ray a pixel, which a TAA would
// average over frames) is averaged over 5 x 5 neighbours on the same surface (normal and depth alike), so the shade
// is smooth instead of a stippled pattern
// the near light's sun visibility: a <= 1 as traced; a > 1: the sun reached the point through a - 1 blocks of water
// (atlas_far.slang nearLightAt), its visibility the water's transmission, shaped by caustics here (full rate)
float nlVis(float a) {
    return a > 1.0 ? exp(-0.083 * (a - 1.0)) : a;
}

vec3 nearLightFiltered(vec2 uv, vec4 nl, vec3 n, float depth) {
    vec2 texel = 1.0 / vec2(textureSize(Sampler6, 0));
    vec2 gtexel = 1.0 / vec2(textureSize(Sampler3, 0));
    vec3 sum = vec3(0.0);
    float wsum = 0.0, visSum = 0.0, visW = 0.0;
    float z0 = 1.0 / max(depth, 1e-7);
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec2 o = vec2(float(i), float(j));
            vec4 q = texture(Sampler6, uv + o * texel);
            vec2 g = uv + o * gtexel;
            float d = texture(Sampler3, g).r;
            if (d <= 0.0) continue;
            vec3 nq = octDecode(texture(Sampler1, g).ba);
            float wn = pow(max(dot(n, nq), 0.0), 16.0);
            float wz = exp(-abs(1.0 / d - z0) / (0.02 * z0));
            float w = wn * wz;
            if (dot(q.rgb, vec3(1.0)) < 1e-6) continue;  // a texel atlas did not light (sky, or past a silhouette)
            float qv = nlVis(q.a);
            vec3 skyQ = max(q.rgb - KeyRad.rgb * max(dot(nq, KeyDir.xyz), 0.0) * qv, vec3(0.0));
            sum += skyQ * w;
            wsum += w;
            visSum += qv * w;
            visW += w;
        }
    }
    // atlas lights at its own resolution: at a silhouette the nearest of its texels can be one it did not light (the
    // sky behind a crown): the sun's visibility then comes from the same surface's neighbours (dark fringes otherwise)
    bool centre = dot(nl.rgb, vec3(1.0)) >= 1e-6;
    float vis = centre ? nlVis(nl.a) : (visW > 1e-4 ? visSum / visW : 1.0);
    if (centre && nl.a > 1.0) {
        vec3 pw = (InvViewRot * vec4(viewRay(uv, depth), 1.0)).xyz + vec3(CamPos.x, 0.0, CamPos.z);
        vis *= atlasCausticFactor(pw, nl.a - 1.0);
    }
    vec3 sunPart = KeyRad.rgb * max(dot(n, KeyDir.xyz), 0.0) * vis;
    vec3 sky = wsum > 1e-4 ? sum / wsum : max(nl.rgb - sunPart, vec3(0.0));
    return sunPart + sky;
}

// atlas_light.glsl's forward light (the look's constants kept equal there and in atlas_shared.slang)
vec3 untracedRadiance(vec3 albedo, vec3 n, vec2 lm) {
    float open = smoothstep(0.05, 0.35, lm.y);
    float sky = lm.y * lm.y;
    vec3 direct = KeyRad.rgb * max(dot(n, KeyDir.xyz), 0.0) * open;
    vec3 bounce = KeyRad.rgb * (max(KeyDir.y, 0.0) * 0.25 * (0.6 - 0.4 * n.y) * sky);
    vec3 ambient = mix(Ground.rgb, SkyUp.rgb, n.y * 0.5 + 0.5) * (sky * 3.0) + bounce + vec3(0.012, 0.012, 0.016);
    return atlasSrgbToLinear(albedo) * (direct + ambient + atlasBlockLight(lm.x));
}

// the eye in water: whatever lies dist blocks away is seen through that much water (atlas_water.glsl); vanilla's
// linear water fog left far things untouched
vec3 environmentalFog(vec3 rgb, float dist, vec3 dir) {
    if (atlasEyeInWater()) return atlasUnderwater(rgb, dist, dir) + atlasWaterShafts(dir, dist);
    return mix(rgb, FogColor.rgb, linear_fog_value(dist, FogEnvironmentalStart, FogEnvironmentalEnd) * FogColor.a);
}

void main() {
    bool own = Look.z > 0.5;        // our light and look (1: atlas lit this frame; 2: it did not, our light untraced)
    bool traced = Look.z < 1.5;
    float nearDepth = texture(Sampler3, texCoord).r;
    if (nearDepth > 0.0) {
        vec3 albedo = texture(Sampler0, texCoord).rgb;
        vec4 info = texture(Sampler1, texCoord);
        vec2 levels = info.rg;
        float dist = length(viewRay(texCoord, nearDepth));
        vec3 rgb;
        if (own) {
            // one light: what atlas computed reaches this surface (the far field's own sun, traced shadow, traced sky),
            // capped by the sky light vanilla knows (a cave atlas has no sections for must not see the sky), the block
            // light from vanilla's levels, the ambient floor; then atlas's aerial perspective for this distance
            vec3 n = octDecode(info.ba);
            vec3 radiance;
            if (traced) {
                vec4 nl = texture(Sampler6, texCoord);
                vec4 aerial = texture(Sampler4, texCoord);
                // atlas's frame a few frames behind the camera (the CPU backend, Look.w): a texel it did not light is
                // the sky or far terrain of that frame, not this surface's air: no haze rather than black or a ghost
                if (Look.w > 0.5 && dot(nl.rgb, vec3(1.0)) < 1e-6) aerial = vec4(0.0, 0.0, 0.0, 1.0);
                // atlas holds every section of the loaded chunks (caves included): its traced light is trusted as it is
                // (light spills into a cave mouth and fades in), no cap by vanilla's sky light any more
                vec3 light = nearLightFiltered(texCoord, nl, n, nearDepth);
                radiance = atlasSrgbToLinear(albedo) * (light + atlasBlockLight(levels.r) + vec3(0.012, 0.012, 0.016));
                radiance = radiance * aerial.a + aerial.rgb;
            } else {
                // atlas did not draw this frame (streaming a new place): the same light without its tracing (no cast
                // shadows, vanilla's sky light as the sky's visibility), so the look does not switch to vanilla's
                radiance = untracedRadiance(albedo, n, levels);
            }
            rgb = atlasDisplay(radiance, Look.x, Look.y);
        } else {
            // stage A: vanilla's own light curve (the lightmap: day, night, flicker, night vision, effects) and its
            // fixed face shade (the G-buffer took it out of the albedo)
            vec3 n = octDecode(info.ba), a = abs(n);
            float shade = max(a.x, max(a.y, a.z)) > 0.99 ? (n.y > 0.5 ? 1.0 : n.y < -0.5 ? 0.5 : a.z > 0.5 ? 0.8 : 0.6) : 1.0;
            rgb = albedo * shade * texture(Sampler2, clamp(levels * (15.0 / 16.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
        }
        vec3 dirW = normalize((InvViewRot * vec4(viewRay(texCoord, nearDepth), 0.0)).xyz);
        fragColor = vec4(environmentalFog(rgb, dist, dirW), 1.0);
        gl_FragDepth = nearDepth;
        return;
    }
    vec4 c = texture(Sampler4, texCoord);
    float d = texture(Sampler5, texCoord).r;
    if (c.a <= 0.002 || d > 1e29) {
        if (atlasEyeInWater()) {
            // nothing along this ray under water: the water itself as far as it goes (the sky pass's sky is not seen)
            vec3 dirW = normalize((InvViewRot * vec4(viewRay(texCoord, 0.5), 0.0)).xyz);
            fragColor = vec4(atlasUnderwater(vec3(0.0), 1e4, dirW) + atlasWaterShafts(dirW, 60.0), 1.0);
            gl_FragDepth = 0.0;
            return;
        }
        discard;  // the sky: what the sky pass drew stays
    }
    vec3 straight = max(c.rgb / max(c.a, 1e-4), vec3(0.0));
    // own: the same display as the near pixels; stage A: atlas's harness tonemapper (c / (1 + c), gamma 2.2)
    vec3 ldr = own ? atlasDisplay(straight, Look.x, Look.y) : pow(straight / (1.0 + straight), vec3(1.0 / 2.2));
    // the near field's environmental fog as it stands at the edge of the loaded terrain (AtlasFallback's rule)
    float edge = max(FogRenderDistanceEnd, 16.0);
    float f = clamp(linear_fog_value(min(d, edge), FogEnvironmentalStart, FogEnvironmentalEnd), 0.0, 1.0) * FogColor.a;
    vec3 dirF = normalize((InvViewRot * vec4(viewRay(texCoord, 0.5), 0.0)).xyz);
    vec3 fogged = atlasEyeInWater() ? atlasUnderwater(ldr, d, dirF) + atlasWaterShafts(dirF, d) : mix(ldr, FogColor.rgb, f);
    fragColor = vec4(fogged * c.a, c.a);
    // its depth: the point at that distance along this pixel's ray (beyond the projection's far plane: 0)
    vec4 clip = ProjMat * vec4(normalize(viewRay(texCoord, 0.5)) * d, 1.0);
    gl_FragDepth = clamp(clip.z / clip.w, 0.0, 1.0);
}
