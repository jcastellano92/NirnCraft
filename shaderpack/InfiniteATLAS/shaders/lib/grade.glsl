// The film look after the tone curve (final.fsh):
//  * night vision: below moonlit brightness the eye's rods take over (Purkinje): dim light loses its colour and leans
//    blue-green, firelit and lamplit surfaces keep theirs; per pixel, from the scene's absolute luminance
//  * regional grades: one baked 32^3 LUT per climate / region (shaderpack/tools/bake_grades.py: Whiterun's golden
//    tundra, Falkreath's cold pines, the Rift's amber, Hammerfell's gold, Morrowind's ash, Cyrodiil's meadows, Black
//    Marsh's murk, ...), picked by a baked map over Tamriel (256-block texels) and blended between the four texels
//    around the camera, so walking from one land to the next eases over a few hundred blocks; weather and night ease
//    the grade towards neutral (overcast light is flat, night is the rods' look)
//  * interior grades: inside a barrow, a Dwemer ruin, a cave, a fort, a temple, a home or a Daedric place (the mod's
//    data texture: the place the server says the player is in, eased in and out over about 2 s) the place's own LUT
//    takes over from the land's
// Cost: 4 map texels and 8 LUT texels per grade in reach (one where a single grade covers the camera's cell).
#ifndef NIRNCRAFT_GRADE
#define NIRNCRAFT_GRADE

#include "/lib/grade_table.glsl"
#include "/lib/modtex.glsl"

#ifdef REGION_GRADE
uniform sampler2D gradeLut;  // customTexture: textures/grade_lut.png
uniform sampler2D gradeMap;  // customTexture: textures/grade_map.png
#endif

// rods: luminance under ~0.08 (moonlight on the ground) turns towards the scotopic look, fully by ~0.007
vec3 nightVision(vec3 c, float strength) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float m = clamp((log2(0.08) - log2(max(l, 1e-6))) / 3.6, 0.0, 1.0) * strength;
    float rod = dot(c, vec3(0.04, 0.50, 0.46));
    return mix(c, rod * vec3(0.60, 0.80, 1.20) / 0.787, m);
}

#ifdef REGION_GRADE
bool gradeReady() {
    return textureSize(gradeLut, 0) == ivec2(1024, 32 * GRADE_COUNT) && textureSize(gradeMap, 0) == GRADE_MAP_SIZE;
}

vec3 gradeTexel(int row, ivec3 p) {
    return texelFetch(gradeLut, ivec2(p.z * 32 + p.x, row + p.y), 0).rgb;
}

// grade g's LUT at sRGB c (trilinear over its 32^3 entries)
vec3 gradeLookup(int g, vec3 c) {
    vec3 p = clamp(c, 0.0, 1.0) * 31.0;
    ivec3 a = ivec3(floor(p));
    ivec3 b = min(a + 1, ivec3(31));
    vec3 f = p - vec3(a);
    int row = g * 32;
    vec3 x00 = mix(gradeTexel(row, a), gradeTexel(row, ivec3(b.x, a.y, a.z)), f.x);
    vec3 x10 = mix(gradeTexel(row, ivec3(a.x, b.y, a.z)), gradeTexel(row, ivec3(b.x, b.y, a.z)), f.x);
    vec3 x01 = mix(gradeTexel(row, ivec3(a.x, a.y, b.z)), gradeTexel(row, ivec3(b.x, a.y, b.z)), f.x);
    vec3 x11 = mix(gradeTexel(row, ivec3(a.x, b.y, b.z)), gradeTexel(row, b), f.x);
    return mix(mix(x00, x10, f.y), mix(x01, x11, f.y), f.z);
}

int gradeAt(ivec2 t) {
    if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, GRADE_MAP_SIZE))) return 0;  // beyond the map: neutral
    return clamp(int(texelFetch(gradeMap, t, 0).r * 255.0 + 0.5), 0, GRADE_COUNT - 1);
}
#endif

#ifdef REGION_GRADE
// the interior grade in force (-1: none) and how far the look has eased into it (texel 18,0 of the mod's data texture:
// LocationKind.icon + 1, strength)
int interiorGradeIndex(out float strength) {
    strength = 0.0;
    if (!haveSmoke()) return -1;
    vec4 t = texelFetch(smokeSourcesTex, ivec2(18, 0), 0);
    int kind = int(t.r * 255.0 + 0.5) - 1;
    if (kind < 0 || kind > 31) return -1;
    int g = INTERIOR_GRADE[kind];
    if (g >= 0) strength = t.g;
    return g;
}
#endif

// sRGB c graded for the camera's place (world x, z), by strength 0..1
vec3 regionalGrade(vec3 c, vec2 camXZ, float strength) {
#ifdef REGION_GRADE
    if (strength <= 0.0 || !gradeReady()) return c;
    vec2 p = (camXZ - GRADE_MAP_ORIGIN) / GRADE_MAP_CELL - 0.5;
    ivec2 i = ivec2(floor(p));
    vec2 f = smoothstep(0.0, 1.0, p - vec2(i));
    int g0 = gradeAt(i), g1 = gradeAt(i + ivec2(1, 0)), g2 = gradeAt(i + ivec2(0, 1)), g3 = gradeAt(i + ivec2(1, 1));
    float w0 = (1.0 - f.x) * (1.0 - f.y), w1 = f.x * (1.0 - f.y), w2 = (1.0 - f.x) * f.y, w3 = f.x * f.y;
    // each grade once, with the weight of every texel that has it (one lookup inside a region)
    if (g1 == g0) { w0 += w1; w1 = 0.0; }
    if (g2 == g0) { w0 += w2; w2 = 0.0; } else if (g2 == g1) { w1 += w2; w2 = 0.0; }
    if (g3 == g0) { w0 += w3; w3 = 0.0; } else if (g3 == g1) { w1 += w3; w3 = 0.0; } else if (g3 == g2) { w2 += w3; w3 = 0.0; }
    vec3 graded = vec3(0.0);
    graded += w0 > 0.0 ? w0 * (g0 == 0 ? c : gradeLookup(g0, c)) : vec3(0.0);
    graded += w1 > 0.0 ? w1 * (g1 == 0 ? c : gradeLookup(g1, c)) : vec3(0.0);
    graded += w2 > 0.0 ? w2 * (g2 == 0 ? c : gradeLookup(g2, c)) : vec3(0.0);
    graded += w3 > 0.0 ? w3 * (g3 == 0 ? c : gradeLookup(g3, c)) : vec3(0.0);
    return mix(c, graded, strength);
#else
    return c;
#endif
}

#endif
