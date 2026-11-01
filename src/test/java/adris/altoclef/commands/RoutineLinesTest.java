package adris.altoclef.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Map;
import org.junit.Test;

// the editor boxes <-> line round trip and the per kind checks, without a game (the game's answers are all "fine")
public class RoutineLinesTest {

    private static RoutineStep step(RoutineStep.Kind kind, String line) {
        return new RoutineStep(kind, line);
    }

    private static String problem(RoutineStep.Kind kind, String line) {
        return RoutineLines.problem(step(kind, line), RoutineLines.Checks.SHAPE_ONLY);
    }

    @Test
    public void eachKindBuildsItsLine() {
        assertEquals("get diamond_sword 1, diamond_chestplate 1",
                RoutineLines.lineFor(RoutineStep.Kind.GET, Map.of("items", " diamond_sword 1,  diamond_chestplate 1 ")));
        assertEquals("equip iron", RoutineLines.lineFor(RoutineStep.Kind.EQUIP, Map.of("item", "iron")));
        assertEquals("wait 5", RoutineLines.lineFor(RoutineStep.Kind.WAIT, Map.of("seconds", "5")));
        assertEquals("gamer", RoutineLines.lineFor(RoutineStep.Kind.RAW, Map.of("line", "#gamer")));
        assertEquals("goto 1 64 -3", RoutineLines.lineFor(RoutineStep.Kind.GOTO, Map.of("x", "1", "y", "64", "z", "-3")));
        assertEquals("goto 1 -3", RoutineLines.lineFor(RoutineStep.Kind.GOTO, Map.of("x", "1", "z", "-3")));
        assertEquals("goto 64", RoutineLines.lineFor(RoutineStep.Kind.GOTO, Map.of("y", "64")));
        assertEquals("goto 1 64 ~", RoutineLines.lineFor(RoutineStep.Kind.GOTO, Map.of("x", "1", "y", "64")));
        // the goto boxes have no dimension, a stray one in the map doesn't sneak onto the line
        assertEquals("goto 1 64 -3", RoutineLines.lineFor(RoutineStep.Kind.GOTO, Map.of("x", "1", "y", "64", "z", "-3", "dimension", "nether")));
        assertEquals("wp goto home", RoutineLines.lineFor(RoutineStep.Kind.GOTO, Map.of("waypoint", "home", "block", "dirt")));
        assertEquals("goto diamond_ore", RoutineLines.lineFor(RoutineStep.Kind.GOTO, Map.of("block", "diamond_ore", "x", "1")));
        // nothing filled in is still a line, just one the checks turn down
        assertEquals("get", RoutineLines.lineFor(RoutineStep.Kind.GET, Map.of()));
        assertNotNull(problem(RoutineStep.Kind.GET, "get"));
    }

    @Test
    public void fieldsComeBackOutOfTheLine() {
        String[][] cases = {
                {"GET", "get diamond_sword 1, diamond_chestplate 1"},
                {"EQUIP", "equip iron"},
                {"WAIT", "wait 2.5"},
                {"RAW", "set allowBreak false"},
                {"GOTO", "goto 1 64 -3"},
                {"GOTO", "goto 1 -3"},
                {"GOTO", "goto 64"},
                {"GOTO", "goto 1 64 -3 the_end"},
                {"GOTO", "goto nether"},
                {"GOTO", "wp goto home"},
                {"GOTO", "goto diamond_ore"},
        };
        for (String[] c : cases) {
            RoutineStep.Kind kind = RoutineStep.Kind.valueOf(c[0]);
            Map<String, String> fields = RoutineLines.fieldsOf(step(kind, c[1]));
            assertEquals(c[1], RoutineLines.lineFor(kind, fields));
        }
        // a dimension has no box, so the line opens as typed and keeps going red instead of losing the_end on save
        assertEquals(Map.of("block", "1 64 -3 the_end"), RoutineLines.fieldsOf(step(RoutineStep.Kind.GOTO, "goto 1 64 -3 the_end")));
        assertEquals(Map.of("waypoint", "home"), RoutineLines.fieldsOf(step(RoutineStep.Kind.GOTO, "wp goto home")));
        assertEquals(Map.of("block", "diamond_ore"), RoutineLines.fieldsOf(step(RoutineStep.Kind.GOTO, "goto diamond_ore")));
    }

    @Test
    public void goodLinesPass() {
        assertNull(problem(RoutineStep.Kind.GET, "get diamond_sword 1, diamond_chestplate 1"));
        assertNull(problem(RoutineStep.Kind.EQUIP, "equip diamond"));
        assertNull(problem(RoutineStep.Kind.EQUIP, "equip diamond_helmet diamond_boots"));
        assertNull(problem(RoutineStep.Kind.WAIT, "wait 0.5"));
        assertNull(problem(RoutineStep.Kind.GOTO, "goto 1 2 3"));
        assertNull(problem(RoutineStep.Kind.GOTO, "goto ~ 70 ~10"));
        // the raw step is where a dimension change goes, and it takes the line as is
        assertNull(problem(RoutineStep.Kind.RAW, "goto 1 2 3 nether"));
        assertNull(problem(RoutineStep.Kind.RAW, "goto the_end"));
        assertNull(problem(RoutineStep.Kind.GOTO, "goto diamond_ore"));
        assertNull(problem(RoutineStep.Kind.GOTO, "waypoints goto home"));
        assertNull(problem(RoutineStep.Kind.RAW, "goto diamond_ore"));
        assertNull(problem(RoutineStep.Kind.RAW, "mine diamond_ore 10"));
    }

