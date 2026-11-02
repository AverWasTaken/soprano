package adris.altoclef.commands.graph;

import adris.altoclef.RoutineRunner;
import adris.altoclef.RoutineRunner.Op;
import adris.altoclef.RoutineRunner.Result;
import adris.altoclef.commands.RoutineLines;
import adris.altoclef.commands.RoutineStep;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

// walks a graph for RoutineRunner. the runner does the starting and the waiting, this only decides what's next, so the
// tests can step through a whole graph with fake results. one active node at a time, no threads, no surprises
public final class GraphProgram implements RoutineRunner.Program {

    // the trail the editor draws. a forever loop must not grow it forever
    private static final int RECENT = 32;
    public static final int INLINE_BUDGET = 64;

    private final RoutineGraph graph;
    private final Conditions.World world;
    private final GraphValidator.Checks checks;
    private final Consumer<String> log;
    private final Map<String, RoutineGraph.Node> nodes;
    private final Map<String, RoutineGraph.Edge> wires = new HashMap<>();
    private final int total;

    private RoutineGraph.Node at;
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final Map<String, String> outcomes = new LinkedHashMap<>();
    private final Map<String, Integer> repeats = new HashMap<>();
    private final Set<String> taken = new LinkedHashSet<>();
    private int visitsThatCount;

    public GraphProgram(RoutineGraph graph, Conditions.World world, GraphValidator.Checks checks, Consumer<String> log) {
        this.graph = graph;
        this.world = world;
        this.checks = checks;
        this.log = log;
        this.nodes = graph.byId();
        for (RoutineGraph.Edge e : graph.edges()) {
            wires.putIfAbsent(e.from() + ":" + e.port(), e);
        }
        int count = 0;
        for (RoutineGraph.Node n : graph.nodes()) {
            count += counts(n) ? 1 : 0;
        }
        this.total = count;
    }

    // a graph that doesn't validate never starts: half a run down a wire that isn't there helps nobody
    @Override
    public String precheck(Function<RoutineStep, String> ignored) {
        GraphValidator.Result v = GraphValidator.validate(graph, checks);
        if (v.ok()) {
            return null;
        }
        String node = v.firstErrorNode();
        at = node == null ? null : nodes.get(node);
        return v.firstError();
    }

    @Override
    public Op first() {
        return enter(graph.start(), false);
    }

    @Override
    public Op next(String node, Result result) {
        RoutineGraph.Node n = nodes.get(node);
        note(n, result);
        String port = portFor(n, result);
        RoutineGraph.Edge wire = port == null ? null : wires.get(n.id() + ":" + port);
        if (wire == null) {
            return unwired(n, result);
        }
        taken.add(wire.key());
        return enter(nodes.get(wire.to()), wire.again());
    }

    // an end of the line is a fine way to finish. a failure nobody wired up is not
    private Op unwired(RoutineGraph.Node n, Result result) {
        return switch (result.kind()) {
            case FAILED -> new Op.End(RoutineRunner.State.FAILED, n.type().label + ": " + result.message());
            case TIMEOUT -> new Op.End(RoutineRunner.State.FAILED, n.type().label + " timed out, nothing wired to timeout");
            default -> new Op.End(RoutineRunner.State.DONE, null);
        };
    }

    private static String portFor(RoutineGraph.Node n, Result r) {
        return switch (r.kind()) {
            case OK -> n.type() == RoutineGraph.Type.START ? RoutineGraph.OUT : RoutineGraph.OK;
            case FAILED -> n.type().action != null ? RoutineGraph.FAILED : null;
            case TIMEOUT -> RoutineGraph.TIMEOUT;
            case YES -> n.type() == RoutineGraph.Type.REPEAT ? RoutineGraph.BODY : RoutineGraph.YES;
            case NO -> n.type() == RoutineGraph.Type.REPEAT ? RoutineGraph.DONE : RoutineGraph.NO;
        };
    }

