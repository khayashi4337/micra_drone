package io.github.khayashi4337.micradrone.construction.core;

import java.util.Objects;

/** The verdict for one placement against what the world holds now. */
public sealed interface ReplaceDecision {
    record Place(Destruction destruction) implements ReplaceDecision {
        public Place {
            Objects.requireNonNull(destruction, "destruction");
        }
    }

    record AlreadyDone() implements ReplaceDecision {
    }

    record Refused(Refusal refusal) implements ReplaceDecision {
        public Refused {
            Objects.requireNonNull(refusal, "refusal");
        }
    }
}
