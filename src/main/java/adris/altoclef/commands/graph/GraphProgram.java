package adris.altoclef.commands.graph;

import adris.altoclef.LinearProgram;
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
import java.util.List;
import java.util.Locale;
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

    // what a Run routine node can call. read when the node is reached and not up front, so an edit in the tab counts from
    // the next call on
    public interface Library {
        // null when there's no such routine
        Callee find(String id);

        Library NONE = id -> null;
    }

    // a graph, a list's steps, or why it can't run
    public record Callee(RoutineGraph graph, List<RoutineStep> steps, String error) {
        public static Callee of(RoutineGraph graph) {
            return new Callee(graph, null, null);
        }

        public static Callee of(List<RoutineStep> steps) {
            return new Callee(null, steps, null);
        }

        public static Callee bad(String why) {
            return new Callee(null, null, why);
        }
    }

    private final RoutineGraph graph;
    private final Conditions.World world;
    private final GraphValidator.Checks checks;
    private final Consumer<String> log;
    private final Library library;
    // the routines up the chain, this one included
    private final Set<String> callers;
    private final Map<String, RoutineGraph.Node> nodes;
    private final Map<String, RoutineGraph.Edge> wires = new HashMap<>();
    private final int total;

    private RoutineGraph.Node at;
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final Map<String, String> outcomes = new LinkedHashMap<>();
    private final Map<String, Integer> repeats = new HashMap<>();
    private final Set<String> taken = new LinkedHashSet<>();
    private int visitsThatCount;
    private boolean lastActionFailed;
    // the repeats we're inside right now, innermost first. a body can leave on any wire, so this gets worked out from where
    // the walk is and not from who said done
    private final ArrayDeque<String> loops = new ArrayDeque<>();
    private final Map<String, Set<String>> bodies = new HashMap<>();
    // the routine a Run routine node is in the middle of. everything goes to it until it ends. its node ids can't clash
    // with ours: only it ever gets to see them
    private RoutineRunner.Program callee;
    private String calleeNode;

    private final Conditions.RunFacts facts = new Conditions.RunFacts() {
        @Override
        public boolean lastActionFailed() {
            return lastActionFailed;
        }

        @Override
        public int laps() {
            String top = loops.peek();
            return top == null ? Conditions.RunFacts.NONE.laps() : repeats.getOrDefault(top, 0);
        }
    };

    public GraphProgram(RoutineGraph graph, Conditions.World world, GraphValidator.Checks checks, Consumer<String> log) {
        this(graph, world, checks, log, Library.NONE);
    }

    public GraphProgram(RoutineGraph graph, Conditions.World world, GraphValidator.Checks checks, Consumer<String> log, Library library) {
        this(graph, world, checks, log, library, Set.of());
    }

    private GraphProgram(RoutineGraph graph, Conditions.World world, GraphValidator.Checks checks, Consumer<String> log, Library library, Set<String> above) {
        this.graph = graph;
        this.world = world;
        this.checks = checks;
        this.log = log;
        this.library = library;
        this.callers = new LinkedHashSet<>(above);
        this.callers.add(key(graph.id()));
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
        if (callee == null) {
            return walk(node, result);
        }
        Op op = callee.next(node, result);
        if (!(op instanceof Op.End end)) {
            return op;
        }
        String back = calleeNode;
        callee = null;
        calleeNode = null;
        return walk(back, resultOf(end));
    }

    // a Stop in there ends that routine and not the whole run, that's what the ok and failed ports are for
    private static Result resultOf(Op.End end) {
        return end.state() == RoutineRunner.State.DONE ? Result.OK : Result.failed(end.message());
    }

    private Op walk(String node, Result result) {
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
            case FAILED -> n.type().acts ? RoutineGraph.FAILED : null;
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
        trackLoops(n);
        return switch (n.type()) {
            case START -> new Op.Inline(n.id(), () -> Result.OK, false);
            case GET, EQUIP, GOTO, RAW -> new Op.Step(n.id(), new RoutineStep(n.type().action, RoutineLines.normalize(n.param("line"), null)));
            case MINE -> new Op.Act(n.id(), new GraphAction.Mine(n.param("block").trim(), number(n, "n")));
            case CRAFT -> new Op.Act(n.id(), new GraphAction.Craft(n.param("item").trim(), number(n, "n")));
            case EAT -> new Op.Act(n.id(), new GraphAction.Eat(n.param("hunger").isBlank() ? 20 : number(n, "hunger")));
            case FOLLOW -> new Op.Act(n.id(), new GraphAction.Follow(n.param("player").trim(), number(n, "secs")));
            case SUB -> call(n);
            case IF -> new Op.Inline(n.id(), () -> ask(n), false);
            // a back wire always costs a tick: a loop of quick commands would freeze the game on one otherwise
            case REPEAT -> new Op.Inline(n.id(), () -> repeat(n, again), again);
            case WAIT -> waitOp(n);
            case STOP -> stop(n);
        };
    }

    private static int number(RoutineGraph.Node n, String key) {
        return Integer.parseInt(n.param(key).trim());
    }

    // the walk stepped onto n: every repeat it isn't inside of anymore is over. coming back to a repeat through again finds
    // it still on top, an inner one that got skipped on the way falls off here
    private void trackLoops(RoutineGraph.Node n) {
        while (!loops.isEmpty() && !loops.peek().equals(n.id()) && !bodies.computeIfAbsent(loops.peek(), r -> GraphValidator.bodyOf(graph, r)).contains(n.id())) {
            loops.pop();
        }
        if (n.type() == RoutineGraph.Type.REPEAT && !n.id().equals(loops.peek())) {
            loops.push(n.id());
        }
    }

    private Op call(RoutineGraph.Node n) {
        String id = n.param("routine").trim();
        if (callers.contains(key(id))) {
            return failedNow(n, id + " is already running, a routine can't run itself");
        }
        if (callers.size() >= GraphSpecs.MAX_CALL_DEPTH) {
            return failedNow(n, "routines only go " + GraphSpecs.MAX_CALL_DEPTH + " deep");
        }
        Callee found = library.find(id);
        if (found == null) {
            return failedNow(n, "there's no routine called " + id);
        }
        if (found.error() != null) {
            return failedNow(n, id + ": " + found.error());
        }
        RoutineRunner.Program program = found.graph() != null
                ? new GraphProgram(found.graph(), world, checks, log, library, callers)
                : new LinearProgram(found.steps());
        String why = program.precheck(step -> RoutineLines.problem(step, checks));
        if (why != null) {
            return failedNow(n, id + ": " + why);
        }
        Op first = program.first();
        if (first instanceof Op.End end) {
            return new Op.Inline(n.id(), () -> resultOf(end), false);
        }
        callee = program;
        calleeNode = n.id();
        return first;
    }

    private static Op failedNow(RoutineGraph.Node n, String why) {
        return new Op.Inline(n.id(), () -> Result.failed(why), false);
    }

    private static String key(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT);
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
                return Conditions.eval(n.cond(), world, facts) ? Result.OK : null;
            } catch (RuntimeException e) {
                return Result.failed(e.getMessage());
            }
        };
        return new Op.WaitUntil(n.id(), poll, Double.parseDouble(n.param("timeout").trim()), Conditions.text(n.cond()));
    }

    private Result ask(RoutineGraph.Node n) {
        try {
            world.freshen();
            return Conditions.eval(n.cond(), world, facts) ? Result.YES : Result.NO;
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
        if (n.type().acts) {
            lastActionFailed = r.kind() == Result.Kind.FAILED;
        }
        String before = outcomes.put(n.id(), r.kind().name());
        if (!r.kind().name().equals(before) && n.type() != RoutineGraph.Type.START) {
            log.accept("routine " + graph.id() + ": " + n.id() + " (" + n.type().wire() + ") " + r.kind() + (r.message() == null ? "" : ": " + r.message()));
        }
    }

    private static boolean counts(RoutineGraph.Node n) {
        return n.type().acts || n.type() == RoutineGraph.Type.WAIT;
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
        if (at == null) {
            return null;
        }
        String here = at.type().label + ": " + GraphText.summary(at);
        return callee == null ? here : here + " > " + callee.line();
    }

    @Override
    public RoutineRunner.Trace trace() {
        return new RoutineRunner.Trace(at == null ? null : at.id(), new ArrayList<>(recent), new HashMap<>(outcomes), new HashMap<>(repeats), new LinkedHashSet<>(taken));
    }
}
