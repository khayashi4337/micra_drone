package io.github.khayashi4337.micradrone.construction.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The migration table (design 01, section 0): each saved type has a current version and one upgrade step per older
 * version. A newer version, an unknown type or a missing step refuses the file with a reason; nothing is guessed.
 */
public final class Migrations {
    private final Map<String, Integer> current;
    private final Map<String, Map<Integer, UnaryOperator<Object>>> steps = new HashMap<>();

    public Migrations(Map<String, Integer> currentVersions) {
        this.current = Map.copyOf(currentVersions);
    }

    public Migrations step(String type, int fromVersion, UnaryOperator<Object> upgrade) {
        steps.computeIfAbsent(type, k -> new HashMap<>()).put(fromVersion, upgrade);
        return this;
    }

    public Object payloadOf(PersistenceEnvelope e) throws UnreadableFileException {
        Integer target = current.get(e.type());
        if (target == null) {
            throw new UnreadableFileException("unknown saved type " + e.type());
        }
        if (e.schemaVersion() > target) {
            throw new UnreadableFileException(e.type() + " v" + e.schemaVersion() + " is newer than this game's v" + target);
        }
        Object payload = e.payload();
        for (int v = e.schemaVersion(); v < target; v++) {
            UnaryOperator<Object> up = steps.getOrDefault(e.type(), Map.of()).get(v);
            if (up == null) {
                throw new UnreadableFileException("no migration of " + e.type() + " from v" + v);
            }
            payload = up.apply(payload);
        }
        return payload;
    }
}
