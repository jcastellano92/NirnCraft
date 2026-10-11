package com.skycraft.loot;

import com.skycraft.economy.ItemValues;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Legendary unique weapons of Tamriel: one-of-a-kind named arms, each with its lore, a fixed enchantment, Legendary-ish
 * tempering and a value far above its material. They are defined here, centrally, and placed in the world by id:
 * <ul>
 *   <li>the explorers' sites put {@code skycraft:uniques/<id>} (a loot table) or {@link #create(String)} in a chest,
 *   weapon rack or on a corpse;</li>
 *   <li>a unique with a {@code place} is also handed out by {@code skycraft:place_collectibles} in that named
 *   dungeon's chests, and one with only a province turns up (rarely) in that province's boss chests
 *   ({@code skycraft:random_unique}).</li>
 * </ul>
 * Each character finds each unique once (with Lootr every player finds their own). Names and lore: lang keys
 * {@code unique.skycraft.<id>} and {@code unique.skycraft.<id>.lore}.
 */
public final class LegendaryUniques {
    public static final String COLLECTION = "legendary_uniques";

    private LegendaryUniques() {}

    /**
     * @param province gear key of the province it belongs to
     * @param place    named place ({@code places/*.json}) it waits in, or null (explorers' sites / random finds)
     */
    public record Unique(String id, String base, String province, @Nullable String place, int minLevel, int value,
                         int quality, Map<String, Integer> enchants) {}

    private static final Map<String, Unique> ALL = new LinkedHashMap<>();

    private static void def(String id, String base, String province, @Nullable String place, int minLevel, int value, int quality, Object... enchants) {
        Map<String, Integer> e = new LinkedHashMap<>();
        for (int i = 0; i < enchants.length; i += 2) e.put((String) enchants[i], (Integer) enchants[i + 1]);
        ALL.put(id, new Unique(id, base, province, place, minLevel, value, quality, e));
    }

