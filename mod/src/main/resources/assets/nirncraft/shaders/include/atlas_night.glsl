#ifndef NIRNCRAFT_ATLAS_NIGHT_GLSL
#define NIRNCRAFT_ATLAS_NIGHT_GLSL
// The sky's own lights, one model with the air (atlas_air.glsl, which must be included first): everything that shines
// above or through the air, per direction, from the camera's real position. AtlasSky fills NightInfo each frame from
// com.nirncraft.atmosphere.client.NightSky (and NightSigns once), so the sky, the water's reflections (the emitter cube,
// atlas_sky_emit.fsh) and the land's light (atlas_sky_light.fsh, read back for atlas and the near pass) see one sky.
//
//   the aurora     a volume: emission in a shell 95..330 km above the round planet, its footprint an auroral oval round
//                  a pole north of Atmora (Tamriel's map at the sky's geography, AuroraEye.w km a block). From the north
//                  it is overhead; travelling south it sinks to the northern horizon and fades through the air like any
//                  distant thing. Curtains: thin sheets along the oval from warped, drifting, folding noise; rays along
//                  the field lines; oxygen green in the body, oxygen red above ~200 km, a nitrogen blue-purple fringe at
//                  the lower border. Ray-marched only where the ray crosses the oval's band (dithered: TAA resolves it).
//   the stars      atlas_celestial.glsl (included below, its state from atmosphere.NirnCelestial): the stars, the
//                  galaxy band, the birthsigns as star figures, the named stars, the planets and a comet, seen from
//                  the observer's latitude, turning with the sun's clock (sidereal: a turn a year on the sun)
//   the moons      Masser (large, reddish) and Secunda (small, white): discs lit by the sun (their albedo maps, the
//                  phase from the real geometry: Lommel-Seeliger, the terminator where the sun puts it), occulting the
//                  stars; by day the air's light lies over them (faint)
//   meteors        streaks along great circles, head and fading train (NightInfo's slots, NightSky's simulation)
//   airglow        the 95 km oxygen layer (brighter towards the horizon: van Rhijn) and the unresolved starlight
//
// Units: the air's (radiance, the sun's irradiance SUN_IRRADIANCE ~ 11.8 = 128 klx above the air: 1 unit ~ 10.8 klx or
// kcd/m2). Every emitter is given its physical value and scaled by NightGain.x, the night's lift (the host's moon is
// compressed against the sun until pre-exposure carries the real range: NightGain.x = the moon's light over a real full
// moon's, so the emitters keep their real ratios to the moon; 1 once the moon is physical). Everything is added under
// the air's transmittance along the ray (the air lies in front of all of it), so the day sky outshines it on its own.

layout(std140) uniform NightInfo {
    mat4 NightCelestial;  // world direction -> the celestial frame (the stars', the galaxy's, the birthsigns')
    mat4 NightSerpent;    // world direction -> the Serpent's own frame
    vec4 NightGain;       // x the night's lift (the emitters' scale), y the sky's clearness (overcast hides it), z seconds,
                          // w the frame's index (dithers)
    vec4 NightStars;      // x stars, y galaxy, z birthsigns (each 0..1: the options), w twinkle
    vec4 MasserPos;       // xyz towards Masser, w its angular radius (rad)
    vec4 MasserUp;        // xyz its disc's up on the sky, w the disc's radiance at full per unit albedo
    vec4 MasserTint;      // rgb its colour (normalised to luminance 1), w drawn (0/1)
    vec4 SecundaPos;
    vec4 SecundaUp;
    vec4 SecundaTint;
    vec4 AuroraEye;       // the eye in the sky's geography: x east, z south (km), y its altitude (km); w km a block
    vec4 AuroraOval;      // x, y the pole (east, south km), z the oval's radius (km), w its band's half-width (km)
    vec4 AuroraLook;      // x strength (1: a strong display, ~100 kR green at the brightest curtain), y redness (high
                          // red, a storm's), z nitrogen fringe, w drift (km/s along the oval)
    vec4 MeteorHead[4];   // xyz the head's direction, w its irradiance (units, before NightGain)
    vec4 MeteorTail[4];   // xyz the train's end, w the head's colour (0 white .. 1 green)
    vec4 NightTarget;     // x the pre-exposure a half-float target stores (atlas_sky_emit: rgb x it, alpha it), y the
                          // target's texel angle (rad)
};

uniform sampler2D NightGalaxy;  // the galaxy band (u around the band, v across it)
uniform sampler2D NightMasser;  // Masser's phases (4 x 4: cell 0 the full disc, its albedo map)
uniform sampler2D NightSecunda;

