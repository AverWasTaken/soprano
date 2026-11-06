package adris.altoclef.ui.gui.preview;

import adris.altoclef.commands.RoutineLines;
import adris.altoclef.commands.RoutineStep;
import adris.altoclef.commands.graph.GraphText;
import adris.altoclef.commands.graph.GraphValidator;
import adris.altoclef.commands.graph.RoutineGraph;
import adris.altoclef.ui.gui.facade.RoutinesAccess;
import adris.altoclef.ui.gui.mc.GraphMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// the commands tab's fake: routines in a list, the real line rules from RoutineLines (so the red rows are the ones
// the game would draw), a game that knows a handful of items, and a run that only moves when a test says so
public final class FakeRoutines implements RoutinesAccess {

    // everything else is a real item, this is the one the fake game has never heard of
    public static final String UNKNOWN = "unobtainium";

    private final List<String> actions;
    public final List<Routine> list = new ArrayList<>();
    public StoreState store = new StoreState(true, null);
    public RunStatus status = IDLE_STATUS;
    public boolean failNextSave;
    // set it and the next run fails on its first step before anybody sees it running
    public String failOnRun;
    // the graph side. the setting, then the files. the rules are the real validator and the real specs, through the real
    // mapping, so a card that goes red here is one that goes red in the game
    public boolean graphsOn;
    public final Map<String, Graph> graphFiles = new LinkedHashMap<>();
    public final Map<String, String> badFiles = new LinkedHashMap<>();
    public GraphRunStatus graphStatus = IDLE_GRAPH_STATUS;

    FakeRoutines(List<String> actions) {
        this.actions = actions;
    }

    private static final RoutineLines.Checks CHECKS = new RoutineLines.Checks() {
        @Override
        public boolean commandExists(String label) {
            return !Set.of("dance").contains(label);
        }

        @Override
        public String itemProblem(RoutineStep.Kind kind, String name) {
            return name.equals(UNKNOWN) ? "there is no item called " + name : null;
        }

        @Override
        public String blockProblem(String name) {
            return name.equals(UNKNOWN) ? "there is no block called " + name : null;
        }
    };

    // the routines the shots use: a gear run, a nether trip with every step type, and one with a typo in it
    public FakeRoutines seed() {
        list.clear();
        list.add(new Routine("Diamond_gear", "Diamond gear", "Full diamond kit", List.of(
                new Step(StepType.GET, "get diamond_chestplate, diamond_leggings, diamond_helmet, diamond_boots, diamond_sword"),
                new Step(StepType.EQUIP, "equip diamond"))));
        list.add(new Routine("Nether_trip", "Nether trip", "Gear up, then go through the portal", List.of(
                new Step(StepType.GET, "get iron_sword, bread 16"),
                new Step(StepType.GET, "get diamond_sword"),
                new Step(StepType.GOTO, "wp goto home"),
                new Step(StepType.WAIT, "wait 5"),
                new Step(StepType.RAW, "goto 0 70 0 nether"))));
        list.add(new Routine("Base_trip", "Base trip", "", List.of(
                new Step(StepType.GOTO, "goto 118 64 -32"),
                new Step(StepType.GET, "get " + UNKNOWN + " 2"),
                new Step(StepType.GOTO, "goto crafting_table"))));
        return this;
    }

    private final GraphValidator.Checks graphChecks = new GraphValidator.Checks() {
        @Override
        public boolean commandExists(String label) {
            return CHECKS.commandExists(label);
        }

        @Override
        public String itemProblem(RoutineStep.Kind kind, String name) {
            return CHECKS.itemProblem(kind, name);
        }

        @Override
        public String blockProblem(String name) {
            return CHECKS.blockProblem(name);
        }

        @Override
        public String idProblem(GraphValidator.IdKind kind, String id) {
            return id.equals(UNKNOWN) ? "there is nothing called " + id : null;
        }

        @Override
        public String routineProblem(String id) {
            return routineExists(id) ? null : "there is no routine called " + id;
        }
    };

    private boolean routineExists(String id) {
        return list.stream().anyMatch(x -> x.id().equalsIgnoreCase(id)) || fileKey(id) != null;
    }

