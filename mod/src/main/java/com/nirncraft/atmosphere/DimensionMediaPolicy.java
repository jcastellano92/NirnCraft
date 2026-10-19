package com.nirncraft.atmosphere;

/** Local media is portable; terrestrial weather and celestial state belong only to the Overworld. */
public record DimensionMediaPolicy(boolean localMedia,boolean terrestrialWeather,boolean celestial) {
    public static DimensionMediaPolicy of(String dimension) {
        return switch(dimension) {
            case "minecraft:overworld" -> new DimensionMediaPolicy(true,true,true);
            case "minecraft:the_nether","minecraft:the_end","nirncraft:shivering_isles","nirncraft:apocrypha",
                    "nirncraft:soul_cairn","nirncraft:coldharbour" -> new DimensionMediaPolicy(true,false,false);
            default -> new DimensionMediaPolicy(false,false,false);
        };
    }
}
