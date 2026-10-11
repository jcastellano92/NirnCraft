package com.skycraft.realms.client.rig;

import com.skycraft.creatures.client.rig.Anim;
import com.skycraft.creatures.client.rig.CreatureDef;
import com.skycraft.creatures.client.rig.defs.Defs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Standalone exporter of the realm creatures' packed UV layouts (and animations, for the rigview previews), like
 * {@code creatures.client.rig.RigDump} but for {@link RealmDefs} only. Plain javac/java: see
 * {@code tools/textures/dump_realm_rigs.sh}.
 */
public final class RealmRigDump {
    private RealmRigDump() {}

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "tools/textures/rigs");
        Path anims = args.length > 1 ? Path.of(args[1]) : null;
        Files.createDirectories(out);
        if (anims != null) Files.createDirectories(anims);
        RealmDefs.register();
        int n = 0;
        for (String name : RealmDefs.NAMES) {
            CreatureDef def = Defs.get(name);
            Files.writeString(out.resolve(name + ".json"), def.rig.toJson(), StandardCharsets.UTF_8);
            if (anims != null) Files.writeString(anims.resolve(name + ".json"), animsJson(def), StandardCharsets.UTF_8);
            n++;
        }
        System.out.println("Wrote " + n + " realm rig layouts to " + out.toAbsolutePath());
    }

    /** Animations as JSON (the same format as RigDump's, read by tools/textures/rigview.py). */
    private static String animsJson(CreatureDef def) {
        StringBuilder sb = new StringBuilder("{");
        boolean firstAnim = true;
        for (Map.Entry<String, Anim> e : def.anims.entrySet()) {
            if (!firstAnim) sb.append(',');
            firstAnim = false;
            Anim a = e.getValue();
            sb.append('"').append(e.getKey()).append("\":{\"length\":").append(a.length).append(",\"loop\":").append(a.loop)
                    .append(",\"channels\":[");
            boolean first = true;
            for (Anim.Channel c : a.channels) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"bone\":\"").append(c.bone).append("\",\"target\":\"").append(c.target).append("\",\"smooth\":")
                        .append(c.smooth).append(",\"times\":[");
                for (int i = 0; i < c.times.length; i++) sb.append(i > 0 ? "," : "").append(c.times[i]);
                sb.append("],\"values\":[");
                for (int i = 0; i < c.values.length; i++) {
                    float[] v = c.values[i];
                    sb.append(i > 0 ? "," : "").append('[').append(v[0]).append(',').append(v[1]).append(',').append(v[2]).append(']');
                }
                sb.append("]}");
            }
            sb.append("]}");
        }
        return sb.append('}').toString();
    }
}
