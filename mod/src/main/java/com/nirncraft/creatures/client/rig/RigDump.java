package com.nirncraft.creatures.client.rig;

import com.nirncraft.creatures.client.rig.defs.Defs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Standalone exporter of every creature rig's packed UV layout, for the texture painter
 * ({@code tools/textures/rigpaint.py}). The rig kit has no Minecraft dependencies, so this runs with plain javac:
 * <pre>
 *   javac -d build/rigdump $(find mod/src/main/java/com/nirncraft/creatures/client/rig -name '*.java')
 *   java -cp build/rigdump com.nirncraft.creatures.client.rig.RigDump tools/textures/rigs
 * </pre>
 * ({@code tools/textures/dump_rigs.sh} does both.)
 */
public final class RigDump {
    private RigDump() {}

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "tools/textures/rigs");
        Path anims = args.length > 1 ? Path.of(args[1]) : null;
        Files.createDirectories(out);
        if (anims != null) Files.createDirectories(anims);
        int n = 0;
        for (Map.Entry<String, Supplier<CreatureDef>> e : Defs.ALL.entrySet()) {
            CreatureDef def = e.getValue().get();
            Files.writeString(out.resolve(e.getKey() + ".json"), def.rig.toJson(), StandardCharsets.UTF_8);
            if (anims != null) Files.writeString(anims.resolve(e.getKey() + ".json"), animsJson(def), StandardCharsets.UTF_8);
            n++;
        }
        System.out.println("Wrote " + n + " rig layouts to " + out.toAbsolutePath());
    }

    /** Animations as JSON (for the offline preview renderer, tools/textures/rigview.py). */
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
