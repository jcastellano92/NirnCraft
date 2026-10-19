#version 330 compatibility
// The sun's shadow map: the same wind as gbuffers (so a swaying plant's shadow sways with it); water casts none.
#include "/lib/settings.glsl"
#include "/lib/materials.glsl"
uniform mat4 gbufferModelViewInverse;
uniform vec3 sunPosition;
#include "/lib/common.glsl"
#include "/lib/keylight.glsl"
#ifdef WAVING_FOLIAGE
#include "/lib/wind.glsl"
#endif

uniform mat4 shadowModelView;
uniform mat4 shadowModelViewInverse;
uniform vec3 cameraPosition;
uniform float frameTimeCounter;
uniform float rainStrength;

in vec4 mc_Entity;
in vec4 at_midBlock;

out vec2 texcoord;
out vec4 glcolor;
flat out int material;

vec3 distortShadow(vec3 p) {
    float f = (1.0 - SHADOW_DISTORT) + length(p.xy) * SHADOW_DISTORT;
    return vec3(p.xy / f, p.z * 0.25);
}

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    glcolor = gl_Color;
    material = materialOf(mc_Entity.x);
    vec3 playerPos = (shadowModelViewInverse * (gl_ModelViewMatrix * gl_Vertex)).xyz;
#ifdef WAVING_FOLIAGE
    if (isFoliage(material)) {
        float up = clamp(0.5 - at_midBlock.y / 64.0, 0.0, 1.0);
        if (material == MAT_HANGING) up = 1.0 - up;
        playerPos += windOffset(playerPos + cameraPosition, frameTimeCounter, rainStrength, material, up);
    }
#endif
    // the night's key may be another moon than Iris' shadow light: turn the casters the way receivers are turned
    playerPos = shadowKeyRotation(keyLightDir(worldSunDir(gbufferModelViewInverse, sunPosition))) * playerPos;
    gl_Position = gl_ProjectionMatrix * (shadowModelView * vec4(playerPos, 1.0));
    gl_Position.xyz = distortShadow(gl_Position.xyz / gl_Position.w) * gl_Position.w;
}
