#ifndef NIRNCRAFT_ATLAS_MOONS_GLSL
#define NIRNCRAFT_ATLAS_MOONS_GLSL
// The moons and what they hide (atlas_night.glsl includes this after its NightInfo block; atlas_air.glsl first):
//
//   Masser, Secunda  lit spheres. Each disc's pixel is a point on the sphere (body frame: x right on the sky, y the
//                    disc's up = the moon's north, z towards the eye), its longitude/latitude look up the moon's baked
//                    maps (tools/dev/moon_gen.py: albedo, and relief = the surface normal + the local height), the face
//                    at longitude 0 always towards Nirn (tidally locked). Lit by the real sun direction: the phase is
//                    geometry. Lunar-Lambert photometry (Lommel-Seeliger regolith, a little Lambert for a gentle limb
//                    darkening), the opposition surge and the phase darkening; the relief's normals light the terminator
//                    and the height map casts real shadows there (a march towards the sun over the height field, the
//                    sphere's curvature in it, the sun's penumbra); a faint Nirnshine on the night side. Mip-mapped,
//                    the level from the pixel's footprint on the sphere (no derivatives: the discs are drawn in
//                    branches).
//   eclipses         MoonInfo (com.nirncraft.atmosphere.MoonMath): the sun's hidden share; the corona round the hidden
//                    sun and a ring of light along the occulting limb; in a dark eclipse the Dark Moon, a dark disc
//                    rimmed by the corona; the Necromancer's Moon over Arkay (when the option and the planets give it).
//
// API for the sky's other bodies (stars, planets, comets): moonCover(dir, pix), how much of direction dir a moon's disc
// hides (0..1). Units as atlas_night.glsl's: the moons' light is physical (their own, not lifted).

layout(std140) uniform MoonInfo {
    vec4 MoonEclipse;  // x the sun's hidden share (0..1), y the dark eclipse (0..1), z the sun's angular radius (rad),
                       // w the ring round the occulting limb (0..1)
    vec4 DarkMoon;     // xyz towards the Dark Moon, w its angular radius (0: not shown)
    vec4 Revenant;     // xyz towards the Necromancer's Moon, w its angular radius (0: not shown)
    vec4 RevenantLook; // x its strength (0..1), yzw spare
};

uniform sampler2D NightMasser;          // Masser's albedo (equirectangular, gamma 2.2; masser_albedo.png)
uniform sampler2D NightMasserRelief;    // its relief: rgb the normal (east, north, up), a the local height
uniform sampler2D NightSecunda;
uniform sampler2D NightSecundaRelief;

// the relief maps' height range (moon radii at a = 0 and 1: h = (a * 2 - 1) * this; moon_gen.py RELIEF)
const float MASSER_RELIEF = 0.010;
const float SECUNDA_RELIEF = 0.012;
// photometry (moon_gen.py PHOTO): x the Lommel-Seeliger share (1 - x is Lambert), y the opposition surge's amplitude,
// z its width, w the phase darkening (per radian)
const vec4 MASSER_PHOTO = vec4(0.88, 0.45, 0.08, 0.35);
const vec4 SECUNDA_PHOTO = vec4(0.92, 0.75, 0.05, 0.30);
// Nirn's light on the moons' night side (a share of the full moon's, at a new moon: Nirn full from there)
const float MOON_NIRNSHINE = 0.0015;
// the sun's angular radius from the moons (the penumbra of their shadows)
const float MOON_SUN_RADIUS = 0.0087;

