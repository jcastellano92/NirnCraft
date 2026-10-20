// The air (sky, the sun's colour and strength, aerial perspective, the sky's light): a port of native/src/air.hpp, the
// reference, function for function with the same constants (the GLSL sky, atlas_sky.glsl, is the third copy). The
// host fills the frame's air (FrameGpu air*: per-km coefficients, the lights above the air, the eye's altitude, the
// sky light it integrated) and puts the multiple-scattering LUT after the weather fields (MS_BASE).
// Requires (declared by the including kernel): frame, weather.

static const float AIR_PI = 3.14159265;
static const float R_GROUND = 6360.0;
static const float R_TOP = 6460.0;
static const float H_RAYLEIGH = 8.0;
static const float OZONE_PEAK = 25.0;
static const float OZONE_HALF = 15.0;
static const float SUN_HALF_ANGLE = 0.00465;
static const float3 SUN_IRRADIANCE = float3(11.181, 11.807, 12.854);
static const float3 NIGHT_GLOW = float3(0.0, 0.0, 0.0);  // (air.hpp: the night's glow is the sky's emitters')
static const float SEA_LEVEL_Y = 63.0;
static const int MS_SIZE = 32;
static const int SKY_STEPS = 24;
static const int AERIAL_STEPS = 8;
static const uint MS_BASE = 4u * 16384u;  // the weather buffer: four 128^2 fields, then the LUT (rgb float bits, pad)

// the key light at the point being shaded (airAt): the sun's transmitted light, or the moon's at night
static float3 gKeyRad = float3(0, 0, 0);

float airErfcx(float y) { return 1.1283792 / (y + sqrt(y * y + 0.393 * y + 1.2732395)); }

float airChapman(float X, float h, float mu) {
    float x = X + h;
    float up = sqrt(1.5707963 * x) * airErfcx(sqrt(0.5 * x) * abs(mu)) * exp(-h);
    if (mu >= 0.0) return up;
    float x0 = sqrt(max(1.0 - mu * mu, 0.0)) * x;
    return 2.0 * sqrt(1.5707963 * x0) * exp(X - x0) - up;
}

float airOzoneAbove(float h) {
    float u = saturate((h - (OZONE_PEAK - OZONE_HALF)) / (2.0 * OZONE_HALF));
    return u < 0.5 ? 1.0 - 2.0 * u * u : 2.0 * (1.0 - u) * (1.0 - u);
}

float airHorizonMu(float r) { return -sqrt(max(1.0 - (R_GROUND / r) * (R_GROUND / r), 0.0)); }

float3 airDepthToSpace(float r, float mu) {
    float h = max(r - R_GROUND, 0.0);
    mu = max(mu, airHorizonMu(r));
    float invHM = frame.airRayleigh.w;
    float dR = H_RAYLEIGH * airChapman(R_GROUND / H_RAYLEIGH, h / H_RAYLEIGH, mu);
    float dM = airChapman(R_GROUND * invHM, h * invHM, mu) / invHM;
    float s = min(r / (R_GROUND + OZONE_PEAK), 1.0);
    float slant = 1.0 / sqrt(max(1.0 - s * s * (1.0 - mu * mu), 1e-4));
    float dO = 2.0 * OZONE_HALF * 0.5 * airOzoneAbove(h) * slant;
    return frame.airRayleigh.xyz * dR + frame.airMieExt.xyz * dM + frame.airOzone.xyz * dO;
}

float3 airLightTransmittance(float r, float mu) {
    float vis = smoothstep(-SUN_HALF_ANGLE, SUN_HALF_ANGLE, mu - airHorizonMu(r));
    if (vis <= 0.0) return float3(0, 0, 0);
    return exp(-airDepthToSpace(r, mu)) * vis;
}

