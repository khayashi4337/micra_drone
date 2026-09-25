package io.github.khayashi4337.micradrone.build.plan;

/** The record of an analysis run over a template: who ran it, with which analyzer, and the result's hash. */
public record Verification(VerificationOrigin origin, String analyzerVersion, String resultHash, long atMillis) {
}
