package io.github.khayashi4337.micradrone.lang;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * face name (an arbitrary string at this language layer) -> registered ISR handler. attach_isr
 * registers, raise_interrupt fires. Owned alongside {@link TaskRegistry} by
 * {@link io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity} for the controller's
 * whole lifetime (across Runs) - see docs/design/lang_rtos_task_foundation.md.
 *
 * <p>Created here (rather than only once attach_isr/raise_interrupt land) because
 * {@link Interpreter}'s constructor takes one alongside a {@link TaskRegistry} - see that
 * constructor's javadoc.
 */
public final class InterruptTable {
    private final Map<String, MicraFunction> handlers = new ConcurrentHashMap<>();

    /** Registers (or replaces) the handler for {@code face}. */
    public void attach(String face, MicraFunction fn) {
        handlers.put(face, fn);
    }

    /** Null if nothing is attached to {@code face}. */
    public MicraFunction handlerFor(String face) {
        return handlers.get(face);
    }

    /** Call at the start of a fresh Run - a previous script version's handlers shouldn't linger. */
    public void clear() {
        handlers.clear();
    }
}
