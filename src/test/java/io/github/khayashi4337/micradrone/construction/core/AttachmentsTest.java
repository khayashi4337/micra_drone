package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AttachmentsTest {
    private static RestoreItem undo(int x, int y, int z, BlockSpec now) {
        return new RestoreItem(new IntPos(x, y, z), now, Set.of(), BlockSpec.AIR, "job-1", 0, true);
    }

    @Test
    void attachedBlocksGoFirstAndADoorsHalvesStayTogether() {
        RestoreItem wall = undo(0, 64, 0, BlockSpec.of("minecraft:stone_bricks"));
        RestoreItem sign = undo(0, 64, 1, BlockSpec.of("minecraft:oak_wall_sign", "facing", "south"));
        RestoreItem lower = undo(2, 64, 0, BlockSpec.of("minecraft:oak_door", "half", "lower"));
        RestoreItem upper = undo(2, 65, 0, BlockSpec.of("minecraft:oak_door", "half", "upper"));
        RestoreItem roof = undo(0, 66, 0, BlockSpec.of("minecraft:oak_planks"));
        List<RestoreItem> all = new ArrayList<>(List.of(wall, sign, lower, upper, roof));
        all.sort(Attachments.REMOVAL_ORDER);
        assertEquals(List.of(upper, lower, sign, roof, wall), all);
        assertTrue(Attachments.samePiece(upper, lower));
        assertFalse(Attachments.samePiece(lower, sign));
        assertTrue(Attachments.dependent(sign.expectedNow()));
        assertTrue(Attachments.dependent(BlockSpec.of("minecraft:oak_trapdoor")), "a trapdoor hangs on its neighbour");
        assertTrue(Attachments.dependent(BlockSpec.of("minecraft:potted_poppy")));
        assertFalse(Attachments.dependent(wall.expectedNow()));
    }
}
