package adris.altoclef.commands.graph;

import adris.altoclef.commands.RoutineLines;
import adris.altoclef.commands.RoutineStep;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// what's wrong with a graph, per node, so the editor can paint it and the run can refuse it. errors block a run, warnings
// are a node that just won't do anything. pure: the game only comes in through Checks
public final class GraphValidator {

    public enum IdKind {
        ITEM,
        BLOCK,
        MOB
    }

    public interface Checks extends RoutineLines.Checks {
        // null when the id is a real one. fine by default, same deal as RoutineLines.Checks
        default String idProblem(IdKind kind, String id) {
            return null;
        }

        // null when a Run routine node can call that id
        default String routineProblem(String id) {
            return null;
        }

        // the ids that routine calls itself, nothing for a list
        default List<String> callsOf(String id) {
            return List.of();
        }

        // false when no recipe is known for it. a craft node still gets it, just not by crafting
        default boolean craftable(String item) {
            return true;
        }

        Checks SHAPE_ONLY = new Checks() {
            @Override
            public boolean commandExists(String label) {
                return true;
            }

            @Override
            public String itemProblem(RoutineStep.Kind kind, String name) {
                return null;
            }

            @Override
            public String blockProblem(String name) {
                return null;
            }
        };
    }

    public record Problem(String error, String warning) {
    }

    // errors = what belongs to the whole graph. nodes has an entry for every node, even a fine one
    public record Result(List<String> errors, Map<String, Problem> nodes) {
        public boolean ok() {
            return firstError() == null;
        }

        public String firstError() {
            if (!errors.isEmpty()) {
                return errors.get(0);
            }
            for (Problem p : nodes.values()) {
                if (p.error() != null) {
                    return p.error();
                }
            }
            return null;
        }

        // the node firstError came from, null when it's about the whole graph
        public String firstErrorNode() {
            if (errors.isEmpty()) {
                for (Map.Entry<String, Problem> e : nodes.entrySet()) {
                    if (e.getValue().error() != null) {
                        return e.getKey();
                    }
                }
            }
            return null;
        }
    }

    private GraphValidator() {
    }

    public static Result validate(RoutineGraph g, Checks checks) {
        List<String> errors = new ArrayList<>();
        Map<String, String> err = new LinkedHashMap<>();
        Map<String, String> warn = new HashMap<>();
        Map<String, RoutineGraph.Node> by = g.byId();
        int starts = 0;
        CallWalk calls = new CallWalk(g.id(), checks);
        for (RoutineGraph.Node n : g.nodes()) {
            starts += n.type() == RoutineGraph.Type.START ? 1 : 0;
            err.put(n.id(), nodeProblem(n, checks, calls));
            if (n.type() == RoutineGraph.Type.CRAFT && !n.param("item").isBlank() && !checks.craftable(n.param("item").trim())) {
                warn.put(n.id(), "No recipe known for this, it gets it any way it can");
            }
        }
        if (starts != 1) {
            errors.add(starts == 0 ? "there is no Start node" : "there can only be one Start node");
        }
        if (g.nodes().size() != by.size()) {
            errors.add("two nodes have the same id");
        }
        Map<String, List<RoutineGraph.Edge>> outs = wireProblems(g, by, errors, err);
        // a wire that doesn't go anywhere is already an error above, the walks below never see it
        loopProblems(g, by, outs, err, warn);
        RoutineGraph.Node start = g.start();
        Set<String> reached = start == null ? Set.of() : reach(start.id(), outs, false);
        for (RoutineGraph.Node n : g.nodes()) {
            if (n.type() != RoutineGraph.Type.START && !reached.contains(n.id())) {
                warn.putIfAbsent(n.id(), "Not wired to Start, it won't run");
            }
        }
        Map<String, Problem> nodes = new LinkedHashMap<>();
        err.forEach((id, why) -> nodes.put(id, new Problem(why, warn.get(id))));
        return new Result(errors, nodes);
    }

    private static Map<String, List<RoutineGraph.Edge>> wireProblems(RoutineGraph g, Map<String, RoutineGraph.Node> by, List<String> errors, Map<String, String> err) {
        Map<String, List<RoutineGraph.Edge>> outs = new HashMap<>();
        Set<String> usedPorts = new HashSet<>();
        for (RoutineGraph.Edge e : g.edges()) {
            RoutineGraph.Node from = by.get(e.from());
            RoutineGraph.Node to = by.get(e.to());
            if (from == null || to == null) {
                errors.add("a wire goes to a node that isn't there (" + e.from() + " -> " + e.to() + ")");
                continue;
            }
            outs.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
            if (!RoutineGraph.outputs(from).contains(e.port())) {
                mark(err, from.id(), from.type().label + " has no " + e.port() + " port");
            } else if (!usedPorts.add(e.from() + ":" + e.port())) {
                // the interpreter walks one wire, picking one of two would be a coin flip nobody asked for
                mark(err, from.id(), "Only one wire can leave " + e.port());
            }
            if (!RoutineGraph.inputs(to.type()).contains(e.toPort())) {
                mark(err, to.id(), to.type() == RoutineGraph.Type.START ? "Nothing can wire into Start" : to.type().label + " has no " + e.toPort() + " input");
            }
        }
        return outs;
    }

