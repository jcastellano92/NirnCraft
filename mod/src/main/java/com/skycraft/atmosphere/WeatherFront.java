package com.skycraft.atmosphere;

import com.skycraft.atmosphere.weather.WeatherKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Objects;

/**
 * A moving synoptic weather system (blizzard, sandstorm/haboob, ashstorm, coastal squall/thunderstorm, sea fog,
 * rain or snow band) that drifts across Tamriel along its wind vector {@code (vx, vz)} and is synchronized from
 * {@link com.skycraft.atmosphere.weather.RegionalWeather} to clients via
 * {@link com.skycraft.atmosphere.weather.WeatherPackets}.
 */
public final class WeatherFront {
    public static final float MIN_RADIUS = 600.0f;
    public static final float MAX_RADIUS = 2200.0f;
    public static final float APPROACH_MIN_DIST = 200.0f;
    public static final float APPROACH_MAX_DIST = 1600.0f;

    public final int id;
    public float x;
    public float z;
    public final float radius;
    public float vx;
    public float vz;
    public final WeatherKind kind;
    public float intensity;
    public int ticksLeft;

    public WeatherFront(int id, float x, float z, float radius, float vx, float vz,
                        WeatherKind kind, float intensity, int ticksLeft) {
        this.id = id;
        this.x = x;
        this.z = z;
        this.radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        this.vx = vx;
        this.vz = vz;
        this.kind = kind == null ? WeatherKind.CLEAR : kind;
        this.intensity = Math.max(0.0f, Math.min(1.0f, intensity));
        this.ticksLeft = Math.max(0, ticksLeft);
    }

    public int id() { return id; }
    public float x() { return x; }
    public float z() { return z; }
    public float centerX() { return x; }
    public float centerZ() { return z; }
    public float radius() { return radius; }
    public float vx() { return vx; }
    public float vz() { return vz; }
    public float velX() { return vx; }
    public float velZ() { return vz; }
    public WeatherKind kind() { return kind; }
    public float intensity() { return intensity; }
    public int ticksLeft() { return ticksLeft; }
    public int ticksRemaining() { return ticksLeft; }

    /** Advances this front by one tick along its wind velocity vector {@code (vx, vz)} and returns {@code true} if expired. */
    public boolean tick() {
        advect(1);
        return expired();
    }

    /** Advances this front by {@code ticks} along its wind velocity vector {@code (vx, vz)}. */
    public void advect(int ticks) {
        if (ticks <= 0) return;
        this.x += this.vx * ticks;
        this.z += this.vz * ticks;
        this.ticksLeft = Math.max(0, this.ticksLeft - ticks);
    }

    public boolean expired() {
        return ticksLeft <= 0 || intensity <= 0.001f;
    }

    public WeatherFront copy() {
        return new WeatherFront(id, x, z, radius, vx, vz, kind, intensity, ticksLeft);
    }

