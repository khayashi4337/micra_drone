package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Objects;

/**
 * The write-ahead log of the executor (04 F-2, F-7). One executor call is one run: its intents (the world writes, the
 * material operations and the container drops it may do) are on the disk before anything changes, its end (what it
 * really wrote and moved) right after. A durable point says a flushed save of the whole server holds every run up to
 * it; only then are the world and the supply chests known to be on the disk.
 */
public sealed interface WalEntry {
    record RunStart(String jobId, long run) implements WalEntry {
        public RunStart {
            Objects.requireNonNull(jobId, "jobId");
        }
    }

    /** A world write this run may do. */
    record PlaceIntent(String jobId, long run, JournalRecord record) implements WalEntry {
        public PlaceIntent {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(record, "record");
        }
    }

    record MaterialIntent(String jobId, long run, OpKey key, List<ItemCount> items) implements WalEntry {
        public MaterialIntent {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(key, "key");
            items = List.copyOf(items);
        }
    }

    /**
     * A container's contents about to be dropped as item entities (a removal with dropContents). Best effort: dropped
     * items live in chunk data, so a crash before a durable point can lose them; recovery only reports the position.
     */
    record DropIntent(String jobId, long run, IntPos pos) implements WalEntry {
        public DropIntent {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(pos, "pos");
        }
    }

    /** The moves one material operation really made, per source. */
    record OpMoves(OpKey key, List<Move> moves) {
        public OpMoves {
            Objects.requireNonNull(key, "key");
            moves = List.copyOf(moves);
        }
    }

    record RunEnd(String jobId, long run, List<Integer> written, List<OpMoves> moves) implements WalEntry {
        public RunEnd {
            Objects.requireNonNull(jobId, "jobId");
            written = List.copyOf(written);
            moves = List.copyOf(moves);
        }
    }

    /** A flushed save of the whole server (chunks, entities, saved data, job files) holds every run up to this. */
    record DurablePoint(long upToRun) implements WalEntry {
    }

    /** The run an entry belongs to (a durable point: the last run it covers). */
    static long runOf(WalEntry e) {
        return switch (e) {
            case RunStart s -> s.run();
            case PlaceIntent p -> p.run();
            case MaterialIntent m -> m.run();
            case DropIntent d -> d.run();
            case RunEnd r -> r.run();
            case DurablePoint d -> d.upToRun();
        };
    }
}
