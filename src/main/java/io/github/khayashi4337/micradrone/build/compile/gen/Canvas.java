package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** The cells generated so far, keyed by local position; a position has at most one owner. */
public final class Canvas {
    /** Names in Issue.data shared by the overlap and the out-of-bounds issues: how many cells, and the first one. */
    public static final String DATA_COUNT = "count";
    public static final String DATA_FIRST_POS = "firstPos";
    /** The Issue key and data name of the cell-budget refusal. */
    public static final String KEY_CELLS = "cells";
    private static final String DATA_REASON = "reason";
    private static final String OWNER_PAIR_SEPARATOR = "|";
    private static final String POS_SEPARATOR = ",";
    /**
     * Placement attempts allowed per cell of the budget. Parts that overlap place nothing new, so the cell budget alone
     * would not bound the work of many parts piled on one spot; a plan that needs more attempts is all overlap anyway.
     */
    private static final int ATTEMPTS_PER_CELL = 2;

    /** {@code mergeGroup}/{@code mergeVariant}: two cells of one group with the same block but different variants join (wall corners). */
    public record Cell(LocalPos pos, BlockSpec block, VerifyMode verify, BuildPhase phase, Map<String, String> blockEntity,
                       String ownerId, String mergeGroup, String mergeVariant) {
    }

    private record Overlap(String a, String b, int count, LocalPos first, String reason) {
    }

    private final Map<LocalPos, Cell> cells = new HashMap<>();
    private final TreeMap<String, Overlap> overlaps = new TreeMap<>();
    private final int maxCells;
    private final long maxAttempts;
    private long attempts;

    public Canvas(int maxCells) {
        this.maxCells = maxCells;
        this.maxAttempts = (long) maxCells * ATTEMPTS_PER_CELL;
    }

    /** "u,v,w": the form positions take in issue messages and data. */
    public static String posText(LocalPos p) {
        return p.u() + POS_SEPARATOR + p.v() + POS_SEPARATOR + p.w();
    }

    public Cell get(LocalPos pos) {
        return cells.get(pos);
    }

    public Cell remove(LocalPos pos) {
        return cells.remove(pos);
    }

    public int size() {
        return cells.size();
    }

    /** How many units of work were counted against the attempt budget so far (see {@link #charge}). */
    public long attempts() {
        return attempts;
    }

    /** A read-only view; cells change only through {@link #put} and {@link #remove}. */
    public Collection<Cell> all() {
        return Collections.unmodifiableCollection(cells.values());
    }

    /** Places a cell. A cell already there means an overlap, unless both belong to the same merge group with the same block. */
    public boolean put(Cell cell) {
        charge();
        Cell existing = cells.get(cell.pos());
        if (existing != null) {
            boolean merge = existing.mergeGroup() != null && existing.mergeGroup().equals(cell.mergeGroup())
                    && existing.block().equals(cell.block())
                    && !Objects.equals(existing.mergeVariant(), cell.mergeVariant());
            if (!merge) {
                recordOverlap(existing.ownerId(), cell.ownerId(), cell.pos());
            }
            return false;
        }
        if (cells.size() >= maxCells) {
            throw budgetExceeded("施工の大きさの上限(" + maxCells + "マス)を超えました");
        }
        cells.put(cell.pos(), cell);
        return true;
    }

    /**
     * Counts one cell of work against the budget. {@link #put} charges itself; a generator that considers a cell and
     * then leaves it empty (a floor hole) charges it too, so a part that places nothing still has bounded work.
     */
    public void charge() {
        if (++attempts > maxAttempts) {
            throw budgetExceeded("施工の作業量の上限(" + maxAttempts + "マス分。ほとんどが重なりか穴です)を超えました");
        }
    }

    /** Fatal: the whole compile stops, since every further part would only add to the work. */
    private GenAbort budgetExceeded(String message) {
        return new GenAbort(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_CELLS, List.of(), message,
                Map.of(KEY_CELLS, String.valueOf(maxCells)), List.of()), true);
    }

    public void recordOverlap(String ownerA, String ownerB, LocalPos pos) {
        recordOverlap(ownerA, ownerB, pos, "");
    }

    /**
     * {@code reason} says why the two claim the same cell when it is not plain overlap (e.g. "clearance"). A pair is one
     * issue, so once any of its overlaps has a reason the pair keeps it, whichever kind was seen first.
     */
    public void recordOverlap(String ownerA, String ownerB, LocalPos pos, String reason) {
        boolean inOrder = ownerA.compareTo(ownerB) <= 0;
        String a = inOrder ? ownerA : ownerB;
        String b = inOrder ? ownerB : ownerA;
        String key = a + OWNER_PAIR_SEPARATOR + b;
        Overlap prev = overlaps.get(key);
        overlaps.put(key, prev == null ? new Overlap(a, b, 1, pos, reason)
                : new Overlap(a, b, prev.count() + 1, prev.first(), prev.reason().isEmpty() ? reason : prev.reason()));
    }

    private static Map<String, String> overlapData(Overlap o) {
        Map<String, String> data = new TreeMap<>();
        data.put(DATA_COUNT, String.valueOf(o.count()));
        data.put(DATA_FIRST_POS, posText(o.first()));
        if (!o.reason().isEmpty()) {
            data.put(DATA_REASON, o.reason());
        }
        return data;
    }

    /** One E-OVERLAP per pair of owners, in the dictionary order of the pairs. */
    public List<Issue> overlapIssues() {
        List<Issue> out = new ArrayList<>();
        for (Overlap o : overlaps.values()) {
            out.add(Issue.of(IssueCode.E_OVERLAP, "", List.of(o.a(), o.b()),
                    o.a() + "と" + o.b() + "が同じ場所に重なっています(" + o.count() + "マス。最初は " + posText(o.first()) + ")",
                    overlapData(o), List.of()));
        }
        return out;
    }
}
