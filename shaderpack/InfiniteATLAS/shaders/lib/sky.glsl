// The night sky over the atmosphere's: stars on a celestial sphere that turns with the sun (so they wheel overnight),
// twinkling more in damp air, the galaxy's band, and aurora curtains in cold northern skies. Shared by the deferred
// sky and water reflections.
#ifndef NIRNCRAFT_SKY
#define NIRNCRAFT_SKY

#include "/lib/atmosphere.glsl"
#include "/lib/noise.glsl"

// the celestial frame: the sun's daily circle (sunPathRotation tilts it like Iris does)
vec3 celestial(vec3 dir, vec3 sun) {
    // rotate dir so the sun sits on a fixed meridian: stars keep their places relative to the sun's path
    vec3 ax = normalize(cross(sun, vec3(0.0, 0.0, 1.0)) + vec3(1e-4));
    vec3 ay = normalize(cross(ax, sun));
    return vec3(dot(dir, ax), dot(dir, ay), dot(dir, sun));
}

vec3 stars(vec3 dir, vec3 sun, float time, float humidity) {
#ifdef NIRNCRAFT_REALM
    return vec3(0.0);
#endif
    float night = smoothstep(0.05, -0.2, sun.y);
    if (night <= 0.0 || dir.y < -0.05) return vec3(0.0);
    vec3 c = celestial(dir, sun);
    vec3 col = vec3(0.0);
    // two cell layers: sparse bright stars and a fine dust of faint ones
    for (int layer = 0; layer < 2; layer++) {
        float scale = layer == 0 ? 90.0 : 260.0;
        vec3 p = c * scale;
        vec3 cell = floor(p);
        float h = hash13(cell);
        float thresh = layer == 0 ? 0.985 : 0.94;
        if (h > thresh) {
            vec3 sp = cell + 0.5 + 0.35 * (vec3(hash13(cell + 1.3), hash13(cell + 2.7), hash13(cell + 4.1)) - 0.5);
            float d = length(p - sp);
            float tw = 0.7 + 0.3 * sin(time * (2.0 + 6.0 * hash13(cell + 9.0)) + h * 50.0) * (0.3 + humidity);
            float b = (h - thresh) / (1.0 - thresh);
            vec3 tint = mix(vec3(0.75, 0.82, 1.0), vec3(1.0, 0.85, 0.65), hash13(cell + 5.0));
            col += tint * smoothstep(0.32, 0.0, d) * b * tw * (layer == 0 ? 2.2 : 0.6);
        }
    }
    // the galaxy: a soft band along a great circle, mottled with dust
    float band = exp(-pow(c.y * 3.2, 2.0));
    float dust = fbm2(vec2(atan(c.x, c.z) * 6.0, c.y * 12.0), 4);
    col += vec3(0.05, 0.055, 0.08) * band * smoothstep(0.35, 0.8, dust) * 2.0;
    float horizon = smoothstep(-0.05, 0.25, dir.y);
    return col * night * horizon * 0.02;
}

// north: Tamriel's north is -Z (Skyrim at the top of the map). coldNorth 0..1: how cold and northern this place is
vec3 aurora(vec3 dir, float time, float coldNorth, vec3 sun) {
#ifdef NIRNCRAFT_REALM
    return vec3(0.0);
#endif
    float night = smoothstep(0.0, -0.25, sun.y);
    if (coldNorth <= 0.0 || night <= 0.0 || dir.y < 0.02) return vec3(0.0);
    vec3 col = vec3(0.0);
    // curtains: a few layers of rippling bands projected on a dome, brighter towards the north
    for (int i = 0; i < 6; i++) {
        float hgt = 0.18 + float(i) * 0.045;
        vec2 uv = dir.xz / (dir.y + hgt);
        uv += vec2(time * 0.01, 0.0);
        float wave = sin(uv.x * 1.3 + fbm2(uv * 0.6 + time * 0.02, 3) * 4.0 + float(i));
        float curtain = smoothstep(0.92, 1.0, 1.0 - abs(wave - (uv.y * 0.35 + 0.3)) * 0.9);
        curtain *= fbm2(uv * vec2(6.0, 0.6) + time * 0.05, 2);
        vec3 tint = mix(vec3(0.15, 1.0, 0.45), vec3(0.6, 0.25, 1.0), float(i) / 6.0);
        col += tint * curtain * (1.0 - float(i) / 7.0);
    }
    float north = smoothstep(0.2, -0.6, dir.z);
    return col * night * coldNorth * north * smoothstep(0.02, 0.2, dir.y) * 0.05;
}

#endif
