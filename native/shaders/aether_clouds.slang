// Curved cloud shell for aether_core. A line-for-line port of Skycraft's cloud density model
// (tools/shaders/patch_complementary_clouds.py: GetCloudNoise, SkycraftMiddleCloudDensity, SkycraftHighCloudDensity)
// onto the same inputs: the two 128^2 weather/dynamics field pairs of WeatherFieldTexture and the baked 64^3 noise
// volume. What changes is the frame: altitude is measured above the curved surface (y + d^2 / 2R), so the deck is a
// spherical shell that wraps over the horizon, and the march shares the terrain's depth for exact occlusion.
// Requires (declared by the including kernel): frame, weather (4 x 128 x 128 RGBA8), cloudNoise (64^3 RGBA8).

static const float CLOUD_BASE_DEFAULT = 232.0;
static const float CLOUD_SHELL_TOP = 1200.0;
static const float CLOUD_SHELL_BASE_MIN = 150.0;  // no deck forms lower (bases are 232 +- the field's offset)

float4 unpackRGBA8(uint v) {
    return float4(float(v & 255u), float((v >> 8) & 255u), float((v >> 16) & 255u), float(v >> 24)) / 255.0;
}

float weatherTime() { return frame.sunDir.w; }

float cloudHash(float3 p) {
    p = frac(p * 0.1031);
    p += dot(p, p.yxz + 33.33);
    return frac((p.x + p.y) * p.z);
}

float4 noiseTexel(int3 c) {
    c = c & 63;
    return unpackRGBA8(cloudNoise[(c.z * 64 + c.y) * 64 + c.x]);
}

// SkycraftCloudNoise: period 16 world units in p, trilinear over the 64^3 volume, mix(b, r, 0.3).
float cloudNoiseAt(float3 p) {
    float3 v = frac(p / 16.0) * 64.0 - 0.5;
    int3 b = int3(floor(v));
    float3 f = v - float3(b);
    float4 c000 = noiseTexel(b), c100 = noiseTexel(b + int3(1, 0, 0));
    float4 c010 = noiseTexel(b + int3(0, 1, 0)), c110 = noiseTexel(b + int3(1, 1, 0));
    float4 c001 = noiseTexel(b + int3(0, 0, 1)), c101 = noiseTexel(b + int3(1, 0, 1));
    float4 c011 = noiseTexel(b + int3(0, 1, 1)), c111 = noiseTexel(b + int3(1, 1, 1));
    float4 n = lerp(lerp(lerp(c000, c100, f.x), lerp(c010, c110, f.x), f.y),
                    lerp(lerp(c001, c101, f.x), lerp(c011, c111, f.x), f.y), f.z);
    return lerp(n.b, n.r, 0.30);
}

float4 weatherTexel(uint layer, int2 c) {
    return unpackRGBA8(weather[layer * 16384u + uint((c.y & 127) * 128 + (c.x & 127))]);
}

float mixWindAngle(float a, float b, float w) { return frac(a + (frac(b - a + 0.5) - 0.5) * w + 1.0); }

// Bilinear with REPEAT; alpha (categorical band + flash) nearest, like the GLSL sampler setup.
float4 sampleField(uint layer, float2 uv, bool angleInBlue) {
    float2 cell = frac(uv) * 128.0 - 0.5;
    int2 b = int2(floor(cell));
    float2 w = cell - float2(b);
    float4 a = weatherTexel(layer, b), bb = weatherTexel(layer, b + int2(1, 0));
    float4 c = weatherTexel(layer, b + int2(0, 1)), d = weatherTexel(layer, b + int2(1, 1));
    float4 v = lerp(lerp(a, bb, w.x), lerp(c, d, w.x), w.y);
    if (angleInBlue) v.b = mixWindAngle(mixWindAngle(a.b, bb.b, w.x), mixWindAngle(c.b, d.b, w.x), w.y);
    else v.a = weatherTexel(layer, int2(floor(frac(uv) * 128.0))).a;
    return v;
}

struct CloudColumn {
    float4 weather;   // coverage, storm tower, curtain, band/flash
    float4 dynamics;  // cloud base / 1024, surface / 1024, wind angle, ground fog
};

