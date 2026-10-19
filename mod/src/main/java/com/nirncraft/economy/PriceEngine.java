package com.nirncraft.economy;

import com.nirncraft.crafting.RegionalGear;
import com.nirncraft.quest.faction.FactionStaff;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Causal supply-and-demand pricing engine (MS22 / T5).
 *
 * <p>Combines Skyrim Speech/perk barter curves ({@link Pricing}) with regional supply, multi-hop transport costs,
 * and L7 embargoes/tariffs from {@link TradeNetwork}:</p>
 * <ul>
 *   <li><b>Local surplus goods (0 hops)</b>: {@code 0.75x–0.85x} base price (e.g. saltrice &amp; kwama eggs in Morrowind,
 *   Nord mead &amp; silver in Skyrim, moon sugar in Elsweyr).</li>
 *   <li><b>Adjacent-region imports (1 hop)</b>: {@code 1.00x–1.15x} base price.</li>
 *   <li><b>Cross-province caravan/shipping imports (2–3 hops)</b>: {@code 1.25x–1.60x} base price.</li>
 *   <li><b>Contraband / embargoed goods</b>: {@code 1.60x–2.20x} base price (e.g. skooma &amp; moon sugar outside Elsweyr,
 *   unregulated raw ebony/Dwemer scrap under East Empire monopoly).</li>
 * </ul>
 */
public final class PriceEngine {
    /** Optional hook for social/racial/faction disposition modifiers (wired by social/faction systems). */
    private static volatile BiFunction<Player, LivingEntity, Float> attitudeHook = com.nirncraft.society.RaceAttitudes::priceMultiplier;

    private PriceEngine() {}

    /** Registers a social/faction disposition price multiplier hook (1.0 = neutral, <1.0 = friendly discount, >1.0 = hostile surcharge). */
    public static void setAttitudeHook(BiFunction<Player, LivingEntity, Float> hook) {
        attitudeHook = hook != null ? hook : com.nirncraft.society.RaceAttitudes::priceMultiplier;
    }

