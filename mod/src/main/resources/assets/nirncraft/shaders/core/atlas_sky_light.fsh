#version 330
#extension GL_ARB_separate_shader_objects : require
#include <nirncraft:atlas_air.glsl>
#include <nirncraft:atlas_night.glsl>
// The light the sky's diffuse emitters give the land (com.nirncraft.atlas.AtlasSky, read back for atlas's sky light and
// the near pass's: an aurora night tints the snow): their radiance over the sky through the air (atlas_night.glsl
// NIGHT_DIFFUSE: the aurora, the airglow, the galaxy, the stars' light) projected on the first two bands of spherical
// harmonics, one coefficient a texel of a 4 x 1 target: 0 the constant band (Y00 = 0.282095), 1..3 the linear band
// along world x, y, z (Y1 = 0.488603 n). A surface's irradiance is then E(n) = pi Y00 L00 + 2 pi / 3 Y1 (L1 . n).
// The moons (the key light and the fill) and the meteors are the host's own sums (NightSky: their light is known).
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int SAMPLES = 160;

void main() {
    int k = int(gl_FragCoord.x);
    vec3 sum = vec3(0.0);
    // a Fibonacci spiral over the upper hemisphere (below the horizon is the ground: no emitters), equal solid angles
    for (int i = 0; i < SAMPLES; i++) {
        float y = (float(i) + 0.5) / float(SAMPLES);
        float r = sqrt(max(1.0 - y * y, 0.0));
        float phi = float(i) * 2.3999632;
        vec3 d = vec3(r * cos(phi), y, r * sin(phi));
        vec3 L = nightSky(d, 0.05, fract(float(i) * 0.618034), NIGHT_DIFFUSE, 8);
        float basis = k == 0 ? 0.282095 : 0.488603 * (k == 1 ? d.x : k == 2 ? d.y : d.z);
        sum += L * basis;
    }
    fragColor = vec4(sum * (6.2831853 / float(SAMPLES)), 1.0);
}
