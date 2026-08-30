package io.github.khayashi4337.micradrone.drone;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ready-to-run example scripts, handed out one at a time by the enchanting table (see
 * {@link SampleCatalog}, which decides what a given library unlocks and what it costs) so trying
 * the drone out doesn't require hand-writing a script first. Each one is exercised against
 * {@code FakeDroneApi} in {@code SampleScriptsTest} to catch language-level mistakes before they
 * ship - that test is what {@link #ALL} exists for.
 */
public final class SampleScripts {
    public static final String FIRST_PROGRAM = """
            # The very first program, straight out of The Farmer Was Replaced: harvest whatever the
            # drone is standing on, then celebrate. do_a_flip() does nothing at all to the farm - it
            # is purely for watching the drone tumble - which makes it a safe way to see a script
            # really run, and to try a for-loop, before touching a single crop.
            harvest()
            for i in range(5):
                do_a_flip()
            """;

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

    public static final String SURVEY_PLOT = """
            # Looks at the ground before acting (issue #10), so it works on a plot that hasn't been
            # prepared yet and says what it found instead of silently failing. Without get_ground()
            # the drone can only assume what it's standing on; with it, one script handles farmland,
            # bare dirt, and cells it simply can't farm.
            size = get_world_size()
            print("biome:")
            print(get_biome())
            print("weather:")
            print(get_weather())
            planted = 0
            blocked = 0
            going_east = True
            row = 0
            while row < size:
                col = 0
                while col < size:
                    ground = get_ground()
                    if ground == "farmland":
                        if plant("wheat"):
                            planted = planted + 1
                    elif ground == "dirt" or ground == "grass_block":
                        till()
                        if plant("wheat"):
                            planted = planted + 1
                    else:
                        blocked = blocked + 1
                        print("cannot farm this cell:")
                        print(ground)
                    if col < size - 1:
                        if going_east:
                            move("east")
                        else:
                            move("west")
                    col = col + 1
                if row < size - 1:
                    move("south")
                going_east = not going_east
                row = row + 1
            print("cells planted:")
            print(planted)
            print("cells I could not farm:")
            print(blocked)
            # Vanilla crops stop growing below light level 9, so this is worth knowing before you
            # walk away expecting a harvest.
            if get_light() < 9:
                print("it is too dark here for crops to grow")
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

    public static final String COUNT_GROUND = """
            # リストと辞書の練習。プロットを一周して、足元の地面を種類ごとに数える。
            # 種類が何通りあるか分からなくても、辞書ならキーを増やしていくだけで数えられる。
            size = get_world_size()
            counts = {}
            going_east = True
            row = 0
            while row < size:
                col = 0
                while col < size - 1:
                    ground = get_ground()
                    if ground in counts:
                        counts[ground] = counts[ground] + 1
                    else:
                        counts[ground] = 1
                    if going_east:
                        move("east")
                    else:
                        move("west")
                    col = col + 1
                ground = get_ground()
                if ground in counts:
                    counts[ground] = counts[ground] + 1
                else:
                    counts[ground] = 1
                if row < size - 1:
                    move("south")
                going_east = not going_east
                row = row + 1
            names = counts.keys()
            print("見つけた地面の種類の数:")
            print(len(names))
            for name in names:
                print(name)
                print(counts[name])
            """;

    public static final String PLOT_ID = """
            # Prints this plot's Corner Marker id - the friendly name if you renamed the marker in
            # an anvil before placing it, otherwise a short auto-assigned id. Handy for telling
            # scripts apart once you have more than one plot, or just to check which plot a script
            # is currently running on.
            print("plot id:")
            print(get_plot_id())
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
            # 本家 The Farmer Was Replaced 流のかぼちゃ(ショップで pumpkin を解放してから):
            # 全面に植えて、腐ったマス(約1/5)は見つけ次第そのまま植え直し、全マスが実って
            # 1つの巨大かぼちゃに融合するまで収穫を我慢する。最後に1マスで harvest() すると
            # 一辺 n で n*n*n ポイント(n が 6 以上なら n*n*6)がまとめて入る。1マスずつ収穫
            # すると1ポイントなので、かぼちゃのスコアはほぼこれで決まる。
            # 1回の Run で畑を1周する。育つのを待ちながら、Run(またはレバー)を繰り返すこと。
            size = get_world_size()
            going_east = True
            row = 0
            planted = 0
            ripe = 0
            while row < size:
                col = 0
                while col < size:
                    if get_ground() != "farmland":
                        till()
                    above = get_block_above()
                    if above == "pumpkin":
                        harvest()   # 古いバージョンの vanilla の実は1ポイントで片付ける
                        above = "air"
                    if is_rotten() or above == "air" or above == "pumpkin_stem" or above == "attached_pumpkin_stem":
                        plant("pumpkin")   # 腐り・空き・古い苗は plant() でそのまま上書きできる
                        planted = planted + 1
                    elif can_harvest():
                        ripe = ripe + 1
                    if col < size - 1:
                        if going_east:
                            move("east")
                        else:
                            move("west")
                    col = col + 1
                if row < size - 1:
                    move("south")
                going_east = not going_east
                row = row + 1
            print("planted:")
            print(planted)
            print("ripe:")
            print(ripe)
            if ripe == size * size:
                print("all ripe - harvesting the giant pumpkin:")
                print(harvest())
            print("Pumpkin points:")
            print(get_points("pumpkin"))
            """;

    /** File name (with extension) -> content, in the order they should appear in the picker. */
    public static final Map<String, String> ALL = buildAll();

    private static Map<String, String> buildAll() {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("first_program.mdrone", FIRST_PROGRAM);
        all.put("main.mdrone", MAIN);
        all.put("plot_id.mdrone", PLOT_ID);
        all.put("move_square.mdrone", MOVE_SQUARE);
        all.put("till_and_plant.mdrone", TILL_AND_PLANT);
        all.put("survey_plot.mdrone", SURVEY_PLOT);
        all.put("harvest_when_ready.mdrone", HARVEST_WHEN_READY);
        all.put("count_ground.mdrone", COUNT_GROUND);
        all.put("carrot_farm.mdrone", CARROT_FARM);
        all.put("pumpkin_smart_harvest.mdrone", PUMPKIN_SMART_HARVEST);
        return Map.copyOf(all);
    }

    private SampleScripts() {
    }
}
