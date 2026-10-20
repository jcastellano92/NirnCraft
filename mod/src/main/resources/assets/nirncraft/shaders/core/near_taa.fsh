#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS's temporal resolve (com.nirncraft.atlas.AtlasNearPass): the frame (terrain near and far, lit and
// displayed, sampled at this frame's sub-pixel jitter) blended with the last result reprojected to where this pixel's
// surface was then. Static world only (it holds what the near pass and atlas drew; entities, water and particles are
// drawn after, untouched). Over the jitter sequence edges and distant detail resolve to their coverage instead of
// crawling, and atlas's per-pixel dithers average out.
//  Sampler0: this frame (premultiplied, alpha 0 = sky)   Sampler1: its depth (reverse-Z; 0 = beyond the far plane or sky)
//  Sampler2: the last result
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
layout(std140) uniform TaaInfo {
    mat4 InvViewProj;   // this frame, unjittered, camera-relative
    mat4 PrevViewProj;  // the last frame's, unjittered, camera-relative to its own camera
    vec4 CamDelta;      // this camera's position minus the last one's (blocks)
    vec4 Params;        // x weight of this frame, y history valid
};
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 c = texture(Sampler0, texCoord);
    vec2 px = 1.0 / vec2(textureSize(Sampler0, 0));
    vec4 mn = c, mx = c;
    for (int j = -1; j <= 1; j++)
        for (int i = -1; i <= 1; i++) {
            if (i == 0 && j == 0) continue;
            vec4 q = texture(Sampler0, texCoord + vec2(i, j) * px);
            mn = min(mn, q);
            mx = max(mx, q);
        }
    if (Params.y < 0.5) {
        fragColor = c;
        return;
    }
    // where this pixel's surface was on the last frame's screen (beyond the far plane: a direction, the sky's way)
    float d = texture(Sampler1, texCoord).r;
    vec4 p = InvViewProj * vec4(texCoord * 2.0 - 1.0, d, 1.0);
    vec4 prev = abs(p.w) < 1e-7 ? PrevViewProj * vec4(p.xyz, 0.0) : PrevViewProj * vec4(p.xyz / p.w + CamDelta.xyz, 1.0);
    vec2 uv = prev.xy / prev.w * 0.5 + 0.5;
    if (prev.w <= 0.0 || any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        fragColor = c;
        return;
    }
    vec4 h = clamp(texture(Sampler2, uv), mn, mx);  // what is no longer there (disocclusion, change) is let go
    fragColor = mix(h, c, Params.x);
}
