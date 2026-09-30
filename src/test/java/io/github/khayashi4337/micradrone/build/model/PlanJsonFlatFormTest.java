package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.chat.MiniJson;
import org.junit.jupiter.api.Test;

/**
 * The compact patch form the FLAT schema emits: {@code params} as an array of {@code {key, value}} pairs and a
 * {@code rot} whose {@code turns} and {@code mirror} are optional. Both read back to the same records as the
 * object form.
 */
class PlanJsonFlatFormTest {
    private static final String PATCH_PREFIX = "{\"patchId\":\"p\",\"baseRevision\":0,\"stageId\":\"ai\",\"ops\":[";
    private static final String PATCH_SUFFIX = "]}";

    private static PlanPatch patch(String opsJson) {
        return PlanJson.patchFromTree(MiniJson.parse(PATCH_PREFIX + opsJson + PATCH_SUFFIX));
    }

    private static String addNode(String anchorJson, String paramsJson) {
        return "{\"op\":\"add_node\",\"node\":{\"id\":\"hut\",\"type\":\"micra:structure\",\"anchor\":"
                + anchorJson + ",\"params\":" + paramsJson + "}}";
    }

    private static String absoluteAnchor(String rotJson) {
        return "{\"kind\":\"absolute\",\"pos\":[0,0,0]" + (rotJson == null ? "" : ",\"rot\":" + rotJson) + "}";
    }

    private static Rot rotOf(PlanPatch patch) {
        PlanOp.AddNode add = (PlanOp.AddNode) patch.ops().get(0);
        return ((Anchor.Absolute) add.node().anchor()).rot();
    }

    @Test
    void paramsAsPairsReadLikeTheObjectForm() {
        String anchor = absoluteAnchor("{\"turns\":0,\"mirror\":false}");
        PlanPatch object = patch(addNode(anchor, "{\"width\":7,\"depth\":7}"));
        PlanPatch pairs = patch(addNode(anchor, "[{\"key\":\"width\",\"value\":7},{\"key\":\"depth\",\"value\":7}]"));
        assertEquals(object, pairs);

        PlanPatch updateObject = patch("{\"op\":\"update_params\",\"id\":\"hut\",\"params\":{\"width\":9}}");
        PlanPatch updatePairs = patch(
                "{\"op\":\"update_params\",\"id\":\"hut\",\"params\":[{\"key\":\"width\",\"value\":9}]}");
        assertEquals(updateObject, updatePairs);
    }

    @Test
    void anEmptyPairArrayReadsAsNoParams() {
        PlanPatch emptyObject = patch(addNode(absoluteAnchor(null), "{}"));
        PlanPatch emptyPairs = patch(addNode(absoluteAnchor(null), "[]"));
        assertEquals(emptyObject, emptyPairs);
    }

    @Test
    void malformedPairsReportTheirJsonPath() {
        PlanJsonException missingKey = assertThrows(PlanJsonException.class,
                () -> patch(addNode(absoluteAnchor(null), "[{\"value\":7}]")));
        assertEquals("$.ops[0].node.params[0]: missing \"key\"", missingKey.getMessage());

        PlanJsonException nonStringKey = assertThrows(PlanJsonException.class,
                () -> patch(addNode(absoluteAnchor(null), "[{\"key\":3,\"value\":7}]")));
        assertEquals("$.ops[0].node.params[0].key: expected a string", nonStringKey.getMessage());

        PlanJsonException duplicateKey = assertThrows(PlanJsonException.class,
                () -> patch(addNode(absoluteAnchor(null),
                        "[{\"key\":\"width\",\"value\":7},{\"key\":\"width\",\"value\":8}]")));
        assertEquals("$.ops[0].node.params[1].key: duplicate key \"width\"", duplicateKey.getMessage());

        PlanJsonException missingValue = assertThrows(PlanJsonException.class,
                () -> patch(addNode(absoluteAnchor(null), "[{\"key\":\"width\"}]")));
        assertEquals("$.ops[0].node.params[0]: missing \"value\"", missingValue.getMessage());

        PlanJsonException notAnObject = assertThrows(PlanJsonException.class,
                () -> patch(addNode(absoluteAnchor(null), "[7]")));
        assertEquals("$.ops[0].node.params[0]: expected an object", notAnObject.getMessage());

        PlanJsonException notAnObjectOrArray = assertThrows(PlanJsonException.class,
                () -> patch(addNode(absoluteAnchor(null), "7")));
        assertEquals("$.ops[0].node.params: expected an object", notAnObjectOrArray.getMessage());

        PlanJsonException inUpdateParams = assertThrows(PlanJsonException.class,
                () -> patch("{\"op\":\"update_params\",\"id\":\"hut\",\"params\":[{\"value\":7}]}"));
        assertEquals("$.ops[0].params[0]: missing \"key\"", inUpdateParams.getMessage());
    }

    @Test
    void rotMembersAreOptional() {
        assertEquals(Rot.NONE, rotOf(patch(addNode(absoluteAnchor(null), "{}"))));
        assertEquals(Rot.NONE, rotOf(patch(addNode(absoluteAnchor("{}"), "{}"))));
        assertEquals(Rot.NONE, rotOf(patch(addNode(absoluteAnchor("{\"turns\":0,\"mirror\":false}"), "{}"))));
        assertEquals(new Rot(2, false), rotOf(patch(addNode(absoluteAnchor("{\"turns\":2}"), "{}"))));
        assertEquals(new Rot(0, true), rotOf(patch(addNode(absoluteAnchor("{\"mirror\":true}"), "{}"))));
    }

    @Test
    void rotMembersStillTypeCheckWhenPresent() {
        PlanJsonException e = assertThrows(PlanJsonException.class,
                () -> patch(addNode(absoluteAnchor("{\"turns\":\"x\"}"), "{}")));
        assertEquals("$.ops[0].node.anchor.rot.turns: expected an integer", e.getMessage());
        PlanJsonException b = assertThrows(PlanJsonException.class,
                () -> patch(addNode(absoluteAnchor("{\"mirror\":1}"), "{}")));
        assertEquals("$.ops[0].node.anchor.rot.mirror: expected true or false", b.getMessage());
    }
}
