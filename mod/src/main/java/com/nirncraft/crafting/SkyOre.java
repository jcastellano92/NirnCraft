package com.nirncraft.crafting;

/**
 * Skyrim ores that don't exist in vanilla. Each has a stone and a deepslate vein block, a raw ore item dropped by the
 * vein and a smelted ingot (Skyrim's "Refined Moonstone" / "Refined Malachite" are ingots too).
 * Skyrim iron and gold use the vanilla ores.
 */
public enum SkyOre {
    CORUNDUM("corundum", "corundum_ingot", 1, 3, 10, 40, ToolNeed.STONE),
    ORICHALCUM("orichalcum", "orichalcum_ingot", 2, 5, 13, 45, ToolNeed.IRON),
    MOONSTONE("moonstone", "refined_moonstone", 3, 6, 18, 75, ToolNeed.IRON),
    MALACHITE("malachite", "refined_malachite", 3, 6, 25, 100, ToolNeed.IRON),
    QUICKSILVER("quicksilver", "quicksilver_ingot", 2, 5, 15, 60, ToolNeed.IRON),
    EBONY("ebony", "ebony_ingot", 4, 8, 40, 150, ToolNeed.DIAMOND),
    SILVER("silver", "silver_ingot", 2, 5, 25, 50, ToolNeed.IRON);

    /** Minimum pickaxe tier needed for drops. */
    public enum ToolNeed { STONE, IRON, DIAMOND }

    public final String id;
    public final String ingotId;
    public final int xpMin;
    public final int xpMax;
    public final int rawValue;
    public final int ingotValue;
    public final ToolNeed toolNeed;

    SkyOre(String id, String ingotId, int xpMin, int xpMax, int rawValue, int ingotValue, ToolNeed toolNeed) {
        this.id = id;
        this.ingotId = ingotId;
        this.xpMin = xpMin;
        this.xpMax = xpMax;
        this.rawValue = rawValue;
        this.ingotValue = ingotValue;
        this.toolNeed = toolNeed;
    }

    public String oreId() {
        return id + "_ore";
    }

    public String deepslateOreId() {
        return "deepslate_" + id + "_ore";
    }

    public String rawId() {
        return "raw_" + id;
    }
}