float airPhaseRayleigh(float nu) { return 3.0 / (16.0 * AIR_PI) * (1.0 + nu * nu); }
float airPhaseMie(float nu, float g) {
    float g2 = g * g;
    return 3.0 / (8.0 * AIR_PI) * (1.0 - g2) * (1.0 + nu * nu) / ((2.0 + g2) * pow(max(1.0 + g2 - 2.0 * g * nu, 1e-4), 1.5));
}

float airDistanceToTop(float r, float mu) {
    float d = r * r * (mu * mu - 1.0) + R_TOP * R_TOP;
    return max(-r * mu + sqrt(max(d, 0.0)), 0.0);
}
float airDistanceToGround(float r, float mu) {
    float d = r * r * (mu * mu - 1.0) + R_GROUND * R_GROUND;
    if (mu >= 0.0 || d < 0.0) return -1.0;
    return max(-r * mu - sqrt(d), 0.0);
}

float3 airMsTexel(int x, int y) {
    uint b = MS_BASE + uint(y * MS_SIZE + x) * 4u;
    return float3(asfloat(weather[b]), asfloat(weather[b + 1u]), asfloat(weather[b + 2u]));
}
float3 airMs(float h, float muS) {
    float u = saturate(muS * 0.5 + 0.5) * float(MS_SIZE - 1);
    float v = sqrt(saturate(h / (R_TOP - R_GROUND))) * float(MS_SIZE - 1);
    int x0 = min(int(u), MS_SIZE - 2), y0 = min(int(v), MS_SIZE - 2);
    float fx = u - float(x0), fy = v - float(y0);
    float3 a = lerp(airMsTexel(x0, y0), airMsTexel(x0 + 1, y0), fx);
    float3 b = lerp(airMsTexel(x0, y0 + 1), airMsTexel(x0 + 1, y0 + 1), fx);
    return lerp(a, b, fy);
}

struct AirPath {
    float3 inscatter;
    float3 transmittance;
};

// An eclipse (atlas_set_eclipse): the share of the sun's disc seen past the occulting moons from a point p km from the
// camera (world axes; api.cpp eclipseShare, the same formula). 1 without one.
float airSunShareKm(float3 p) {
    if (frame.eclipseC.y < 0.5) return 1.0;
    float3 sun = frame.sunDir.xyz;
    const float rs = 0.0089;  // the sun's angular radius (the host's sky disc)
    float vis = 1.0;
    for (int k = 0; k < 2; k++) {
        float4 o = k == 0 ? frame.eclipseA : frame.eclipseB;
        if (o.w <= 0.0) continue;
        float3 v = o.xyz - p;
        float l = length(v);
        if (l <= o.w) return 0.0;
        float3 u = v / l;
        float c = dot(u, sun);
        if (c <= 0.0) continue;
        float ang = asin(min(o.w / l, 1.0));
        float sep = atan2(length(cross(u, sun)), c);
        float cover = saturate((ang + rs - sep) / (2.0 * min(ang, rs))) * min(1.0, ang * ang / (rs * rs));
        vis *= 1.0 - cover * cover * (3.0 - 2.0 * cover);
    }
    return vis;
}

// that share at a point pRel blocks from the camera
float airSunShareAt(float3 pRel) { return airSunShareKm(pRel * frame.eclipseC.x); }

