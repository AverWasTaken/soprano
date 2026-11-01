package adris.altoclef.ui.gui.facade;

import java.util.List;
import java.util.Map;

// the commands tab: saved routines (CustomTasks.json, the same ones #custom runs) and the one run that can go at a time.
// the run lives in the game and not in the screen, so closing the gui doesn't stop it and reopening shows it again
public interface RoutinesAccess {

    enum StepType {
        GET,
        EQUIP,
        GOTO,
        WAIT,
        RAW
    }

    // line = the full command line without the prefix, e.g. "get diamond_sword 1"
    record Step(StepType type, String line) {
    }

    // id is the one word #custom takes, name is what the list shows (spaces ok). a new routine has a null id
    record Routine(String id, String name, String description, List<Step> steps) {
    }

    // !ok -> show the error, editing off (a bad CustomTasks.json is left alone until a person fixes it)
    record StoreState(boolean ok, String error) {
    }

    record SaveResult(boolean ok, String error) {
    }

    enum RunState {
        IDLE,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED,
        REPLACED
    }

    // step is 1-based ("step 2/5"). once a run is over it keeps the step it ended on and says why in message
    record RunStatus(String id, String name, int step, int total, String line, RunState state, String message) {
    }

    StoreState storeState();

    List<Routine> list();

    // the id comes from the name. a routine whose name changed is a rename: new id, same spot, the old id gone
    SaveResult save(Routine routine);

    boolean delete(String id);

    // one entry per step, null when that row is fine, else what's wrong with it
    List<String> validate(List<Step> steps);

    // the editor's boxes -> a row's line. keys per type:
    // GET "items" ("diamond_sword 1, diamond_chestplate 1"), EQUIP "item", WAIT "seconds", RAW "line",
    // GOTO "x" "y" "z" (+ "dimension": overworld / nether / the_end), or "waypoint" (a name), or "block" (an id).
    // waypoint wins over block wins over the coordinates. a missing x or z with the others there is ~ (where you stand)
    String lineFor(StepType type, Map<String, String> fields);

    // the reverse, so a saved row opens in its boxes
    Map<String, String> fieldsOf(Step step);

    // a second run replaces the first one
    void run(String id);

    // the stock stop: ends the run and whatever the bot was doing
    void stop();

    RunStatus status();

    RunStatus IDLE_STATUS = new RunStatus(null, null, 0, 0, null, RunState.IDLE, null);

    RoutinesAccess NONE = new RoutinesAccess() {
        @Override
        public StoreState storeState() {
            return new StoreState(false, "not available here");
        }

        @Override
        public List<Routine> list() {
            return List.of();
        }

        @Override
        public SaveResult save(Routine routine) {
            return new SaveResult(false, "not available here");
        }

        @Override
        public boolean delete(String id) {
            return false;
        }

        @Override
        public List<String> validate(List<Step> steps) {
            return steps == null ? List.of() : java.util.Collections.nCopies(steps.size(), null);
        }

        @Override
        public String lineFor(StepType type, Map<String, String> fields) {
            return "";
        }

        @Override
        public Map<String, String> fieldsOf(Step step) {
            return Map.of();
        }

        @Override
        public void run(String id) {
        }

        @Override
        public void stop() {
        }

        @Override
        public RunStatus status() {
            return IDLE_STATUS;
        }
    };
}
