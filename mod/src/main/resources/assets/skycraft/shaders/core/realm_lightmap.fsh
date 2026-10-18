#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 light = texture(Sampler0, texCoord);
    fragColor = vec4(clamp(light.rgb * ColorModulator.rgb, 0.0, 1.0), light.a);
}
