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
    vec4 CamAbs;      // the camera's world position; w the sun map's size S (0: no sun map)
};
uniform sampler2D SunMap;  // RG32F, S x S + 1: the terrain's depth along the light from the map's centre, cloud transmittance;
                           // the last row: centre (world), half extent, X, Y, L (atlas_far.slang sunMapMain)
#include <nirncraft:atlas_water.glsl>
uniform sampler2D ShadowMap;  // the key light's shadow map of the near terrain (AtlasNearPass.drawShadow)

// whether the key light reaches camera-relative world point p, from the shadow map: 1 lit, 0 shadowed, -1 outside it
float sunVisAt(vec3 p) {
    vec4 s = ShadowMat * vec4(p, 1.0);
    vec2 uv = s.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || ShadowMat[3][3] == 0.0) return -1.0;
    return texture(ShadowMap, uv).r <= s.z + 0.0015 ? 1.0 : 0.0;  // reverse-Z: an occluder nearer the light is larger
}

// The sun's glare: the disc is small but so bright the eye (and a camera) sees a large glowing blob round it (the
// scattering in the eye's lens and the air's forward glow): a sharp core and a wide halo, as bright and as coloured as
// the sun's light through the air (KeyRad: white and large at midday, dim and red low over the horizon, where the disc
// itself shows), dimmed by how much of the sun the near and far geometry hides (taps round its place on screen).
// Linear radiance, added to every pixel; dir unit camera-relative.
float sunCover(vec2 uv) {
    float d = texture(Sampler3, uv).r;  // the near field (reverse-Z: 0 = nothing)
    if (d > 0.0) return 1.0;
    return texture(Sampler5, uv).r < 1e29 ? 1.0 : 0.0;  // the far field
}

vec3 sunGlare(vec3 dir) {
    vec3 S = normalize(SunDir.xyz);
    if (S.y < -0.06 || dot(normalize(KeyDir.xyz), S) < 0.99) return vec3(0.0);  // (the key is the moon at night)
    // the sun on screen, and how much of it shows
    mat4 viewRot = transpose(InvViewRot);  // (a rotation)
    vec4 c = ProjMat * (viewRot * vec4(S * 1000.0, 1.0));
    float vis = 1.0;
    if (c.w > 0.0) {
        vec2 su = c.xy / c.w * 0.5 + 0.5;
        if (all(greaterThan(su, vec2(0.0))) && all(lessThan(su, vec2(1.0)))) {
            float covered = 0.0;
            const int TAPS = 12;
            for (int k = 0; k < TAPS; k++) {
                float a = float(k) * 2.39996, r = 0.012 * sqrt((float(k) + 0.5) / float(TAPS));
                covered += sunCover(su + vec2(cos(a), sin(a)) * vec2(r * float(textureSize(Sampler3, 0).y) / float(textureSize(Sampler3, 0).x), r));
            }
            vis = 1.0 - covered / float(TAPS);
        }
    }
    float th = acos(clamp(dot(dir, S), -1.0, 1.0));  // radians from the sun's centre
    float core = exp(-th / 0.012);
    float halo = 0.05 / (1.0 + pow(th / 0.04, 2.0));
    // low over the horizon the air takes the glare's spread with the light: a smaller, softer blob
    float low = smoothstep(0.0, 0.25, S.y);
    return KeyRad.rgb * vis * (core * 1.6 + halo * mix(0.4, 1.0, low));
}

// Crepuscular rays: how much of the air along a view ray lies in the shadow of the terrain (mountains, ridges, the far
// field's own landscape out to the sun map's +-2048 blocks), weighted towards the air near the eye; 0..1. The sky's and
// the haze's scattered light is dimmed by it: dark shafts fanning across a low sun's sky behind the mountains, the light
// shooting up between them as the sun sets. dir unit camera-relative, dist blocks (1e30: the sky).
vec2 sunMapAt(int i) {
    int S = int(CamAbs.w);
    return texelFetch(SunMap, ivec2(i, S), 0).rg;
}