AirPath airIntegrate(float r, float mu, float nuS, float muS, float3 ES, float nuM, float muM, float3 EM, float tEnd,
                     int steps, bool quadratic, bool ground, float3 dirW) {
    // (an eclipse: the sun's share at each point of the path, t km of air = t / airMieExt.w blocks along dirW)
    float kmGeo = frame.eclipseC.x / max(frame.airMieExt.w, 1e-6);
    bool moon = EM.x + EM.y + EM.z > 0.0;
    float g = frame.airMieScat.w;
    float pRS = airPhaseRayleigh(nuS), pMS = airPhaseMie(nuS, g), pRM = airPhaseRayleigh(nuM), pMM = airPhaseMie(nuM, g);
    float invHM = frame.airRayleigh.w;
    float3 T = float3(1, 1, 1), L = float3(0, 0, 0);
    float prev = 0.0;
    for (int s = 0; s < steps; s++) {
        float a = float(s + 1) / float(steps), m = (float(s) + 0.5) / float(steps);
        float t1 = quadratic ? tEnd * a * a : tEnd * a;
        float t = quadratic ? tEnd * m * m : tEnd * m;
        float dt = t1 - prev;
        prev = t1;
        float rt = sqrt(max(r * r + 2.0 * r * mu * t + t * t, 1.0));
        float ht = max(rt - R_GROUND, 0.0);
        float dR = exp(-ht / H_RAYLEIGH), dM = exp(-ht * invHM);
        float dO = max(0.0, 1.0 - abs(ht - OZONE_PEAK) / OZONE_HALF);
        float3 sR = frame.airRayleigh.xyz * dR, sM = frame.airMieScat.xyz * dM;
        float3 ext = sR + frame.airMieExt.xyz * dM + frame.airOzone.xyz * dO;
        float3 stepT = exp(-ext * dt);
        float3 w = (1.0 - stepT) / max(ext, 1e-7);
        float muSt = (r * muS + t * nuS) / rt;
        float3 S = ((sR * pRS + sM * pMS) * airLightTransmittance(rt, muSt) + (sR + sM) * airMs(ht, muSt)) * ES
                   * airSunShareKm(dirW * (t * kmGeo));
        if (moon) {
            float muMt = (r * muM + t * nuM) / rt;
            S += ((sR * pRM + sM * pMM) * airLightTransmittance(rt, muMt) + (sR + sM) * airMs(ht, muMt)) * EM;
        }
        L += T * S * w;
        T *= stepT;
    }
    if (ground) {
        float rg = sqrt(max(r * r + 2.0 * r * mu * tEnd + tEnd * tEnd, 1.0));
        float muG = (r * muS + tEnd * nuS) / rg;
        float3 lit = airLightTransmittance(rg, muG) * ES * max(muG, 0.0) * airSunShareKm(dirW * (tEnd * kmGeo));
        if (moon) {
            float muGM = (r * muM + tEnd * nuM) / rg;
            lit += airLightTransmittance(rg, muGM) * EM * max(muGM, 0.0);
        }
        L += T * lit * (frame.airOzone.w / AIR_PI);
    }
    AirPath p;
    p.inscatter = L;
    p.transmittance = T;
    return p;
}

// the moon the air is lit by: the host's (atlas_set_moon), else opposite the sun as the key light takes it
float3 airMoonDir(float3 sun) { return gMoonDir.w > 0.5 ? gMoonDir.xyz : -sun; }

// air.hpp skyRadiance, from the eye (frame.airSun.w: its altitude, km)
float3 airSkyRadiance(float3 dir) {
    float3 sun = frame.sunDir.xyz, moon = airMoonDir(sun);
    float r = R_GROUND + frame.airSun.w;
    float tg = airDistanceToGround(r, dir.y);
    float tEnd = tg >= 0.0 ? tg : airDistanceToTop(r, dir.y);
    return airIntegrate(r, dir.y, dot(dir, sun), sun.y, frame.airSun.xyz, dot(dir, moon), moon.y, frame.airMoon.xyz,
                        tEnd, SKY_STEPS, true, tg >= 0.0, dir).inscatter + NIGHT_GLOW;
}

// air.hpp aerialPath: the air between the eye and a surface t blocks away along dir
AirPath airAerial(float3 dir, float t) {
    float3 sun = frame.sunDir.xyz, moon = airMoonDir(sun);
    float r = R_GROUND + frame.airSun.w;
    float tEnd = min(t * frame.airMieExt.w, airDistanceToTop(r, dir.y));
    // (FRAME_LIGHT_LOW, the cheap light: half the steps; the energy-conserving steps keep a long path's total)
    int steps = (frame.dims.z & FRAME_LIGHT_LOW) != 0u ? AERIAL_STEPS / 2 : AERIAL_STEPS;
    AirPath p = airIntegrate(r, dir.y, dot(dir, sun), sun.y, frame.airSun.xyz, dot(dir, moon), moon.y,
                             frame.airMoon.xyz, tEnd, steps, false, false, dir);
    p.inscatter += NIGHT_GLOW * (1.0 - p.transmittance);
    return p;
}