    private static void loopProblems(RoutineGraph g, Map<String, RoutineGraph.Node> by, Map<String, List<RoutineGraph.Edge>> outs, Map<String, String> err, Map<String, String> warn) {
        for (String id : cycleNodes(by, outs)) {
            mark(err, id, "This loops back without a Repeat, wire it into a Repeat's again input");
        }
        Set<String> insideALoop = new HashSet<>();
        for (RoutineGraph.Node n : g.nodes()) {
            if (n.type() != RoutineGraph.Type.REPEAT) {
                continue;
            }
            List<RoutineGraph.Edge> mine = outs.getOrDefault(n.id(), List.of());
            RoutineGraph.Edge body = port(mine, RoutineGraph.BODY);
            if (body == null) {
                mark(err, n.id(), "Wire something to body");
            }
            Set<String> inBody = bodyOf(n, mine, outs);
            insideALoop.addAll(inBody);
            boolean loops = false;
            for (RoutineGraph.Edge e : g.edges()) {
                if (e.again() && e.to().equals(n.id()) && by.containsKey(e.from())) {
                    loops = true;
                    if (!inBody.contains(e.from())) {
                        // from the done side it would go round forever without ever running the body
                        mark(err, e.from(), "Only the Repeat's own body can wire into its again input");
                    }
                }
            }
            if (body != null && !loops) {
                warn.put(n.id(), "Nothing wires back to again, the body only runs once");
            } else if (loops && inBody.stream().noneMatch(id -> waits(by.get(id)))) {
                // raw is done when its command returns, so a raw mine in here starts over every tick and an until runs
                // out of laps in a second. allowed, a quick loop is a real thing, but nobody expects it
                warn.put(n.id(), "Raw commands don't wait, this laps every tick. Put a Mine, Craft, Get, Go to or Wait in the body");
            }
        }
        // a repeat's own until is the one place outside its body that can read its counter
        for (RoutineGraph.Node n : g.nodes()) {
            if (n.type() != RoutineGraph.Type.REPEAT && !insideALoop.contains(n.id()) && Conditions.uses(n.cond(), "loop_counter")) {
                warn.putIfAbsent(n.id(), "The loop counter only reads inside a Repeat, out here it fails the run");
            }
        }
    }

    private static boolean waits(RoutineGraph.Node n) {
        return n != null && n.type() != RoutineGraph.Type.RAW && (n.type().acts || n.type() == RoutineGraph.Type.WAIT);
    }

    // the nodes inside a repeat, so the editor can tell a wire back into it (again) from a wire into its front (in)
    public static Set<String> bodyOf(RoutineGraph g, String repeatId) {
        Map<String, RoutineGraph.Node> by = g.byId();
        RoutineGraph.Node repeat = by.get(repeatId);
        if (repeat == null || repeat.type() != RoutineGraph.Type.REPEAT) {
            return Set.of();
        }
        Map<String, List<RoutineGraph.Edge>> outs = new HashMap<>();
        for (RoutineGraph.Edge e : g.edges()) {
            if (by.containsKey(e.from()) && by.containsKey(e.to())) {
                outs.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
            }
        }
        return bodyOf(repeat, outs.getOrDefault(repeatId, List.of()), outs);
    }

    // what the body port reaches, minus what the done port reaches too: past the point where the two meet you're out of
    // the loop. leaving this set on any wire is how a body breaks out
    private static Set<String> bodyOf(RoutineGraph.Node repeat, List<RoutineGraph.Edge> mine, Map<String, List<RoutineGraph.Edge>> outs) {
        Set<String> body = entered(port(mine, RoutineGraph.BODY), outs);
        body.removeAll(entered(port(mine, RoutineGraph.DONE), outs));
        body.remove(repeat.id());
        return body;
    }

    private static Set<String> entered(RoutineGraph.Edge first, Map<String, List<RoutineGraph.Edge>> outs) {
        return first == null || first.again() ? new HashSet<>() : reach(first.to(), outs, true);
    }

