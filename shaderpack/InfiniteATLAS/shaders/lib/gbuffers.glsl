// Shared geometry program. Each gbuffers_*.vsh/.fsh defines VSH or FSH plus what it draws, then includes this:
//   SOLID       opaque geometry: writes the G-buffer (deferred.fsh lights it)
//   TERRAIN     blocks with material ids (block.properties): wind, PBR defaults, emitters, parallax occlusion
//               from the labPBR height map up close (POM)
//   EMISSIVE    always glowing (spider eyes, beacon beams)
//   TRANSLUCENT water, stained glass, ice: forward-tinted, refracted / reflected in composite.fsh
//   FORWARD_LIT what Iris draws in the translucent phase, after deferred.fsh has lit the G-buffer (translucent
//               entities: 26.3 draws avatar bodies so; translucent block entities): lit forward with its own
//               normals (sun, shadow, sky, block light), premultiplied; an opaque texel looks as an opaque entity
//   ENTITY      with SOLID or FORWARD_LIT: Iris' entityColor (the hurt / freeze flash) over the texture
//   PARTICLES   particles and the mod's alpha meshes (fire smoke, atmosphere particles): forward-lit like a thin
//               volume (sun through it, sky all round), fogged forward (full-bright ones glow: magic, embers)
//   ADDITIVE    with EMISSIVE: pure added light (the mod's flames and additive meshes, spider eyes), HDR for bloom
//   BASIC       untextured geometry (the mod's lightning channels and POSITION_COLOR quads, block outlines,
//               leads): colour from the vertex only (Iris may leave any texture bound), lit forward; bright
//               near-white colours (lightning) are emitted as light
//   WEATHER     rain and snow: into the translucent buffer
//   SKYTEX      the moon (and anything else drawn as a textured sky): marked for the deferred sky
//   DISCARD     draws nothing (vanilla clouds and sky: atlas and deferred.fsh draw those)
//
// G-buffer: colortex0 albedo (sRGB), colortex1 normal (octahedral) + light map, colortex2 smoothness / F0 /
// subsurface / emission, colortex3 material id / AO / metal. Translucents: colortex5 premultiplied colour,
// colortex6 normal + material id + sky light.
#include "/lib/settings.glsl"
#if defined SKYBASIC && !defined NIRNCRAFT_REALM
#define DISCARD  // the Overworld's sky is deferred.fsh's atmosphere
#endif
#if defined SKYBASIC || defined SKYTEX
#ifdef NIRNCRAFT_REALM
#define REALM_SKY  // a realm's own dome and sprites, as the mod (or vanilla) draws them
#endif
#endif
#include "/lib/common.glsl"
#include "/lib/curvature.glsl"
#include "/lib/materials.glsl"
#include "/lib/pbr.glsl"

uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform vec3 sunPosition;
uniform vec3 cameraPosition;
uniform float rainStrength;
uniform float frameTimeCounter;
uniform float wetness;
#ifdef ENTITY
uniform vec4 entityColor;
#endif

#ifdef VSH
#if defined TERRAIN || defined TRANSLUCENT
in vec4 mc_Entity;
in vec4 at_midBlock;
#endif
#if defined TERRAIN && defined SOLID && defined POM && defined PBR_MAPS
in vec2 mc_midTexCoord;
out vec4 tileBounds;  // the block face's sprite in the atlas: origin, size (parallax wraps inside it)
#endif
in vec4 at_tangent;

out vec2 texcoord;
out vec2 lmcoord;
out vec4 glcolor;
out vec3 playerPos;
out vec3 worldNormal;
out vec4 worldTangent;
flat out int material;

#ifdef WAVING_FOLIAGE
#include "/lib/wind.glsl"
#endif

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    glcolor = gl_Color;
    vec4 viewPos = gl_ModelViewMatrix * gl_Vertex;
    playerPos = (gbufferModelViewInverse * viewPos).xyz;
    worldNormal = normalize(mat3(gbufferModelViewInverse) * (gl_NormalMatrix * gl_Normal));
    worldTangent = vec4(normalize(mat3(gbufferModelViewInverse) * (gl_NormalMatrix * at_tangent.xyz)), at_tangent.w);
    material = MAT_NONE;
