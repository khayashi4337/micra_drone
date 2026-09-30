package io.github.khayashi4337.micradrone.build.compile;

import java.util.ArrayList;
import java.util.List;

/** The runs of equal construction phases in a placement list, as index ranges (design 01, section 4). */
public final class PhaseRanges {
    private PhaseRanges() {
    }

    public static List<PhaseRange> of(List<Placement> placements) {
        List<PhaseRange> phases = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= placements.size(); i++) {
            if (i == placements.size() || placements.get(i).phase() != placements.get(start).phase()) {
                phases.add(new PhaseRange(placements.get(start).phase(), start, i));
                start = i;
            }
        }
        return phases;
    }
}
