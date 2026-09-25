package io.github.khayashi4337.micradrone.build;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.PartCategory;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.parts.PortKind;
import io.github.khayashi4337.micradrone.build.parts.PortSpec;
import io.github.khayashi4337.micradrone.build.parts.VersionRange;
import io.github.khayashi4337.micradrone.build.plan.ModuleTemplate;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared fixtures: the building parts plus a few made-up machine parts that have ports. */
public final class TestParts {
    private static final String MOTOR_ID = "test:motor";
    private static final String PRESS_ID = "test:press";
    private static final String SHAFT_ID = "test:shaft";
    private static final String PORT_OUT = "out";
    private static final String PORT_IN = "in";
    private static final String NODE_MOTOR = "motor";
    private static final String NODE_SHAFT = "shaft";
    private static final String LINE_TEMPLATE_ID = "mod:test_line";
    private static final int LINE_SCHEMA_VERSION = 1;
    private static final Box LINE_FOOTPRINT = new Box(0, 0, 0, 3, 0, 1);

    private TestParts() {
    }

    public static PartTypeRegistry registry() {
        PartTypeRegistry.Builder b = PartTypeRegistry.builder().defaultPalette(BuildingParts.DEFAULT_PALETTE);
        for (PartType t : BuildingParts.registry().all()) {
            b.register(t);
        }
        b.register(PartType.builder(MOTOR_ID, PartCategory.POWER).displayNameKey("t.motor")
                .ports(new PortSpec(PORT_OUT, PortKind.ROTATION_OUT, new LocalPos(1, 0, 0), Dir6.EAST, Set.of())).build());
        b.register(PartType.builder(PRESS_ID, PartCategory.PROCESSING).displayNameKey("t.press")
                .ports(new PortSpec("power_in", PortKind.ROTATION_IN, new LocalPos(-1, 0, 0), Dir6.WEST, Set.of()),
                        new PortSpec("item_in", PortKind.ITEM_IN, new LocalPos(0, 1, 0), Dir6.UP, Set.of()),
                        new PortSpec("item_out", PortKind.ITEM_OUT, new LocalPos(0, -1, 0), Dir6.DOWN, Set.of())).build());
        b.register(PartType.builder(SHAFT_ID, PartCategory.TRANSMISSION).displayNameKey("t.shaft")
                .ports(new PortSpec(PORT_IN, PortKind.ROTATION_IN, new LocalPos(-1, 0, 0), Dir6.WEST, Set.of()),
                        new PortSpec(PORT_OUT, PortKind.ROTATION_OUT, new LocalPos(1, 0, 0), Dir6.EAST, Set.of())).build());
        return b.build();
    }

    public static PlanNode at(String id, String type, String parent, int u, int v, int w) {
        return new PlanNode(id, type, parent, new Anchor.Absolute(new LocalPos(u, v, w), Rot.NONE), Map.of(), Set.of(), "");
    }

    /** A module made of a motor and a shaft child of it; exposes the shaft's output. */
    public static ModuleTemplate lineTemplate() {
        return new ModuleTemplate(LINE_SCHEMA_VERSION, LINE_TEMPLATE_ID, "t.line", PartCategory.MODULE, VersionRange.ALWAYS,
                LINE_FOOTPRINT,
                List.of(new PortSpec(PORT_OUT, PortKind.ROTATION_OUT, new LocalPos(3, 0, 0), Dir6.EAST, Set.of())),
                List.of(at(NODE_MOTOR, MOTOR_ID, null, 2, 0, 1), at(NODE_SHAFT, SHAFT_ID, NODE_MOTOR, 1, 0, 0)),
                List.of(new Connection("link", new PortRef(NODE_MOTOR, PORT_OUT), new PortRef(NODE_SHAFT, PORT_IN),
                        ConnKind.ROTATION, new Routing.Explicit(List.of()), Constraints.NONE)),
                null, null, Set.of("test"));
    }

    public static TemplateBundle bundle() {
        return new TemplateBundle(List.of(lineTemplate()));
    }
}