#if defined TERRAIN && defined SOLID && defined POM && defined PBR_MAPS
    vec2 mid = (gl_TextureMatrix[0] * vec4(mc_midTexCoord, 0.0, 1.0)).xy;
    vec2 hs = abs(texcoord - mid);
    tileBounds = vec4(mid - hs, hs * 2.0);
#endif
#if defined TERRAIN || defined TRANSLUCENT
    material = materialOf(mc_Entity.x);
#if defined WAVING_FOLIAGE && defined TERRAIN
    if (isFoliage(material)) {
        // at_midBlock: from the vertex to its block's centre, in 1/64 blocks: > 0 in y means below the centre
        float up = clamp(0.5 - at_midBlock.y / 64.0, 0.0, 1.0);
        if (material == MAT_HANGING) up = 1.0 - up;  // hangs from its top
        playerPos += windOffset(playerPos + cameraPosition, frameTimeCounter, rainStrength, material, up);
    }
#endif
#endif
#if defined SKYTEX || defined SKYBASIC
    gl_Position = ftransform();
#else
    gl_Position = gl_ProjectionMatrix * (gbufferModelView * vec4(curvePlayerPos(playerPos), 1.0));
#endif
}
#endif

#ifdef FSH
uniform sampler2D gtexture;
#ifdef PBR_MAPS
uniform sampler2D normals;
uniform sampler2D specular;
#endif

in vec2 texcoord;
in vec2 lmcoord;
in vec4 glcolor;
in vec3 playerPos;
in vec3 worldNormal;
in vec4 worldTangent;
flat in int material;
#if defined TERRAIN && defined SOLID && defined POM && defined PBR_MAPS
#define PARALLAX
in vec4 tileBounds;
#include "/lib/quality.glsl"
#endif

#if defined SOLID
/* RENDERTARGETS: 0,1,2,3 */
layout(location = 0) out vec4 outAlbedo;
layout(location = 1) out vec4 outNormal;
layout(location = 2) out vec4 outSpecular;
layout(location = 3) out vec4 outMaterial;
#elif defined REALM_SKY
/* RENDERTARGETS: 7 */
layout(location = 0) out vec4 outColor;
#elif defined SKYTEX
/* RENDERTARGETS: 0,3 */
layout(location = 0) out vec4 outAlbedo;
layout(location = 1) out vec4 outMaterial;
#elif defined TRANSLUCENT || defined FORWARD_LIT
/* RENDERTARGETS: 5,6 */
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outInfo;
#elif defined DISCARD
/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;
#else
/* RENDERTARGETS: 5 */
layout(location = 0) out vec4 outColor;
#endif

#if defined TRANSLUCENT || defined PARTICLES || defined WEATHER || defined BASIC || defined FORWARD_LIT
const float BASIC_GLOW = 6.0;  // lightning channels and other near-white untextured light
#include "/lib/lighting.glsl"
#include "/lib/fog.glsl"

// forward translucents carry the air in front of them themselves (composite fogs the scene behind to its own depth,
// then lays these over it): premultiplied colour c of coverage a
vec3 fogForward(vec3 c, float a, vec3 sun) {
    vec4 air = airLight(normalize(playerPos), sun, length(playerPos), cameraPosition.y, wetness, rainStrength);
    return c * air.a + air.rgb * a;
}
#endif
#ifdef TRANSLUCENT
#include "/lib/weather.glsl"
#include "/lib/waves.glsl"
#include "/lib/flow.glsl"
#endif

// the surface normal with the resource pack's normal map (labPBR: xy in rg, z reconstructed), world space
vec3 surfaceNormal(vec4 nm) {
    vec3 n = normalize(worldNormal);
#ifdef PBR_MAPS
    if (nm.a > 0.0 || nm.r + nm.g > 0.0) {
        vec3 t = normalize(worldTangent.xyz - n * dot(n, worldTangent.xyz));
        vec3 b = cross(n, t) * (worldTangent.w < 0.0 ? -1.0 : 1.0);
        vec2 xy = nm.rg * 2.0 - 1.0;
        vec3 tn = vec3(xy, sqrt(max(1.0 - dot(xy, xy), 0.0)));
        n = normalize(mat3(t, b, n) * tn);
    }
#endif
    return n;
}

