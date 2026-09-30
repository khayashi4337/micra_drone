package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DroneChoreographerTest {
    @Test
    void eachDroneGoesToTheLastPositionItWasGiven() {
        List<IntPos> touched = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            touched.add(new IntPos(i, 64, 0));
        }
        assertEquals(List.of(new DroneMove(0, new IntPos(6, 64, 0)), new DroneMove(1, new IntPos(4, 64, 0)),
                new DroneMove(2, new IntPos(5, 64, 0))), DroneChoreographer.assign(3, touched));
    }

    @Test
    void nothingPlacedMeansNoMoveAndAtLeastOneDrone() {
        assertEquals(List.of(), DroneChoreographer.assign(3, List.of()));
        assertEquals(List.of(new DroneMove(0, new IntPos(1, 2, 3))), DroneChoreographer.assign(0, List.of(new IntPos(1, 2, 3))));
    }
}
