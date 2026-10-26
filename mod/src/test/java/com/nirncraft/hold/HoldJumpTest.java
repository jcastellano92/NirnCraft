package com.nirncraft.hold;

import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** An arrival hold needs a discontinuity: a big move with a teleport behind it, never speed (PlayerHold.isJump). */
class HoldJumpTest {
    static final double FAR = (PlayerHold.JUMP_BLOCKS + 14) * (PlayerHold.JUMP_BLOCKS + 14);

    @Test
    void flightIsNeverAnArrival() {
        // the owner's 10-26 flight: 78 blocks in one (lagging) server tick, no teleport
        assertFalse(PlayerHold.isJump(FAR, false, false));
    }

    @Test
    void aTeleportFarAwayIs() {
        assertTrue(PlayerHold.isJump(FAR, true, false));   // the server sent a position (the mod's travel, /tp, a pearl)
        assertTrue(PlayerHold.isJump(FAR, false, true));   // a teleport event named it
        assertFalse(PlayerHold.isJump(30 * 30, true, true));  // a short one: no hold
    }

    @Test
    void thePositionPacketIsWhatCounts() {
        var pkt = ClientboundPlayerPositionPacket.of(1, new PositionMoveRotation(new Vec3(1, 2, 3), Vec3.ZERO, 0f, 0f), Set.of());
        assertTrue(HoldTeleports.isTeleport(pkt));
        assertFalse(HoldTeleports.isTeleport("not a packet"));
    }
}
