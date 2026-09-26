package io.github.khayashi4337.micradrone.lang;

import java.lang.reflect.Proxy;

/**
 * The {@link DroneApi} a construction interpreter is given: it has no drone, so every method refuses, except
 * {@code print}, which goes to the plan's own log. The interpreter refuses farm commands before they reach the
 * DroneApi; this is the second line of defence.
 */
final class PlanModeDroneApi {
    private static final String PRINT = "print";
    private static final String OBJECT_EQUALS = "equals";
    private static final String OBJECT_HASH_CODE = "hashCode";
    private static final String DESCRIPTION = "PlanModeDroneApi";

    private PlanModeDroneApi() {
    }

    static DroneApi create(PlanApi planApi) {
        return (DroneApi) Proxy.newProxyInstance(DroneApi.class.getClassLoader(), new Class<?>[]{DroneApi.class}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case OBJECT_EQUALS -> proxy == args[0];
                    case OBJECT_HASH_CODE -> System.identityHashCode(proxy);
                    default -> DESCRIPTION;
                };
            }
            if (method.getName().equals(PRINT) && args != null && args.length == 1) {
                planApi.print(String.valueOf(args[0]));
                return null;
            }
            throw new MicraLangException(0, "'" + method.getName() + "' is a drone command and cannot be used in a construction script");
        });
    }
}
