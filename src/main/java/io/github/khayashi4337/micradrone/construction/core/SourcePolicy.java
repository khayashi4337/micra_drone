package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Which stocks a survival job may see, and in which order it may take them (Task 27a). The owner's
 * inventory appears in the answer only when the claim's switch allows it, and then it comes first;
 * supply-chest stocks always follow in coordinate order. This single rule is what keeps a child's own
 * belongings from being spent while a supply chest held the same materials.
 */
public final class SourcePolicy {
    /** The deterministic supply order: ascending x, then y, then z. Also the order of the chest scan. */
    public static final Comparator<IntPos> POSITION_ORDER = Comparator.comparingInt(IntPos::x)
            .thenComparingInt(IntPos::y).thenComparingInt(IntPos::z);

    private SourcePolicy() {
    }

    /**
     * The stocks {@code all} that the job may draw on: without {@code inventoryAllowed} every
     * {@link MaterialPort#INVENTORY} stock is dropped (none left at all if there are no chests); with it the
     * inventory stocks lead. Chest stocks are sorted by their position's coordinates; stocks of any other
     * source keep their place at the end. A stock whose item id sits in {@code excludedItems} is
     * dropped no matter which source it sits in (Task 27b) - this view is the TAKE side only, so the
     * give side (terraform leftovers, returned surplus) is untouched. The argument is not mutated.
     */
    public static List<Stock> visibleStocks(List<Stock> all, boolean inventoryAllowed,
                                            Set<String> excludedItems) {
        List<Stock> inventory = new ArrayList<>();
        List<Stock> chests = new ArrayList<>();
        List<Stock> rest = new ArrayList<>();
        for (Stock s : all) {
            if (excludedItems.contains(s.itemId())) {
                continue;
            }
            if (MaterialPort.INVENTORY.equals(s.sourceId())) {
                inventory.add(s);
            } else if (MaterialPort.chestPos(s.sourceId()).isPresent()) {
                chests.add(s);
            } else {
                rest.add(s);
            }
        }
        chests.sort(Comparator.comparing(s -> MaterialPort.chestPos(s.sourceId()).orElseThrow(),
                POSITION_ORDER));
        List<Stock> out = new ArrayList<>(all.size());
        if (inventoryAllowed) {
            out.addAll(inventory);
        }
        out.addAll(chests);
        out.addAll(rest);
        return out;
    }
}
