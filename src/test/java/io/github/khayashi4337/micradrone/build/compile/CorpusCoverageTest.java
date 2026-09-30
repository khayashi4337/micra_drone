package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.ParamSpec;
import io.github.khayashi4337.micradrone.build.parts.ParamType;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The plans the invariance tests run — the six showcase roofs and twenty seeds of random freestanding parts —
 * are also the parameter corpus: every declared enum value must be resolved by at least one corpus node, and a
 * list of less-common values must be set explicitly. A schema change that adds a value nobody exercises fails
 * here instead of shipping untested.
 */
class CorpusCoverageTest {
    private static final long FIRST_SEED = 1;
    private static final long LAST_SEED = 20;

    /** Every node the corpus builds, showcase and freestanding alike. */
    private static List<PlanNode> corpusNodes() {
        List<PlanNode> nodes = new ArrayList<>();
        for (String roof : ShowcasePlans.ROOFS) {
            nodes.addAll(ShowcasePlans.showcase(roof, Facing.NORTH).nodes());
        }
        for (long seed = FIRST_SEED; seed <= LAST_SEED; seed++) {
            nodes.addAll(RandomParts.nodes(seed));
        }
        return nodes;
    }

    /** Every value every corpus node resolves a parameter to, keyed {@code "partId#paramName"}. */
    private static Map<String, Set<Object>> corpusValues() {
        Map<String, Set<Object>> used = new TreeMap<>();
        for (PlanNode n : corpusNodes()) {
            PartType type = CompileFixtures.REGISTRY.get(n.type());
            Params resolved = Params.resolve(type, n.params());
            for (ParamSpec spec : type.params()) {
                if (resolved.has(spec.name())) {
                    used.computeIfAbsent(n.type() + "#" + spec.name(), k -> new HashSet<>())
                            .add(valueOf(resolved, spec));
                }
            }
        }
        return used;
    }

    private static Object valueOf(Params resolved, ParamSpec spec) {
        return switch (spec.type()) {
            case INT -> resolved.i(spec.name());
            case NUM -> resolved.d(spec.name());
            case BOOL -> resolved.b(spec.name());
            case ENUM, STR, MATERIAL -> resolved.s(spec.name());
            case INT_LIST -> resolved.ints(spec.name());
        };
    }

    @Test
    void everyEnumValueOfEveryParameterIsExercisedSomewhere() {
        Map<String, Set<Object>> used = corpusValues();
        for (PartType type : BuildingParts.registry().all()) {
            for (ParamSpec spec : type.params()) {
                if (spec.type() == ParamType.ENUM) {
                    assertEquals(new TreeSet<>(spec.enumValues()),
                            new TreeSet<>(used.getOrDefault(type.id() + "#" + spec.name(), Set.of())),
                            type.id() + "#" + spec.name() + ": the corpus never exercises every value");
                }
            }
        }
    }

    @Test
    void theCorpusReachesTheUncommonParameterValues() {
        Map<String, Set<Object>> used = corpusValues();
        // a multi-storey structure
        assertTrue(used.getOrDefault("micra:structure#floors", Set.of()).contains(2),
                "no structure with floors=2");
        // a wall thicker than the doubled west wall
        assertTrue(used.getOrDefault("micra:wall#thickness", Set.of()).contains(3),
                "no wall with thickness=3");
        // a partial wall: a segment that starts inside the span
        assertTrue(used.getOrDefault("micra:wall#from", Set.of()).contains(1), "no partial wall (from=1)");
        // a balcony with no railing
        assertTrue(used.getOrDefault("micra:balcony#rail", Set.of()).contains(false),
                "no balcony with rail=false");
        // a gable with its end triangles left open
        assertTrue(used.getOrDefault("micra:roof#gable_fill", Set.of()).contains(false),
                "no roof with gable_fill=false");
        // a sawtooth with the narrowest possible teeth
        assertTrue(used.getOrDefault("micra:roof#tooth", Set.of()).contains(2), "no sawtooth with tooth=2");
        // a monitor slit wider and taller than the default
        assertTrue(used.getOrDefault("micra:roof#monitor_width", Set.of()).contains(3),
                "no monitor with monitor_width=3");
        assertTrue(used.getOrDefault("micra:roof#monitor_height", Set.of()).contains(2),
                "no monitor with monitor_height=2");
    }
}