    static {
        // Skyrim
        def("red_eagles_fury", "skycraft:ancient_nord_sword", "skyrim", "red_eagle_redoubt", 5, 450, 1, "minecraft:fire_aspect", 1);
        def("bloodthorn", "skycraft:ancient_nord_dagger", "skyrim", "hag_rock_redoubt", 6, 400, 1, "minecraft:sharpness", 1, "minecraft:looting", 1);
        def("bolars_oathblade", "skycraft:steel_greatsword", "skyrim", "valtheim_towers", 8, 600, 2, "minecraft:sharpness", 2);
        def("nettlebane", "skycraft:orcish_dagger", "skyrim", "dead_crone_rock", 10, 650, 2, "minecraft:bane_of_arthropods", 3, "minecraft:sharpness", 1);
        def("gauldur_blackblade", "skycraft:ancient_nord_greatsword", "skyrim", "geirmunds_hall", 12, 900, 2, "minecraft:smite", 3, "minecraft:sharpness", 1);
        def("ghostblade", "skycraft:nordic_greatsword", "skyrim", "ironbind_barrow", 18, 1100, 2, "minecraft:smite", 2, "minecraft:knockback", 1);
        def("the_pale_blade", "skycraft:ancient_nord_sword", "skyrim", "lost_valkygg", 18, 1200, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        def("kahvozeins_fang", "skycraft:dwarven_dagger", "skyrim", "skyborn_altar", 22, 1500, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        // Cyrodiil
        def("welkynd_sliver", "skycraft:elven_dagger", "cyrodiil", null, 14, 900, 2, "minecraft:sharpness", 2, "minecraft:smite", 2);
        def("goldbrand", "skycraft:glass_sword", "cyrodiil", null, 30, 3500, 4, "minecraft:fire_aspect", 2, "minecraft:sharpness", 3);
        def("umbra", "skycraft:ebony_sword", "cyrodiil", null, 34, 4000, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        // Morrowind and Solstheim
        def("hopesfire", "skycraft:silver_sword", "morrowind", null, 20, 1600, 3, "minecraft:smite", 3, "minecraft:fire_aspect", 1);
        def("trueflame", "skycraft:chitin_sword", "morrowind", null, 26, 2600, 4, "minecraft:fire_aspect", 2, "minecraft:sharpness", 2);
        def("keening", "skycraft:dwarven_dagger", "morrowind", null, 40, 5000, 5, "minecraft:sharpness", 5, "minecraft:looting", 3);
        def("sunder", "skycraft:dwarven_warhammer", "morrowind", null, 40, 5000, 5, "minecraft:sharpness", 4, "minecraft:knockback", 2);
        def("riekling_spear_of_thirsk", "skycraft:stalhrim_sword", "solstheim", null, 40, 3000, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        // Black Marsh
        def("hist_fang", "skycraft:argonian_dagger", "black_marsh", null, 16, 1100, 2, "minecraft:bane_of_arthropods", 2, "minecraft:sharpness", 2);
        def("shadowscale_kris", "skycraft:argonian_sword", "black_marsh", null, 24, 1900, 3, "minecraft:sharpness", 3, "minecraft:looting", 1);
        def("marsh_lords_mace", "skycraft:argonian_mace", "black_marsh", null, 30, 2400, 3, "minecraft:smite", 3, "minecraft:knockback", 2);
        // Elsweyr
        def("claw_of_alkosh", "skycraft:khajiit_dagger", "elsweyr", null, 16, 1200, 2, "minecraft:sharpness", 2, "minecraft:fire_aspect", 1);
        def("moonblade_of_the_mane", "skycraft:khajiit_sword", "elsweyr", null, 26, 2200, 3, "minecraft:sharpness", 3, "minecraft:smite", 2);
        def("rajhins_shadow", "skycraft:khajiit_bow", "elsweyr", null, 30, 2500, 3, "minecraft:power", 3, "minecraft:punch", 1);
        // Valenwood
        def("green_pact_fang", "skycraft:bosmer_dagger", "valenwood", null, 14, 1000, 2, "minecraft:sharpness", 2, "minecraft:looting", 1);
        def("jaqspur_longbow", "skycraft:bosmer_bow", "valenwood", null, 22, 1800, 3, "minecraft:power", 3, "minecraft:infinity", 1);
        def("wild_hunt_axe", "skycraft:bosmer_war_axe", "valenwood", null, 30, 2300, 3, "minecraft:sharpness", 3, "minecraft:fire_aspect", 1);
        // Summerset
        def("alinor_dawnblade", "skycraft:elven_sword", "summerset", null, 22, 1900, 3, "minecraft:smite", 3, "minecraft:sharpness", 2);
        def("psijic_longbow", "skycraft:glass_bow", "summerset", null, 30, 3000, 4, "minecraft:power", 4, "minecraft:flame", 1);
        def("sunbird_greatsword", "skycraft:elven_greatsword", "summerset", null, 34, 3400, 4, "minecraft:sharpness", 4, "minecraft:fire_aspect", 1);
        // Hammerfell
        def("sword_of_the_ansei", "skycraft:redguard_sword", "hammerfell", null, 18, 1500, 3, "minecraft:sharpness", 3);
        def("hews_bane_scimitar", "skycraft:redguard_greatsword", "hammerfell", null, 26, 2200, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("shehai_blade", "skycraft:yokudan_sword", "hammerfell", null, 34, 3600, 4, "minecraft:sharpness", 4, "minecraft:smite", 2);
        // High Rock and Orsinium
        def("shornhelm_blade", "skycraft:breton_sword", "high_rock", null, 14, 1100, 2, "minecraft:sharpness", 2, "minecraft:smite", 1);
        def("direnni_warhammer", "skycraft:breton_warhammer", "high_rock", null, 24, 2000, 3, "minecraft:smite", 3, "minecraft:knockback", 2);
        def("chrysamere", "skycraft:breton_greatsword", "high_rock", null, 36, 4200, 5, "minecraft:sharpness", 4, "minecraft:fire_aspect", 1);
        def("gortwogs_hammer", "skycraft:orcish_warhammer", "orsinium", null, 20, 1700, 3, "minecraft:sharpness", 2, "minecraft:knockback", 2);
        def("malacaths_cleaver", "skycraft:orcish_battleaxe", "orsinium", null, 28, 2600, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        // Akavir and Atmora
        def("dragonguard_katana", "skycraft:akaviri_sword", "akavir", null, 28, 3000, 4, "minecraft:sharpness", 4);
        def("atmoran_frostaxe", "skycraft:atmoran_war_axe", "atmora", null, 24, 2100, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
    }

    public static List<Unique> all() {
        return Collections.unmodifiableList(new ArrayList<>(ALL.values()));
    }

    @Nullable
    public static Unique get(String id) {
        return ALL.get(id);
    }

    /** The unique as an item (empty for an unknown id or a missing base item). */
    public static ItemStack create(String id) {
        Unique u = ALL.get(id);
        if (u == null) return ItemStack.EMPTY;
        Item base = ForgeRegistries.ITEMS.getValue(new ResourceLocation(u.base));
        if (base == null || base == Items.AIR) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(base);
        for (Map.Entry<String, Integer> e : u.enchants.entrySet()) {
            Enchantment ench = ForgeRegistries.ENCHANTMENTS.getValue(new ResourceLocation(e.getKey()));
            if (ench != null) s.enchant(ench, e.getValue());
        }
        s.setHoverName(Component.translatable("unique.skycraft." + id).withStyle(ChatFormatting.GOLD).withStyle(s2 -> s2.withItalic(false)));
        CompoundTag display = s.getOrCreateTagElement("display");
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.translatable("unique.skycraft." + id + ".lore")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC))));
        display.put("Lore", lore);
        CompoundTag tag = s.getOrCreateTag();
        tag.putString(Collectibles.NBT, COLLECTION + "/" + id);
        tag.putInt(ItemValues.VALUE_NBT, u.value);
        if (u.quality > 0) tag.putInt(ItemValues.QUALITY_NBT, u.quality);
        return s;
    }
}
