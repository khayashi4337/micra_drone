package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A {@link ConstructionJob} as a tree (enveloped as {@code jobs/<jobId>/job.bin}): every field, enums by name, the
 * owner as a UUID string, {@code null} fields as {@code null}. A value of the wrong shape is refused, never guessed.
 */
public final class JobCodec {
    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_JOB_ID = "jobId";
    private static final String KEY_OWNER = "ownerUuid";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_MANIFEST_HASH = "manifestHash";
    private static final String KEY_KIND = "kind";
    private static final String KEY_PARENT = "parentJobId";
    private static final String KEY_STATE = "state";
    private static final String KEY_PAUSE_REASON = "pauseReason";
    private static final String KEY_CURSOR = "cursor";
    private static final String KEY_TOTAL = "total";
    private static final String KEY_REPAIR_ROUND = "repairRound";
    private static final String KEY_CLAIM_ID = "claimId";
    private static final String KEY_MATERIAL_POLICY = "materialPolicy";
    private static final String KEY_JOURNAL_FILE = "journalFile";
    private static final String KEY_LEDGER_FILE = "ledgerFile";
    private static final String KEY_LAST_ERROR = "lastError";
    private static final String KEY_CREATED_TICK = "createdTick";
    private static final String KEY_ACCEPTED_RISKS = "acceptedRiskIds";

    private JobCodec() {
    }

    public static Object toTree(ConstructionJob j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_SCHEMA_VERSION, (long) j.schemaVersion());
        m.put(KEY_JOB_ID, j.jobId());
        m.put(KEY_OWNER, j.ownerUuid().toString());
        m.put(KEY_DIMENSION, j.dimension());
        m.put(KEY_MANIFEST_HASH, j.manifestHash());
        m.put(KEY_KIND, j.kind().name());
        m.put(KEY_PARENT, j.parentJobId());
        m.put(KEY_STATE, j.state().name());
        m.put(KEY_PAUSE_REASON, j.pauseReason() == null ? null : j.pauseReason().name());
        m.put(KEY_CURSOR, (long) j.cursor());
        m.put(KEY_TOTAL, (long) j.total());
        m.put(KEY_REPAIR_ROUND, (long) j.repairRound());
        m.put(KEY_CLAIM_ID, j.claimId());
        m.put(KEY_MATERIAL_POLICY, j.materialPolicy().name());
        m.put(KEY_JOURNAL_FILE, j.journalFile());
        m.put(KEY_LEDGER_FILE, j.ledgerFile());
        m.put(KEY_LAST_ERROR, j.lastError());
        m.put(KEY_CREATED_TICK, j.createdTick());
        List<Object> risks = new ArrayList<>();
        for (String r : j.acceptedRiskIds()) {
            risks.add(r);
        }
        m.put(KEY_ACCEPTED_RISKS, risks);
        return m;
    }

    public static ConstructionJob fromTree(Object tree) {
        Map<String, Object> m = JsonReads.map(tree, "job");
        String pause = JsonReads.stringOrNull(m.get(KEY_PAUSE_REASON), KEY_PAUSE_REASON);
        List<Object> risks = JsonReads.list(m.get(KEY_ACCEPTED_RISKS), KEY_ACCEPTED_RISKS);
        List<String> accepted = new ArrayList<>();
        for (Object r : risks) {
            accepted.add(JsonReads.string(r, "accepted risk id"));
        }
        return new ConstructionJob(JsonReads.integer(m.get(KEY_SCHEMA_VERSION), KEY_SCHEMA_VERSION),
                JsonReads.string(m.get(KEY_JOB_ID), KEY_JOB_ID),
                UUID.fromString(JsonReads.string(m.get(KEY_OWNER), KEY_OWNER)),
                JsonReads.string(m.get(KEY_DIMENSION), KEY_DIMENSION),
                JsonReads.string(m.get(KEY_MANIFEST_HASH), KEY_MANIFEST_HASH),
                JobKind.valueOf(JsonReads.string(m.get(KEY_KIND), KEY_KIND)),
                JsonReads.stringOrNull(m.get(KEY_PARENT), KEY_PARENT),
                JobState.valueOf(JsonReads.string(m.get(KEY_STATE), KEY_STATE)),
                pause == null ? null : PauseReason.valueOf(pause),
                JsonReads.integer(m.get(KEY_CURSOR), KEY_CURSOR),
                JsonReads.integer(m.get(KEY_TOTAL), KEY_TOTAL),
                JsonReads.integer(m.get(KEY_REPAIR_ROUND), KEY_REPAIR_ROUND),
                JsonReads.string(m.get(KEY_CLAIM_ID), KEY_CLAIM_ID),
                MaterialPolicy.valueOf(JsonReads.string(m.get(KEY_MATERIAL_POLICY), KEY_MATERIAL_POLICY)),
                JsonReads.string(m.get(KEY_JOURNAL_FILE), KEY_JOURNAL_FILE),
                JsonReads.string(m.get(KEY_LEDGER_FILE), KEY_LEDGER_FILE),
                JsonReads.string(m.get(KEY_LAST_ERROR), KEY_LAST_ERROR),
                JsonReads.longValue(m.get(KEY_CREATED_TICK), KEY_CREATED_TICK),
                accepted);
    }
}