// ---- physical constants (units per the quantity; the air's units, see above)
const float NIGHT_KLUX = 1.0 / 10830.0;               // 1 lux in units
const float STAR_E0 = 2.54e-6 * NIGHT_KLUX;           // a magnitude-0 star's irradiance
const float KR_GREEN = 1.93e-4 * NIGHT_KLUX;          // 1 kR of OI 557.7 nm: luminance (cd/m2 -> units)
const float KR_RED = 4.5e-5 * NIGHT_KLUX;             // 1 kR of OI 630.0 nm (the eye barely sees it)
const float KR_BLUE = 3.4e-6 * NIGHT_KLUX;            // 1 kR of N2+ 427.8 nm
const float AIRGLOW_ZENITH = 1.0e-4 * NIGHT_KLUX;     // the airglow and unresolved starlight at the zenith (cd/m2)
const float GALAXY_SCALE = 1.6e-3 * NIGHT_KLUX;       // the band's texture (linear luminance x alpha ~ 0.25 at its
                                                      // middle) to ~21 mag / arcsec2
const vec3 GALAXY_N = vec3(0.2795, 0.4193, 0.8635);   // SkyGeometry.GALAXY_N, normalised
// the lines' colours (linear sRGB, luminance 1): OI green, OI red, N2+ blue-violet, N2 1PG's pink lower border
const vec3 C_GREEN = vec3(0.13, 1.0, 0.36) / 0.7676;
const vec3 C_RED = vec3(1.0, 0.035, 0.05) / 0.2412;
const vec3 C_BLUE = vec3(0.32, 0.06, 1.0) / 0.1831;
const vec3 C_PINK = vec3(1.0, 0.12, 0.42) / 0.3330;

// ---- hashes and noise (no textures: the same everywhere it is evaluated)
float nHash(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}
vec3 nHash3(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}
float nNoise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(nHash(i), nHash(i + vec3(1, 0, 0)), f.x), mix(nHash(i + vec3(0, 1, 0)), nHash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(nHash(i + vec3(0, 0, 1)), nHash(i + vec3(1, 0, 1)), f.x), mix(nHash(i + vec3(0, 1, 1)), nHash(i + vec3(1, 1, 1)), f.x), f.y),
               f.z);
}
float nFbm(vec3 p) {
    return 0.5 * nNoise(p) + 0.28 * nNoise(p * 2.03 + 17.1) + 0.14 * nNoise(p * 4.07 + 31.7) + 0.08 * nNoise(p * 8.11 + 5.3);
}

