package io.github.khayashi4337.micradrone.build.compile;

/** What a placement may replace in the world. The code form is written into the manifest and its hash. */
public sealed interface ReplacePolicy {
    AirOnly AIR_ONLY = new AirOnly();
    Replaceable REPLACEABLE = new Replaceable();

    String CODE_AIR_ONLY = "air_only";
    String CODE_REPLACEABLE = "replaceable";
    String CODE_EXPECT_PREFIX = "expect:";

    record AirOnly() implements ReplacePolicy {
    }

    /** May replace the naturally replaceable blocks (air, water, grass, snow, leaves ...); the runtime decides. */
    record Replaceable() implements ReplacePolicy {
    }

    record Expect(String blockId) implements ReplacePolicy {
    }

    default String code() {
        return switch (this) {
            case AirOnly a -> CODE_AIR_ONLY;
            case Replaceable r -> CODE_REPLACEABLE;
            case Expect e -> CODE_EXPECT_PREFIX + e.blockId();
        };
    }
}
