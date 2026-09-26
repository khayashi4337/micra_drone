package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.parts.AssemblyExpectation.ContraptionExpectation;
import io.github.khayashi4337.micradrone.build.parts.AssemblyExpectation.SubLevelExpectation;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The registry version is the gate that refuses a manifest, job or approval made against another registry, so a part
 * field that the hash ignores would let a changed part pass. One part with every field filled in (no default, no null)
 * is built, then each field of it, and of every record nested in it, is changed once on its own: the version must
 * differ from the unchanged part's each time, and from every other change's.
 */
class PartTypeVersionTest {
    private static final String PALETTE_ROLE = "wall";
    private static final String PALETTE_BLOCK = "minecraft:stone";
    /** Separator of the parts of a variation's name: {@code Record.field} or {@code Record.field.detail}. */
    private static final String NAME_SEPARATOR = ".";

    /** ParamSpec with every field set, as a mutable draft so that a test changes one of them. */
    private static final class ParamDraft {
        String name = "speed";
        ParamType type = ParamType.INT;
        String unit = "rpm";
        ParamValue min = new IntV(1);
        ParamValue max = new IntV(9);
        ParamValue defaultValue = new IntV(3);
        List<String> enumValues = List.of("slow", "fast");
        int maxItems = 4;

        ParamSpec build() {
            return new ParamSpec(name, type, unit, min, max, defaultValue, enumValues, maxItems);
        }
    }

    private static final class PortDraft {
        String name = "out";
        PortKind kind = PortKind.ROTATION_OUT;
        int u = 1;
        int v = 2;
        int w = 3;
        Dir6 facing = Dir6.EAST;
        Set<String> accepts = Set.of("clean");

        PortSpec build() {
            return new PortSpec(name, kind, new LocalPos(u, v, w), facing, accepts);
        }
    }

    /** A box as six numbers, so that each can be changed alone. */
    private static final class BoxDraft {
        int minA;
        int minB;
        int minC;
        int maxA;
        int maxB;
        int maxC;

        BoxDraft(int minA, int minB, int minC, int maxA, int maxB, int maxC) {
            this.minA = minA;
            this.minB = minB;
            this.minC = minC;
            this.maxA = maxA;
            this.maxB = maxB;
            this.maxC = maxC;
        }

        Box build() {
            return new Box(minA, minB, minC, maxA, maxB, maxC);
        }
    }

    /** A PartType with all 16 fields set to a value that is not the field's default, and every nested field set too. */
    private static final class Draft {
        String id = "test:full";
        PartCategory category = PartCategory.POWER;
        Visibility visibility = Visibility.USER;
        String displayNameKey = "key.full";
        String visualDescription = "A part that uses every field.";
        ParamDraft param = new ParamDraft();
        ParamDraft secondParam = null;
        PortDraft port = new PortDraft();
        PortDraft secondPort = null;
        BoxDraft box = new BoxDraft(0, 0, 0, 1, 2, 3);
        BoxDraft secondBox = null;
        Map<String, String> sizeFromParams = Map.of("width", "speed");
        String requiresModId = "create";
        String requiresRange = "[6.0.10,6.1.0)";
        PlacerId placer = PlacerId.BELT;
        VerifyMode verify = VerifyMode.STATE_SUBSET;
        Set<String> volatileProps = Set.of("open");
        EffectKind effectKind = EffectKind.BREAK;
        BoxDraft reach = new BoxDraft(-1, -2, -3, 4, 5, 6);
        AssemblyKind assemblyKind = AssemblyKind.BEARING;
        String triggerPort = "power_in";
        AssemblyExpectation expect = new ContraptionExpectation(1, 12);
        String disassembleAction = "stop";
        boolean hasAssembly = true;
        String modelId = "motor";
        boolean hasModel = true;
        BuildPhase phase = BuildPhase.POWER;

        PartType build() {
            List<ParamSpec> params = secondParam == null ? List.of(param.build())
                    : List.of(param.build(), secondParam.build());
            List<PortSpec> ports = secondPort == null ? List.of(port.build()) : List.of(port.build(), secondPort.build());
            List<Box> boxes = new ArrayList<>();
            if (box != null) {
                boxes.add(box.build());
            }
            if (secondBox != null) {
                boxes.add(secondBox.build());
            }
            AssemblySpec assembly = hasAssembly ? new AssemblySpec(assemblyKind, triggerPort, expect, disassembleAction) : null;
            return new PartType(id, category, visibility, displayNameKey, visualDescription, params, ports,
                    new VolumeSpec(boxes, sizeFromParams), new VersionRange(requiresModId, requiresRange), placer, verify,
                    volatileProps, new EffectSpec(effectKind, reach == null ? null : reach.build()), assembly,
                    hasModel ? new ModelRef(modelId) : null, phase);
        }
    }

    private static ParamDraft paramNamed(String name) {
        ParamDraft p = new ParamDraft();
        p.name = name;
        return p;
    }

