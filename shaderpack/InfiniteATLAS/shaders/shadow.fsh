#version 330 compatibility
#include "/lib/materials.glsl"

uniform sampler2D gtexture;

in vec2 texcoord;
in vec4 glcolor;
flat in int material;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 shadowColor;

void main() {
    if (material == MAT_WATER) discard;  // light reaches the bed (caustics do the rest)
    vec4 c = texture(gtexture, texcoord) * glcolor;
    if (c.a < 0.1) discard;  // leaves and grass cast dappled shadows
    // alpha marks foliage (0.5): light through a canopy reaches the leaves inside it (lib/lighting.glsl, gKeyThrough)
    shadowColor = vec4(c.rgb, isFoliage(material) ? 0.5 : 1.0);
}
