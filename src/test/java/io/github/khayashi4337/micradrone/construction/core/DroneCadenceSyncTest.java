package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** F-2: construction drones place at the farm drone's pace. LiveDroneApi's constant is private, so its source is read. */
class DroneCadenceSyncTest {
    @Test
    void theDroneIntervalMatchesTheFarmDrone() throws IOException {
        String src = Files.readString(Path.of("src/main/java/io/github/khayashi4337/micradrone/drone/LiveDroneApi.java"),
                StandardCharsets.UTF_8);
        assertTrue(src.contains("ACTION_DELAY_TICKS = " + BudgetConfig.DRONE_INTERVAL_TICKS + ";"),
                "LiveDroneApi.ACTION_DELAY_TICKS changed; update BudgetConfig.DRONE_INTERVAL_TICKS and design 04 F-2 together");
    }
}
