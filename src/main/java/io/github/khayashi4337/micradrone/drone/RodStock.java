package io.github.khayashi4337.micradrone.drone;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Where cast_line()/reel_in() looks for a spare {@code minecraft:fishing_rod} when the current one
 * breaks or wears thin (see {@link DroneControllerBlockEntity}) - any container (chest, barrel,
 * shulker box) touching one of the controller's 6 faces. Deliberately a separate, simpler scan than
 * {@link ScriptChestLibrary}'s axis-line search toward the corner marker: a rod stock isn't tied to
 * the plot's shape or size the way the script library is, so there's no reason to walk outward along
 * an axis - "next to the controller" is the whole rule.
 */
final class RodStock {
    private RodStock() {
    }

    /** Every container touching one of the controller's 6 faces, in {@link Direction#values()} order. */
    static List<Container> findContainers(ServerLevel level, BlockPos controllerPos) {
        List<Container> containers = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            if (level.getBlockEntity(controllerPos.relative(direction)) instanceof Container container) {
                containers.add(container);
            }
        }
        return containers;
    }

    /** Where a spare fishing rod currently sits, if any adjacent container holds one - re-scanned fresh every call. */
    static Optional<RodLocation> findSpareRod(ServerLevel level, BlockPos controllerPos) {
        for (Container container : findContainers(level, controllerPos)) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).is(Items.FISHING_ROD)) {
                    return Optional.of(new RodLocation(container, slot));
                }
            }
        }
        return Optional.empty();
    }

    /** A specific slot in a specific container, resolved by {@link #findSpareRod} - {@link #take} removes exactly one rod from it. */
    record RodLocation(Container container, int slot) {
        ItemStack take() {
            return container.removeItem(slot, 1);
        }
    }
}
