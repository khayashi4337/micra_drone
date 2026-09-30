package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import java.util.Set;

/** Which placements a comparison covers (design 01, section 8). IndexRange lets a large manifest be checked in windows. */
public sealed interface CompareScope {
    All ALL = new All();

    boolean includes(Placement p);

    record UpToCursor(int cursor) implements CompareScope {
        @Override
        public boolean includes(Placement p) {
            return p.index() < cursor;
        }
    }

    record Phases(Set<BuildPhase> phases) implements CompareScope {
        public Phases {
            phases = Set.copyOf(phases);
        }

        @Override
        public boolean includes(Placement p) {
            return phases.contains(p.phase());
        }
    }

    record IndexRange(int from, int toExclusive) implements CompareScope {
        @Override
        public boolean includes(Placement p) {
            return p.index() >= from && p.index() < toExclusive;
        }
    }

    record All() implements CompareScope {
        @Override
        public boolean includes(Placement p) {
            return true;
        }
    }
}