#ifdef PARALLAX
vec2 wrapTile(vec2 uv) {
    return tileBounds.xy + mod(uv - tileBounds.xy, tileBounds.zw);
}

// parallax occlusion: the labPBR height map (normal map alpha, 1 = top) marched along the view ray inside the block
// face's sprite, up close only (POM_DISTANCE, fading out; the SSAO knob scales the steps: contact detail goes
// together). The face's u / v directions come from screen derivatives (no atlas orientation assumed). dx, dy: the
// texture coordinate's derivatives, taken before any branch
vec2 parallaxUV(vec3 n, vec2 dx, vec2 dy, vec3 dp1, vec3 dp2) {
    float dist = length(playerPos);
    float fade = 1.0 - smoothstep(POM_DISTANCE * 0.6, POM_DISTANCE, dist);
    if (fade <= 0.0 || isFoliage(material) || tileBounds.z <= 0.0 || tileBounds.w <= 0.0) return texcoord;
    float q = qualityKnob(2, 0, QUALITY_FALLBACK) * qualityFade(qualityKnob(2, 0, QUALITY_FALLBACK), 0.2);
    if (q <= 0.0) return texcoord;
    float h = 1.0 - textureGrad(normals, texcoord, dx, dy).a;  // how deep the surface lies here, 0..1
    if (h <= 0.003) return texcoord;  // the top of the height field, or no height map
    vec3 dp2perp = cross(dp2, n), dp1perp = cross(n, dp1);
    vec3 T = dp2perp * dx.x + dp1perp * dy.x, B = dp2perp * dx.y + dp1perp * dy.y;
    if (dot(T, T) < 1e-24 || dot(B, B) < 1e-24) return texcoord;
    vec3 v = normalize(playerPos);
    float vn = max(-dot(v, n), 0.08);
    // one block of depth along the ray drifts this far across the face, in this sprite's texture coordinates
    vec2 drift = vec2(dot(v, normalize(T)), dot(v, normalize(B))) / vn * tileBounds.zw * (POM_DEPTH * fade);
    int steps = qualityCount(POM_STEPS, q, qualityDither(gl_FragCoord.xy), 2);
    float layer = 1.0 / float(steps), depth = 0.0, prevDepth = 0.0, prevH = h;
    for (int i = 0; i < POM_STEPS; i++) {
        if (i >= steps || depth >= h) break;
        prevDepth = depth;
        prevH = h;
        depth += layer;
        h = 1.0 - textureGrad(normals, wrapTile(texcoord + drift * depth), dx, dy).a;
    }
    // where the ray crossed the surface between the last two layers
    float over = depth - h, under = prevH - prevDepth;
    float hit = depth >= h ? mix(prevDepth, depth, under / max(over + under, 1e-5)) : depth;
    return wrapTile(texcoord + drift * hit);
}
#endif

