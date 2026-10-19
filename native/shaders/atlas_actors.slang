// Far actors: players and large creatures beyond vanilla entity tracking, as analytic impostors (a few primitives
// per actor) intersected per pixel and depth-tested against the far terrain. Positions arrive camera-relative with
// the curvature drop already applied (host side, in double precision), so they stand on the curved ground.
// Requires (declared by the including kernel): frame, actors; frame.weatherParams.w = actor count.

struct ActorGpu {
    float4 posYaw;  // camera-relative feet position (view-flat), yaw radians (0 = facing -Z)
    float4 shape;   // height, radius, kind, tint (RGBA8 bit pattern)
    float4 motion;  // animation phase seed 0..1, gait (0 still / on the ground, 1 gliding, 2 flapping), opacity 0..1
};

static const int ACTOR_HUMANOID = 0;
static const int ACTOR_CREATURE = 1;
static const int ACTOR_DRAGON = 2;
static const int ACTOR_SHIP = 3;

struct ActorHit {
    float t;
    float3 n;
    float3 albedo;
};

// iq's capsule intersection: segment a..b, radius r.
float rayCapsule(float3 ro, float3 rd, float3 a, float3 b, float r, out float3 n) {
    float3 ba = b - a, oa = ro - a;
    float baba = dot(ba, ba), bard = dot(ba, rd), baoa = dot(ba, oa), rdoa = dot(rd, oa), oaoa = dot(oa, oa);
    float A = baba - bard * bard, B = baba * rdoa - baoa * bard, C = baba * oaoa - baoa * baoa - r * r * baba;
    float h = B * B - A * C;
    n = float3(0, 1, 0);
    if (h < 0.0) return -1.0;
    float t = (-B - sqrt(h)) / A;
    float y = baoa + t * bard;
    if (y > 0.0 && y < baba) {
        float3 p = ro + rd * t;
        float3 c = a + ba * (y / baba);
        n = normalize(p - c);
        return t;
    }
    float3 oc = y <= 0.0 ? oa : ro - b;
    B = dot(rd, oc);
    C = dot(oc, oc) - r * r;
    h = B * B - C;
    if (h <= 0.0) return -1.0;
    t = -B - sqrt(h);
    n = normalize(oc + rd * t);
    return t;
}

// Axis-aligned ellipsoid in the actor's local frame.
float rayEllipsoid(float3 ro, float3 rd, float3 radii, out float3 n) {
    float3 ocn = ro / radii, rdn = rd / radii;
    float a = dot(rdn, rdn), b = dot(ocn, rdn), c = dot(ocn, ocn);
    float h = b * b - a * (c - 1.0);
    n = float3(0, 1, 0);
    if (h < 0.0) return -1.0;
    float t = (-b - sqrt(h)) / a;
    n = normalize((ro + rd * t) / (radii * radii));
    return t;
}

float rayBox(float3 ro, float3 rd, float3 half, out float3 n) {
    float3 m = float3(abs(rd.x) > 1e-9 ? 1.0 / rd.x : 1e9, abs(rd.y) > 1e-9 ? 1.0 / rd.y : 1e9, abs(rd.z) > 1e-9 ? 1.0 / rd.z : 1e9);
    float3 k = abs(m) * half;
    float3 t1 = -ro * m - k, t2 = -ro * m + k;
    float tN = max(max(t1.x, t1.y), t1.z), tF = min(min(t2.x, t2.y), t2.z);
    n = float3(0, 1, 0);
    if (tN > tF || tF < 0.0) return -1.0;
    n = t1.x >= t1.y && t1.x >= t1.z ? float3(-sign(rd.x), 0, 0) : (t1.y >= t1.z ? float3(0, -sign(rd.y), 0) : float3(0, 0, -sign(rd.z)));
    return tN;
}

void keepNearest(float t, float3 n, inout float best, inout float3 bestN) {
    if (t > 0.0 && t < best) {
        best = t;
        bestN = n;
    }
}

float3 rotZ(float3 v, float a) {
    float c = cos(a), s = sin(a);
    return float3(c * v.x - s * v.y, s * v.x + c * v.y, v.z);
}

