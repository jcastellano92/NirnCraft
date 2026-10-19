package com.nirncraft.client.pinned;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Where a pinned copy is drawn: the pin's anchor O (its box centre, in the coordinates it was taken from) goes to A in
 * the world, the copy scaled by s about it. A point p of the original lands at A + s (p - O).
 *
 * <p>Perspective scaling: seen from a camera c, that copy projects exactly like the original seen from the equivalent
 * eye E = O + (c - A) / s (every direction from the camera is the same, every distance s times shorter). So a subject
 * kilometres away is drawn s times smaller, s times nearer, well inside the far plane, and covers the same pixels with
 * the same texture derivatives (mip selection, anisotropy) and the same baked per-vertex light. {@link #view} chooses
 * A and s for a distance along a direction; the capture receipt carries E for the offline reference.
 */
public final class PinTransform {
    /** The copy's anchor in the pin's own coordinates (its box centre). */
    public final double ox, oy, oz;
    /** Where the anchor is drawn in the world. */
    public final double ax, ay, az;
    /** The copy's size against the original (1: the real size; 1 / k: k times smaller). */
    public final double scale;

    public PinTransform(double ox, double oy, double oz, double ax, double ay, double az, double scale) {
        if (!(scale > 0) || !Double.isFinite(scale)) throw new IllegalArgumentException("scale must be positive");
        this.ox = ox; this.oy = oy; this.oz = oz;
        this.ax = ax; this.ay = ay; this.az = az;
        this.scale = scale;
    }

    /** Minecraft's look vector for a yaw and pitch in degrees (yaw 0 looks south, +Z; pitch > 0 looks down). */
    public static double[] forward(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        return new double[] {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
    }

    /**
     * The copy for a subject at a true distance from the camera along a direction: real size while the distance is
     * under maxDrawn, else scaled so its anchor is drawn at maxDrawn; scaled further when the copy's far side would
     * pass farLimit (the projection's far plane with a margin). radius: the subject's extent around its anchor.
     */
    public static PinTransform view(double ox, double oy, double oz, double cx, double cy, double cz, double distance,
                                    double yawDeg, double pitchDeg, double radius, double maxDrawn, double farLimit) {
        if (!(distance > 0)) throw new IllegalArgumentException("distance must be positive");
        double s = Math.min(1.0, maxDrawn / distance);
        if ((distance + radius) * s > farLimit) s = farLimit / (distance + radius);
        double[] f = forward(yawDeg, pitchDeg);
        double d = distance * s;
        return new PinTransform(ox, oy, oz, cx + f[0] * d, cy + f[1] * d, cz + f[2] * d, s);
    }

    /** A point of the original (pin coordinates) where the copy draws it. */
    public double[] toWorld(double px, double py, double pz) {
        return new double[] {ax + scale * (px - ox), ay + scale * (py - oy), az + scale * (pz - oz)};
    }

    /** The eye that sees the original exactly as the camera at c sees the copy. */
    public double[] equivalentEye(double cx, double cy, double cz) {
        return new double[] {ox + (cx - ax) / scale, oy + (cy - ay) / scale, oz + (cz - az) / scale};
    }

    /** The true distance from the camera at c to the subject's anchor (what the picture shows). */
    public double equivalentDistance(double cx, double cy, double cz) {
        double dx = cx - ax, dy = cy - ay, dz = cz - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) / scale;
    }

    /**
     * The terrain shader's model-view for one draw of the copy. The vanilla terrain vertex shader computes
     * pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset, then ProjMat * ModelViewMat * pos; with the
     * section uniform's ChunkPosition set to (section origin - box origin) + K (K: an integer point near the camera,
     * chosen when the copy is uploaded), pos = L + K - c, L the vertex in box-local coordinates. The copy wants
     * viewRotation * (A + s (L + B - O) - c) = viewRotation * (s pos + t), t = (A - c) - s ((K - c) + (O - B)): every
     * term small, so float precision holds wherever the pin came from. B: the box's minimum corner.
     */
    public Matrix4f terrainModelView(Matrix4fc viewRotation, double cx, double cy, double cz, int kx, int ky, int kz,
                                     int bx, int by, int bz) {
        double tx = (ax - cx) - scale * ((kx - cx) + (ox - bx));
        double ty = (ay - cy) - scale * ((ky - cy) + (oy - by));
        double tz = (az - cz) - scale * ((kz - cz) + (oz - bz));
        float s = (float) scale;
        return new Matrix4f(viewRotation).translate((float) tx, (float) ty, (float) tz).scale(s, s, s);
    }

    /** n distances from d0 to d1, evenly spaced on a log scale (a fly-in: each frame the same share closer). */
    public static double[] flyIn(double d0, double d1, int n) {
        if (n < 2 || !(d0 > 0) || !(d1 > 0)) throw new IllegalArgumentException("fly-in: two or more frames, distances > 0");
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = d0 * Math.pow(d1 / d0, i / (double) (n - 1));
        return out;
    }
}
