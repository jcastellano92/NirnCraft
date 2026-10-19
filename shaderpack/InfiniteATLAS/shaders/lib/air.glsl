// Local air: where the open sky reaches (roofs, caves), the mod's dungeon air volumes, and its point lights. Shared by
// the media march (lib/media.glsl), lightning (lib/lightning.glsl) and the deferred lighting.
//
// Rules (InteriorAtmosphere, DungeonAir, DimensionMediaPolicy): outdoor fog, rain mist, ash and storm light only exist
// where the sky reaches: under a roof (the mod's roof cache, camera-independent, so a window still shows the fog
// outside) or below the terrain surface they are zero; caves and houses stay clear. Dungeon pieces get their own
// air: a thin dust or damp haze through the room and a low creeping layer over the floor (style 1..3), lit only by
// the lamps and lights inside.
#ifndef NIRNCRAFT_AIR
#define NIRNCRAFT_AIR

#include "/lib/modtex.glsl"
#include "/lib/weather.glsl"
#include "/lib/noise.glsl"
#include "/lib/occlusion.glsl"

// the terrain surface over a world column (64-block cells from the mod's weather dynamics), sea level without it
float surfaceHeight(vec2 worldXZ) {
    return haveWeather() ? weatherSample(weatherDyn, worldXZ).g * 1024.0 : 63.0;
}

// how open to the sky a world point is: the roof cache within ~58 blocks of the camera, the coarse terrain surface
// beyond (deep below it is underground)
float outdoorExposure(vec3 worldPos, vec3 camPos) {
    if (haveAir() && max(abs(worldPos.x - camPos.x), abs(worldPos.z - camPos.z)) <= 58.0) {
        ivec2 cell = ivec2(floor(worldPos.xz / 2.0)) & ivec2(63);
        vec4 d = texBytes(fogRadianceTex, cell);
        if (d.a >= 128.0) {
            float roof = bytes16(d.g, d.b) - 64.0;
            return smoothstep(roof + 0.05, roof + 0.75, worldPos.y);
        }
    }
    if (!haveWeather()) return 1.0;
    return 1.0 - smoothstep(4.0, 12.0, surfaceHeight(worldPos.xz) - worldPos.y);
}

// ------------------------------------------------------------------ the mod's local air, decoded once per pixel
vec3 gAirOrigin;
int gRooms = 0, gLights = 0, gSmokes = 0, gImpulses = 0;
AirRoom gRoom[8];
AirLight gLight[4];
vec4 gSmoke[4];     // campfire smoke in a room: xyz player space, w height
vec4 gImpulse[4];   // shock / shout: xyz player space, w radius
float gImpulseAge[4];
bool gAirLoaded = false;

void loadAir(vec3 camPos) {
    if (gAirLoaded) return;
    gAirLoaded = true;
    if (!airLocalMedia()) return;
    gAirOrigin = airOrigin(camPos);
    gRooms = min(airCount(66), 8);
    for (int i = 0; i < 8; i++) if (i < gRooms) gRoom[i] = airRoom(i, gAirOrigin);
    gLights = min(airCount(69), 4);
    for (int i = 0; i < 4; i++) if (i < gLights) gLight[i] = airLight(i, gAirOrigin);
    gSmokes = min(airCount(71), 4);
    for (int i = 0; i < 4; i++) if (i < gSmokes)
        gSmoke[i] = vec4(airPoint(i * 2, 70, gAirOrigin), texelFetch(fogRadianceTex, ivec2(i, 71), 0).r * 32.0);
    gImpulses = min(airCount(73), 4);
    for (int i = 0; i < 4; i++) if (i < gImpulses) {
        vec4 d = texelFetch(fogRadianceTex, ivec2(i, 73), 0);
        gImpulse[i] = vec4(airPoint(i * 2, 72, gAirOrigin), d.a > 0.5 ? d.r * 16.0 : 0.0);
        gImpulseAge[i] = d.g;
    }
}