// One dragon wing on side sgn (+1 right, -1 left, mirrored): an inner arm membrane hinged at the shoulder, raised by
// a1 about the body's long axis, and an outer membrane hinged at its tip, raised a further a2 (lagging the beat, so
// the tip whips). Boxes in each hinge's frame; returns the hit and its normal in the actor's frame.
float rayWing(float3 ro, float3 rd, float sgn, float3 shoulder, float len1, float len2, float chord, float th, float a1,
              float a2, out float3 n) {
    float3 mo = float3(ro.x * sgn, ro.y, ro.z), md = float3(rd.x * sgn, rd.y, rd.z);
    float3 o1 = rotZ(mo - shoulder, -a1), d1 = rotZ(md, -a1);
    float3 n1, n2;
    float t1 = rayBox(o1 - float3(len1 * 0.5, 0, chord * 0.1), d1, float3(len1 * 0.5, th, chord * 0.5), n1);
    float3 o2 = rotZ(o1 - float3(len1, 0, 0), -a2), d2 = rotZ(d1, -a2);
    // the outer membrane narrows and sweeps back towards the tail
    float t2 = rayBox(o2 - float3(len2 * 0.5, 0, chord * 0.32), d2, float3(len2 * 0.5, th, chord * 0.36), n2);
    float best = 1e30;
    float3 bn = float3(0, 1, 0);
    if (t1 > 0.0 && t1 < best) { best = t1; bn = rotZ(n1, a1); }
    if (t2 > 0.0 && t2 < best) { best = t2; bn = rotZ(rotZ(n2, a2), a1); }
    n = float3(bn.x * sgn, bn.y, bn.z);
    return best < 1e29 ? best : -1.0;
}

