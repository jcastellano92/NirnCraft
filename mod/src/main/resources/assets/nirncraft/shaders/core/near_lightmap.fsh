#version 330
#extension GL_ARB_separate_shader_objects : require
#include <nirncraft:atlas_light.glsl>
// Infinite ATLAS: vanilla's 16 x 16 lightmap rewritten in our look (com.nirncraft.atlas.AtlasNearPass, at AfterLevel
// while we own the frame): what is still lit by vanilla's lightmap after our pipelines took the world (the first-person
// hand and held items, drawn after the level; a few special renderers) sees our light and display for its block and
// sky light, a white surface facing up and sideways (the key light at half strength, as a hand turns through it).
uniform sampler2D Sampler6;  // atlas's near light (irradiance, sun visibility) this frame, or a 1x1 stand-in
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 lm = floor(texCoord * 16.0) / 15.0;  // (block, sky) levels 0..15 -> 0..1
    float open = smoothstep(0.05, 0.35, lm.y);
    float sky = lm.y * lm.y;
    vec3 n = normalize(vec3(0.0, 1.0, 0.6));
    vec3 direct = KeyRad.rgb * 0.5 * open;
    vec3 bounce = KeyRad.rgb * (max(KeyDir.y, 0.0) * LOOK_BOUNCE * 0.5 * sky);
    vec3 ambient = mix(Ground.rgb, SkyUp.rgb, n.y * 0.5 + 0.5) * (sky * LOOK_AMBIENT) + bounce + vec3(2.0e-8, 2.0e-8, 3.0e-8);
    vec3 light = direct + ambient + atlasBlockLight(lm.x);
    // where atlas lit the frame: the light on the ground just in front of the player (the bottom of the screen, a few
    // texels averaged) for the open-sky case, so a hand in a building's shadow is in shadow too
    if (Look.z > 0.5 && Look.z < 1.5 && textureSize(Sampler6, 0).x > 1) {
        vec3 near = vec3(0.0);
        float found = 0.0;
        for (int i = 0; i < 5; i++) {
            vec4 q = texture(Sampler6, vec2(0.3 + 0.1 * float(i), 0.12));
            q.rgb /= Ground.w;  // (pre-exposed)
            if (dot(q.rgb, vec3(1.0)) > 1e-6) {
                near += q.rgb;
                found += 1.0;
            }
        }
        if (found > 0.0) light = mix(light, near / found * (lm.y > 0.0 ? 1.0 : 0.0) + atlasBlockLight(lm.x)
                + vec3(2.0e-8, 2.0e-8, 3.0e-8), smoothstep(0.5, 0.9, lm.y));
    }
    fragColor = vec4(atlasDisplay(light, Look.x, Look.y), 1.0);
}
