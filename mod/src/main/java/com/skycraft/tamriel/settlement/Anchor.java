package com.skycraft.tamriel.settlement;

import net.minecraft.core.BlockPos;

/**
 * One recorded point of a settlement.
 *
 * @param owner the building it belongs to: an owner label such as {@code breezehome} or {@code inn}, plus the
 *              building's index ({@code smithy#4}); used for container ownership and the house-for-sale id
 */
public record Anchor(AnchorType type, BlockPos pos, String owner) {
}
