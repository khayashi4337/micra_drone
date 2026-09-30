package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestCodecTest {
    @Test
    void theGoldenHutComesBackWithTheSameHashAndItsPaletteIsSmall() throws Exception {
        PlacementManifest hut = TestManifests.hut();
        Map<String, String> types = Map.of("wall-n", "micra:wall");
        Object tree = ManifestCodec.toTree(new ManifestCodec.Stored(hut, types));
        byte[] bytes = new PersistenceEnvelope(SaveTypes.MANIFEST, 1, tree).toBytes();
        ManifestCodec.Stored back = ManifestCodec.fromTree(SaveTypes.migrations().payloadOf(PersistenceEnvelope.fromBytes(bytes)));
        assertEquals(hut, back.manifest());
        assertEquals(types, back.nodeTypes());
        assertTrue(((List<?>) ((Map<?, ?>) tree).get("palette")).size() < 40, "238 placements share a few block states");
    }

    @Test
    void aTamperedManifestIsRefused() {
        PlacementManifest hut = TestManifests.hut();
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = new java.util.LinkedHashMap<>((Map<String, Object>) ManifestCodec.toTree(
                new ManifestCodec.Stored(hut, Map.of())));
        tree.put("hash", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> ManifestCodec.fromTree(tree));
    }
}
