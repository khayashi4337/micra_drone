package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.EnumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.ListV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.MaterialV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.NumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParamValidatorTest {
    private static final ParamSpec WIDTH = ParamSpec.integer("width", 3, 64, 7);
    private static final ParamSpec KIND = ParamSpec.enumOf("kind", "gable", "gable", "hip", "flat");
    private static final ParamSpec SIDE = ParamSpec.enumOf("side", null, "north", "south");
    private static final ParamSpec MATERIAL = ParamSpec.material("material", "wall");
    private static final ParamSpec TEXT = ParamSpec.text("text", 5, null);
    private static final ParamSpec HOLES = ParamSpec.intList("holes", 0, 63, 8);
    private static final ParamSpec FLAG = ParamSpec.bool("flag", false);

    @Test
    void integersAcceptIntegralNumbersAndRejectFractionsAndOverflow() throws Exception {
        assertEquals(new IntV(7), ParamValidator.coerce(WIDTH, new IntV(7)));
        assertEquals(new IntV(7), ParamValidator.coerce(WIDTH, new NumV(7.0)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(WIDTH, new NumV(7.5)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(WIDTH, new NumV(3_000_000_000.0)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(WIDTH, new IntV(2)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(WIDTH, new IntV(65)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(WIDTH, new StrV("7")));
    }

    @Test
    void enumsBecomeEnumValuesAndMustBeListed() throws Exception {
        assertEquals(new EnumV("hip"), ParamValidator.coerce(KIND, new StrV("hip")));
        assertEquals(new EnumV("hip"), ParamValidator.coerce(KIND, new EnumV("hip")));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(KIND, new StrV("dome")));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(KIND, new IntV(1)));
    }

    @Test
    void materialsAreRolesOrBlockIds() throws Exception {
        assertEquals(new MaterialV("roof"), ParamValidator.coerce(MATERIAL, new StrV("roof")));
        assertEquals(new MaterialV("minecraft:stone"), ParamValidator.coerce(MATERIAL, new StrV("minecraft:stone")));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(MATERIAL, new StrV("Not Valid!")));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(MATERIAL, new StrV("")));
    }

    @Test
    void numbersBoolsTextsAndIntLists() throws Exception {
        ParamSpec speed = new ParamSpec("speed", ParamType.NUM, "rpm", new NumV(0), new NumV(256), new NumV(16), List.of(), 0);
        assertEquals(new NumV(4.0), ParamValidator.coerce(speed, new IntV(4)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(speed, new NumV(300)));
        assertEquals(new BoolV(true), ParamValidator.coerce(FLAG, new BoolV(true)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(FLAG, new IntV(1)));
        assertEquals(new StrV("abc"), ParamValidator.coerce(TEXT, new StrV("abc")));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(TEXT, new StrV("abcdef")));
        assertEquals(new ListV(List.of(new IntV(1), new IntV(2))),
                ParamValidator.coerce(HOLES, new ListV(List.of(new IntV(1), new NumV(2.0)))));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(HOLES, new ListV(List.of(new IntV(64)))));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(HOLES,
                new ListV(List.of(new IntV(0), new IntV(0), new IntV(0), new IntV(0), new IntV(0), new IntV(0), new IntV(0),
                        new IntV(0), new IntV(0)))));
    }

    @Test
    void aOneSidedBoundIsNamedAloneInsteadOfAsADanglingRange() {
        ParamSpec atLeast = new ParamSpec("floor", ParamType.NUM, "", new NumV(1), null, null, List.of(), 0);
        ParamSpec atMost = new ParamSpec("ceiling", ParamType.INT, "", null, new IntV(9), null, List.of(), 0);
        ParamException low = assertThrows(ParamException.class, () -> ParamValidator.coerce(atLeast, new NumV(0.5)));
        assertEquals("1.0以上にしてください", low.getMessage());
        ParamException high = assertThrows(ParamException.class, () -> ParamValidator.coerce(atMost, new IntV(10)));
        assertEquals("9以下にしてください", high.getMessage());
        ParamException both = assertThrows(ParamException.class, () -> ParamValidator.coerce(WIDTH, new IntV(2)));
        assertEquals("3〜64の範囲にしてください", both.getMessage());
    }

    private static PartType sample() {
        return PartType.builder("test:thing", PartCategory.STRUCTURE).displayNameKey("k")
                .params(WIDTH, KIND, SIDE, MATERIAL).build();
    }

    @Test
    void validateReportsEveryProblemAsParamRangeWithTheParameterAsKey() {
        Map<String, ParamValue> given = Map.of("width", new IntV(2), "kind", new StrV("dome"), "nope", new IntV(1));
        ParamValidator.Result r = ParamValidator.validate("thing-1", sample(), given);
        assertTrue(r.issues().stream().allMatch(i -> i.code() == IssueCode.E_PARAM_RANGE));
        List<String> ids = r.issues().stream().map(Issue::id).toList();
        assertTrue(ids.contains("E-PARAM-RANGE:thing-1#width"), ids.toString());
        assertTrue(ids.contains("E-PARAM-RANGE:thing-1#kind"), ids.toString());
        assertTrue(ids.contains("E-PARAM-RANGE:thing-1#nope"), "unknown name: " + ids);
        assertTrue(ids.contains("E-PARAM-RANGE:thing-1#side"), "missing required parameter: " + ids);
        Issue width = r.issues().stream().filter(i -> i.id().endsWith("#width")).findFirst().orElseThrow();
        assertEquals("3", width.data().get("min"));
        assertEquals("64", width.data().get("max"));
    }

    @Test
    void validateReturnsTypedParametersWithoutFillingDefaults() {
        Map<String, ParamValue> given = Map.of("width", new NumV(9.0), "kind", new StrV("hip"), "side", new StrV("north"));
        ParamValidator.Result r = ParamValidator.validate("thing-1", sample(), given);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals(new IntV(9), r.typed().get("width"));
        assertEquals(new EnumV("hip"), r.typed().get("kind"));
        assertEquals(3, r.typed().size(), "defaults are resolved at compile time, not stored");
    }

    @Test
    void paramsResolveDefaultsAndReadTypedValues() {
        Params p = Params.resolve(sample(), Map.of("side", new EnumV("south"), "width", new IntV(9)));
        assertEquals(9, p.i("width"));
        assertEquals("gable", p.s("kind"));
        assertEquals("south", p.s("side"));
        assertEquals("wall", p.s("material"));
        assertThrows(IllegalArgumentException.class, () -> p.i("nope"));
    }
}
