package io.github.khayashi4337.micradrone.drone;

/**
 * Turns raw world readings into the short, stable strings the perception commands hand back to
 * scripts (see {@link FarmBlockAccess}'s perception section, GitHub issue #10). Deliberately kept in
 * its own class with zero Minecraft dependency, for the same reason {@link PlotGeometry} is - see
 * that class's note on Minecraft references breaking verification on the test sourceSet - which also
 * makes these rules directly unit-testable.
 */
public final class SenseNames {
    /** Minecraft's own namespace; stripped from names so scripts can write the everyday {@code "dirt"}. */
    private static final String VANILLA_NAMESPACE = "minecraft";

    /** One Minecraft day in ticks - the period {@link #timeOfDay} folds a running day-time count into. */
    static final long TICKS_PER_DAY = 24000;

    private SenseNames() {
    }

    /**
     * {@code ("minecraft", "farmland") -> "farmland"}, but {@code ("micradrone", "rotten_pumpkin") ->
     * "micradrone:rotten_pumpkin"}. Vanilla blocks/biomes - all a script realistically compares
     * against - stay short and readable, while anything from a mod keeps its namespace so two mods'
     * same-named blocks can never collide into one script-visible name. Public: also reused by
     * chat/LiveBlockSnapshotReader (AI chat's get_block_snapshot tool) for the same reason.
     */
    public static String simplify(String namespace, String path) {
        return VANILLA_NAMESPACE.equals(namespace) ? path : namespace + ":" + path;
    }

    /**
     * Thunder wins over rain, matching how Minecraft itself layers the two (a thunderstorm is always
     * also raining, so testing rain first would make {@code "thunder"} unreachable).
     */
    static String weather(boolean raining, boolean thundering) {
        if (thundering) {
            return "thunder";
        }
        return raining ? "rain" : "clear";
    }

    /**
     * Folds a level's ever-increasing day-time counter into 0..23999, the in-day clock scripts
     * actually want (0 = sunrise, 6000 = noon, 12000 = sunset, 18000 = midnight). floorMod, not %,
     * because {@code /time set} can leave the raw counter negative - a plain remainder would then
     * hand scripts a negative "time of day".
     */
    static long timeOfDay(long dayTime) {
        return Math.floorMod(dayTime, TICKS_PER_DAY);
    }
}
