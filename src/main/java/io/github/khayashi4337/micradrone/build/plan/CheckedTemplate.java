package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanIds;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.ParamValidator;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A module template as one {@link PlanExpander#expand} call knows it: everything that depends on the template alone and
 * not on the instance, worked out once. That is whether the ids of its parts and connections are well formed and
 * unique, what the registry and the parameter validator say about each part (with the parameters typed), and its hash.
 * A plan holds up to about 100,000 module instances, so each of these is paid once per distinct template and an
 * instance only adds its own prefix and placement. The parts and the hash are worked out when first needed, as the
 * expander needed them before: a part that no instance gets as far as, and the hash of a template that no instance
 * expands, are never worked out.
 */
final class CheckedTemplate {
    /** The Issue key that tells the id problems of a template apart from other issues on the same instance part. */
    private static final String KEY_TEMPLATE = "template";
    // What a template's ids name, for the start of the messages about them.
    private static final String PART_LABEL = "部品";
    private static final String CONNECTION_LABEL = "接続";

    /** One bad id of the template: reported by each instance as {@code <instance>/<id>}, with the same words. */
    private record IdProblem(IssueCode code, String id, String message) {
    }

    /**
     * What the registry and the validator say about one template part. {@code type} is null when the registry does not
     * have the part, and {@code typed} is null when its parameters were refused; otherwise {@code typed} holds them typed.
     */
    record PartCheck(PartType type, Map<String, ParamValue> typed) {
        boolean known() {
            return type != null;
        }

        boolean valid() {
            return typed != null;
        }
    }

    private final ModuleTemplate template;
    private final PartTypeRegistry registry;
    private final TemplateWork work;
    private final List<IdProblem> nodeIdProblems;
    private final List<IdProblem> connectionIdProblems;
    /** The verdict on each part, null until an instance reaches the part. */
    private final PartCheck[] checks;
    private String hash;

    CheckedTemplate(ModuleTemplate template, PartTypeRegistry registry, TemplateWork work) {
        this.template = template;
        this.registry = registry;
        this.work = work;
        work.templateChecked();
        this.checks = new PartCheck[template.nodes().size()];
        this.nodeIdProblems = idProblems(template, template.nodes().stream().map(PlanNode::id).toList(), PART_LABEL);
        this.connectionIdProblems = idProblems(template,
                template.internal().stream().map(Connection::id).toList(), CONNECTION_LABEL);
    }

    ModuleTemplate template() {
        return template;
    }

    /** Whether the ids of the parts and of the connections are all well formed and unique. */
    boolean idsAreValid() {
        return nodeIdProblems.isEmpty() && connectionIdProblems.isEmpty();
    }

    /**
     * Adds the id issues of the template under an instance's prefix: the parts' first, then the connections'. Each bad id
     * is reported once, as E-ID-INVALID or E-ID-DUPLICATE on {@code <instance>/<id>}. The template may come from a
     * client, and with a repeated id every lookup by id (parent, via, ports) would pick one of the parts arbitrarily;
     * a "/" in an id could also name another instance's part.
     */
    void reportIdProblems(String prefix, List<Issue> issues) {
        report(nodeIdProblems, prefix, issues);
        report(connectionIdProblems, prefix, issues);
    }

    private static void report(List<IdProblem> problems, String prefix, List<Issue> issues) {
        for (IdProblem p : problems) {
            issues.add(Issue.of(p.code(), KEY_TEMPLATE, List.of(prefix + p.id()), p.message()));
        }
    }

    /** How many parts the template has. */
    int partCount() {
        return checks.length;
    }

    /**
     * What the registry and the validator say about the part at {@code index} of the template, worked out the first time
     * an instance gets as far as that part. An instance stops at the first part that is refused, so the parts after a
     * part that the registry or the validator refuses are never worked out.
     */
    PartCheck part(int index) {
        PartCheck check = checks[index];
        if (check == null) {
            check = checkPart(template.nodes().get(index));
            checks[index] = check;
        }
        return check;
    }

    /** The template's hash, built on first use. */
    String hash() {
        if (hash == null) {
            work.templateHashed();
            hash = template.hash();
        }
        return hash;
    }

    private PartCheck checkPart(PlanNode part) {
        work.partChecked();
        PartType type = registry.find(part.type()).orElse(null);
        Map<String, ParamValue> typed = null;
        if (type != null) {
            ParamValidator.Result checked = ParamValidator.validate(part.id(), type, part.params());
            typed = checked.issues().isEmpty() ? checked.typed() : null;
        }
        return new PartCheck(type, typed);
    }

    private static List<IdProblem> idProblems(ModuleTemplate template, List<String> ids, String label) {
        List<IdProblem> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (String id : ids) {
            if (!PlanIds.isValid(id)) {
                if (reported.add(id)) {
                    problems.add(new IdProblem(IssueCode.E_ID_INVALID, id,
                            "テンプレート" + template.id() + "の" + label + "の" + PlanIds.invalidMessage(id)));
                }
            } else if (!seen.add(id)) {
                if (reported.add(id)) {
                    problems.add(new IdProblem(IssueCode.E_ID_DUPLICATE, id,
                            "テンプレート" + template.id() + "の" + label + "のIDが重複しています: " + id));
                }
            }
        }
        return problems;
    }
}
