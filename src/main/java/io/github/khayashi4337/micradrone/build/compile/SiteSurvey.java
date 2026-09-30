package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.Hashing;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The server-issued terrain survey a compile is pinned to (design 01, sections 7 and 11; 04 F-3): per column of the
 * world box, the highest natural ground block (not plants, leaves, logs or fluid), the block there, and whether water or
 * a tree stood above it. Arrays are indexed [x - minX][z - minZ]. The digest covers the whole content, so a pinned
 * survey reproduces the same terraforming, and so the same manifest hash, however the world changes afterwards.
 */
public record SiteSurvey(String dimension, Box worldBounds, int[][] surfaceY, String[][] surfaceBlock, boolean[][] water,
                         boolean[][] tree, String digest) {
    private static final String AIR = "minecraft:air";
    /**
     * Upper bound on the column count of one survey (04 F-5): the largest build region is 128x96x128, so the
     * columns of a 128x128 site with headroom; a bigger box is refused before any array is sized.
     */
    public static final int MAX_SURVEY_COLUMNS = 1 << 20;
    /** Marker for the factory paths below: the canonical constructor computes the digest from the copies itself. */
    private static final String COMPUTE_DIGEST = new String("compute");

    public SiteSurvey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(worldBounds, "worldBounds");
        int[] size = checkedColumns(worldBounds);
        int sx = size[0];
        int sz = size[1];
        surfaceY = copy(surfaceY, sx, sz);
        surfaceBlock = copy(surfaceBlock, sx, sz);
        water = copy(water, sx, sz);
        tree = copy(tree, sx, sz);
        // a caller-passed digest is verified against the defensive copies, not trusted
        String computed = digestOf(dimension, worldBounds, surfaceY, surfaceBlock, water, tree);
        if (digest == COMPUTE_DIGEST) {
            digest = computed;
        } else {
            Objects.requireNonNull(digest, "digest");
            if (!digest.equals(computed)) {
                throw new IllegalArgumentException("the survey digest does not match the arrays");
            }
        }
    }

    public static SiteSurvey of(String dimension, Box worldBounds, int[][] surfaceY, String[][] surfaceBlock, boolean[][] water,
                                boolean[][] tree) {
        // the canonical constructor shape-checks and copies the arrays once, then computes the digest from the copies
        return new SiteSurvey(dimension, worldBounds, surfaceY, surfaceBlock, water, tree, COMPUTE_DIGEST);
    }

    public static SiteSurvey flat(String dimension, Box worldBounds, int surface, String block) {
        int[] size = checkedColumns(worldBounds);
        int sx = size[0];
        int sz = size[1];
        int[][] ys = new int[sx][sz];
        String[][] blocks = new String[sx][sz];
        for (int i = 0; i < sx; i++) {
            Arrays.fill(ys[i], surface);
            Arrays.fill(blocks[i], block);
        }
        return of(dimension, worldBounds, ys, blocks, new boolean[sx][sz], new boolean[sx][sz]);
    }

    /** A survey with no ground inside the box: terraforming leaves any manifest unchanged. */
    public static SiteSurvey air(String dimension, Box worldBounds) {
        return flat(dimension, worldBounds, worldBounds.minB() - 1, AIR);
    }

    public SiteSurvey withColumn(int x, int z, int surface, String block) {
        requireCovered(x, z);
        int[][] ys = surfaceY();
        String[][] blocks = surfaceBlock();
        ys[x - worldBounds.minA()][z - worldBounds.minC()] = surface;
        blocks[x - worldBounds.minA()][z - worldBounds.minC()] = block;
        return of(dimension, worldBounds, ys, blocks, water(), tree());
    }

    public boolean covers(int x, int z) {
        return x >= worldBounds.minA() && x <= worldBounds.maxA() && z >= worldBounds.minC() && z <= worldBounds.maxC();
    }

    public int surfaceAt(int x, int z) {
        requireCovered(x, z);
        return surfaceY[x - worldBounds.minA()][z - worldBounds.minC()];
    }

    /** False when the column holds no natural ground inside the box (the surveyor wrote air): nothing to cut or fill. */
    public boolean hasGround(int x, int z) {
        requireCovered(x, z);
        return !AIR.equals(surfaceBlock[x - worldBounds.minA()][z - worldBounds.minC()]);
    }

    /** Column-addressing entry points share this check: an outside column is the caller's error, not a bad index. */
    private void requireCovered(int x, int z) {
        if (!covers(x, z)) {
            throw new IllegalArgumentException("column " + x + "," + z + " is outside the survey " + worldBounds);
        }
    }

    public SurveyRef ref(long cachedUntilTick) {
        return new SurveyRef(digest, cachedUntilTick);
    }

    @Override
    public int[][] surfaceY() {
        return copy(surfaceY, surfaceY.length, surfaceY.length == 0 ? 0 : surfaceY[0].length);
    }

    @Override
    public String[][] surfaceBlock() {
        return copy(surfaceBlock, surfaceBlock.length, surfaceBlock.length == 0 ? 0 : surfaceBlock[0].length);
    }

    @Override
    public boolean[][] water() {
        return copy(water, water.length, water.length == 0 ? 0 : water[0].length);
    }

    @Override
    public boolean[][] tree() {
        return copy(tree, tree.length, tree.length == 0 ? 0 : tree[0].length);
    }

    /** Column dimensions of the box as {sx, sz}, counted in long so an extreme box is refused before arrays are sized. */
    private static int[] checkedColumns(Box worldBounds) {
        long sx = (long) worldBounds.maxA() - worldBounds.minA() + 1;
        long sz = (long) worldBounds.maxC() - worldBounds.minC() + 1;
        if (sx * sz > MAX_SURVEY_COLUMNS) {
            throw new IllegalArgumentException("survey of " + sx * sz + " columns exceeds the limit "
                    + MAX_SURVEY_COLUMNS + ": " + worldBounds);
        }
        return new int[]{(int) sx, (int) sz};
    }

    private static String digestOf(String dimension, Box bounds, int[][] ys, String[][] blocks, boolean[][] water,
                                   boolean[][] tree) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("dimension", dimension);
        t.put("bounds", PlanJson.boxTree(bounds));
        List<Object> cols = new ArrayList<>();
        for (int i = 0; i < ys.length; i++) {
            for (int j = 0; j < ys[i].length; j++) {
                cols.add(List.of((long) ys[i][j], blocks[i][j], water[i][j], tree[i][j]));
            }
        }
        t.put("columns", cols);
        return Hashing.sha256Hex(CanonicalJson.write(t));
    }

    private static int[][] copy(int[][] a, int sx, int sz) {
        checkShape(a, sx, sz);
        int[][] out = new int[sx][];
        for (int i = 0; i < sx; i++) {
            out[i] = a[i].clone();
        }
        return out;
    }

    private static String[][] copy(String[][] a, int sx, int sz) {
        checkShape(a, sx, sz);
        String[][] out = new String[sx][];
        for (int i = 0; i < sx; i++) {
            out[i] = a[i].clone();
            for (String s : out[i]) {
                Objects.requireNonNull(s, "surface block");
            }
        }
        return out;
    }

    private static boolean[][] copy(boolean[][] a, int sx, int sz) {
        checkShape(a, sx, sz);
        boolean[][] out = new boolean[sx][];
        for (int i = 0; i < sx; i++) {
            out[i] = a[i].clone();
        }
        return out;
    }

    /** Every 2-D survey array is an Object[] of primitive or String rows; all must be sx by sz. */
    private static void checkShape(Object[] rows, int sx, int sz) {
        Objects.requireNonNull(rows, "survey array");
        if (rows.length != sx) {
            throw new IllegalArgumentException("survey arrays must have " + sx + " rows, got " + rows.length);
        }
        for (Object row : rows) {
            int n = Array.getLength(Objects.requireNonNull(row, "survey row"));
            if (n != sz) {
                throw new IllegalArgumentException("survey rows must have " + sz + " columns, got " + n);
            }
        }
    }
}
