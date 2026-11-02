package adris.altoclef.ui.gui.mc;

import adris.altoclef.RoutineRunner;
import adris.altoclef.commands.CommandsStore;
import adris.altoclef.commands.GameChecks;
import adris.altoclef.commands.RoutineLines;
import adris.altoclef.commands.RoutineStep;
import adris.altoclef.commands.StoredRoutine;
import adris.altoclef.commands.graph.GameConditions;
import adris.altoclef.commands.graph.GraphStore;
import adris.altoclef.commands.graph.GraphText;
import adris.altoclef.commands.graph.GraphValidator;
import adris.altoclef.commands.graph.RoutineGraph;
import adris.altoclef.ui.gui.facade.RoutinesAccess;
import adris.altoclef.ui.gui.kit.Safe;
import baritone.api.BaritoneAPI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// the commands tab's side of CommandsStore and RoutineRunner. holds nothing itself: the screen makes a new one of these
// whenever it rebuilds, and the file and the run have to be the same ones every time
final class McRoutines implements RoutinesAccess {

    @Override
    public StoreState storeState() {
        try {
            CommandsStore.Snapshot s = CommandsStore.get().snapshot();
            return new StoreState(s.ok(), s.error());
        } catch (Throwable t) {
            Safe.report("routines state", t);
            return new StoreState(false, "couldn't read CustomTasks.json, the log has the details");
        }
    }

    @Override
    public List<Routine> list() {
        List<Routine> out = new ArrayList<>();
        try {
            for (StoredRoutine r : CommandsStore.get().list()) {
                out.add(toFacade(r));
            }
        } catch (Throwable t) {
            Safe.report("routines list", t);
        }
        return out;
    }

    @Override
    public SaveResult save(Routine routine) {
        try {
            if (routine == null) {
                return new SaveResult(false, "nothing to save");
            }
            List<RoutineStep> steps = new ArrayList<>();
            List<Step> in = routine.steps() == null ? List.of() : routine.steps();
            for (Step s : in) {
                // the tab may hand back a row with nothing picked yet, the store turns it down with the row's number
                steps.add(new RoutineStep(s == null ? null : kind(s.type()), s == null ? null : normalize(s.line())));
            }
            CommandsStore.Result r = CommandsStore.get().save(new StoredRoutine(routine.id(), routine.name(), routine.description(), steps), routine.id());
            return new SaveResult(r.ok(), r.error());
        } catch (Throwable t) {
            Safe.report("routines save", t);
            return new SaveResult(false, "saving broke, the log has the details");
        }
    }

    @Override
    public boolean delete(String id) {
        try {
            return CommandsStore.get().delete(id);
        } catch (Throwable t) {
            Safe.report("routines delete", t);
            return false;
        }
    }

    @Override
    public List<String> validate(List<Step> steps) {
        List<String> out = new ArrayList<>();
        if (steps == null) {
            return out;
        }
        for (Step s : steps) {
            try {
                out.add(s == null || s.type() == null ? "this step has no type"
                        : RoutineLines.problem(new RoutineStep(kind(s.type()), normalize(s.line())), GameChecks.INSTANCE));
            } catch (Throwable t) {
                Safe.report("routines validate", t);
                out.add(null);
            }
        }
        return out;
    }

    @Override
    public String lineFor(StepType type, Map<String, String> fields) {
        try {
            return RoutineLines.lineFor(kind(type), fields);
        } catch (Throwable t) {
            Safe.report("routines line", t);
            return "";
        }
    }

    @Override
    public Map<String, String> fieldsOf(Step step) {
        try {
            return step == null ? Map.of() : RoutineLines.fieldsOf(new RoutineStep(kind(step.type()), step.line()));
        } catch (Throwable t) {
            Safe.report("routines fields", t);
            return Map.of();
        }
    }

    @Override
    public void run(String id) {
        try {
            StoredRoutine r = id == null ? null : CommandsStore.get().find(id);
            if (r == null) {
                return;
            }
            RoutineRunner.get().start(r.id(), r.name(), r.steps());
        } catch (Throwable t) {
            Safe.report("routines run", t);
        }
    }

    @Override
    public void stop() {
        try {
            RoutineRunner.get().cancel("stopped");
            // the stock stop, so the button does what #stop does: altoclef's task, baritone's path, every ";" line
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
        } catch (Throwable t) {
            Safe.report("routines stop", t);
        }
    }

    @Override
    public RunStatus status() {
        try {
            RoutineRunner.Status s = RoutineRunner.get().status();
            return new RunStatus(s.id(), s.name(), s.step(), s.total(), s.line(), RunState.valueOf(s.state().name()), s.message());
        } catch (Throwable t) {
            Safe.report("routines status", t);
            return IDLE_STATUS;
        }
    }

    @Override
    public boolean graphsEnabled() {
        return GraphStore.enabled();
    }

    @Override
    public List<GraphEntry> listGraphs() {
        List<GraphEntry> out = new ArrayList<>();
        if (!GraphStore.enabled()) {
            return out;
        }
        try {
            for (GraphStore.Entry e : GraphStore.get().list()) {
                RoutineGraph g = e.graph();
                out.add(g == null ? new GraphEntry(e.id(), e.id(), "", false, e.error())
                        : new GraphEntry(e.id(), g.name(), g.description(), g.toSteps() != null, null));
            }
        } catch (Throwable t) {
            Safe.report("graphs list", t);
        }
        return out;
    }

