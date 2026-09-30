package io.github.khayashi4337.micradrone.build.plan;

/**
 * How much per-template work one {@link PlanExpander#expand} call did. The work must be done once per distinct
 * template, never once per module instance, so the counters stay the same however many instances there are.
 * Package-private: the tests read it to see that nothing is repeated.
 */
final class TemplateWork {
    private int templatesChecked;
    private int partsChecked;
    private int templatesHashed;

    /** Templates whose ids were checked: one per distinct template that an instance of the plan used. */
    int templatesChecked() {
        return templatesChecked;
    }

    /** Template parts run through the registry and the parameter validator. */
    int partsChecked() {
        return partsChecked;
    }

    /** Templates whose canonical JSON was built and hashed. */
    int templatesHashed() {
        return templatesHashed;
    }

    void templateChecked() {
        templatesChecked++;
    }

    void partChecked() {
        partsChecked++;
    }

    void templateHashed() {
        templatesHashed++;
    }
}
