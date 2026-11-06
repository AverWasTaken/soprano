package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// the tidy button. layers by longest path from start, then barycenter to untangle a layer. same input, same layout,
// so pressing it twice is a no-op and not a dice roll
public final class AutoArrange {

    private static final int STEP_X = NodeGeometry.WIDTH + 48;
    private static final int GAP_Y = 32;
    private static final int SNAP = 8;

    public record Node(String id, int height) {
    }

    public record Edge(String from, String to, boolean again) {
    }

    public record Placed(String id, int x, int y) {
    }

    private AutoArrange() {
    }

    // placed in input order. nodes nothing reaches from start go in a row under everything, so they stay findable
    public static List<Placed> arrange(String start, List<Node> nodes, List<Edge> edges) {
        Map<String, Node> byId = new HashMap<>();
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            byId.put(nodes.get(i).id, nodes.get(i));
            order.put(nodes.get(i).id, i);
        }
        Map<String, List<String>> out = forwardEdges(start, byId, edges);
        Map<String, Integer> layer = layers(start, out, nodes.size());
        List<List<String>> columns = columns(layer, order);
        untangle(columns, out);

        Map<String, Placed> placed = new HashMap<>();
        int bottom = stack(columns, byId, placed);
        placeLeftovers(nodes, placed, bottom);

        List<Placed> result = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            result.add(placed.get(n.id));
        }
        return result;
    }

    // walks from start in edge order. again wires never count, and any other edge landing on the walk's stack is a real
    // cycle somebody made: dropping it is the one way longest path terminates
    private static Map<String, List<String>> forwardEdges(String start, Map<String, Node> byId, List<Edge> edges) {
        Map<String, List<String>> all = new HashMap<>();
        for (Edge e : edges) {
            if (!e.again && byId.containsKey(e.from) && byId.containsKey(e.to)) {
                all.computeIfAbsent(e.from, k -> new ArrayList<>()).add(e.to);
            }
        }
        Map<String, List<String>> out = new HashMap<>();
        if (byId.containsKey(start)) {
            walk(start, all, new HashSet<>(), new HashSet<>(), out);
        }
        return out;
    }

    private static void walk(String node, Map<String, List<String>> all, Set<String> onStack, Set<String> done,
                             Map<String, List<String>> out) {
        onStack.add(node);
        List<String> kept = out.computeIfAbsent(node, k -> new ArrayList<>());
        for (String next : all.getOrDefault(node, List.of())) {
            if (onStack.contains(next)) {
                continue;
            }
            kept.add(next);
            if (!done.contains(next)) {
                walk(next, all, onStack, done, out);
            }
        }
        onStack.remove(node);
        done.add(node);
    }

    private static Map<String, Integer> layers(String start, Map<String, List<String>> out, int nodeCount) {
        Map<String, Integer> layer = new HashMap<>();
        if (out.containsKey(start)) {
            layer.put(start, 0);
        }
        // what's left is a dag, so relaxing n times settles it whatever order the map walks in. n is 200 on a bad day
        boolean changed = !layer.isEmpty();
        for (int pass = 0; changed && pass <= nodeCount; pass++) {
            changed = false;
            for (Map.Entry<String, List<String>> e : out.entrySet()) {
                Integer from = layer.get(e.getKey());
                if (from == null) {
                    continue;
                }
                for (String to : e.getValue()) {
                    if (layer.getOrDefault(to, -1) < from + 1) {
                        layer.put(to, from + 1);
                        changed = true;
                    }
                }
            }
        }
        return layer;
    }

    private static List<List<String>> columns(Map<String, Integer> layer, Map<String, Integer> order) {
        int max = -1;
        for (int l : layer.values()) {
            max = Math.max(max, l);
        }
        List<List<String>> columns = new ArrayList<>();
        for (int i = 0; i <= max; i++) {
            columns.add(new ArrayList<>());
        }
        List<String> ids = new ArrayList<>(layer.keySet());
        ids.sort(Comparator.comparingInt(order::get));
        for (String id : ids) {
            columns.get(layer.get(id)).add(id);
        }
        return columns;
    }

    // one sweep down by predecessors, one up by successors. a second sweep down would just agree with the first one
    private static void untangle(List<List<String>> columns, Map<String, List<String>> out) {
        Map<String, List<String>> in = new HashMap<>();
        out.forEach((from, tos) -> {
            for (String to : tos) {
                in.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
            }
        });
        for (int i = 1; i < columns.size(); i++) {
            sortColumn(columns.get(i), columns.get(i - 1), in);
        }
        for (int i = columns.size() - 2; i >= 0; i--) {
            sortColumn(columns.get(i), columns.get(i + 1), out);
        }
    }

    private static void sortColumn(List<String> column, List<String> neighbour, Map<String, List<String>> links) {
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < neighbour.size(); i++) {
            pos.put(neighbour.get(i), i);
        }
        Map<String, Float> key = new HashMap<>();
        for (int i = 0; i < column.size(); i++) {
            String id = column.get(i);
            float sum = 0f;
            int count = 0;
            for (String other : links.getOrDefault(id, List.of())) {
                Integer p = pos.get(other);
                if (p != null) {
                    sum += p;
                    count++;
                }
            }
            // nothing on that side: stays about where it was
            key.put(id, count == 0 ? i : sum / count);
        }
        // List.sort is stable, so ties keep their order. that and input order is all the determinism there is
        column.sort(Comparator.comparingDouble(key::get));
    }

    private static int stack(List<List<String>> columns, Map<String, Node> byId, Map<String, Placed> placed) {
        int bottom = 0;
        for (int c = 0; c < columns.size(); c++) {
            int y = 0;
            for (String id : columns.get(c)) {
                placed.put(id, new Placed(id, c * STEP_X, y));
                y = snapUp(y + byId.get(id).height + GAP_Y);
            }
            bottom = Math.max(bottom, y);
        }
        return bottom;
    }

    private static void placeLeftovers(List<Node> nodes, Map<String, Placed> placed, int bottom) {
        int x = 0;
        for (Node n : nodes) {
            if (!placed.containsKey(n.id)) {
                placed.put(n.id, new Placed(n.id, x, bottom));
                x += STEP_X;
            }
        }
    }

    // up, never down: rounding down could eat the gap
    private static int snapUp(int y) {
        return (y + SNAP - 1) / SNAP * SNAP;
    }
}
