package io.github.khayashi4337.micradrone.build.ai;

import io.github.khayashi4337.micradrone.chat.CodeBlockParser;
import io.github.khayashi4337.micradrone.chat.CodeBlockParser.LocatedBlock;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The optional materials directive an AI reply may carry beside the plan frame (Task 27b): one
 * fenced {@code ```materials} block holding
 * {@code {"materials": {"inventory": true|false, "exclude": ["item:id"], "include": ["item:id"]}}}.
 * Any field may be left out; {@code include} takes an id back out of this same directive's
 * {@code exclude} (and, on the server, out of the claim's saved exclusion list).
 *
 * <p>The check is all-or-nothing on purpose: one bad field - an unknown key, a wrongly typed
 * value, an item id the catalog does not know, a cap overrun - discards the WHOLE directive, so a
 * half-understood "don't use this" can never change half of what it said. {@code knownItem} is the
 * item catalog, passed as a predicate because {@code build.*} may not import
 * {@code construction.core.ItemCatalog} (BuildPurityTest).
 */
public final class MaterialsDirective {
    /** Largest accepted frame body, in chars - far smaller than a plan, it is one small object. */
    public static final int MAX_DIRECTIVE_CHARS = 4_000;
    /** Mirrors {@code SupplySettings.MAX_EXCLUDED_ITEMS}; the wire caps each list at this count. */
    public static final int MAX_ITEMS = 32;
    /** Longest accepted item id; the wire's string codec caps at the same length. */
    public static final int MAX_ITEM_ID_CHARS = 256;
    /** The fence label of the materials frame - deliberately not "json" so the plan stays separate. */
    static final String FRAME_LANGUAGE = "materials";

    private static final String KEY_MATERIALS = "materials";
    private static final String KEY_INVENTORY = "inventory";
    private static final String KEY_EXCLUDE = "exclude";
    private static final String KEY_INCLUDE = "include";
    private static final Set<String> KNOWN_FIELDS = Set.of(KEY_INVENTORY, KEY_EXCLUDE, KEY_INCLUDE);

    private MaterialsDirective() {
    }

    /** One parsed directive; a null {@code inventory} leaves the claim's switch alone. */
    public record Directive(Boolean inventory, List<String> exclude, List<String> include) {
        public Directive {
            exclude = List.copyOf(exclude);
            include = List.copyOf(include);
        }

        /** True when nothing would change - an empty directive is valid and is never sent. */
        public boolean isEmpty() {
            return inventory == null && exclude.isEmpty() && include.isEmpty();
        }
    }

    /**
     * The frame's verdict: {@code found} says a {@code materials} block was there at all;
     * {@code directive} is null when it was found but invalid - found-and-invalid is how the flow
     * tells "nothing to do" from "ask again".
     */
    public record Extracted(boolean found, Directive directive) {
    }

    private static final Extracted NONE = new Extracted(false, null);
    private static final Extracted INVALID = new Extracted(true, null);

    public static Extracted extract(String reply, Predicate<String> knownItem) {
        Objects.requireNonNull(reply, "reply");
        Objects.requireNonNull(knownItem, "knownItem");
        List<LocatedBlock> frames = new ArrayList<>();
        for (LocatedBlock b : CodeBlockParser.parseLocated(reply)) {
            if (b.language().equalsIgnoreCase(FRAME_LANGUAGE)) {
                frames.add(b);
            }
        }
        if (frames.isEmpty()) {
            return NONE;
        }
        if (frames.size() > 1) {
            return INVALID; // two contradictory frames: guessing which wins is not safe
        }
        String body = frames.get(0).code().strip();
        if (body.length() > MAX_DIRECTIVE_CHARS) {
            return INVALID;
        }
        Object tree;
        try {
            tree = MiniJson.parse(body);
        } catch (RuntimeException malformed) {
            return INVALID;
        }
        if (!(tree instanceof Map<?, ?> top) || !top.keySet().equals(Set.of(KEY_MATERIALS))) {
            return INVALID;
        }
        if (!(top.get(KEY_MATERIALS) instanceof Map<?, ?> m)) {
            return INVALID;
        }
        for (Object key : m.keySet()) {
            if (!(key instanceof String name) || !KNOWN_FIELDS.contains(name)) {
                return INVALID;
            }
        }
        Boolean inventory = null;
        if (m.containsKey(KEY_INVENTORY)) {
            if (!(m.get(KEY_INVENTORY) instanceof Boolean b)) {
                return INVALID;
            }
            inventory = b;
        }
        List<String> exclude = itemList(m.get(KEY_EXCLUDE), knownItem);
        List<String> include = itemList(m.get(KEY_INCLUDE), knownItem);
        if (exclude == null || include == null) {
            return INVALID;
        }
        // an id the same directive re-includes is not an exclusion at all
        exclude = exclude.stream().filter(id -> !include.contains(id)).toList();
        return new Extracted(true, new Directive(inventory, exclude, include));
    }

    /** The field read as a bounded id list; null = missing (empty), a bad entry fails everything. */
    private static List<String> itemList(Object value, Predicate<String> knownItem) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw) || raw.size() > MAX_ITEMS) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>(raw.size());
        for (Object entry : raw) {
            if (!(entry instanceof String id) || id.length() > MAX_ITEM_ID_CHARS
                    || !knownItem.test(id)) {
                return null;
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }
}
