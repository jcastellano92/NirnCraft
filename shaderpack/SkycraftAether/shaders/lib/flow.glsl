// Flowing water and falls, recovered from what the geometry carries (Iris passes no flow vector):
//  * flow direction: vanilla turns the flowing-water sprite on each top face so its +v axis runs with the flow
//    (LiquidBlockRenderer: angle = atan2(flow.z, flow.x) - pi/2), so the world direction of the sprite's +v axis,
//    from the screen derivatives of the texture coordinate and of the world position, is the flow
//  * flowing or still: still water is drawn unturned at the source height (8/9 of a block, flat); a top face below
//    that height or sloped (its real, derivative normal tilts) is flowing; the slope sets the speed
//  * falls: vertical water faces; white-water streaks scroll down them, fading to an even sheen with distance
// The flow advects the wave field (lib/waves.glsl waveNormal's flow), and steep or fast flow raises rapids foam.
#ifndef SKYCRAFT_FLOW
#define SKYCRAFT_FLOW

#include "/lib/noise.glsl"

const float FLOW_SPEED = 1.4;  // blocks per second of gently flowing water
const float FALL_SPEED = 7.0;  // white water down a fall

// (flow xz in blocks/s, slope of the face 0..1, flowing 0 / 1) of a water top face. playerPos: interpolated
// camera-relative position, uv: the atlas texture coordinate, camY: the camera's world height
vec4 waterFlow(vec3 playerPos, vec2 uv, float camY) {
    vec3 dpx = dFdx(playerPos), dpy = dFdy(playerPos);
    vec3 ng = cross(dpx, dpy);
    float lng = length(ng);
    if (lng < 1e-12) return vec4(0.0);
    ng /= lng;
    if (ng.y < 0.0) ng = -ng;
    float slope = clamp(length(ng.xz) / max(ng.y, 0.05), 0.0, 1.0);
    vec2 dux = dFdx(uv), duy = dFdy(uv);
    float det = dux.x * duy.y - duy.x * dux.y;
    if (abs(det) < 1e-14) return vec4(0.0, 0.0, slope, 0.0);
    vec3 tv = (dpy * dux.x - dpx * duy.x) / det;  // dP/dv: the world direction of the sprite's +v axis
    vec2 dir = length(tv.xz) > 1e-6 ? normalize(tv.xz) : vec2(0.0);
    // the source height is 8/9 of the block; flowing water stands lower or slopes
    float level = fract(playerPos.y + camY + 0.0005);
    float flowing = max(step(0.035, slope), step(0.025, abs(level - 0.8889)));
    float speed = FLOW_SPEED * (0.6 + 7.0 * slope) * flowing;
    return vec4(dir * speed, slope, flowing);
}

// rapids foam on a flowing top face: steep or fast water breaks white, in clots that ride the flow
float rapidsFoam(vec2 wxz, vec2 flow, float slope, float time, float dist) {
    float speed = length(flow);
    float amount = smoothstep(0.08, 0.4, slope) + 0.5 * smoothstep(3.0, 6.0, speed);
    if (amount <= 0.0) return 0.0;
    vec2 q = wxz - flow * time * 0.9;
    float n = valueNoise(q * 1.7) * 0.6 + valueNoise(q * 4.3 + 11.0) * 0.4;
    float fine = 1.0 - smoothstep(24.0, 80.0, dist);  // far: an even white, no shimmering clots
    return clamp(amount, 0.0, 1.0) * mix(0.55, smoothstep(0.42, 0.75, n), fine);
}

// a fall's white water on a vertical water face: (whiteness 0..1, normal ripple along the face)
vec2 fallsWater(vec3 wp, vec3 n, float time, float dist) {
    vec2 t = normalize(vec2(-n.z, n.x) + vec2(1e-5));
    float along = dot(wp.xz, t);
    float y = wp.y + time * FALL_SPEED;
    float streak = valueNoise(vec2(along * 2.6, y * 0.32)) * 0.6 + valueNoise(vec2(along * 6.5 + 3.0, y * 0.9)) * 0.4;
    float white = smoothstep(0.38, 0.8, streak);
    float fine = 1.0 - smoothstep(20.0, 90.0, dist);
    return vec2(mix(0.45, white, fine) * 0.85, (streak - 0.5) * 0.6 * fine);
}

// colortex6 alpha carries the sky light (low byte) and the foam (high byte) of a translucent surface
float packSkyFoam(float sky, float foam) {
    return (floor(clamp(sky, 0.0, 1.0) * 255.0 + 0.5) + floor(clamp(foam, 0.0, 1.0) * 255.0 + 0.5) * 256.0) / 65535.0;
}

vec2 unpackSkyFoam(float a) {
    float v = floor(a * 65535.0 + 0.5);
    float hi = floor(v / 256.0);
    return vec2((v - hi * 256.0) / 255.0, hi / 255.0);
}

#endif
