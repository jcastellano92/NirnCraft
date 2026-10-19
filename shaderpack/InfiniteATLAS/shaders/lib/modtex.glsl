// The NirnCraft mod's small data textures (docs/NATIVE-26.3-RENDER-HANDOFF.md "Resource texture transport"), decoded.
// All are RGBA8 NativeImages (setPixelABGR: r is the low byte), read with texelFetch only (no filtering across packed
// values). Each is a 1-pixel / blank placeholder without the mod: every reader checks the size first and falls back
// to "nothing there".
//
//   fogRadianceTex  128x128  fog_radiance.png   x 0..63, y 0..63: block light of 4-block cells (r), 16 layers of 16x16,
//                                               cell = floorMod(floor(world / 4), 16), layer (y) at ((l & 3) * 16,
//                                               (l >> 2) * 16); g/b/a: the roof cache, 2-block columns
//                                               (floorMod(floor(world.xz / 2), 64)): roof height + 64 (16 bit), a 255
//                                               when known. x 64..127: the same cells' emitter colour * light (rgb).
//                                               rows 64..75: InteriorAtmosphere (dungeon air volumes, point lights,
//                                               campfire smoke, shock impulses), the clock, the media policy
//   smokeSourcesTex  32x4    smoke_sources.png  24 smoke sources (0: the Red Mountain volcano, 1..23 fires) and headers
//   lightningTex     32x4    lightning_sources  4 discharges: (pulse, leader progress, cloud-to-ground, strength) +
//                                               6 channel points, camera-relative metres
//   localOcclusionTex 256x294 local_occlusion   64^3 collision cells + 16^3 macro cells around the camera, shapes
//   sunHorizonTex    1x1     sun_horizon.png    sine of the terrain horizon on the sun's side / 0.5
#ifndef NIRNCRAFT_MODTEX
#define NIRNCRAFT_MODTEX

uniform sampler2D fogRadianceTex;
uniform sampler2D smokeSourcesTex;
uniform sampler2D lightningTex;
uniform sampler2D sunHorizonTex;

vec4 texBytes(sampler2D t, ivec2 p) { return floor(texelFetch(t, p, 0) * 255.0 + 0.5); }

float bytes16(float lo, float hi) { return lo + hi * 256.0; }

// a whole little-endian 32-bit integer in one texel (two's complement)
float bytesInt32(vec4 b) {
    return b.r + b.g * 256.0 + b.b * 65536.0 + (b.a < 128.0 ? b.a : b.a - 256.0) * 16777216.0;
}

// ------------------------------------------------------------------ fog_radiance: local air
bool haveAir() { return textureSize(fogRadianceTex, 0).x == 128 && textureSize(fogRadianceTex, 0).y == 128; }

// the mod's game clock in seconds (ticks + partial tick) / 20: drifts the local media with the world, not the frame
float airTime() {
    if (!haveAir()) return 0.0;
    vec4 b = texBytes(fogRadianceTex, ivec2(0, 75));
    return (b.r + b.g * 256.0 + b.b * 65536.0 + texelFetch(fogRadianceTex, ivec2(1, 75), 0).r) * 0.05;
}

// the dimension's media policy: local media (rooms, lamps) allowed here
bool airLocalMedia() { return haveAir() && texBytes(fogRadianceTex, ivec2(2, 75)).r > 0.5; }

// a realm's authored distance fog (near, far) in blocks; (0, 0) when it has none (and in the Overworld)
vec2 airRealmFogRange() {
    if (!haveAir()) return vec2(0.0);
    vec4 b = texBytes(fogRadianceTex, ivec2(3, 75));
    return vec2(bytes16(b.r, b.g), bytes16(b.b, b.a)) / 16.0;
}

// the anchor every local point is relative to, as a camera-relative (player-space) position
vec3 airOrigin(vec3 camPos) {
    return vec3(bytesInt32(texBytes(fogRadianceTex, ivec2(60, 74))), bytesInt32(texBytes(fogRadianceTex, ivec2(61, 74))),
                bytesInt32(texBytes(fogRadianceTex, ivec2(62, 74)))) - camPos;
}

