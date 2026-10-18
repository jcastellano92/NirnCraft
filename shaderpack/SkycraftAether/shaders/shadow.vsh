#version 330 compatibility
#include "/lib/settings.glsl"

out vec2 texcoord;
out vec4 glcolor;

vec3 distortShadow(vec3 p) {
    float f = (1.0 - SHADOW_DISTORT) + length(p.xy) * SHADOW_DISTORT;
    return vec3(p.xy / f, p.z * 0.25);
}

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    glcolor = gl_Color;
    gl_Position = ftransform();
    gl_Position.xyz = distortShadow(gl_Position.xyz / gl_Position.w) * gl_Position.w;
}
