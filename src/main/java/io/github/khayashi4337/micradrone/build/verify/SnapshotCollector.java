package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the positions of a program over several ticks (F-21: a bounded number per tick) into windows of at most
 * {@link SparseSnapshot#MAX_POSITIONS}. An unloaded position stays unread until {@link #resetUnloaded} queues it again.
 */
public final class SnapshotCollector {
    public static final int DEFAULT_READS_PER_TICK = 1_000;

    public record Window(int fromIndex, int toIndexExclusive, SparseSnapshot snapshot) {
    }

    private final List<IntPos> positions;
    private final Map<IntPos, ObservedBlock> read = new HashMap<>();
    private final Set<IntPos> unloaded = new LinkedHashSet<>();
    private final ArrayDeque<IntPos> retry = new ArrayDeque<>();
    private int windowStart;
    private int next;

    public SnapshotCollector(List<IntPos> positions) {
        this.positions = List.copyOf(positions);
    }

    private int windowEnd() {
        return Math.min(positions.size(), windowStart + SparseSnapshot.MAX_POSITIONS);
    }

    public List<IntPos> nextBatch(int max) {
        List<IntPos> out = new ArrayList<>();
        while (out.size() < max && !retry.isEmpty()) {
            out.add(retry.poll());
        }
        while (out.size() < max && next < windowEnd()) {
            out.add(positions.get(next++));
        }
        return out;
    }

    public void accept(IntPos pos, ObservedBlock block) {
        read.put(pos, block);
    }

    public void unloaded(IntPos pos) {
        unloaded.add(pos);
    }

    public List<IntPos> unloadedPositions() {
        return List.copyOf(unloaded);
    }

    /** Queues the unloaded positions again (after the job resumes from CHUNK_UNLOADED). */
    public void resetUnloaded() {
        retry.addAll(unloaded);
        unloaded.clear();
    }

    public boolean windowFull() {
        return next >= windowEnd() && unloaded.isEmpty() && retry.isEmpty();
    }

    public Window takeWindow() {
        if (!windowFull()) {
            throw new IllegalStateException("the window is not read yet");
        }
        Window w = new Window(windowStart, windowEnd(), new SparseSnapshot(read));
        read.clear();
        windowStart = windowEnd();
        next = windowStart;
        return w;
    }

    public boolean done() {
        return windowStart >= positions.size();
    }
}
