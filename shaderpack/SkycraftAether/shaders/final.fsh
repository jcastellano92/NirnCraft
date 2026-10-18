#version 330 compatibility
// The one tonemapper for near and far: the same curve the aether harness uses (c / (1 + c), gamma 2.2).
#include "/lib/settings.glsl"

uniform sampler2D colortex0;

in vec2 texcoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec3 c = texture(colortex0, texcoord).rgb * EXPOSURE;
    c = c / (1.0 + c);
    fragColor = vec4(pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
}
