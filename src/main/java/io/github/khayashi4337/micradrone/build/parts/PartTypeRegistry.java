package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.Hashing;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The list of parts that may be used. Everything (AI schemas, image prompts, construction, checks) speaks in
 * these ids only. {@link #version()} is a hash of the registered content and the default palette; manifests,
 * jobs and approvals record it, and anything built against another version is refused.
 */
public final class PartTypeRegistry {
    // Keys of the tree the version is hashed from.
    private static final String KEY_PARTS = "parts";
    private static final String KEY_PALETTE = "palette";
    /** Levenshtein cost of one insertion, deletion or substitution. */
    private static final int EDIT_COST = 1;
    private static final int NO_COST = 0;

    private final TreeMap<String, PartType> parts;
    private final TreeMap<String, String> defaultPalette;
    private final String version;

    private PartTypeRegistry(TreeMap<String, PartType> parts, TreeMap<String, String> defaultPalette) {
        this.parts = parts;
        this.defaultPalette = defaultPalette;
        List<Map<String, Object>> trees = parts.values().stream().map(PartTypeJson::toTree).toList();
        this.version = Hashing.sha256Hex(CanonicalJson.write(Map.of(KEY_PARTS, trees, KEY_PALETTE, defaultPalette)));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<PartType> find(String id) {
        return Optional.ofNullable(parts.get(id));
    }

    public PartType get(String id) {
        PartType t = parts.get(id);
        if (t == null) {
            throw new IllegalArgumentException("unknown part " + id);
        }
        return t;
    }

    public boolean contains(String id) {
        return parts.containsKey(id);
    }

    public Collection<PartType> all() {
        return Collections.unmodifiableCollection(parts.values());
    }

    public List<PartType> userParts() {
        List<PartType> out = new ArrayList<>();
        for (PartType t : parts.values()) {
            if (t.visibility() == Visibility.USER) {
                out.add(t);
            }
        }
        return out;
    }

    public Map<String, String> defaultPalette() {
        return Collections.unmodifiableMap(defaultPalette);
    }

    public String version() {
        return version;
    }

    /** Ids close to {@code unknownId} (edit distance), nearest first, for "did you mean" hints. */
    public List<String> suggest(String unknownId, int limit) {
        List<Map.Entry<Integer, String>> scored = new ArrayList<>();
        for (String id : parts.keySet()) {
            scored.add(Map.entry(editDistance(unknownId, id), id));
        }
        scored.sort(Map.Entry.<Integer, String>comparingByKey().thenComparing(Map.Entry.comparingByValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            out.add(scored.get(i).getValue());
        }
        return out;
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = a.charAt(i - 1) == b.charAt(j - 1) ? NO_COST : EDIT_COST;
                cur[j] = Math.min(Math.min(cur[j - 1] + EDIT_COST, prev[j] + EDIT_COST), prev[j - 1] + substitution);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    public static final class Builder {
        private final TreeMap<String, PartType> parts = new TreeMap<>();
        private final TreeMap<String, String> palette = new TreeMap<>();

        public Builder register(PartType type) {
            if (parts.putIfAbsent(type.id(), type) != null) {
                throw new IllegalArgumentException("part already registered: " + type.id());
            }
            return this;
        }

        public Builder defaultPalette(Map<String, String> roles) {
            palette.putAll(roles);
            return this;
        }

        public PartTypeRegistry build() {
            return new PartTypeRegistry(new TreeMap<>(parts), new TreeMap<>(palette));
        }
    }
}
