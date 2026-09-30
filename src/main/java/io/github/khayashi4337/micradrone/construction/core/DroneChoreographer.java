package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Which show drone flies where this tick (N-27): the placed positions dealt out in turn; each drone ends at its last. */
public final class DroneChoreographer {
    /** How high above the block it "places" a show drone hovers. */
    public static final double HOVER_BLOCKS = 1.5;

    private DroneChoreographer() {
    }

    public static List<DroneMove> assign(int droneCount, List<IntPos> touched) {
        int drones = Math.max(1, droneCount);
        Map<Integer, IntPos> last = new TreeMap<>();
        for (int i = 0; i < touched.size(); i++) {
            last.put(i % drones, touched.get(i));
        }
        List<DroneMove> out = new ArrayList<>();
        last.forEach((drone, pos) -> out.add(new DroneMove(drone, pos)));
        return out;
    }
}
