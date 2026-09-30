package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.model.PlanOp;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Safety net for {@link PlanRecorder}'s run-wide character budget: a reflection walk over the
 * sealed {@link PlanOp} variants that finds every place a recorded operation can retain a
 * String. The walk's output is asserted equal to the EXPECTED set below, which is derived by
 * hand from the {@code build.model} sources - so a new string-bearing field fails this test
 * until someone decides which recorder charge covers it. The walker stays in the test: no
 * reflection or budget machinery belongs in the model classes.
 *
 * <p>Path syntax: {@code .component} steps through record components, {@code []} marks a
 * List/Set element, {@code {key}}/{@code {value}} a Map entry, {@code :Subtype} a permitted
 * subclass of a sealed type. Each EXPECTED entry's comment names the PlanRecorder charge that
 * covers the site.
 */
class PlanRecorderStringPathsTest {
    /**
     * Every string a recorded op can keep, written from reading the model sources, not from
     * the walker's output. Enum-typed sites keep no text and so produce no path - they are
     * still charged on the string that was read: the site facing (SetSite.site.frame.facing),
     * the anchor side (AddNode.node.anchor:OnSurface.side), the connect kind
     * (AddConnection.connection.kind), the entry_dirs names
     * (AddConnection.connection.constraints.allowedEntryDirs[]) and the dock approach
     * (SetLogistics.logistics.docks[].approach).
     */
    private static final Set<String> EXPECTED = Set.of(
            // charged by toConstraints's "avoid" entry: chargeRecordedCharsOf(names)
            "AddConnection.connection.constraints.avoidNodeIds[]",
            // charged by connect() as textChars(from); PortRef keeps halves of that text
            "AddConnection.connection.from.nodeId",
            "AddConnection.connection.from.port",
            // charged by connect() as textChars(id)
            "AddConnection.connection.id",
            // charged by connect() as chargeRecordedCharsOf(via)
            "AddConnection.connection.routing:Explicit.viaNodeIds[]",
            // charged by connect() as textChars(to); kept split by port()
            "AddConnection.connection.to.nodeId",
            "AddConnection.connection.to.port",
            // charged by toAnchor() as textChars(a.slot())
            "AddNode.node.anchor:InSlot.slotId",
            // charged by toAnchor() as textChars(a.target())
            "AddNode.node.anchor:OnSurface.nodeId",
            // charged by part() as textChars(id)
            "AddNode.node.id",
            // charged by part() as textChars(label)
            "AddNode.node.label",
            // charged by toParams() as textChars(key) per entry
            "AddNode.node.params{key}",
            // charged by boundedCopy() as s.length() per string scalar (the recorder only ever
            // builds StrV, but the same charge covers these variants if a value reaches them)
            "AddNode.node.params{value}:EnumV.value",
            "AddNode.node.params{value}:MaterialV.value",
            "AddNode.node.params{value}:StrV.value",
            // charged by part() as textChars(parent)
            "AddNode.node.parent",
            // charged by part() as chargeRecordedCharsOf(tags)
            "AddNode.node.tags[]",
            // charged by part() as textChars(type)
            "AddNode.node.type",
            // charged by relocate() -> toAnchor() as textChars(a.slot())
            "MoveNode.anchor:InSlot.slotId",
            // charged by relocate() -> toAnchor() as textChars(a.target())
            "MoveNode.anchor:OnSurface.nodeId",
            // charged by relocate() as textChars(id)
            "MoveNode.id",
            // charged by disconnect() as textChars(id)
            "RemoveConnection.id",
            // charged by removePart() as textChars(id)
            "RemoveNode.id",
            // charged by logistics() as chargeRecordedCharsOf(connectorIds)
            "SetLogistics.logistics.docks[].dockingConnectorNodeIds[]",
            // charged by logistics() as textChars(dockId)
            "SetLogistics.logistics.docks[].id",
            // charged by logistics() as portSpec.length(); kept split by port()
            "SetLogistics.logistics.docks[].linkedPorts[].nodeId",
            "SetLogistics.logistics.docks[].linkedPorts[].port",
            // charged by logistics() as textChars(from)
            "SetLogistics.logistics.flows[].fromDock",
            // charged by logistics() as textChars(item)
            "SetLogistics.logistics.flows[].itemId",
            // charged by logistics() as textChars(to)
            "SetLogistics.logistics.flows[].toDock",
            // charged by logistics() as textChars(airship)
            "SetLogistics.logistics.routes[].airshipTemplateId",
            // charged by logistics() as textChars(from)
            "SetLogistics.logistics.routes[].fromDock",
            // charged by logistics() as textChars(routeId)
            "SetLogistics.logistics.routes[].id",
            // charged by logistics() as textChars(to)
            "SetLogistics.logistics.routes[].toDock",
            // charged by site() as textChars(claimId)
            "SetSite.site.claimId",
            // charged by site() as textChars(dimension)
            "SetSite.site.dimension",
            // charged by site() as textChars(terrainDigest)
            "SetSite.site.terrainDigest",
            // charged by mood() as textChars(tag)
            "SetStyle.style.moodTags[]",
            // charged by style() as textChars(role)
            "SetStyle.style.palette{key}",
            // charged by style() as textChars(material)
            "SetStyle.style.palette{value}",
            // charged by updateParams() as textChars(id)
            "UpdateParams.id",
            // charged by toParams() as textChars(key) per entry
            "UpdateParams.params{key}",
            // charged by boundedCopy() as s.length() per string scalar
            "UpdateParams.params{value}:EnumV.value",
            "UpdateParams.params{value}:MaterialV.value",
            "UpdateParams.params{value}:StrV.value");

