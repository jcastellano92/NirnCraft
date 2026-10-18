// Far actors: players and large creatures beyond vanilla entity tracking, as analytic impostors (a few primitives
// per actor) intersected per pixel and depth-tested against the far terrain. Positions arrive camera-relative with
// the curvature drop already applied (host side, in double precision), so they stand on the curved ground.
// Requires (declared by the including kernel): frame, actors; frame.weatherParams.w = actor count.

struct ActorGpu {
    float4 posYaw;  // camera-relative feet position (view-flat), yaw radians (0 = facing -Z)
    float4 shape;   // height, radius, kind, tint (RGBA8 bit pattern)
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

ActorHit traceActors(float3 o, float3 dir, float tMax) {
    ActorHit h;
    h.t = -1.0;
    h.n = float3(0, 1, 0);
    h.albedo = float3(1, 1, 1);
    int count = int(frame.weatherParams.w);
    float best = tMax;
    for (int i = 0; i < min(count, 512); i++) {
        ActorGpu a = actors[i];
        float height = a.shape.x, radius = a.shape.y;
        int kind = int(a.shape.z);
        float3 feet = a.posYaw.xyz;
        // bounding sphere first
        float3 centre = feet + float3(0, height * 0.5, 0);
        float bound = max(height, radius * 4.0);
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
            keepNearest(rayEllipsoid(lo - float3(0, height * 0.5, 0), ld, float3(radius * 0.6, height * 0.35, radius * 2.2), nn), nn, t, ln);
            keepNearest(rayBox(lo - float3(radius * 1.9, height * 0.55, 0), ld, float3(radius * 1.6, height * 0.03, radius * 0.9), nn), nn, t, ln);
            keepNearest(rayBox(lo - float3(-radius * 1.9, height * 0.55, 0), ld, float3(radius * 1.6, height * 0.03, radius * 0.9), nn), nn, t, ln);
        } else {
            keepNearest(rayBox(lo - float3(0, height * 0.15, 0), ld, float3(radius * 0.35, height * 0.15, radius), nn), nn, t, ln);
            keepNearest(rayCapsule(lo, ld, float3(0, height * 0.3, 0), float3(0, height, 0), radius * 0.04, nn), nn, t, ln);
            keepNearest(rayBox(lo - float3(0, height * 0.7, 0), ld, float3(radius * 0.32, height * 0.25, radius * 0.02), nn), nn, t, ln);
        }
        if (t < best) {
            best = t;
            h.t = t;
            // back to world: inverse yaw rotation
            h.n = normalize(float3(cy * ln.x + sy * ln.z, ln.y, -sy * ln.x + cy * ln.z));
            uint tint = asuint(a.shape.w);
            h.albedo = pow(float3(float(tint & 255u), float((tint >> 8) & 255u), float((tint >> 16) & 255u)) / 255.0,
                           float3(2.2, 2.2, 2.2));
        }
    }
    return h;
}
