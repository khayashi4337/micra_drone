package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.model.PlanJsonException;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.model.PlanIds;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SchemaGeneratorTest {
    private static final PartTypeRegistry REGISTRY = BuildingParts.registry();
    /** The hand-written golden plan patch, kept in sync with the compiler by GoldenHutTest. */
    private static final String GOLDEN_HUT = "/build/golden/hut.patch.json";

    /** BuildingParts' sign text limit: full lines of SIGN_MAX_LINE_CHARS plus the separators between them. */
    private static final int SIGN_TEXT_LIMIT =
            BuildingParts.SIGN_MAX_LINES * BuildingParts.SIGN_MAX_LINE_CHARS + (BuildingParts.SIGN_MAX_LINES - 1);
    /** BuildingParts' hole limit, read back from the floor's "holes" parameter (never a re-typed literal). */
    private static final int HOLE_VALUE_LIMIT = listLimit("micra:floor", "holes");
    /** Characters cmd.exe would read as control codes inside a command line. */
    private static final String CMD_META_CHARS = "&|<>^%!";

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
    void typedModeRejectsRegistryDerivedBounds() {
        MiniSchemaValidator v = validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null));
        // positive controls at the exact limits: a rejection below then means the limit, not something else
        assertEquals(List.of(), v.validate(addNode("sign-ok", "micra:sign",
                "\"text\":\"" + "x".repeat(SIGN_TEXT_LIMIT) + "\"")), "longest allowed sign text");
        StringBuilder maxHoles = new StringBuilder();
        for (int i = 0; i < HOLE_VALUE_LIMIT; i++) {
            maxHoles.append(i == 0 ? "" : ",").append(0);
        }
        assertEquals(List.of(), v.validate(addNode("floor-ok", "micra:floor",
                "\"holes\":[" + maxHoles + "]")), "largest allowed holes array");

        assertFalse(v.validate(addNode("sign-1", "micra:sign",
                "\"text\":\"" + "x".repeat(SIGN_TEXT_LIMIT + 1) + "\"")).isEmpty(), "sign text over the limit");
        StringBuilder holes = new StringBuilder();
        for (int i = 0; i <= HOLE_VALUE_LIMIT; i++) {
            holes.append(i == 0 ? "" : ",").append(0);
        }
        assertFalse(v.validate(addNode("floor-1", "micra:floor", "\"holes\":[" + holes + "]")).isEmpty(),
                "one hole value too many");
        assertFalse(v.validate(addNode("floor-2", "micra:floor", "\"material\":\"Not A Block\"")).isEmpty(),
                "a material is a role or a block id");
        assertFalse(v.validate(addNode("roof-1", "micra:roof", "\"kind\":\"pyramid\"")).isEmpty(),
                "the roof kind is a fixed enum");
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
        assertTrue(some.chars() < all.chars());
        assertFalse(some.json().contains("micra:pillar"));
        assertTrue(some.json().contains("micra:wall"));
    }

    @Test
    void anEmptyOfferedSelectionIsRejected() {
        // a oneOf over the offered parts needs at least one branch to be a valid schema
        assertThrows(IllegalArgumentException.class,
                () -> SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.FLAT, Set.of("no:such")));
        assertThrows(IllegalArgumentException.class,
                () -> SchemaGenerator.forLimit(REGISTRY, Set.of(), SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS));
    }

    @Test
    void theFullBuildingRegistryFitsTheDirectLaunchLimitAndTheFlatFormFitsTheCmdLimit() {
        int typedChars = CanonicalJson.write(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null)).length();
        int flatChars = CanonicalJson.write(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.FLAT, null)).length();

        assertEquals(SchemaGenerator.Mode.TYPED,
                SchemaGenerator.forLimit(REGISTRY, null, SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS).mode(),
                "typed size " + typedChars);
        assertEquals(SchemaGenerator.Mode.FLAT,
                SchemaGenerator.forLimit(REGISTRY, null, SchemaLimits.CMD_EXE_MAX_SCHEMA_CHARS).mode(),
                "flat size " + flatChars);
        assertEquals(SchemaGenerator.Mode.TYPED, SchemaGenerator.forLimit(REGISTRY, null, typedChars).mode());
        assertEquals(SchemaGenerator.Mode.FLAT, SchemaGenerator.forLimit(REGISTRY, null, typedChars - 1).mode());
        assertEquals(SchemaGenerator.Mode.FLAT, SchemaGenerator.forLimit(REGISTRY, null, flatChars).mode());
        SchemaTooLargeException tooBig = assertThrows(SchemaTooLargeException.class,
                () -> SchemaGenerator.forLimit(REGISTRY, null, flatChars - 1));
        assertTrue(tooBig.getMessage().contains(String.valueOf(typedChars)), tooBig.getMessage());
        assertTrue(tooBig.getMessage().contains(String.valueOf(flatChars)), tooBig.getMessage());
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
    void theFlatFormAcceptsMixedPairValues() {
        MiniSchemaValidator v = validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.FLAT, null));
        String patch = "{\"ops\":[{\"op\":\"update_params\",\"id\":\"hut\",\"params\":["
                + "{\"key\":\"an_int\",\"value\":7},{\"key\":\"a_string\",\"value\":\"x\"},"
                + "{\"key\":\"a_bool\",\"value\":true},{\"key\":\"a_list\",\"value\":[1,2,\"s\"]}]}]}";
        assertEquals(List.of(), v.validate(MiniJson.parse(patch)));
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
    void theGoldenHutPatchInPairFormPassesTheFlatSchema() throws IOException {
        Map<String, Object> full = obj(MiniJson.parse(resource(GOLDEN_HUT)));
        List<Object> flatOps = new ArrayList<>();
        for (Object opObj : list(full.get("ops"))) {
            Map<String, Object> op = new LinkedHashMap<>(obj(opObj));
            if (op.get("node") instanceof Map) {
                Map<String, Object> node = new LinkedHashMap<>(obj(op.get("node")));
                if (node.get("params") instanceof Map) {
                    node.put("params", toPairs(obj(node.get("params"))));
                }
                op.put("node", node);
            }
            if (op.get("params") instanceof Map) {
                op.put("params", toPairs(obj(op.get("params"))));
            }
            flatOps.add(op);
        }
        MiniSchemaValidator v = validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.FLAT, null));
        assertEquals(List.of(), v.validate(Map.of("ops", flatOps)));
    }

    @Test
    void allNineOperationsValidateAgainstTheTypedSchema() {
        PlanNode node = new PlanNode("hut", "micra:structure", null,
                new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE),
                Map.of("width", new ParamValue.IntV(7)), Set.of(), "");
        Connection connection = new Connection("link-1", new PortRef("a", "out"), new PortRef("b", "in"),
                ConnKind.ITEM, new Routing.Explicit(List.of("a")),
                new Constraints(8, Set.of("c"), 2, Set.of(Dir6.NORTH)));
        Site site = new Site("minecraft:overworld", new BuildFrame(new IntPos(0, 64, 0), Facing.NORTH),
                new Box(-5, -5, -5, 15, 12, 15), "", "");
        LogisticsPlan logistics = new LogisticsPlan(
                List.of(new LogisticsPlan.Dock("dock-1", new Box(0, 0, 0, 3, 0, 3), new Box(0, 0, 0, 5, 4, 5),
                        Facing.NORTH, List.of(new PortRef("a", "in")), List.of("b"))),
                List.of(new LogisticsPlan.Route("route-1", "dock-1", "dock-2",
                        List.of(new LocalPos(1, 2, 3)), "ship-1")),
                List.of(new LogisticsPlan.CargoFlow("minecraft:iron_ingot", 1.5, "dock-1", "dock-2")));
        PlanPatch patch = new PlanPatch("p", 0, "ai", List.of(
                new PlanOp.AddNode(node),
                new PlanOp.UpdateParams("hut", Map.of("width", new ParamValue.IntV(9))),
                new PlanOp.MoveNode("hut", new Anchor.OnSurface("w", Side.OUTER, 1, 0)),
                new PlanOp.RemoveNode("hut"),
                new PlanOp.AddConnection(connection),
                new PlanOp.RemoveConnection("link-1"),
                new PlanOp.SetStyle(new StyleSpec(Map.of("roof", "minecraft:oak_planks"), Set.of("hut"))),
                new PlanOp.SetSite(site),
                new PlanOp.SetLogistics(logistics)));

        Map<String, Object> typed = SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null);
        Object doc = Map.of("ops", PlanJson.toTree(patch).get("ops"));
        assertEquals(List.of(), validator(typed).validate(doc));

        // and the schema offers exactly these nine operations
        List<String> offered = new ArrayList<>();
        for (Object branch : list(obj(obj(obj(typed.get("properties")).get("ops")).get("items")).get("oneOf"))) {
            offered.add((String) obj(obj(obj(branch).get("properties")).get("op")).get("const"));
        }
        assertEquals(List.of("add_node", "update_params", "move_node", "remove_node", "add_connection",
                "remove_connection", "set_style", "set_site", "set_logistics"), offered);

        // and the serialized form reads back to the very same patch
        assertEquals(patch, PlanJson.patchFromTree(PlanJson.toTree(patch)));
    }

    @Test
    void theSchemaAndTheParserAgreeOnRequiredMembers() {
        // A member both sides require must not survive removal from only the schema's "required" list;
        // PlanJson.patchFromTree is the second guard, and its message must name the missing member.
        MiniSchemaValidator typed = validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null));
        MiniSchemaValidator flat = validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.FLAT, null));
        String dock = "{\"id\":\"d1\",\"pad\":[0,0,0,3,0,3],\"clearance\":[0,0,0,5,4,5],"
                + "\"approach\":\"north\",\"ports\":[{\"node\":\"a\",\"port\":\"in\"}],\"connectors\":[\"b\"]}";
        String logisticsWithDock = "{\"docks\":[" + dock + "],\"routes\":[],\"flows\":[]}";
        String logisticsWithRoute = "{\"docks\":[],\"routes\":[{\"id\":\"r1\",\"from\":\"d1\",\"to\":\"d2\","
                + "\"waypoints\":[[1,2,3]],\"airship\":\"ship-1\"}],\"flows\":[]}";
        String connection = "{\"id\":\"c1\",\"from\":{\"node\":\"a\",\"port\":\"out\"},"
                + "\"to\":{\"node\":\"b\",\"port\":\"in\"},\"kind\":\"item\","
                + "\"routing\":{\"mode\":\"explicit\",\"via\":[\"a\"]}}";
        // flatRejects says whether the FLAT schema models the member at all: the routing def is the same in
        // both forms, but the FLAT logistics def is a loose {"type":"object"} and leaves members to the parser.
        List<RequiredMemberCase> cases = List.of(
                new RequiredMemberCase("ports",
                        "{\"op\":\"set_logistics\",\"logistics\":" + logisticsWithDock + "}",
                        List.of("logistics", "docks", 0, "ports"), false),
                new RequiredMemberCase("connectors",
                        "{\"op\":\"set_logistics\",\"logistics\":" + logisticsWithDock + "}",
                        List.of("logistics", "docks", 0, "connectors"), false),
                new RequiredMemberCase("waypoints",
                        "{\"op\":\"set_logistics\",\"logistics\":" + logisticsWithRoute + "}",
                        List.of("logistics", "routes", 0, "waypoints"), false),
                new RequiredMemberCase("via",
                        "{\"op\":\"add_connection\",\"connection\":" + connection + "}",
                        List.of("connection", "routing", "via"), true));
        for (RequiredMemberCase c : cases) {
            Map<String, Object> op = obj(MiniJson.parse(c.opJson()));
            // positive control: the complete document passes the schema and the parser
            assertEquals(List.of(), typed.validate(Map.of("ops", List.of(op))),
                    c.missing() + " complete document, typed schema");
            assertEquals(List.of(), flat.validate(Map.of("ops", List.of(op))),
                    c.missing() + " complete document, flat schema");
            assertDoesNotThrow(() -> PlanJson.patchFromTree(envelope(op)),
                    c.missing() + " complete document, parser");

            removeMember(op, c.memberPath());
            Map<String, Object> doc = Map.of("ops", List.of(op));
            assertFalse(typed.validate(doc).isEmpty(), "typed schema still requires " + c.missing());
            assertEquals(c.flatRejects(), !flat.validate(doc).isEmpty(),
                    "flat schema on missing " + c.missing());
            PlanJsonException e = assertThrows(PlanJsonException.class,
                    () -> PlanJson.patchFromTree(envelope(op)), "parser on missing " + c.missing());
            assertTrue(e.getMessage().contains("\"" + c.missing() + "\""), e.getMessage());
        }
    }

    @Test
    void theSerializersOwnDefaultOutputValidates() {
        // Constraints.NONE serializes with explicit JSON nulls (maxLength, maxTurns); the schema must take
        // what PlanJson.toTree itself emits, or every default connection would be rejected.
        PlanPatch patch = new PlanPatch("p", 0, "ai", List.of(new PlanOp.AddConnection(
                new Connection("c1", new PortRef("a", "out"), new PortRef("b", "in"),
                        ConnKind.ITEM, Routing.AUTO, Constraints.NONE))));
        Object doc = Map.of("ops", PlanJson.toTree(patch).get("ops"));
        assertEquals(List.of(),
                validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null)).validate(doc),
                "typed schema on serializer defaults");
        assertEquals(List.of(),
                validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.FLAT, null)).validate(doc),
                "flat schema on serializer defaults");
    }

    @Test
    void theWholeSchemaTreeIsCheckedUpFront() {
        // the validator's constructor walks every sub-schema, not only the ones a document reaches
        assertDoesNotThrow(() -> validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null)));
        assertDoesNotThrow(() -> validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.FLAT, null)));
    }

    @Test
    void everyDefAcceptsAMinimalDocument() {
        for (SchemaGenerator.Mode mode : SchemaGenerator.Mode.values()) {
            Map<String, Object> schema = SchemaGenerator.patchSchema(REGISTRY, mode, null);
            Map<String, Object> defs = defs(schema);
            Map<String, String> docs = minimalDocs(mode == SchemaGenerator.Mode.FLAT);
            Map<String, String> wrong = wrongDocs(mode == SchemaGenerator.Mode.FLAT);
            assertEquals(docs.keySet(), defs.keySet(), mode + " $defs");
            assertEquals(docs.keySet(), wrong.keySet(), mode + " rejecting docs");
            for (Map.Entry<String, String> e : docs.entrySet()) {
                Map<String, Object> against = Map.of("$defs", defs, "$ref", "#/$defs/" + e.getKey());
                MiniSchemaValidator v = validator(against);
                assertEquals(List.of(), v.validate(MiniJson.parse(e.getValue())),
                        mode + " $defs." + e.getKey());
                assertFalse(v.validate(MiniJson.parse(wrong.get(e.getKey()))).isEmpty(),
                        mode + " $defs." + e.getKey() + " takes a clearly wrong document");
            }
        }
    }

    @Test
    void theAnchorDefHasAbsoluteSurfaceAndSlotBranches() {
        Map<String, Object> defs = defs(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null));
        MiniSchemaValidator v = validator(Map.of("$defs", defs, "$ref", "#/$defs/anchor"));
        assertEquals(List.of(), v.validate(MiniJson.parse("{\"kind\":\"absolute\",\"pos\":[0,0,0]}")));
        assertEquals(List.of(), v.validate(MiniJson.parse(
                "{\"kind\":\"surface\",\"node\":\"w\",\"side\":\"outer\",\"u\":0,\"v\":0}")));
        assertEquals(List.of(), v.validate(MiniJson.parse("{\"kind\":\"slot\",\"slot\":\"s\"}")));
        assertFalse(v.validate(MiniJson.parse("{\"kind\":\"up\"}")).isEmpty());
    }

    @Test
    void theRoutingDefIsDiscriminatedByMode() {
        for (SchemaGenerator.Mode mode : SchemaGenerator.Mode.values()) {
            Map<String, Object> defs = defs(SchemaGenerator.patchSchema(REGISTRY, mode, null));
            MiniSchemaValidator v = validator(Map.of("$defs", defs, "$ref", "#/$defs/routing"));
            assertEquals(List.of(), v.validate(MiniJson.parse("{\"mode\":\"auto\"}")), mode + " auto");
            assertEquals(List.of(), v.validate(MiniJson.parse("{\"mode\":\"explicit\",\"via\":[\"a\"]}")),
                    mode + " explicit");
            assertFalse(v.validate(MiniJson.parse("{\"mode\":\"auto\",\"via\":[\"a\"]}")).isEmpty(),
                    mode + " auto takes no other member");
            assertFalse(v.validate(MiniJson.parse("{\"mode\":\"explicit\"}")).isEmpty(),
                    mode + " explicit needs via");
            assertFalse(v.validate(MiniJson.parse("{\"mode\":\"fast\"}")).isEmpty(), mode + " unknown mode");
        }
    }

    @Test
    void noSchemaUsesAUnionOfSeveralConcreteTypes() {
        // ajv strictTypes warns on a "type" array of several non-null members (checked 2026-09-26 with the
        // real claude CLI); a nullable ["x","null"] pair measured fine and stays.
        for (SchemaGenerator.Mode mode : SchemaGenerator.Mode.values()) {
            assertNoUnionTypes(SchemaGenerator.patchSchema(REGISTRY, mode, null), "$", mode);
        }
    }

    @Test
    void theSchemaIsByteIdenticalAcrossRuns() {
        for (SchemaGenerator.Mode mode : SchemaGenerator.Mode.values()) {
            assertEquals(SchemaGenerator.patchSchema(REGISTRY, mode, null),
                    SchemaGenerator.patchSchema(REGISTRY, mode, null));
            assertEquals(CanonicalJson.write(SchemaGenerator.patchSchema(REGISTRY, mode, null)),
                    CanonicalJson.write(SchemaGenerator.patchSchema(REGISTRY, mode, null)));
        }
    }

    @Test
    void theOfferedPartsComeInRegistryIdOrder() {
        // the registry is id-ordered, so the generator needs no sort of its own
        Map<String, Object> flat = SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.FLAT, null);
        Map<String, Object> addNode = obj(list(
                obj(obj(obj(flat.get("properties")).get("ops")).get("items")).get("oneOf")).get(0));
        Map<String, Object> nodeProps = obj(obj(obj(addNode.get("properties")).get("node")).get("properties"));
        List<String> offered = strings(obj(nodeProps.get("type")).get("enum"));
        List<String> expected = new ArrayList<>();
        for (PartType t : REGISTRY.userParts()) {
            expected.add(t.id());
        }
        assertEquals(expected, offered);
        assertEquals(expected.stream().sorted().toList(), offered, "registry id order is sorted");
    }

    @Test
    void theFlatJsonHasNoWhitespaceOrCmdMetaCharacters() {
        String json = SchemaGenerator.forLimit(REGISTRY, null, SchemaLimits.CMD_EXE_MAX_SCHEMA_CHARS).json();
        for (int i = 0; i < CMD_META_CHARS.length(); i++) {
            assertEquals(-1, json.indexOf(CMD_META_CHARS.charAt(i)), "cmd.exe metacharacter at " + i);
        }
        for (int i = 0; i < json.length(); i++) {
            assertFalse(Character.isWhitespace(json.charAt(i)), "whitespace at " + i);
        }
    }

    @Test
    void theReturnedSchemaIsDeeplyUnmodifiable() {
        SchemaGenerator.Generated g = SchemaGenerator.forLimit(REGISTRY, null, SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS);
        assertThrows(UnsupportedOperationException.class, () -> g.schema().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> obj(g.schema().get("properties")).put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> defs(g.schema()).put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> obj(defs(g.schema()).get("pos")).put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> list(g.schema().get("required")).add("x"));
    }

    @Test
    void theSchemaPatternsAgreeWithTheCheckers() {
        String typedJson = CanonicalJson.write(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null));
        assertTrue(typedJson.contains(SchemaGenerator.ID_PATTERN), "the id pattern is emitted");
        assertTrue(typedJson.contains(SchemaGenerator.MATERIAL_PATTERN), "the material pattern is emitted");

        assertTrue(PlanIds.isValid("hut-1") && !PlanIds.isValid("Hut"));
        assertTrue(ParamValidator.isRoleName("wall") && !ParamValidator.isRoleName("minecraft:stone"));
        assertTrue(ParamValidator.isBlockId("minecraft:stone") && !ParamValidator.isBlockId("wall"));

        String[] samples = {"hut", "a-1", "Hut", "a_b", "x".repeat(PlanIds.MAX_LENGTH),
                "x".repeat(PlanIds.MAX_LENGTH + 1), "a:b", "wall", "roof", "a1", "x_y", "Wall", "1x", "a-b",
                "minecraft:stone", "minecraft:iron/gold", "a.b:c_d", "minecraft:", ":x", "UPPER:x", "", "hut!",
                "a:b:c", "minecraft:overworld"};
        for (String s : samples) {
            assertEquals(PlanIds.isValid(s), matches(SchemaGenerator.ID_PATTERN, s), "id pattern on " + s);
            assertEquals(ParamValidator.isRoleName(s), matches(SchemaGenerator.ROLE_PATTERN, s),
                    "role pattern on " + s);
            assertEquals(ParamValidator.isRoleName(s) || ParamValidator.isBlockId(s),
                    matches(SchemaGenerator.MATERIAL_PATTERN, s), "material pattern on " + s);
        }
    }

    @Test
    void enumBudgetForTheCmdExeRoute() {
        assertTrue(SchemaLimits.enumFitsCmdExe(280, 9), "the measured comfortable size: 9-char ids, about 280 of them");
        assertFalse(SchemaLimits.enumFitsCmdExe(300, 9));
        assertTrue(SchemaLimits.enumFitsCmdExe(140, 23));
        assertEquals(20_000, SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS);
        assertEquals(5_000, SchemaLimits.CMD_EXE_MAX_SCHEMA_CHARS);
    }

    /** Minimal documents per $defs name; the loose FLAT defs take the loosest value of the same shape. */
    private static Map<String, String> minimalDocs(boolean flat) {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("pos", "[0,0,0]");
        docs.put("rot", "{}");
        docs.put("anchor", "{\"kind\":\"absolute\",\"pos\":[0,0,0]}");
        docs.put("connection", "{\"id\":\"c\",\"from\":{\"node\":\"a\",\"port\":\"o\"},"
                + "\"to\":{\"node\":\"b\",\"port\":\"i\"},\"kind\":\"item\"}");
        docs.put("site", "{\"dimension\":\"d\",\"origin\":[0,0,0],\"facing\":\"north\",\"bounds\":[0,0,0,1,1,1]}");
        docs.put("style", "{\"palette\":{}}");
        docs.put("logistics", flat ? "{}" : "{\"docks\":[],\"routes\":[],\"flows\":[]}");
        docs.put("anyParams", flat ? "[]" : "{}");
        docs.put("routing", "{\"mode\":\"auto\"}");
        docs.put("constraints", "{}");
        if (!flat) {
            docs.put("material", "\"wall\"");
            docs.put("dir4", "\"north\"");
        }
        return docs;
    }

    /**
     * A clearly wrong document per $defs name: drops a required member or breaks the value's form, so a def
     * that stopped checking anything could not pass silently.
     */
    private static Map<String, String> wrongDocs(boolean flat) {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("pos", "[0,0]"); // a position is three integers
        docs.put("rot", "{\"turns\":9}"); // quarter turns run 0..3
        docs.put("anchor", "{\"kind\":\"absolute\"}"); // pos is required
        docs.put("connection", "{\"id\":\"c\",\"from\":{\"node\":\"a\",\"port\":\"o\"},"
                + "\"to\":{\"node\":\"b\",\"port\":\"i\"}}"); // kind is required
        docs.put("site", "{\"dimension\":\"d\",\"origin\":[0,0,0],\"facing\":\"north\"}"); // bounds is required
        docs.put("style", "{}"); // palette is required
        // the dock drops "ports" and the route drops "waypoints"; the loose FLAT def only requires an object
        docs.put("logistics", flat ? "[]"
                : "{\"docks\":[{\"id\":\"d\",\"pad\":[0,0,0,1,0,1],\"clearance\":[0,0,0,1,0,1],"
                + "\"approach\":\"north\",\"connectors\":[]}],"
                + "\"routes\":[{\"id\":\"r\",\"from\":\"d\",\"to\":\"e\"}],\"flows\":[]}");
        // TYPED takes a params object; FLAT takes {key, value} pairs, so a value-less pair must fail
        docs.put("anyParams", flat ? "[{\"key\":\"k\"}]" : "[]");
        docs.put("routing", "{\"mode\":\"explicit\"}"); // via is required
        docs.put("constraints", flat ? "[]" : "{\"entryDirs\":[\"sideways\"]}"); // a dir6 enum value
        if (!flat) {
            docs.put("material", "\"Not A Role\"");
            docs.put("dir4", "\"up\"");
        }
        return docs;
    }

    private static void assertNoUnionTypes(Object tree, String path, SchemaGenerator.Mode mode) {
        if (tree instanceof Map<?, ?> m) {
            if (m.get("type") instanceof List<?> types) {
                long concrete = types.stream().filter(t -> !"null".equals(t)).count();
                assertTrue(concrete <= 1, mode + " " + path + " has a type union " + types);
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                assertNoUnionTypes(e.getValue(), path + "." + e.getKey(), mode);
            }
        } else if (tree instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                assertNoUnionTypes(l.get(i), path + "[" + i + "]", mode);
            }
        }
    }

    /** A member both the schema's {@code required} list and the parser must refuse to lose. */
    private record RequiredMemberCase(String missing, String opJson, List<Object> memberPath,
                                      boolean flatRejects) {
    }

    /** The {@code maxItems} bound of {@code partId}'s {@code paramName}, read from the registry itself. */
    private static int listLimit(String partId, String paramName) {
        return REGISTRY.get(partId).param(paramName)
                .orElseThrow(() -> new IllegalArgumentException(partId + " has no parameter " + paramName))
                .maxItems();
    }

    /** Removes the member at {@code path} (map keys as Strings, list indices as Integers) from a parsed op. */
    private static void removeMember(Map<String, Object> op, List<Object> path) {
        Object node = op;
        for (int i = 0; i + 1 < path.size(); i++) {
            node = path.get(i) instanceof Integer index ? list(node).get(index) : obj(node).get(path.get(i));
        }
        obj(node).remove(path.get(path.size() - 1));
    }

    /** A full patch envelope (patchId, baseRevision, stageId) around a single op, as patchFromTree reads it. */
    private static Map<String, Object> envelope(Map<String, Object> op) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("patchId", "p");
        doc.put("baseRevision", 0);
        doc.put("stageId", "ai");
        doc.put("ops", List.of(op));
        return doc;
    }

    private static Object addNode(String id, String type, String paramsJson) {
        return MiniJson.parse("{\"ops\":[{\"op\":\"add_node\",\"node\":{\"id\":\"" + id + "\",\"type\":\"" + type
                + "\",\"anchor\":{\"kind\":\"absolute\",\"pos\":[0,0,0]},\"params\":{" + paramsJson + "}}}]}");
    }

    private static List<Object> toPairs(Map<String, Object> params) {
        List<Object> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            pairs.add(Map.of("key", e.getKey(), "value", e.getValue()));
        }
        return pairs;
    }

    private static boolean matches(String pattern, String value) {
        return Pattern.compile(pattern).matcher(value).matches();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object tree) {
        return (Map<String, Object>) tree;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object tree) {
        return (List<Object>) tree;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object tree) {
        return (List<String>) tree;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> defs(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("$defs");
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