// interleaved gradient noise, stepped per frame (the dithers TAA resolves)
float nightDither(vec2 pixel) {
    vec2 p = pixel + 5.588238 * mod(NightGain.w, 64.0);
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// the air's transmittance from the eye to space along dir (0 below the horizon)
vec3 nightAirT(vec3 dir) {
    return airLightTransmittance(R_GROUND + AirSun.w, dir.y);
}

// ================================================================================================= the aurora
// The emission per km along the ray at point p (relative to the planet's centre), in kR / km of each line (green,
// red, the nitrogen fringe). blur: how far across the oval one step of the march reaches (km): the sheets widen to it,
// their light kept (a far or grazing view sees them as soft bands instead of aliasing between the steps).
vec3 auroraEmission(vec3 p, float seconds, float blur) {
    float r = length(p);
    float h = r - R_GROUND;
    // where it stands on the map: the eye's position plus the arc to p's foot (the planet's curvature: the arc, not the chord)
    float horiz = length(p.xz);
    vec2 off = horiz > 1e-3 ? p.xz / horiz * (R_GROUND * atan(horiz, p.y)) : vec2(0.0);
    vec2 q = AuroraEye.xz + off - AuroraOval.xy;  // from the pole
    float D = length(q);
    // along the oval (km of arc; the angle's cut on the pole's far side, never seen), v across it (km, + south)
    float u = atan(q.x, q.y) * AuroraOval.z;
    float v = D - AuroraOval.z;
    float W = AuroraOval.w;
    float drift = seconds * AuroraLook.w;
    float slow = seconds * 0.004;
    // the band meanders (hundreds of km, domain-warped: never repeating), travelling slowly along the oval
    vec3 a = vec3(u / 800.0 + drift / 800.0, slow, 3.1);
    float meander = (nFbm(a + vec3(0.0, 0.0, nNoise(a * 1.7 + 9.0) * 1.5)) - 0.5) * 2.0 * W * 0.7;
    float vw = v + meander;
    float bw = vw / (W * 1.6);
    float band = exp(-bw * bw * bw * bw);
    if (band < 0.01) return vec3(0.0);
    // the curtains: three arcs across the band, each a thin sheet folded along the oval (folds of ~100 km drifting
    // faster than the band, kinks of tens of km), lit in stretches that surge and fade (packets)
    float fold = (nNoise(vec3(u / 110.0 - drift / 70.0, slow * 3.0, 7.7)) - 0.5) * 70.0
               + (nNoise(vec3(u / 28.0 + drift / 40.0, slow * 7.0, 9.1)) - 0.5) * 16.0;
    float curtain = 0.0;
    for (int k = 0; k < 3; k++) {
        float fk = float(k);
        float ck = (fk - 1.0) * W * 0.6 + (nNoise(vec3(u / 1100.0, slow * 0.5, 11.0 + fk)) - 0.5) * W * 0.6;
        float w = 4.0 + 3.0 * fk;  // the sheet's half-thickness (km): the poleward arcs thinner
        float we = sqrt(w * w + blur * blur);
        float d = (vw + fold * (1.0 - 0.3 * fk) - ck) / we;
        float lit = smoothstep(0.25, 0.62, nFbm(vec3(u / (520.0 + 240.0 * fk) + drift / 1600.0, slow * 0.8, 20.0 + fk)));
        curtain += lit * (1.0 - 0.25 * fk) * exp(-d * d) * (w / we);
    }
    // and the diffuse glow the arcs stand in
    curtain = band * (curtain + 0.06);
    // rays: striations along the field lines (vertical here), a few km apart, flickering
    // (along the curtain only: each sheet its own fine stripes, drifting; never stripes across the band)
    float rays = nNoise(vec3(u / 3.5 - drift / 6.0, seconds * 0.35, 2.2 + vw / 45.0));
    rays = 0.35 + 0.65 * rays * rays;
    curtain *= mix(1.0, rays, 0.6);
    // the lower border (~100 km, rippling) and the height the rays reach
    float bottom = 98.0 + 14.0 * nNoise(vec3(u / 70.0, slow * 4.0, 5.5));
    float top = bottom + 60.0 + 140.0 * rays;
    float above = h - bottom;
    float hem = smoothstep(-2.5, 4.0, above);
    float body = hem * exp(-max(above, 0.0) / 42.0) * (1.0 - smoothstep(top - 40.0, top, h) * 0.85);
    float green = curtain * body;
    // OI red: above ~200 km (the green quenched less there than... the red is long-lived: it shows where the air is
    // thinnest), faint but for a storm's high red
    float red = curtain * smoothstep(170.0, 240.0, h) * exp(-max(h - 250.0, 0.0) / 70.0) * 0.15 * (0.25 + 0.75 * AuroraLook.y);
    // the nitrogen fringe at the lower border (energetic electrons reaching deep: a pink-purple hem in a strong display)
    float fringe = curtain * hem * exp(-max(above, 0.0) / 6.0) * AuroraLook.z;
    return vec3(green, red, fringe);
}

// The aurora's radiance along dir (above the air: the caller applies the air's transmittance), physical units before
// NightGain. steps: the march's samples within the band.
vec3 nightAurora(vec3 dir, float dither, int steps) {
    if (AuroraLook.x <= 0.0) return vec3(0.0);
    float re = R_GROUND + AuroraEye.y;
    float mu = dir.y;
    // a ray into the ground sees none
    if (mu < 0.0 && re * re * (mu * mu - 1.0) + R_GROUND * R_GROUND >= 0.0) return vec3(0.0);
    const float H0 = 92.0, H1 = 330.0;
    float r0 = R_GROUND + H0, r1 = R_GROUND + H1;
    float t0 = -re * mu + sqrt(max(re * re * (mu * mu - 1.0) + r0 * r0, 0.0));
    float t1 = -re * mu + sqrt(max(re * re * (mu * mu - 1.0) + r1 * r1, 0.0));
    vec3 o = vec3(0.0, re, 0.0);
    // the part of the path over the oval's band: a coarse pass over the map, then the march there only
    float margin = AuroraOval.w * 2.2 + 90.0;
    float ta = 1e9, tb = -1e9;
    const int COARSE = 10;
    for (int i = 0; i <= COARSE; i++) {
        float t = mix(t0, t1, float(i) / float(COARSE));
        vec3 p = o + t * dir;
        float horiz = length(p.xz);
        vec2 off = horiz > 1e-3 ? p.xz / horiz * (R_GROUND * atan(horiz, p.y)) : vec2(0.0);
        float v = length(AuroraEye.xz + off - AuroraOval.xy) - AuroraOval.z;
        if (abs(v) < margin) {
            float span = (t1 - t0) / float(COARSE);
            ta = min(ta, t - span);
            tb = max(tb, t + span);
        }
    }
    if (tb < ta) return vec3(0.0);
    ta = max(ta, t0);
    tb = min(tb, t1);
    float dt = (tb - ta) / float(steps);
    float seconds = NightGain.z;
    // a step's reach across the oval (the ray's horizontal run over a step, half of it either side)
    float blur = 0.5 * dt * sqrt(max(1.0 - mu * mu, 0.0));
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 64; i++) {
        if (i >= steps) break;
        float t = ta + (float(i) + dither) * dt;
        sum += auroraEmission(o + t * dir, seconds, blur) * dt;
    }
    // kR / km x km = kR, each line in its colour; the display's strength: 5 kR / km at the brightest curtain's hem
    // (a ~200 kR column, IBC III; edge-on more) for strength 1
    float k = 5.0 * AuroraLook.x;
    // (the fringe: N2 1PG's pink and N2+'s violet at the hem, a fifth of the green's light there at most)
    return k * (sum.x * KR_GREEN * C_GREEN + sum.y * KR_RED * C_RED
                + sum.z * KR_GREEN * (0.15 * C_PINK + 0.04 * C_BLUE));
}