    private static PortDraft portNamed(String name) {
        PortDraft p = new PortDraft();
        p.name = name;
        return p;
    }

    private static String versionOf(PartType type) {
        return PartTypeRegistry.builder().register(type).defaultPalette(Map.of(PALETTE_ROLE, PALETTE_BLOCK)).build().version();
    }

    /** Every top-level field of PartType, and every field of the records nested in it, changed once (assembly has a contraption expectation). */
    private static Map<String, Consumer<Draft>> variations() {
        Map<String, Consumer<Draft>> v = new LinkedHashMap<>();
        // the 16 top-level fields
        v.put("PartType.id", d -> d.id = "test:other");
        v.put("PartType.category", d -> d.category = PartCategory.FLUID);
        v.put("PartType.visibility", d -> d.visibility = Visibility.IMPLICIT);
        v.put("PartType.displayNameKey", d -> d.displayNameKey = "key.other");
        v.put("PartType.visualDescription", d -> d.visualDescription = "Another description.");
        v.put("PartType.params", d -> d.secondParam = paramNamed("torque"));
        v.put("PartType.ports", d -> d.secondPort = portNamed("in"));
        v.put("PartType.volume", d -> {
            d.box = null;
            d.sizeFromParams = VolumeSpec.GENERATED.sizeFromParams();
        });
        v.put("PartType.requires", d -> {
            d.requiresModId = VersionRange.ALWAYS.modId();
            d.requiresRange = VersionRange.ALWAYS.mavenRange();
        });
        v.put("PartType.placer", d -> d.placer = PlacerId.ARM);
        v.put("PartType.verify", d -> d.verify = VerifyMode.BLOCK_ONLY);
        v.put("PartType.volatileProps", d -> d.volatileProps = Set.of("open", "powered"));
        v.put("PartType.effect", d -> {
            d.effectKind = EffectSpec.NONE.kind();
            d.reach = null;
        });
        v.put("PartType.assembly", d -> d.hasAssembly = false);
        v.put("PartType.kineticModel", d -> d.hasModel = false);
        v.put("PartType.phase", d -> d.phase = BuildPhase.DOWNSTREAM);
        // ParamSpec: 8 fields (and the absent forms of the three that may be null)
        v.put("ParamSpec.name", d -> d.param.name = "rate");
        v.put("ParamSpec.type", d -> d.param.type = ParamType.NUM);
        v.put("ParamSpec.unit", d -> d.param.unit = "rps");
        v.put("ParamSpec.min", d -> d.param.min = new IntV(2));
        v.put("ParamSpec.min.absent", d -> d.param.min = null);
        v.put("ParamSpec.max", d -> d.param.max = new IntV(10));
        v.put("ParamSpec.max.absent", d -> d.param.max = null);
        v.put("ParamSpec.defaultValue", d -> d.param.defaultValue = new IntV(4));
        v.put("ParamSpec.defaultValue.absent", d -> d.param.defaultValue = null);
        v.put("ParamSpec.enumValues", d -> d.param.enumValues = List.of("slow", "fast", "warp"));
        v.put("ParamSpec.maxItems", d -> d.param.maxItems = 5);
        // PortSpec: 5 fields; the offset is three numbers
        v.put("PortSpec.name", d -> d.port.name = "shaft");
        v.put("PortSpec.kind", d -> d.port.kind = PortKind.ITEM_OUT);
        v.put("PortSpec.offset.u", d -> d.port.u = 7);
        v.put("PortSpec.offset.v", d -> d.port.v = 7);
        v.put("PortSpec.offset.w", d -> d.port.w = 7);
        v.put("PortSpec.facing", d -> d.port.facing = Dir6.WEST);
        v.put("PortSpec.accepts", d -> d.port.accepts = Set.of("clean", "dry"));
        // VolumeSpec: 2 fields; a box is six numbers
        v.put("VolumeSpec.boxes", d -> d.secondBox = new BoxDraft(5, 5, 5, 6, 6, 6));
        v.put("VolumeSpec.boxes.minA", d -> d.box.minA = -1);
        v.put("VolumeSpec.boxes.minB", d -> d.box.minB = -1);
        v.put("VolumeSpec.boxes.minC", d -> d.box.minC = -1);
        v.put("VolumeSpec.boxes.maxA", d -> d.box.maxA = 8);
        v.put("VolumeSpec.boxes.maxB", d -> d.box.maxB = 8);
        v.put("VolumeSpec.boxes.maxC", d -> d.box.maxC = 8);
        v.put("VolumeSpec.sizeFromParams", d -> d.sizeFromParams = Map.of("width", "torque"));
        // VersionRange: 2 fields
        v.put("VersionRange.modId", d -> d.requiresModId = "createaddition");
        v.put("VersionRange.mavenRange", d -> d.requiresRange = "[6.1.0,6.2.0)");
        // EffectSpec: 2 fields; the reach is six numbers
        v.put("EffectSpec.kind", d -> d.effectKind = EffectKind.FLUID);
        v.put("EffectSpec.reachLocal", d -> d.reach = new BoxDraft(0, 0, 0, 9, 9, 9));
        v.put("EffectSpec.reachLocal.absent", d -> d.reach = null);
        v.put("EffectSpec.reachLocal.minA", d -> d.reach.minA = -9);
        v.put("EffectSpec.reachLocal.minB", d -> d.reach.minB = -9);
        v.put("EffectSpec.reachLocal.minC", d -> d.reach.minC = -9);
        v.put("EffectSpec.reachLocal.maxA", d -> d.reach.maxA = 9);
        v.put("EffectSpec.reachLocal.maxB", d -> d.reach.maxB = 9);
        v.put("EffectSpec.reachLocal.maxC", d -> d.reach.maxC = 9);
        // AssemblySpec: 4 fields; the contraption expectation has two numbers, and its kind matters too
        v.put("AssemblySpec.kind", d -> d.assemblyKind = AssemblyKind.WINDMILL);
        v.put("AssemblySpec.triggerPort", d -> d.triggerPort = "power_out");
        v.put("AssemblySpec.expect.kind", d -> d.expect = new SubLevelExpectation(1, 12));
        v.put("AssemblySpec.expect.absent", d -> d.expect = null);
        v.put("AssemblySpec.expect.entityCount", d -> d.expect = new ContraptionExpectation(2, 12));
        v.put("AssemblySpec.expect.movedBlockCount", d -> d.expect = new ContraptionExpectation(1, 13));
        v.put("AssemblySpec.disassembleAction", d -> d.disassembleAction = "break");
        // ModelRef: 1 field
        v.put("ModelRef.modelId", d -> d.modelId = "engine");
        return v;
    }