    private static RoutineGraph.Edge port(List<RoutineGraph.Edge> edges, String port) {
        for (RoutineGraph.Edge e : edges) {
            if (e.port().equals(port)) {
                return e;
            }
        }
        return null;
    }

    private static Set<String> reach(String from, Map<String, List<RoutineGraph.Edge>> outs, boolean skipAgain) {
        Set<String> seen = new HashSet<>(List.of(from));
        ArrayDeque<String> todo = new ArrayDeque<>(seen);
        while (!todo.isEmpty()) {
            for (RoutineGraph.Edge e : outs.getOrDefault(todo.poll(), List.of())) {
                if ((!skipAgain || !e.again()) && seen.add(e.to())) {
                    todo.add(e.to());
                }
            }
        }
        return seen;
    }

    // peel off everything nothing points at. whatever can't be peeled sits on a cycle (or behind one, close enough to
    // point at). again wires don't count, those are the only back wires there are
    private static Set<String> cycleNodes(Map<String, RoutineGraph.Node> by, Map<String, List<RoutineGraph.Edge>> outs) {
        Map<String, Integer> pointing = new HashMap<>();
        by.keySet().forEach(id -> pointing.put(id, 0));
        for (List<RoutineGraph.Edge> edges : outs.values()) {
            for (RoutineGraph.Edge e : edges) {
                if (!e.again()) {
                    pointing.merge(e.to(), 1, Integer::sum);
                }
            }
        }
        ArrayDeque<String> free = new ArrayDeque<>();
        pointing.forEach((id, count) -> {
            if (count == 0) {
                free.add(id);
            }
        });
        while (!free.isEmpty()) {
            String id = free.poll();
            pointing.remove(id);
            for (RoutineGraph.Edge e : outs.getOrDefault(id, List.of())) {
                if (!e.again() && pointing.merge(e.to(), -1, Integer::sum) == 0) {
                    free.add(e.to());
                }
            }
        }
        return pointing.keySet();
    }

    private static void mark(Map<String, String> err, String id, String why) {
        if (err.get(id) == null) {
            err.put(id, why);
        }
    }

    private static String nodeProblem(RoutineGraph.Node n, Checks checks, CallWalk calls) {
        if (n.type().action != null) {
            return RoutineLines.problem(new RoutineStep(n.type().action, RoutineLines.normalize(n.param("line"), null)), checks);
        }
        return switch (n.type()) {
            case MINE, EAT, FOLLOW -> fieldsProblem(n, checks);
            case CRAFT -> {
                String why = fieldsProblem(n, checks);
                // the item id is real, it also has to be something the catalogue has a way of getting
                yield why != null ? why : checks.itemProblem(RoutineStep.Kind.GET, n.param("item").trim());
            }
            case SUB -> {
                String why = fieldsProblem(n, checks);
                yield why != null ? why : calls.problem(n.param("routine").trim());
            }
            case IF -> condProblem(n.cond(), checks);
            case REPEAT -> repeatProblem(n, checks);
            case WAIT -> waitProblem(n, checks);
            case STOP -> List.of("", "true", "false").contains(n.param("ok")) ? null : "Ends as true or false";
            default -> null;
        };
    }

