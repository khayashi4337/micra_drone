package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.BlockToItem;
import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TerrainSummary;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The safety envelope (04 F-5), checked on the server before approval and before a single block is placed: size limits,
 * the build height, blocks that are always forbidden (again, server-side), items the game does not know, and every
 * position's replacement verdict on the pinned survey (the same ReplaceRules the executor uses later).
 */
public final class SafetyEnvelope {
    static final String SUBJECT_MANIFEST = "manifest";
    static final String KEY_PLACEMENTS = "placements";
    static final String KEY_SIZE = "size";
    static final String KEY_HEIGHT = "height";
    static final String KEY_BOUNDS = "bounds";
    static final String KEY_UNLOADED = "unloaded";
    static final String DATA_COUNT = "count";
    static final String DATA_LIMIT = "limit";
    static final String DATA_FIRST = "first";
    /** A range issue names the offending parts; the cap keeps a huge manifest from growing an issue id without bound. */
    static final int MAX_ISSUE_SUBJECTS = 16;

    private SafetyEnvelope() {
    }

    public static SafetyReport check(PlacementManifest m, TerrainSummary terrain, PlacementSurvey survey, SafetyLimits limits,
                                     PlaceableBlockPolicy policy, ItemCatalog items,
                                     Function<IntPos, Optional<BlockSpec>> ownPlaced) {
        List<Issue> issues = new ArrayList<>();
        int n = m.placements().size();
        if (n > limits.maxPlacements()) {
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_PLACEMENTS, List.of(SUBJECT_MANIFEST),
                    "置くブロックが多すぎます(" + n + "個。上限は" + limits.maxPlacements() + "個)",
                    Map.of(DATA_COUNT, String.valueOf(n), DATA_LIMIT, String.valueOf(limits.maxPlacements())), List.of()));
        }
        Box b = m.worldBounds();
        long sx = (long) b.maxA() - b.minA() + 1;
        long sy = (long) b.maxB() - b.minB() + 1;
        long sz = (long) b.maxC() - b.minC() + 1;
        if (sx > limits.maxSizeX() || sy > limits.maxSizeY() || sz > limits.maxSizeZ()) {
            String size = sx + "x" + sy + "x" + sz;
            String max = limits.maxSizeX() + "x" + limits.maxSizeY() + "x" + limits.maxSizeZ();
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_SIZE, List.of(SUBJECT_MANIFEST),
                    "施工の範囲が大きすぎます(" + size + "。上限は" + max + ")", Map.of(DATA_COUNT, size, DATA_LIMIT, max),
                    List.of()));
        }
        int outsideHeight = 0;
        IntPos firstOutside = null;
        TreeSet<String> outsideHeightParts = new TreeSet<>();
        int outsideSite = 0;
        IntPos firstOutsideSite = null;
        TreeSet<String> outsideSiteParts = new TreeSet<>();
        Map<String, TreeSet<String>> forbidden = new TreeMap<>();
        Map<String, TreeSet<String>> unknownItems = new TreeMap<>();
        Map<String, int[]> blockedCount = new TreeMap<>();
        Map<String, IntPos> blockedFirst = new TreeMap<>();
        int unloaded = 0;
        IntPos firstUnloaded = null;
        int fluids = 0;
        int leaves = 0;
        int empty = 0;
        List<IntPos> sample = new ArrayList<>();
        for (Placement p : m.placements()) {
            IntPos pos = p.pos();
            if (!b.contains(pos.x(), pos.y(), pos.z())) {
                // the site box is what the claim, the survey and the size limits cover: nothing is placed outside it
                outsideSite++;
                outsideSiteParts.add(p.partNodeId());
                firstOutsideSite = firstOutsideSite == null ? pos : firstOutsideSite;
                continue;
            }
            if (pos.y() < limits.minBuildY() || pos.y() >= limits.maxBuildYExclusive()) {
                // the world cannot hold a block at this height: no world, item or ownership query is done for it
                outsideHeight++;
                outsideHeightParts.add(p.partNodeId());
                firstOutside = firstOutside == null ? pos : firstOutside;
                continue;
            }
            String id = p.block().blockId();
            if (policy.isAlwaysForbidden(id)) {
                forbidden.computeIfAbsent(id, k -> new TreeSet<>()).add(p.partNodeId());
            }
            BlockToItem.cost(p.block()).ifPresent(c -> {
                if (!items.exists(c.itemId())) {
                    unknownItems.computeIfAbsent(c.itemId(), k -> new TreeSet<>()).add(p.partNodeId());
                }
            });
            WorldCell cell = survey.cells().get(pos);
            if (cell == null || !cell.loaded()) {
                unloaded++;
                firstUnloaded = firstUnloaded == null ? pos : firstUnloaded;
                continue;
            }
            ReplaceDecision d = ReplaceRules.decide(p, cell, false, ownPlaced.apply(pos));
            if (d instanceof ReplaceDecision.Refused r) {
                String key = p.partNodeId() + "#" + r.refusal().name().toLowerCase(Locale.ROOT);
                blockedCount.computeIfAbsent(key, k -> new int[1])[0]++;
                blockedFirst.putIfAbsent(key, pos);
            } else if (d instanceof ReplaceDecision.Place place && place.destruction().needsDestructiveConfirm()) {
                switch (place.destruction()) {
                    case FLUID -> fluids++;
                    case LEAVES -> leaves++;
                    default -> empty++;
                }
                if (sample.size() < ReplacementSummary.SAMPLE_LIMIT) {
                    sample.add(pos);
                }
            }
        }
        if (outsideSite > 0) {
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_BOUNDS, partSubjects(outsideSiteParts),
                    "敷地の範囲の外に、置く位置が" + outsideSite + "個あります",
                    Map.of(DATA_COUNT, String.valueOf(outsideSite), DATA_FIRST, text(firstOutsideSite)), List.of()));
        }
        if (outsideHeight > 0) {
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_HEIGHT, partSubjects(outsideHeightParts),
                    "ワールドの建てられる高さの外に、" + outsideHeight + "個のブロックがあります",
                    Map.of(DATA_COUNT, String.valueOf(outsideHeight), DATA_FIRST, text(firstOutside)), List.of()));
        }
        forbidden.forEach((id, nodes) -> issues.add(Issue.of(IssueCode.E_BLOCK_FORBIDDEN, id, new ArrayList<>(nodes),
                id + "は、置いてはいけないブロックです")));
        unknownItems.forEach((item, nodes) -> issues.add(Issue.of(IssueCode.E_MATERIAL_UNKNOWN, item, new ArrayList<>(nodes),
                item + "は、このゲームに無い品物です(材料の対応表に無い)")));
        blockedCount.forEach((key, count) -> {
            String node = key.substring(0, key.indexOf('#'));
            String reason = key.substring(key.indexOf('#') + 1);
            issues.add(Issue.of(IssueCode.E_SITE_BLOCKED, reason, List.of(node),
                    node + "を置く場所に、置き換えられないブロックが" + count[0] + "個あります(最初は " + text(blockedFirst.get(key))
                            + ")", Map.of(DATA_COUNT, String.valueOf(count[0]), DATA_FIRST, text(blockedFirst.get(key))),
                    List.of()));
        });
        if (unloaded > 0) {
            issues.add(Issue.of(IssueCode.E_SITE_BLOCKED, KEY_UNLOADED, List.of(SUBJECT_MANIFEST),
                    "読み込まれていない場所が" + unloaded + "個あります。近づいてから、もう一度送ってください",
                    Map.of(DATA_COUNT, String.valueOf(unloaded), DATA_FIRST, text(firstUnloaded)), List.of()));
        }
        return new SafetyReport(issues, new ReplacementSummary(fluids, leaves, empty, terrain.cut(), terrain.fill(), sample));
    }

    /** The part node ids that broke a range rule: sorted, deduplicated, capped at {@link #MAX_ISSUE_SUBJECTS}. */
    private static List<String> partSubjects(TreeSet<String> parts) {
        return parts.stream().limit(MAX_ISSUE_SUBJECTS).toList();
    }

    static String text(IntPos p) {
        return p.x() + "," + p.y() + "," + p.z();
    }
}