    @Test
    public void badLinesSayWhy() {
        assertNotNull(problem(RoutineStep.Kind.GET, "get diamond_sword 0"));
        assertNotNull(problem(RoutineStep.Kind.GET, "get 3"));
        assertNotNull(problem(RoutineStep.Kind.GET, "equip iron"));
        assertNotNull(problem(RoutineStep.Kind.EQUIP, "equip"));
        assertNotNull(problem(RoutineStep.Kind.WAIT, "wait"));
        assertNotNull(problem(RoutineStep.Kind.WAIT, "wait -1"));
        assertNotNull(problem(RoutineStep.Kind.WAIT, "wait soon"));
        assertNotNull(problem(RoutineStep.Kind.WAIT, "wait 1 2"));
        assertNotNull(problem(RoutineStep.Kind.WAIT, "wait 100000"));
        assertNotNull(problem(RoutineStep.Kind.GOTO, "goto"));
        assertNotNull(problem(RoutineStep.Kind.GOTO, "goto 1 2 3 4"));
        assertNotNull(problem(RoutineStep.Kind.GOTO, "goto ~ 2 3 nether"));
        assertEquals("use a Raw step to change dimension", problem(RoutineStep.Kind.GOTO, "goto 1 2 3 nether"));
        assertEquals("use a Raw step to change dimension", problem(RoutineStep.Kind.GOTO, "goto the_end"));
        assertEquals("use a Raw step to change dimension", problem(RoutineStep.Kind.GOTO, "goto 64 overworld"));
        assertNotNull(problem(RoutineStep.Kind.GOTO, "goto stone 3"));
        assertNotNull(problem(RoutineStep.Kind.GOTO, "wp goto"));
        assertNotNull(problem(RoutineStep.Kind.GOTO, "get dirt"));
        assertNotNull(problem(RoutineStep.Kind.RAW, "get dirt; get stone"));
        assertNotNull(problem(RoutineStep.Kind.RAW, "custom other"));
        assertNotNull(problem(RoutineStep.Kind.RAW, " "));
        assertNotNull(problem(null, "get dirt"));
        assertNotNull(RoutineLines.problem(null, RoutineLines.Checks.SHAPE_ONLY));
    }

    @Test
    public void theGameGetsTheLastWord() {
        RoutineLines.Checks picky = new RoutineLines.Checks() {
            @Override
            public boolean commandExists(String label) {
                return !label.equals("dance");
            }

            @Override
            public String itemProblem(RoutineStep.Kind kind, String name) {
                return name.equals("unobtainium") ? "nope" : null;
            }

            @Override
            public String blockProblem(String name) {
                return name.equals("air_ore") ? "not a block" : null;
            }
        };
        assertEquals("there is no command called dance", RoutineLines.problem(step(RoutineStep.Kind.RAW, "dance"), picky));
        assertEquals("nope", RoutineLines.problem(step(RoutineStep.Kind.GET, "get dirt 1 unobtainium 2"), picky));
        assertEquals("not a block", RoutineLines.problem(step(RoutineStep.Kind.GOTO, "goto air_ore"), picky));
    }

    @Test
    public void kindsAndNames() {
        assertEquals(RoutineStep.Kind.GET, RoutineLines.kindOf("get dirt"));
        assertEquals(RoutineStep.Kind.GOTO, RoutineLines.kindOf("wp goto home"));
        assertEquals(RoutineStep.Kind.GOTO, RoutineLines.kindOf("Goto 1 2 3"));
        assertEquals(RoutineStep.Kind.RAW, RoutineLines.kindOf("wp list"));
        // an old untyped entry that crossed dimensions keeps running as the raw line it was
        assertEquals(RoutineStep.Kind.RAW, RoutineLines.kindOf("goto 1 2 3 nether"));
        assertEquals(RoutineStep.Kind.RAW, RoutineLines.kindOf("goto the_end"));
        // but a typed goto with a dimension still loads, so the file isn't turned down over one red row
        assertNull(CommandsStore.stepShape(RoutineStep.Kind.GOTO, "goto 1 2 3 nether"));
        assertEquals(RoutineStep.Kind.WAIT, RoutineLines.kindNamed("wait"));
        assertNull(RoutineLines.kindNamed("dance"));
        assertEquals("diamond_gear", RoutineLines.idFor("  diamond   gear "));
        assertEquals("wood-run_2", RoutineLines.idFor("wood-run_2!"));
        assertNull(RoutineLines.idFor("!!!"));
        assertEquals("get dirt 1", RoutineLines.normalize("  $get   dirt 1", "$"));
        assertEquals("get dirt 1", RoutineLines.normalize("#get dirt 1", "$"));
    }

    @Test
    public void legacyRowsFollowTheOldRules() {
        assertEquals("get dirt 1 stone 2", RoutineLines.legacyLine("get", new String[][]{{"dirt", "1"}, {"stone", "2"}}));
        assertEquals("goto 1 2 3", RoutineLines.legacyLine("goto", new String[][]{{"1", "2", "3"}, {"4", "5", "6"}}));
        assertEquals("hero", RoutineLines.legacyLine("hero", null));
        assertEquals("hero", RoutineLines.legacyLine("hero", new String[][]{{}}));
    }
}
