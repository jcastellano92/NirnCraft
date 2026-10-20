#ifndef NIRNCRAFT_ATLAS_MOONS_GLSL
#define NIRNCRAFT_ATLAS_MOONS_GLSL
// The moons and what they hide and shadow (atlas_night.glsl includes this after its NightInfo block; atlas_air.glsl
// first). Everything from the bodies' real places in space (com.nirncraft.atmosphere.MoonMath: km from the eye):
//
//   Masser, Secunda  lit spheres. Each disc's pixel is a point on the sphere (body frame: x right on the sky, y the
//                    disc's up = the moon's north, z towards the eye), its longitude/latitude look up the moon's baked
//                    maps (tools/dev/moon_gen.py: albedo, relief = the surface normal + the local height), the face at
//                    longitude 0 always towards Nirn (tidally locked); a tiling detail layer (moon_gen.py detail) fades
//                    in where a disc is magnified past its map (a spyglass, a huge low Masser). Lit by the real sun:
//                    the phase is geometry. Lunar-Lambert photometry (Lommel-Seeliger regolith, a little Lambert: a
//                    gentle limb darkening), the opposition surge and the phase darkening; the relief's normals light
//                    the terminator and the height map casts real shadows there (a march towards the sun over the
//                    height field, the sphere's curvature, the sun's penumbra).
//                    Shadows on them, from the geometry: Nirn's (a lunar eclipse: the penumbra, then the umbra lit only
//                    by sunlight bent through Nirn's air, copper-red), the other moon's (a transit shadow crossing the
//                    face). Nirnshine: sunlight off Nirn's day side on the night side, Nirn's phase from the moon (full
//                    at a new moon) and its size from there. The nearer moon passes in front of the farther.
//                    Near the horizon a disc looks larger, as the sun does (atlas_sky.fsh's rule: the eye's illusion).
//   eclipses         MoonInfo: the sun's hidden share at the eye; the corona (Magnus's light round its tear: golden-white
//                    inner, pearly helmet streamers, polar plumes, violet-blue outer rays, slowly alive), the red
//                    chromosphere and pink prominences at the sun's limb (they show only beside the moon's limb, at the
//                    contacts), a ring of light along the occulting limb; the moon's limb has relief, so the last light
//                    breaks into beads (moonCoverSun, the sun's disc in atlas_sky.fsh); in a dark eclipse the Dark Moon,
//                    a dark disc rimmed by the corona; the Necromancer's Moon over Arkay.
//
// API for the sky's other bodies (stars, planets, comets): moonCover(dir, pix), how much of direction dir the moons'
// discs hide (0..1); atlas_celestial.glsl's nightMoonCover is it. Units as atlas_night.glsl's: physical radiance.

layout(std140) uniform MoonInfo {
    vec4 MoonEclipse;  // x the sun's hidden share at the eye (0..1), y the dark eclipse (0..1), z the sun's angular
                       // radius (rad), w the ring round the occulting limb (0..1)
    vec4 DarkMoon;     // xyz towards the Dark Moon, w its angular radius (0: not shown)
    vec4 Revenant;     // xyz towards the Necromancer's Moon, w its angular radius (0: not shown)
    vec4 RevenantLook; // x its strength (0..1), yzw spare
    vec4 MoonGeom;     // x 1: Secunda the nearer (in front), y 1: the shadows on, z km a block, w spare
    vec4 MasserBody;   // xyz Masser's centre from the eye (km, world axes), w its radius (km)
    vec4 SecundaBody;  // Secunda's
    vec4 NirnBody;     // Nirn's centre from the eye, w its radius (km)
};

uniform sampler2D NightMasser;          // Masser's albedo (equirectangular, gamma 2.2; masser_albedo.png)
uniform sampler2D NightMasserRelief;    // its relief: rgb the normal (east, north, up), a the local height
uniform sampler2D NightMasserDetail;    // its tiling detail: rg the detail normal's slope, b albedo (0.5: x1), a height
uniform sampler2D NightSecunda;
uniform sampler2D NightSecundaRelief;
uniform sampler2D NightSecundaDetail;

