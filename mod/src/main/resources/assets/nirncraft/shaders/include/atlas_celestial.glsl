#ifndef NIRNCRAFT_ATLAS_CELESTIAL_GLSL
#define NIRNCRAFT_ATLAS_CELESTIAL_GLSL
// Nirn's sky beyond the air and the moons (com.nirncraft.atmosphere.NirnCelestial computes what it is given), included
// by atlas_night.glsl after the moons (it uses NightInfo, the hashes and nightCube there):
//
//   the stars      procedural (three cube grids of cells, a star in many: magnitudes from the count law, colours by a
//                  temperature drawn from the naked eye's mix, a little richer than Earth's), each a point at the
//                  pixel's footprint (flux kept at every resolution) with the eye's faint glare round the bright ones,
//                  scintillating with the air's seeing (stronger with the air mass, the colours twinkling apart low
//                  down); the galaxy band (textures/environment/galaxy.png)
//   the birthsigns thirteen figures of brighter stars among the others (no lines: the stars' placement is the
//                  figure), the Serpent wandering in its own frame
//   named stars    the Nine Coruscations in their colours (Xero-Lyg a black point hiding what lies behind it, a faint
//                  ring of bent light round it), Azura's Star, Darumzu, Harakk, the Northern Sisters, the Southron Pole
//                  Star, the Star of the East, the Three Stars of Locanda
//   the planets    points of light, their brightest a tiny disc with its phase (lit where the sun is)
//   a comet        the coma (a bright false nucleus in a greenish glow), the curved warm dust tail and the straight
//                  blue-violet ion tail, along axes NirnCelestial traces (CometDust / CometIon)
//
// The frame: equatorial (x the sun's place on the year's first day, z the north celestial pole), from the observer's
// latitude and the sun's clock (CelEquator: world -> equatorial). Units: the air's (atlas_night.glsl), physical,
// before NightGain. What the moons cover is hidden: nightMoonCover (the moons' own, when they define NIGHT_MOON_COVER
// before this file; else the stub below over Masser's and Secunda's discs).

layout(std140) uniform NightSigns {
    vec4 SignCentre[13];  // xyz the figure's centre (equatorial; 12 the Serpent, in its own frame), w cos(its radius)
    vec4 SignStar[182];   // 14 a figure: xyz direction, w magnitude (99: none)
    vec4 SignTint[182];   // rgb colour (luminance 1), w the star's twinkle seed
    vec4 NamedStar[20];   // xyz direction (equatorial), w magnitude; a dark star: its angular radius (rad)
    vec4 NamedTint[20];   // rgb colour, w 0 a star, 1 a dark star, -1 none
};

layout(std140) uniform CelestialInfo {
    mat4 CelEquator;      // world direction -> equatorial
    mat4 CelSerpent;      // world direction -> the Serpent's own frame
    vec4 CelObserver;     // x latitude, y longitude (rad), z the seeing (the scintillation's strength, 1 typical), w -
    vec4 PlanetPos[8];    // xyz towards it (world), w its irradiance (0: none)
    vec4 PlanetTint[8];   // rgb colour (luminance 1), w the disc's angular radius (rad)
    vec4 PlanetSun[8];    // xyz from it towards the sun (world), w the lit share of the disc's light (2/3 Lambert)
    vec4 CometHead;       // xyz towards the nucleus, w the coma's irradiance (0: no comet)
    vec4 CometLook;       // x the coma's angular radius (rad), y the dust tail's irradiance, z the ion tail's
    vec4 CometBound;      // xyz, w cos: a cap holding the whole comet
    vec4 CometDust[8];    // the dust tail's axis from the head: xyz (world), w its half-width (rad)
    vec4 CometIon[8];     // the ion tail's
};

const vec3 CEL_GALAXY_N = vec3(0.4193, 0.8635, 0.2795);  // NirnCelestial.GALAXY_N (equatorial)
const vec3 CEL_LUM = vec3(0.2126, 0.7152, 0.0722);

