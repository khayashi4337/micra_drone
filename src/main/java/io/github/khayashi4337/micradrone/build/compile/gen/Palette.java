package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.FixHint;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.MaterialFamilies;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Resolves a material (a role name or a block id) to blocks. The plan's style overrides the registry's default
 * palette. Every id handed out is remembered with the parts that asked for it, for the block policy check.
 */
public final class Palette {
    /** Materials that have both a stairs and a slab form, offered when a material has neither. */
    private static final List<String> STAIRS_CANDIDATES = List.of("minecraft:red_nether_bricks", "minecraft:bricks",
            "minecraft:stone_bricks", "minecraft:oak_planks");
    /** A block id has a namespace ("minecraft:stone"); a role name does not ("wall"). */
    private static final String NAMESPACE_SEPARATOR = ":";
    private static final String LIST_SEPARATOR = ",";
    /** The Issue key of every material problem: the parameter that names the material. */
    private static final String KEY_MATERIAL = "material";
    private static final String DATA_ROLE = "role";
    private static final String DATA_ROLES = "roles";
    private static final String DATA_MATERIAL = "material";
    private static final String HINT_USE_ROLE = "USE_ROLE";
    private static final String HINT_USE_MATERIAL = "USE_MATERIAL";
    private static final String ARG_ROLES = "roles";
    private static final String ARG_IDS = "ids";
    private static final String FORM_STAIRS = "階段";
    private static final String FORM_SLAB = "スラブ";

    private final Map<String, String> roles = new TreeMap<>();
    private final Map<String, Set<String>> usedBy = new TreeMap<>();

    public Palette(Map<String, String> defaults, Map<String, String> planPalette) {
        roles.putAll(defaults);
        roles.putAll(planPalette);
    }

    /** Block id to the ids of the parts it was handed out to. */
    public Map<String, Set<String>> usedBy() {
        return usedBy;
    }

    private String note(String id, PlanNode node) {
        usedBy.computeIfAbsent(id, k -> new TreeSet<>()).add(node.id());
        return id;
    }

    /** The role name, or null when the material is a block id. */
    private static String role(String material) {
        return material.contains(NAMESPACE_SEPARATOR) ? null : material;
    }

    public String full(String material, PlanNode node) {
        if (role(material) == null) {
            return note(material, node);
        }
        String id = roles.get(material);
        if (id == null) {
            throw unknownRole(material, node);
        }
        return note(id, node);
    }

    public String stairs(String material, PlanNode node) {
        String role = role(material);
        if (role != null && roles.containsKey(role + BlockForms.STAIRS_SUFFIX)) {
            return note(roles.get(role + BlockForms.STAIRS_SUFFIX), node);
        }
        String full = full(material, node);
        if (full.endsWith(BlockForms.STAIRS_SUFFIX)) {
            return full; // the role already names a stairs block (e.g. the default "stairs" role)
        }
        MaterialFamilies.Family f = MaterialFamilies.family(full).orElse(null);
        if (f == null || f.stairs() == null) {
            throw noFamily(material, full, FORM_STAIRS, node);
        }
        return note(f.stairs(), node);
    }

    public String slab(String material, PlanNode node) {
        String role = role(material);
        if (role != null && roles.containsKey(role + BlockForms.SLAB_SUFFIX)) {
            return note(roles.get(role + BlockForms.SLAB_SUFFIX), node);
        }
        String full = full(material, node);
        if (full.endsWith(BlockForms.SLAB_SUFFIX)) {
            return full;
        }
        MaterialFamilies.Family f = MaterialFamilies.family(full).orElse(null);
        if (f == null) {
            throw noFamily(material, full, FORM_SLAB, node);
        }
        return note(f.slab(), node);
    }

    private GenAbort unknownRole(String role, PlanNode node) {
        String known = String.join(LIST_SEPARATOR, roles.keySet());
        return new GenAbort(Issue.of(IssueCode.E_PARAM_RANGE, KEY_MATERIAL, List.of(node.id()),
                "パレットに役割「" + role + "」がありません(style(\"" + role + "\", \"minecraft:…\")で決めてください)",
                Map.of(DATA_ROLE, role, DATA_ROLES, known),
                List.of(new FixHint(HINT_USE_ROLE, Map.of(ARG_ROLES, known)))), false);
    }

    private GenAbort noFamily(String material, String full, String form, PlanNode node) {
        String role = role(material);
        String roleAdvice = role == null ? "" : role + BlockForms.STAIRS_SUFFIX + " / " + role + BlockForms.SLAB_SUFFIX + " か、";
        return new GenAbort(Issue.of(IssueCode.E_PARAM_RANGE, KEY_MATERIAL, List.of(node.id()),
                full + "には" + form + "の形がありません(ゲーム標準には無い素材です)。" + form + "の素材を、"
                        + roleAdvice + "族のある素材(例: minecraft:red_nether_bricks)で指定してください",
                Map.of(DATA_MATERIAL, full),
                List.of(new FixHint(HINT_USE_MATERIAL, Map.of(ARG_IDS, String.join(LIST_SEPARATOR, STAIRS_CANDIDATES))))), false);
    }
}