// The transmittance alone of that path, analytic (flat air, exponential layers: exact but for the curvature, which
// adds nothing over a surface's distance; the ozone lies above every surface): for what needs no inscatter
float3 airTransmittanceTo(float3 dir, float t) {
    float tk = t * frame.airMieExt.w;
    float h0 = frame.airSun.w;
    float dh = tk * dir.y;
    float invHM = frame.airRayleigh.w;
    float xR = dh / H_RAYLEIGH, xM = dh * invHM;
    float fR = abs(xR) < 1e-3 ? 1.0 - 0.5 * xR : (1.0 - exp(-xR)) / xR;
    float fM = abs(xM) < 1e-3 ? 1.0 - 0.5 * xM : (1.0 - exp(-xM)) / xM;
    float3 od = (frame.airRayleigh.xyz * (exp(-h0 / H_RAYLEIGH) * fR) + frame.airMieExt.xyz * (exp(-h0 * invHM) * fM)) * tk;
    return exp(-od);
}

// Sets the key light for a point at world height y (gKeyRad; keyLightRadiance reads it): air.hpp keyLight
void airAt(float worldY) {
    float3 sun = frame.sunDir.xyz;
    float r = R_GROUND + max(worldY - SEA_LEVEL_Y, 0.0) * frame.airMieExt.w;
    if (sun.y > -0.05) {
        gKeyRad = airLightTransmittance(r, sun.y) * frame.airSun.xyz * (1.0 / AIR_PI);
        return;
    }
    float3 moon = gMoonDir.w > 0.5 ? gMoonDir.xyz : -sun;
    float3 moonRad = gMoonDir.w > 0.5 ? gMoonRad.xyz : float3(0.035, 0.05, 0.09);  // the host's moons, else the old key
    gKeyRad = airLightTransmittance(r, moon.y) * moonRad * smoothstep(-0.05, -0.2, sun.y);
}

// The key light at a point pRel blocks from the camera: airAt at its height, the sun's an eclipse's share there (the
// umbra's footprint dark, the land outside it lit)
void airAtPoint(float3 pRel) {
    airAt(pRel.y + float(frame.camInt.y));
    if (frame.sunDir.y > -0.05) gKeyRad *= airSunShareAt(pRel);
}

// The sun's light alone at world height y (the cloud deck's sunlit side)
float3 airSunAt(float worldY) {
    float r = R_GROUND + max(worldY - SEA_LEVEL_Y, 0.0) * frame.airMieExt.w;
    return airLightTransmittance(r, frame.sunDir.y) * frame.airSun.xyz * (1.0 / AIR_PI);
}

// each entry point: the frame's moon and the key light at the eye
void airBegin() {
    gMoonDir = frame.moonDir;
    gMoonRad = frame.moonRad;
    airAtPoint(float3(0.0, frame.camFrac.y, 0.0));
}

// ---- the look's names for the air (what the shading calls) ---------------------------------------------------------

float3 skyColor(float3 dir, float3 sun) { return airSkyRadiance(dir); }

// the sky's light on a face (the host's integral at the eye, air.hpp skyLight): sky over the horizon, ground under it
float3 skyAmbient(float3 n, float3 sun) { return lerp(frame.airGround.xyz, frame.airSkyUp.xyz, n.y * 0.5 + 0.5); }

// The key light: the sun, or the moon (opposite it unless the host set one) once the sun is below the horizon
float3 keyLightDir(float3 sun) { return sun.y > -0.05 ? sun : (gMoonDir.w > 0.5 ? gMoonDir.xyz : -sun); }

float3 keyLightRadiance(float3 sun) { return gKeyRad; }