// the relief maps' height range (moon radii at a = 0 and 1: h = (a * 2 - 1) * this; moon_gen.py RELIEF)
const float MASSER_RELIEF = 0.015;
const float SECUNDA_RELIEF = 0.012;
// the detail tiles' size (moon radii a tile; moon_gen.py DETAIL_TILE) and the second, finer octave's ratio
const float MASSER_DETAIL_TILE = 0.0245;
const float SECUNDA_DETAIL_TILE = 0.049;
const float DETAIL_OCTAVE = 3.73;
// photometry (moon_gen.py PHOTO): x the Lommel-Seeliger share (1 - x is Lambert), y the opposition surge's amplitude,
// z its width, w the phase darkening (per radian)
const vec4 MASSER_PHOTO = vec4(0.88, 0.45, 0.08, 0.35);
const vec4 SECUNDA_PHOTO = vec4(0.92, 0.75, 0.05, 0.30);
// Nirn's albedo (MoonMath.NIRN_ALBEDO): its light on the moons' night side
const float NIRN_ALBEDO = 0.30;
// the sunlight bent through Nirn's air into its umbra (MoonMath.UMBRA_GLOW), copper-red (luminance 1)
const float UMBRA_GLOW = 0.006;
const vec3 UMBRA_COLOUR = vec3(1.0, 0.30, 0.09) / 0.4442;
// the albedo maps' mean luminance (NightSkyMath.MASSER_ALBEDO / SECUNDA_ALBEDO): a full disc's mean radiance is this
// times its MasserUp.w
const float MASSER_ALB_LUM = 0.1404;
const float SECUNDA_ALB_LUM = 0.1100;
// The eye's own adaptation to a bright disc (it looks at the moon, not at the dark land it is adapted to): where a
// full disc's mean, at the eye's exposure (AirMoon.w), would pass MOON_KNEE, it is eased towards MOON_CEILING, so a
// moon stays a lit surface with its detail, a little under white, however dark the night round it. Its light on the
// land is not touched (NightSkyMath's).
const float MOON_KNEE = 0.7;
const float MOON_CEILING = 1.15;
// the sun's angular radius from the moons (the penumbra of their shadows)
const float MOON_SUN_RADIUS = 0.0087;
// the sun's rotation pole (the sun's path's axis at its default tilt: the corona's streamers and plumes)
const vec3 SUN_POLE = vec3(0.0, -0.4226, 0.9063);

// ---- small helpers
float moonHash(float n) { return fract(sin(n * 12.9898) * 43758.5453); }

// A disc's size as drawn: larger near the horizon, as the sun's (atlas_sky.fsh: up to 1.3 x below ~14 degrees).
float moonDrawRadius(vec4 pos) {
    return pos.w * (1.0 + 0.3 * (1.0 - smoothstep(0.0, 0.25, pos.y)));
}

// The share of the sun seen from point p (km from the eye) past a sphere (centre, radius km).
float moonSunPast(vec3 p, vec4 body, vec3 sun) {
    if (body.w <= 0.0) return 1.0;
    vec3 v = body.xyz - p;
    float l = length(v);
    if (l <= body.w) return 0.0;
    vec3 u = v / l;
    float c = dot(u, sun);
    if (c <= 0.0) return 1.0;
    float ang = asin(min(body.w / l, 1.0));
    float sep = atan(length(cross(u, sun)), c);
    float rs = MOON_SUN_RADIUS;
    float cover = clamp((ang + rs - sep) / (2.0 * min(ang, rs)), 0.0, 1.0) * min(1.0, ang * ang / (rs * rs));
    return 1.0 - cover * cover * (3.0 - 2.0 * cover);
}

// Triplanar detail on the unit sphere (body frame point p): rg slope, b albedo, a height, averaged over two octaves.
vec4 moonDetail(sampler2D detail, vec3 p, float tile, float footprint) {
    vec3 w = pow(abs(p), vec3(4.0));
    w /= w.x + w.y + w.z;
    vec4 o = vec4(0.0);
    float size = float(textureSize(detail, 0).x);
    for (int k = 0; k < 2; k++) {
        float t = k == 0 ? tile : tile / DETAIL_OCTAVE;
        float lod = max(log2(footprint / (t / size)), 0.0);
        vec3 q = p / t + float(k) * vec3(0.37, 0.61, 0.13);
        vec4 a = textureLod(detail, q.yz, lod) * w.x + textureLod(detail, q.zx, lod) * w.y
               + textureLod(detail, q.xy, lod) * w.z;
        o += a * (k == 0 ? 0.6 : 0.4);
    }
    return o;
}

