package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchemaGeneratorTest {
    private static final PartTypeRegistry REGISTRY = BuildingParts.registry();
    /** The hand-written golden plan patch, kept in sync with the compiler by GoldenHutTest. */
    private static final String GOLDEN_HUT = "/build/golden/hut.patch.json";

    private static final String PATCH = "{\"ops\":["
            + "{\"op\":\"set_site\",\"site\":{\"dimension\":\"minecraft:overworld\",\"origin\":[0,64,0],\"facing\":\"north\",\"bounds\":[0,0,0,9,9,9]}},"
            + "{\"op\":\"add_node\",\"node\":{\"id\":\"hut\",\"type\":\"micra:structure\",\"anchor\":{\"kind\":\"absolute\",\"pos\":[0,0,0]},\"params\":{\"width\":7,\"depth\":7}}},"
            + "{\"op\":\"add_node\",\"node\":{\"id\":\"wall-n\",\"type\":\"micra:wall\",\"parent\":\"hut\",\"anchor\":{\"kind\":\"absolute\",\"pos\":[0,0,0],\"rot\":{\"turns\":0,\"mirror\":false}},\"params\":{\"side\":\"north\",\"material\":\"wall\"}}},"
            + "{\"op\":\"add_node\",\"node\":{\"id\":\"door-1\",\"type\":\"micra:door\",\"parent\":\"hut\",\"anchor\":{\"kind\":\"surface\",\"node\":\"wall-n\",\"side\":\"outer\",\"u\":3,\"v\":0},\"params\":{}}},"
            + "{\"op\":\"remove_node\",\"id\":\"door-1\"}]}";

    private static MiniSchemaValidator validator(Map<String, Object> schema) {
        return new MiniSchemaValidator(schema);
    }

    @Test
    void theRootIsAnObjectWithClosedProperties() {
        Map<String, Object> s = SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null);
        assertEquals("object", s.get("type"));
        assertEquals(false, s.get("additionalProperties"));
        assertEquals(List.of("ops"), s.get("required"));
        assertTrue(s.containsKey("$defs"));
    }

    @Test
    void aValidPatchPassesTheTypedForm() {
        Object patch = MiniJson.parse(PATCH);
        assertEquals(List.of(), validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null)).validate(patch));
    }

    @Test
    void typedModeRejectsWhatTheSpecForbids() {
        MiniSchemaValidator v = validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null));
        assertFalse(v.validate(MiniJson.parse(PATCH.replace("\"width\":7", "\"width\":99"))).isEmpty(), "out of range");
        assertFalse(v.validate(MiniJson.parse(PATCH.replace("micra:structure", "micra:castle"))).isEmpty(), "unknown part");
        assertFalse(v.validate(MiniJson.parse(PATCH.replace("\"side\":\"north\"", "\"side\":\"up\""))).isEmpty(), "bad enum");
        assertFalse(v.validate(MiniJson.parse(PATCH.replace("\"depth\":7", "\"depth\":7,\"bogus\":1"))).isEmpty(), "extra parameter");
        assertFalse(v.validate(MiniJson.parse(PATCH.replace("\"side\":\"north\",", ""))).isEmpty(), "missing required side");
        assertFalse(v.validate(MiniJson.parse(PATCH.replace("\"op\":\"remove_node\"", "\"op\":\"explode\""))).isEmpty(), "unknown op");
    }

    @Test
    void onlyUserPartsAreOffered() {
        PartTypeRegistry withImplicit = PartTypeRegistry.builder()
                .register(PartType.builder("test:shown", PartCategory.STRUCTURE).displayNameKey("k").build())
                .register(PartType.builder("test:hidden", PartCategory.STRUCTURE).visibility(Visibility.IMPLICIT).build())
                .build();
        String json = SchemaGenerator.forLimit(withImplicit, null, SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS).json();
        assertTrue(json.contains("test:shown"));
        assertFalse(json.contains("test:hidden"));
    }

    @Test
    void aSubsetOfPartsGivesASmallerSchema() {
        SchemaGenerator.Generated all = SchemaGenerator.forLimit(REGISTRY, null, SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS);
        Set<String> subset = Set.of("micra:wall", "micra:roof");
        SchemaGenerator.Generated some = SchemaGenerator.forLimit(REGISTRY, subset, SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS);
        int subsetFlat = CanonicalJson.write(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.FLAT, subset)).length();
        System.out.println("SCHEMA-SIZE subset2 typed=" + some.chars() + " flat=" + subsetFlat);
        assertTrue(some.chars() < all.chars());
        assertFalse(some.json().contains("micra:pillar"));
        assertTrue(some.json().contains("micra:wall"));
    }

    @Test
    void theFullBuildingRegistryFitsTheDirectLaunchLimitAndTheFlatFormFitsTheCmdLimit() {
        Map<String, Object> typedSchema = SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null);
        Map<String, Object> flatSchema = SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.FLAT, null);
        int typedChars = CanonicalJson.write(typedSchema).length();
        int flatChars = CanonicalJson.write(flatSchema).length();
        Map<String, Object> flatDefs = defs(flatSchema);
        StringBuilder defSizes = new StringBuilder();
        for (Map.Entry<String, Object> e : flatDefs.entrySet()) {
            defSizes.append(e.getKey()).append('=').append(CanonicalJson.write(e.getValue()).length()).append(' ');
        }
        System.out.println("SCHEMA-SIZE full typed=" + typedChars + " flat=" + flatChars);
        System.out.println("SCHEMA-FLAT-DEFS " + defSizes);

        SchemaGenerator.Generated direct = SchemaGenerator.forLimit(REGISTRY, null, SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS);
        assertTrue(direct.chars() <= SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS, "typed size " + direct.chars());
        SchemaGenerator.Generated cmd = SchemaGenerator.forLimit(REGISTRY, null, SchemaLimits.CMD_EXE_MAX_SCHEMA_CHARS);
        assertTrue(cmd.chars() <= SchemaLimits.CMD_EXE_MAX_SCHEMA_CHARS, "flat size " + cmd.chars());
        assertEquals(SchemaGenerator.Mode.FLAT, cmd.mode(), "typed is too big for the cmd.exe limit, so it falls back");
        assertThrows(SchemaTooLargeException.class, () -> SchemaGenerator.forLimit(REGISTRY, null, 200));
    }

    @Test
    void theFlatFormTakesParametersAsKeyValuePairs() {
        Map<String, Object> flat = SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.FLAT, null);
        MiniSchemaValidator v = validator(flat);
        String flatPatch = PATCH.replace("\"params\":{\"width\":7,\"depth\":7}", "\"params\":[{\"key\":\"width\",\"value\":7},{\"key\":\"depth\",\"value\":7}]")
                .replace("\"params\":{\"side\":\"north\",\"material\":\"wall\"}", "\"params\":[{\"key\":\"side\",\"value\":\"north\"},{\"key\":\"material\",\"value\":\"wall\"}]")
                .replace("\"params\":{}", "\"params\":[]");
        assertEquals(List.of(), v.validate(MiniJson.parse(flatPatch)));
        // the name is a free string in the compact form; the checker is what rejects an unknown name
        String unknown = flatPatch.replace("\"key\":\"width\"", "\"key\":\"unknown_param\"");
        assertEquals(List.of(), v.validate(MiniJson.parse(unknown)));
        PatchResult rejected = new PlanPatcher(TestParts.registry(), TemplateBundle.EMPTY).apply(SemanticPlan.empty("p"), withMeta(unknown));
        assertFalse(rejected.ok());
        assertEquals("E-PARAM-RANGE", rejected.issues().get(0).code().label());
    }

    @Test
    void bothFormsReadBackToTheSamePlan() {
        String flatPatch = PATCH.replace("\"params\":{\"width\":7,\"depth\":7}", "\"params\":[{\"key\":\"width\",\"value\":7},{\"key\":\"depth\",\"value\":7}]")
                .replace("\"params\":{\"side\":\"north\",\"material\":\"wall\"}", "\"params\":[{\"key\":\"side\",\"value\":\"north\"},{\"key\":\"material\",\"value\":\"wall\"}]")
                .replace("\"params\":{}", "\"params\":[]");
        PlanPatcher patcher = new PlanPatcher(TestParts.registry(), TemplateBundle.EMPTY);
        PatchResult typed = patcher.apply(SemanticPlan.empty("p"), withMeta(PATCH));
        PatchResult flat = patcher.apply(SemanticPlan.empty("p"), withMeta(flatPatch));
        assertTrue(typed.ok(), typed.issues().toString());
        assertTrue(flat.ok(), flat.issues().toString());
        assertEquals(typed.plan().contentHash(), flat.plan().contentHash());
    }

    @Test
    void theGoldenHutPatchPassesTheTypedSchema() throws IOException {
        Object full = MiniJson.parse(resource(GOLDEN_HUT));
        Object opsOnly = Map.of("ops", obj(full).get("ops"));
        List<String> errors = validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null))
                .validate(opsOnly);
        assertEquals(List.of(), errors);
    }

    @Test
    void theSchemaDoesNotDependOnRegistrationOrder() {
        List<PartType> forward = new ArrayList<>(BuildingParts.registry().all());
        List<PartType> backward = new ArrayList<>(forward);
        Collections.reverse(backward);
        PartTypeRegistry a = registryOf(forward);
        PartTypeRegistry b = registryOf(backward);
        for (SchemaGenerator.Mode mode : SchemaGenerator.Mode.values()) {
            assertEquals(SchemaGenerator.patchSchema(a, mode, null), SchemaGenerator.patchSchema(b, mode, null));
            assertEquals(CanonicalJson.write(SchemaGenerator.patchSchema(a, mode, null)),
                    CanonicalJson.write(SchemaGenerator.patchSchema(b, mode, null)));
        }
        // and the same registry twice gives byte-identical text
        assertEquals(CanonicalJson.write(SchemaGenerator.patchSchema(a, SchemaGenerator.Mode.TYPED, null)),
                CanonicalJson.write(SchemaGenerator.patchSchema(a, SchemaGenerator.Mode.TYPED, null)));
    }

    @Test
    void enumBudgetForTheCmdExeRoute() {
        assertTrue(SchemaLimits.enumFitsCmdExe(280, 9), "the measured comfortable size: 9-char ids, about 280 of them");
        assertFalse(SchemaLimits.enumFitsCmdExe(300, 9));
        assertTrue(SchemaLimits.enumFitsCmdExe(140, 23));
        assertEquals(20_000, SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS);
        assertEquals(5_000, SchemaLimits.CMD_EXE_MAX_SCHEMA_CHARS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object tree) {
        return (Map<String, Object>) tree;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> defs(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("$defs");
    }

    private static PartTypeRegistry registryOf(List<PartType> parts) {
        PartTypeRegistry.Builder b = PartTypeRegistry.builder();
        for (PartType t : parts) {
            b.register(t);
        }
        return b.build();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = SchemaGeneratorTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "missing golden resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static PlanPatch withMeta(String opsOnly) {
        String json = "{\"patchId\":\"p\",\"baseRevision\":0,\"stageId\":\"ai\"," + opsOnly.substring(1);
        return PlanJson.patchFromTree(MiniJson.parse(json));
    }
}
