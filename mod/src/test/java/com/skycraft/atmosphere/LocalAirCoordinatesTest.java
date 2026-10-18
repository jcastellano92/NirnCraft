package com.skycraft.atmosphere;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalAirCoordinatesTest {
    @Test void stationaryWorldBoundaryNeverMovesWithTickOrFrameCamera() {
        double boundary = 513.25;
        for (double tickCamera : new double[]{-0.01, 0.01, 255.99, 256.01, 511.99, 512.01}) {
            int anchor = LocalAirCoordinates.anchor(tickCamera);
            int packet = LocalAirCoordinates.encode(boundary - anchor);
            for (double frameCamera : new double[]{tickCamera - 0.4, tickCamera, tickCamera + 0.4}) {
                double frameRelative = LocalAirCoordinates.decode(packet) + anchor - frameCamera;
                assertEquals(boundary, frameRelative + frameCamera, 1e-10);
            }
        }
    }

    @Test void movingSourcesKeepOneGlobalQuarterBlockQuantizationAcrossCells() {
        for (double position : new double[]{-257.11, -0.11, 0.11, 255.89, 256.11}) {
            double expected = Math.round(position * 4) * 0.25;
            for (double camera : new double[]{position - 30, position, position + 30}) {
                int anchor = LocalAirCoordinates.anchor(camera);
                assertEquals(expected, LocalAirCoordinates.decode(LocalAirCoordinates.encode(position-anchor)) + anchor);
            }
        }
    }
}
