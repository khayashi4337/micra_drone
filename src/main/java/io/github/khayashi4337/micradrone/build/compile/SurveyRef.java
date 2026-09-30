package io.github.khayashi4337.micradrone.build.compile;

/** The server-issued, pinned terrain survey the compile was based on. P3 records it only; P4 uses it for terraforming. */
public record SurveyRef(String digest, long cachedUntilTick) {
}