#ifndef NIGHT_MOON_COVER
#define NIGHT_MOON_COVER
// How much of what lies beyond dir the moons hide (0..1). A stub over Masser's and Secunda's discs (NightInfo); the
// moons' own (eclipses, the Dark Moon) replaces it by defining NIGHT_MOON_COVER and this function before this file.
float nightMoonCover(vec3 dir, float pix) {
    float open = 1.0;
    for (int i = 0; i < 2; i++) {
        vec4 p = i == 0 ? MasserPos : SecundaPos;
        if ((i == 0 ? MasserTint.w : SecundaTint.w) <= 0.0) continue;
        float d = atan(length(cross(dir, p.xyz)), dot(dir, p.xyz));
        float aa = max(pix, 1e-4);
        open *= smoothstep(p.w - aa, p.w + aa, d);
    }
    return 1.0 - open;
}
#endif

// ---- a point of light: irradiance 1 at s, seen at c through a pixel of angular size pix (radiance). A point source:
// its light lands in its pixel, its radiance there its irradiance over the pixel's solid angle (pix^2: the view's and
// each sky cube face's alike), spread only by the seeing (an energy-conserving core of ~1.5 pixels, so it does not
// alias as it moves) and the eye's own glare round a bright point (its scatter: 10 % of the light a couple of
// pixels out, 2 % further): a point past the display's white keeps its rank by its size, the brightest the largest,
// as the eye sees a bright star. The same exposure as everything else then shows a magnitude-5 star in its pixel on a
// dark night, as a dark-adapted eye sees it.
float celPoint(vec3 c, vec3 s, float pix) {
    vec3 x = cross(c, s);
    float a2 = dot(x, x);
    if (dot(c, s) < 0.0) return 0.0;
    float sg = max(pix * 0.6, 2e-5);
    float sh = max(pix * 2.2, 6e-5), sw = max(pix * 6.0, 1.6e-4);
    return (0.88 * exp(-0.5 * a2 / (sg * sg)) / (sg * sg) + 0.10 * exp(-0.5 * a2 / (sh * sh)) / (sh * sh)
            + 0.02 * exp(-0.5 * a2 / (sw * sw)) / (sw * sw)) / 6.2831853;
}

// a blackbody's colour (linear sRGB, luminance 1), a little richer than the eye's (NirnCelestial.blackbody)
vec3 celBlackbody(float t) {
    const vec3 lam = vec3(0.610, 0.550, 0.465);
    vec3 l5 = lam * lam * lam * lam * lam;
    vec3 b = 1.0 / (l5 * (exp(14388.0 / (lam * t)) - 1.0));
    vec3 w = 1.0 / (l5 * (exp(14388.0 / (lam * 6500.0)) - 1.0));
    vec3 c = b / w;
    c /= dot(c, CEL_LUM);
    c = max(vec3(1.0) + (c - 1.0) * 1.35, 0.0);
    return c / dot(c, CEL_LUM);
}

// a temperature from a uniform number: the naked eye's mix (many orange giants, some blue-white; NirnCelestial's)
float celTemperature(float u) {
    if (u < 0.12) return mix(2900.0, 3700.0, u / 0.12);
    if (u < 0.45) return mix(3700.0, 5200.0, (u - 0.12) / 0.33);
    if (u < 0.60) return mix(5200.0, 6000.0, (u - 0.45) / 0.15);
    if (u < 0.75) return mix(6000.0, 7500.0, (u - 0.60) / 0.15);
    if (u < 0.90) return mix(7500.0, 10000.0, (u - 0.75) / 0.15);
    return mix(10000.0, 25000.0, (u - 0.90) / 0.10);
}

// The air's seeing on a point (a multiplier, ~1 on average): scintillation grows with the air mass, a gentle shimmer
// overhead, a strong sparkle low down where the colours' paths part and they twinkle apart. seed: the star's own.
vec3 celTwinkle(vec3 dir, float seed) {
    float am = 1.0 / max(dir.y + 0.03, 0.04);  // ~ the air mass (1 overhead, ~ 30 at the horizon)
    float s = CelObserver.z * NightStars.w * clamp(0.09 * pow(am, 0.85), 0.0, 0.85);
    if (s <= 0.0) return vec3(1.0);
    float t = NightGain.z;
    float n = nNoise(vec3(seed * 97.0, t * 9.0, 1.7)) - 0.5;
    vec3 c = vec3(n);
    float split = smoothstep(2.5, 9.0, am);
    if (split > 0.0)
        c = mix(c, vec3(nNoise(vec3(seed * 53.0, t * 11.0, 4.1)), nNoise(vec3(seed * 37.0, t * 12.0, 8.3)),
                        nNoise(vec3(seed * 71.0, t * 13.0, 2.9))) - 0.5, split * 0.7);
    return max(vec3(1.0) + 2.4 * s * c, 0.0);
}