CloudColumn sampleColumn(float2 worldXZ, float horizontal) {
    CloudColumn c;
    float farBlend = smoothstep(2200.0, 3600.0, horizontal);
    if (farBlend >= 1.0) {
        // beyond the blend the near fields have no weight: sample only the far pair (most cloud samples)
        c.weather = sampleField(2, worldXZ / 65536.0, false);
        c.dynamics = sampleField(3, worldXZ / 65536.0, true);
        return c;
    }
    c.weather = sampleField(0, worldXZ / 8192.0, false);
    c.dynamics = sampleField(1, worldXZ / 8192.0, true);
    if (farBlend > 0.0) {
        float4 fw = sampleField(2, worldXZ / 65536.0, false);
        float4 fd = sampleField(3, worldXZ / 65536.0, true);
        c.weather.rgb = lerp(c.weather.rgb, fw.rgb, farBlend);
        if (farBlend > 0.5) c.weather.a = fw.a;
        float ang = mixWindAngle(c.dynamics.b, fd.b, farBlend);
        c.dynamics = lerp(c.dynamics, fd, farBlend);
        c.dynamics.b = ang;
    }
    return c;
}

float2 windOf(float4 dyn) {
    float a = dyn.b * 6.2831853;
    return float2(cos(a), sin(a));
}

float highCloudDensity(float3 p, float4 dyn, float coverage, float convection) {
    float h = (p.y - 1020.0) / 180.0;
    if (h <= 0.0 || h >= 1.0) return 0.0;
    float2 wind = windOf(dyn);
    float2 along = float2(0.8, 0.6), across = float2(-0.6, 0.8);
    float2 xz = p.xz - along * weatherTime() * 2.2;
    xz -= (wind - along) * (32.0 + 56.0 * h);
    float3 q = float3(dot(xz, along) / 600.0, p.y / 90.0, dot(xz, across) / 85.0);
    float noise = cloudNoiseAt(q) * 0.7 + cloudNoiseAt(q * 2.7) * 0.3;
    float veil = smoothstep(0.65, 0.95, coverage) * (1.0 - 0.75 * convection);
    float ripples = cloudNoiseAt(float3(xz / 65.0, p.y / 55.0));
    float cellular = smoothstep(0.30, 0.65, coverage) * (1.0 - smoothstep(0.30, 0.70, convection));
    float wisps = max(0.0, noise - 0.48) * 0.10;
    float icePuffs = max(0.0, ripples - 0.57) * cellular * 0.12;
    float sheet = veil * (0.003 + noise * 0.007);
    return (wisps + icePuffs + sheet) * smoothstep(0.0, 0.22, h) * (1.0 - smoothstep(0.48, 1.0, h));
}

float middleCloudDensity(float3 p, float4 w) {
    float h = (p.y - 650.0) / 200.0;
    if (h <= 0.0 || h >= 1.0) return 0.0;
    float moisture = smoothstep(0.42, 0.85, w.r);
    float stratiform = (1.0 - smoothstep(0.30, 0.80, w.g)) * moisture;
    if (stratiform < 0.01) return 0.0;
    float3 q = (p - float3(0.8, 0.0, 0.6) * weatherTime() * 1.4) / float3(110.0, 65.0, 110.0);
    float n = 0.65 * cloudNoiseAt(q) + 0.35 * cloudNoiseAt(q * 2.5);
    float sheet = smoothstep(0.70, 0.96, w.r) * (0.015 + w.b * 0.05);
    float puffs = max(0.0, n - lerp(0.65, 0.42, moisture)) * 0.22;
    return (puffs + sheet) * stratiform * smoothstep(0.0, 0.20, h) * (1.0 - smoothstep(0.50, 1.0, h));
}

