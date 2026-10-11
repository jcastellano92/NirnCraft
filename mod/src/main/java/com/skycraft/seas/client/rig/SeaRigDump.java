package com.skycraft.seas.client.rig;

import com.skycraft.creatures.client.rig.CreatureDef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Exports the packed UV layouts of the seas creatures' rigs for {@code tools/textures/seas.py} (plain Java, like the
 * creatures module's {@code RigDump}):
 * <pre>
 *   javac -d build/seadump $(find mod/src/main/java/com/skycraft/creatures/client/rig -maxdepth 1 -name '*.java') \
 *       mod/src/main/java/com/skycraft/creatures/client/rig/defs/Defs.java ... SeaDefs.java SeaRigDump.java
 *   java -cp build/seadump com.skycraft.seas.client.rig.SeaRigDump tools/textures/rigs
 * </pre>
 * ({@code tools/textures/dump_sea_rigs.sh} does both.)
 */
public final class SeaRigDump {
    private SeaRigDump() {}

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "tools/textures/rigs");
        Files.createDirectories(out);
        for (Map.Entry<String, Supplier<CreatureDef>> e : SeaDefs.ALL.entrySet()) {
            Files.writeString(out.resolve(e.getKey() + ".json"), e.getValue().get().rig.toJson(), StandardCharsets.UTF_8);
        }
        System.out.println("Wrote " + SeaDefs.ALL.size() + " sea rig layouts to " + out.toAbsolutePath());
    }
}
