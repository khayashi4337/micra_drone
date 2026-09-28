package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The range-size check must hold at extreme coordinates: the volume is counted in long, since an
 * int subtraction of far-apart coordinates overflows before the cap can refuse the range. The tests
 * run against {@link BlockRangeDescription#rangeVolume} because the test classpath has no
 * {@code net.minecraft} types to build a {@code Level} from.
 */
class BlockRangeDescriptionTest {

    @Test
    void aRangeFromMinToMaxIntCountsEveryCell() {
        // (MAX_VALUE - MIN_VALUE + 1) is 2^32: in int it wraps to 0 and the too-large check is bypassed.
        assertEquals(4294967296L,
                BlockRangeDescription.rangeVolume(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0));
    }

    @Test
    void anExtremeRangeIsCountedAndRefused() {
        long volume = BlockRangeDescription.rangeVolume(Integer.MIN_VALUE, 0, 0,
                Integer.MIN_VALUE + 2000, 0, 0);
        assertEquals(2001L, volume);
        assertTrue(volume > BlockRangeDescription.MAX_BLOCKS_PER_QUERY);
    }

    @Test
    void theCapStillAppliesToWideRangesAtExtremeCoordinates() {
        long volume = BlockRangeDescription.rangeVolume(Integer.MIN_VALUE, Integer.MIN_VALUE, 0,
                Integer.MIN_VALUE + 34, Integer.MIN_VALUE + 34, 0);
        assertEquals(1225L, volume);
        assertTrue(volume > BlockRangeDescription.MAX_BLOCKS_PER_QUERY);
    }

    @Test
    void aRangeInsideTheCapCountsExactly() {
        assertEquals(1L, BlockRangeDescription.rangeVolume(5, 5, 5, 5, 5, 5));
        assertEquals(1000L, BlockRangeDescription.rangeVolume(0, 0, 0, 9, 9, 9));
        assertEquals(64L, BlockRangeDescription.rangeVolume(-3, -2, -1, 0, 1, 2));
    }

    @Test
    void reversedEndpointsCountTheSameRange() {
        assertEquals(BlockRangeDescription.rangeVolume(0, 0, 0, 9, 9, 9),
                BlockRangeDescription.rangeVolume(9, 9, 9, 0, 0, 0));
    }
}