// ---- the procedural stars at equatorial direction c (dir: the world's, for the seeing)
vec3 celStarField(vec3 c, vec3 dir, float pix) {
    vec3 fc = nightCube(c);
    float lat = dot(c, CEL_GALAXY_N);
    float band = exp(-lat * lat / 0.03);
    vec3 L = vec3(0.0);
    for (int layer = 0; layer < 3; layer++) {
        // (cells a face side, the layer's magnitude range: the count law N(< m) ~ 10^(0.5 m) from its faintest; its
        // brightest folded back into its range, the named stars and the birthsigns own the bright end). The field ends
        // at the naked eye's limit (6.6): the fainter crowd is the galaxy's glow
        float n = layer == 0 ? 32.0 : layer == 1 ? 56.0 : 110.0;
        float mFaint = layer == 0 ? 5.0 : layer == 1 ? 6.0 : 6.6;
        float mBright = layer == 0 ? 3.2 : layer == 1 ? 4.6 : 5.4;
        float crowd = 1.0 + (0.6 + 1.1 * float(layer)) * band;  // the faint ones crowd towards the galaxy's plane
        vec2 g = (fc.yz * 0.5 + 0.5) * n;
        vec2 cell = floor(g);
        vec3 h = nHash3(vec3(cell + fc.x * 311.0, float(layer) * 97.0 + 13.0));
        float p = min(0.40 * crowd, 0.95);
        if (h.x > p) continue;
        vec2 at = (cell + 0.2 + 0.6 * h.yz) / n * 2.0 - 1.0;
        vec3 s = nightCubeDir(fc.x, at);
        float u = max(h.x / p, 1e-6);
        float m = mFaint + 2.0 * log(u) / 2.302585;
        if (m < mBright) m = mBright + fract(h.y * 41.3 + h.z * 17.9) * (mFaint - mBright) * 0.5;
        float e = STAR_E0 * exp2(-1.328771 * m);  // 10^(-0.4 m)
        float seed = fract(h.y * 7.31 + h.z * 3.7);
        vec3 col = celBlackbody(celTemperature(fract(h.z * 13.7 + h.y * 5.1)));
        L += e * celPoint(c, s, pix) * col * celTwinkle(dir, seed);
    }
    return L;
}