// a quarter-block point (two texels at column, row), player space
vec3 airPoint(int column, int row, vec3 origin) {
    vec4 a = texBytes(fogRadianceTex, ivec2(column, row)), b = texBytes(fogRadianceTex, ivec2(column + 1, row));
    return (vec3(bytes16(a.r, a.g), bytes16(a.b, a.a), bytes16(b.r, b.g)) - 32768.0) * 0.25 + origin;
}

int airCount(int row) { return int(texBytes(fogRadianceTex, ivec2(63, row)).r); }

// the roof over a world column: how open to the sky a point is (1 outside or unknown, 0 under a roof). Leaves are
// not roofs (mist stays under a canopy). Camera-independent, so outside fog still shows through a window.
float roofExposure(vec3 worldPos, vec3 camPos) {
    if (!haveAir() || max(abs(worldPos.x - camPos.x), abs(worldPos.z - camPos.z)) > 58.0) return 1.0;
    ivec2 cell = ivec2(floor(worldPos.xz / 2.0)) & ivec2(63);
    vec4 d = texBytes(fogRadianceTex, cell);
    if (d.a < 128.0) return 1.0;  // unknown columns are not invented as roofs
    float roof = bytes16(d.g, d.b) - 64.0;
    return max(smoothstep(roof + 0.05, roof + 0.75, worldPos.y),  // a roof shelters only the room under it:
                   smoothstep(16.0, 24.0, roof - worldPos.y));  // far below something floating is open sky
}

// coloured block light in the air around worldPos (trilinear over the 4-block cells, ~26 blocks around the camera),
// as radiance: what makes a lamp's halo in fog
vec3 airLampRadiance(vec3 worldPos, vec3 camPos) {
    vec3 rel = worldPos - camPos;
    if (!haveAir() || max(abs(rel.x), max(abs(rel.y), abs(rel.z))) > 26.0) return vec3(0.0);
    // nothing lit in this cell: skip the eight colour taps (most of the air, most of the time)
    ivec3 nc = ivec3(floor(worldPos / 4.0)) & ivec3(15);
    if (texelFetch(fogRadianceTex, ivec2((nc.y & 3) * 16 + nc.x, (nc.y >> 2) * 16 + nc.z), 0).r <= 0.0) return vec3(0.0);
    vec3 c = worldPos / 4.0 - 0.5;
    vec3 b = floor(c), f = c - b;
    vec3 rad = vec3(0.0);
    for (int k = 0; k < 8; k++) {
        ivec3 o = ivec3(k & 1, (k >> 1) & 1, k >> 2);
        ivec3 cell = (ivec3(b) + o) & ivec3(15);
        ivec2 px = ivec2((cell.y & 3) * 16 + cell.x + 64, (cell.y >> 2) * 16 + cell.z);
        vec3 w = mix(1.0 - f, f, vec3(o));
        rad += texelFetch(fogRadianceTex, px, 0).rgb * (w.x * w.y * w.z);
    }
    // Minecraft's light levels fall linearly; squared keeps the hue and gives the source a bright core
    return rad * max(rad.r, max(rad.g, rad.b)) * 1.8;
}

// dungeon air volumes (DungeonAir: underground pieces; style 0 mine/dwemer dust, 1 cave/ruin, 2 ice, 3 Falmer /
// Blackreach spores), up to 8 (4 current + 4 fading predecessors), and point lights (spells, atronachs, fires)
struct AirRoom { vec3 lo; vec3 hi; float floorY; float style; float weight; };
struct AirLight { vec3 pos; vec3 energy; float radius; };

AirRoom airRoom(int i, vec3 origin) {
    vec4 d = texBytes(fogRadianceTex, ivec2(i, 66));
    return AirRoom(airPoint(i * 2, 64, origin), airPoint(i * 2, 65, origin),
                   (bytes16(d.r, d.g) - 32768.0) * 0.25 + origin.y, d.b, d.a / 255.0);
}

AirLight airLight(int i, vec3 origin) {
    vec4 d = texelFetch(fogRadianceTex, ivec2(i, 69), 0);
    return AirLight(airPoint(i * 2, 68, origin), d.rgb * 4.0, d.a * 32.0);
}

// ------------------------------------------------------------------ smoke_sources: volcano and fire plumes
struct SmokePlume { vec2 xz; float y; float height; float radius; float strength; float smoke; };

