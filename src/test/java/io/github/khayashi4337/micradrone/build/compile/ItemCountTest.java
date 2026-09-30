package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ItemCountTest {
    @Test
    void plusAddsItemsInIdOrder() {
        assertEquals(List.of(new ItemCount("a:x", 3), new ItemCount("b:y", 1)),
                ItemCount.plus(List.of(new ItemCount("b:y", 1), new ItemCount("a:x", 1)),
                        List.of(new ItemCount("a:x", 2))));
    }

    @Test
    void plusRefusesAnItemTotalPastTheIntRange() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ItemCount.plus(List.of(new ItemCount("x", Integer.MAX_VALUE)), List.of(new ItemCount("x", 1))));
        assertEquals("item total exceeds int range: x", e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ItemCount.plus(List.of(new ItemCount("x", Integer.MAX_VALUE), new ItemCount("x", 2)), List.of()),
                "the same item twice in one list can overflow too");
        assertThrows(IllegalArgumentException.class,
                () -> ItemCount.plus(List.of(new ItemCount("x", Integer.MAX_VALUE), new ItemCount("x", Integer.MAX_VALUE)),
                        List.of(new ItemCount("x", 3))),
                "2*max+3 wraps to a positive count in int arithmetic: it must be refused, not emitted");
    }

    @Test
    void minusDropsAnItemWhoseTotalReachesZeroOrBelow() {
        assertEquals(List.of(new ItemCount("a:x", 4)),
                ItemCount.minus(List.of(new ItemCount("a:x", 5), new ItemCount("b:y", 2)),
                        List.of(new ItemCount("a:x", 1), new ItemCount("b:y", 2))));
        assertEquals(List.of(), ItemCount.minus(List.of(new ItemCount("x", 5)),
                        List.of(new ItemCount("x", Integer.MAX_VALUE), new ItemCount("x", Integer.MAX_VALUE))),
                "5 - 2*int-max wraps positive in int arithmetic: the difference must be taken in long");
    }

    @Test
    void theSharedAccumulationRefusesAnOverflowedTotal() {
        TreeMap<String, Long> sum = new TreeMap<>();
        assertThrows(IllegalArgumentException.class, () -> ItemCount.addAll(sum,
                List.of(new ItemCount("x", Integer.MAX_VALUE), new ItemCount("x", 1))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BlockToItem.merge(List.of(new ItemCount("x", Integer.MAX_VALUE), new ItemCount("x", 1))),
                "BlockToItem's merge shares the same accumulation");
        assertEquals("item total exceeds int range: x", e.getMessage());
    }
}
