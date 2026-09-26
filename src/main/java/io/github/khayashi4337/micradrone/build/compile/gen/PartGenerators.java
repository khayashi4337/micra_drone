package io.github.khayashi4337.micradrone.build.compile.gen;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The generator of each building part. Task 16 adds a test that keeps this table equal to the registry's micra:* parts. */
public final class PartGenerators {
    /** BASE parts lay down blocks; CARVE parts (openings) run after all of them and cut into what BASE laid. */
    public enum Stage { BASE, CARVE }

    public record Entry(PartGenerator generator, Stage stage) {
    }

    private static final Map<String, Entry> ENTRIES = Map.ofEntries(
            Map.entry("micra:structure", new Entry(new StructureGen(), Stage.BASE)),
            Map.entry("micra:foundation", new Entry(new FoundationGen(), Stage.BASE)),
            Map.entry("micra:floor", new Entry(new FloorGen(), Stage.BASE)),
            Map.entry("micra:wall", new Entry(new WallGen(), Stage.BASE)),
            Map.entry("micra:pillar", new Entry(new PillarGen(), Stage.BASE)),
            Map.entry("micra:beam", new Entry(new BeamGen(), Stage.BASE)),
            Map.entry("micra:chimney", new Entry(new ChimneyGen(), Stage.BASE)),
            Map.entry("micra:road", new Entry(new RoadGen(), Stage.BASE)),
            Map.entry("micra:dock_pad", new Entry(new DockPadGen(), Stage.BASE)),
            Map.entry("micra:roof", new Entry(new RoofGen(), Stage.BASE)),
            Map.entry("micra:stairs", new Entry(new StairsGen(), Stage.BASE)),
            Map.entry("micra:ladder", new Entry(new LadderGen(), Stage.BASE)),
            Map.entry("micra:ramp", new Entry(new RampGen(), Stage.BASE)),
            Map.entry("micra:catwalk", new Entry(new CatwalkGen(), Stage.BASE)),
            Map.entry("micra:railing", new Entry(new RailingGen(), Stage.BASE)),
            Map.entry("micra:balcony", new Entry(new BalconyGen(), Stage.BASE)),
            Map.entry("micra:lamp", new Entry(new LampGen(), Stage.BASE)),
            Map.entry("micra:sign", new Entry(new SignGen(), Stage.BASE)),
            Map.entry("micra:planter", new Entry(new PlanterGen(), Stage.BASE)),
            Map.entry("micra:trim", new Entry(new TrimGen(), Stage.BASE)),
            Map.entry("micra:door", new Entry(new DoorGen(), Stage.CARVE)),
            Map.entry("micra:window", new Entry(new WindowGen(), Stage.CARVE)));

    private PartGenerators() {
    }

    public static Optional<Entry> find(String partId) {
        return Optional.ofNullable(ENTRIES.get(partId));
    }

    public static Set<String> ids() {
        return ENTRIES.keySet();
    }
}
