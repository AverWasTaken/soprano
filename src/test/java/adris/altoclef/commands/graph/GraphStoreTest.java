package adris.altoclef.commands.graph;

import static adris.altoclef.commands.graph.GraphTestKit.graph;
import static adris.altoclef.commands.graph.GraphTestKit.hasLogs;
import static adris.altoclef.commands.graph.GraphTestKit.night;
import static adris.altoclef.commands.graph.GraphTestKit.raw;
import static adris.altoclef.commands.graph.GraphTestKit.start;
import static adris.altoclef.commands.graph.GraphTestKit.stop;
import static adris.altoclef.commands.graph.GraphTestKit.times;
import static adris.altoclef.commands.graph.GraphTestKit.until;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import adris.altoclef.commands.RoutineStep;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class GraphStoreTest {

    private Path dir;
    private GraphStore store;
    private final Set<String> linear = new HashSet<>();
    private boolean linearRemoves = true;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("graphs").resolve("routines");
        store = new GraphStore(() -> dir, new GraphStore.Linear() {
            @Override
            public boolean has(String id) {
                return linear.contains(id);
            }

            @Override
            public boolean remove(String id) {
                return linearRemoves && linear.remove(id);
            }
        });
    }

    private static RoutineGraph named(String name) {
        return new RoutineGraph(null, name, "about it", List.of(start(), raw("a", "say hi")),
                List.of(new RoutineGraph.Edge("s", "out", "a", null)));
    }

    private void write(String file, String text) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(file), text);
    }

    @Test
    public void noFolderIsAnEmptyListAndReadingNeverMakesOne() {
        assertTrue(store.list().isEmpty());
        assertNull(store.find("x"));
        assertFalse(Files.exists(dir));
    }

    @Test
    public void everythingSurvivesARoundTrip() throws Exception {
        RoutineGraph g = new RoutineGraph("loop", "Wood loop", "logs, a lot", List.of(
                new RoutineGraph.Node("s", RoutineGraph.Type.START, -20, 30, null, null),
                until("r", Cond.all(hasLogs(64), Cond.not(night())), 20),
                raw("m", "mine oak_log 16"),
                GraphTestKit.waitUntil("w", night(), 300),
                GraphTestKit.waitSecs("q", "2.5"),
                GraphTestKit.iff("i", Cond.any(Cond.leaf("near_pos", Map.of("x", "1", "z", "-2", "r", "5")), hasLogs(1))),
                stop("e", false)),
                List.of(new RoutineGraph.Edge("s", "out", "r", null),
                        new RoutineGraph.Edge("r", "body", "m", "in"),
                        new RoutineGraph.Edge("m", "ok", "r", "again")));
        String text = GraphFile.write(g);
        RoutineGraph back = GraphFile.parse("loop", text);
        assertEquals(g, back);
        // and writing it again is the same bytes, a diff of two saves shows only what changed
        assertEquals(text, GraphFile.write(back));
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        assertEquals(GraphFile.FORMAT, o.get("format").getAsString());
        assertEquals(1, o.get("version").getAsInt());
        assertFalse("the id is the file's name", o.has("id"));
        assertFalse("in is the default", o.getAsJsonArray("edges").get(0).getAsJsonObject().has("toPort"));
        assertEquals("again", o.getAsJsonArray("edges").get(2).getAsJsonObject().get("toPort").getAsString());
    }

    @Test
    public void aHalfBuiltGraphSavesAndComesBack() {
        RoutineGraph half = new RoutineGraph(null, "half", "", List.of(start(), times("r", 0), GraphTestKit.iff("i", Cond.leaf("", Map.of("item", ""))), GraphTestKit.iff("j", Cond.not(Cond.all()))),
                List.of(new RoutineGraph.Edge("s", "nope", "ghost", null)));
        assertFalse(GraphValidator.validate(half, GraphValidator.Checks.SHAPE_ONLY).ok());
        GraphStore.Result r = store.save(half, null);
        assertTrue(r.error(), r.ok());
        assertNotNull(store.find("half").graph());
        assertEquals(4, store.find("half").graph().nodes().size());
    }

    @Test
    public void aStrangeNumberInAHandEditReads() throws Exception {
        RoutineGraph g = GraphFile.parse("x", """
                {"format": "soprano-routine-graph", "version": 1, "nodes": [
                  {"id": "r", "type": "repeat", "x": 12.5, "params": {"mode": "times", "n": 5}}
                ], "edges": []}
                """);
        assertEquals("5", g.nodes().get(0).param("n"));
        assertEquals(13, g.nodes().get(0).x());
        assertEquals("", g.name());
    }

    @Test
    public void badFilesSayWhy() {
        String ok = "\"format\": \"soprano-routine-graph\", \"version\": 1";
        assertBad("{", "json");
        assertBad("[]", "object");
        assertBad("{\"format\": \"other\", \"version\": 1, \"nodes\": [], \"edges\": []}", "soprano-routine-graph");
        assertBad("{\"format\": \"soprano-routine-graph\", \"version\": 2, \"nodes\": [], \"edges\": []}", "newer");
        assertBad("{\"format\": \"soprano-routine-graph\", \"nodes\": [], \"edges\": []}", "version");
        assertBad("{" + ok + ", \"edges\": []}", "nodes");
        assertBad("{" + ok + ", \"nodes\": [{\"id\": \"a\", \"type\": \"teleport\"}], \"edges\": []}", "teleport");
        assertBad("{" + ok + ", \"nodes\": [{\"type\": \"raw\"}], \"edges\": []}", "no id");
        assertBad("{" + ok + ", \"nodes\": [{\"id\": \"a\", \"type\": \"raw\"}, {\"id\": \"a\", \"type\": \"raw\"}], \"edges\": []}", "two nodes");
        assertBad("{" + ok + ", \"nodes\": [], \"edges\": [{\"from\": \"a\"}]}", "wire");
        assertBad("{" + ok + ", \"nodes\": [{\"id\": \"a\", \"type\": \"if\", \"cond\": {\"maybe\": 1}}], \"edges\": []}", "condition");
    }

    private static void assertBad(String text, String says) {
        GraphFile.BadFile e = assertThrows(GraphFile.BadFile.class, () -> GraphFile.parse("x", text));
        assertTrue(e.getMessage(), e.getMessage().contains(says));
    }

    @Test
    public void saveMakesTheFileAndTheIdComesFromTheName() throws Exception {
        GraphStore.Result r = store.save(named("Wood  Loop"), null);
        assertTrue(r.error(), r.ok());
        assertEquals("Wood_Loop", r.id());
        assertTrue(Files.exists(dir.resolve("Wood_Loop.json")));
        assertEquals("Wood Loop", store.find("wood_loop").graph().name());
        assertEquals("Wood_Loop", store.find("wood_loop").graph().id());
        try (var files = Files.list(dir)) {
            assertEquals("no temp files left", 1, files.count());
        }
    }

    @Test
    public void namesTheFolderCantHoldAreTurnedDown() {
        assertFalse(store.save(named("!!!"), null).ok());
        assertFalse(store.save(named("NUL"), null).ok());
        assertFalse(store.save(named("com1"), null).ok());
    }

    @Test
    public void idsAreUniqueAcrossBothStores() {
        assertTrue(store.save(named("a"), null).ok());
        assertTrue(store.save(named("A"), null).error().contains("already"));
        linear.add("wood");
        assertTrue(store.save(named("wood"), null).error().contains("already"));
        assertTrue(store.save(named("wood"), "a").error().contains("already"));
    }

    @Test
    public void aRenameMovesTheFile() {
        assertTrue(store.save(named("old"), null).ok());
        GraphStore.Result r = store.save(named("new"), "old");
        assertTrue(r.error(), r.ok());
        assertNull(store.find("old"));
        assertNotNull(store.find("new"));
    }

    @Test
    public void aCaseOnlyRenameKeepsTheFile() throws Exception {
        assertTrue(store.save(named("wood"), null).ok());
        GraphStore.Result r = store.save(named("Wood"), "wood");
        assertTrue(r.error(), r.ok());
        assertEquals("wood", r.id());
        assertEquals("Wood", store.find("wood").graph().name());
        assertTrue(Files.exists(dir.resolve("wood.json")));
    }

    @Test
    public void anUpgradeWritesTheGraphThenDropsTheListEntry() {
        linear.add("gear");
        GraphStore.Result r = store.save(named("gear"), "gear");
        assertTrue(r.error(), r.ok());
        assertNull(r.error());
        assertFalse(linear.contains("gear"));
        assertNotNull(store.find("gear"));
    }

    @Test
    public void anUpgradeWhoseListEntryWontGoStillSaves() {
        // a bad CustomTasks.json is read only. the graph wins over it in #custom, so nothing is lost
        linear.add("gear");
        linearRemoves = false;
        GraphStore.Result r = store.save(named("gear"), "gear");
        assertTrue(r.ok());
        assertTrue(r.error(), r.error().contains("CustomTasks.json"));
        assertNotNull(store.find("gear"));
    }

    @Test
    public void aBadFileIsLeftAloneAndCantBeOverwrittenOrDeleted() throws Exception {
        write("wood.json", "{ oops");
        byte[] before = Files.readAllBytes(dir.resolve("wood.json"));
        GraphStore.Entry e = store.find("wood");
        assertNull(e.graph());
        assertTrue(e.error(), e.error().contains("json"));
        assertEquals(1, store.list().size());
        assertTrue(store.save(named("wood"), "wood").error().contains("fix it by hand"));
        assertFalse(store.delete("wood"));
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("wood.json")));
    }

    @Test
    public void aFileNameThatIsntAnIdIsBadToo() throws Exception {
        write("my wood.json", GraphFile.write(named("x")));
        assertNull(store.find("my wood").graph());
    }

    @Test
    public void aFileChangedOnDiskIsReadAgainInsteadOfWrittenOver() throws Exception {
        assertTrue(store.save(named("wood"), null).ok());
        String edited = GraphFile.write(new RoutineGraph("wood", "wood", "hand edit", List.of(start()), List.of()));
        write("wood.json", edited);
        GraphStore.Result r = store.save(named("wood"), "wood");
        assertFalse(r.ok());
        assertTrue(r.error(), r.error().contains("changed on disk"));
        assertEquals(edited, Files.readString(dir.resolve("wood.json")));
        assertEquals("hand edit", store.find("wood").graph().description());
        // once it's been seen the next save is a real one
        assertTrue(store.save(named("wood"), "wood").ok());
    }

    @Test
    public void deleteRemovesTheFile() {
        assertTrue(store.save(named("wood"), null).ok());
        assertTrue(store.delete("WOOD"));
        assertNull(store.find("wood"));
        assertFalse(store.delete("wood"));
    }

    @Test
    public void aFileDroppedInShowsUp() throws Exception {
        write("Dropped.json", GraphFile.write(named("whatever")));
        assertEquals("Dropped", store.list().get(0).id());
        assertEquals("whatever", store.find("dropped").graph().name());
    }

    @Test
    public void aListRoutineIsAChainAndBack() {
        List<RoutineStep> steps = List.of(
                new RoutineStep(RoutineStep.Kind.GET, "get diamond_sword 1"),
                new RoutineStep(RoutineStep.Kind.GOTO, "goto 1 2 3"),
                new RoutineStep(RoutineStep.Kind.WAIT, "wait 5"),
                new RoutineStep(RoutineStep.Kind.EQUIP, "equip iron"),
                new RoutineStep(RoutineStep.Kind.RAW, "say hi"));
        RoutineGraph g = RoutineGraph.ofSteps("gear", "gear", "d", steps);
        assertTrue(GraphValidator.validate(g, GraphValidator.Checks.SHAPE_ONLY).firstError(), GraphValidator.validate(g, GraphValidator.Checks.SHAPE_ONLY).ok());
        assertEquals(steps, g.toSteps());
        assertEquals(List.of(), RoutineGraph.ofSteps("e", "e", "", List.of()).toSteps());
    }

    @Test
    public void onlyAStraightGraphHasSteps() {
        assertEquals(List.of(new RoutineStep(RoutineStep.Kind.RAW, "x")), graph(List.of(start(), raw("a", "x"), stop("e", true)), "s.out>a", "a.ok>e").toSteps());
        assertNull(graph(List.of(start(), raw("a", "x"), stop("e", false)), "s.out>a", "a.ok>e").toSteps());
        assertNull("a failed wire", graph(List.of(start(), raw("a", "x"), raw("b", "y")), "s.out>a", "a.failed>b").toSteps());
        assertNull("a side node", graph(List.of(start(), raw("a", "x"), raw("b", "y")), "s.out>a").toSteps());
        assertNull("a loop", graph(List.of(start(), times("r", 2), raw("a", "x")), "s.out>r", "r.body>a", "a.ok>>r").toSteps());
        assertNull("a wait until", graph(List.of(start(), GraphTestKit.waitUntil("w", night(), 5)), "s.out>w").toSteps());
    }

    @Test
    public void theNewNodesAndConditionsSurviveARoundTrip() throws Exception {
        RoutineGraph g = new RoutineGraph("x", "x", "", List.of(start(), GraphTestKit.mine("m", "oak_log", 16), GraphTestKit.craft("c", "stick", 4),
                new RoutineGraph.Node("e", RoutineGraph.Type.EAT, 0, 0, Map.of("hunger", "14"), null), GraphTestKit.follow("f", "Steve", 30), GraphTestKit.sub("u", "wood"),
                GraphTestKit.iff("i", GraphTestKit.lastFailed()), GraphTestKit.iff("j", GraphTestKit.laps(">=", 3))), List.of());
        String text = GraphFile.write(g);
        RoutineGraph back = GraphFile.parse("x", text);
        assertEquals(g.nodes(), back.nodes());
        assertEquals(text, GraphFile.write(back));
    }

    @Test
    public void aGraphWithTheNewNodesHasNoListView() {
        assertNull(graph(List.of(start(), GraphTestKit.mine("m", "dirt", 1)), "s.out>m").toSteps());
        assertNull(graph(List.of(start(), GraphTestKit.sub("m", "dirt")), "s.out>m").toSteps());
    }
}