float airShadow(vec3 dir, float dist) {
    int S = int(CamAbs.w);
    if (S < 2) return 0.0;
    vec2 p0 = sunMapAt(0), p1 = sunMapAt(1), p2 = sunMapAt(2), p3 = sunMapAt(3), p4 = sunMapAt(4), p5 = sunMapAt(5),
         p6 = sunMapAt(6);
    if (p6.y < 0.5) return 0.0;  // no valid map (the light at the horizon)
    vec3 C = vec3(p0.x, p0.y, p1.x);
    float he = p1.y;
    vec3 X = vec3(p2.x, p2.y, p3.x), Y = vec3(p3.y, p4.x, p4.y), L = vec3(p5.x, p5.y, p6.x);
    vec3 eye = CamAbs.xyz - C;
    float len = min(dist, 3000.0);
    const int N = 20;
    float f = fract(SunDir.w * 7.31);
    float jit = fract(52.9829189 * fract(dot(gl_FragCoord.xy + f * 71.0, vec2(0.06711056, 0.00583715))));
    float shadowed = 0.0, wsum = 0.0;
    for (int k = 0; k < N; k++) {
        float s = (float(k) + jit) / float(N);
        float t = len * s * s;                      // denser near the eye, where the shafts are seen
        float dt = len * 2.0 * s / float(N);
        vec3 q = eye + dir * t;
        float u = dot(q, X) / he, v = dot(q, Y) / he;
        if (abs(u) > 1.0 || abs(v) > 1.0) continue;
        vec2 st = texelFetch(SunMap, ivec2(clamp(int((u * 0.5 + 0.5) * float(S)), 0, S - 1),
                                           clamp(int((v * 0.5 + 0.5) * float(S)), 0, S - 1)), 0).rg;
        float w = dt * exp(-max(q.y + C.y - 63.0, 0.0) / 1200.0);  // thinner air higher up
        shadowed += (st.x > dot(q, L) + 2.0 ? 1.0 : 0.0) * w;
        wsum += w;
    }
    return wsum > 0.0 ? shadowed / wsum : 0.0;
}

// the scattered light of the sky or the haze along dir, dimmed where the terrain shadows the air: most towards a low sun
// (the forward scattering the shafts are made of), a little away from it (anticrepuscular, opposite the sun)
float crepuscular(vec3 dir, float dist) {
    vec3 S = normalize(SunDir.xyz);
    if (S.y < -0.02) return 1.0;
    float low = 1.0 - smoothstep(0.05, 0.4, S.y);
    float towards = 0.35 + 0.65 * pow(max(dot(dir, S), 0.0), 3.0);
    return 1.0 - airShadow(dir, dist) * (0.15 + 0.5 * low) * towards;
}

