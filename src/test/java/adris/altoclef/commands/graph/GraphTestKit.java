package adris.altoclef.commands.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// little builders so a test's graph reads like the graph
public final class GraphTestKit {

    private GraphTestKit() {
    }

    public static RoutineGraph.Node start() {
        return new RoutineGraph.Node("s", RoutineGraph.Type.START, 0, 0, null, null);
    }

    public static RoutineGraph.Node raw(String id, String line) {
        return new RoutineGraph.Node(id, RoutineGraph.Type.RAW, 0, 0, Map.of("line", line), null);
    }

    public static RoutineGraph.Node iff(String id, Cond cond) {
        return new RoutineGraph.Node(id, RoutineGraph.Type.IF, 0, 0, null, cond);
    }

    public static RoutineGraph.Node times(String id, int n) {
        return new RoutineGraph.Node(id, RoutineGraph.Type.REPEAT, 0, 0, Map.of("mode", "times", "n", String.valueOf(n)), null);
    }

    public static RoutineGraph.Node forever(String id) {
        return new RoutineGraph.Node(id, RoutineGraph.Type.REPEAT, 0, 0, Map.of("mode", "forever"), null);
    }

    public static RoutineGraph.Node until(String id, Cond cond, int cap) {
        return new RoutineGraph.Node(id, RoutineGraph.Type.REPEAT, 0, 0, Map.of("mode", "until", "cap", String.valueOf(cap)), cond);
    }

    public static RoutineGraph.Node waitUntil(String id, Cond cond, int timeout) {
        return new RoutineGraph.Node(id, RoutineGraph.Type.WAIT, 0, 0, Map.of("mode", "until", "timeout", String.valueOf(timeout)), cond);
    }

    public static RoutineGraph.Node waitSecs(String id, String secs) {
        return new RoutineGraph.Node(id, RoutineGraph.Type.WAIT, 0, 0, Map.of("mode", "secs", "secs", secs), null);
    }

    public static RoutineGraph.Node stop(String id, boolean ok) {
        return new RoutineGraph.Node(id, RoutineGraph.Type.STOP, 0, 0, Map.of("ok", String.valueOf(ok)), null);
    }

    public static Cond night() {
        return Cond.leaf("time", Map.of("value", "night"));
    }

    public static Cond hasLogs(int n) {
        return Cond.leaf("has_item", Map.of("item", "oak_log", "op", ">=", "value", String.valueOf(n)));
    }

    // "a.ok>b" is a wire, "a.ok>>b" is a wire into b's again input
    public static RoutineGraph graph(List<RoutineGraph.Node> nodes, String... wires) {
        List<RoutineGraph.Edge> edges = new ArrayList<>();
        for (String w : wires) {
            boolean again = w.contains(">>");
            String[] sides = w.split(again ? ">>" : ">");
            String[] from = sides[0].split("\\.");
            edges.add(new RoutineGraph.Edge(from[0], from[1], sides[1], again ? RoutineGraph.AGAIN : null));
        }
        return new RoutineGraph("test", "test", "", nodes, edges);
    }

    // a world you can set by hand. freshens counts the times an If asked for fresh numbers
    public static class FakeWorld implements Conditions.World {
        public int logs;
        public double health = 20;
        public int hunger = 20;
        public boolean day = true;
        public String dimension = "overworld";
        public int freeSlots = 36;
        public double x;
        public double y;
        public double z;
        public int freshens;
        public boolean mobNear;
        public boolean blockNear;
        public int lastRadius;
        public RuntimeException unreadable;

        private void check() {
            if (unreadable != null) {
                throw unreadable;
            }
        }

        @Override
        public void freshen() {
            freshens++;
        }

        @Override
        public int itemCount(String item) {
            check();
            return "oak_log".equals(item) ? logs : 0;
        }

        @Override
        public double health() {
            return health;
        }

        @Override
        public int hunger() {
            return hunger;
        }

        @Override
        public boolean isDay() {
            check();
            return day;
        }

        @Override
        public String dimension() {
            return dimension;
        }

        @Override
        public int freeSlots() {
            return freeSlots;
        }

        @Override
        public double x() {
            return x;
        }

        @Override
        public double y() {
            return y;
        }

        @Override
        public double z() {
            return z;
        }

        @Override
        public boolean nearMob(String mob, int radius) {
            lastRadius = radius;
            return mobNear;
        }

        @Override
        public boolean nearBlock(String block, int radius) {
            lastRadius = radius;
            return blockNear;
        }
    }
}