    /** Euclidean distance in blocks from {@code (px, pz)} to the center {@code (x, z)} of this front. */
    public float distanceToCenter(double px, double pz) {
        double dx = px - this.x;
        double dz = pz - this.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Distance in blocks from {@code (px, pz)} to the outer edge of this front ({@code 0} when inside {@code radius}).
     */
    public float distanceToEdge(double px, double pz) {
        return Math.max(0.0f, distanceToCenter(px, pz) - this.radius);
    }

    /**
     * Smooth radial weather intensity at world coordinates {@code (px, pz)} in {@code [0.0, intensity]}.
     * Equal to {@link #intensity} at the front center {@code (x, z)} and smoothly falling off to {@code 0.0} at
     * {@code dist >= radius}.
     */
    public float intensityAt(double px, double pz) {
        if (this.radius <= 0.0f || this.intensity <= 0.0f) return 0.0f;
        float dist = distanceToCenter(px, pz);
        if (dist >= this.radius) return 0.0f;
        float u = 1.0f - (dist / this.radius);
        float smooth = u * u * (3.0f - 2.0f * u);
        return this.intensity * smooth;
    }

    /**
     * Horizon approach factor in {@code [0.0, 1.0]} for rendering distant storm walls, curtains, and anvil clouds
     * when this front is between {@code 200} and {@code 1,600} blocks away from {@code (px, pz)}.
     */
    public float approachFactor(double px, double pz) {
        float edge = distanceToEdge(px, pz);
        if (edge > APPROACH_MAX_DIST) return 0.0f;
        if (edge <= 0.0f) {
            float centerDist = distanceToCenter(px, pz);
            return centerDist >= APPROACH_MIN_DIST ? this.intensity : this.intensity * (centerDist / APPROACH_MIN_DIST);
        }
        float t = 1.0f - (edge / APPROACH_MAX_DIST);
        return this.intensity * t * t * (3.0f - 2.0f * t);
    }

    /**
     * Curved mesoscale rain-band / squall line intensity at {@code (px, pz)} in {@code [0.0, intensity]}, modeling
     * the leading bowed squall arc and trailing spiral rain bands inside this weather system.
     */
    public float rainBandAt(double px, double pz, double timeSec) {
        return WeatherPhysics.frontRainBandAt(this, px, pz, timeSec);
    }

    /**
     * Sub-cloud precipitation curtain density at {@code (px, pz)} in {@code [0.0, 1.0]} under this weather system's
     * rain bands and convective core.
     */
    public float curtainDensityAt(double px, double pz, double timeSec) {
        if (kind.precip == WeatherKind.Precip.NONE) return 0.0f;
        float band = rainBandAt(px, pz, timeSec);
        float core = intensityAt(px, pz);
        return WeatherPhysics.clamp01(kind.amount * (0.30f * core + 0.70f * band));
    }

    /** True if this front is a major storm wall/squall visible on the horizon. */
    public boolean isHorizonWallKind() {
        return kind == WeatherKind.BLIZZARD
                || kind == WeatherKind.SANDSTORM
                || kind == WeatherKind.ASH_STORM
                || kind == WeatherKind.BLIGHT
                || kind == WeatherKind.STORM
                || kind == WeatherKind.SNOW
                || kind == WeatherKind.RAIN;
    }

    /** True if this front brings hazardous outdoor conditions (blizzard, sandstorm, ashstorm, blight). */
    public boolean isSevere() {
        return kind == WeatherKind.BLIZZARD
                || kind == WeatherKind.SANDSTORM
                || kind == WeatherKind.ASH_STORM
                || kind == WeatherKind.BLIGHT;
    }

    /** Wind direction in degrees matching {@code ClientWeather}'s yaw convention ({@code vx = -sin(deg), vz = cos(deg)}). */
    public int windDegrees() {
        if (Math.abs(vx) < 1.0e-5f && Math.abs(vz) < 1.0e-5f) return 0;
        double deg = Math.toDegrees(Math.atan2(-vx, vz));
        int d = (int) Math.round(deg) % 360;
        return d < 0 ? d + 360 : d;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeFloat(x);
        buf.writeFloat(z);
        buf.writeFloat(radius);
        buf.writeFloat(vx);
        buf.writeFloat(vz);
        buf.writeByte(kind.ordinal());
        buf.writeFloat(intensity);
        buf.writeVarInt(ticksLeft);
    }

    public static WeatherFront read(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        float x = buf.readFloat();
        float z = buf.readFloat();
        float radius = buf.readFloat();
        float vx = buf.readFloat();
        float vz = buf.readFloat();
        WeatherKind kind = WeatherKind.byOrdinal(buf.readByte());
        float intensity = buf.readFloat();
        int ticksLeft = buf.readVarInt();
        return new WeatherFront(id, x, z, radius, vx, vz, kind, intensity, ticksLeft);
    }

    public static void encode(WeatherFront front, FriendlyByteBuf buf) {
        front.write(buf);
    }

    public static WeatherFront decode(FriendlyByteBuf buf) {
        return read(buf);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putFloat("x", x);
        tag.putFloat("z", z);
        tag.putFloat("radius", radius);
        tag.putFloat("vx", vx);
        tag.putFloat("vz", vz);
        tag.putString("kind", kind.id());
        tag.putFloat("intensity", intensity);
        tag.putInt("ticksLeft", ticksLeft);
        return tag;
    }

    public static WeatherFront load(CompoundTag tag) {
        WeatherKind k = WeatherKind.byId(tag.getStringOr("kind",""));
        if (k == null) k = WeatherKind.STORM;
        return new WeatherFront(
                tag.getIntOr("id",0),
                tag.getFloatOr("x",0F),
                tag.getFloatOr("z",0F),
                tag.getFloatOr("radius",0F),
                tag.getFloatOr("vx",0F),
                tag.getFloatOr("vz",0F),
                k,
                tag.getFloatOr("intensity",0F),
                tag.getIntOr("ticksLeft",0)
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WeatherFront that)) return false;
        return id == that.id
                && Float.compare(that.x, x) == 0
                && Float.compare(that.z, z) == 0
                && Float.compare(that.radius, radius) == 0
                && Float.compare(that.vx, vx) == 0
                && Float.compare(that.vz, vz) == 0
                && kind == that.kind
                && Float.compare(that.intensity, intensity) == 0
                && ticksLeft == that.ticksLeft;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, x, z, radius, vx, vz, kind, intensity, ticksLeft);
    }

    @Override
    public String toString() {
        return "WeatherFront{id=" + id + ", kind=" + kind + ", pos=(" + x + "," + z + "), r=" + radius
                + ", vel=(" + vx + "," + vz + "), intensity=" + intensity + ", ticksLeft=" + ticksLeft + '}';
    }
}
