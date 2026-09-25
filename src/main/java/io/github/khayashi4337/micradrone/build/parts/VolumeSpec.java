package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The space a part occupies: fixed boxes plus optional parameter-dependent sizes. Building parts (micra:*)
 * are {@link #GENERATED}: the generator decides, so the generated cells are the occupied volume.
 */
public record VolumeSpec(List<Box> boxes, Map<String, String> sizeFromParams) {
    private static final String KEY_MODE = "mode";
    private static final String MODE_GENERATED = "generated";

    public static final VolumeSpec GENERATED = new VolumeSpec(List.of(), Map.of(KEY_MODE, MODE_GENERATED));

    public VolumeSpec {
        boxes = List.copyOf(Objects.requireNonNullElse(boxes, List.of()));
        sizeFromParams = SortedCopies.map(sizeFromParams);
    }
}
