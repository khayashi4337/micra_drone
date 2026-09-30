package io.github.khayashi4337.micradrone.build.compile;

/** What a placement may replace in the world. The code form is written into the manifest and its hash. */
public sealed interface ReplacePolicy {
    AirOnly AIR_ONLY = new AirOnly();
    Replaceable REPLACEABLE = new Replaceable();
    Terraform TERRAFORM = new Terraform();

    String CODE_AIR_ONLY = "air_only";
    String CODE_REPLACEABLE = "replaceable";
    String CODE_TERRAFORM = "terraform";
    String CODE_EXPECT_PREFIX = "expect:";

    record AirOnly() implements ReplacePolicy {
    }

    /** May replace the naturally replaceable blocks (air, water, grass, snow, leaves ...); the runtime decides. */
    record Replaceable() implements ReplacePolicy {
    }

    /** May remove natural ground (the micradrone:terraformable tag) as well as what REPLACEABLE takes (P4, F-5). */
    record Terraform() implements ReplacePolicy {
    }

    record Expect(String blockId) implements ReplacePolicy {
    }

    default String code() {
        return switch (this) {
            case AirOnly a -> CODE_AIR_ONLY;
            case Replaceable r -> CODE_REPLACEABLE;
            case Terraform t -> CODE_TERRAFORM;
            case Expect e -> CODE_EXPECT_PREFIX + e.blockId();
        };
    }

    static ReplacePolicy fromCode(String code) {
        if (CODE_AIR_ONLY.equals(code)) {
            return AIR_ONLY;
        }
        if (CODE_REPLACEABLE.equals(code)) {
            return REPLACEABLE;
        }
        if (CODE_TERRAFORM.equals(code)) {
            return TERRAFORM;
        }
        if (code != null && code.startsWith(CODE_EXPECT_PREFIX) && code.length() > CODE_EXPECT_PREFIX.length()) {
            return new Expect(code.substring(CODE_EXPECT_PREFIX.length()));
        }
        throw new IllegalArgumentException("unknown replace policy code: " + code);
    }
}