// The disc's pixel along dir: rgb radiance (physical), a its coverage (it hides what is behind it).
vec4 moonDisc(vec3 dir, vec4 pos, vec4 up, vec4 tint, sampler2D albedo, sampler2D relief, float reliefScale,
              vec4 photo, vec3 sun, float pix) {
    if (tint.w <= 0.0) return vec4(0.0);
    float cosd = dot(dir, pos.xyz);
    if (cosd <= cos(pos.w * 1.15)) return vec4(0.0);
    vec3 U = up.xyz, R = normalize(cross(pos.xyz, U));
    // gnomonic coordinates on the disc, in radii
    vec3 local = dir / cosd;
    vec2 xy = vec2(dot(local, R), dot(local, U)) / tan(pos.w);
    float rho = length(xy);
    float aa = max(pix / pos.w, 1e-4) * 1.2;
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
    float f = pix / pos.w;  // the pixel's footprint on the disc (radii)
    float lod = max(log2(f * max(1.0 / max(mu, 0.15), 1.0 / max(clat, 0.1)) / texel), 0.0);
    vec3 alb = pow(textureLod(albedo, uv, lod).rgb, vec3(2.2));
    vec4 rel = textureLod(relief, uv, lod);
    // the local frame (east, north, up) in the body frame; the relief's normal in it
    float sl = sin(lat), so = sin(lon), co = cos(lon);
    vec3 e = vec3(co, 0.0, -so), nn = vec3(-sl * so, clat, -sl * co);
    vec3 tn = normalize(rel.xyz * 2.0 - 1.0);
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
    // sphere's curvature, t^2 / 2), soft by the sun's own size; seen only off the sun's line (none at full) and fading
    // into the limb with the relief
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
    // Nirnshine: the planet's day side lights the moon's night side, most at a new moon (Nirn full from there)
    float fill = MOON_NIRNSHINE * pow(0.5 - 0.5 * cos(alpha), 1.5) * (L + (1.0 - L) * mud) * norm;
    return vec4(alb * (shade + fill) * up.w * tint.rgb * cover, cover);
}

// A dark disc's coverage along dir (the Dark Moon, the Revenant).
float moonDarkDisc(vec3 dir, vec4 body, float pix) {
    if (body.w <= 0.0) return 0.0;
    float d = acos(clamp(dot(dir, body.xyz), -1.0, 1.0));
    return 1.0 - smoothstep(body.w - pix, body.w + pix, d);
}

// How much of direction dir the moons hide (0..1): for the stars', planets' and comets' occultation.
float moonCover(vec3 dir, float pix) {
    float keep = 1.0;
    if (MasserTint.w > 0.0) keep *= 1.0 - moonDarkDisc(dir, vec4(MasserPos.xyz, MasserPos.w), pix);
    if (SecundaTint.w > 0.0) keep *= 1.0 - moonDarkDisc(dir, vec4(SecundaPos.xyz, SecundaPos.w), pix);
    keep *= 1.0 - moonDarkDisc(dir, DarkMoon, pix) * MoonEclipse.y;
    keep *= 1.0 - moonDarkDisc(dir, Revenant, pix) * RevenantLook.x;
    return 1.0 - keep;
}

// atlas_celestial.glsl's hook (its stars, planets and comet hidden behind the moons): this file's moonCover.
#define NIGHT_MOON_COVER
float nightMoonCover(vec3 dir, float pix) {
    return moonCover(dir, pix);
}

// The moons along dir: rgb radiance, a coverage. Secunda passes in front of Masser; the Dark Moon in front of both.
vec4 nightMoons(vec3 dir, vec3 sun, float pix) {
    vec4 m = moonDisc(dir, MasserPos, MasserUp, MasserTint, NightMasser, NightMasserRelief, MASSER_RELIEF, MASSER_PHOTO,
                      sun, pix);
    vec4 s = moonDisc(dir, SecundaPos, SecundaUp, SecundaTint, NightSecunda, NightSecundaRelief, SECUNDA_RELIEF,
                      SECUNDA_PHOTO, sun, pix);
    vec4 o = vec4(s.rgb + m.rgb * (1.0 - s.a), 1.0 - (1.0 - s.a) * (1.0 - m.a));
    float dk = max(moonDarkDisc(dir, DarkMoon, pix) * MoonEclipse.y, moonDarkDisc(dir, Revenant, pix) * RevenantLook.x);
    return vec4(o.rgb * (1.0 - dk), 1.0 - (1.0 - o.a) * (1.0 - dk));
}

