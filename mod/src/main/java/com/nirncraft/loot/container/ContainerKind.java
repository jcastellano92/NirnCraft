package com.nirncraft.loot.container;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The kinds of NirnCraft loot container (docs/CONTAINERS.md section 2). Each kind is one block with its own models;
 * the {@link ContainerStyle} of the styled kinds is a property of that block.
 *
 * <p>{@code stashKg} is the balance rule of world containers (section 7): on top of what its table generated, a world
 * container accepts this much weight of a player's own things. Player-placed containers have no limit beyond the
 * bulk store's engineering caps, like every other NirnCraft storage.</p>
 */
public enum ContainerKind {
    CHEST("loot_chest", true, 150f, Block.box(1, 0, 1, 15, 14, 15)),
    GRAND_CHEST("grand_chest", true, 250f, Block.box(0, 0, 1, 16, 15, 15)),
    STRONGBOX("strongbox", false, 20f, Block.box(3, 0, 4, 13, 7, 12)),
    BARREL("loot_barrel", false, 100f, Block.box(0, 0, 0, 16, 16, 16)),
    SACK("loot_sack", false, 40f, Block.box(3, 0, 3, 13, 11, 13)),
    URN("loot_urn", true, 25f, Block.box(3, 0, 3, 13, 14, 13)),
    BOOKSHELF("loot_bookshelf", true, 30f, Block.box(0, 0, 0, 16, 16, 16)),
    REMAINS("remains", false, 15f, Block.box(1, 0, 1, 15, 4, 15));

    public static final ContainerKind[] VALUES = values();

    public final String id;
    public final boolean styled;
    public final float stashKg;
    public final VoxelShape shape;

    ContainerKind(String id, boolean styled, float stashKg, VoxelShape shape) {
        this.id = id;
        this.styled = styled;
        this.stashKg = stashKg;
        this.shape = shape;
    }

    @Nullable
    public static ContainerKind byId(@Nullable String id) {
        if (id == null) return null;
        String clean = id.toLowerCase(Locale.ROOT);
        for (ContainerKind k : VALUES) if (k.id.equals(clean) || k.name().toLowerCase(Locale.ROOT).equals(clean)) return k;
        return null;
    }

    /** Only books and notes go on a shelf. */
    public boolean booksOnly() {
        return this == BOOKSHELF;
    }

    public SoundEvent openSound() {
        return switch (this) {
            case BARREL -> SoundEvents.BARREL_OPEN;
            case SACK -> SoundEvents.BUNDLE_REMOVE_ONE;
            case URN -> SoundEvents.DECORATED_POT_INSERT;
            case BOOKSHELF -> SoundEvents.BOOK_PAGE_TURN;
            case REMAINS -> SoundEvents.SKELETON_STEP;
            case STRONGBOX -> SoundEvents.IRON_TRAPDOOR_OPEN;
            default -> SoundEvents.CHEST_OPEN;
        };
    }

    public SoundEvent closeSound() {
        return switch (this) {
            case BARREL -> SoundEvents.BARREL_CLOSE;
            case SACK -> SoundEvents.BUNDLE_INSERT;
            case URN -> SoundEvents.DECORATED_POT_HIT;
            case BOOKSHELF -> SoundEvents.BOOK_PUT;
            case REMAINS -> SoundEvents.SKELETON_STEP;
            case STRONGBOX -> SoundEvents.IRON_TRAPDOOR_CLOSE;
            default -> SoundEvents.CHEST_CLOSE;
        };
    }

    /**
     * The kind a world-placed container becomes (section 9): the table says what it is (a boss hoard, a strongbox, a
     * satchel, an urn, a shelf), else the block the builder placed (a barrel stays a barrel; in a tomb built by Nords,
     * Velothi, Dwemer or Ayleids the small dungeon containers are urns).
     */
    public static ContainerKind forPlacement(@Nullable String table, @Nullable BlockState source, @Nullable ContainerStyle style) {
        String t = table == null ? "" : table;
        if (t.contains("/boss") || t.contains("dragon_lair") || t.contains("uniques/") || t.contains("jarl")) return GRAND_CHEST;
        if (t.contains("strongbox/")) return STRONGBOX;
        if (t.contains("misc/satchel") || t.contains("misc/knapsack") || t.contains("misc/coin_purse")) return SACK;
        if (t.contains("misc/urn")) return URN;
        if (t.contains("bookshelf/") || t.contains("household_books/")) return BOOKSHELF;
        if (t.contains("corpse/")) return REMAINS;
        boolean barrel = source != null && source.is(net.minecraft.world.level.block.Blocks.BARREL);
        if (t.contains("dungeon/minor") && barrel && style != null && style != ContainerStyle.RUSTIC && style != ContainerStyle.DESERT
                && style != ContainerStyle.IMPERIAL) return URN;
        return barrel ? BARREL : CHEST;
    }
}
