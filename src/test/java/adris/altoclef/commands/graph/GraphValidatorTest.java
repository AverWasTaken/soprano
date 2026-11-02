package adris.altoclef.commands.graph;

import static adris.altoclef.commands.graph.GraphTestKit.graph;
import static adris.altoclef.commands.graph.GraphTestKit.hasLogs;
import static adris.altoclef.commands.graph.GraphTestKit.iff;
import static adris.altoclef.commands.graph.GraphTestKit.night;
import static adris.altoclef.commands.graph.GraphTestKit.raw;
import static adris.altoclef.commands.graph.GraphTestKit.start;
import static adris.altoclef.commands.graph.GraphTestKit.stop;
import static adris.altoclef.commands.graph.GraphTestKit.times;
import static adris.altoclef.commands.graph.GraphTestKit.waitSecs;
import static adris.altoclef.commands.graph.GraphTestKit.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.commands.RoutineStep;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class GraphValidatorTest {

    private static GraphValidator.Result check(RoutineGraph g) {
        return GraphValidator.validate(g, GraphValidator.Checks.SHAPE_ONLY);
    }

    private static String err(GraphValidator.Result r, String node) {
        return r.nodes().get(node).error();
    }

    @Test
    public void aSimpleLoopIsFine() {
        GraphValidator.Result r = check(graph(List.of(start(), times("r", 3), waitSecs("a", "1"), stop("e", true)),
                "s.out>r", "r.body>a", "a.ok>>r", "r.done>e"));
        assertTrue(r.firstError(), r.ok());
        assertNull(r.nodes().get("r").warning());
    }

    @Test
    public void aBackWireIntoInIsACycle() {
        GraphValidator.Result r = check(graph(List.of(start(), times("r", 3), raw("a", "say hi")),
                "s.out>r", "r.body>a", "a.ok>r"));
        assertFalse(r.ok());
        assertTrue(err(r, "a"), err(r, "a").contains("again"));
    }

    @Test
    public void aCycleWithoutARepeatIsACycle() {
        GraphValidator.Result r = check(graph(List.of(start(), raw("a", "x"), raw("b", "y")), "s.out>a", "a.ok>b", "b.ok>a"));
        assertFalse(r.ok());
    }

    @Test
    public void onlyTheBodyMayWireIntoAgain() {
        // the done side going back around would spin forever without running anything
        GraphValidator.Result r = check(graph(List.of(start(), times("r", 3), raw("a", "x"), raw("b", "y")),
                "s.out>r", "r.body>a", "a.ok>>r", "r.done>b", "b.ok>>r"));
        assertTrue(err(r, "b"), err(r, "b").contains("body"));
        assertNull(err(r, "a"));
    }

    @Test
    public void whereBodyAndDoneMeetIsOutsideTheLoop() {
        GraphValidator.Result r = check(graph(List.of(start(), times("r", 3), raw("a", "x"), raw("z", "y")),
                "s.out>r", "r.body>a", "a.ok>z", "r.done>z", "z.ok>>r"));
        assertTrue(err(r, "z"), err(r, "z").contains("body"));
    }

    @Test
    public void aRepeatCantLoopOntoItselfDirectly() {
        GraphValidator.Result r = check(graph(List.of(start(), times("r", 3)), "s.out>r", "r.body>>r"));
        assertTrue(err(r, "r"), err(r, "r").contains("body"));
    }

    @Test
    public void anotherRepeatsAgainIsOutOfBounds() {
        GraphValidator.Result r = check(graph(List.of(start(), times("r", 2), times("q", 2), raw("a", "x"), raw("b", "y")),
                "s.out>r", "r.body>a", "a.ok>>r", "r.done>q", "q.body>b", "b.ok>>r"));
        assertTrue(err(r, "b"), err(r, "b").contains("body"));
    }

    @Test
    public void nestedRepeatsAndABreakAreFine() {
        GraphValidator.Result r = check(graph(List.of(start(), times("o", 2), times("i", 2), raw("a", "x"), raw("b", "y"), stop("e", true)),
                "s.out>o", "o.body>i", "i.body>a", "a.ok>>i", "a.failed>e", "i.done>b", "b.ok>>o", "o.done>e"));
        assertTrue(r.firstError(), r.ok());
    }

    @Test
    public void anInnerBodyMayContinueTheOuterLoop() {
        GraphValidator.Result r = check(graph(List.of(start(), times("o", 2), times("i", 2), raw("a", "x")),
                "s.out>o", "o.body>i", "i.body>a", "a.ok>>i", "a.failed>>o", "i.done>>o"));
        assertTrue(r.firstError(), r.ok());
    }

    @Test
    public void aRepeatNeedsABodyAndWarnsWithoutABackWire() {
        assertEquals("Wire something to body", err(check(graph(List.of(start(), times("r", 3)), "s.out>r")), "r"));
        GraphValidator.Result once = check(graph(List.of(start(), times("r", 3), raw("a", "x")), "s.out>r", "r.body>a"));
        assertTrue(once.ok());
        assertTrue(once.nodes().get("r").warning().contains("once"));
    }

    @Test
    public void aBodyOfOnlyRawCommandsWarnsButStillRuns() {
        // raw is done when its command returns, a forever loop of those is allowed and laps every tick
        GraphValidator.Result quick = check(graph(List.of(start(), GraphTestKit.forever("r"), raw("a", "mine oak_log 16")), "s.out>r", "r.body>a", "a.ok>>r"));
        assertTrue(quick.ok());
        assertTrue(quick.nodes().get("r").warning().contains("every tick"));
        GraphValidator.Result waiting = check(graph(List.of(start(), GraphTestKit.forever("r"), raw("a", "x"), waitSecs("w", "1")), "s.out>r", "r.body>a", "a.ok>w", "w.ok>>r"));
        assertNull(waiting.nodes().get("r").warning());
    }

    @Test
    public void theBodyIsAskableForTheEditor() {
        RoutineGraph g = graph(List.of(start(), times("r", 2), raw("a", "x"), raw("b", "y"), raw("z", "after")),
                "s.out>r", "r.body>a", "a.ok>b", "b.ok>>r", "r.done>z");
        assertEquals(Set.of("a", "b"), GraphValidator.bodyOf(g, "r"));
        assertEquals(Set.of(), GraphValidator.bodyOf(g, "a"));
        assertEquals(Set.of(), GraphValidator.bodyOf(g, "ghost"));
    }

    @Test
    public void stopIsOkOrNot() {
        assertTrue(check(graph(List.of(start(), stop("e", false)), "s.out>e")).ok());
        RoutineGraph typo = new RoutineGraph("t", "t", "", List.of(start(), new RoutineGraph.Node("e", RoutineGraph.Type.STOP, 0, 0, Map.of("ok", "no"), null)),
                List.of(new RoutineGraph.Edge("s", "out", "e", null)));
        assertFalse(check(typo).ok());
    }

    @Test
    public void portsAreCheckedAgainstTheNode() {
        assertFalse(check(graph(List.of(start(), raw("a", "x")), "s.ok>a")).ok());
        // no timeout port on a wait that can't time out
        assertFalse(check(graph(List.of(start(), waitSecs("w", "5"), raw("a", "x")), "s.out>w", "w.timeout>a")).ok());
        assertTrue(check(graph(List.of(start(), waitUntil("w", night(), 60), raw("a", "x")), "s.out>w", "w.timeout>a")).ok());
        // again is a repeat's input and nobody else's
        assertFalse(check(graph(List.of(start(), raw("a", "x")), "s.out>>a")).ok());
        assertFalse(check(graph(List.of(start(), raw("a", "x")), "s.out>a", "a.ok>s")).ok());
    }

    @Test
    public void oneWirePerPort() {
        GraphValidator.Result r = check(graph(List.of(start(), raw("a", "x"), raw("b", "y")), "s.out>a", "s.out>b"));
        assertTrue(err(r, "s"), err(r, "s").contains("one wire"));
    }

    @Test
    public void aWireToNowhereAndNoStartAreGraphErrors() {
        assertFalse(check(graph(List.of(start()), "s.out>ghost")).errors().isEmpty());
        assertEquals("there is no Start node", check(graph(List.of(raw("a", "x")))).errors().get(0));
        assertEquals("there can only be one Start node", check(new RoutineGraph("t", "t", "",
                List.of(start(), new RoutineGraph.Node("s2", RoutineGraph.Type.START, 0, 0, null, null)), List.of())).errors().get(0));
    }

    @Test
    public void unreachableIsAWarningAndDoesntBlock() {
        GraphValidator.Result r = check(graph(List.of(start(), raw("a", "x"), raw("b", "y")), "s.out>a"));
        assertTrue(r.ok());
        assertTrue(r.nodes().get("b").warning().contains("won't run"));
    }

    @Test
    public void paramsAreChecked() {
        assertFalse(check(graph(List.of(start(), times("r", 0), raw("a", "x")), "s.out>r", "r.body>a")).ok());
        assertFalse(check(graph(List.of(start(), raw("a", "")), "s.out>a")).ok());
        assertFalse(check(graph(List.of(start(), raw("a", "custom other")), "s.out>a")).ok());
        assertFalse(check(graph(List.of(start(), waitSecs("w", "soon")), "s.out>w")).ok());
        assertFalse(check(graph(List.of(start(), waitUntil("w", night(), 0)), "s.out>w")).ok());
        assertFalse(check(graph(List.of(start(), iff("i", null)), "s.out>i")).ok());
    }

    @Test
    public void conditionsAreCheckedAgainstTheirSpec() {
        assertNull(GraphValidator.condProblem(hasLogs(3), GraphValidator.Checks.SHAPE_ONLY));
        assertEquals("Add a condition", GraphValidator.condProblem(null, GraphValidator.Checks.SHAPE_ONLY));
        assertEquals("Pick a condition", GraphValidator.condProblem(Cond.leaf("", Map.of()), GraphValidator.Checks.SHAPE_ONLY));
        assertEquals("Add a condition", GraphValidator.condProblem(Cond.all(), GraphValidator.Checks.SHAPE_ONLY));
        assertTrue(GraphValidator.condProblem(Cond.leaf("near_mob", Map.of("mob", "zombie", "r", "65")), GraphValidator.Checks.SHAPE_ONLY).contains("64"));
        assertTrue(GraphValidator.condProblem(Cond.leaf("near_block", Map.of("block", "stone", "r", "17")), GraphValidator.Checks.SHAPE_ONLY).contains("16"));
        assertNull(GraphValidator.condProblem(Cond.leaf("near_pos", Map.of("x", "1", "z", "2", "r", "5")), GraphValidator.Checks.SHAPE_ONLY));
        assertTrue(GraphValidator.condProblem(Cond.leaf("near_pos", Map.of("x", "~", "z", "2", "r", "5")), GraphValidator.Checks.SHAPE_ONLY).contains("coordinate"));
        assertTrue(GraphValidator.condProblem(Cond.leaf("time", Map.of("value", "noon")), GraphValidator.Checks.SHAPE_ONLY).contains("day"));
        assertTrue(GraphValidator.condProblem(Cond.leaf("health", Map.of("op", "~", "value", "3")), GraphValidator.Checks.SHAPE_ONLY).contains("comparison"));
    }

    @Test
    public void depthStopsAtThree() {
        assertNull(GraphValidator.condProblem(Cond.all(Cond.not(night())), GraphValidator.Checks.SHAPE_ONLY));
        assertTrue(GraphValidator.condProblem(Cond.all(Cond.any(Cond.not(night()))), GraphValidator.Checks.SHAPE_ONLY).contains("deep"));
    }

    @Test
    public void theGameCanTurnDownAnId() {
        GraphValidator.Checks picky = new GraphValidator.Checks() {
            @Override
            public boolean commandExists(String label) {
                return true;
            }

            @Override
            public String itemProblem(RoutineStep.Kind kind, String name) {
                return null;
            }

            @Override
            public String blockProblem(String name) {
                return null;
            }

            @Override
            public String idProblem(GraphValidator.IdKind kind, String id) {
                return kind == GraphValidator.IdKind.MOB ? "no such mob" : null;
            }
        };
        assertEquals("no such mob", GraphValidator.condProblem(Cond.leaf("near_mob", Map.of("mob", "grue", "r", "5")), picky));
        assertNull(GraphValidator.condProblem(hasLogs(1), picky));
    }
}
