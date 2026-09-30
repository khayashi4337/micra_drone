package io.github.khayashi4337.micradrone.build.compile;

/**
 * How much the terraforming of a manifest touches: {@code cut} counts the sunk placements (REPLACEABLE turned
 * TERRAFORM) plus the SITE_PREP cells cut to air, {@code fill} the cells filled with the fill block (04 F-5).
 */
public record TerrainSummary(int cut, int fill) {
    public TerrainSummary {
        if (cut < 0 || fill < 0) {
            throw new IllegalArgumentException("terrain counts must not be negative: cut=" + cut + ", fill=" + fill);
        }
    }

    public boolean any() {
        return cut + fill > 0;
    }
}
