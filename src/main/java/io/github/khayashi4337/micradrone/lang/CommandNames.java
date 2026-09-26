package io.github.khayashi4337.micradrone.lang;

import java.util.List;
import java.util.stream.Stream;

/**
 * Every script-visible command name (snake_case, as scripts call them) the interpreter recognizes -
 * the editor autocomplete popup's candidate list (do_a_flip() was hard to find without one).
 * There's no way to derive this mechanically from {@link Interpreter#evalCall}'s {@code switch}
 * (case labels aren't reflectively enumerable), so this is a hand-maintained mirror of it - keep the
 * two in sync when adding or removing a command, the same "duplicated in a couple of related places"
 * tradeoff already accepted for {@code CommandsHelpDoc}/{@code SampleCatalog}.
 */
public final class CommandNames {
    public static final List<String> ALL = List.of(
            "move", "till", "plant", "harvest", "do_a_flip", "sleep_ticks",
            "can_harvest", "is_rotten", "measure",
            "get_pos_x", "get_pos_y", "get_world_size", "get_points",
            "get_ground", "get_block_above", "get_time", "get_weather", "get_biome", "get_light", "get_plot_id",
            "set_output", "get_output", "pair_with", "is_paired",
            "print", "range",
            // Automated fishing - see DroneControllerBlockEntity/LiveDroneApi.
            "cast_line", "reel_in", "is_fishing",
            "is_bobber_bobbing", "did_fish_bite", "is_open_water_cast", "get_rod_durability",
            // Automated anvil repair - shares its rod stock with the fishing commands above.
            "is_anvil", "get_repair_cost", "repair_rod",
            // General-purpose, nothing to do with the drone - see Interpreter's "general-purpose builtins".
            "len", "abs", "min", "max", "random", "str", "list", "dict", "set", "semaphore", "create_task",
            "attach_isr", "raise_interrupt");

    // ---- construction commands (see docs/design/nl_factory_builder/04_foundations.md, F-6) ----

    public static final String SITE = "site";
    public static final String STYLE = "style";
    public static final String MOOD = "mood";
    public static final String PART = "part";
    public static final String UPDATE_PARAMS = "update_params";
    public static final String RELOCATE = "relocate";
    public static final String REMOVE_PART = "remove_part";
    public static final String CONNECT = "connect";
    public static final String DISCONNECT = "disconnect";
    public static final String LOGISTICS = "logistics";

    /** General construction commands. */
    public static final List<String> PLAN_GENERAL = List.of(SITE, STYLE, MOOD, PART, UPDATE_PARAMS, RELOCATE,
            REMOVE_PART, CONNECT, DISCONNECT, LOGISTICS);

    /** One command per building part (micra:*), named by the part id without the prefix; a test keeps it equal to the registry. */
    public static final List<String> PLAN_PART_COMMANDS = List.of("balcony", "beam", "catwalk", "chimney", "dock_pad", "door",
            "floor", "foundation", "ladder", "lamp", "pillar", "planter", "railing", "ramp", "road", "roof", "sign", "stairs",
            "structure", "trim", "wall", "window");

    /**
     * Every construction command. Deliberately NOT part of {@link #ALL}: {@code ALL} makes the interpreter refuse a farm
     * script's own function with the same name (and feeds the farm editor), so generic names like wall or door would break
     * scripts players already wrote. The interpreter only accepts these when it was built with a PlanApi.
     */
    public static final List<String> PLAN = Stream.concat(PLAN_GENERAL.stream(), PLAN_PART_COMMANDS.stream()).toList();

    /** Farm builtins that are pure and deterministic, so they stay usable in a construction script. */
    public static final List<String> PLAN_HELPERS = List.of("print", "len", "abs", "min", "max", "str", "list", "dict", "set", "range");

    /** What the construction editor highlights and completes. */
    public static final List<String> PLAN_VISIBLE = Stream.concat(PLAN.stream(), PLAN_HELPERS.stream()).toList();

    private CommandNames() {
    }
}
