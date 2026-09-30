package io.github.khayashi4337.micradrone.construction.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The job state machine of design 01, section 8, as one table. A move that is not in the table is a bug in the caller,
 * never a user error, so it throws instead of being ignored.
 */
public final class JobStateMachine {
    private static final Map<JobState, Map<JobEvent, JobState>> TABLE = new EnumMap<>(JobState.class);
    private static final List<JobState> PAUSABLE = List.of(JobState.QUEUED, JobState.RUNNING, JobState.ASSEMBLING,
            JobState.VERIFYING, JobState.REPAIRING);
    private static final List<JobState> LIVE = List.of(JobState.PENDING_APPROVAL, JobState.QUEUED, JobState.RUNNING,
            JobState.PAUSED, JobState.ASSEMBLING, JobState.VERIFYING, JobState.REPAIRING);
    /** The endings a claim's rollback closes: when its ROLLBACK job ends, the claim's other jobs become ROLLED_BACK. */
    private static final List<JobState> ROLLBACKABLE = List.of(JobState.VERIFIED, JobState.PARTIAL, JobState.FAILED,
            JobState.CANCELLED);

    static {
        for (JobState s : JobState.values()) {
            TABLE.put(s, new EnumMap<>(JobEvent.class));
        }
        allow(JobState.PENDING_APPROVAL, JobEvent.ADMITTED, JobState.QUEUED);
        allow(JobState.PENDING_APPROVAL, JobEvent.CLAIM_REFUSED, JobState.CANCELLED);
        allow(JobState.QUEUED, JobEvent.START, JobState.RUNNING);
        allow(JobState.RUNNING, JobEvent.PLACED_ALL, JobState.VERIFYING);
        allow(JobState.RUNNING, JobEvent.NEED_ASSEMBLY, JobState.ASSEMBLING);
        allow(JobState.ASSEMBLING, JobEvent.ASSEMBLED, JobState.VERIFYING);
        allow(JobState.VERIFYING, JobEvent.CLEAN, JobState.VERIFIED);
        allow(JobState.VERIFYING, JobEvent.NEED_REPAIR, JobState.REPAIRING);
        allow(JobState.VERIFYING, JobEvent.GIVE_UP, JobState.PARTIAL);
        allow(JobState.REPAIRING, JobEvent.REPAIRED, JobState.VERIFYING);
        allow(JobState.PAUSED, JobEvent.RESUME, JobState.QUEUED);
        for (JobState s : PAUSABLE) {
            allow(s, JobEvent.PAUSE, JobState.PAUSED);
        }
        for (JobState s : LIVE) {
            allow(s, JobEvent.CANCEL, JobState.CANCELLED);
            allow(s, JobEvent.FAIL, JobState.FAILED);
        }
        for (JobState s : ROLLBACKABLE) {
            allow(s, JobEvent.ROLL_BACK_DONE, JobState.ROLLED_BACK);
        }
    }

    private JobStateMachine() {
    }

    private static void allow(JobState from, JobEvent event, JobState to) {
        TABLE.get(from).put(event, to);
    }

    public static boolean allows(JobState from, JobEvent event) {
        return TABLE.get(from).containsKey(event);
    }

    public static JobState next(JobState from, JobEvent event) {
        JobState to = TABLE.get(from).get(event);
        if (to == null) {
            throw new IllegalStateException("a job in " + from + " cannot take " + event);
        }
        return to;
    }
}
