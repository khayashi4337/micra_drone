package io.github.khayashi4337.micradrone.build.plan;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Measured figures of a verified template. Not part of its hash, so they can be refreshed. */
public record TemplateStats(double rpm, double stressSu, Map<String, Double> perMinByProduct) {
    public TemplateStats {
        perMinByProduct = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNullElse(perMinByProduct, Map.of())));
    }
}
