package adris.altoclef.ui.gui.mc;

import adris.altoclef.RoutineRunner;
import adris.altoclef.commands.CommandsStore;
import adris.altoclef.commands.GameChecks;
import adris.altoclef.commands.RoutineLines;
import adris.altoclef.commands.RoutineStep;
import adris.altoclef.commands.StoredRoutine;
import adris.altoclef.ui.gui.facade.RoutinesAccess;
import adris.altoclef.ui.gui.kit.Safe;
import baritone.api.BaritoneAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
