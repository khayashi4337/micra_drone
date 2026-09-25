package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The templates a plan refers to. Bundled ones are matched by id and hash; player-promoted ones carry their body. */
public record TemplateBundle(List<ModuleTemplate> templates) {
    public static final TemplateBundle EMPTY = new TemplateBundle(List.of());

    public TemplateBundle {
        templates = List.copyOf(templates);
    }

    public Optional<ModuleTemplate> find(String id) {
        for (ModuleTemplate t : templates) {
            if (t.id().equals(id)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** Refuses templates whose id is unknown to the server or whose hash differs from the server's own copy. */
    public List<Issue> verifyAgainst(Map<String, String> knownHashById) {
        List<Issue> issues = new ArrayList<>();
        for (ModuleTemplate t : templates) {
            String known = knownHashById.get(t.id());
            if (known == null) {
                issues.add(Issue.of(IssueCode.E_TEMPLATE_UNVERIFIED, List.of(t.id()),
                        "テンプレート" + t.id() + "は、サーバーの手持ちにありません"));
            } else if (!known.equals(t.hash())) {
                issues.add(Issue.of(IssueCode.E_TEMPLATE_UNVERIFIED, List.of(t.id()),
                        "テンプレート" + t.id() + "の内容が、サーバーの手持ちと違います"));
            }
        }
        return issues;
    }
}