    private String fileKey(String id) {
        for (String k : graphFiles.keySet()) {
            if (k.equalsIgnoreCase(id)) {
                return k;
            }
        }
        return null;
    }

    private static GraphNode node(String id, String type, int x, int y, Map<String, String> params, CondTree cond) {
        return new GraphNode(id, type, x, y, params, cond);
    }

    private static GraphEdge wire(String from, String port, String to, String toPort) {
        return new GraphEdge(from, port, to, toPort);
    }

    private static CondTree leaf(String kind, Map<String, String> args) {
        return new CondTree("leaf", kind, args, List.of());
    }

    // the shots' graphs: the mock's wood loop and night guard, one with a little of everything wrong, and a bad file
    public FakeRoutines seedGraphs() {
        graphsOn = true;
        graphFiles.clear();
        badFiles.clear();
        CondTree full = new CondTree("any", null, Map.of(), List.of(
                leaf("has_item", Map.of("item", "oak_log", "op", ">=", "value", "64")),
                leaf("free_slots", Map.of("op", "=", "value", "0"))));
        graphFiles.put("Wood_loop", new Graph("Wood_loop", "Wood loop", "Chop oak until there are 64 logs", List.of(
                node("s", "start", 0, 40, Map.of(), null),
                node("r", "repeat", 160, 40, Map.of("mode", "until", "cap", "20", "n", "10"), full),
                node("g", "goto", 384, 40, Map.of("line", "goto oak_log"), null),
                node("m", "mine", 608, 40, Map.of("block", "oak_log", "n", "8"), null),
                node("z", "stop", 384, 176, Map.of("ok", "true"), null)), List.of(
                wire("s", "out", "r", "in"),
                wire("r", "body", "g", "in"),
                wire("g", "ok", "m", "in"),
                wire("m", "ok", "r", "again"),
                wire("r", "done", "z", "in"))));
        graphFiles.put("Night_guard", new Graph("Night_guard", "Night guard", "Home before dark, bread if it is day", List.of(
                node("s", "start", 0, 76, Map.of(), null),
                node("i", "if", 160, 76, Map.of(), leaf("time", Map.of("value", "night"))),
                node("g", "goto", 384, 0, Map.of("line", "wp goto home"), null),
                node("w", "wait", 608, 0, Map.of("mode", "until", "timeout", "600", "secs", "5"), leaf("time", Map.of("value", "day"))),
                node("b", "get", 384, 152, Map.of("line", "get bread 16, oak_log 8, iron_ingot 4"), null),
                node("z", "stop", 832, 76, Map.of("ok", "true"), null)), List.of(
                wire("s", "out", "i", "in"),
                wire("i", "yes", "g", "in"),
                wire("g", "ok", "w", "in"),
                wire("w", "ok", "z", "in"),
                wire("i", "no", "b", "in"),
                wire("b", "ok", "z", "in"))));
        graphFiles.put("Half_built", new Graph("Half_built", "Half built", "", List.of(
                node("s", "start", 0, 0, Map.of(), null),
                node("m", "mine", 160, 0, Map.of("n", "99999"), null),
                node("r", "raw", 384, 0, Map.of("line", "dance"), null),
                node("i", "if", 160, 160, Map.of(), null),
                node("l", "repeat", 384, 160, Map.of("mode", "times", "n", "3"), null)), List.of(
                wire("s", "out", "m", "in"),
                wire("m", "ok", "r", "in"))));
        badFiles.put("Bad_file", "line 3: expected a comma");
        return this;
    }

    // start, then 199 commands in rows of 20, ok to the next one and failed down to the row under it
    public FakeRoutines seedStress() {
        graphsOn = true;
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        nodes.add(node("s", "start", -224, 0, Map.of(), null));
        for (int i = 0; i < 199; i++) {
            nodes.add(node("c" + i, "raw", (i % 20) * 224, (i / 20) * 120, Map.of("line", "farm " + i), null));
            if (i > 0) {
                edges.add(wire("c" + (i - 1), "ok", "c" + i, "in"));
            }
            if (i + 20 < 199) {
                edges.add(wire("c" + i, "failed", "c" + (i + 20), "in"));
            }
        }
        edges.add(wire("s", "out", "c0", "in"));
        graphFiles.put("Stress", new Graph("Stress", "Stress", "Two hundred nodes", nodes, edges));
        return this;
    }

