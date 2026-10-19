// Harness camera: yaw/pitch/fov to a camera-relative inverse view-projection (column-major, GL clip space).
#pragma once
#include <cmath>

namespace atlas {

// Camera-relative inverse view-projection (column-major), GL clip conventions.
inline void cameraMatrix(double yawDeg, double pitchDeg, double fovDeg, double aspect, float out[16]) {
    double yaw = yawDeg * 3.14159265358979323846 / 180, pitch = pitchDeg * 3.14159265358979323846 / 180;
    double fx = std::sin(yaw) * std::cos(pitch), fy = std::sin(pitch), fz = -std::cos(yaw) * std::cos(pitch);
    double rx = std::cos(yaw), ry = 0, rz = std::sin(yaw);  // right = forward x up
    double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
    double f = 1.0 / std::tan(fovDeg * 3.14159265358979323846 / 360), n = 0.05, fa = 100000;
    // invP (column-major)
    double ip[16] = {aspect / f, 0, 0, 0, 0, 1 / f, 0, 0, 0, 0, 0, (n - fa) / (2 * n * fa), 0, 0, -1, (n + fa) / (2 * n * fa)};
    // invV: columns right, up, -forward
    double iv[16] = {rx, ry, rz, 0, ux, uy, uz, 0, -fx, -fy, -fz, 0, 0, 0, 0, 1};
    for (int c = 0; c < 4; c++)
        for (int r = 0; r < 4; r++) {
            double s = 0;
            for (int k = 0; k < 4; k++) s += iv[k * 4 + r] * ip[c * 4 + k];
            out[c * 4 + r] = float(s);
        }
}

}  // namespace atlas
