package io.github.khayashi4337.micradrone.lang;

/**
 * The typed "the script's argument was malformed" signal of a construction command: the command
 * dispatcher turns exactly this type into a {@link MicraLangException} naming the command and the
 * line. It deliberately extends {@link IllegalArgumentException} so the {@link PlanApi} contract
 * stays readable as "bad argument" for other callers, while the dispatcher's narrow catch keeps an
 * untyped {@code IllegalArgumentException} - a defect inside an implementation, not a bad value the
 * script handed over - propagating as the bug it is instead of being blamed on the script.
 * A limit on how much one run may keep is the sibling {@link PlanBudgetException}, reported as
 * E-SCRIPT-LIMIT instead.
 */
public final class PlanArgumentException extends IllegalArgumentException {
    public PlanArgumentException(String message) {
        super(message);
    }

    public PlanArgumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
