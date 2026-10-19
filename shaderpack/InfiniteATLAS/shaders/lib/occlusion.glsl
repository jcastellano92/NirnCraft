// Point-light visibility through the mod's bounded collision snapshot (LocalOcclusionTexture: 64^3 blocks around the
// camera, each a shape id with up to 8 boxes, plus 4-block macro cells marking empty space): a DDA from the receiver
// to the light, so a spell's light or an atronach's glow never shines through a wall. Only with LIGHT_OCCLUSION (the
// mod also has to run its snapshot: -Dnirncraft.localLightOcclusion=true); unknown cells count as open.
#ifndef NIRNCRAFT_OCCLUSION
#define NIRNCRAFT_OCCLUSION

#ifdef LIGHT_OCCLUSION
uniform sampler2D localOcclusionTex;

vec4 occBytes(ivec2 p) { return floor(texelFetch(localOcclusionTex, p, 0) * 255.0 + 0.5); }

float occInt32(vec4 b) { return b.r + b.g * 256.0 + b.b * 65536.0 + (b.a < 128.0 ? b.a : b.a - 256.0) * 16777216.0; }

// byte `address` of the packed array starting at `row` (4 bytes per texel, 256 texels per row)
float occByte(int address, int row) {
    vec4 b = occBytes(ivec2((address >> 2) & 255, row + (address >> 10)));
    int k = address & 3;
    return k == 0 ? b.r : k == 1 ? b.g : k == 2 ? b.b : b.a;
}

vec3 occBoxPoint(int address) {
    vec4 a = occBytes(ivec2(address & 255, 262 + (address >> 8)));
    int n = address + 1;
    vec4 b = occBytes(ivec2(n & 255, 262 + (n >> 8)));
    return (vec3(a.r + a.g * 256.0, a.b + a.a * 256.0, b.r + b.g * 256.0) - 16384.0) / 16384.0;
}

bool occSlab(vec3 o, vec3 invD, vec3 lo, vec3 hi, float len) {
    vec3 a = (lo - o) * invD, b = (hi - o) * invD;
    vec3 en = min(a, b), ex = max(a, b);
    return min(len - 0.06, min(ex.x, min(ex.y, ex.z))) > max(0.03, max(en.x, max(en.y, en.z)));
}

// 1 when the light at `source` reaches `receiver` (world positions), 0 when a collision box lies between
float lightVisibility(vec3 receiver, vec3 source) {
    if (textureSize(localOcclusionTex, 0).y != 294 || occBytes(ivec2(3, 260)).r < 0.5) return 1.0;
    vec3 lo = vec3(occInt32(occBytes(ivec2(0, 260))), occInt32(occBytes(ivec2(1, 260))), occInt32(occBytes(ivec2(2, 260))));
    vec3 m3 = min(min(receiver - lo, lo + 64.0 - receiver), min(source - lo, lo + 64.0 - source));
    float margin = min(m3.x, min(m3.y, m3.z));
    if (margin <= 0.0) return 1.0;  // outside the snapshot: not claimed
    vec3 delta = source - receiver;
    float len = length(delta);
    if (len < 0.12) return 1.0;
    vec3 d = delta / len;
    vec3 sd = vec3(abs(d.x) < 1e-7 ? 1e-7 : d.x, abs(d.y) < 1e-7 ? 1e-7 : d.y, abs(d.z) < 1e-7 ? 1e-7 : d.z);
    vec3 invD = 1.0 / sd;
    float t = 0.03;
    float vis = 1.0;
    for (int it = 0; it < 96; it++) {
        if (t >= len - 0.06) break;
        vec3 p = receiver + d * t;
        ivec3 macro = ivec3(floor(p / 4.0)) & ivec3(15);
        float scale = occByte((macro.y * 16 + macro.z) * 16 + macro.x, 256) < 0.5 ? 4.0 : 1.0;
        if (scale < 1.5) {
            ivec3 cell = ivec3(floor(p));
            ivec3 w = cell & ivec3(63);
            int id = int(occByte((w.y * 64 + w.z) * 64 + w.x, 0));
            if (id == 255) return 1.0;  // unknown geometry is not claimed as either
            if (id > 0) {
                int count = int(occBytes(ivec2(id, 261)).r);
                for (int b = 0; b < 8; b++) {
                    if (b >= count) break;
                    int address = id * 32 + b * 4;
                    if (occSlab(receiver, invD, vec3(cell) + occBoxPoint(address), vec3(cell) + occBoxPoint(address + 2), len)) {
                        vis = 0.0;
                        break;
                    }
                }
                if (vis < 0.5) break;
            }
        }
        vec3 boundary = floor(p / scale) * scale + step(vec3(0.0), d) * scale;
        vec3 next = (boundary - receiver) * invD;
        next = vec3(abs(d.x) < 1e-7 ? 1e20 : next.x, abs(d.y) < 1e-7 ? 1e20 : next.y, abs(d.z) < 1e-7 ? 1e20 : next.z);
        t = min(next.x, min(next.y, next.z)) + 1e-5;
    }
    return mix(1.0, vis, smoothstep(0.0, 4.0, margin));
}
#else
float lightVisibility(vec3 receiver, vec3 source) { return 1.0; }
#endif

#endif
