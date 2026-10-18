package com.skycraft.atmosphere;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightningMathTest {
    @Test void groundChannelIsPinnedToItsSelectedContactAndForksHaveParents() {
        var c = new LightningMath.Channel();
        c.build(12345, 100, 380, 200, 145.5f, 84.12f, 230.5f, true, 84.12f, 500);
        assertEquals(100, c.x[0][0]);
        assertEquals(380, c.y[0][0]);
        assertEquals(145.5f, c.x[0][32]);
        assertEquals(84.12f, c.y[0][32], 0.0001);
        assertEquals(230.5f, c.z[0][32]);
        for (int b = 1; b <= LightningMath.FORKS; b++) {
            int parent = b > 6 ? b - 6 : 0;
            boolean connected = false;
            for (int p = 0; p < (parent == 0 ? 33 : 17); p++)
                connected |= c.x[b][0] == c.x[parent][p] && c.y[b][0] == c.y[parent][p] && c.z[b][0] == c.z[parent][p];
            assertTrue(connected, "Disconnected fork " + b);
            for (int p = 1; p < 17; p++) assertTrue(c.y[b][p] >= 87.12f);
        }
    }
    @Test void cloudChannelsAreStableAndRemainInTheCloudHeightRange() {
        var a = new LightningMath.Channel();
        var b = new LightningMath.Channel();
        a.build(999, 0, 300, 0, 700, 340, -50, false, 280, 600);
        b.build(999, 0, 300, 0, 700, 340, -50, false, 280, 600);
        for (int branch = 0; branch <= LightningMath.FORKS; branch++) {
            assertArrayEquals(a.x[branch], b.x[branch]);
            for (int p = 0; p < (branch == 0 ? 33 : 17); p++) {
                assertTrue(a.y[branch][p] >= 280);
                assertTrue(a.y[branch][p] <= 600);
            }
        }
    }
    @Test void lightAndGeometryShareConnectionAndRepeatStrokesOutliveEightTicks() {
        boolean lateStroke = false;
        for (long sample = 0; sample < 100; sample++) {
            long seed = sample * 0x9E3779B97F4A7C15L;
            float connection = LightningMath.leaderTicks(seed);
            float life = LightningMath.lifetime(seed);
            assertEquals(1f, LightningMath.progress(seed, connection));
            assertEquals(1f, LightningMath.pulse(seed, connection, life), 0.0001);
            assertTrue(LightningMath.pulse(seed, connection - 0.1f, life) < 0.18f);
            assertEquals(0, LightningMath.pulse(seed, life, life));
            boolean late = false;
            for (float t = 0; t < life; t += 0.02f) {
                float pulse = LightningMath.pulse(seed, t, life);
                assertTrue(Float.isFinite(pulse) && pulse >= 0 && pulse <= 1);
                if (t > 8 && pulse > 0.1f) late = true;
            }
            lateStroke |= late;
        }
        assertTrue(lateStroke, "Long discharges must retain their late channel/light pulses");
    }
    @Test void cloudBridgeKeepsAttachmentsAcrossDifferentTowerHeights() {
        var c = new LightningMath.Channel();
        c.build(123, 0, 460, 0, 800, 180, 50, false, 430, 600);
        assertEquals(460, c.y[0][0]);
        assertEquals(180, c.y[0][32]);
        c.build(123, 0, 460, 0, 800, 720, 50, false, 430, 600);
        assertEquals(720, c.y[0][32]);
    }
    @Test void targetHeuristicBalancesProminenceDistanceAndCloudClearance() {
        assertTrue(LightningMath.targetScore(100, 70, 40, 300) > LightningMath.targetScore(70, 70, 0, 300));
        assertTrue(LightningMath.targetScore(100, 70, 0, 300) > LightningMath.targetScore(100, 70, 100, 300));
        assertTrue(LightningMath.targetScore(310, 70, 0, 300) < LightningMath.targetScore(290, 70, 0, 300));
    }
}
