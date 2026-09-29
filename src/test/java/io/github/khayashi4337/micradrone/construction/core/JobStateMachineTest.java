package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobStateMachineTest {
    private record T(JobState from, JobEvent event, JobState to) {
    }

    private static final int LISTED = 34;
    private static final List<JobState> PAUSABLE = List.of(JobState.QUEUED, JobState.RUNNING, JobState.ASSEMBLING,
            JobState.VERIFYING, JobState.REPAIRING);
    private static final List<JobState> LIVE = List.of(JobState.PENDING_APPROVAL, JobState.QUEUED, JobState.RUNNING,
            JobState.PAUSED, JobState.ASSEMBLING, JobState.VERIFYING, JobState.REPAIRING);
    private static final List<JobState> ROLLBACKABLE = List.of(JobState.VERIFIED, JobState.PARTIAL, JobState.FAILED,
            JobState.CANCELLED);

    private static Set<T> expected() {
        Set<T> out = new HashSet<>(List.of(
                new T(JobState.PENDING_APPROVAL, JobEvent.ADMITTED, JobState.QUEUED),
                new T(JobState.PENDING_APPROVAL, JobEvent.CLAIM_REFUSED, JobState.CANCELLED),
                new T(JobState.QUEUED, JobEvent.START, JobState.RUNNING),
                new T(JobState.RUNNING, JobEvent.PLACED_ALL, JobState.VERIFYING),
                new T(JobState.RUNNING, JobEvent.NEED_ASSEMBLY, JobState.ASSEMBLING),
                new T(JobState.ASSEMBLING, JobEvent.ASSEMBLED, JobState.VERIFYING),
                new T(JobState.VERIFYING, JobEvent.CLEAN, JobState.VERIFIED),
                new T(JobState.VERIFYING, JobEvent.NEED_REPAIR, JobState.REPAIRING),
                new T(JobState.VERIFYING, JobEvent.GIVE_UP, JobState.PARTIAL),
                new T(JobState.REPAIRING, JobEvent.REPAIRED, JobState.VERIFYING),
                new T(JobState.PAUSED, JobEvent.RESUME, JobState.QUEUED)));
        for (JobState s : PAUSABLE) {
            out.add(new T(s, JobEvent.PAUSE, JobState.PAUSED));
        }
        for (JobState s : LIVE) {
            out.add(new T(s, JobEvent.CANCEL, JobState.CANCELLED));
            out.add(new T(s, JobEvent.FAIL, JobState.FAILED));
        }
        for (JobState s : ROLLBACKABLE) {
            out.add(new T(s, JobEvent.ROLL_BACK_DONE, JobState.ROLLED_BACK));
        }
        return out;
    }

    @Test
    void everyListedTransitionIsAllowedAndLandsWhereTheDesignSays() {
        Set<T> table = expected();
        assertEquals(LISTED, table.size(), "11 named moves + 5 pauses + 7 cancels + 7 failures + 4 rollbacks");
        for (T t : table) {
            assertTrue(JobStateMachine.allows(t.from(), t.event()), t.toString());
            assertEquals(t.to(), JobStateMachine.next(t.from(), t.event()), t.toString());
        }
    }

    @Test
    void everyOtherPairIsRefusedAsABug() {
        Set<T> table = expected();
        int refused = 0;
        for (JobState s : JobState.values()) {
            for (JobEvent e : JobEvent.values()) {
                boolean listed = table.stream().anyMatch(t -> t.from() == s && t.event() == e);
                if (!listed) {
                    refused++;
                    assertFalse(JobStateMachine.allows(s, e), s + " + " + e);
                    assertThrows(IllegalStateException.class, () -> JobStateMachine.next(s, e), s + " + " + e);
                }
            }
        }
        assertEquals(JobState.values().length * JobEvent.values().length - LISTED, refused);
    }

    @Test
    void terminalStatesAreExactlyTheFiveEndings() {
        Set<JobState> terminal = new HashSet<>();
        for (JobState s : JobState.values()) {
            if (s.terminal()) {
                terminal.add(s);
            }
        }
        assertEquals(Set.of(JobState.VERIFIED, JobState.PARTIAL, JobState.FAILED, JobState.CANCELLED, JobState.ROLLED_BACK),
                terminal);
        assertFalse(JobStateMachine.allows(JobState.ROLLED_BACK, JobEvent.CANCEL), "rolled back is final");
    }
}
