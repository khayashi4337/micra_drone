package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Terraforming (SITE_PREP cut and fill, 04 F-5) from the pinned survey only: placements at or below a column's surface
 * may remove natural ground (REPLACEABLE becomes TERRAFORM), ground in the building's footprint columns is cut to air
 * from the column's lowest solid placement up to the column's surface (an interior column holds only its floor, yet the
 * hill must go; ground left above the roof would hang there, and sand or gravel would fall into the house), and the gap
 * under the lowest solid placement is filled with dirt. Cuts run top-down and fills bottom-up, so a falling block (sand,
 * gravel) never drops into a cell that was just cleared. Deterministic: the same manifest and survey always give the
 * same result, so the approval hash holds while the world changes (completion condition 16).
 */
public final class TerrainPrep {
    public static final String FILL_BLOCK = "minecraft:dirt";
    public static final String SITE_PREP_NODE = "site-prep";
    private static final String KEY_SURVEY = "survey";
    private static final String KEY_TERRAIN = "terrain";
    private static final String SUBJECT = "site";
    private static final String DATA_COUNT = "count";
    private static final int NO_BASE = Integer.MAX_VALUE;
    private static final Comparator<IntPos> BOTTOM_UP = Comparator.comparingInt(IntPos::y).thenComparingInt(IntPos::z)
            .thenComparingInt(IntPos::x);
    /** Cutting from the top keeps every cell above a cut already empty, so nothing falls into it. */
    private static final Comparator<IntPos> TOP_DOWN = Comparator.<IntPos>comparingInt(p -> -p.y()).thenComparingInt(IntPos::z)
            .thenComparingInt(IntPos::x);

    private TerrainPrep() {
    }

    public static TerrainResult apply(PlacementManifest m, SiteSurvey survey) {
        if (!m.dimension().equals(survey.dimension())) {
            throw new IllegalArgumentException("survey of " + survey.dimension() + " for a manifest of " + m.dimension());
        }
        List<Issue> issues = new ArrayList<>();
        Map<Long, int[]> columns = new HashMap<>();
        Set<IntPos> occupied = new HashSet<>();
        int uncovered = 0;
        for (Placement p : m.placements()) {
            occupied.add(p.pos());
            if (!survey.covers(p.pos().x(), p.pos().z())) {
                uncovered++;
                continue;
            }
            int[] base = columns.computeIfAbsent(key(p.pos().x(), p.pos().z()), k -> new int[]{NO_BASE});
            if (!p.block().isAir()) {
                base[0] = Math.min(base[0], p.pos().y());
            }
        }
        if (uncovered > 0) {
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_SURVEY, List.of(SUBJECT),
                    "地形調査の範囲の外に、置く位置が" + uncovered + "個あります", Map.of(DATA_COUNT, String.valueOf(uncovered)),
                    List.of()));
            return new TerrainResult(null, new TerrainSummary(0, 0), issues);
        }
        List<Placement> rest = new ArrayList<>(m.placements().size());
        int sunk = 0;
        for (Placement p : m.placements()) {
            int px = p.pos().x();
            int pz = p.pos().z();
            boolean underground = survey.hasGround(px, pz) && p.pos().y() <= survey.surfaceAt(px, pz);
            if (underground && p.replaces() instanceof ReplacePolicy.Replaceable) {
                rest.add(withReplaces(p, ReplacePolicy.TERRAFORM));
                sunk++;
            } else {
                rest.add(p);
            }
        }
        List<IntPos> cut = new ArrayList<>();
        List<IntPos> fill = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : columns.entrySet()) {
            int base = e.getValue()[0];
            if (base == NO_BASE) {
                continue; // only air is placed here: nothing stands on this column
            }
            int x = (int) (e.getKey() >> 32);
            int z = e.getKey().intValue();
            if (!survey.hasGround(x, z)) {
                continue; // a column without ground (air survey) is neither cut nor filled
            }
            int surface = survey.surfaceAt(x, z);
            for (int y = base; y <= surface; y++) {
                IntPos q = new IntPos(x, y, z);
                if (!occupied.contains(q)) {
                    cut.add(q);
                }
            }
            for (int y = surface + 1; y < base; y++) {
                fill.add(new IntPos(x, y, z));
            }
        }
        Box bounds = m.worldBounds();
        long outside = java.util.stream.Stream.concat(cut.stream(), fill.stream())
                .filter(q -> !bounds.contains(q.x(), q.y(), q.z())).count();
        if (outside > 0) {
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_TERRAIN, List.of(SUBJECT),
                    "地面を切る・土を盛る位置が、敷地の範囲をはみ出します(" + outside + "マス)。敷地を広げてください",
                    Map.of(DATA_COUNT, String.valueOf(outside)), List.of()));
            return new TerrainResult(null, new TerrainSummary(0, 0), issues);
        }
        if (sunk == 0 && cut.isEmpty() && fill.isEmpty()) {
            return new TerrainResult(m, new TerrainSummary(0, 0), issues);
        }
        List<Placement> prep = new ArrayList<>();
        cut.sort(TOP_DOWN);
        fill.sort(BOTTOM_UP);
        List<IntPos> prepCells = new ArrayList<>(cut);
        prepCells.addAll(fill);
        Set<IntPos> fillSet = new HashSet<>(fill);
        for (IntPos q : prepCells) {
            boolean isFill = fillSet.contains(q);
            prep.add(new Placement(0, q, isFill ? BlockSpec.of(FILL_BLOCK) : BlockSpec.AIR, Map.of(), SITE_PREP_NODE,
                    BuildPhase.SITE_PREP, PlacerId.SIMPLE, VerifyMode.EXACT,
                    isFill ? ReplacePolicy.REPLACEABLE : ReplacePolicy.TERRAFORM, null));
        }
        List<Placement> all = new ArrayList<>(prep.size() + rest.size());
        all.addAll(prep);
        all.addAll(rest);
        List<Placement> indexed = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            indexed.add(withIndex(all.get(i), i));
        }
        int shift = prep.size();
        List<AssemblyStep> assemblies = new ArrayList<>();
        for (AssemblyStep a : m.assemblies()) {
            assemblies.add(new AssemblyStep(a.groupId(), a.kind(), a.trigger(),
                    a.memberIndexes().stream().map(i -> i + shift).toList(), a.expect()));
        }
        Map<String, Integer> bom = BomCalculator.bom(indexed);
        String hash = ManifestJson.computeHash(m.dimension(), m.registryVersion(), bounds, indexed, assemblies, bom);
        PlacementManifest out = new PlacementManifest(m.manifestVersion(), m.planId(), m.planRevision(), m.registryVersion(),
                m.dimension(), m.frame(), bounds, indexed, assemblies, bom, PhaseRanges.of(indexed), hash);
        return new TerrainResult(out, new TerrainSummary(sunk + cut.size(), fill.size()), issues);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static Placement withReplaces(Placement p, ReplacePolicy r) {
        return new Placement(p.index(), p.pos(), p.block(), p.blockEntityConfig(), p.partNodeId(), p.phase(), p.placer(),
                p.verify(), r, p.assemblyGroup());
    }

    private static Placement withIndex(Placement p, int i) {
        return new Placement(i, p.pos(), p.block(), p.blockEntityConfig(), p.partNodeId(), p.phase(), p.placer(), p.verify(),
                p.replaces(), p.assemblyGroup());
    }
}