    /** Returns the social/racial attitude multiplier for {@code player} trading with {@code npc}. */
    public static float attitudeMultiplier(@Nullable Player player, @Nullable LivingEntity npc) {
        if (player == null || npc == null) return 1.0f;
        try {
            return Mth.clamp(attitudeHook.apply(player, npc), 0.75f, 1.50f);
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    /**
     * Computes the causal supply-and-demand price multiplier for {@code itemId} in {@code province} (and optional
     * settlement {@code placeId}). Accepts either {@code (placeId, province, itemId)} or {@code (province, placeId, itemId)}.
     */
    public static float supplyMultiplier(@Nullable String placeId, String province, String itemId) {
        if (itemId == null || itemId.isBlank()) return 1.0f;
        if (placeId != null && province != null && TradeNetwork.place(placeId) == null && TradeNetwork.place(province) != null) {
            String tmp = placeId;
            placeId = province;
            province = tmp;
        }
        String prov = TradeNetwork.canonProvince(province);

        // Contraband always commands a smuggling risk premium outside its legal homeland
        if (TradeNetwork.isContraband(itemId, prov)) {
            float tariff = TradeNetwork.tariffMultiplier(itemId, "elsweyr", prov, placeId);
            return Mth.clamp(tariff * 1.05f, 1.60f, 2.20f);
        }

        Optional<TradeNetwork.ImportRoute> routeOpt = TradeNetwork.routeFor(placeId, prov, itemId);
        if (routeOpt.isEmpty()) {
            return 1.0f;
        }

        TradeNetwork.ImportRoute route = routeOpt.get();
        if (route.isLocal()) {
            // Locally produced surplus good: 0.75x - 0.85x base price
            float tariff = TradeNetwork.tariffMultiplier(itemId, prov, prov, placeId);
            if (tariff > 1.05f) {
                // Local charter monopoly outside charter port
                return Mth.clamp(0.85f * tariff, 1.05f, 1.45f);
            }
            return placeId != null && TradeNetwork.localExports(placeId).contains(itemId) ? 0.78f : 0.82f;
        }

        int hops = route.hops();
        float hopMult = switch (hops) {
            case 1 -> 1.08f;
            case 2 -> 1.32f;
            default -> 1.48f;
        };
        float combined = hopMult * route.tariffMultiplier();
        if (hops == 1 && route.tariffMultiplier() <= 1.01f) {
            return Mth.clamp(combined, 1.00f, 1.15f);
        }
        if (route.tariffMultiplier() >= 1.45f) {
            return Mth.clamp(combined, 1.60f, 2.20f);
        }
        return Mth.clamp(combined, 1.25f, 1.60f);
    }

    /** Computes the supply-and-demand price multiplier for {@code stack} at world position {@code pos}. */
    public static float supplyMultiplier(@Nullable Level world, @Nullable BlockPos pos, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 1.0f;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return 1.0f;
        String province = "skyrim";
        String placeId = null;
        if (pos != null) {
            if (world != null) {
                Region region = EconomyLoot.regionAt(world, pos);
                province = RegionalGear.gearKey(region);
            }
            Optional<Place> near = Places.nearest(null, pos.getX(), pos.getZ(), 240);
            if (near.isPresent() && near.get().type().isSettlement()) {
                placeId = near.get().id();
            }
        }
        return supplyMultiplier(placeId, province, id.toString());
    }

    /** Regional base value of an item with base value {@code baseValue} at {@code placeId} in {@code province}. */
    public static int regionalValue(int baseValue, @Nullable String placeId, String province, String itemId) {
        if (baseValue <= 0) return 0;
        float mult = supplyMultiplier(placeId, province, itemId);
        return Math.max(1, Math.round(baseValue * mult));
    }

    /** Regional base value of {@code stack} with explicit {@code baseValue} in {@code province} / {@code placeId}. */
    public static int regionalValue(String province, @Nullable String placeId, ItemStack stack, int baseValue) {
        if (baseValue <= 0 || stack == null || stack.isEmpty()) return Math.max(0, baseValue);
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return baseValue;
        float mult = supplyMultiplier(placeId, province, id.toString());
        return Math.max(1, Math.round(baseValue * mult));
    }

    /** Regional base value of {@code stack} at {@code pos} in {@code world}. */
    public static int regionalValue(@Nullable Level world, @Nullable BlockPos pos, ItemStack stack) {
        int base = ItemValues.get(stack);
        if (base <= 0) return 0;
        float mult = supplyMultiplier(world, pos, stack);
        return Math.max(1, Math.round(base * mult));
    }

    /** Price of ONE item of {@code stack} when bought by {@code player} at their current location. */
    public static int buyPrice(Player player, ItemStack stack) {
        return buyPrice(player, stack, null);
    }

    /** Price of ONE item of {@code stack} when bought by {@code player} from {@code npc}. */
    public static int buyPrice(Player player, ItemStack stack, @Nullable LivingEntity npc) {
        Level world = npc != null ? npc.level() : player.level();
        BlockPos pos = npc != null ? npc.blockPosition() : player.blockPosition();
        int value = regionalValue(world, pos, stack);
        double mult = Pricing.buyMult(player) * attitudeMultiplier(player, npc);
        int price = (int) Math.max(1, Math.min(Integer.MAX_VALUE / 128, Math.ceil(value * mult)));
        if (npc != null) {
            int off = FactionStaff.discountFor(player, npc);
            if (off > 0) {
                int floor = (int) Math.ceil(sellPrice(player, stack, npc) * 1.1) + 1;
                price = Math.max(Math.min(price, floor), (int) Math.ceil(price * (100 - off) / 100.0));
            }
        }
        return price;
    }

    /** Gold paid for ONE item of {@code stack} when sold by {@code player} at their current location. */
    public static int sellPrice(Player player, ItemStack stack) {
        return sellPrice(player, stack, null);
    }

    /** Gold paid for ONE item of {@code stack} when sold by {@code player} to {@code npc}. */
    public static int sellPrice(Player player, ItemStack stack, @Nullable LivingEntity npc) {
        int base = ItemValues.get(stack);
        if (base <= 0) return 0;
        Level world = npc != null ? npc.level() : player.level();
        BlockPos pos = npc != null ? npc.blockPosition() : player.blockPosition();
        float supply = supplyMultiplier(world, pos, stack);
        float attitude = attitudeMultiplier(player, npc);
        // Friendly attitude improves sell price; hostile attitude reduces it slightly
        double mult = Pricing.sellMult(player) * supply / Math.max(0.8f, attitude);
        return (int) Math.max(1, Math.floor(base * mult));
    }
}
