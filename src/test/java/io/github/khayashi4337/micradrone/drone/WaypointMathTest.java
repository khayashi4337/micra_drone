package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WaypointMathTest {

    @Test
    void horizontalDistanceIgnoresYAndUsesPythagoras() {
        // A 3-4-5 right triangle on the X/Z plane.
        assertEquals(5.0, WaypointMath.horizontalDistance(0, 0, 3, 4), 1e-9);
    }

    @Test
    void horizontalDistanceOfCoincidingPointsIsZero() {
        assertEquals(0.0, WaypointMath.horizontalDistance(10, 20, 10, 20), 1e-9);
    }

    @Test
    void bearingYawPointsSouthWhenTargetIsDueSouth() {
        // +Z is south, and yaw 0 is south (verified against Entity.calculateViewVector/FishingHook).
        assertEquals(0f, WaypointMath.bearingYawDegrees(0, 0, 0, 10), 1e-6);
    }

    @Test
    void bearingYawPointsWestWhenTargetIsDueWest() {
        assertEquals(90f, WaypointMath.bearingYawDegrees(0, 0, -10, 0), 1e-6);
    }

    @Test
    void bearingYawPointsNorthWhenTargetIsDueNorth() {
        assertEquals(180f, WaypointMath.bearingYawDegrees(0, 0, 0, -10), 1e-6);
    }

    @Test
    void bearingYawPointsEastWhenTargetIsDueEast() {
        assertEquals(270f, WaypointMath.bearingYawDegrees(0, 0, 10, 0), 1e-6);
    }

    @Test
    void bearingYawIsRelativeToTheFromPointNotTheOrigin() {
        // Same due-south relationship, just translated away from (0,0) - must not accidentally
        // measure the bearing from the world origin instead of "from".
        assertEquals(0f, WaypointMath.bearingYawDegrees(100, 200, 100, 250), 1e-6);
    }

    @Test
    void bearingYawOfCoincidingPointsIsZeroNotNaN() {
        assertEquals(0f, WaypointMath.bearingYawDegrees(5, 5, 5, 5), 1e-6);
    }

    @Test
    void bearingYawStaysInZeroTo360ForIntermediateDirections() {
        // South-east-ish: dx > 0, dz > 0 -> between south(0) and east(270) going the short way,
        // i.e. in (270, 360) since yaw increases clockwise from south through west/north/east.
        float yaw = WaypointMath.bearingYawDegrees(0, 0, 10, 10);
        org.junit.jupiter.api.Assertions.assertTrue(yaw >= 0 && yaw < 360, "yaw out of range: " + yaw);
        assertEquals(315f, yaw, 1e-6);
    }
}
