package io.github.khayashi4337.micradrone.client;

/**
 * Pure viewport math for the IDE screen's top-down 3D camera (issue #6 v3): from the plot size,
 * the player's vertical FOV, and the window aspect ratio, work out how high above the field the
 * camera must hover for the whole plot to fit inside the RIGHT HALF of the screen (the left half
 * is covered by the editor), and how far west to shift the camera so the plot lands centered in
 * that right half (at 75% of screen width) instead of behind the editor. Minecraft-free - kept as
 * its own class with zero Minecraft imports (see PlotGeometry's class comment for why that
 * discipline matters) so the trigonometry is unit-testable.
 */
public final class IdeCameraMath {
    /** Extra world blocks of breathing room beyond the plot on each axis. */
    private static final double MARGIN = 2.0;

    /**
     * {@code height}: camera altitude above the crop layer. {@code westOffset}: how far the camera
     * sits west of the plot center (with north up, screen-left = west, so shifting the camera west
     * moves the plot toward screen-right).
     */
    public record CameraPose(double height, double westOffset) {}

    private IdeCameraMath() {
    }

    /**
     * A straight-down camera sees a ground rectangle of {@code 2*h*tan(fov/2)} blocks vertically
     * and {@code 2*h*tan(fov/2)*aspect} horizontally. The plot (plus margin) must fit both the
     * full screen height and the right HALF of the screen width; the west offset then moves the
     * plot center from screen center (50%) to the right half's center (75%), i.e. a quarter of
     * the full visible width.
     */
    public static CameraPose topDownPose(int plotSize, double fovYDegrees, double aspect) {
        double tanHalf = Math.tan(Math.toRadians(fovYDegrees) / 2.0);
        double extent = plotSize + MARGIN;
        double fitFullHeight = extent / (2.0 * tanHalf);
        double fitRightHalfWidth = extent / (tanHalf * aspect);
        double height = Math.max(fitFullHeight, fitRightHalfWidth);
        double westOffset = 0.5 * height * tanHalf * aspect;
        return new CameraPose(height, westOffset);
    }
}
