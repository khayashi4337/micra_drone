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
    private static final String CARGO_U = "cargo_u";
    private static final String CARGO_W = "cargo_w";
    /** The pad corner: a cargo position that always lies on the pad, whatever the random size is. */
    private static final int CARGO_CORNER = 0;

    private RandomParts() {
    }

    static Map<String, ParamValue> randomParams(PartType type, Random rnd) {
        Map<String, ParamValue> out = new TreeMap<>();
        for (ParamSpec p : type.params()) {
            switch (p.type()) {
                case INT -> {
                    int min = ((ParamValue.IntV) p.min()).value();
                    int max = Math.min(((ParamValue.IntV) p.max()).value(), min + INT_PARAM_SPAN);
                    out.put(p.name(), new ParamValue.IntV(min + rnd.nextInt(max - min + 1)));
                }
                case BOOL -> out.put(p.name(), new ParamValue.BoolV(rnd.nextBoolean()));
                case ENUM -> out.put(p.name(), new ParamValue.StrV(p.enumValues().get(rnd.nextInt(p.enumValues().size()))));
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
        Random rnd = new Random(seed);
        List<PlanNode> nodes = new ArrayList<>();
        int k = 0;
        for (String id : FREESTANDING) {
            PartType type = CompileFixtures.REGISTRY.get(id);
            Rot rot = new Rot(rnd.nextInt(4), rnd.nextBoolean());
            nodes.add(new PlanNode("p-" + k, id, null, new Anchor.Absolute(new LocalPos(k * SPACING, 0, 0), rot),
                    randomParams(type, rnd), Set.of(), ""));
            k++;
        }
        return nodes;
    }
}
