package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.parts.ParamSpec;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/** Random valid instances of the freestanding building parts, spread far apart so they never overlap. */
public final class RandomParts {
    private static final String DOCK_PAD = "micra:dock_pad";
    public static final List<String> FREESTANDING = List.of("micra:pillar", "micra:beam", "micra:chimney",
            "micra:stairs", "micra:ladder", "micra:catwalk", "micra:railing", "micra:ramp", "micra:lamp", "micra:road",
            DOCK_PAD);
    /** Local u between consecutive parts: wider than the largest footprint any of them can roll. */
    public static final int SPACING = 400;
    /** Random int params stay within this many of their minimum so footprints stay small and inside the bounds. */
    private static final int INT_PARAM_SPAN = 12;
    /**
     * Seeds 1..20 fed straight into {@code new Random(seed)} correlate the first draws: every pillar rolled
     * turns=2 and the stairs dir only ever came out north or west. Node rots and enum params are therefore
     * enumerated deterministically from {@code seed + partIndex} so every value occurs across the seeds;
     * int and bool params still draw from a {@link Random} on a scrambled seed.
     */
    private static final long SEED_SCRAMBLE = 7919L;
    private static final int QUARTER_TURNS = 4;
    private static final int MIRROR_SIDES = 2;
    private static final String CARGO_U = "cargo_u";
    private static final String CARGO_W = "cargo_w";
    /** The pad corner: a cargo position that always lies on the pad, whatever the random size is. */
    private static final int CARGO_CORNER = 0;

    private RandomParts() {
    }

    static Map<String, ParamValue> randomParams(PartType type, Random rnd, long pick) {
        Map<String, ParamValue> out = new TreeMap<>();
        for (ParamSpec p : type.params()) {
            switch (p.type()) {
                case INT -> {
                    int min = ((ParamValue.IntV) p.min()).value();
                    int max = Math.min(((ParamValue.IntV) p.max()).value(), min + INT_PARAM_SPAN);
                    out.put(p.name(), new ParamValue.IntV(min + rnd.nextInt(max - min + 1)));
                }
                case BOOL -> out.put(p.name(), new ParamValue.BoolV(rnd.nextBoolean()));
                case ENUM -> out.put(p.name(), new ParamValue.StrV(
                        p.enumValues().get((int) Math.floorMod(pick, p.enumValues().size()))));
                default -> {
                    // materials keep their default role; texts and lists are not used by the freestanding parts
                }
            }
        }
        // a dock pad's cargo position must lie on the pad
        if (type.id().equals(DOCK_PAD)) {
            out.put(CARGO_U, new ParamValue.IntV(CARGO_CORNER));
            out.put(CARGO_W, new ParamValue.IntV(CARGO_CORNER));
        }
        return out;
    }

    public static List<PlanNode> nodes(long seed) {
        Random rnd = new Random(seed * SEED_SCRAMBLE);
        List<PlanNode> nodes = new ArrayList<>();
        int k = 0;
        for (String id : FREESTANDING) {
            PartType type = CompileFixtures.REGISTRY.get(id);
            long pick = seed + k;
            Rot rot = new Rot((int) (pick % QUARTER_TURNS), pick % MIRROR_SIDES == 0);
            nodes.add(new PlanNode("p-" + k, id, null, new Anchor.Absolute(new LocalPos(k * SPACING, 0, 0), rot),
                    randomParams(type, rnd, pick), Set.of(), ""));
            k++;
        }
        return nodes;
    }
}
