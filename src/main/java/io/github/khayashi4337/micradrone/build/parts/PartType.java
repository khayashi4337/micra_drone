package io.github.khayashi4337.micradrone.build.parts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** One entry of the part registry (design doc 01, section 3). */
public record PartType(String id, PartCategory category, Visibility visibility, String displayNameKey,
                       String visualDescription, List<ParamSpec> params, List<PortSpec> ports, VolumeSpec volume,
                       VersionRange requires, PlacerId placer, VerifyMode verify, Set<String> volatileProps,
                       EffectSpec effect, AssemblySpec assembly, ModelRef kineticModel, BuildPhase phase) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+:[a-z0-9_]+");
    private static final String NO_DESCRIPTION = "";

    public PartType {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("part id must look like namespace:name but was " + id);
        }
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(visibility, "visibility");
        if (visibility == Visibility.USER && (displayNameKey == null || displayNameKey.isBlank())) {
            throw new IllegalArgumentException("USER part " + id + " needs a displayNameKey");
        }
        visualDescription = Objects.requireNonNullElse(visualDescription, NO_DESCRIPTION);
        params = List.copyOf(Objects.requireNonNullElse(params, List.of()));
        ports = List.copyOf(Objects.requireNonNullElse(ports, List.of()));
        Set<String> seen = new HashSet<>();
        for (ParamSpec p : params) {
            if (!seen.add(p.name())) {
                throw new IllegalArgumentException("duplicate parameter " + p.name() + " in " + id);
            }
        }
        seen.clear();
        for (PortSpec p : ports) {
            if (!seen.add(p.name())) {
                throw new IllegalArgumentException("duplicate port " + p.name() + " in " + id);
            }
        }
        volume = Objects.requireNonNullElse(volume, VolumeSpec.GENERATED);
        requires = Objects.requireNonNullElse(requires, VersionRange.ALWAYS);
        placer = Objects.requireNonNullElse(placer, PlacerId.SIMPLE);
        verify = Objects.requireNonNullElse(verify, VerifyMode.EXACT);
        volatileProps = SortedCopies.set(volatileProps);
        effect = Objects.requireNonNullElse(effect, EffectSpec.NONE);
        phase = Objects.requireNonNullElse(phase, BuildPhase.STRUCTURE);
    }

    public Optional<ParamSpec> param(String name) {
        for (ParamSpec p : params) {
            if (p.name().equals(name)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public Optional<PortSpec> port(String name) {
        for (PortSpec p : ports) {
            if (p.name().equals(name)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public static Builder builder(String id, PartCategory category) {
        return new Builder(id, category);
    }

    public static final class Builder {
        private final String id;
        private final PartCategory category;
        private Visibility visibility = Visibility.USER;
        private String displayNameKey;
        private String visualDescription = NO_DESCRIPTION;
        private final List<ParamSpec> params = new ArrayList<>();
        private final List<PortSpec> ports = new ArrayList<>();
        private VolumeSpec volume = VolumeSpec.GENERATED;
        private VersionRange requires = VersionRange.ALWAYS;
        private PlacerId placer = PlacerId.SIMPLE;
        private VerifyMode verify = VerifyMode.EXACT;
        private Set<String> volatileProps = Set.of();
        private EffectSpec effect = EffectSpec.NONE;
        private AssemblySpec assembly;
        private ModelRef kineticModel;
        private BuildPhase phase = BuildPhase.STRUCTURE;

        private Builder(String id, PartCategory category) {
            this.id = id;
            this.category = category;
        }

        public Builder visibility(Visibility v) {
            this.visibility = v;
            return this;
        }

        public Builder displayNameKey(String key) {
            this.displayNameKey = key;
            return this;
        }

        public Builder visualDescription(String text) {
            this.visualDescription = text;
            return this;
        }

        public Builder params(ParamSpec... specs) {
            params.addAll(List.of(specs));
            return this;
        }

        public Builder ports(PortSpec... specs) {
            ports.addAll(List.of(specs));
            return this;
        }

        public Builder volume(VolumeSpec v) {
            this.volume = v;
            return this;
        }

        public Builder requires(VersionRange r) {
            this.requires = r;
            return this;
        }

        public Builder placer(PlacerId p) {
            this.placer = p;
            return this;
        }

        public Builder verify(VerifyMode v) {
            this.verify = v;
            return this;
        }

        public Builder volatileProps(String... names) {
            this.volatileProps = Set.of(names);
            return this;
        }

        public Builder effect(EffectSpec e) {
            this.effect = e;
            return this;
        }

        public Builder assembly(AssemblySpec a) {
            this.assembly = a;
            return this;
        }

        public Builder kineticModel(ModelRef m) {
            this.kineticModel = m;
            return this;
        }

        public Builder phase(BuildPhase p) {
            this.phase = p;
            return this;
        }

        public PartType build() {
            return new PartType(id, category, visibility, displayNameKey, visualDescription, params, ports, volume,
                    requires, placer, verify, volatileProps, effect, assembly, kineticModel, phase);
        }
    }
}
