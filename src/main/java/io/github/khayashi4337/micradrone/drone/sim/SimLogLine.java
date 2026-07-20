package io.github.khayashi4337.micradrone.drone.sim;

/**
 * One print() output line from a simulated script, tagged with the index of the {@link SimFrame}
 * it belongs to so the bird's-eye panel can reveal log lines in sync with playback (show every
 * line whose frameIndex <= the frame currently displayed).
 */
public record SimLogLine(int frameIndex, String text) {
}
