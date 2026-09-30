package io.github.khayashi4337.micradrone.build.compile;

/**
 * How much the terraforming of a manifest touches: {@code cut} counts the unique positions the SITE_PREP phase cuts
 * to air, {@code fill} the cells filled with the fill block (04 F-5).
 */
public record TerrainSummary(int cut, int fill) {
    public TerrainSummary {
        if (cut < 0 || fill < 0) {
            throw new IllegalArgumentException("terrain counts must not be negative: cut=" + cut + ", fill=" + fill);
        }
    }

    public boolean any() {
        return cut > 0 || fill > 0;
    }
}
