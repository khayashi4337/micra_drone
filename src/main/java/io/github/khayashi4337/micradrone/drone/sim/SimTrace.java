package io.github.khayashi4337.micradrone.drone.sim;

import java.util.List;

/**
 * The complete result of one dry-run simulation: every recorded frame in execution order (frame 0
 * is the untouched starting state), the print() output, and how the run ended - {@code error} is
 * null for a clean finish and carries the "line N: ..." message (syntax or runtime) otherwise;
 * {@code truncated} is true when the script was cut off at {@link SimDroneApi#MAX_STEPS} builtin
 * calls (e.g. an endless while-loop) - everything recorded up to that point is still playable.
 */
public record SimTrace(int worldSize, List<SimFrame> frames, List<SimLogLine> logLines, String error, boolean truncated) {
}