void main() {
#ifdef DISCARD
    discard;
#elif defined REALM_SKY
    // premultiplied over the realm's sky so far; a sprite's black surround (drawn additively by vanilla) covers nothing
#ifdef SKYBASIC
    vec4 sky = glcolor;
#else
    vec4 sky = glcolor * texture(gtexture, texcoord);
#endif
    vec3 sc = srgbToLinear(sky.rgb);
    float cover = sky.a * smoothstep(0.015, 0.2, max(sky.r, max(sky.g, sky.b)));
#ifdef SKYBASIC
    cover = sky.a;
#endif
    outColor = vec4(sc * sky.a, cover);
#else
#ifdef BASIC
    vec4 albedo = glcolor;  // untextured: whatever texture Iris left bound is not ours to sample
    if (albedo.a < 0.004) discard;
#elif defined PARALLAX
    vec2 tdx = dFdx(texcoord), tdy = dFdy(texcoord);
    vec2 uv = parallaxUV(normalize(worldNormal), tdx, tdy, dFdx(playerPos), dFdy(playerPos));
    vec4 albedo = glcolor * textureGrad(gtexture, uv, tdx, tdy);
    if (albedo.a < 0.1) discard;
#else
    vec4 albedo = glcolor * texture(gtexture, texcoord);
    if (albedo.a < 0.1) discard;
#endif
#ifdef ENTITY
    albedo.rgb = mix(albedo.rgb, entityColor.rgb, entityColor.a);  // the hurt flash
#endif
    vec2 lm = clamp((lmcoord - 1.0 / 32.0) * (16.0 / 15.0), 0.0, 1.0);
    vec4 nm = vec4(0.0), sp = vec4(0.0);
#ifdef PARALLAX
    nm = textureGrad(normals, uv, tdx, tdy);
    sp = textureGrad(specular, uv, tdx, tdy);
#elif defined PBR_MAPS
    nm = texture(normals, texcoord);
    sp = texture(specular, texcoord);
#endif
#if defined SKYTEX
    outAlbedo = vec4(albedo.rgb, 1.0);
    outMaterial = vec4(1.0, 0.0, 0.0, 0.0);  // id 255: textured sky (the moon), for the deferred sky
#elif defined SOLID
    vec3 n = surfaceNormal(nm);
    vec4 def = materialDefaults(material, srgbToLinear(albedo.rgb));
    vec4 lab = decodeLabSpecular(sp);
    bool haveSpec = sp.r + sp.g + sp.b > 0.0;  // no specular map: the material's defaults
    float smoothness = haveSpec ? lab.x : def.x;
    float f0 = haveSpec ? lab.y : def.y;
    float emission = max(def.w, haveSpec ? lab.w : 0.0);
#ifdef EMISSIVE
    emission = 1.0;
#endif
    float ao = nm.a + nm.r + nm.g > 0.0 ? nm.b : 1.0;  // labPBR: AO in the normal map's blue
    outAlbedo = vec4(albedo.rgb, 1.0);
    outNormal = vec4(octEncode(n), lm.x, lm.y);
    outSpecular = vec4(smoothness, f0, def.z, emission);
    outMaterial = vec4(float(material) / 255.0, ao, haveSpec ? lab.z : (material == MAT_METAL ? 1.0 : 0.0), 0.0);
#elif defined TRANSLUCENT
    vec3 sun = worldSunDir(gbufferModelViewInverse, sunPosition);
    vec3 n = normalize(worldNormal);
    float dist = length(playerPos);
    float a = albedo.a;
    float foam = 0.0;
    vec3 wp = playerPos + cameraPosition;
    if (material == MAT_WATER && n.y > 0.5) {
        // open water: more sky seen = a bigger body; storms whip it up
        float openness = smoothstep(0.6, 1.0, lm.y);
        vec2 wxz = wp.xz;
        vec2 flow = vec2(0.0);
#ifdef WATER_FLOW
        // flowing water: the waves ride the flow, ripples tighten, steep runs break white
        vec4 fl = waterFlow(playerPos, texcoord, cameraPosition.y);
        flow = fl.xy;
        openness *= 1.0 - 0.7 * fl.w;
        foam = rapidsFoam(wxz, flow, fl.z, frameTimeCounter, dist) * fl.w;
#endif
        // the live wind sets the heading and size of the waves; rain rings the surface where the sky reaches it
        n = waveNormal(wxz, frameTimeCounter, openness, waterWind(wxz, rainStrength), flow, dist,
                       rainStrength * smoothstep(0.85, 1.0, lm.y));
        a = WATER_ALPHA;
    } else if (material == MAT_WATER) {
        a = WATER_ALPHA;  // falls and the sides of flowing water
#ifdef WATER_FLOW
        if (abs(n.y) < 0.3) {
            // a fall: white water streaming down the face
            vec2 fw = fallsWater(wp, n, frameTimeCounter, dist);
            foam = fw.x;
            vec2 t = normalize(vec2(-n.z, n.x) + vec2(1e-5));
            n = normalize(n + vec3(t.x, 0.0, t.y) * fw.y);
        }
#endif
    }
    vec3 base = srgbToLinear(albedo.rgb);
    vec3 lit = shadeForward(base, n, lm, playerPos, sun, 0.0);
    outColor = vec4(lit * a, a);
    outInfo = vec4(octEncode(n), float(material == MAT_NONE ? MAT_GLASS : material) / 255.0, packSkyFoam(lm.y, foam));
#elif defined FORWARD_LIT
    // a body, a translucent block entity: lit as the deferred pass lights the opaque scene (its own normals, the key
    // light with its shadow, sky and block light), the air in front of it carried forward. colortex6 says "not water
    // or glass" here, so a body before a lake is never taken for the lake's surface
    vec3 sun = worldSunDir(gbufferModelViewInverse, sunPosition);
    vec3 n = surfaceNormal(nm);
    vec3 lit = shadeForward(srgbToLinear(albedo.rgb), n, lm, playerPos, sun, 0.0);
    outColor = vec4(fogForward(lit * albedo.a, albedo.a, sun), albedo.a);
    outInfo = vec4(octEncode(n), 0.0, 0.0);
#elif defined WEATHER
    vec3 sun = worldSunDir(gbufferModelViewInverse, sunPosition);
    vec3 c = srgbToLinear(albedo.rgb) * (skyAmbient(vec3(0, 1, 0), sun) * 1.4 + sunRadiance(sun) * 0.15);
    float a = albedo.a * 0.55;
    outColor = vec4(fogForward(c * a, a, sun), a);
#elif defined BASIC
    // lines and untextured quads: lit like a thin thing in the air; near-white bright colours (the storm's lightning
    // channels, glowing outlines) are light, added rather than covering (alpha 0 under the premultiplied blend)
    vec3 sun = worldSunDir(gbufferModelViewInverse, sunPosition);
    vec3 base = srgbToLinear(albedo.rgb);
    float lum = dot(base, vec3(0.2126, 0.7152, 0.0722));
    float sat = max(base.r, max(base.g, base.b)) - min(base.r, min(base.g, base.b));
    float glow = smoothstep(0.35, 0.75, lum) * (1.0 - smoothstep(0.35, 0.7, sat));
    vec3 lit = shadeForward(base, vec3(0, 1, 0), lm, playerPos, sun, 0.0);
    vec3 c = mix(lit, base * BASIC_GLOW, glow) * albedo.a;
    float a = albedo.a * (1.0 - glow);
    outColor = vec4(fogForward(c, a, sun), a);
#elif defined ADDITIVE
    // flames, the mod's additive meshes, spider eyes: light only, bright enough for bloom; fog dims it with distance
    vec3 sun = worldSunDir(gbufferModelViewInverse, sunPosition);
    vec3 base = srgbToLinear(albedo.rgb);
    vec3 c = base * albedo.a * EMISSIVE_STRENGTH;
    outColor = vec4(fogForward(c, 0.0, sun), 0.0);
#else
    // particles, the mod's smoke meshes and the rest: a thin volume, lit from every side by the sky, by the sun
    // through it (forward scattering: bright rims looking into the light), by block light (fire under its own smoke);
    // full-bright ones (magic, embers, sparks) glow
    vec3 sun = worldSunDir(gbufferModelViewInverse, sunPosition);
    vec3 base = srgbToLinear(albedo.rgb);
    float glow = smoothstep(0.92, 1.0, lm.x) * smoothstep(0.4, 0.8, dot(base, vec3(0.33)));
#ifdef EMISSIVE
    glow = 1.0;
#endif
    vec3 L = keyLightDir(sun);
    float vis = keyVisibility(playerPos, L, L, gl_FragCoord.xy) * smoothstep(0.05, 0.35, lm.y);
    vec3 v = normalize(playerPos);
    float mu = dot(v, L);
    // Henyey-Greenstein, g = 0.45 (relative to isotropic = 1), plus an isotropic share (soot scatters both ways)
    float hg = (1.0 - 0.2025) / pow(max(1.2025 - 0.9 * mu, 1e-3), 1.5);
    float phase = 0.35 + 0.65 * hg;
    vec3 sky = (skyAmbient(vec3(0, 1, 0), sun) * 0.7 + skyAmbient(vec3(0, -1, 0), sun) * 0.3) * lm.y * lm.y;
    vec3 light = keyLightRadiance(sun) * vis * phase * 0.5 + sky + vec3(0.012, 0.012, 0.016) + blockLight(lm.x);
    vec3 c = base * light + base * glow * 3.0;
    outColor = vec4(fogForward(c * albedo.a, albedo.a, sun), albedo.a);
#endif
#endif
}
#endif
