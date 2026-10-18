// The planet's curvature for the rasterised near field: a point at horizontal distance d from the camera drops by
// d^2 / 2R, matching aether's parabolic view rays exactly (native/shaders/aether_far.slang header).
#ifndef SKYCRAFT_CURVATURE
#define SKYCRAFT_CURVATURE

vec3 curvePlayerPos(vec3 playerPos) {
#ifdef CURVATURE
    playerPos.y -= dot(playerPos.xz, playerPos.xz) / (2.0 * PLANET_RADIUS);
#endif
    return playerPos;
}

#endif
