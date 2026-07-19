package io.github.khayashi4337.micradrone.drone;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ready-to-run example scripts seeded into every controller's script folder (see
 * {@link ScriptFileStore}), so trying the drone out doesn't require hand-writing a script first.
 * Each one is exercised against {@code FakeDroneApi} in {@code SampleScriptsTest} to catch
 * language-level mistakes before they ship.
 */
public final class SampleScripts {
    public static final String MAIN = """
            # Write your drone script here, then click Run.
            print(get_world_size())
            """;

    public static final String TILL_AND_PLANT = """
            # Tills and plants wheat across the whole plot using a snake path (no backtracking needed).
            size = get_world_size()
            going_east = True
            row = 0
            while row < size:
                col = 0
                while col < size - 1:
                    till()
                    plant("wheat")
                    if going_east:
                        move("east")
                    else:
                        move("west")
                    col = col + 1
                till()
                plant("wheat")
                if row < size - 1:
                    move("south")
                going_east = not going_east
                row = row + 1
            print("planted the whole plot")
            """;

    public static final String HARVEST_WHEN_READY = """
            # Walks the whole plot and harvests any cell that's ready, snake path. Run this after the
            # plot has had time to grow (see till_and_plant.mdrone to plant it first).
            size = get_world_size()
            going_east = True
            row = 0
            harvested = 0
            while row < size:
                col = 0
                while col < size - 1:
                    if can_harvest():
                        if harvest():
                            harvested = harvested + 1
                    if going_east:
                        move("east")
                    else:
                        move("west")
                    col = col + 1
                if can_harvest():
                    if harvest():
                        harvested = harvested + 1
                if row < size - 1:
                    move("south")
                going_east = not going_east
                row = row + 1
            print("harvested cells:")
            print(harvested)
            print("Wheat:")
            print(get_points("wheat"))
            """;

    public static final String MOVE_SQUARE = """
            # Walks the plot's outer edge once, printing the drone's position at each step. A gentle
            # first script to see move()/get_pos_x()/get_pos_y() in action.
            size = get_world_size()
            i = 0
            while i < size - 1:
                move("east")
                print(get_pos_x())
                i = i + 1
            i = 0
            while i < size - 1:
                move("south")
                print(get_pos_y())
                i = i + 1
            i = 0
            while i < size - 1:
                move("west")
                print(get_pos_x())
                i = i + 1
            i = 0
            while i < size - 1:
                move("north")
                print(get_pos_y())
                i = i + 1
            print("back near the start")
            """;

    public static final String CARROT_FARM = """
            # Tills, harvests any mature carrot, and replants across the whole plot - unlock carrot
            # first via the Corner Marker shop, then re-run this any time (it's safe to run before
            # anything is grown yet, and safe to run again after harvesting).
            size = get_world_size()
            going_east = True
            row = 0
            harvested = 0
            while row < size:
                col = 0
                while col < size - 1:
                    till()
                    if can_harvest():
                        if harvest():
                            harvested = harvested + 1
                    plant("carrot")
                    if going_east:
                        move("east")
                    else:
                        move("west")
                    col = col + 1
                till()
                if can_harvest():
                    if harvest():
                        harvested = harvested + 1
                plant("carrot")
                if row < size - 1:
                    move("south")
                going_east = not going_east
                row = row + 1
            print("carrots harvested:")
            print(harvested)
            print("Carrot points:")
            print(get_points("carrot"))
            """;

    public static final String PUMPKIN_SMART_HARVEST = """
            # Verification script for the Phase 3 rework: plants pumpkin across the plot (unlock it
            # first via the Corner Marker shop) and, every time you Run it, checks is_rotten() before
            # harvesting. About 1 in 5 pumpkins grow defective - harvesting one earns nothing, so
            # skipping straight to plant() on a rotten cell (no wasted harvest() call) is the efficient
            # move. Re-run this periodically as the plot grows to watch rotten_skipped tick up.
            size = get_world_size()
            going_east = True
            row = 0
            harvested = 0
            rotten_skipped = 0
            while row < size:
                col = 0
                while col < size - 1:
                    till()
                    if is_rotten():
                        rotten_skipped = rotten_skipped + 1
                    elif can_harvest():
                        if harvest():
                            harvested = harvested + 1
                    plant("pumpkin")
                    if going_east:
                        move("east")
                    else:
                        move("west")
                    col = col + 1
                till()
                if is_rotten():
                    rotten_skipped = rotten_skipped + 1
                elif can_harvest():
                    if harvest():
                        harvested = harvested + 1
                plant("pumpkin")
                if row < size - 1:
                    move("south")
                going_east = not going_east
                row = row + 1
            print("good pumpkins harvested:")
            print(harvested)
            print("rotten pumpkins skipped (replanted without wasting harvest):")
            print(rotten_skipped)
            print("Pumpkin points:")
            print(get_points("pumpkin"))
            """;

    /** File name (with extension) -> content, in the order they should appear in the picker. */
    public static final Map<String, String> ALL = buildAll();

    private static Map<String, String> buildAll() {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("main.mdrone", MAIN);
        all.put("move_square.mdrone", MOVE_SQUARE);
        all.put("till_and_plant.mdrone", TILL_AND_PLANT);
        all.put("harvest_when_ready.mdrone", HARVEST_WHEN_READY);
        all.put("carrot_farm.mdrone", CARROT_FARM);
        all.put("pumpkin_smart_harvest.mdrone", PUMPKIN_SMART_HARVEST);
        return Map.copyOf(all);
    }

    private SampleScripts() {
    }
}
