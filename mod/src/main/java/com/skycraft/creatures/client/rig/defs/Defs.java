package com.skycraft.creatures.client.rig.defs;

import com.skycraft.creatures.client.rig.CreatureDef;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every rig-model creature definition by name. The name is also the model layer path and the texture base name
 * ({@code textures/entity/creatures/<name>.png}). Definitions are built once and cached.
 */
public final class Defs {
    public static final Map<String, Supplier<CreatureDef>> ALL = new LinkedHashMap<>();
    private static final Map<String, CreatureDef> CACHE = new HashMap<>();

    static {
        SkyrimBeasts.register();
        SkyrimMonsters.register();
        SkyrimFauna.register();
        SkyrimCreatures.register();
        MainlandDefs.register();
        WesternDefs.register();
        DaedraDefs.register();
        Dragons.register();
        Costumes.register();
    }

    private Defs() {}

    static void reg(String name, Supplier<CreatureDef> def) {
        if (ALL.put(name, def) != null) throw new IllegalStateException("Duplicate creature def " + name);
    }

    public static synchronized CreatureDef get(String name) {
        CreatureDef def = CACHE.get(name);
        if (def == null) {
            Supplier<CreatureDef> s = ALL.get(name);
            if (s == null) throw new IllegalArgumentException("Unknown creature def " + name);
            def = s.get();
            CACHE.put(name, def);
        }
        return def;
    }
}
