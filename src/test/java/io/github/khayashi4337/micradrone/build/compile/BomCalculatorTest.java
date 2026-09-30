package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BomCalculatorTest {
    private static final String STONE = "minecraft:stone";
    private static final String OAK_DOOR = "minecraft:oak_door";
    private static final String OAK_SLAB = "minecraft:oak_slab";

    private static Placement at(int index, BlockSpec block) {
        return new Placement(index, new IntPos(index, 0, 0), block, Map.of(), "n", BuildPhase.STRUCTURE, PlacerId.SIMPLE,
                VerifyMode.EXACT, ReplacePolicy.REPLACEABLE, null);
    }

    @Test
    void countsItemsByBlockWithTheSpecialCases() {
        List<Placement> ps = new ArrayList<>();
        ps.add(at(0, BlockSpec.of(STONE)));
        ps.add(at(1, BlockSpec.of(STONE)));
        ps.add(at(2, BlockSpec.of(OAK_DOOR, "half", "lower")));
        ps.add(at(3, BlockSpec.of(OAK_DOOR, "half", "upper")));
        ps.add(at(4, BlockSpec.of(OAK_SLAB, "type", "double")));
        ps.add(at(5, BlockSpec.of(OAK_SLAB, "type", "bottom")));
        ps.add(at(6, BlockSpec.of("minecraft:oak_wall_sign", "facing", "north")));
        ps.add(at(7, BlockSpec.of("create:belt")));
        ps.add(at(8, BlockSpec.AIR));
        Map<String, Integer> bom = BomCalculator.bom(ps);
        assertEquals(2, bom.get(STONE));
        assertEquals(1, bom.get(OAK_DOOR));
        assertEquals(3, bom.get(OAK_SLAB), "double slab counts twice");
        assertEquals(1, bom.get("minecraft:oak_sign"));
        assertEquals(1, bom.get("create:belt_connector"));
        assertEquals(false, bom.containsKey("minecraft:air"));
        assertEquals(List.of("create:belt_connector", OAK_DOOR, "minecraft:oak_sign", OAK_SLAB, STONE),
                List.copyOf(bom.keySet()), "sorted by item id");
    }
}