// ================================================================================================= the stars
// a direction's cube face and its coordinates there (-1..1)
vec3 nightCube(vec3 c) {
    vec3 a = abs(c);
    if (a.x >= a.y && a.x >= a.z) return vec3(c.x > 0.0 ? 0.0 : 1.0, c.zy / a.x);
    if (a.y >= a.z) return vec3(c.y > 0.0 ? 2.0 : 3.0, c.xz / a.y);
    return vec3(c.z > 0.0 ? 4.0 : 5.0, c.xy / a.z);
}
vec3 nightCubeDir(float face, vec2 uv) {
    if (face < 0.5) return normalize(vec3(1.0, uv.y, uv.x));
    if (face < 1.5) return normalize(vec3(-1.0, uv.y, uv.x));
    if (face < 2.5) return normalize(vec3(uv.x, 1.0, uv.y));
    if (face < 3.5) return normalize(vec3(uv.x, -1.0, uv.y));
    if (face < 4.5) return normalize(vec3(uv.x, uv.y, 1.0));
    return normalize(vec3(uv.x, uv.y, -1.0));
}

// a point source of irradiance E (per colour) at direction s, seen at c with the pixel's angular size pix: radiance
float nightPoint(vec3 c, vec3 s, float pix) {
    float sigma = max(pix * 0.75, 2e-5);
    vec3 x = cross(c, s);
    float a2 = dot(x, x);
    return exp(-0.5 * a2 / (sigma * sigma)) / (6.2831853 * sigma * sigma);
}

// ================================================================================================= the moons
// A moon's disc along dir: rgb radiance (physical, its own light: not lifted), a its coverage (it hides the stars).
vec4 nightMoon(vec3 dir, vec4 pos, vec4 up, vec4 tint, sampler2D albedo, vec3 sun, float pix) {
    if (tint.w <= 0.0) return vec4(0.0);
    float cosd = dot(dir, pos.xyz);
    if (cosd <= cos(pos.w * 1.15)) return vec4(0.0);
    vec3 U = up.xyz, R = normalize(cross(pos.xyz, U));
    // gnomonic coordinates on the disc (the old quad's projection), in radii
    vec3 local = dir / cosd;
    vec2 xy = vec2(dot(local, R), dot(local, U)) / tan(pos.w);
    float rho2 = dot(xy, xy);
    float rho = sqrt(rho2);
    float aa = max(pix / pos.w, 1e-4) * 1.2;
    float cover = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, rho);
    if (cover <= 0.0) return vec4(0.0);
    float mu = sqrt(max(1.0 - rho2, 0.0));
    vec3 n = xy.x * R + xy.y * U - mu * pos.xyz;  // the sphere's normal there, facing the eye
    float mu0 = dot(n, sun);
    // Lommel-Seeliger (the regolith: an even full disc, a sharp terminator), normalised to 1 at full
    float shade = mu0 > 0.0 ? 2.0 * mu0 / (mu0 + max(mu, 0.05)) : 0.0;
    // the full phase's cell (0) as its albedo map: the disc fills 0.916 of the cell's half
    vec2 uv = (vec2(-xy.x, -xy.y) * 0.916 * 0.5 + 0.5) * 0.25;
    vec3 alb = pow(textureLod(albedo, uv, 0.0).rgb, vec3(2.2));
    return vec4(alb * shade * up.w * tint.rgb * cover, cover);
}

