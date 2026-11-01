package adris.altoclef.ui.gui.preview;

import adris.altoclef.commands.RoutineLines;
import adris.altoclef.commands.RoutineStep;
import adris.altoclef.ui.gui.facade.RoutinesAccess;

import java.util.ArrayList;
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
        }
    }

    @Override
    public RunStatus status() {
        return status;
    }
}
