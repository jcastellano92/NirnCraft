#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS near pass, the composite (com.nirncraft.atlas.AtlasNearPass): our resolved frame (terrain near and far,
// lit, displayed: premultiplied, alpha 0 where only sky is) into the game's main pass after opaque terrain, with its
// depth (reverse-Z), so everything vanilla draws next depth-tests against it.
//  Sampler0: our frame's colour          Sampler1: our frame's depth
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 c = texture(Sampler0, texCoord);
    if (c.a <= 0.002) discard;  // the sky: what the sky pass drew stays
    fragColor = c;
    gl_FragDepth = texture(Sampler1, texCoord).r;
}
