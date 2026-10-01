package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The saved types' names and current versions (design 01, section 0; 04 F-2). The write-ahead log's entries are not
 * enveloped one by one: the log is an append-only file and holds them as {@link WalCodec} frames instead.
 */
public final class SaveTypes {
    public static final String JOB = "job";
    public static final String JOURNAL = "journal";
    public static final String LEDGER = "ledger";
    public static final String OUTCOME = "outcome";
    public static final String PROGRAM = "program";
    public static final String MANIFEST = "manifest";
    public static final String CLAIMS = "claims";
    public static final String REGISTRY = "registry";

    /** Every saved type starts at version 1; the payload records that carry a SCHEMA_VERSION share that constant. */
    public static final int FIRST_VERSION = 1;

    private SaveTypes() {
    }

    /** The current version of each type: writers stamp envelopes with it and {@link Migrations} targets it. */
    public static Map<String, Integer> currentVersions() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(JOB, ConstructionJob.SCHEMA_VERSION);
        m.put(JOURNAL, FIRST_VERSION);
        m.put(LEDGER, MaterialLedger.SCHEMA_VERSION);
        m.put(OUTCOME, FIRST_VERSION);
        m.put(PROGRAM, FIRST_VERSION);
        m.put(MANIFEST, PlacementManifest.MANIFEST_VERSION);
        m.put(CLAIMS, SiteClaim.SCHEMA_VERSION);
        m.put(REGISTRY, PlacedRegistry.SCHEMA_VERSION);
        return Map.copyOf(m);
    }

    public static Migrations migrations() {
        return new Migrations(currentVersions());
    }
}
