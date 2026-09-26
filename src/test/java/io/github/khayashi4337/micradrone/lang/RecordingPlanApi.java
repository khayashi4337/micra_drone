package io.github.khayashi4337.micradrone.lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Records every call as one readable line, so tests can compare the sequence. */
final class RecordingPlanApi implements PlanApi {
    final List<String> calls = new ArrayList<>();
    final List<String> printed = new ArrayList<>();

    @Override
    public void site(String dimension, int x, int y, int z, String facing, int[] bounds, String terrainDigest, String claimId) {
        calls.add("site " + dimension + " " + x + "," + y + "," + z + " " + facing + " " + Arrays.toString(bounds)
                + " [" + terrainDigest + "|" + claimId + "]");
    }

    @Override
    public void style(String role, String material) {
        calls.add("style " + role + "=" + material);
    }

    @Override
    public void mood(String tag) {
        calls.add("mood " + tag);
    }

    @Override
    public void part(String id, String type, String parent, PlanAnchorArgs anchor, Map<String, Object> params,
            List<String> tags, String label) {
        calls.add("part " + id + " " + type + " parent=" + parent + " " + anchor + " " + new TreeMap<>(params) + " " + tags
                + " '" + label + "'");
    }

    @Override
    public void updateParams(String id, Map<String, Object> params) {
        calls.add("update " + id + " " + new TreeMap<>(params));
    }

    @Override
    public void relocate(String id, PlanAnchorArgs anchor) {
        calls.add("relocate " + id + " " + anchor);
    }

    @Override
    public void removePart(String id) {
        calls.add("remove " + id);
    }

    @Override
    public void connect(String id, String from, String to, String kind, List<String> via, Map<String, Object> constraints) {
        calls.add("connect " + id + " " + from + " " + to + " " + kind + " via=" + via + " "
                + (constraints == null ? null : new TreeMap<>(constraints)));
    }

    @Override
    public void disconnect(String id) {
        calls.add("disconnect " + id);
    }

    @Override
    public void logistics(List<Object> docks, List<Object> routes, List<Object> flows) {
        calls.add("logistics " + docks.size() + "/" + routes.size() + "/" + flows.size());
    }

    @Override
    public void print(String text) {
        printed.add(text);
    }
}
