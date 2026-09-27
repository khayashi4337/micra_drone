package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The templates a plan refers to. Bundled ones are matched by id and hash; player-promoted ones carry their body.
 * Equal when the lists are equal, as it was when this was a record. The lookup by id goes through an index built once
 * when the bundle is made (a bundle is immutable), so one lookup does not cost a scan of the list.
 */
public final class TemplateBundle {
    public static final TemplateBundle EMPTY = new TemplateBundle(List.of());

    private final List<ModuleTemplate> templates;
    private final Map<String, ModuleTemplate> byId;

    public TemplateBundle(List<ModuleTemplate> templates) {
        this.templates = List.copyOf(templates);
        Map<String, ModuleTemplate> index = new HashMap<>();
        for (ModuleTemplate t : this.templates) {
            // the first template of an id is the one found, as a scan of the list would find it
            index.putIfAbsent(t.id(), t);
        }
        this.byId = Collections.unmodifiableMap(index);
    }

    public List<ModuleTemplate> templates() {
        return templates;
    }

    public Optional<ModuleTemplate> find(String id) {
        return Optional.ofNullable(byId.get(id));
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

    @Override
    public boolean equals(Object other) {
        return other instanceof TemplateBundle that && templates.equals(that.templates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(templates);
    }

    @Override
    public String toString() {
        return "TemplateBundle[templates=" + templates + "]";
    }
}
