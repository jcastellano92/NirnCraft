package com.nirncraft.fire.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import com.nirncraft.fire.FireConfig;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Place zones that have their own music: the footprint of a place (its Tamriel place id plus a margin) plays a given
 * event instead of the exploration or town piece, with the music controller's normal crossfade. Data:
 * {@code assets/nirncraft/fire/music_zones.json}
 * <pre>{@code { "zones": [ { "place": "helgen", "margin": 40, "event": "music.helgen_aftermath" } ] }}</pre>
 * Used for places where something has just happened (Helgen after the dragon). {@code com.nirncraft.world.client.
 * MusicController} asks {@link #eventAt} once a second.
 */
public final class MusicZones {
    private record Zone(String place, int margin, int radiusOverride, String event) {
    }

    private static List<Zone> zones;

    private MusicZones() {}

    private static synchronized List<Zone> zones() {
        if (zones != null) return zones;
        List<Zone> out = new ArrayList<>();
        try (InputStream in = MusicZones.class.getResourceAsStream("/assets/nirncraft/fire/music_zones.json")) {
            if (in != null) {
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray a = o.getAsJsonArray("zones");
                for (JsonElement e : a) {
                    JsonObject z = e.getAsJsonObject();
                    out.add(new Zone(z.get("place").getAsString(), z.has("margin") ? z.get("margin").getAsInt() : 32,
                            z.has("radius") ? z.get("radius").getAsInt() : 0, z.get("event").getAsString()));
                }
            }
        } catch (Exception ex) {
            NirnCraft.LOGGER.error("Could not read the music zones", ex);
        }
        zones = out;
        return out;
    }

    /** The music event of the zone containing {@code pos}, or null. Overworld only. */
    @Nullable
    public static String eventAt(Level level, BlockPos pos) {
        if (level.dimension() != Level.OVERWORLD || !FireConfig.AFTERMATH_MUSIC.get()) return null;
        for (Zone z : zones()) {
            Place p = Places.byId(z.place()).orElse(null);
            if (p == null) continue;
            long r = (z.radiusOverride() > 0 ? z.radiusOverride() : Math.max(p.radius(), 56)) + z.margin();
            long dx = pos.getX() - p.x(), dz = pos.getZ() - p.z();
            if (dx * dx + dz * dz <= r * r) return z.event();
        }
        return null;
    }
}
