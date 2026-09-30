package io.github.khayashi4337.micradrone.construction.core;

/** What happens to a job; the state machine decides where it leads. */
public enum JobEvent {
    ADMITTED, CLAIM_REFUSED, START, PAUSE, RESUME, PLACED_ALL, NEED_ASSEMBLY, ASSEMBLED, CLEAN, NEED_REPAIR, REPAIRED,
    GIVE_UP, FAIL, CANCEL, ROLL_BACK_DONE
}
