package io.github.khayashi4337.micradrone.drone;

/**
 * Pure bearing/distance math for the waypoint compass - kept Minecraft-free (like PlotGeometry) so
 * the yaw formula can be unit-tested without a running game.
 *
 * <p>Yaw convention verified against vanilla's own direction formula (decompiled
 * {@code Entity.calculateViewVector}: {@code dirX = -sin(yawRad), dirZ = cos(yawRad)}, the same
 * one {@code FishingHook}'s cast-direction constructor uses - see
 * {@code DroneControllerBlockEntity#resolveAngler}'s javadoc for the yaw-0-is-south finding this
 * derives from): yaw 0 = south (+Z), 90 = west (-X), 180 = north (-Z), 270 = east (+X), increasing
 * clockwise viewed from above.
 */
public final class WaypointMath {
    private WaypointMath() {
    }

    /** Horizontal (X/Z only, ignores Y) straight-line distance between two points. */
    public static double horizontalDistance(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The yaw (degrees, vanilla convention, 0..360) a player at (fromX,fromZ) would need to face
     * to look straight at (toX,toZ). Solving {@code dirX = -sin(yaw), dirZ = cos(yaw)} for yaw
     * given a direction (dx,dz) gives {@code yaw = atan2(-dx, dz)} - verified against the four
     * cardinal directions in WaypointMathTest. Returns 0 (arbitrary but stable) if the two points
     * coincide, since no bearing is meaningful there.
     */
    public static float bearingYawDegrees(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (dx == 0 && dz == 0) {
            return 0f;
        }
        double yawDegrees = Math.toDegrees(Math.atan2(-dx, dz));
        return (float) ((yawDegrees % 360 + 360) % 360);
    }
}