    private Op enter(RoutineGraph.Node n, boolean again) {
        at = n;
        recent.addLast(n.id());
        if (recent.size() > RECENT) {
            recent.removeFirst();
        }
        visitsThatCount += counts(n) ? 1 : 0;
        return switch (n.type()) {
            case START -> new Op.Inline(n.id(), () -> Result.OK, false);
            case GET, EQUIP, GOTO, RAW -> new Op.Step(n.id(), new RoutineStep(n.type().action, RoutineLines.normalize(n.param("line"), null)));
            case IF -> new Op.Inline(n.id(), () -> ask(n), false);
            // a back wire always costs a tick: a loop of quick commands would freeze the game on one otherwise
            case REPEAT -> new Op.Inline(n.id(), () -> repeat(n, again), again);
            case WAIT -> waitOp(n);
            case STOP -> stop(n);
        };
    }

    private Op stop(RoutineGraph.Node n) {
        boolean failed = "false".equals(n.param("ok"));
        outcomes.put(n.id(), (failed ? Result.Kind.FAILED : Result.Kind.OK).name());
        return new Op.End(failed ? RoutineRunner.State.FAILED : RoutineRunner.State.DONE, failed ? "stopped as failed" : null);
    }

    private Op waitOp(RoutineGraph.Node n) {
        if (!RoutineGraph.MODE_UNTIL.equals(n.param("mode"))) {
            return new Op.Step(n.id(), new RoutineStep(RoutineStep.Kind.WAIT, "wait " + n.param("secs").trim()));
        }
        Supplier<Result> poll = () -> {
            try {
                return Conditions.eval(n.cond(), world) ? Result.OK : null;
            } catch (RuntimeException e) {
                return Result.failed(e.getMessage());
            }
        };
        return new Op.WaitUntil(n.id(), poll, Double.parseDouble(n.param("timeout").trim()), Conditions.text(n.cond()));
    }

    private Result ask(RoutineGraph.Node n) {
        try {
            world.freshen();
            return Conditions.eval(n.cond(), world) ? Result.YES : Result.NO;
        } catch (RuntimeException e) {
            return Result.failed(e.getMessage());
        }
    }

    // YES = go round, NO = done. coming in from the front is a new loop, an inner repeat starts over on every outer lap
    private Result repeat(RoutineGraph.Node n, boolean again) {
        int k = again ? repeats.getOrDefault(n.id(), 0) : 0;
        repeats.put(n.id(), k);
        switch (n.param("mode")) {
            case RoutineGraph.MODE_TIMES -> {
                if (k >= Integer.parseInt(n.param("n").trim())) {
                    return Result.NO;
                }
            }
            case RoutineGraph.MODE_UNTIL -> {
                Result r = ask(n);
                if (r.kind() != Result.Kind.NO) {
                    return r.kind() == Result.Kind.YES ? Result.NO : r;
                }
                int cap = Integer.parseInt(n.param("cap").trim());
                if (k >= cap) {
                    return Result.failed("gave up after " + cap + " loops");
                }
            }
            default -> {
            }
        }
        repeats.put(n.id(), k + 1);
        return Result.YES;
    }

    // logging every lap of a loop would bury the log, so only what's new: a node's first go, or an outcome that changed
    private void note(RoutineGraph.Node n, Result r) {
        String before = outcomes.put(n.id(), r.kind().name());
        if (!r.kind().name().equals(before) && n.type() != RoutineGraph.Type.START) {
            log.accept("routine " + graph.id() + ": " + n.id() + " (" + n.type().wire() + ") " + r.kind() + (r.message() == null ? "" : ": " + r.message()));
        }
    }

    private static boolean counts(RoutineGraph.Node n) {
        return n.type().action != null || n.type() == RoutineGraph.Type.WAIT;
    }

    @Override
    public int inlineBudget() {
        return INLINE_BUDGET;
    }

    @Override
    public boolean quiet() {
        return true;
    }

    @Override
    public int step() {
        return total == 0 ? 0 : Math.max(1, Math.min(total, visitsThatCount));
    }

    @Override
    public int total() {
        return total;
    }

    @Override
    public String line() {
        return at == null ? null : at.type().label + ": " + GraphText.summary(at);
    }

    @Override
    public RoutineRunner.Trace trace() {
        return new RoutineRunner.Trace(at == null ? null : at.id(), new ArrayList<>(recent), new HashMap<>(outcomes), new HashMap<>(repeats), new LinkedHashSet<>(taken));
    }
}