    /** The two numbers of a sub-level expectation, which the contraption base above does not reach. */
    private static Map<String, Consumer<Draft>> subLevelVariations() {
        Map<String, Consumer<Draft>> v = new LinkedHashMap<>();
        v.put("AssemblySpec.expect.subLevelCount", d -> d.expect = new SubLevelExpectation(2, 12));
        v.put("AssemblySpec.expect.movedBlockCount", d -> d.expect = new SubLevelExpectation(1, 13));
        return v;
    }

    private static void assertEachChangeChangesTheVersion(Supplier<Draft> base, Map<String, Consumer<Draft>> variations) {
        String baseVersion = versionOf(base.get().build());
        Map<String, String> seen = new HashMap<>();
        seen.put(baseVersion, "the unchanged part");
        for (Map.Entry<String, Consumer<Draft>> variation : variations.entrySet()) {
            Draft d = base.get();
            variation.getValue().accept(d);
            String version = versionOf(d.build());
            assertNotEquals(baseVersion, version, variation.getKey() + " changed but the version did not");
            String clash = seen.put(version, variation.getKey());
            assertNull(clash, variation.getKey() + " and " + clash + " gave the same version");
        }
    }

    @Test
    void everyFieldOfAPartTypeChangesTheRegistryVersion() {
        assertEachChangeChangesTheVersion(Draft::new, variations());
    }

    @Test
    void theSubLevelExpectationFieldsChangeTheRegistryVersion() {
        assertEachChangeChangesTheVersion(() -> {
            Draft d = new Draft();
            d.expect = new SubLevelExpectation(1, 12);
            return d;
        }, subLevelVariations());
    }

    @Test
    void theUnchangedPartHasTheSameVersionEveryTime() {
        assertEquals(versionOf(new Draft().build()), versionOf(new Draft().build()));
    }

    /**
     * A field added to one of these records later must get a variation above, or the version test would stay green
     * without covering it: every record component has to be named by some variation.
     */
    @Test
    void everyRecordComponentHasAVariation() {
        Set<String> named = new TreeSet<>(variations().keySet());
        named.addAll(subLevelVariations().keySet());
        for (Class<?> record : List.of(PartType.class, ParamSpec.class, PortSpec.class, VolumeSpec.class,
                VersionRange.class, EffectSpec.class, AssemblySpec.class, ModelRef.class)) {
            assertTrue(record.isRecord(), record.getName());
            for (RecordComponent c : record.getRecordComponents()) {
                String prefix = record.getSimpleName() + NAME_SEPARATOR + c.getName();
                assertTrue(named.stream().anyMatch(n -> n.equals(prefix) || n.startsWith(prefix + NAME_SEPARATOR)),
                        prefix + " has no variation");
            }
        }
        for (Class<?> record : List.of(ContraptionExpectation.class, SubLevelExpectation.class)) {
            for (RecordComponent c : record.getRecordComponents()) {
                String field = c.getName();
                assertTrue(named.contains("AssemblySpec.expect." + field), "AssemblySpec.expect." + field + " has no variation");
            }
        }
    }
}