    @Override
    public Graph loadGraph(String id) {
        if (id == null || !GraphStore.enabled()) {
            return null;
        }
        try {
            GraphStore.Entry e = GraphStore.get().find(id);
            if (e != null) {
                return e.graph() == null ? null : GraphMapping.toFacade(e.graph());
            }
            StoredRoutine r = CommandsStore.get().find(id);
            return r == null ? null : GraphMapping.toFacade(RoutineGraph.ofSteps(r.id(), r.name(), r.description(), r.steps()));
        } catch (Throwable t) {
            Safe.report("graphs load", t);
            return null;
        }
    }

    @Override
    public GraphSaveResult saveGraph(Graph graph, String previousId) {
        if (!GraphStore.enabled()) {
            return new GraphSaveResult(false, "routine graphs are off", null);
        }
        try {
            GraphStore.Result r = GraphStore.get().save(GraphMapping.fromFacade(graph), previousId);
            return new GraphSaveResult(r.ok(), r.error(), r.id());
        } catch (Throwable t) {
            Safe.report("graphs save", t);
            return new GraphSaveResult(false, "saving broke, the log has the details", null);
        }
    }

    @Override
    public boolean deleteGraph(String id) {
        try {
            return GraphStore.enabled() && GraphStore.get().delete(id);
        } catch (Throwable t) {
            Safe.report("graphs delete", t);
            return false;
        }
    }

    @Override
    public GraphCheck validateGraph(Graph graph) {
        try {
            GraphValidator.Result v = GraphValidator.validate(GraphMapping.fromFacade(graph), GameChecks.INSTANCE);
            Map<String, NodeCheck> nodes = new LinkedHashMap<>();
            v.nodes().forEach((id, p) -> nodes.put(id, new NodeCheck(p.error(), p.warning())));
            return new GraphCheck(v.ok(), List.copyOf(v.errors()), nodes);
        } catch (Throwable t) {
            Safe.report("graphs validate", t);
            return new GraphCheck(false, List.of("checking broke, the log has the details"), Map.of());
        }
    }

    @Override
    public List<Step> stepsOf(Graph graph) {
        try {
            List<RoutineStep> steps = GraphMapping.fromFacade(graph).toSteps();
            if (steps == null) {
                return null;
            }
            List<Step> out = new ArrayList<>();
            for (RoutineStep s : steps) {
                out.add(new Step(StepType.valueOf(s.kind().name()), s.line()));
            }
            return out;
        } catch (Throwable t) {
            Safe.report("graphs steps", t);
            return null;
        }
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
    public List<String> outputsOf(GraphNode node) {
        try {
            return RoutineGraph.outputs(GraphMapping.fromFacade(node));
        } catch (Throwable t) {
            Safe.report("graphs ports", t);
            return List.of();
        }
    }

    @Override
    public Set<String> bodyOf(Graph graph, String repeatId) {
        try {
            return GraphValidator.bodyOf(GraphMapping.fromFacade(graph), repeatId);
        } catch (Throwable t) {
            Safe.report("graphs body", t);
            return Set.of();
        }
    }

    @Override
    public String summaryOf(GraphNode node) {
        try {
            return GraphText.summary(GraphMapping.fromFacade(node));
        } catch (Throwable t) {
            Safe.report("graphs summary", t);
            return "";
        }
    }

    @Override
    public void runGraph(String id) {
        if (id == null || !GraphStore.enabled()) {
            return;
        }
        try {
            GraphStore.Entry e = GraphStore.get().find(id);
            if (e != null && e.graph() != null) {
                RoutineRunner.get().start(e.id(), e.graph().name(), GameConditions.program(e.graph()));
            }
        } catch (Throwable t) {
            Safe.report("graphs run", t);
        }
    }

    @Override
    public GraphRunStatus graphStatus() {
        try {
            RoutineRunner runner = RoutineRunner.get();
            RoutineRunner.Trace trace = runner.trace();
            if (trace == null) {
                return IDLE_GRAPH_STATUS;
            }
            RoutineRunner.Status s = runner.status();
            boolean running = s.state() == RoutineRunner.State.RUNNING;
            boolean failed = s.state() == RoutineRunner.State.FAILED;
            return new GraphRunStatus(s.id(), RunState.valueOf(s.state().name()), s.message(), running ? trace.at() : null, failed ? trace.at() : null,
                    trace.recent(), trace.outcomes(), trace.repeats(), trace.taken());
        } catch (Throwable t) {
            Safe.report("graphs status", t);
            return IDLE_GRAPH_STATUS;
        }
    }

    private static Routine toFacade(StoredRoutine r) {
        List<Step> steps = new ArrayList<>();
        for (RoutineStep s : r.steps()) {
            steps.add(new Step(StepType.valueOf(s.kind().name()), s.line()));
        }
        return new Routine(r.id(), r.name(), r.description(), List.copyOf(steps));
    }

    private static RoutineStep.Kind kind(StepType type) {
        return type == null ? null : RoutineStep.Kind.valueOf(type.name());
    }

    private static String normalize(String line) {
        return RoutineLines.normalize(line, GameChecks.prefix());
    }
}