// The disc's pixel along dir: rgb radiance (physical), a its coverage (it hides what is behind it). body: its place
// from the eye (km), other: the other moon's (its shadow).
vec4 moonDisc(vec3 dir, vec4 pos, vec4 up, vec4 tint, sampler2D albedo, sampler2D relief, sampler2D detail,
              float reliefScale, float detailTile, vec4 photo, float albLum, vec4 body, vec4 other, vec3 sun, float pix) {
    if (tint.w <= 0.0) return vec4(0.0);
    float rad = moonDrawRadius(pos);
    float cosd = dot(dir, pos.xyz);
    if (cosd <= cos(rad * 1.15)) return vec4(0.0);
    vec3 U = up.xyz, R = normalize(cross(pos.xyz, U));
    // gnomonic coordinates on the disc, in radii
    vec3 local = dir / cosd;
    vec2 xy = vec2(dot(local, R), dot(local, U)) / tan(rad);
    float rho = length(xy);
    float aa = max(pix / rad, 1e-4) * 1.2;
    float cover = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, rho);
    if (cover <= 0.0) return vec4(0.0);
    vec2 q = xy / max(rho, 1.0);  // (the anti-aliased rim: sampled at the limb)
    float mu = sqrt(max(1.0 - dot(q, q), 0.0));
    vec3 p = vec3(q, mu);
    float lat = asin(clamp(p.y, -1.0, 1.0));
    float lon = atan(p.x, max(p.z, 1e-6));
    float clat = cos(lat);
    vec2 uv = vec2(lon * 0.15915494 + 0.5, 0.5 - lat * 0.31830989);
    float texel = 6.2831853 / float(textureSize(albedo, 0).x);
    float f = pix / rad;  // the pixel's footprint on the disc (radii)
    float lodRaw = log2(f * max(1.0 / max(mu, 0.15), 1.0 / max(clat, 0.1)) / texel);
    float lod = max(lodRaw, 0.0);
    vec3 alb = pow(textureLod(albedo, uv, lod).rgb, vec3(2.2));
    vec4 rel = textureLod(relief, uv, lod);
    // the local frame (east, north, up) in the body frame; the relief's normal in it
    float sl = sin(lat), so = sin(lon), co = cos(lon);
    vec3 e = vec3(co, 0.0, -so), nn = vec3(-sl * so, clat, -sl * co);
    vec3 tn = normalize(rel.xyz * 2.0 - 1.0);
    // the detail layer, where the disc is magnified past its map (a spyglass, a huge low moon)
    float wd = smoothstep(0.0, -2.0, lodRaw) * smoothstep(0.1, 0.4, mu);
    if (wd > 0.0) {
        vec4 dt = moonDetail(detail, p, detailTile, f);
        tn = normalize(tn + vec3((dt.rg * 2.0 - 1.0) * wd, 0.0));
        alb *= mix(1.0, dt.b * 2.0, wd);
    }
    vec3 nb = tn.x * e + tn.y * nn + tn.z * p;
    nb = p + (nb - p) * smoothstep(0.0, 0.3, mu);  // (the relief fades into the limb: its facets there face away)
    nb.z += max(0.15 - nb.z, 0.0);                  // (no facet turned from the eye: what hides it is seen instead)
    nb = normalize(nb);
    vec3 s = vec3(dot(sun, R), dot(sun, U), -dot(sun, pos.xyz));  // the sun in the body frame
    float mu0g = dot(p, s);
    float mu0 = max(dot(nb, s), 0.0);
    float mud = max(nb.z, 0.5 * mu + 0.02);
    float alpha = acos(clamp(s.z, -1.0, 1.0));  // the phase angle
    float L = photo.x;
    float surge = (1.0 + photo.y / (1.0 + tan(min(alpha, 3.1) * 0.5) / photo.z)) / (1.0 + photo.y);
    float norm = 1.0 / (L + (1.0 - L) * 0.6666667);  // (the full disc's mean stays 1: its light is NightSkyMath's)
    float shade = (2.0 * L * mu0 / max(mu0 + mud, 1e-4) + (1.0 - L) * mu0) * surge * exp(-photo.w * alpha) * norm;
    shade *= smoothstep(-0.03, 0.01, mu0g);
    // cast shadows near the terminator: march the height field towards the sun (the surface falls away with the
    // sphere's curvature, t^2 / 2), soft by the sun's own size
    if (shade > 0.0 && mu0g < 0.45 && lod < 4.0) {
        vec2 sd = vec2(dot(s, e), dot(s, nn));
        float sl2 = length(sd) + 1e-6;
        sd /= sl2;
        float tanE = dot(s, p) / sl2;
        float h0 = (rel.a * 2.0 - 1.0) * reliefScale;
        float lit = 1.0;
        float t = max(texel * 1.5, f * 0.75);
        for (int k = 0; k < 12; k++) {
            vec2 uk = uv + vec2(sd.x * t / (6.2831853 * max(clat, 0.05)), -sd.y * t * 0.31830989);
            float hk = (textureLod(relief, uk, max(log2(max(t * 0.25, f) / texel), 0.0)).a * 2.0 - 1.0) * reliefScale;
            float z = h0 + t * tanE + 0.5 * t * t;
            lit = min(lit, clamp((z - hk) / (t * MOON_SUN_RADIUS * 2.0) + 0.5, 0.0, 1.0));
            t *= 1.55;
        }
        // a shadow shows only where the eye sees past what casts it: from the sun's side (a full moon, towards the
        // limb) the caster hides its own shadow; the relief fades into the limb
        vec2 vd = vec2(-so, -sl * co);
        float vl = length(vd) + 1e-6;
        float hidden = clamp(tanE * vl / max(mu, 1e-4) * max(dot(sd, vd / vl), 0.0), 0.0, 1.0);
        float seen = (1.0 - hidden) * smoothstep(0.0, 0.3, mu);
        shade *= 1.0 - (1.0 - lit) * seen;
    }
    // the shadows on it from space: Nirn's (a lunar eclipse) and the other moon's (a transit), at this point of it
    vec3 nw = p.x * R + p.y * U - p.z * pos.xyz;  // (the sphere's normal, world axes)
    vec3 P = body.xyz + body.w * nw;               // (km from the eye)
    float glow = 0.0;
    if (MoonGeom.y > 0.0) {
        float byNirn = moonSunPast(P, NirnBody, sun);
        float byOther = moonSunPast(P, other, sun);
        shade *= byNirn * byOther;
        // the umbra: the sunlight bent through Nirn's air, deepest red in its middle
        if (byNirn < 1.0 && mu0g > -0.2) {
            vec3 v = NirnBody.xyz - P;
            float sep = atan(length(cross(normalize(v), sun)), dot(normalize(v), sun));
            float umb = asin(min(NirnBody.w / length(v), 1.0));
            glow = UMBRA_GLOW * (1.0 - byNirn) * mix(0.45, 1.0, smoothstep(0.0, umb, sep)) * max(mu0g + 0.2, 0.0);
        }
    }
    // Nirnshine: Nirn's day side, its phase and size as this point of the moon sees it (full at a new moon)
    vec3 fromNirn = P - NirnBody.xyz;
    float dN = length(fromNirn);
    float beta = acos(clamp(dot(fromNirn / dN, sun), -1.0, 1.0));
    float phaseN = (sin(beta) + (3.1415927 - beta) * cos(beta)) / 3.1415927;
    float kN = NirnBody.w / dN;
    float muN = max(nb.z, 0.0);
    float fill = NIRN_ALBEDO * kN * kN * phaseN * (2.0 * L * muN / max(muN + mud, 1e-4) + (1.0 - L) * muN) * norm;
    vec3 rgb = alb * (vec3(shade + fill) + glow * UMBRA_COLOUR) * up.w * tint.rgb;
    // (the eye's adaptation to a bright disc)
    float x = up.w * albLum * AirMoon.w;
    if (x > MOON_KNEE) {
        float span = MOON_CEILING - MOON_KNEE;
        rgb *= (MOON_KNEE + span * (1.0 - exp(-(x - MOON_KNEE) / span))) / x;
    }
    return vec4(rgb * cover, cover);
}

