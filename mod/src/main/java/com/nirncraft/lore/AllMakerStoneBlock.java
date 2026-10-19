package com.nirncraft.lore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** An All-Maker stone: shaped like a Standing Stone, carved with a Skaal rune; touching it grants its blessing. */
public class AllMakerStoneBlock extends StandingStoneBlock {
    private final AllMakerStone stone;

    public AllMakerStoneBlock(AllMakerStone stone, Properties props) {
        super(stone.color, props);
        this.stone = stone;
    }

    public AllMakerStone stone() {
        return stone;
    }

    @Override
    protected void activate(ServerPlayer player, BlockPos base) {
        AllMakerStones.activate(player, stone, base);
    }
}