// The sun's light scattered towards the eye by the air between it and what it sees, dist blocks along unit camera-
// relative dir, shadowed by everything the shadow map holds: beams through leaves, through windows and doors, down a
// cave's skylight; a lit haze where nothing is in the way. Linear radiance (before the display). A march through the
// shadow map's range (80 blocks), a per-pixel and per-frame offset the TAA averages.
vec3 airBeams(vec3 dir, float dist) {
    vec3 L = normalize(KeyDir.xyz);
    if (KeyDir.w < 0.5 || L.y < 0.02 || ShadowMat[3][3] == 0.0) return vec3(0.0);
    // the shadowing is probed over the same 72 blocks for every pixel (so a ray stopped by a tree and its neighbour that
    // passes it agree on how much the air glows: per-ray weights drew an outline round every silhouette); the light is
    // gathered only up to what the pixel sees
    const float PROBE = 72.0;
    float len = min(dist, PROBE);
    const int N = 24;
    float dt = PROBE / float(N);
    // interleaved gradient noise, shifted each frame
    float f = fract(SunDir.w * 7.31);
    float jit = fract(52.9829189 * fract(dot(gl_FragCoord.xy + f * 113.0, vec2(0.06711056, 0.00583715))));
    float lit = 0.0, probeLit = 0.0, inside = 0.0;
    for (int k = 0; k < N; k++) {
        float t = (float(k) + jit) * dt;
        float v = sunVisAt(dir * t);
        if (v < 0.0) continue;  // past the shadow map: atlas's aerial haze has that air
        probeLit += v;
        inside += 1.0;
        if (t < len) lit += v;
    }
    if (inside < 0.5) return vec3(0.0);
    // the beams are the lit air among shadows: where nothing along the ray is shadowed the air's light is the haze atlas
    // already draws, so only a little of the uniform part stays (open views stay clear); a ray through a canopy, a
    // window or a cave's mouth keeps all of it, lit stretches between dark ones
    float shadowed = 1.0 - probeLit / inside;
    float keep = clamp(0.15 + shadowed * 3.0, 0.15, 1.0);
    float sigma = 0.004;  // the air's scattering a block (dust and moisture; the weather sets it later)
    float c = dot(dir, L);
    float g = 0.55;
    float hg = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * c, 1.5);
    float phase = (0.7 * hg + 0.3) / 12.566;  // forward glow towards the sun, some light to the sides
    return KeyRad.rgb * (lit * dt * sigma * phase * keep);
}
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
    vec3 visC = vec3(vis);
    if (centre && nl.a > 1.0) {
        // through water: red lost first, then green (per channel, not the grey 8 % a block), shaped by the caustics
        vec3 pw = (InvViewRot * vec4(viewRay(uv, depth), 1.0)).xyz + vec3(CamPos.x, 0.0, CamPos.z);
        visC = exp(-ATLAS_WATER_ABSORB * (nl.a - 1.0)) * atlasCausticFactor(pw, nl.a - 1.0);
    }
    vec3 sunPart = KeyRad.rgb * max(dot(n, KeyDir.xyz), 0.0) * visC;
    vec3 sky = wsum > 1e-4 ? sum / wsum : max(nl.rgb - sunPart, vec3(0.0));
    return sunPart + sky;
}

// atlas_light.glsl's forward light (the look's constants kept equal there and in atlas_shared.slang: LOOK_AMBIENT 1,
// LOOK_BOUNCE 0.25)
vec3 untracedRadiance(vec3 albedo, vec3 n, vec2 lm) {
    float open = smoothstep(0.05, 0.35, lm.y);
    float sky = lm.y * lm.y;
    vec3 direct = KeyRad.rgb * max(dot(n, KeyDir.xyz), 0.0) * open;
    vec3 bounce = KeyRad.rgb * (max(KeyDir.y, 0.0) * 0.25 * (0.6 - 0.4 * n.y) * sky);
    vec3 ambient = mix(Ground.rgb, SkyUp.rgb, n.y * 0.5 + 0.5) * (sky * 1.0) + bounce + vec3(0.0025, 0.0025, 0.0035);
    return atlasSrgbToLinear(albedo) * (direct + ambient + atlasBlockLight(lm.x));
}

// the eye in water: whatever lies dist blocks away is seen through that much water (atlas_water.glsl); vanilla's
// linear water fog left far things untouched
// vanilla's fog only where it is a special fog (blindness, darkness, lava, powder snow, a dense biome fog: short ranges)
// when our look is on: the air's haze is atlas's (aerialInscatter), and vanilla's grey distance fog over it hazed the far
// field twice (bright, washed mountains at night)
float vanillaFogShare() {
    return Look.z > 0.5 ? Ground.w : 1.0;  // (our look: only a special medium; no distance fog of vanilla's)
}