// GetCloudNoise at quality 3 (4 octaves), CLOUD_SPEED_MULT 100, CLOUD_UNBOUND_AMOUNT 1, no smoke plumes.
// p: world position with y = altitude above the (curved) surface datum. horizontal: distance from the camera.
float cumulusDensity(float3 p, CloudColumn col, float horizontal, out float h01) {
    float skyCov = col.weather.r, skyTower = col.weather.g, skyCurtain = col.weather.b;
    float effectiveCov = max(skyCov, skyCurtain * 0.68);
    float4 dyn = col.dynamics;
    h01 = 0.0;
    float baseRoll = 0.0;
    if (effectiveCov > 0.48 || skyTower > 0.30) {
        float rollStrength = saturate(max(effectiveCov - 0.45, skyTower * 0.85));
        baseRoll = (cloudNoiseAt(p / 180.0) - 0.5) * 14.0 * rollStrength;
    }
    float lower = dyn.r * 1024.0 + baseRoll;
    float higher = lower + 72.0 + 120.0 * skyCov + 300.0 * skyTower;
    if (p.y < lower || p.y > higher) {
        float ice = highCloudDensity(p, dyn, skyCov, skyTower);
        float middle = middleCloudDensity(p, col.weather);
        return max(ice, middle);
    }
    float height01 = saturate((p.y - lower) / max(12.0, higher - lower));
    h01 = height01;
    float anvilShelf = smoothstep(0.60, 0.82, height01) * (1.0 - smoothstep(0.88, 1.0, height01)) * smoothstep(0.30, 0.78, skyTower);
    float3 traceM = p / 150.0;
    float2 bulkWind = float2(0.8, 0.6);
    float2 parcelXZ = p.xz - bulkWind * weatherTime() * 0.9 - (windOf(dyn) - bulkWind) * 24.0;
    float2 shear = float2(sin(weatherTime() * 0.002), cos(weatherTime() * 0.0017 + 1.2));
    parcelXZ -= shear * pow(height01, 2.0) * (25.0 + skyTower * 55.0);
    float2 parcelCell = floor(parcelXZ / 256.0);
    float parcelShape = -1.0;
    float thickness = higher - lower;
    for (int px = -1; px <= 1; px++) {
        for (int pz = -1; pz <= 1; pz++) {
            float2 cell = parcelCell + float2(float(px), float(pz));
            float r0 = cloudHash(float3(cell.x, 7.0, cell.y));
            float r1 = cloudHash(float3(cell.x, 13.0, cell.y));
            float2 centreXZ = (cell + 0.5 + (float2(r0, r1) - 0.5) * 0.55) * 256.0;
            float radius = lerp(76.0, 230.0, effectiveCov) * (0.75 + r0 * 0.45);
            float growth = 0.88 + 0.12 * sin(weatherTime() * 0.002 + r1 * 6.2831853);
            radius *= growth;
            float2 deltaXZ = parcelXZ - centreXZ;
            if (max(abs(deltaXZ.x), abs(deltaXZ.y)) > radius * 1.15) continue;
            float centreY = lower + thickness * (0.38 + r1 * 0.16);
            float radiusY = thickness * (0.38 + r0 * 0.08) * growth;
            float lobe = 1.0 - length(float3(deltaXZ / radius, (p.y - centreY) / radiusY));
            for (int bud = 0; bud < 3; bud++) {
                float angle = (r0 + float(bud) * 0.333333) * 6.2831853;
                float2 budXZ = centreXZ + float2(cos(angle), sin(angle)) * radius * 0.53;
                float budY = centreY + radiusY * (bud == 0 ? 0.45 + 0.15 * r1 : (bud == 1 ? 0.08 : -0.32));
                float budShape = 1.0 - length(float3((parcelXZ - budXZ) / (radius * 0.61), (p.y - budY) / (radiusY * 0.62)));
                float budMix = saturate(0.5 + 0.5 * (lobe - budShape) / 0.13);
                lobe = lerp(budShape, lobe, budMix) + 0.13 * budMix * (1.0 - budMix);
            }
            float hh = saturate(0.5 + 0.5 * (parcelShape - lobe) / 0.16);
            parcelShape = lerp(lobe, parcelShape, hh) + 0.16 * hh * (1.0 - hh);
        }
    }
    if (parcelShape < -0.10) return 0.0;

    float wind = 0.006 * weatherTime() * (1.0 + 0.85 * height01);
    float noise = 0.0, persist = 1.0, total = 0.0;
    float turretCrisp = (1.0 - anvilShelf * 0.75) * (0.34 + 0.26 * skyTower);
    float2 windDir = float2(0.8, 0.6);
    float2 terrainFlow = (windOf(dyn) - windDir) * 0.16;
    // detail by distance: the finest octaves are sub-pixel at a half-resolution pixel kilometres away
    int octaves = horizontal < 1500.0 ? 4 : (horizontal < 6000.0 ? 3 : 2);
    for (int i = 0; i < octaves; i++) {
        float n = cloudNoiseAt(traceM - float3(windDir.x * wind + terrainFlow.x, 0.0, windDir.y * wind + terrainFlow.y));
        if (i >= 1) n = lerp(n, sqrt(max(n, 0.0)), turretCrisp * 0.35);
        noise += n * persist;
        total += persist;
        traceM *= 2.6;
        wind *= 2.6;
        persist *= 0.53;
    }
    noise /= total;
    float baseFlat = smoothstep(0.0, 0.15, height01);
    float domeRound = 1.0 - smoothstep(0.26, 1.0, height01);
    float anvilCap = 1.0 - smoothstep(0.89, 1.0, height01);
    float verticalShape = lerp(lerp(domeRound, sqrt(max(domeRound, 0.0)), 0.55 + 0.35 * skyTower), anvilCap, anvilShelf * 0.82);
    float cumulusProfile = baseFlat * verticalShape;
    float stableSheet = smoothstep(0.68, 0.94, effectiveCov) * (1.0 - smoothstep(0.25, 0.65, skyTower));
    float overcastFloor = (smoothstep(0.86, 0.98, effectiveCov) * 0.035 + stableSheet * 0.065) * cumulusProfile;
    float eroded = parcelShape + (noise - 0.5) * 0.75 - 0.05;
    float closeDetail = 1.0 - smoothstep(900.0, 2600.0, horizontal);
    if (eroded > -0.15 && eroded < 0.40 && closeDetail > 0.01) {
        float3 edgePos = (p - float3(0.8, 0.0, 0.6) * weatherTime() * 0.9) / 26.0;
        eroded -= (1.0 - cloudNoiseAt(edgePos)) * 0.20 * closeDetail;
    }
    return max(eroded, 0.0) * cumulusProfile * 0.48 + overcastFloor;
}