    @Override
    public boolean graphsEnabled() {
        return graphsOn;
    }

    // listed with the setting off too, like the real one: hiding them is the tab's job, and a test has to be able to catch it
    @Override
    public List<GraphEntry> listGraphs() {
        List<GraphEntry> out = new ArrayList<>();
        graphFiles.forEach((id, g) -> out.add(new GraphEntry(id, g.name(), g.description(), stepsOf(g) != null, null)));
        badFiles.forEach((id, why) -> out.add(new GraphEntry(id, id, "", false, why)));
        return out;
    }

    @Override
    public Graph loadGraph(String id) {
        if (id == null || !graphsOn) {
            return null;
        }
        String key = fileKey(id);
        if (key != null) {
            return graphFiles.get(key);
        }
        for (Routine r : list) {
            if (r.id().equalsIgnoreCase(id)) {
                List<RoutineStep> steps = new ArrayList<>();
                r.steps().forEach(s -> steps.add(new RoutineStep(RoutineStep.Kind.valueOf(s.type().name()), s.line())));
                return GraphMapping.toFacade(RoutineGraph.ofSteps(r.id(), r.name(), r.description(), steps));
            }
        }
        return null;
    }

    @Override
    public GraphSaveResult saveGraph(Graph g, String previousId) {
        actions.add("saveGraph " + g.name());
        if (!graphsOn) {
            return new GraphSaveResult(false, "routine graphs are off", null);
        }
        if (failNextSave) {
            failNextSave = false;
            return new GraphSaveResult(false, "couldn't write the file", null);
        }
        String id = RoutineLines.idFor(g.name());
        if (id == null) {
            return new GraphSaveResult(false, "give it a name with a letter or a number in it", null);
        }
        boolean same = previousId != null && previousId.equalsIgnoreCase(id);
        if (!same && routineExists(id)) {
            return new GraphSaveResult(false, "there is already one called " + id, null);
        }
        if (previousId != null) {
            String old = fileKey(previousId);
            if (old != null) {
                graphFiles.remove(old);
            }
            list.removeIf(x -> x.id().equalsIgnoreCase(previousId));
        }
        graphFiles.put(id, new Graph(id, g.name().trim(), g.description().trim(), g.nodes(), g.edges()));
        return new GraphSaveResult(true, null, id);
    }

    @Override
    public boolean deleteGraph(String id) {
        actions.add("deleteGraph " + id);
        String key = fileKey(id);
        if (key != null) {
            graphFiles.remove(key);
            return true;
        }
        return badFiles.remove(id) != null;
    }

    @Override
    public GraphCheck validateGraph(Graph g) {
        GraphValidator.Result v = GraphValidator.validate(GraphMapping.fromFacade(g), graphChecks);
        Map<String, NodeCheck> nodes = new LinkedHashMap<>();
        v.nodes().forEach((id, p) -> nodes.put(id, new NodeCheck(p.error(), p.warning())));
        return new GraphCheck(v.ok(), List.copyOf(v.errors()), nodes);
    }

    @Override
    public List<Step> stepsOf(Graph g) {
        List<RoutineStep> steps = GraphMapping.fromFacade(g).toSteps();
        if (steps == null) {
            return null;
        }
        List<Step> out = new ArrayList<>();
        steps.forEach(s -> out.add(new Step(StepType.valueOf(s.kind().name()), s.line())));
        return out;
    }

    @Override
    public List<NodeSpec> nodeSpecs() {
        return GraphMapping.nodeSpecs();
    }

    @Override
    public List<CondSpec> condSpecs() {
        return GraphMapping.condSpecs();
    }

    @Override
    public List<CondPreset> condPresets() {
        return GraphMapping.condPresets();
    }

    @Override
    public List<String> outputsOf(GraphNode n) {
        return RoutineGraph.outputs(GraphMapping.fromFacade(n));
    }

    @Override
    public Set<String> bodyOf(Graph g, String repeatId) {
        return GraphValidator.bodyOf(GraphMapping.fromFacade(g), repeatId);
    }