    @Test
    void everyRetainedStringSiteMatchesTheHandDerivedSet() {
        Set<String> paths = new TreeSet<>();
        Set<String> unhandled = new TreeSet<>();
        Deque<Class<?>> onPath = new ArrayDeque<>(List.of(PlanOp.class));
        for (Class<?> op : PlanOp.class.getPermittedSubclasses()) {
            walkRecord(op, op.getSimpleName(), onPath, paths, unhandled);
        }
        assertEquals(EXPECTED, paths,
                "the model's string-retaining sites drifted from the hand-derived set -"
                        + " decide which PlanRecorder charge covers each new site");
        assertEquals(Set.of(), unhandled,
                () -> "component types the walk cannot classify - extend it deliberately: " + unhandled);
    }

    /** Adds {@code record} to the path types and walks each component under {@code path + "." + name}. */
    private static void walkRecord(Class<?> record, String path, Deque<Class<?>> onPath,
            Set<String> paths, Set<String> unhandled) {
        onPath.push(record);
        for (RecordComponent component : record.getRecordComponents()) {
            walkType(component.getGenericType(), path + "." + component.getName(), onPath, paths, unhandled);
        }
        onPath.pop();
    }

    /**
     * Classifies one component type: a String is a leaf path; a List/Set element or Map entry
     * recurses with a marker; a nested record recurses; a sealed type recurses into its
     * permitted subclasses. Enums, primitives and boxed numbers keep no text. A class already
     * on the path ends the branch - its string sites are described by the shallower occurrence.
     * Anything the walk cannot classify lands in {@code unhandled} and fails the test.
     */
    private static void walkType(Type type, String path, Deque<Class<?>> onPath,
            Set<String> paths, Set<String> unhandled) {
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() instanceof Class<?> raw
                    && (raw == List.class || raw == Set.class)) {
                walkType(parameterized.getActualTypeArguments()[0], path + "[]", onPath, paths, unhandled);
            } else if (parameterized.getRawType() instanceof Class<?> raw && raw == Map.class) {
                Type[] arguments = parameterized.getActualTypeArguments();
                walkType(arguments[0], path + "{key}", onPath, paths, unhandled);
                walkType(arguments[1], path + "{value}", onPath, paths, unhandled);
            } else {
                unhandled.add(path + " of " + type.getTypeName());
            }
            return;
        }
        if (!(type instanceof Class<?> c)) {
            unhandled.add(path + " of " + type.getTypeName());
            return;
        }
        if (c == String.class) {
            paths.add(path);
            return;
        }
        if (c.isEnum() || c.isPrimitive() || Number.class.isAssignableFrom(c)
                || c == Boolean.class || c == Character.class) {
            return;
        }
        if (onPath.contains(c)) {
            return;
        }
        if (c.isSealed()) {
            onPath.push(c);
            for (Class<?> sub : c.getPermittedSubclasses()) {
                if (sub.isRecord() && !onPath.contains(sub)) {
                    walkRecord(sub, path + ":" + sub.getSimpleName(), onPath, paths, unhandled);
                } else if (!sub.isRecord()) {
                    unhandled.add(path + ":" + sub.getSimpleName());
                }
            }
            onPath.pop();
            return;
        }
        if (c.isRecord()) {
            walkRecord(c, path, onPath, paths, unhandled);
            return;
        }
        unhandled.add(path + " of " + c.getName());
    }
}
