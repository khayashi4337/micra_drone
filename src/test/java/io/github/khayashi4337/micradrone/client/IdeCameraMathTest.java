package io.github.khayashi4337.micradrone.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IdeCameraMathTest {

    private static final double FOV = 70.0;
    private static final double WIDESCREEN = 16.0 / 9.0;

    private static double tanHalf(double fovDegrees) {
        return Math.tan(Math.toRadians(fovDegrees) / 2.0);
    }

    @Test
    void biggerPlotsNeedAHigherCamera() {
        double small = IdeCameraMath.topDownPose(3, FOV, WIDESCREEN).height();
        double large = IdeCameraMath.topDownPose(9, FOV, WIDESCREEN).height();
        assertTrue(large > small);
    }

    @Test
    void plotFitsTheScreenHeightAndTheRightHalfWidth() {
        for (int size = 3; size <= 9; size++) {
            IdeCameraMath.CameraPose pose = IdeCameraMath.topDownPose(size, FOV, WIDESCREEN);
            double visibleHeight = 2.0 * pose.height() * tanHalf(FOV);
            double visibleRightHalfWidth = pose.height() * tanHalf(FOV) * WIDESCREEN;
            assertTrue(visibleHeight >= size + 2 - 1e-9, "height fit failed for size " + size);
            assertTrue(visibleRightHalfWidth >= size + 2 - 1e-9, "right-half width fit failed for size " + size);
        }
    }

    @Test
    void westOffsetIsAQuarterOfTheFullVisibleWidth() {
        IdeCameraMath.CameraPose pose = IdeCameraMath.topDownPose(5, FOV, WIDESCREEN);
        double fullVisibleWidth = 2.0 * pose.height() * tanHalf(FOV) * WIDESCREEN;
        assertEquals(fullVisibleWidth / 4.0, pose.westOffset(), 1e-9);
    }

    @Test
    void squareWindowIsConstrainedByTheRightHalfWidthNotTheHeight() {
        // At aspect 1.0 the right half is half as wide as the screen is tall, so the width
        // constraint needs exactly twice the height the vertical fit alone would.
        IdeCameraMath.CameraPose pose = IdeCameraMath.topDownPose(5, FOV, 1.0);
        double fitFullHeight = (5 + 2) / (2.0 * tanHalf(FOV));
        assertEquals(fitFullHeight * 2.0, pose.height(), 1e-9);
    }
}