float cloudDensityAt(float3 p, float horizontal, out float h01) {
    CloudColumn col = sampleColumn(p.xz, horizontal);
    return cumulusDensity(p, col, horizontal, h01);
}

// Altitude of a camera-relative world-flat point above the curved datum (planet curvature).
float curvedAltitude(float3 rel, float k2R) {
    float d2 = dot(rel.xz, rel.xz);
    return rel.y + float(frame.camInt.y) + d2 * k2R;
}

// Solve for t where the curved view ray reaches altitude Y: k t^2 + dy t + (y0 - Y) = 0. Returns smallest t >= 0.
float2 shellInterval(float y0, float dy, float k, float Ylo, float Yhi, float tMax) {
    // altitude(t) = y0 + dy t + k t^2 is convex (k >= 0): the set altitude <= Yhi is an interval [a, b]; the part
    // above Ylo is that interval minus [c, d] where altitude < Ylo.
    float tA = 0.0, tB = tMax;
    if (k > 1e-12) {
        float disc = dy * dy - 4.0 * k * (y0 - Yhi);
        if (disc < 0.0) return float2(1, 0);
        float sq = sqrt(disc);
        tA = max(0.0, (-dy - sq) / (2.0 * k));
        tB = min(tMax, (-dy + sq) / (2.0 * k));
    } else {
        if (abs(dy) < 1e-9) { if (y0 > Yhi) return float2(1, 0); }
        else {
            float tY = (Yhi - y0) / dy;
            if (dy > 0) tB = min(tMax, tY); else tA = max(0.0, tY);
        }
    }
    return float2(tA, tB);
}

struct CloudResult {
    float3 scatter;
    float transmittance;
    float firstHit;
};

float cloudOpticalDepthTowardsSun(float3 rel, float3 sun, float k2R, int steps) {
    float od = 0.0;
    float dist = 32.0;
    for (int l = 0; l < steps; l++) {
        float3 q = rel + sun * dist;
        float3 wp = float3(q.x + float(frame.camInt.x), curvedAltitude(q, k2R), q.z + float(frame.camInt.z));
        float hh;
        od += max(cloudDensityAt(wp, length(q.xz), hh), 0.0) * dist * 0.18 * 0.66;
        dist *= 3.0;
    }
    return od;
}

