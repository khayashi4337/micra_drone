package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.drone.CornerMarkerScan;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;

/**
 * Owns the IDE screen's top-down camera (issue #6 v3, 林さん's proposal): instead of rendering a
 * second viewpoint into a GUI framebuffer (which needs heavy machinery), move the game's ONE
 * normal camera above the field with {@link Minecraft#setCameraEntity} and let the screen leave
 * its background transparent. The camera is a client-only {@link Marker} entity ("the camera") -
 * a vanilla entity with no model and no behavior, deliberately NOT added to the level, so it is
 * never rendered, never ticked, and never known to the server; the player's body stays where it
 * is. {@code Camera.setup} interpolates {@code xo/yo/zo -> x/y/z} (all public fields), so keeping
 * old and current values identical gives a perfectly stable shot.
 */
final class IdeCameraController {
    private final BlockPos controllerPos;
    private Marker camera;

    IdeCameraController(BlockPos controllerPos) {
        this.controllerPos = controllerPos;
    }

    /**
     * (Re)computes the camera pose from the current plot bounds, FOV, and window aspect, and makes
     * it the active viewpoint. Called every client tick while the 3D view is showing, so the shot
     * follows corner-marker changes and window resizes.
     */
    void update(Minecraft minecraft, CornerMarkerScan.PlotBounds bounds) {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (camera == null || camera.level() != minecraft.level) {
            camera = EntityType.MARKER.create(minecraft.level);
            if (camera == null) {
                return;
            }
        }

        int size = Math.max(1, bounds.worldSize());
        double fovDegrees = minecraft.options.fov().get();
        double aspect = (double) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
        IdeCameraMath.CameraPose pose = IdeCameraMath.topDownPose(size, fovDegrees, aspect);

        // Plot center: cell centers sit at controller + dir*(1+g) + 0.5 on each axis.
        double centerX = controllerPos.getX() + 0.5 + bounds.dirX() * (1 + (size - 1) / 2.0);
        double centerZ = controllerPos.getZ() + 0.5 + bounds.dirZ() * (1 + (size - 1) / 2.0);
        double x = centerX - pose.westOffset();
        double y = controllerPos.getY() + 1 + pose.height();
        double z = centerZ;

        camera.setPos(x, y, z);
        camera.xo = x;
        camera.yo = y;
        camera.zo = z;
        // Straight down, with north at the top of the screen (yRot 180 = facing north), matching
        // the 2D/Sim tile views' orientation.
        camera.setXRot(90.0f);
        camera.setYRot(180.0f);
        camera.xRotO = 90.0f;
        camera.yRotO = 180.0f;

        if (minecraft.getCameraEntity() != camera) {
            minecraft.setCameraEntity(camera);
        }
    }

    /** Hands the viewpoint back to the player. Safe to call repeatedly / when never activated. */
    void restore(Minecraft minecraft) {
        if (minecraft.player != null && minecraft.getCameraEntity() != minecraft.player) {
            minecraft.setCameraEntity(minecraft.player);
        }
    }
}
