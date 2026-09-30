package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Hand-built manifests for the runtime's tests; indexes, phases, bill of materials and hash are filled in here. */
public final class TestManifests {
    public static final String DIM = "minecraft:overworld";
    public static final String REGISTRY_VERSION = "test-registry";
    public static final BuildFrame FRAME = new BuildFrame(new IntPos(0, 0, 0), Facing.NORTH);

    private TestManifests() {
    }

    public static Placement put(int x, int y, int z, String blockId, String... props) {
        return put(new IntPos(x, y, z), BlockSpec.of(blockId, props), "wall", BuildPhase.STRUCTURE, VerifyMode.EXACT);
    }

    public static Placement put(IntPos pos, BlockSpec block, String node, BuildPhase phase, VerifyMode verify) {
        return new Placement(0, pos, block, Map.of(), node, phase, PlacerId.SIMPLE, verify, ReplacePolicy.REPLACEABLE, null);
    }

    /** The placements in the given order, re-indexed; the hash is computed the way the compiler does. */
    public static PlacementManifest of(Box worldBounds, List<Placement> placements) {
        List<Placement> indexed = new ArrayList<>();
        for (int i = 0; i < placements.size(); i++) {
            Placement p = placements.get(i);
            indexed.add(new Placement(i, p.pos(), p.block(), p.blockEntityConfig(), p.partNodeId(), p.phase(), p.placer(),
                    p.verify(), p.replaces(), p.assemblyGroup()));
        }
        Map<String, Integer> bom = BomCalculator.bom(indexed);
        String hash = ManifestJson.computeHash(DIM, REGISTRY_VERSION, worldBounds, indexed, List.of(), bom);
        return new PlacementManifest(PlacementManifest.MANIFEST_VERSION, "plan", 1, REGISTRY_VERSION, DIM, FRAME, worldBounds,
                indexed, List.of(), bom, PhaseRanges.of(indexed), hash);
    }

    /** The P3 golden hut, compiled the golden way (238 placements). */
    public static PlacementManifest hut() {
        try {
            return GoldenHutTest.compileHut(GoldenHutTest.hut()).manifest();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A 3x3 hut: cobblestone foundation at y=63, a ring of planks at y=64 and y=65, an empty interior column. */
    public static PlacementManifest smallHut() {
        List<Placement> out = new ArrayList<>();
        for (int z = 0; z <= 2; z++) {
            for (int x = 0; x <= 2; x++) {
                out.add(put(new IntPos(x, 63, z), BlockSpec.of("minecraft:cobblestone"), "found", BuildPhase.STRUCTURE,
                        VerifyMode.EXACT));
            }
        }
        for (int y = 64; y <= 65; y++) {
            for (int z = 0; z <= 2; z++) {
                for (int x = 0; x <= 2; x++) {
                    if (x != 1 || z != 1) {
                        out.add(put(new IntPos(x, y, z), BlockSpec.of("minecraft:oak_planks"), "wall", BuildPhase.STRUCTURE,
                                VerifyMode.EXACT));
                    }
                }
            }
        }
        return of(new Box(-2, 55, -2, 4, 70, 4), out);
    }
}
