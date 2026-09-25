package io.github.khayashi4337.micradrone.build.parts;

/** The stage of construction a part belongs to; the ascending order is the construction order. */
public enum BuildPhase {
    SITE_PREP, STRUCTURE, ENVELOPE, POWER, UPSTREAM, DOWNSTREAM, LOGISTICS, ASSEMBLE, DECORATION, FINISH
}
