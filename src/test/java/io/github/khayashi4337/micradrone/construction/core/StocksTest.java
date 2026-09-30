package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.List;
import org.junit.jupiter.api.Test;

class StocksTest {
    private static final String CHEST = MaterialPort.CHEST_PREFIX + "1,2,3";

    @Test
    void theInventoryIsDrawnFirstThenTheChestsInOrder() {
        List<Stock> stocks = List.of(new Stock(MaterialPort.INVENTORY, "a:x", 2), new Stock(CHEST, "a:x", 5));
        assertEquals(List.of(new Move(MaterialPort.INVENTORY, "a:x", -2), new Move(CHEST, "a:x", -2)),
                Stocks.takeMoves(List.of(new ItemCount("a:x", 4)), stocks));
        assertEquals(List.of(new ItemCount("a:x", 1)), Stocks.missing(List.of(new ItemCount("a:x", 8)), stocks));
        assertThrows(IllegalArgumentException.class, () -> Stocks.takeMoves(List.of(new ItemCount("a:x", 8)), stocks));
    }

    @Test
    void givesGoToOneTargetAndMovesAddUp() {
        List<Move> give = Stocks.giveMoves(List.of(new ItemCount("a:x", 1), new ItemCount("a:x", 2)), MaterialPort.INVENTORY);
        assertEquals(List.of(new Move(MaterialPort.INVENTORY, "a:x", 3)), give);
        assertEquals(List.of(new ItemCount("a:x", 3)), Stocks.items(give));
    }
}