// A dark disc's coverage along dir (the Dark Moon, the Revenant; r its radius).
float moonDarkDisc(vec3 dir, vec3 c, float r, float pix) {
    if (r <= 0.0) return 0.0;
    float d = atan(length(cross(dir, c)), dot(dir, c));
    return 1.0 - smoothstep(r - pix, r + pix, d);
}

// How much of direction dir the moons hide (0..1): for the stars', planets' and comets' occultation.
float moonCover(vec3 dir, float pix) {
    float keep = 1.0;
    if (MasserTint.w > 0.0) keep *= 1.0 - moonDarkDisc(dir, MasserPos.xyz, moonDrawRadius(MasserPos), pix);
    if (SecundaTint.w > 0.0) keep *= 1.0 - moonDarkDisc(dir, SecundaPos.xyz, moonDrawRadius(SecundaPos), pix);
    keep *= 1.0 - moonDarkDisc(dir, DarkMoon.xyz, DarkMoon.w, pix) * MoonEclipse.y;
    keep *= 1.0 - moonDarkDisc(dir, Revenant.xyz, Revenant.w, pix) * RevenantLook.x;
    return 1.0 - keep;
}

// A moon's limb against the sun, with its relief: mountains and valleys a few hundred metres to a few km high on the
// limb (a few ten-thousandths of a radian), so at the contacts the last light breaks into beads.
float moonLimbCover(vec3 dir, vec4 pos, float pix) {
    float r = moonDrawRadius(pos);
    float d = atan(length(cross(dir, pos.xyz)), dot(dir, pos.xyz));
    if (d > r + 0.002) return 0.0;
    vec3 ref = abs(pos.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 ax = normalize(cross(ref, pos.xyz)), ay = cross(pos.xyz, ax);
    vec3 v = dir - pos.xyz * dot(dir, pos.xyz);
    float phi = atan(dot(v, ay), dot(v, ax));
    float h = 0.00022 * (sin(phi * 131.0 + 1.3) + 0.7 * sin(phi * 317.0 + 0.2) + 0.5 * sin(phi * 563.0 + 2.9)
                         + 0.35 * sin(phi * 997.0 + 4.1));
    return 1.0 - smoothstep(r + h - pix, r + h + pix, d);
}

// The sun's disc behind the moons (atlas_sky.fsh): moonCover with the limbs' relief (Baily's beads, the diamond ring).
float moonCoverSun(vec3 dir, float pix) {
    float keep = 1.0;
    if (MasserTint.w > 0.0) keep *= 1.0 - moonLimbCover(dir, MasserPos, pix);
    if (SecundaTint.w > 0.0) keep *= 1.0 - moonLimbCover(dir, SecundaPos, pix);
    keep *= 1.0 - moonDarkDisc(dir, DarkMoon.xyz, DarkMoon.w, pix) * MoonEclipse.y;
    return 1.0 - keep;
}

// atlas_celestial.glsl's hook (its stars, planets and comet hidden behind the moons): this file's moonCover.
#define NIGHT_MOON_COVER
float nightMoonCover(vec3 dir, float pix) {
    return moonCover(dir, pix);
}

// The moons along dir: rgb radiance, a coverage. The nearer passes in front of the farther; the Dark Moon in front.
vec4 nightMoons(vec3 dir, vec3 sun, float pix) {
    vec4 m = moonDisc(dir, MasserPos, MasserUp, MasserTint, NightMasser, NightMasserRelief, NightMasserDetail,
                      MASSER_RELIEF, MASSER_DETAIL_TILE, MASSER_PHOTO, MASSER_ALB_LUM, MasserBody, SecundaBody, sun, pix);
    vec4 s = moonDisc(dir, SecundaPos, SecundaUp, SecundaTint, NightSecunda, NightSecundaRelief, NightSecundaDetail,
                      SECUNDA_RELIEF, SECUNDA_DETAIL_TILE, SECUNDA_PHOTO, SECUNDA_ALB_LUM, SecundaBody, MasserBody, sun,
                      pix);
    vec4 front = MoonGeom.x > 0.5 ? s : m, back = MoonGeom.x > 0.5 ? m : s;
    vec4 o = vec4(front.rgb + back.rgb * (1.0 - front.a), 1.0 - (1.0 - front.a) * (1.0 - back.a));
    float dk = max(moonDarkDisc(dir, DarkMoon.xyz, DarkMoon.w, pix) * MoonEclipse.y,
                   moonDarkDisc(dir, Revenant.xyz, Revenant.w, pix) * RevenantLook.x);
    return vec4(o.rgb * (1.0 - dk), 1.0 - (1.0 - o.a) * (1.0 - dk));
}

// A ring of light along an occulting disc's limb (outside it): brightest towards the hidden sun, weaker the deeper the
// sun lies behind the disc.
float moonLimbRing(vec3 dir, vec4 pos, vec3 sun, float pix) {
    float r = moonDrawRadius(pos);
    if (r <= 0.0) return 0.0;
    float rs = MoonEclipse.z;
    float sunIn = r - atan(length(cross(sun, pos.xyz)), dot(sun, pos.xyz));  // how deep the sun lies behind the limb
    if (sunIn < -rs) return 0.0;
    float out_ = atan(length(cross(dir, pos.xyz)), dot(dir, pos.xyz)) - r;  // outside the limb (rad)
    if (out_ < -pix) return 0.0;
    vec3 a = dir - pos.xyz * dot(dir, pos.xyz), b = sun - pos.xyz * dot(sun, pos.xyz);
    float facing = dot(a, b) / max(length(a) * length(b), 1e-6);  // 1: the limb nearest the sun
    float w = 0.0012 + 0.008 * r;
    float depth = exp(-max(sunIn, 0.0) / (0.25 * r + 6.0 * rs));
    float around = 0.3 + 0.7 * pow(0.5 + 0.5 * facing, 6.0 * clamp(sunIn / r, 0.0, 1.0) + 1.0);
    float pa = atan(dot(cross(pos.xyz, a), b), dot(a, b));
    float streak = 0.75 + 0.25 * sin(pa * 9.0 + 0.7) * sin(pa * 4.0 + 2.3);
    float o = max(out_, 0.0);
    return depth * around * (0.7 * exp(-o / w) + 0.3 * streak * exp(-o / (6.0 * w))) * smoothstep(-pix, pix, out_);
}

// The corona's structure at position angle pa (0 the sun's equator, +-pi/2 its poles), x solar radii out, t seconds:
// rgb, relative (the radiance is the sun's x MoonEclipse's scale).
vec3 moonCorona(float pa, float x, float t) {
    float lat = sin(pa);
    // helmet streamers: broad petals over the sun's equatorial belt, narrowing outward, drifting over the minutes
    float helmet = 0.0;
    for (int k = 0; k < 6; k++) {
        float c = moonHash(float(k) + 3.0) * 6.2831853 + 0.00015 * t * (moonHash(float(k) + 9.0) - 0.5);
        float belt = 1.0 - smoothstep(0.35, 0.9, abs(sin(c)));
        float d = atan(sin(pa - c), cos(pa - c));
        float wdt = (0.10 + 0.12 * moonHash(float(k) + 5.0)) * (0.35 + 0.65 / sqrt(x));
        helmet += belt * exp(-d * d / (wdt * wdt)) * (0.6 + 0.4 * moonHash(float(k) + 7.0));
    }
    // polar plumes: fine straight rays over the poles
    float plume = pow(abs(lat), 5.0) * (0.55 + 0.45 * sin(pa * 71.0 + 0.4 * sin(t * 0.002)))
                * (0.6 + 0.4 * sin(pa * 23.0 + 1.7));
    // fine streamers everywhere, shimmering slowly (the tear's light alive)
    float fine = 0.65 + 0.2 * sin(pa * 37.0 + t * 0.0011) * sin(pa * 13.0 - t * 0.0007)
               + 0.15 * sin(pa * 97.0 + 0.5 * sin(t * 0.003 + pa * 3.0));
    float K = pow(x, -3.0), F = pow(x, -2.1), O = pow(x, -1.35);
    vec3 inner = vec3(1.0, 0.90, 0.70) * K * fine;
    vec3 streamers = vec3(0.96, 0.95, 1.0) * F * (0.2 + 1.4 * helmet + 0.9 * plume * exp(-(x - 1.0) * 0.25)) * fine;
    vec3 outer = vec3(0.60, 0.52, 1.0) * O * (0.05 + 0.5 * helmet) * fine;
    return inner + streamers + outer;
}

// The eclipse's light along dir: the corona round the hidden sun where nothing hides it, the chromosphere and the
// prominences at the sun's limb, the rings along the occulting limbs. Physical radiance (the sun's: 1 unit ~ 10.8
// kcd/m2); behind: what the moons leave of the sky there.
vec3 nightEclipse(vec3 dir, vec3 sun, float pix, float behind) {
    vec3 L = vec3(0.0);
    // the Revenant's faint purple-red cast round it while it hides Arkay
    if (Revenant.w > 0.0 && RevenantLook.x > 0.0) {
        float d = acos(clamp(dot(dir, Revenant.xyz), -1.0, 1.0)) / Revenant.w;
        L += vec3(0.55, 0.12, 0.30) * 4e-9 * RevenantLook.x * exp(-max(d - 1.0, 0.0) * 2.0) * step(1.0, d);
    }
    float hidden = MoonEclipse.x;
    if (hidden <= 0.01) return L;
    float rs = MoonEclipse.z;
    vec3 Lsun = SUN_IRRADIANCE / (3.1415927 * rs * rs);  // the photosphere's radiance
    float th = atan(length(cross(dir, sun)), dot(dir, sun));
    float x = max(th / rs, 1.0);
    float t = NightGain.z;
    vec3 ax = normalize(cross(SUN_POLE, sun)), ay = cross(sun, ax);
    vec3 q = dir - sun * dot(dir, sun);
    float pa = atan(dot(q, ay), dot(q, ax));
    float on = smoothstep(0.3, 1.0, hidden);
    // the corona: brighter, larger and more colourful than an ordinary star's (Magnus's light round its tear)
    if (th < 0.7) L += Lsun * 6e-6 * moonCorona(pa, x, t) * behind * on * smoothstep(1.0, 1.02, th / rs);
    // the chromosphere (a thin red rim) and the prominences (pink flames and loops), right at the sun's limb: only
    // beside the moon's limb do they show (behind), at the contacts
    if (x < 1.4) {
        float chrom = exp(-max(th / rs - 1.0, 0.0) / 0.012) * step(1.0, th / rs);
        float prom = 0.0;
        for (int k = 0; k < 7; k++) {
            float c = moonHash(float(k) * 1.7 + floor(t / 7200.0)) * 6.2831853;
            float d = atan(sin(pa - c), cos(pa - c));
            float hgt = 0.06 + 0.18 * moonHash(float(k) + 11.0);
            float loop = exp(-pow((th / rs - 1.0 - hgt * 0.5) / (hgt * 0.5), 2.0))
                       * exp(-pow(d / (0.03 + 0.05 * moonHash(float(k) + 13.0)), 2.0));
            prom += loop * (0.6 + 0.4 * sin(th / rs * 90.0 + float(k)));
        }
        L += Lsun * (vec3(1.0, 0.18, 0.30) * 5e-5 * chrom + vec3(1.0, 0.38, 0.62) * 3e-5 * prom) * behind * on;
    }
    // the rings: along the occulting moons' limbs (Masser's thin and golden, Secunda's pearly) and, in a dark eclipse,
    // the Dark Moon's whole limb drawn by the corona
    float ringM = MoonEclipse.w * moonLimbRing(dir, MasserPos, sun, pix);
    float ringS = MoonEclipse.w * moonLimbRing(dir, SecundaPos, sun, pix);
    L += (vec3(1.0, 0.90, 0.72) * 0.35 * ringM + vec3(0.92, 0.94, 1.0) * 0.22 * ringS);
    if (DarkMoon.w > 0.0 && MoonEclipse.y > 0.0) {
        float o = atan(length(cross(dir, DarkMoon.xyz)), dot(dir, DarkMoon.xyz)) - DarkMoon.w;
        float w = 0.0012 + 0.006 * DarkMoon.w;
        float rim = smoothstep(-pix, pix, o) * (0.75 * exp(-max(o, 0.0) / w) + 0.25 * exp(-max(o, 0.0) / (5.0 * w)));
        L += vec3(0.80, 0.84, 1.0) * 0.3 * MoonEclipse.y * rim;
    }
    return L;
}

#endif
