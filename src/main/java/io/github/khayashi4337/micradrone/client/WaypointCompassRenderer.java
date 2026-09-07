package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.drone.WaypointCompassItem;
import io.github.khayashi4337.micradrone.drone.WaypointData;
import io.github.khayashi4337.micradrone.drone.WaypointMath;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * While a bound {@link WaypointCompassItem} is in the main hand: shows the distance in the action
 * bar and points toward the waypoint with a short trail of particles floating out in front of the
 * player - vanilla's own lodestone compass has no equivalent "how far" readout, this adds one.
 * Bearing/distance math is in {@link WaypointMath} (Minecraft-free, unit-tested); this class is
 * pure Minecraft glue on top of it.
 *
 * <p>Instance-level {@code @SubscribeEvent} registered from {@code MicraDroneClient}, like
 * {@link RegionSelectionRenderer} - see {@code EnchantTableWatcher} for why not a static
 * subscriber. Driven by {@link ClientTickEvent.Post} (once per tick, physical client only) rather
 * than a render-stage event: spawning particles is itself a per-tick action ({@code DroneEntity}
 * does the same from its own {@code tick()}), not something that needs a 3D render pass of its own.
 */
public final class WaypointCompassRenderer {
    /** Roughly twice a second - frequent enough to feel "live" without flooding the particle system. */
    private static final int PARTICLE_INTERVAL_TICKS = 10;
    /** The action bar re-displays its own text every call, so this just controls the update rate. */
    private static final int MESSAGE_INTERVAL_TICKS = 10;
    /** How far in front of the player the trail starts, and how many particles form it. */
    private static final double TRAIL_START_DISTANCE = 1.0;
    private static final double TRAIL_STEP = 0.5;
    private static final int TRAIL_PARTICLE_COUNT = 4;

    private int tickCounter = 0;

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        if (!(held.getItem() instanceof WaypointCompassItem)) {
            return;
        }
        tickCounter++;
        WaypointData data = held.get(MicraDrone.WAYPOINT_DATA.get());
        if (data == null) {
            return; // Not bound yet - the tooltip already explains how; nothing live to show.
        }

        ResourceKey<Level> here = minecraft.level.dimension();
        if (!here.equals(data.dimension())) {
            if (tickCounter % MESSAGE_INTERVAL_TICKS == 0) {
                minecraft.player.displayClientMessage(
                        Component.translatable("micradrone.waypoint_compass.different_dimension"), true);
            }
            return;
        }

        Vec3 from = minecraft.player.position();
        double toX = data.pos().getX() + 0.5;
        double toZ = data.pos().getZ() + 0.5;
        double distance = WaypointMath.horizontalDistance(from.x, from.z, toX, toZ);

        if (tickCounter % MESSAGE_INTERVAL_TICKS == 0) {
            minecraft.player.displayClientMessage(
                    Component.translatable("micradrone.waypoint_compass.distance", Math.round(distance)), true);
        }
        if (tickCounter % PARTICLE_INTERVAL_TICKS == 0) {
            spawnDirectionTrail(minecraft, from, toX, toZ);
        }
    }

    private static void spawnDirectionTrail(Minecraft minecraft, Vec3 from, double toX, double toZ) {
        float yawDegrees = WaypointMath.bearingYawDegrees(from.x, from.z, toX, toZ);
        double yawRad = Math.toRadians(yawDegrees);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);
        double eyeY = from.y + minecraft.player.getEyeHeight();
        for (int i = 0; i < TRAIL_PARTICLE_COUNT; i++) {
            double step = TRAIL_START_DISTANCE + i * TRAIL_STEP;
            minecraft.level.addParticle(ParticleTypes.END_ROD,
                    from.x + dirX * step, eyeY, from.z + dirZ * step,
                    dirX * 0.01, 0.0, dirZ * 0.01);
        }
    }
}
