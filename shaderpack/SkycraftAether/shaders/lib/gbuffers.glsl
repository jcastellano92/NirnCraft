// Shared gbuffers program. Each gbuffers_*.vsh/.fsh defines VSH or FSH plus feature flags, then includes this:
//   LIT (light map + sun), TEXTURED (samples gtexture), EMISSIVE, WATER (translucent water), WEATHER (rain/snow),
//   SKY (sky dome), DISCARD (draw nothing, e.g. vanilla clouds: aether draws the cloud shell).
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/curvature.glsl"

uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferProjection;
uniform vec3 sunPosition;
uniform vec3 cameraPosition;
uniform float rainStrength;

#ifdef VSH
out vec2 texcoord;
out vec2 lmcoord;
out vec4 glcolor;
out vec3 playerPos;
out vec3 worldNormal;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    glcolor = gl_Color;
    vec4 viewPos = gl_ModelViewMatrix * gl_Vertex;
    playerPos = (gbufferModelViewInverse * viewPos).xyz;
    worldNormal = normalize(mat3(gbufferModelViewInverse) * (gl_NormalMatrix * gl_Normal));
#ifdef SKY
    gl_Position = ftransform();
#else
    gl_Position = gl_ProjectionMatrix * (gbufferModelView * vec4(curvePlayerPos(playerPos), 1.0));
#endif
}
#endif

#ifdef FSH
#ifdef LIT
#include "/lib/lighting.glsl"
#else
#include "/lib/atmosphere.glsl"
#endif

uniform sampler2D gtexture;

in vec2 texcoord;
in vec2 lmcoord;
in vec4 glcolor;
in vec3 playerPos;
in vec3 worldNormal;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

void main() {
#ifdef DISCARD
    discard;
#else
    vec3 sun = worldSunDir(gbufferModelViewInverse, sunPosition);
#ifdef SKY
    // the sky dome: the same sky the far field's haze and aether's clouds are lit against
    vec3 dir = normalize(playerPos);
    outColor = vec4(skyColor(dir, sun), 1.0);
#else
    vec4 albedo = glcolor;
#ifdef TEXTURED
    albedo *= texture(gtexture, texcoord);
#endif
    if (albedo.a < 0.1) discard;
    vec3 base = srgbToLinear(albedo.rgb);
    vec2 lm = clamp((lmcoord - 1.0 / 32.0) * (16.0 / 15.0), 0.0, 1.0);
#if defined WATER
    // water: tinted, with a Fresnel sky reflection; the colour behind shows through the blend
    vec3 v = normalize(playerPos);
    vec3 n = normalize(worldNormal);
    float cosv = clamp(dot(-v, n), 0.0, 1.0);
    float fres = 0.02 + 0.98 * pow(1.0 - cosv, 5.0);
    vec3 refl = skyColor(reflect(v, n), sun) * (0.25 + 0.75 * lm.y);
    vec3 lit = shadeForward(base, n, lm, playerPos, sun, 0.0);
    outColor = vec4(mix(lit, refl, fres), mix(WATER_ALPHA, 1.0, fres));
#elif defined WEATHER
    outColor = vec4(base * skyAmbient(vec3(0, 1, 0), sun) * 1.4, albedo.a * 0.6);
#elif defined LIT
#ifdef EMISSIVE
    float emissive = 1.0;
#else
    float emissive = 0.0;
#endif
    outColor = vec4(shadeForward(base, normalize(worldNormal), lm, playerPos, sun, emissive), albedo.a);
#else
    outColor = vec4(base, albedo.a);
#endif
#endif
#endif
}
#endif
