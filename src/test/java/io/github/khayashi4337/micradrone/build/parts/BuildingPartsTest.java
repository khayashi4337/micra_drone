package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.ParamValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuildingPartsTest {
    private static final List<String> EXPECTED = List.of("balcony", "beam", "catwalk", "chimney", "dock_pad", "door",
            "floor", "foundation", "ladder", "lamp", "planter", "pillar", "railing", "ramp", "road", "roof", "sign",
            "stairs", "structure", "trim", "wall", "window").stream().sorted().toList();

    /**
     * Parameters as design doc 05, section 1.1.1 lists them: one row per part, then one token per parameter:
     * {@code name:int(min..max)=default}, {@code name:bool=default}, {@code name:enum(a|b)=default} ({@code !} =
     * required), {@code name:material=role}, {@code name:str(max N)=!}, {@code name:ints(min..max, N items)=[]}.
     * The table leaves the upper bounds of wall from/length and cargo_u/cargo_w open ("0~"); they are the largest
     * footprint the structure allows (64 blocks, so index 63).
     */
    private static final String DESIGN_TABLE = """
            structure width:int(3..64)=7 depth:int(3..64)=7 floors:int(1..8)=1 floor_height:int(3..8)=4
            foundation margin:int(0..8)=0 depth:int(1..8)=1 material:material=foundation
            floor level:int(0..7)=0 kind:enum(block|slab)=block holes:ints(0..63,64items)=[] material:material=floor
            wall side:enum(north|east|south|west)=! level:int(0..7)=0 height:int(0..16)=0 thickness:int(1..3)=1 \
            from:int(0..63)=0 length:int(0..64)=0 part:enum(full|half)=full material:material=wall
            pillar height:int(1..32)=4 base:bool=true capital:bool=true material:material=pillar
            beam axis:enum(u|v|w)=u length:int(1..64)=3 material:material=beam
            roof kind:enum(gable|hip|flat|shed|sawtooth|monitor)=gable overhang:int(0..3)=1 ridge:enum(auto|u|w)=auto \
            high_side:enum(north|east|south|west)=east gable_fill:bool=true tooth:int(2..8)=3 \
            monitor_width:int(1..5)=1 monitor_height:int(1..3)=1 material:material=roof
            door kind:enum(single|double|hangar)=single width:int(3..9)=5 height:int(3..6)=4 \
            hinge:enum(left|right)=left material:material=door
            window kind:enum(pane|wide|arch)=pane lattice:bool=false material:material=glass
            stairs steps:int(1..32)=4 width:int(1..8)=1 dir:enum(north|east|south|west)=north material:material=stairs
            ladder height:int(1..32)=3 facing:enum(north|east|south|west)=north
            catwalk length:int(1..64)=6 dir:enum(north|east|south|west)=north width:int(1..5)=2 rail:bool=true \
            material:material=catwalk
            balcony width:int(1..16)=3 depth:int(1..8)=2 rail:bool=true material:material=floor
            railing length:int(1..64)=3 dir:enum(north|east|south|west)=north height:int(1..3)=1 material:material=fence
            chimney height:int(2..32)=6 size:int(1..3)=1 cap:bool=true material:material=chimney
            ramp length:int(2..32)=6 dir:enum(north|east|south|west)=north width:int(1..8)=2 material:material=ramp
            lamp kind:enum(lantern|hanging|post|torch)=lantern height:int(1..6)=2
            sign text:str(max60)=! material:material=sign
            planter width:int(1..8)=3
            trim length:int(1..64)=3 axis:enum(horizontal|vertical)=horizontal shape:enum(block|slab)=block \
            material:material=trim
            dock_pad width:int(5..64)=9 depth:int(5..64)=9 clearance:int(4..64)=16 cargo_u:int(0..63)=1 \
            cargo_w:int(0..63)=1 marker:bool=true material:material=pad
            road length:int(1..128)=8 dir:enum(north|east|south|west)=north width:int(1..8)=2 material:material=path
            """;

    private static final String REQUIRED_MARK = "!";

    private static String describe(ParamSpec p) {
        String defaultText = p.required() ? REQUIRED_MARK : String.valueOf(p.defaultValue().toTree());
        return switch (p.type()) {
            case INT -> p.name() + ":int(" + p.min().toTree() + ".." + p.max().toTree() + ")=" + defaultText;
            case BOOL -> p.name() + ":bool=" + defaultText;
            case ENUM -> p.name() + ":enum(" + String.join("|", p.enumValues()) + ")=" + defaultText;
            case MATERIAL -> p.name() + ":material=" + defaultText;
            case STR -> p.name() + ":str(max" + p.max().toTree() + ")=" + defaultText;
            case INT_LIST -> p.name() + ":ints(" + p.min().toTree() + ".." + p.max().toTree() + ","
                    + p.maxItems() + "items)=" + defaultText;
            case NUM -> throw new AssertionError("no building part has a NUM parameter: " + p.name());
        };
    }

    @Test
    void registersExactlyTheTwentyTwoBuildingParts() {
        PartTypeRegistry r = BuildingParts.registry();
        assertEquals(22, BuildingParts.NAMES.size());
        assertEquals(EXPECTED, BuildingParts.NAMES);
        for (String name : BuildingParts.NAMES) {
            PartType t = r.get(BuildingParts.ID_PREFIX + name);
            assertEquals(Visibility.USER, t.visibility(), name);
            assertEquals("micradrone.part." + name, t.displayNameKey());
            assertFalse(t.visualDescription().isBlank(), name + " needs a visual description");
        }
        assertEquals(22, r.userParts().size());
    }

    @Test
    void everyDefaultPassesItsOwnSpec() throws Exception {
        for (PartType t : BuildingParts.registry().all()) {
            for (ParamSpec p : t.params()) {
                if (p.defaultValue() != null) {
                    ParamValue coerced = ParamValidator.coerce(p, p.defaultValue());
                    assertEquals(p.defaultValue(), coerced, t.id() + "." + p.name());
                }
            }
        }
    }

    @Test
    void everyMaterialParameterDefaultsToARoleThatExistsInTheDefaultPalette() {
        for (PartType t : BuildingParts.registry().all()) {
            for (ParamSpec p : t.params()) {
                if (p.type() == ParamType.MATERIAL) {
                    String role = ((ParamValue.MaterialV) p.defaultValue()).value();
                    assertTrue(BuildingParts.DEFAULT_PALETTE.containsKey(role), t.id() + "." + p.name() + " -> " + role);
                }
            }
        }
    }

    @Test
    void requiredParametersAreDeclaredWhereAGuessWouldBeWrong() {
        assertTrue(BuildingParts.registry().get("micra:wall").param("side").orElseThrow().required());
        assertTrue(BuildingParts.registry().get("micra:sign").param("text").orElseThrow().required());
        assertFalse(BuildingParts.registry().get("micra:structure").param("width").orElseThrow().required());
    }

    @Test
    void parametersFollowTheDesignTable() {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        for (String row : DESIGN_TABLE.strip().split("\n")) {
            String[] tokens = row.trim().split(" +");
            expected.put(tokens[0], List.of(tokens).subList(1, tokens.length));
        }
        Map<String, List<String>> actual = new LinkedHashMap<>();
        for (String name : BuildingParts.NAMES) {
            actual.put(name, BuildingParts.registry().get(BuildingParts.ID_PREFIX + name).params().stream()
                    .map(BuildingPartsTest::describe).toList());
        }
        assertEquals(Set.copyOf(expected.keySet()), Set.copyOf(actual.keySet()));
        for (Map.Entry<String, List<String>> row : expected.entrySet()) {
            assertEquals(row.getValue(), actual.get(row.getKey()), row.getKey());
        }
    }

    @Test
    void phasesFollowTheDesignTable() {
        PartTypeRegistry r = BuildingParts.registry();
        for (String n : List.of("foundation", "floor", "pillar", "beam", "chimney", "stairs", "ramp", "structure")) {
            assertEquals(BuildPhase.STRUCTURE, r.get(BuildingParts.ID_PREFIX + n).phase(), n);
        }
        for (String n : List.of("wall", "roof", "door", "window")) {
            assertEquals(BuildPhase.ENVELOPE, r.get(BuildingParts.ID_PREFIX + n).phase(), n);
        }
        for (String n : List.of("ladder", "catwalk", "balcony", "railing", "lamp", "sign", "planter", "trim")) {
            assertEquals(BuildPhase.DECORATION, r.get(BuildingParts.ID_PREFIX + n).phase(), n);
        }
        for (String n : List.of("dock_pad", "road")) {
            assertEquals(BuildPhase.LOGISTICS, r.get(BuildingParts.ID_PREFIX + n).phase(), n);
        }
    }

    @Test
    void doorsDeclareTheirVolatileState() {
        assertEquals(Set.of("open", "powered"), BuildingParts.registry().get("micra:door").volatileProps());
    }

    @Test
    void defaultPaletteIsTheDocumentedOne() {
        assertEquals("minecraft:stone_bricks", BuildingParts.DEFAULT_PALETTE.get("wall"));
        assertEquals("minecraft:oak_planks", BuildingParts.DEFAULT_PALETTE.get("roof"));
        assertEquals("minecraft:glass_pane", BuildingParts.DEFAULT_PALETTE.get("glass"));
        assertEquals(22, BuildingParts.DEFAULT_PALETTE.size());
        assertNotNull(BuildingParts.registry().version());
    }

    @Test
    void everyDisplayNameKeyExistsInTheEnglishLanguageFile() throws IOException {
        String lang = Files.readString(Path.of("src/main/resources/assets/micradrone/lang/en_us.json"), StandardCharsets.UTF_8);
        for (String name : BuildingParts.NAMES) {
            assertTrue(lang.contains("\"micradrone.part." + name + "\""), name);
        }
    }
}