vec3 environmentalFog(vec3 rgb, float dist, vec3 dir) {
    if (atlasEyeInWater()) return atlasUnderwater(rgb, dist, dir) + atlasWaterShafts(dir, dist);
    if (Look.z > 0.5) return atlasMedium(rgb, dist);  // our look: the air's haze is atlas's; a medium if the eye is in one
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
                radiance = atlasSrgbToLinear(albedo) * (light + atlasBlockLight(levels.r) + vec3(0.0025, 0.0025, 0.0035));
                radiance = radiance * aerial.a + aerial.rgb;
            } else {
                // atlas did not draw this frame (streaming a new place): the same light without its tracing (no cast
                // shadows, vanilla's sky light as the sky's visibility), so the look does not switch to vanilla's
                radiance = untracedRadiance(albedo, n, levels);
            }
            // the light in the air in front of it (not under water: the water's own light there)
            vec3 dirB = normalize((InvViewRot * vec4(viewRay(texCoord, nearDepth), 0.0)).xyz);
            // (glare over geometry is a faint veil: full, it washed backlit mountains white)
            if (!atlasEyeInWater()) radiance += airBeams(dirB, dist) + sunGlare(dirB) * 0.15;
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
        // the sky: what the sky pass drew stays, the light in the air in front of it added (beams against the sky, up
        // through a canopy or a cave's skylight)
        vec3 dirS = normalize((InvViewRot * vec4(viewRay(texCoord, 0.5), 0.0)).xyz);
        vec3 b = Look.z > 0.5 && !atlasEyeInWater() ? atlasDisplay(airBeams(dirS, 80.0) + sunGlare(dirS), Look.x, Look.y) : vec3(0.0);
        // the terrain's shadow in the air dims the sky drawn behind (premultiplied blend: dst x (1 - a) + src)
        float cr = Look.z > 0.5 && !atlasEyeInWater() ? crepuscular(dirS, 1e30) : 1.0;
        if (dot(b, vec3(1.0)) < 1e-4 && cr > 0.999) discard;
        fragColor = vec4(b, 1.0 - cr);
        gl_FragDepth = 0.0;
        return;
    }
    vec3 straight = max(c.rgb / max(c.a, 1e-4), vec3(0.0));
    // own: the same display as the near pixels; stage A: atlas's harness tonemapper (c / (1 + c), gamma 2.2)
    vec3 ldr = own ? atlasDisplay(straight, Look.x, Look.y) : pow(straight / (1.0 + straight), vec3(1.0 / 2.2));
    // the near field's environmental fog as it stands at the edge of the loaded terrain (AtlasFallback's rule)
    float edge = max(FogRenderDistanceEnd, 16.0);
    float f = clamp(linear_fog_value(min(d, edge), FogEnvironmentalStart, FogEnvironmentalEnd), 0.0, 1.0) * FogColor.a * vanillaFogShare();
    vec3 dirF = normalize((InvViewRot * vec4(viewRay(texCoord, 0.5), 0.0)).xyz);
    vec3 fogged = atlasEyeInWater() ? atlasUnderwater(ldr, d, dirF) + atlasWaterShafts(dirF, d)
                : own ? atlasMedium(ldr, d) : mix(ldr, FogColor.rgb, f);
    if (!atlasEyeInWater() && own) {  // (the far field: the glare a faint veil, as over near geometry)
        // the haze in front of far terrain is the sky's light too: dimmed where the terrain shadows the air
        float crF = crepuscular(dirF, d);
        fogged = fogged * mix(1.0, crF, 1.0 - exp(-d / 5000.0)) + atlasDisplay(airBeams(dirF, d) + sunGlare(dirF) * 0.15, Look.x, Look.y);  // (the haze's share grows with distance)
    }
    // (under water nothing behind is seen: partial coverage let the sky pass's sky through, a line and the world far off)
    fragColor = atlasEyeInWater() ? vec4(fogged, 1.0) : vec4(fogged * c.a, c.a);
    // its depth: the point at that distance along this pixel's ray (beyond the projection's far plane: 0)
    vec4 clip = ProjMat * vec4(normalize(viewRay(texCoord, 0.5)) * d, 1.0);
    gl_FragDepth = clamp(clip.z / clip.w, 0.0, 1.0);
}