    private static String fieldsProblem(RoutineGraph.Node n, Checks checks) {
        for (GraphSpecs.Field f : GraphSpecs.node(n.type()).fields()) {
            String why = fieldProblem(n.type().label, f, n.param(f.key()).trim(), checks);
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    // the run has its own guard, this is so it's on the card before anybody presses Run. what it finds is what's saved right
    // now. every routine gets asked about once: asking walks the routines folder, and ten calls per level over eight levels
    // would otherwise be a million walks for one If getting edited
    private static final class CallWalk {
        private final String home;
        private final Checks checks;
        private final Set<String> path = new HashSet<>();
        private final Map<String, Integer> depths = new HashMap<>();
        private String found;

        CallWalk(String home, Checks checks) {
            this.home = home;
            this.checks = checks;
        }

        String problem(String id) {
            path.clear();
            found = null;
            // the depth doesn't count us, the guard does
            if (walk(id) >= GraphSpecs.MAX_CALL_DEPTH && found == null) {
                found = "Routines only go " + GraphSpecs.MAX_CALL_DEPTH + " deep";
            }
            return found;
        }

        // how many routines deep it goes from here down, this one included
        private int walk(String id) {
            String key = id.toLowerCase(Locale.ROOT);
            if (key.equalsIgnoreCase(home)) {
                found = "This ends up running itself through " + id;
                return 0;
            }
            Integer known = depths.get(key);
            if (known != null) {
                return known;
            }
            if (!path.add(key)) {
                found = id + " ends up running itself";
                return 0;
            }
            if (path.size() >= GraphSpecs.MAX_CALL_DEPTH) {
                found = "Routines only go " + GraphSpecs.MAX_CALL_DEPTH + " deep";
                return 0;
            }
            int deepest = 0;
            for (String next : checks.callsOf(id)) {
                deepest = Math.max(deepest, walk(next));
                if (found != null) {
                    return 0;
                }
            }
            path.remove(key);
            depths.put(key, deepest + 1);
            return deepest + 1;
        }
    }

    private static String repeatProblem(RoutineGraph.Node n, Checks checks) {
        return switch (n.param("mode")) {
            case RoutineGraph.MODE_TIMES -> intIn(n.param("n"), 1, GraphSpecs.MAX_LOOPS) ? null : "Repeat 1 to " + GraphSpecs.MAX_LOOPS + " times";
            case RoutineGraph.MODE_UNTIL -> {
                String why = condProblem(n.cond(), checks);
                yield why != null || intIn(n.param("cap"), 1, GraphSpecs.MAX_LOOPS) ? why : "Give up after 1 to " + GraphSpecs.MAX_LOOPS + " loops";
            }
            case RoutineGraph.MODE_FOREVER -> null;
            default -> "Pick a mode: times, until or forever";
        };
    }

    private static String waitProblem(RoutineGraph.Node n, Checks checks) {
        return switch (n.param("mode")) {
            // the same rules as a list routine's wait step, it is the same step
            case RoutineGraph.MODE_SECS -> RoutineLines.problem(new RoutineStep(RoutineStep.Kind.WAIT, "wait " + n.param("secs").trim()), checks);
            case RoutineGraph.MODE_UNTIL -> {
                String why = condProblem(n.cond(), checks);
                yield why != null || secondsIn(n.param("timeout")) ? why : "Time out after 1 to " + (int) RoutineLines.MAX_WAIT_SECONDS + " seconds";
            }
            default -> "Pick a mode: secs or until";
        };
    }

    private static boolean secondsIn(String s) {
        try {
            double v = Double.parseDouble(s.trim());
            return v >= 1 && v <= RoutineLines.MAX_WAIT_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // the first thing wrong with a condition, null when it can be asked
    public static String condProblem(Cond c, Checks checks) {
        return c == null ? "Add a condition" : condProblem(c, checks, 1);
    }

    private static String condProblem(Cond c, Checks checks, int depth) {
        if (depth > Conditions.MAX_DEPTH) {
            return "Conditions go " + Conditions.MAX_DEPTH + " levels deep at most";
        }
        if (c.op() == Cond.Op.LEAF) {
            return leafProblem(c, checks);
        }
        if (c.children().isEmpty()) {
            return "Add a condition";
        }
        if (c.op() == Cond.Op.NOT && c.children().size() != 1) {
            return "not takes one condition";
        }
        for (Cond k : c.children()) {
            String why = condProblem(k, checks, depth + 1);
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    private static String leafProblem(Cond c, Checks checks) {
        GraphSpecs.CondSpec spec = GraphSpecs.cond(c.kind());
        if (spec == null) {
            return "Pick a condition";
        }
        for (GraphSpecs.Field f : spec.fields()) {
            String why = fieldProblem(spec.label(), f, c.arg(f.key()).trim(), checks);
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    private static String fieldProblem(String owner, GraphSpecs.Field f, String v, Checks checks) {
        String label = f.label().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return f.required() ? owner + " needs " + label : null;
        }
        return switch (f.kind()) {
            case INT -> intIn(v, f.min(), f.max()) ? null : owner + ": " + label + " goes from " + f.min() + " to " + f.max();
            case COORD -> intIn(v, -GraphSpecs.MAX_COORD, GraphSpecs.MAX_COORD) ? null : owner + ": \"" + v + "\" is not a coordinate";
            case CHOICE -> f.choices().contains(v) ? null : owner + " is one of " + String.join(", ", f.choices());
            case OP -> GraphSpecs.OPS.contains(v) ? null : "\"" + v + "\" is not a comparison";
            case ITEM -> checks.idProblem(IdKind.ITEM, v);
            case BLOCK -> checks.idProblem(IdKind.BLOCK, v);
            case MOB -> checks.idProblem(IdKind.MOB, v);
            // the characters a minecraft name can have. offline servers bend the rest, so no more than that
            case PLAYER -> v.matches("[A-Za-z0-9_]{1,16}") ? null : "\"" + v + "\" is not a player name";
            case ROUTINE -> checks.routineProblem(v);
            default -> null;
        };
    }

    private static boolean intIn(String s, int min, int max) {
        try {
            int v = Integer.parseInt(s.trim());
            return v >= min && v <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
