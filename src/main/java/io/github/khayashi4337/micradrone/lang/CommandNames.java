package io.github.khayashi4337.micradrone.lang;

import java.util.List;

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
            "move", "till", "plant", "harvest", "do_a_flip",
            "can_harvest", "is_rotten", "measure",
            "get_pos_x", "get_pos_y", "get_world_size", "get_points",
            "get_ground", "get_block_above", "get_time", "get_weather", "get_biome", "get_light", "get_plot_id",
            "set_output", "get_output",
            "print", "range",
            // General-purpose, nothing to do with the drone - see Interpreter's "general-purpose builtins".
            "len", "abs", "min", "max", "random", "str", "list", "dict", "set");

    private CommandNames() {
    }
}
