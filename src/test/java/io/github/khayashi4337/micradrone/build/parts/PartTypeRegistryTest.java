package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PartTypeRegistryTest {
    private static PartType part(String id, Visibility visibility, String key) {
        return PartType.builder(id, PartCategory.STRUCTURE).visibility(visibility).displayNameKey(key)
                .params(ParamSpec.integer("n", 1, 5, 2)).build();
    }

    private static PartTypeRegistry registry(int defaultN) {
        return PartTypeRegistry.builder()
                .register(PartType.builder("test:b", PartCategory.STRUCTURE).displayNameKey("b")
                        .params(ParamSpec.integer("n", 1, 5, defaultN)).build())
                .register(part("test:a", Visibility.USER, "a"))
                .register(part("test:hidden", Visibility.IMPLICIT, null))
                .defaultPalette(Map.of("wall", "minecraft:stone"))
                .build();
    }

    @Test
    void partsAreSortedByIdAndUserPartsExcludeImplicit() {
        PartTypeRegistry r = registry(2);
        assertEquals(List.of("test:a", "test:b", "test:hidden"), r.all().stream().map(PartType::id).toList());
        assertEquals(List.of("test:a", "test:b"), r.userParts().stream().map(PartType::id).toList());
        assertTrue(r.contains("test:hidden"));
        assertTrue(r.find("test:nope").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> r.get("test:nope"));
    }

    @Test
    void versionIsStableAndSensitiveToPartsAndPalette() {
        assertEquals(registry(2).version(), registry(2).version());
        assertNotEquals(registry(2).version(), registry(3).version(), "a changed default changes the version");
        PartTypeRegistry otherPalette = PartTypeRegistry.builder()
                .register(part("test:a", Visibility.USER, "a")).defaultPalette(Map.of("wall", "minecraft:bricks")).build();
        PartTypeRegistry samePalette = PartTypeRegistry.builder()
                .register(part("test:a", Visibility.USER, "a")).defaultPalette(Map.of("wall", "minecraft:stone")).build();
        assertNotEquals(otherPalette.version(), samePalette.version());
        assertEquals(64, registry(2).version().length());
    }

    @Test
    void registrationOrderDoesNotChangeTheVersion() {
        PartTypeRegistry ab = PartTypeRegistry.builder().register(part("test:a", Visibility.USER, "a"))
                .register(part("test:b", Visibility.USER, "b")).build();
        PartTypeRegistry ba = PartTypeRegistry.builder().register(part("test:b", Visibility.USER, "b"))
                .register(part("test:a", Visibility.USER, "a")).build();
        assertEquals(ab.version(), ba.version());
    }

    private static Map<String, String> linked(String firstKey, String secondKey) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(firstKey, firstKey + "-value");
        m.put(secondKey, secondKey + "-value");
        return m;
    }

    private static Set<String> linkedSet(String first, String second) {
        Set<String> s = new LinkedHashSet<>();
        s.add(first);
        s.add(second);
        return s;
    }

    private static PartType wired(Set<String> accepts, Map<String, String> sizes, String... volatileProps) {
        return PartType.builder("test:wired", PartCategory.STRUCTURE).displayNameKey("w")
                .ports(new PortSpec("in", PortKind.ITEM_IN, new LocalPos(0, 0, 0), Dir6.UP, accepts))
                .volume(new VolumeSpec(List.of(), sizes)).volatileProps(volatileProps).build();
    }

    @Test
    void setsAndMapsInsideAPartAreSortedSoTheVersionDoesNotDependOnTheirOrder() {
        PartType ab = wired(linkedSet("a", "b"), linked("x", "y"), "p", "q");
        PartType ba = wired(linkedSet("b", "a"), linked("y", "x"), "q", "p");
        assertEquals(ab, ba);
        assertEquals(List.of("a", "b"), List.copyOf(ba.ports().get(0).accepts()));
        assertEquals(List.of("x", "y"), List.copyOf(ba.volume().sizeFromParams().keySet()));
        assertEquals(List.of("p", "q"), List.copyOf(ba.volatileProps()));
        assertEquals(PartTypeRegistry.builder().register(ab).build().version(),
                PartTypeRegistry.builder().register(ba).build().version());
        assertThrows(UnsupportedOperationException.class, () -> ba.ports().get(0).accepts().add("c"));
        assertThrows(UnsupportedOperationException.class, () -> ba.volume().sizeFromParams().put("z", "1"));
        assertThrows(UnsupportedOperationException.class, () -> ba.volatileProps().add("r"));
    }

    @Test
    void duplicateIdsAreRejected() {
        PartTypeRegistry.Builder b = PartTypeRegistry.builder().register(part("test:a", Visibility.USER, "a"));
        assertThrows(IllegalArgumentException.class, () -> b.register(part("test:a", Visibility.USER, "a")));
    }

    @Test
    void partTypeInvariants() {
        assertThrows(IllegalArgumentException.class, () -> part("Bad Id", Visibility.USER, "k"));
        assertThrows(IllegalArgumentException.class, () -> part("test:user_without_name", Visibility.USER, null));
        assertThrows(IllegalArgumentException.class, () -> PartType.builder("test:dup", PartCategory.STRUCTURE)
                .displayNameKey("k").params(ParamSpec.integer("n", 1, 2, 1), ParamSpec.integer("n", 1, 2, 1)).build());
        PartType t = part("test:a", Visibility.USER, "a");
        assertTrue(t.param("n").isPresent());
        assertFalse(t.param("x").isPresent());
        assertEquals(EffectKind.NONE, t.effect().kind());
    }

    @Test
    void suggestReturnsNearbyIds() {
        PartTypeRegistry r = registry(2);
        assertEquals(List.of("test:a"), r.suggest("test:aa", 1));
        assertTrue(r.suggest("zzz:qqqqqq", 3).size() <= 3);
    }
}
