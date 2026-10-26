#pragma once
#include <algorithm>
#include <cmath>
#include <cstdlib>

// Level 0's reach (VoxGrid::frame) towards the value its footprint asks for: up at most ATLAS_REACH_UP (10 %) a second,
// down at most ATLAS_REACH_DOWN (30 %), a change within 10 % ignored; the first value (or an unbounded one) is taken at
// once. Each step of the reach is streaming work, and the adaptive quality's render scale and LOD bias took it from 126
// to 1208 blocks in 8 s and back in one, a static camera's frame from 2.5 to 30 ms every 30 s (10-26).
inline float reachStep(float cur, float want, double dt) {
    static const double up = [] { const char* e = std::getenv("ATLAS_REACH_UP"); return e ? std::atof(e) : 0.10; }();
    static const double down = [] { const char* e = std::getenv("ATLAS_REACH_DOWN"); return e ? std::atof(e) : 0.30; }();
    const double band = 0.10;
    if (!(cur > 0.0f) || cur >= 1e29f || want >= 1e29f) return want;
    if (want > cur * (1.0 + band)) return std::min(want, float(double(cur) * std::exp(dt * std::log1p(up))));
    if (want < cur / (1.0 + band)) return std::max(want, float(double(cur) * std::exp(-dt * std::log1p(down))));
    return cur;
}
