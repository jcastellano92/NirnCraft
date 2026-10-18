package com.skycraft.world.ocean;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/** Server-authoritative outer-ocean travel; geography itself is fixed on both logical sides. */
public final class OceanWorldConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.IntValue HALF_WIDTH,BUFFER;
    static {
        var b=new ModConfigSpec.Builder();b.push("outerOcean");
        ENABLED=b.comment("Wrap overworld voyages across the far ocean edge; never affects realm dimensions or regenerates saved chunks.")
                .define("wrapEnabled",true);
        HALF_WIDTH=b.comment("Symmetric east/west and north/south seam, in blocks. Default remains safely inside Minecraft's hard boundary and aligns the regional weather field.")
                .defineInRange("halfWidth",OceanWrapMath.DEFAULT_HALF_WIDTH,OceanWrapMath.MIN_HALF_WIDTH,OceanWrapMath.MAX_HALF_WIDTH);
        BUFFER=b.comment("Begin asynchronous destination prewarm this far before the seam. Arrival retains exact overshoot, altitude, facing and velocity.")
                .defineInRange("arrivalBuffer",1024,64,4096);
        b.pop();SPEC=b.build();
    }
    private OceanWorldConfig() {}
    public static void register() { com.skycraft.Skycraft.config(ModConfig.Type.SYNCED,SPEC,"skycraft-ocean-server.toml"); }
    private static <T>T get(ModConfigSpec.ConfigValue<T> value) {
        try{return value.get();}catch(IllegalStateException e){return value.getDefault();}
    }
    static boolean enabled(){return get(ENABLED);}
    static OceanWrapMath.Bounds bounds(){return new OceanWrapMath.Bounds(get(HALF_WIDTH),get(BUFFER));}
}
