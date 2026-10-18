// Physically based shading bits: GGX specular with Smith visibility and Schlick Fresnel, labPBR decoding, and the
// distance widening that keeps far highlights from shimmering (a far pixel covers many normals: rougher on average).
#ifndef SKYCRAFT_PBR
#define SKYCRAFT_PBR

float roughnessFrom(float smoothness, float dist) {
    float r = (1.0 - smoothness) * (1.0 - smoothness);
    return clamp(max(r, dist * 0.0006), 0.02, 1.0);
}

vec3 fresnelSchlick(float cosT, vec3 f0) {
    return f0 + (1.0 - f0) * pow(1.0 - cosT, 5.0);
}

// radiance-to-outgoing factor for a light of unit radiance from l (n, v, l world; v towards the eye)
vec3 ggxSpecular(vec3 n, vec3 v, vec3 l, float rough, vec3 f0) {
    vec3 h = normalize(v + l);
    float nl = max(dot(n, l), 0.0), nv = max(dot(n, v), 1e-3), nh = max(dot(n, h), 0.0), vh = max(dot(v, h), 0.0);
    float a2 = rough * rough * rough * rough;
    float d = a2 / (3.14159265 * pow(nh * nh * (a2 - 1.0) + 1.0, 2.0) + 1e-6);
    float k = (rough + 1.0) * (rough + 1.0) / 8.0;
    float g = (nv / (nv * (1.0 - k) + k)) * (nl / (nl * (1.0 - k) + k));
    return fresnelSchlick(vh, f0) * d * g / (4.0 * nv) ;
}

// labPBR 1.3: specular map r = perceptual smoothness, g = F0 (0..229 dielectric / 255) or a metal id (230..255),
// a = emission (255 = none ... 0..254). Returns smoothness, F0, metal flag, emission.
vec4 decodeLabSpecular(vec4 s) {
    float f0 = s.g * 255.0 >= 229.5 ? 1.0 : s.g;
    float metal = s.g * 255.0 >= 229.5 ? 1.0 : 0.0;
    float em = s.a * 255.0 < 254.5 ? s.a : 0.0;
    return vec4(s.r, f0, metal, em);
}

// octahedral normal packing into two 0..1 values
vec2 octEncode(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 e = n.y >= 0.0 ? n.xz : (1.0 - abs(n.zx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.z >= 0.0 ? 1.0 : -1.0);
    return e * 0.5 + 0.5;
}

vec3 octDecode(vec2 e) {
    e = e * 2.0 - 1.0;
    vec3 n = vec3(e.x, 1.0 - abs(e.x) - abs(e.y), e.y);
    if (n.y < 0.0) n.xz = (1.0 - abs(n.zx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.z >= 0.0 ? 1.0 : -1.0);
    return normalize(n);
}

#endif