bool haveSmoke() { return textureSize(smokeSourcesTex, 0).x == 32 && textureSize(smokeSourcesTex, 0).y == 4; }

#ifdef NIRNCRAFT_REALM
int smokeCount() { return 0; }
#else
int smokeCount() { return haveSmoke() ? min(int(texBytes(smokeSourcesTex, ivec2(31, 3)).r), 24) : 0; }
#endif

SmokePlume smokePlume(int i) {
    int row = i < 8 ? 0 : 2, slot = i < 8 ? i : i - 8;
    vec4 st = texBytes(smokeSourcesTex, ivec2(slot * 2 + 1, row));
    vec4 xz = texBytes(smokeSourcesTex, ivec2(slot * 2, row));
    vec4 sh = texBytes(smokeSourcesTex, ivec2(slot * 2, row + 1));
    return SmokePlume(vec2(bytes16(xz.r, xz.g), bytes16(xz.b, xz.a)) - 32768.0, bytes16(st.r, st.g) - 64.0,
                      bytes16(sh.r, sh.g), sh.b, st.b / 100.0, st.a / 70.0);
}

// the mod's live surface wind 0..1 (smoke header)
float smokeWind() { return haveSmoke() ? texelFetch(smokeSourcesTex, ivec2(11, 1), 0).b : 0.3; }

// ------------------------------------------------------------------ lightning_sources: discharges
struct Discharge { vec4 state; vec3 p[6]; };  // state: pulse, leader progress, cloud-to-ground, strength / 1.25

int lightningCount() {
#ifdef NIRNCRAFT_REALM
    return 0;
#endif
    if (textureSize(lightningTex, 0).x != 32 || textureSize(lightningTex, 0).y != 4) return 0;
    return min(int(texBytes(lightningTex, ivec2(31, 0)).r), 4);
}

Discharge discharge(int row) {
    Discharge d;
    d.state = texelFetch(lightningTex, ivec2(0, row), 0);
    for (int k = 0; k < 6; k++) {
        vec4 a = texBytes(lightningTex, ivec2(k * 2 + 1, row));
        vec4 b = texBytes(lightningTex, ivec2(k * 2 + 2, row));
        d.p[k] = vec3(bytes16(a.r, a.g), bytes16(a.b, a.a), bytes16(b.r, b.g)) - 32768.0;  // camera-relative
    }
    return d;
}

// the nearest point of a discharge's grown channel (main bolt points 0..3, fork 4..5) to q (player space)
vec3 dischargeNearest(Discharge d, vec3 q) {
    float progress = d.state.g * 3.0;
    vec3 best = d.p[0];
    float bd = dot(best - q, best - q);
    for (int k = 0; k < 3; k++) {
        float reached = clamp(progress - float(k), 0.0, 1.0);
        if (reached <= 0.0) break;
        vec3 a = d.p[k], ab = d.p[k + 1] - a;
        vec3 s = a + ab * clamp(dot(q - a, ab) / max(dot(ab, ab), 1.0), 0.0, reached);
        float dd = dot(s - q, s - q);
        if (dd < bd) { bd = dd; best = s; }
    }
    if (progress > 1.5) {
        vec3 a = d.p[4], ab = d.p[5] - a;
        vec3 s = a + ab * clamp(dot(q - a, ab) / max(dot(ab, ab), 1.0), 0.0, clamp((progress - 1.5) / 1.5, 0.0, 1.0));
        if (dot(s - q, s - q) < bd) best = s;
    }
    return best;
}

float dischargePulse(Discharge d) { return d.state.r * mix(0.65, 1.25, d.state.a); }

// ------------------------------------------------------------------ sun_horizon
// sine of the terrain's elevation on the sun's side of the sky (east when the sun is east, else west), from the
// camera, 0 on flat land or without the mod
float sunHorizonSin() {
#ifdef NIRNCRAFT_REALM
    return 0.0;
#endif
    // gated on the mod's other textures: a missing resource may bind a white stand-in, which would read as a 30 degree
    // wall of mountains
    if (!haveAir() || textureSize(sunHorizonTex, 0).x != 1) return 0.0;
    return texelFetch(sunHorizonTex, ivec2(0), 0).r * 0.5;
}

#endif
