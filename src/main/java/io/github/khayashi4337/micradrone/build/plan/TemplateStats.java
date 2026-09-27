package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.SortedCopies;
import java.util.Map;

/** Measured figures of a verified template. Not part of its hash, so they can be refreshed. */
public record TemplateStats(double rpm, double stressSu, Map<String, Double> perMinByProduct) {
    public TemplateStats {
        perMinByProduct = SortedCopies.map(perMinByProduct);
    }
}
