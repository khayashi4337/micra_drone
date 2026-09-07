package io.github.khayashi4337.micradrone.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A waypoint compass' bound target: a block position plus which dimension it's in (X/Z alone
 * would collide between dimensions that share coordinate ranges, e.g. the Overworld and the
 * Nether). Absence of this component on the stack (not a boolean flag inside it) means "not bound
 * yet" - see WaypointCompassItem.
 */
public record WaypointData(BlockPos pos, ResourceKey<Level> dimension) {
    public static final Codec<WaypointData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(WaypointData::pos),
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(WaypointData::dimension)
    ).apply(instance, WaypointData::new));
}