// no aerial perspective without FRAME_ATMOS (ground-truth views: the game's fog-off)
float3 aerialT(float3 dir, float t) {
    if ((frame.dims.z & FRAME_ATMOS) == 0u) return float3(1, 1, 1);
    return airTransmittanceTo(dir, t);
}

AirPath aerialPathTo(float3 dir, float t) {
    if ((frame.dims.z & FRAME_ATMOS) == 0u) {
        AirPath p;
        p.inscatter = float3(0, 0, 0);
        p.transmittance = float3(1, 1, 1);
        return p;
    }
    return airAerial(dir, t);
}

float3 applyAerial(float3 c, float3 dir, float3 sun, float t, FrameGpu f) {
    AirPath p = aerialPathTo(dir, t);
    return c * p.transmittance + p.inscatter;
}

// ao: 0..1 sky-light occlusion (crevices, forest floors), applied to the ambient term only.
// lightVis: shadowing of the key light. reflection: what water mirrors (the sky, or traced far terrain).
// ambient: the sky (and floor) light reaching the surface, already occluded (shadeSurface: skyAmbient x ao).
float3 shadeSurfaceAmb(float3 albedo, MaterialGpu m, float3 n, float3 v, float3 sun, float lightVis, uint cls,
                       float3 ambient, float3 reflection) {
    float3 L = keyLightDir(sun);
    float ndl = saturate(dot(n, L));
    float3 direct = keyLightRadiance(sun) * ndl * lightVis;
    // the look (one for near and far: the near pass's pixels are lit by this too): the air's sun against the air's
    // sky (LOOK_AMBIENT 1: as they are), and the lit ground bounces warm light into the shade: traced where the sky
    // rays met a surface (its own light, gBounce: an arch's underside over sunlit ground, a gorge's walls; a cave's
    // unlit walls send none), over the rest of the rays a constant from below and the sides, as far as the sky reaches
    float3 sky = skyAmbient(n, sun);
    float open = saturate(dot(ambient, float3(0.2126, 0.7152, 0.0722)) / max(dot(sky, float3(0.2126, 0.7152, 0.0722)), 1e-4));
    float3 bounce = gBounce.rgb + keyLightRadiance(sun) * (max(L.y, 0.0) * LOOK_BOUNCE * (0.6 - 0.4 * n.y) * open * gBounce.a);
    gBounce = float4(0.0, 0.0, 0.0, 1.0);
    float3 c = albedo * (direct + ambient * LOOK_AMBIENT + bounce);
    // (water: the body seen through the surface, when the caller traced it: the bed through the water, gWaterBody)
    if (cls == 2u && gWaterBody.x >= 0.0) c = gWaterBody;
    gWaterBody = float3(-1.0, -1.0, -1.0);
    if (cls == 2u) {
        // water: Schlick Fresnel against what it reflects, plus the key light's glint; m.albedoRough.a carries the
        // glint's alpha (the far field sets it from the waves it can no longer draw: a sun path widening with distance)
        float cosv = saturate(dot(-v, n));
        float f = 0.02 + 0.98 * pow(1.0 - cosv, 5.0);
        float3 r = reflect(v, n);
        float alpha = clamp(m.albedoRough.a, 0.005, 0.5);
        float e = 2.0 / (alpha * alpha) - 2.0;  // the Phong lobe matching that alpha, energy-normalised
        float spec = pow(saturate(dot(r, L)), e) * (e + 8.0) / 25.1327 * 0.37 * lightVis;
        c = lerp(c, reflection, f) + keyLightRadiance(sun) * spec;
    }
    c += albedo * m.emisClass.x * 2.0;
    return c;
}

float3 shadeSurface(float3 albedo, MaterialGpu m, float3 n, float3 v, float3 sun, float lightVis, uint cls, float ao,
                    float3 reflection) {
    return shadeSurfaceAmb(albedo, m, n, v, sun, lightVis, cls, skyAmbient(n, sun) * ao, reflection);
}
