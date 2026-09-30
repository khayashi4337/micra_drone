package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
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
     * footprint the structure allows (64 blocks, so index 63). The design's sign limit of "60 characters" is read
     * as four lines of 15 characters not counting the "|" separators, so the stored text's max is 63: four full
     * lines plus the three separators between them.
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
            sign text:str(max63)=! material:material=sign
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

    /**
     * The visual descriptions feed the registry version and later the image and AI prompts, where a part must not be
     * pinned to a default material (a caller may pick another one). These words are the default palette's blocks
     * (D/05 section 1.1.1) that no part description needs for another reason.
     */
    private static final List<String> DEFAULT_MATERIAL_WORDS = List.of("stone", "brick", "cobble", "oak", "plank", "log",
            "grated", "iron", "trapdoor", "gravel", "concrete", "barrel", "dirt", "poppy", "fence");

    @Test
    void visualDescriptionsDoNotNameADefaultMaterial() {
        for (PartType t : BuildingParts.registry().all()) {
            String text = t.visualDescription().toLowerCase(Locale.ROOT);
            for (String word : DEFAULT_MATERIAL_WORDS) {
                assertFalse(text.contains(word), t.id() + " names \"" + word + "\": " + t.visualDescription());
            }
        }
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
    void theRotationUnsupportedPartsAreExactlyTheParentAndWallBoundOnesAndAllRegistered() {
        assertEquals(Set.of("micra:structure", "micra:foundation", "micra:floor", "micra:wall", "micra:roof",
                "micra:door", "micra:window", "micra:sign", "micra:planter", "micra:trim", "micra:balcony"),
                BuildingParts.ROTATION_UNSUPPORTED);
        for (String id : BuildingParts.ROTATION_UNSUPPORTED) {
            assertTrue(BuildingParts.registry().contains(id), id + " must be a registered building part");
        }
    }

    @Test
    void doorsDeclareTheirVolatileState() {
        assertEquals(Set.of("open", "powered"), BuildingParts.registry().get("micra:door").volatileProps());
    }

    /** The default palette as design doc 05, section 1.1.1 lists it (all blocks are in the minecraft namespace). */
    private static final String DESIGN_PALETTE = """
            wall=stone_bricks floor=oak_planks roof=oak_planks foundation=cobblestone pillar=stone_bricks beam=oak_log \
            trim=stone_bricks glass=glass_pane door=oak_door gate=oak_fence_gate fence=oak_fence stairs=oak_stairs \
            ramp=stone catwalk=iron_trapdoor chimney=bricks path=gravel pad=smooth_stone marker=yellow_concrete \
            cargo=barrel sign=oak_wall_sign planter=dirt plant=poppy
            """;
    private static final String NAMESPACE = "minecraft:";
    private static final String ROLE_SEPARATOR = "=";

    @Test
    void defaultPaletteIsTheDocumentedOne() {
        Map<String, String> documented = new TreeMap<>();
        for (String entry : DESIGN_PALETTE.strip().split("\\s+")) {
            String[] roleAndBlock = entry.split(ROLE_SEPARATOR);
            assertEquals(2, roleAndBlock.length, entry);
            assertNull(documented.put(roleAndBlock[0], NAMESPACE + roleAndBlock[1]), "role listed twice: " + entry);
        }
        assertEquals(22, documented.size());
        assertEquals(documented, BuildingParts.DEFAULT_PALETTE);
        assertEquals(documented, BuildingParts.registry().defaultPalette());
    }

    /**
     * How each part's blocks are compared after building, from the rule in design doc 05, section 1.1.1 ("検証の既定"):
     * blocks without state are EXACT; stairs, slabs, doors, gates, ladders, signs, lanterns and barrels are STATE_SUBSET
     * (only the recorded states are compared); glass panes and fences, whose state follows their neighbours, are
     * BLOCK_ONLY. A part takes the strictest mode that holds for every block it places.
     */
    private static final Map<String, VerifyMode> DESIGN_VERIFY = Map.ofEntries(
            // no blocks of its own, and blocks without state
            Map.entry("structure", VerifyMode.EXACT), Map.entry("foundation", VerifyMode.EXACT),
            Map.entry("wall", VerifyMode.EXACT), Map.entry("pillar", VerifyMode.EXACT),
            Map.entry("planter", VerifyMode.EXACT), Map.entry("road", VerifyMode.EXACT),
            // slabs, logs (axis), stairs, doors and gates, ladders, lanterns, signs, barrels
            Map.entry("floor", VerifyMode.STATE_SUBSET), Map.entry("beam", VerifyMode.STATE_SUBSET),
            Map.entry("roof", VerifyMode.STATE_SUBSET), Map.entry("door", VerifyMode.STATE_SUBSET),
            Map.entry("stairs", VerifyMode.STATE_SUBSET), Map.entry("ladder", VerifyMode.STATE_SUBSET),
            Map.entry("chimney", VerifyMode.STATE_SUBSET), Map.entry("ramp", VerifyMode.STATE_SUBSET),
            Map.entry("lamp", VerifyMode.STATE_SUBSET), Map.entry("sign", VerifyMode.STATE_SUBSET),
            Map.entry("trim", VerifyMode.STATE_SUBSET), Map.entry("dock_pad", VerifyMode.STATE_SUBSET),
            // glass panes and fences
            Map.entry("window", VerifyMode.BLOCK_ONLY), Map.entry("catwalk", VerifyMode.BLOCK_ONLY),
            Map.entry("balcony", VerifyMode.BLOCK_ONLY), Map.entry("railing", VerifyMode.BLOCK_ONLY));

    @Test
    void verifyModesFollowTheDesignRule() {
        assertEquals(new TreeSet<>(BuildingParts.NAMES), new TreeSet<>(DESIGN_VERIFY.keySet()));
        for (String name : BuildingParts.NAMES) {
            assertEquals(DESIGN_VERIFY.get(name), BuildingParts.registry().get(BuildingParts.ID_PREFIX + name).verify(), name);
        }
    }

    /**
     * A golden, not a derived number: the hash of every part, parameter range, description and the default palette. It
     * changes whenever any of them does, so a deliberate registry change updates it here and, because a manifest's hash
     * includes the registry version, the hash line of the golden hut manifest (src/test/resources/build/golden).
     */
    private static final String PINNED_REGISTRY_VERSION = "726753df4a5db0e1bcac2ff10f171a0e64dd88010cc408654963eead8cf9e350";

    @Test
    void theRegistryVersionIsPinned() {
        assertEquals(PINNED_REGISTRY_VERSION, BuildingParts.registry().version());
    }

    private static final Path LANG_EN_US = Path.of("src/main/resources/assets/micradrone/lang/en_us.json");
    private static final String DISPLAY_KEY_PREFIX = "micradrone.part.";

    @Test
    void everyDisplayNameKeyExistsOnceAndNotBlankInTheEnglishLanguageFile() throws IOException {
        String text = Files.readString(LANG_EN_US, StandardCharsets.UTF_8);
        // parsing fails on invalid JSON; the map keeps only the last of two equal keys, so duplicates are counted in the text
        Map<?, ?> lang = assertInstanceOf(Map.class, MiniJson.parse(text));
        Set<String> expectedKeys = new TreeSet<>();
        for (String name : BuildingParts.NAMES) {
            expectedKeys.add(DISPLAY_KEY_PREFIX + name);
            assertEquals(DISPLAY_KEY_PREFIX + name, BuildingParts.registry().get(BuildingParts.ID_PREFIX + name).displayNameKey());
        }
        Set<String> partKeys = new TreeSet<>();
        for (Object key : lang.keySet()) {
            if (String.valueOf(key).startsWith(DISPLAY_KEY_PREFIX)) {
                partKeys.add(String.valueOf(key));
            }
        }
        assertEquals(expectedKeys, partKeys, "the file has a part key that is not a part, or lacks one");
        for (String key : expectedKeys) {
            Object value = lang.get(key);
            assertTrue(value instanceof String s && !s.isBlank(), key + " must have a non-blank text");
            assertEquals(1, text.split("\"" + Pattern.quote(key) + "\"", -1).length - 1, key + " appears more than once");
        }
    }
}