// dungeon air at p (player space): (tint, extinction per block). Overlapping fading copies of the same room never
// double the fog (the union is capped at the densest)
vec4 roomMedium(vec3 p, vec3 camPos, float time) {
    float sum = 0.0, densest = 0.0;
    vec3 tint = vec3(0.0);
    float n = -1.0;
    for (int i = 0; i < 8; i++) {
        if (i >= gRooms) break;
        AirRoom r = gRoom[i];
        if (r.weight < 0.5 / 255.0) continue;
        vec3 m = min(p - r.lo, r.hi - p);
        float edge = min(m.x, min(m.y, m.z));
        if (edge <= 0.0) continue;
        float above = max(0.0, p.y - r.floorY);
        float dens = r.style < 0.5 ? 0.005 : r.style < 1.5 ? 0.011 : r.style < 2.5 ? 0.010 : 0.008;
        if (r.style > 0.5) dens += 0.028 * exp(-above / 2.2);  // the low creeping layer over the floor
        if (n < 0.0) {
            vec3 w = p + camPos;
            n = valueNoise(w.xz * 0.21 + vec2(w.y * 0.37, 0.0) - vec2(time * 0.03, 0.0));
        }
        dens *= smoothstep(0.0, 2.0, edge) * (0.72 + 0.28 * n);
        densest = max(densest, dens);
        float wgt = dens * r.weight;
        sum += wgt;
        vec3 rt = r.style < 0.5 ? vec3(0.82, 0.74, 0.62) : r.style < 1.5 ? vec3(0.76, 0.82, 0.82)
                : r.style < 2.5 ? vec3(0.70, 0.83, 0.94) : vec3(0.63, 0.85, 0.84);
        tint += rt * wgt;
    }
    float sigma = min(sum, densest);
    tint = sum > 1e-6 ? tint / sum : vec3(0.78, 0.80, 0.82);
    // campfire smoke rising in a room
    for (int i = 0; i < 4; i++) {
        if (i >= gSmokes) break;
        vec3 o = p - gSmoke[i].xyz;
        float h = gSmoke[i].w;
        if (o.y <= 0.0 || o.y >= h) continue;
        float rise = o.y / max(1.0, h);
        float radius = 0.35 + o.y * 0.18 + smoothstep(0.65, 0.95, rise) * 2.5;
        vec3 w = p + camPos;
        float billow = valueNoise(w.xz * 1.3 + vec2(0.0, w.y * 0.8 - time * 0.5));
        float plume = (1.0 - smoothstep(radius * 0.3, radius * (0.8 + billow * 0.4), length(o.xz)))
                    * smoothstep(0.0, 0.3, o.y) * (1.0 - smoothstep(0.9, 1.0, rise)) * 0.16 * (0.6 + 0.4 * billow);
        tint = mix(tint, vec3(0.45), plume / max(1e-4, sigma + plume));
        sigma += plume;
    }
    // a shout or a shock wave pushes the air aside for a moment
    float keep = 1.0;
    for (int i = 0; i < 4; i++) {
        if (i >= gImpulses) break;
        float age = gImpulseAge[i], radius = gImpulse[i].w * (0.25 + age);
        if (radius <= 0.0) continue;
        float d = length(p - gImpulse[i].xyz);
        keep *= 1.0 - (1.0 - smoothstep(radius * 0.45, radius, d)) * sin(age * 3.14159265) * 0.85;
    }
    return vec4(tint, sigma * keep);
}

// the mod's point lights at p (player space): on a surface (normal n, facing) or in the air (surface = false)
vec3 localLights(vec3 p, vec3 n, bool surface, vec3 camPos) {
    vec3 light = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        if (i >= gLights) break;
        AirLight l = gLight[i];
        if (l.radius < 0.1) continue;
        vec3 delta = l.pos - p;
        float d2 = dot(delta, delta), d = sqrt(d2);
        if (d >= l.radius) continue;
        float fall = 1.0 - d / l.radius;
        float facing = surface ? max(0.0, dot(n, delta / max(0.05, d))) : 1.0;
        float vis = lightVisibility(p + camPos + (surface ? n * 0.03 : vec3(0.0)), l.pos + camPos);
        light += l.energy * fall * fall / (1.0 + 0.08 * d2) * facing * vis;
    }
    return light;
}

#endif