// ================================================================================================= meteors
vec3 nightMeteors(vec3 dir, float pix) {
    vec3 L = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 H = MeteorHead[i], T = MeteorTail[i];
        if (H.w <= 0.0) continue;
        vec3 n = cross(T.xyz, H.xyz);
        float len = length(n);
        if (len < 1e-5) continue;
        n /= len;
        float d = dot(dir, n);
        float wdt = max(pix * 0.8, 3e-4);
        if (abs(d) > wdt * 5.0) continue;
        vec3 pr = dir - n * d;
        float arc = asin(min(len, 1.0));
        float t = atan(dot(cross(T.xyz, pr), n), dot(T.xyz, pr)) / max(arc, 1e-5);
        vec3 head = mix(vec3(1.0, 0.97, 0.88) / 0.9718, vec3(0.6, 1.0, 0.7) / 0.8925, T.w);
        // the head: a point; the train: a line of the same light fading towards its end (its light per radian)
        L += H.w * 0.6 * nightPoint(dir, H.xyz, pix) * head;
        if (t > 0.0 && t < 1.0)
            L += H.w * 0.4 / max(arc, 1e-3) * t * t * 3.0 * exp(-0.5 * d * d / (wdt * wdt)) / (2.5066 * wdt)
                 * mix(vec3(0.55, 0.70, 1.0) / 0.6658, head, t);
    }
    return L;
}

// ================================================================================================= the whole sky
const int NIGHT_AURORA = 1, NIGHT_STARS = 2, NIGHT_MOONS = 4, NIGHT_METEORS = 8, NIGHT_GLOW_LAYER = 16,
          NIGHT_STARLIGHT = 32, NIGHT_GALAXY = 64, NIGHT_ALL = 127;
// the diffuse part (what a surface's light integrates by sampling; the point stars' light is NIGHT_STARLIGHT's)
const int NIGHT_DIFFUSE = NIGHT_AURORA | NIGHT_GLOW_LAYER | NIGHT_STARLIGHT | NIGHT_GALAXY;

#include <nirncraft:atlas_celestial.glsl>

// The sky's emitters along world direction dir (unit, y up), as seen through the air: radiance to add to the air's sky.
// pix: the pixel's angular size (rad); dither 0..1; what: the NIGHT_* layers; steps: the aurora's samples.
vec3 nightSky(vec3 dir, float pix, float dither, int what, int steps) {
    vec3 T = nightAirT(dir);
    if (T.x + T.y + T.z <= 0.0) return vec3(0.0);
    vec3 sun = AirSun.xyz;
    float gain = NightGain.x;
    float clear = NightGain.y;
    vec3 L = vec3(0.0);
    vec4 moonM = vec4(0.0), moonS = vec4(0.0);
    if ((what & NIGHT_MOONS) != 0) {
        moonM = nightMoon(dir, MasserPos, MasserUp, MasserTint, NightMasser, sun, pix);
        moonS = nightMoon(dir, SecundaPos, SecundaUp, SecundaTint, NightSecunda, sun, pix);
    }
    float behind = (1.0 - moonM.a) * (1.0 - moonS.a);  // what the moons leave of the sky behind them
    vec3 far = vec3(0.0);
    // the stars, the galaxy, the planets, a comet (atlas_celestial.glsl: hidden behind the moons by nightMoonCover)
    if ((what & (NIGHT_STARS | NIGHT_GALAXY)) != 0) L += celestialSky(dir, pix, what) * gain;
    if ((what & NIGHT_STARLIGHT) != 0) far += vec3(0.85, 0.9, 1.0) * AIRGLOW_ZENITH * 0.5 * NightStars.x;
    // the airglow: a thin layer at 95 km, brighter towards the horizon (the slant path through it: van Rhijn)
    if ((what & NIGHT_GLOW_LAYER) != 0) {
        float s = (R_GROUND + AirSun.w) / (R_GROUND + 95.0);
        float vr = 1.0 / sqrt(max(1.0 - s * s * (1.0 - dir.y * dir.y), 0.03));
        L += vec3(0.62, 1.0, 0.55) / 0.8962 * AIRGLOW_ZENITH * vr * gain;
    }
    L += far * behind * gain;
    if ((what & NIGHT_AURORA) != 0) L += nightAurora(dir, dither, steps) * gain;
    if ((what & NIGHT_METEORS) != 0) L += nightMeteors(dir, pix) * gain * behind;
    L *= clear;
    // the moons: their own light (the sun's, reflected), not lifted; overcast hides them too
    L += (moonM.rgb + moonS.rgb) * clear;
    return L * T;
}

#endif