    @Override
    public String summaryOf(GraphNode n) {
        return GraphText.summary(GraphMapping.fromFacade(n));
    }

    @Override
    public void runGraph(String id) {
        actions.add("runGraph " + id);
        String key = fileKey(id);
        if (key == null) {
            return;
        }
        Graph g = graphFiles.get(key);
        status = new RunStatus(key, g.name(), 1, g.nodes().size(), "", RunState.RUNNING, null);
        graphStatus = new GraphRunStatus(key, RunState.RUNNING, null, g.nodes().get(0).id(), null, List.of(), Map.of(), Map.of(), Set.of());
    }

    @Override
    public GraphRunStatus graphStatus() {
        return graphStatus;
    }

    @Override
    public StoreState storeState() {
        return store;
    }

    @Override
    public List<Routine> list() {
        // a broken file still lists what it could read, the tab shows it read only
        return List.copyOf(list);
    }

    @Override
    public SaveResult save(Routine r) {
        actions.add("save " + r.name());
        if (!store.ok()) {
            return new SaveResult(false, "CustomTasks.json has an error");
        }
        if (failNextSave) {
            failNextSave = false;
            return new SaveResult(false, "couldn't write CustomTasks.json");
        }
        String id = RoutineLines.idFor(r.name());
        if (id == null) {
            return new SaveResult(false, "give it a name with a letter or a number in it");
        }
        int at = -1;
        for (int i = 0; i < list.size(); i++) {
            String other = list.get(i).id();
            if (r.id() != null && other.equalsIgnoreCase(r.id())) {
                at = i;
            } else if (other.equalsIgnoreCase(id)) {
                return new SaveResult(false, "there is already one called " + other);
            }
        }
        Routine clean = new Routine(id, r.name().trim(), r.description() == null ? "" : r.description().trim(), List.copyOf(r.steps()));
        if (at >= 0) {
            list.set(at, clean);
        } else {
            list.add(clean);
        }
        return new SaveResult(true, null);
    }

    @Override
    public boolean delete(String id) {
        actions.add("delete " + id);
        return store.ok() && list.removeIf(x -> x.id().equalsIgnoreCase(id));
    }

    @Override
    public List<String> validate(List<Step> steps) {
        List<String> out = new ArrayList<>();
        for (Step s : steps) {
            RoutineStep.Kind kind = s == null || s.type() == null ? null : RoutineStep.Kind.valueOf(s.type().name());
            out.add(RoutineLines.problem(new RoutineStep(kind, s == null ? null : RoutineLines.normalize(s.line(), null)), CHECKS));
        }
        return out;
    }

    @Override
    public String lineFor(StepType type, Map<String, String> fields) {
        return RoutineLines.lineFor(RoutineStep.Kind.valueOf(type.name()), fields);
    }

    @Override
    public Map<String, String> fieldsOf(Step step) {
        return RoutineLines.fieldsOf(new RoutineStep(RoutineStep.Kind.valueOf(step.type().name()), step.line()));
    }

    @Override
    public void run(String id) {
        actions.add("run " + id);
        Routine r = list.stream().filter(x -> x.id().equalsIgnoreCase(id)).findFirst().orElse(null);
        if (r != null && failOnRun != null) {
            status = new RunStatus(r.id(), r.name(), 1, r.steps().size(), r.steps().get(0).line(), RunState.FAILED, failOnRun);
            failOnRun = null;
        } else if (r != null) {
            status = new RunStatus(r.id(), r.name(), 1, r.steps().size(), r.steps().get(0).line(), RunState.RUNNING, null);
        }
    }

    @Override
    public void stop() {
        actions.add("stop");
        if (status.state() == RunState.RUNNING) {
            status = new RunStatus(status.id(), status.name(), status.step(), status.total(), status.line(), RunState.CANCELLED, "stopped");
            if (graphStatus.state() == RunState.RUNNING) {
                graphStatus = new GraphRunStatus(graphStatus.id(), RunState.CANCELLED, "stopped", null, null, graphStatus.recent(),
                        graphStatus.outcomes(), graphStatus.repeats(), graphStatus.taken());
            }
        }
    }

    @Override
    public RunStatus status() {
        return status;
    }
}
