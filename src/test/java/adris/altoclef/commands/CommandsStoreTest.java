package adris.altoclef.commands;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

// CustomTasks.json: old files keep working, new ones round trip, and a file the store doesn't like is never written over
public class CommandsStoreTest {

    private static final String LEGACY = """
            {
              "prefix": "custom2",
              "customTasks": [
                {
                  "name": "gear",
                  "description": "diamond stuff",
                  "tasks": [
                    {"command": "get", "parameters": [["diamond_sword", "1"], ["diamond_pickaxe", "1"]]},
                    {"command": "goto", "parameters": [["100", "64", "200"], ["1", "2", "3"]]},
                    {"command": "equip", "parameters": [["diamond_chestplate"]]}
                  ]
                },
                {
                  "name": "my base",
                  "tasks": [{"command": "goto", "parameters": [["0", "70", "0"]]}]
                }
              ]
            }
            """;

    private Path dir;
    private Path file;
    private CommandsStore store;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("routines");
        file = dir.resolve("configs").resolve("CustomTasks.json");
        store = new CommandsStore(() -> file);
    }

    private void write(String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static RoutineStep step(RoutineStep.Kind kind, String line) {
        return new RoutineStep(kind, line);
    }

    @Test
    public void noFileIsAnEmptyListAndReadingNeverWritesOne() {
        CommandsStore.Snapshot s = store.snapshot();
        assertTrue(s.ok());
        assertTrue(s.routines().isEmpty());
        assertNull(store.customLines("gear"));
        assertFalse(Files.exists(file));
    }

    @Test
    public void aLegacyFileReadsTheOldWayAndStaysUntouched() throws Exception {
        write(LEGACY);
        byte[] before = Files.readAllBytes(file);
        CommandsStore.Snapshot s = store.snapshot();
        assertTrue(s.error(), s.ok());
        assertEquals(2, s.routines().size());
        StoredRoutine gear = s.routines().get(0);
        assertEquals("gear", gear.id());
        assertEquals("diamond stuff", gear.description());
        // get takes every row, goto only ever used the first one
        assertEquals(List.of(
                step(RoutineStep.Kind.GET, "get diamond_sword 1 diamond_pickaxe 1"),
                step(RoutineStep.Kind.GOTO, "goto 100 64 200"),
                step(RoutineStep.Kind.EQUIP, "equip diamond_chestplate")), gear.steps());
        // a name with a space could never be run by #custom, now it gets an id and keeps the name for show
        assertEquals("my_base", s.routines().get(1).id());
        assertEquals("my base", s.routines().get(1).name());
        assertEquals(List.of("get diamond_sword 1 diamond_pickaxe 1", "goto 100 64 200", "equip diamond_chestplate"), store.customLines("GEAR"));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    public void anIdAGraphUsesIsTaken() {
        CommandsStore both = new CommandsStore(() -> file, id -> id.equalsIgnoreCase("wood"));
        CommandsStore.Result r = both.save(new StoredRoutine(null, "wood", "", List.of(step(RoutineStep.Kind.RAW, "say hi"))), null);
        assertFalse(r.ok());
        assertTrue(r.error(), r.error().contains("already"));
        assertTrue(both.save(new StoredRoutine(null, "stone", "", List.of(step(RoutineStep.Kind.RAW, "say hi"))), null).ok());
        // renaming onto it is the same thing
        assertFalse(both.save(new StoredRoutine(null, "Wood", "", List.of(step(RoutineStep.Kind.RAW, "say hi"))), "stone").ok());
        // and a list entry an upgrade left behind can still be saved under its own id
        assertTrue(new CommandsStore(() -> file, id -> id.equalsIgnoreCase("stone"))
                .save(new StoredRoutine(null, "stone", "edited", List.of(step(RoutineStep.Kind.RAW, "say hi"))), "stone").ok());
    }

    @Test
    public void theFirstSaveMigratesAndKeepsTheOriginal() throws Exception {
        write(LEGACY);
        CommandsStore.Result r = store.save(new StoredRoutine(null, "wood run", "", List.of(step(RoutineStep.Kind.GET, "#get log 16"))), null);
        assertTrue(r.error(), r.ok());
        assertEquals(LEGACY, Files.readString(dir.resolve("configs").resolve("CustomTasks.json.bak")));
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(CommandsStore.VERSION, saved.get("version").getAsInt());
        CommandsStore fresh = new CommandsStore(() -> file);
        List<StoredRoutine> all = fresh.list();
        assertEquals(3, all.size());
        assertEquals("wood_run", all.get(2).id());
        assertEquals("wood run", all.get(2).name());
        // the prefix comes off
        assertEquals("get log 16", all.get(2).steps().get(0).line());
        // the old entries come through the migration the same
        assertEquals(store.customLines("gear"), fresh.customLines("gear"));
    }

    @Test
    public void aSavedStepHasTheLineAndTheOldSpellingBoth() throws Exception {
        assertTrue(store.save(new StoredRoutine(null, "gear", "d", List.of(step(RoutineStep.Kind.GET, "get diamond_sword 1, diamond_chestplate 1"))), null).ok());
        JsonObject task = JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                .getAsJsonArray("customTasks").get(0).getAsJsonObject()
                .getAsJsonArray("tasks").get(0).getAsJsonObject();
        assertEquals("get", task.get("type").getAsString());
        assertEquals("get diamond_sword 1, diamond_chestplate 1", task.get("line").getAsString());
        assertEquals("get", task.get("command").getAsString());
        // an older soprano builds the same line out of these
        String[][] params = {{"diamond_sword", "1,", "diamond_chestplate", "1"}};
        assertEquals("get diamond_sword 1, diamond_chestplate 1", RoutineLines.legacyLine("get", params));
        assertEquals(params[0].length, task.getAsJsonArray("parameters").get(0).getAsJsonArray().size());
    }

    @Test
    public void newFormatRoundTrips() {
        List<RoutineStep> steps = List.of(
                step(RoutineStep.Kind.GET, "get diamond_sword 1"),
                step(RoutineStep.Kind.GOTO, "wp goto home"),
                step(RoutineStep.Kind.WAIT, "wait 2.5"),
                step(RoutineStep.Kind.RAW, "goto diamond_ore"),
                step(RoutineStep.Kind.EQUIP, "equip iron"));
        assertTrue(store.save(new StoredRoutine(null, "All Of It", "every kind", steps), null).ok());
        StoredRoutine back = new CommandsStore(() -> file).find("all_of_it");
        assertNotNull(back);
        assertEquals("All Of It", back.name());
        assertEquals("every kind", back.description());
        // the raw goto stays raw (no waiting), the type is what was saved and not what the line looks like
        assertEquals(steps, back.steps());
    }

    @Test
    public void aBadFileIsNeverWrittenOver() throws Exception {
        write("{\"customTasks\": [ {\"name\": \"x\", \"tasks\": [ ] }, ]  oops");
        byte[] before = Files.readAllBytes(file);
        CommandsStore.Snapshot s = store.snapshot();
        assertFalse(s.ok());
        assertNotNull(s.error());
        CommandsStore.Result r = store.save(new StoredRoutine(null, "y", "", List.of()), null);
        assertFalse(r.ok());
        assertFalse(store.delete("x"));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    public void aWrongShapeIsBadToo() throws Exception {
        write("{\"customTasks\": \"nope\"}");
        assertFalse(store.snapshot().ok());
        write("[1, 2]");
        assertFalse(store.snapshot().ok());
    }

    @Test
    public void aFileFromANewerSopranoIsReadOnly() throws Exception {
        write("{\"version\": 99, \"customTasks\": []}");
        assertFalse(store.snapshot().ok());
        assertTrue(store.snapshot().error().contains("newer"));
    }

    @Test
    public void entriesAreCheckedOneByOne() {
        assertFalse(CommandsStore.parse("{\"version\":1,\"customTasks\":[null]}").snapshot().ok());
        assertFalse(CommandsStore.parse("{\"version\":1,\"customTasks\":[{\"name\":\"a\"}]}").snapshot().ok());
        assertFalse(CommandsStore.parse("{\"version\":1,\"customTasks\":[{\"name\":\"a\",\"tasks\":[{}]}]}").snapshot().ok());
        assertFalse(CommandsStore.parse("{\"version\":1,\"customTasks\":[{\"name\":\"a\",\"tasks\":[{\"command\":\"get\",\"parameters\":[null]}]}]}").snapshot().ok());
        // ids are one word in a versioned file, the tab never writes anything else
        assertFalse(CommandsStore.parse("{\"version\":1,\"customTasks\":[{\"name\":\"a b\",\"tasks\":[]}]}").snapshot().ok());
        // the same id twice, whatever the case
        CommandsStore.Parsed dup = CommandsStore.parse("{\"version\":1,\"customTasks\":[{\"name\":\"Gear\",\"tasks\":[]},{\"name\":\"gear\",\"tasks\":[]}]}");
        assertFalse(dup.snapshot().ok());
        assertEquals(1, dup.snapshot().routines().size());
        // line and the legacy fields saying different things
        assertFalse(CommandsStore.parse("{\"version\":1,\"customTasks\":[{\"name\":\"a\",\"tasks\":[{\"line\":\"get dirt 1\",\"command\":\"get\",\"parameters\":[[\"stone\"]]}]}]}").snapshot().ok());
        // the same thing twice is fine
        assertTrue(CommandsStore.parse("{\"version\":1,\"customTasks\":[{\"name\":\"a\",\"tasks\":[{\"line\":\"get dirt 1\",\"command\":\"get\",\"parameters\":[[\"dirt\",\"1\"]]}]}]}").snapshot().ok());
        // a type that doesn't fit the line, and one that doesn't exist
        assertFalse(CommandsStore.parse("{\"version\":1,\"customTasks\":[{\"name\":\"a\",\"tasks\":[{\"type\":\"wait\",\"line\":\"get dirt\"}]}]}").snapshot().ok());
        assertFalse(CommandsStore.parse("{\"version\":1,\"customTasks\":[{\"name\":\"a\",\"tasks\":[{\"type\":\"dance\",\"line\":\"get dirt\"}]}]}").snapshot().ok());
        assertTrue(CommandsStore.parse("").snapshot().ok());
    }

    @Test
    public void customKeepsAnOldEntryTheTabCantEdit() {
        // a list that runs another list worked with #custom before the tab, and still does
        CommandsStore.Parsed p = CommandsStore.parse("{\"customTasks\":[{\"name\":\"outer\",\"tasks\":[{\"command\":\"custom\",\"parameters\":[[\"inner\"]]}]}]}");
        assertFalse(p.snapshot().ok());
        assertTrue(p.snapshot().routines().isEmpty());
        assertEquals(List.of("custom inner"), p.lines().get("outer"));
    }

    @Test
    public void oldNamesStillWorkWithCustomBeforeAndAfterTheMigration() throws Exception {
        write("{\"customTasks\":[{\"name\":\"my.kit\",\"tasks\":[{\"command\":\"get\",\"parameters\":[[\"dirt\"]]}]},"
                + "{\"name\":\"mykit\",\"tasks\":[{\"command\":\"get\",\"parameters\":[[\"stone\"]]}]}]}");
        // the second one's id clashes with the first one's made up id, the file is read only but #custom answers both
        assertFalse(store.snapshot().ok());
        assertEquals(List.of("get dirt"), store.customLines("my.kit"));
        assertEquals(List.of("get stone"), store.customLines("mykit"));
        write("{\"customTasks\":[{\"name\":\"my.kit\",\"tasks\":[{\"command\":\"get\",\"parameters\":[[\"dirt\"]]}]}]}");
        // a fresh store, this one saw the file change under it and would turn the save down once
        CommandsStore fresh = new CommandsStore(() -> file);
        assertTrue(fresh.save(new StoredRoutine(null, "other", "", List.of()), null).ok());
        // migrated: id mykit, shown as my.kit, and #custom my.kit still runs it
        assertEquals("my.kit", fresh.find("mykit").name());
        assertEquals(List.of("get dirt"), fresh.customLines("my.kit"));
        assertEquals(List.of("get dirt"), fresh.customLines("mykit"));
    }

    @Test
    public void aSaveThatCantBeWrittenSaysSo() throws Exception {
        // the folder the file should go in is a file
        Files.writeString(dir.resolve("configs"), "in the way");
        CommandsStore.Result r = store.save(new StoredRoutine(null, "a", "", List.of()), null);
        assertFalse(r.ok());
        assertTrue(r.error().startsWith("couldn't save"));
    }

    @Test
    public void anEditOnDiskIsNotSavedOver() throws Exception {
        assertTrue(store.save(new StoredRoutine(null, "a", "", List.of(step(RoutineStep.Kind.WAIT, "wait 1"))), null).ok());
        String handEdited = Files.readString(file).replace("wait 1", "wait 90").replace("\"1\"", "\"90\"");
        Files.writeString(file, handEdited);
        // the tab refreshing its list in between doesn't make the edit something the save already knew about
        assertEquals("wait 90", store.find("a").steps().get(0).line());
        store.snapshot();
        CommandsStore.Result r = store.save(new StoredRoutine(null, "b", "", List.of()), null);
        assertFalse(r.ok());
        assertTrue(r.error().contains("changed on disk"));
        assertEquals(handEdited, Files.readString(file));
        // read again now, so the next one goes through and the hand edit is still in it
        assertTrue(store.save(new StoredRoutine(null, "b", "", List.of()), null).ok());
        assertEquals("wait 90", store.find("a").steps().get(0).line());
        assertNotNull(store.find("b"));
    }

    @Test
    public void renameMovesTheIdAndKeepsThePlace() {
        assertTrue(store.save(new StoredRoutine(null, "one", "", List.of()), null).ok());
        assertTrue(store.save(new StoredRoutine(null, "two", "", List.of()), null).ok());
        assertTrue(store.save(new StoredRoutine("one", "first one", "", List.of()), "one").ok());
        List<StoredRoutine> all = store.list();
        assertEquals(2, all.size());
        assertEquals("first_one", all.get(0).id());
        assertNull(store.find("one"));
        // a name that would land on another one's id is turned down
        assertFalse(store.save(new StoredRoutine("two", "First One", "", List.of()), "two").ok());
        assertFalse(store.save(new StoredRoutine(null, "TWO", "", List.of()), null).ok());
        // just a new case is still the same one
        assertTrue(store.save(new StoredRoutine("two", "Two", "", List.of()), "two").ok());
        assertEquals("Two", store.list().get(1).id());
    }

    @Test
    public void stepsThatWouldBreakTheFileAreTurnedDown() {
        assertFalse(store.save(new StoredRoutine(null, "a", "", List.of(step(RoutineStep.Kind.RAW, "get a; get b"))), null).ok());
        assertFalse(store.save(new StoredRoutine(null, "a", "", List.of(step(RoutineStep.Kind.RAW, "custom other"))), null).ok());
        assertFalse(store.save(new StoredRoutine(null, "a", "", List.of(step(RoutineStep.Kind.GET, "  "))), null).ok());
        assertFalse(store.save(new StoredRoutine(null, "a", "", List.of(step(RoutineStep.Kind.WAIT, "get dirt"))), null).ok());
        assertFalse(store.save(new StoredRoutine(null, "a", "", List.of(step(null, "get dirt"))), null).ok());
        assertFalse(store.save(new StoredRoutine(null, "!!!", "", List.of()), null).ok());
        assertFalse(Files.exists(file));
    }

    @Test
    public void deleteTakesItOut() {
        assertTrue(store.save(new StoredRoutine(null, "a", "", List.of()), null).ok());
        assertTrue(store.delete("A"));
        assertFalse(store.delete("a"));
        assertTrue(store.list().isEmpty());
    }
}