// A ring of light along an occulting disc's limb (outside it): brightest towards the hidden sun, weaker the deeper the
// sun lies behind the disc.
float moonLimbRing(vec3 dir, vec4 body, vec3 sun, float pix) {
    if (body.w <= 0.0) return 0.0;
    float rs = MoonEclipse.z;
    float sunIn = body.w - acos(clamp(dot(sun, body.xyz), -1.0, 1.0));  // how deep the sun lies behind the limb
    if (sunIn < -rs) return 0.0;
    float out_ = acos(clamp(dot(dir, body.xyz), -1.0, 1.0)) - body.w;  // outside the limb (rad)
    if (out_ < -pix) return 0.0;
    vec3 a = dir - body.xyz * dot(dir, body.xyz), b = sun - body.xyz * dot(sun, body.xyz);
    float facing = dot(a, b) / max(length(a) * length(b), 1e-6);  // 1: the limb nearest the sun
    float w = 0.0015 + 0.012 * body.w;
    float depth = exp(-max(sunIn, 0.0) / (0.25 * body.w + 6.0 * rs));
    float around = 0.3 + 0.7 * pow(0.5 + 0.5 * facing, 6.0 * clamp(sunIn / body.w, 0.0, 1.0) + 1.0);
    // a bright thin rim and its soft outer glow, streaked along the limb (the corona's streamers grazing it)
    float pa = atan(dot(cross(body.xyz, a), b), dot(a, b));
    float streak = 0.75 + 0.25 * sin(pa * 9.0 + 0.7) * sin(pa * 4.0 + 2.3);
    float o = max(out_, 0.0);
    return depth * around * (0.7 * exp(-o / w) + 0.3 * streak * exp(-o / (6.0 * w))) * smoothstep(-pix, pix, out_);
}

// The eclipse's light along dir: the corona round the hidden sun (streamers by position angle, ~r^-2.5) where nothing
// hides it, and the rings along the occulting limbs. Physical radiance (the sun's own: 1 unit ~ 10.8 kcd/m2).
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
    float th = acos(clamp(dot(dir, sun), -1.0, 1.0));
    vec3 ref = abs(sun.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 ax = normalize(cross(sun, ref)), ay = cross(ax, sun);
    float pa = atan(dot(dir, ay), dot(dir, ax));
    float streak = 0.45 + 0.55 * pow(abs(cos(pa)), 2.0) + 0.2 * sin(pa * 7.0 + 1.3) * sin(pa * 3.0 + 0.4)
                 + 0.15 * sin(pa * 13.0 + 2.1);
    float x = max(th / rs, 1.0);
    float corona = 0.06 * pow(x, -2.5) * max(streak, 0.1) * smoothstep(1.0, 1.08, x);
    L += vec3(1.0, 0.97, 0.92) * corona * behind * smoothstep(0.3, 1.0, hidden);
    // the rings: the occulting moon's (Masser, Secunda) and, in a dark eclipse, the Dark Moon's (the corona's rim)
    float ring = MoonEclipse.w * (moonLimbRing(dir, MasserPos, sun, pix) + moonLimbRing(dir, SecundaPos, sun, pix));
    L += vec3(1.0, 0.93, 0.82) * 0.35 * ring;
    // the Dark Moon: its whole limb drawn by the corona's light, a cold silver rim (it hides no sun of its own)
    if (DarkMoon.w > 0.0 && MoonEclipse.y > 0.0) {
        float o = acos(clamp(dot(dir, DarkMoon.xyz), -1.0, 1.0)) - DarkMoon.w;
        float w = 0.0012 + 0.006 * DarkMoon.w;
        float rim = smoothstep(-pix, pix, o) * (0.75 * exp(-max(o, 0.0) / w) + 0.25 * exp(-max(o, 0.0) / (5.0 * w)));
        L += vec3(0.80, 0.84, 1.0) * 0.3 * MoonEclipse.y * rim;
    }
    return L;
}

#endif