// dither: this pixel's threshold for actors fading at the tracking boundary (motion.z < 1: the client has the real
// entity, or just lost it, and the impostor dissolves). tMin: where the near field ends: an impostor is the far field's
// stand-in for an entity the game cannot draw, so none is drawn nearer than that (the game draws what is there, and the
// pack's composite would put an impostor in front of nearer houses); across the next ACTOR_FADE_IN blocks it dissolves
// in, so an actor walking out of the near field never pops.
static const float ACTOR_FADE_IN = 16.0;
ActorHit traceActors(float3 o, float3 dir, float tMin, float tMax, float dither) {
    ActorHit h;
    h.t = -1.0;
    h.n = float3(0, 1, 0);
    h.albedo = float3(1, 1, 1);
    int count = int(frame.weatherParams.w);
    float best = tMax;
    for (int i = 0; i < min(count, 512); i++) {
        ActorGpu a = actors[i];
        if (dither >= a.motion.z) continue;  // dissolved here (opacity 1 draws every pixel)
        float height = a.shape.x, radius = a.shape.y;
        int kind = int(a.shape.z);
        float3 feet = a.posYaw.xyz;
        // bounding sphere first
        float3 centre = feet + float3(0, height * 0.5, 0);
        float bound = max(height, radius * (kind == ACTOR_DRAGON ? 5.0 : 4.0));
        float3 oc = o - centre;
        float bb = dot(oc, dir), cc = dot(oc, oc) - bound * bound;
        if (bb * bb - cc < 0.0 || (bb > 0.0 && cc > 0.0)) continue;
        // into the actor's local frame (yaw about +Y)
        float cy = cos(a.posYaw.w), sy = sin(a.posYaw.w);
        float3 lo = o - feet;
        lo = float3(cy * lo.x - sy * lo.z, lo.y, sy * lo.x + cy * lo.z);
        float3 ld = float3(cy * dir.x - sy * dir.z, dir.y, sy * dir.x + cy * dir.z);
        float t = best;
        float3 ln = float3(0, 1, 0), nn;
        if (kind == ACTOR_HUMANOID) {
            float r = radius;
            keepNearest(rayCapsule(lo, ld, float3(0, r, 0), float3(0, height * 0.78 - r, 0), r, nn), nn, t, ln);
            float tH = rayEllipsoid(lo - float3(0, height * 0.88, 0), ld, float3(r * 0.62, height * 0.12, r * 0.62), nn);
            keepNearest(tH, nn, t, ln);
        } else if (kind == ACTOR_CREATURE) {
            keepNearest(rayEllipsoid(lo - float3(0, height * 0.55, 0), ld, float3(radius, height * 0.42, radius * 1.7), nn), nn, t, ln);
            keepNearest(rayEllipsoid(lo - float3(0, height * 0.8, -radius * 1.75), ld, float3(radius * 0.45, height * 0.22, radius * 0.5), nn), nn, t, ln);
            // four legs down to the ground
            for (int leg = 0; leg < 4; leg++) {
                float lx = (leg & 1) != 0 ? radius * 0.55 : -radius * 0.55, lz = (leg & 2) != 0 ? radius * 1.0 : -radius * 1.0;
                keepNearest(rayCapsule(lo, ld, float3(lx, radius * 0.22, lz), float3(lx, height * 0.45, lz), radius * 0.22, nn), nn, t, ln);
            }
        } else if (kind == ACTOR_DRAGON) {
            // body, neck and head forward (-Z), tail back, and two hinged wings beating on the frame clock: about
            // 0.8 Hz flapping, a slow sway gliding, folded along the flanks when still (on the ground)
            float r = radius;
            keepNearest(rayEllipsoid(lo - float3(0, height * 0.5, 0.1 * r), ld, float3(r * 0.55, height * 0.3, r * 1.8), nn), nn, t, ln);
            keepNearest(rayCapsule(lo, ld, float3(0, height * 0.58, -1.4 * r), float3(0, height * 0.72, -2.5 * r), r * 0.2, nn), nn, t, ln);
            keepNearest(rayEllipsoid(lo - float3(0, height * 0.74, -2.85 * r), ld, float3(r * 0.26, r * 0.2, r * 0.48), nn), nn, t, ln);
            keepNearest(rayCapsule(lo, ld, float3(0, height * 0.48, 1.6 * r), float3(0, height * 0.4, 4.0 * r), r * 0.13, nn), nn, t, ln);
            float gait = a.motion.y;
            float ph = frame.sunDir.w * 5.0265482 + a.motion.x * 6.2831853;  // 0.8 Hz
            float a1, a2;
            if (gait > 1.5) {
                a1 = 0.1 + 0.62 * sin(ph);
                a2 = 0.05 + 0.45 * sin(ph - 0.9);
            } else if (gait > 0.5) {
                a1 = 0.1 + 0.05 * sin(ph * 0.35);
                a2 = -0.04;
            } else {
                a1 = 0.55;
                a2 = -2.5;
            }
            float3 shoulder = float3(r * 0.35, height * 0.62, -0.35 * r);
            float th = max(height * 0.025, 0.08);
            keepNearest(rayWing(lo, ld, 1.0, shoulder, r * 1.7, r * 1.9, r * 1.5, th, a1, a2, nn), nn, t, ln);
            keepNearest(rayWing(lo, ld, -1.0, shoulder, r * 1.7, r * 1.9, r * 1.5, th, a1, a2, nn), nn, t, ln);
        } else {
            keepNearest(rayBox(lo - float3(0, height * 0.15, 0), ld, float3(radius * 0.35, height * 0.15, radius), nn), nn, t, ln);
            keepNearest(rayCapsule(lo, ld, float3(0, height * 0.3, 0), float3(0, height, 0), radius * 0.04, nn), nn, t, ln);
            keepNearest(rayBox(lo - float3(0, height * 0.7, 0), ld, float3(radius * 0.32, height * 0.25, radius * 0.02), nn), nn, t, ln);
        }
        if (t < best && (t < tMin || dither >= a.motion.z * saturate((t - tMin) / ACTOR_FADE_IN))) t = best;  // inside or entering the near field
        if (t < best) {
            best = t;
            h.t = t;
            // back to world: inverse yaw rotation
            float3 wn = float3(cy * ln.x + sy * ln.z, ln.y, -sy * ln.x + cy * ln.z);
            // a degenerate normal (a ray through a primitive's axis) must not shade the impostor NaN (drawn black)
            h.n = dot(wn, wn) > 1e-12 && all(wn == wn) ? normalize(wn) : -dir;
            uint tint = asuint(a.shape.w);
            h.albedo = pow(float3(float(tint & 255u), float((tint >> 8) & 255u), float((tint >> 16) & 255u)) / 255.0,
                           float3(2.2, 2.2, 2.2));
        }
    }
    return h;
}