CloudResult marchClouds(float3 o, float3 dir, float tLimit, float3 sun, uint2 pixel) {
    CloudResult r;
    r.scatter = float3(0, 0, 0);
    r.transmittance = 1.0;
    r.firstHit = -1.0;
    float R = frame.camFrac.w;
    float k2R = R > 0 ? 1.0 / (2.0 * R) : 0.0;
    float k = dot(dir.xz, dir.xz) * k2R;
    float y0 = o.y + float(frame.camInt.y);
    float maxCloud = min(tLimit, 48000.0);
    float2 iv = shellInterval(y0, dir.y, k, CLOUD_SHELL_BASE_MIN, CLOUD_SHELL_TOP, maxCloud);
    if (iv.y <= iv.x) return r;
    // below the lowest possible base: skip to where the curved ray climbs through it
    float ta = iv.x, tb = iv.y;
    if (y0 < CLOUD_SHELL_BASE_MIN) {
        float2 below = shellInterval(y0, dir.y, k, -1e9, CLOUD_SHELL_BASE_MIN, maxCloud);
        if (below.y > ta) ta = below.y;
    }
    if (tb <= ta) return r;
    // interleaved gradient noise: decorrelated per pixel, no moire against the geometric step growth
    float dither = frac(52.9829189 * frac(0.06711056 * float(pixel.x) + 0.00583715 * float(pixel.y)));
    float3 sunCol = sunRadiance(sun);
    float3 amb = skyAmbient(float3(0, 1, 0), sun);
    float mu = dot(dir, sun);
    float phase = 0.65 + 0.35 * pow(saturate(mu), 8.0);
    // steps grow with distance like the GLSL march (fine near, ~2.5 % of the distance far away)
    float t = ta;
    for (int i = 0; i < 192; i++) {
        float dt = clamp(t * 0.025, 12.0, 700.0);
        float ts = t + dt * dither;
        t += dt;
        if (ts >= tb) break;
        float3 rel = o + dir * ts;
        float alt = curvedAltitude(rel, k2R);
        float3 wp = float3(rel.x + float(frame.camInt.x), alt, rel.z + float(frame.camInt.z));
        float horizontal = length(rel.xz);
        CloudColumn col = sampleColumn(wp.xz, horizontal);
        // nothing can form where the column is clear and the upper layers have no veil
        if (max(col.weather.r, col.weather.b) < 0.03 && alt < 640.0) continue;
        float hh;
        float sigma = max(cumulusDensity(wp, col, horizontal, hh), 0.0);
        if (sigma <= 1e-5) continue;
        if (r.firstHit < 0.0) r.firstHit = ts;
        float tau = sigma * dt * 0.18;
        // the light march thins with distance too (4 probes near, 2 beyond ~10 km)
        int lightSteps = ts < 4000.0 ? 4 : (ts < 10000.0 ? 3 : 2);
        float Ts = exp(-cloudOpticalDepthTowardsSun(rel, sun, k2R, lightSteps));
        float3 light = sunCol * Ts * phase * 0.9 + amb * (0.16 + 0.25 * hh + 0.14 * Ts) * 2.2;
        float a = 1.0 - exp(-tau);
        r.scatter += r.transmittance * a * light * float3(0.95, 0.96, 1.0);
        r.transmittance *= 1.0 - a;
        if (r.transmittance < 0.02) break;
    }
    return r;
}

// Sun transmittance through the cloud shell above a surface point (cloud shadows on terrain, 25 km away included).
float cloudShadow(float3 rel, float3 sun) {
    float R = frame.camFrac.w;
    float k2R = R > 0 ? 1.0 / (2.0 * R) : 0.0;
    float alt = curvedAltitude(rel, k2R);
    if (sun.y <= 0.02) return 1.0;
    // straight sun ray locally; altitude grows by sun.y per unit length
    float tLo = max(0.0, (150.0 - alt) / sun.y), tHi = (CLOUD_SHELL_TOP - alt) / sun.y;
    if (tHi <= tLo) return 1.0;
    float od = 0.0;
    const int N = 8;
    for (int i = 0; i < N; i++) {
        float t = tLo + (tHi - tLo) * (float(i) + 0.5) / float(N);
        float3 q = rel + sun * t;
        float3 wp = float3(q.x + float(frame.camInt.x), alt + sun.y * t, q.z + float(frame.camInt.z));
        float hh;
        od += max(cloudDensityAt(wp, length(q.xz), hh), 0.0) * (tHi - tLo) / float(N) * 0.18;
    }
    return exp(-od);
}
