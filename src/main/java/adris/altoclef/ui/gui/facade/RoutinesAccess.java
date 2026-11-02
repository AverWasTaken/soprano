package adris.altoclef.ui.gui.facade;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    // GOTO "x" "y" "z", or "waypoint" (a name), or "block" (an id). no dimension: crossing one is a Raw step.
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

    // ---- graphs, for the node editor. experimentalRoutineGraphs only hides the editor: graphsEnabled says whether to show
    // it, and loadGraph and saveGraph answer "not here" with it off. saved graphs still list, run and delete either way ----

    // type = start get equip goto mine craft eat follow raw sub if repeat wait stop. x and y are design px. params hold what NodeSpec's fields ask
    // for (strings, half typed numbers are fine), cond is the If's or the until's condition, null when there's none
    record GraphNode(String id, String type, int x, int y, Map<String, String> params, CondTree cond) {
    }

    // toPort = "in", or "again" for a wire from a Repeat's own body back into it
    record GraphEdge(String from, String port, String to, String toPort) {
    }

    // op = all any not leaf. a leaf has kind + args (what its CondSpec asks for), the rest have children. not has one
    record CondTree(String op, String kind, Map<String, String> args, List<CondTree> children) {
    }

    // id = null for one that was never saved. the id comes from the name, like a list routine's
    record Graph(String id, String name, String description, List<GraphNode> nodes, List<GraphEdge> edges) {
    }

    // one file in routines/. error set = the file is bad and read only, a person has to fix it
    record GraphEntry(String id, String name, String description, boolean straight, String error) {
    }

    // saved with error set = it's in, but something after it didn't go through (the note says what). id = what it's called now
    record GraphSaveResult(boolean ok, String error, String id) {
    }

    record NodeCheck(String error, String warning) {
    }

    // errors = about the whole graph. nodes has every node in it. ok = it may run
    record GraphCheck(boolean ok, List<String> errors, Map<String, NodeCheck> nodes) {
    }

    // kind = INT COORD CHOICE OP ITEM BLOCK MOB PLAYER ROUTINE LINE SECONDS COND. PLAYER is a name, ROUTINE is an id out of
    // list() or listGraphs(). whenMode = only asked for in that mode, null = always.
    // a LINE opens in the same boxes as the list view's rows: fieldsOf / lineFor with the node's step type
    record FieldSpec(String key, String label, String kind, boolean required, int min, int max, List<String> choices, String whenMode) {
    }

    // group = act flow end. outputs = every port it can have, outputsOf says which ones this node has right now
    record NodeSpec(String type, String group, String label, List<FieldSpec> fields, List<String> inputs, List<String> outputs) {
    }

    record CondSpec(String kind, String label, List<FieldSpec> fields) {
    }

    // a named shortcut for the condition picker: picking it makes a leaf of that kind with these args
    record CondPreset(String label, String kind, Map<String, String> args) {
    }

    // what the cards show. activeNode = running now, null once it's over. failedNode = where a failed run stopped.
    // recent = the last 32 nodes it went through. outcomes = node -> how it last came out (OK FAILED TIMEOUT YES NO; a
    // repeat is YES for another lap and NO for done). repeats = laps per repeat. taken = wires that ran, "from:port:to:toPort"
    record GraphRunStatus(String id, RunState state, String message, String activeNode, String failedNode, List<String> recent,
                          Map<String, String> outcomes, Map<String, Integer> repeats, Set<String> taken) {
    }

    GraphRunStatus IDLE_GRAPH_STATUS = new GraphRunStatus(null, RunState.IDLE, null, null, null, List.of(), Map.of(), Map.of(), Set.of());

    default boolean graphsEnabled() {
        return false;
    }

    default List<GraphEntry> listGraphs() {
        return List.of();
    }

    // a graph file, or a list routine as a straight chain (saving that with its id as previousId is the upgrade).
    // null when there's neither
    default Graph loadGraph(String id) {
        return null;
    }

    // previousId = the id it was opened under, null for a new one. a half built graph saves fine, it just can't run
    default GraphSaveResult saveGraph(Graph graph, String previousId) {
        return new GraphSaveResult(false, "not available here", null);
    }

    default boolean deleteGraph(String id) {
        return false;
    }

    default GraphCheck validateGraph(Graph graph) {
        return new GraphCheck(false, List.of("not available here"), Map.of());
    }

    // the steps of a straight graph, null when the list view can't show it
    default List<Step> stepsOf(Graph graph) {
        return null;
    }

    default List<NodeSpec> nodeSpecs() {
        return List.of();
    }

    default List<CondSpec> condSpecs() {
        return List.of();
    }

    default List<CondPreset> condPresets() {
        return List.of();
    }

    default List<String> outputsOf(GraphNode node) {
        return List.of();
    }

    // the nodes inside a repeat. a wire from one of these into the repeat is toPort "again", the mock draws only one input
    default Set<String> bodyOf(Graph graph, String repeatId) {
        return Set.of();
    }

    // the line a card shows
    default String summaryOf(GraphNode node) {
        return "";
    }

    // the saved one, run saves first. a second run replaces the first one, stop() and status() work on it like any run
    default void runGraph(String id) {
    }

    default GraphRunStatus graphStatus() {
        return IDLE_GRAPH_STATUS;
    }

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
