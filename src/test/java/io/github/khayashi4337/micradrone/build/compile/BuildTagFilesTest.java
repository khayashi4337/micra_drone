package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The datapack tags are the runtime's allow lists (D-22, F-5); the pure builtin list is their single source. */
class BuildTagFilesTest {
    private static final Path TAGS = Path.of("src/main/resources/data/micradrone/tags/block");

    @SuppressWarnings("unchecked")
    private static List<Object> values(String file) throws IOException {
        Map<String, Object> tree = (Map<String, Object>) MiniJson.parse(Files.readString(TAGS.resolve(file), StandardCharsets.UTF_8));
        return (List<Object>) tree.get("values");
    }

    @Test
    void thePaletteTagIsExactlyTheBuiltinAllowList() throws IOException {
        TreeSet<String> tag = new TreeSet<>();
        for (Object v : values("palette_allowed.json")) {
            tag.add((String) v);
        }
        assertEquals(new TreeSet<>(BuiltinAllowList.ids()), tag);
        for (String forbidden : PlaceableBlockPolicy.ALWAYS_FORBIDDEN) {
            assertTrue(!tag.contains(forbidden), forbidden);
        }
    }

    @Test
    void terraformableGroundIsNaturalGroundOnly() throws IOException {
        List<Object> v = values("terraformable.json");
        assertTrue(v.contains("#minecraft:dirt"));
        assertTrue(v.contains("#minecraft:base_stone_overworld"));
        assertTrue(v.contains("#minecraft:sand"));
        assertTrue(v.contains("minecraft:gravel"));
        assertTrue(!v.contains("minecraft:bedrock"));
    }
}