// ---- the galaxy band (SkyGeometry.buildGalaxy's mapping, inverted; its pole CEL_GALAXY_N)
vec3 celGalaxy(vec3 c) {
    vec3 a = normalize(cross(CEL_GALAXY_N, abs(CEL_GALAXY_N.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 b = normalize(cross(CEL_GALAXY_N, a));
    float th = atan(dot(c, b), dot(c, a));
    if (th < 0.0) th += 6.2831853;
    float sn = dot(c, CEL_GALAXY_N);
    float w = sn / max(sqrt(1.0 - sn * sn), 1e-4);
    float hw = 0.30 * (1.0 + 0.25 * sin(th * 3.0 + 1.0));
    float v = (w / hw + 1.0) * 0.5;
    if (v <= 0.0 || v >= 1.0) return vec3(0.0);
    vec4 t = textureLod(NightGalaxy, vec2(th / 6.2831853, v), 0.0);
    vec3 lin = pow(t.rgb, vec3(2.2)) * t.a;
    return lin * GALAXY_SCALE * smoothstep(0.0, 0.08, v) * smoothstep(1.0, 0.92, v);
}

// ---- the birthsigns' stars and the named stars (NightSigns)
vec3 celCatalogue(vec3 c, vec3 serp, vec3 dir, float pix) {
    vec3 L = vec3(0.0);
    if (NightStars.z > 0.0) {
        for (int f = 0; f < 13; f++) {
            vec3 q = f == 12 ? serp : c;
            if (dot(q, SignCentre[f].xyz) < SignCentre[f].w) continue;
            for (int k = 0; k < 14; k++) {
                vec4 s = SignStar[f * 14 + k];
                if (s.w > 50.0) break;
                vec4 t = SignTint[f * 14 + k];
                L += NightStars.z * STAR_E0 * exp2(-1.328771 * s.w) * celPoint(q, s.xyz, pix) * t.rgb * celTwinkle(dir, t.w);
            }
        }
    }
    for (int k = 0; k < 20; k++) {
        vec4 t = NamedTint[k];
        if (t.w < -0.5) break;
        if (t.w > 0.5) continue;
        vec4 s = NamedStar[k];
        if (dot(c, s.xyz) < 0.999) continue;
        L += STAR_E0 * exp2(-1.328771 * s.w) * celPoint(c, s.xyz, pix) * t.rgb * celTwinkle(dir, float(k) * 0.137);
    }
    return L;
}

// Xero-Lyg and its kind: how much they hide at c (0..1), and the faint ring of bent light round them (radiance)
float celDark(vec3 c, float pix, out vec3 rim) {
    rim = vec3(0.0);
    float open = 1.0;
    for (int k = 0; k < 20; k++) {
        vec4 t = NamedTint[k];
        if (t.w < -0.5) break;
        if (t.w < 0.5) continue;
        vec4 s = NamedStar[k];
        float d = atan(length(cross(c, s.xyz)), dot(c, s.xyz));
        if (d > s.w * 4.0 + pix * 3.0) continue;
        float aa = max(pix, s.w * 0.05);
        open *= smoothstep(s.w - aa, s.w + aa, d);
        // the ring: the light of what lies behind, bent round it (a thin edge, violet-white, as bright as a comet's tail)
        float x = (d - s.w * 1.35) / max(s.w * 0.12, pix);
        rim += vec3(0.85, 0.80, 1.0) / 0.8162 * 5.0e-7 * exp(-x * x);
    }
    return 1.0 - open;
}

// ---- the planets: points, or a tiny lit disc where the disc is larger than the pixel
vec3 celPlanets(vec3 dir, float pix) {
    vec3 L = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        vec4 P = PlanetPos[i];
        if (P.w <= 0.0) continue;
        float cosd = dot(dir, P.xyz);
        if (cosd < 0.9997) continue;  // (beyond ~1.4 degrees: none of it)
        vec4 T = PlanetTint[i];
        // planets twinkle less than stars (a disc, not a point: its paths through the air average)
        vec3 tw = mix(vec3(1.0), celTwinkle(dir, float(i) * 0.211 + 0.05), 0.3);
        float R = T.w;
        float resolved = smoothstep(0.45, 1.3, R / max(pix, 1e-7));
        vec3 point = vec3(celPoint(dir, P.xyz, pix));
        vec3 disc = vec3(0.0);
        if (resolved > 0.0) {
            vec3 U = normalize(abs(P.y) < 0.95 ? vec3(0.0, 1.0, 0.0) - P.xyz * P.y : vec3(1.0, 0.0, 0.0) - P.xyz * P.x);
            vec3 Rv = normalize(cross(P.xyz, U));
            vec3 local = dir / cosd;
            vec2 xy = vec2(dot(local, Rv), dot(local, U)) / tan(R);
            float rho2 = dot(xy, xy);
            float aa = max(pix / R, 1e-3);
            float cover = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, sqrt(rho2));
            if (cover > 0.0) {
                float mu = sqrt(max(1.0 - rho2, 0.0));
                vec3 n = xy.x * Rv + xy.y * U - mu * P.xyz;
                float shade = max(dot(n, PlanetSun[i].xyz), 0.0);
                float omega = 3.14159265 * R * R;
                // Lambert's sphere: the disc's light is its flux (P.w) over its solid angle and its lit share
                disc = vec3(shade * cover / (omega * max(PlanetSun[i].w, 1e-4)));
            }
        }
        L += P.w * mix(point, disc, resolved) * T.rgb * tw;
    }
    return L;
}

// ---- the comet: the closest point of a tail's axis to c: (angular distance, the share along the tail, half-width)
vec3 celTail(vec3 c, int ion) {
    float best = 1e9, along = 0.0, width = 1e-3;
    for (int k = 0; k < 7; k++) {
        vec4 A = ion == 1 ? CometIon[k] : CometDust[k];
        vec4 B = ion == 1 ? CometIon[k + 1] : CometDust[k + 1];
        vec3 ab = B.xyz - A.xyz;
        float t = clamp(dot(c - A.xyz, ab) / max(dot(ab, ab), 1e-12), 0.0, 1.0);
        vec3 q = normalize(A.xyz + ab * t);
        float d = length(cross(c, q));
        if (d < best) {
            best = d;
            along = (float(k) + t) / 7.0;
            width = mix(A.w, B.w, t);
        }
    }
    return vec3(best, along, width);
}

vec3 celComet(vec3 dir, float pix) {
    if (CometHead.w <= 0.0 || dot(dir, CometBound.xyz) < CometBound.w) return vec3(0.0);
    vec3 L = vec3(0.0);
    float E = CometHead.w;
    // the coma: a false nucleus (a point) in a glow falling off from it (exp: its area holds its light), greenish
    // (the carbon's emission) in its outer part
    float d = atan(length(cross(dir, CometHead.xyz)), dot(dir, CometHead.xyz));
    float rc = max(CometLook.x * 0.35, pix);
    float glow = exp(-d / rc) / (6.2831853 * rc * rc);
    vec3 green = mix(vec3(1.0, 0.97, 0.9) / 0.9718, vec3(0.62, 1.0, 0.72) / 0.8794, smoothstep(0.2, 1.5, d / rc));
    L += E * (0.3 * celPoint(dir, CometHead.xyz, pix) * vec3(1.0, 0.98, 0.94) + 0.7 * glow * green);
    float seconds = NightGain.z;
    // the dust tail: broad, curved, warm white (sunlight on dust), brightest near the head
    vec3 td = celTail(dir, 0);
    float lenD = max(length(CometDust[7].xyz - CometDust[0].xyz), 1e-4);
    if (td.x < td.z * 4.0) {
        float g = exp(-0.5 * td.x * td.x / (td.z * td.z)) / (2.5066283 * td.z);
        float f = 2.2 * pow(1.0 - td.y, 1.6) * smoothstep(0.0, 0.04, td.y);  // (its integral ~ 0.85)
        L += CometLook.y * g * f / lenD * vec3(1.0, 0.93, 0.80) / 0.9440;
    }
    // the ion tail: straight away from the sun, narrow, blue-violet (CO+), streaming in fine rays that drift
    vec3 ti = celTail(dir, 1);
    float lenI = max(length(CometIon[7].xyz - CometIon[0].xyz), 1e-4);
    if (ti.x < ti.z * 4.0) {
        float g = exp(-0.5 * ti.x * ti.x / (ti.z * ti.z)) / (2.5066283 * ti.z);
        float f = 1.6 * pow(1.0 - ti.y, 0.8) * smoothstep(0.0, 0.03, ti.y);
        float rays = 0.55 + 0.9 * nNoise(vec3(ti.x / ti.z * 2.5, ti.y * 5.0 - seconds * 0.01, 3.3));
        L += CometLook.z * g * f * rays / lenI * vec3(0.50, 0.48, 1.0) / 0.5211;
    }
    return L;
}

// ================================================================================================= the whole of it
// The stars, the galaxy, the planets and a comet along world direction dir: radiance above the air (physical, before
// NightGain), what the moons cover hidden. what: atlas_night.glsl's NIGHT_* layers (NIGHT_STARS: the points, the
// planets and the comet; NIGHT_GALAXY: the band).
vec3 celestialSky(vec3 dir, float pix, int what) {
    float hide = nightMoonCover(dir, pix);
    if (hide >= 1.0) return vec3(0.0);
    vec3 c = mat3(CelEquator) * dir;
    vec3 rim;
    float dark = celDark(c, pix, rim);
    vec3 L = vec3(0.0);
    bool stars = (what & NIGHT_STARS) != 0;
    if (stars) {
        if (NightStars.x > 0.0) L += NightStars.x * celStarField(c, dir, pix);
        L += celCatalogue(c, mat3(CelSerpent) * dir, dir, pix);
    }
    if ((what & NIGHT_GALAXY) != 0 && NightStars.y > 0.0) L += NightStars.y * celGalaxy(c);
    L *= 1.0 - dark;
    if (stars) L += rim + celPlanets(dir, pix) + celComet(dir, pix);
    return L * (1.0 - hide);
}

#endif
