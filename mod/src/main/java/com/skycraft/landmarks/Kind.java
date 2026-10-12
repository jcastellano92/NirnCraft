package com.skycraft.landmarks;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One landmark kind of {@code skycraft/landmarks/kinds.json}: a builder with a style, the biome families it belongs
 * to (with weights), and its footprint. Kinds are cheap data; many kinds share a builder and differ by style and
 * environment, and every instance still differs through its seeded parameters.
 *
 * @param builder  name of the geometry routine in {@code landmarks.build}
 * @param style    builder-specific variant ({@code headstone}, {@code cross}...)
 * @param radius   blocks from the centre the builder may touch (at most the tier's cap)
 * @param weights  family -> weight; the families are those of {@link LandCtx#families()}
 * @param needs    map conditions that must hold: coast, shore, high, mountains, cold
 * @param cast     residents spawned the first time a player comes near: {@code npc:<role>} or {@code mob:<entity id>}
 * @param secret   chance of a hidden cache (L and XL)
 * @param noun     what the name generator calls it ("Arch", "Spire"); empty for unnamed vignettes
 * @param note     category of the story note left at the site ({@code grave}, {@code traveller}...), or empty
 * @param sign     whether a named marker stands at the foot
 */
public record Kind(String id, Tier tier, String builder, String style, int radius, Map<String, Double> weights,
                   Set<String> provinces, Set<String> notProvinces, Set<String> needs, List<String> cast,
                   double secret, String noun, String note, boolean sign, int maxSpread, JsonObject params) {

    public Component displayName() {
        return Component.translatable("landmarks.skycraft.kind." + id);
    }

    /** Weight of this kind at a spot: 0 when the spot's province or conditions rule it out. */
    public double weight(LandCtx c) {
        if (!provinces.isEmpty() && !provinces.contains(c.province)) return 0;
        if (notProvinces.contains(c.province)) return 0;
        for (String n : needs) {
            boolean ok = switch (n) {
                case "coast" -> c.coast;
                case "shore" -> c.shore;
                case "high" -> c.high;
                case "mountains" -> c.mountains;
                case "cold" -> c.cold;
                default -> false;
            };
            if (!ok) return 0;
        }
        double w = weights.getOrDefault(c.base, 0.0);
        if (c.coast) w += weights.getOrDefault("coast", 0.0);
        if (c.shore) w += weights.getOrDefault("shore", 0.0);
        if (c.mountains && !c.base.equals("mountain")) w += weights.getOrDefault("mountain", 0.0);
        if (c.cold && !c.base.equals("tundra")) w += 0.7 * weights.getOrDefault("tundra", 0.0);
        if (c.high) w += weights.getOrDefault("high", 0.0);
        return w;
    }
}
