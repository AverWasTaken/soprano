package adris.altoclef.commands.graph;

import static adris.altoclef.commands.graph.GraphTestKit.craft;
import static adris.altoclef.commands.graph.GraphTestKit.eat;
import static adris.altoclef.commands.graph.GraphTestKit.follow;
import static adris.altoclef.commands.graph.GraphTestKit.graph;
import static adris.altoclef.commands.graph.GraphTestKit.hasLogs;
import static adris.altoclef.commands.graph.GraphTestKit.iff;
import static adris.altoclef.commands.graph.GraphTestKit.laps;
import static adris.altoclef.commands.graph.GraphTestKit.lastFailed;
import static adris.altoclef.commands.graph.GraphTestKit.mine;
import static adris.altoclef.commands.graph.GraphTestKit.night;
import static adris.altoclef.commands.graph.GraphTestKit.raw;
import static adris.altoclef.commands.graph.GraphTestKit.start;
import static adris.altoclef.commands.graph.GraphTestKit.stop;
import static adris.altoclef.commands.graph.GraphTestKit.sub;
import static adris.altoclef.commands.graph.GraphTestKit.times;
import static adris.altoclef.commands.graph.GraphTestKit.until;
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

    private static GraphValidator.Result one(RoutineGraph.Node n) {
        return check(graph(List.of(start(), n), "s.out>" + n.id()));
    }

    private static RoutineGraph.Node with(String id, RoutineGraph.Type type, Map<String, String> params) {
        return new RoutineGraph.Node(id, type, 0, 0, params, null);
    }

    @Test
    public void theNewActionsAreFineWhenFilledIn() {
        for (RoutineGraph.Node n : List.of(mine("n", "oak_log", 16), craft("n", "stick", 4), eat("n"), follow("n", "Steve_1", 60), sub("n", "wood"))) {
            GraphValidator.Result r = one(n);
            assertTrue(n.type() + ": " + r.firstError(), r.ok());
        }
    }

    @Test
    public void theNewActionsSayWhatsMissing() {
        assertTrue(err(one(with("n", RoutineGraph.Type.MINE, Map.of("n", "1"))), "n").contains("needs block"));
        assertTrue(err(one(with("n", RoutineGraph.Type.MINE, Map.of("block", "dirt"))), "n").contains("needs how many"));
        assertTrue(err(one(with("n", RoutineGraph.Type.CRAFT, Map.of("n", "1"))), "n").contains("needs item"));
        assertTrue(err(one(with("n", RoutineGraph.Type.FOLLOW, Map.of("secs", "1"))), "n").contains("needs player"));
        assertTrue(err(one(with("n", RoutineGraph.Type.FOLLOW, Map.of("player", "x"))), "n").contains("needs for seconds"));
        assertTrue(err(one(with("n", RoutineGraph.Type.SUB, Map.of())), "n").contains("needs routine"));
    }

    @Test
    public void theNewActionsCheckTheirNumbers() {
        assertTrue(one(mine("n", "dirt", GraphSpecs.MAX_COUNT)).ok());
        assertFalse(one(mine("n", "dirt", GraphSpecs.MAX_COUNT + 1)).ok());
        assertFalse(one(mine("n", "dirt", 0)).ok());
        assertFalse(one(craft("n", "stick", 0)).ok());
        assertFalse(one(follow("n", "x", 0)).ok());
        assertFalse(one(follow("n", "x", GraphSpecs.MAX_FOLLOW_SECONDS + 1)).ok());
        assertTrue("a half typed number survives a save, a run refuses it", err(one(with("n", RoutineGraph.Type.MINE, Map.of("block", "dirt", "n", "1x"))), "n").contains("goes from"));
    }

    @Test
    public void eatsHungerIsOptionalButInRange() {
        assertTrue(one(with("n", RoutineGraph.Type.EAT, Map.of("hunger", "14"))).ok());
        assertTrue(one(with("n", RoutineGraph.Type.EAT, Map.of())).ok());
        assertFalse(one(with("n", RoutineGraph.Type.EAT, Map.of("hunger", "21"))).ok());
        assertFalse(one(with("n", RoutineGraph.Type.EAT, Map.of("hunger", "0"))).ok());
    }

    @Test
    public void aPlayerNameIsAPlayerName() {
        assertFalse(one(follow("n", "two words", 5)).ok());
        assertFalse(one(follow("n", "seventeen_letters", 5)).ok());
    }

    @Test
    public void theGameGetsToCheckMinesAndCraftsAndCalls() {
        GraphValidator.Checks picky = new GraphValidator.Checks() {
            @Override
            public boolean commandExists(String label) {
                return true;
            }

            @Override
            public String itemProblem(RoutineStep.Kind kind, String name) {
                return kind == RoutineStep.Kind.GET && name.equals("bedrock") ? "can't make that" : null;
            }

            @Override
            public String blockProblem(String name) {
                return null;
            }

            @Override
            public String idProblem(GraphValidator.IdKind kind, String id) {
                return id.equals("grue") ? "no such " + kind : null;
            }

            @Override
            public String routineProblem(String id) {
                return id.equals("gone") ? "no routine" : null;
            }
        };
        assertEquals("no such BLOCK", err(GraphValidator.validate(graph(List.of(start(), mine("n", "grue", 1)), "s.out>n"), picky), "n"));
        assertEquals("no such ITEM", err(GraphValidator.validate(graph(List.of(start(), craft("n", "grue", 1)), "s.out>n"), picky), "n"));
        assertEquals("can't make that", err(GraphValidator.validate(graph(List.of(start(), craft("n", "bedrock", 1)), "s.out>n"), picky), "n"));
        assertEquals("no routine", err(GraphValidator.validate(graph(List.of(start(), sub("n", "gone")), "s.out>n"), picky), "n"));
    }

    private static GraphValidator.Checks calling(Map<String, List<String>> calls) {
        return new GraphValidator.Checks() {
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
            public List<String> callsOf(String id) {
                return calls.getOrDefault(id, List.of());
            }
        };
    }

    @Test
    public void callingYourselfIsRefusedBeforeARun() {
        // graph() names everything "test"
        GraphValidator.Result r = GraphValidator.validate(graph(List.of(start(), sub("n", "TEST")), "s.out>n"), calling(Map.of()));
        assertTrue(err(r, "n"), err(r, "n").contains("itself"));
    }

    @Test
    public void aCallThatComesBackAroundIsRefused() {
        GraphValidator.Result back = GraphValidator.validate(graph(List.of(start(), sub("n", "a")), "s.out>n"), calling(Map.of("a", List.of("b"), "b", List.of("test"))));
        assertTrue(err(back, "n"), err(back, "n").contains("itself"));
        GraphValidator.Result circle = GraphValidator.validate(graph(List.of(start(), sub("n", "a")), "s.out>n"), calling(Map.of("a", List.of("b"), "b", List.of("a"))));
        assertTrue(err(circle, "n"), err(circle, "n").contains("itself"));
    }

    @Test
    public void callingTheSameRoutineTwiceIsNotACircle() {
        GraphValidator.Result r = GraphValidator.validate(graph(List.of(start(), sub("n", "a")), "s.out>n"), calling(Map.of("a", List.of("b", "c"), "c", List.of("b"))));
        assertTrue(r.firstError(), r.ok());
    }

    @Test
    public void callsDeeperThanTheGuardAreRefused() {
        Map<String, List<String>> chain = new java.util.HashMap<>();
        for (int i = 0; i < GraphSpecs.MAX_CALL_DEPTH + 2; i++) {
            chain.put("d" + i, List.of("d" + (i + 1)));
        }
        GraphValidator.Result r = GraphValidator.validate(graph(List.of(start(), sub("n", "d0")), "s.out>n"), calling(chain));
        assertTrue(err(r, "n"), err(r, "n").contains("deep"));
        // exactly as deep as the run allows: us plus the max minus one below us
        Map<String, List<String>> fits = new java.util.HashMap<>();
        for (int i = 0; i < GraphSpecs.MAX_CALL_DEPTH - 2; i++) {
            fits.put("d" + i, List.of("d" + (i + 1)));
        }
        assertTrue(GraphValidator.validate(graph(List.of(start(), sub("n", "d0")), "s.out>n"), calling(fits)).ok());
    }

    @Test
    public void mineAndCraftCountAsWaitingInALoop() {
        for (RoutineGraph.Node n : List.of(mine("a", "dirt", 1), craft("a", "stick", 1), eat("a"), follow("a", "x", 1), sub("a", "y"))) {
            GraphValidator.Result r = check(graph(List.of(start(), times("r", 3), n), "s.out>r", "r.body>a", "a.ok>>r"));
            assertNull(n.type().toString(), r.nodes().get("r").warning());
        }
    }

    @Test
    public void theRawLoopWarningPointsAtMineAndCraft() {
        GraphValidator.Result r = check(graph(List.of(start(), times("r", 3), raw("a", "x")), "s.out>r", "r.body>a", "a.ok>>r"));
        assertTrue(r.nodes().get("r").warning(), r.nodes().get("r").warning().contains("Mine, Craft"));
    }

    @Test
    public void theNewConditionsValidate() {
        assertNull(GraphValidator.condProblem(lastFailed(), GraphValidator.Checks.SHAPE_ONLY));
        assertNull(GraphValidator.condProblem(laps(">=", 3), GraphValidator.Checks.SHAPE_ONLY));
        assertTrue(GraphValidator.condProblem(laps(">=", GraphSpecs.MAX_LOOPS + 1), GraphValidator.Checks.SHAPE_ONLY).contains("goes from"));
        assertTrue(GraphValidator.condProblem(Cond.leaf("loop_counter", Map.of("op", ">=")), GraphValidator.Checks.SHAPE_ONLY).contains("needs lap"));
    }

    @Test
    public void everyPresetIsAValidCondition() {
        assertFalse(GraphSpecs.COND_PRESETS.isEmpty());
        for (GraphSpecs.CondPreset p : GraphSpecs.COND_PRESETS) {
            assertNull(p.label(), GraphValidator.condProblem(Cond.leaf(p.kind(), p.args()), GraphValidator.Checks.SHAPE_ONLY));
        }
        assertEquals("Inventory full", GraphSpecs.COND_PRESETS.get(0).label());
        assertEquals("free_slots", GraphSpecs.COND_PRESETS.get(0).kind());
    }

    @Test
    public void theLoopCounterOutsideARepeatGetsAWarning() {
        GraphValidator.Result outside = check(graph(List.of(start(), iff("i", laps(">=", 1))), "s.out>i"));
        assertTrue(outside.nodes().get("i").warning(), outside.nodes().get("i").warning().contains("inside a Repeat"));
        assertTrue("it can run, the run fails", outside.ok());
        GraphValidator.Result inside = check(graph(List.of(start(), times("r", 3), iff("i", laps(">=", 1)), waitSecs("w", "1")), "s.out>r", "r.body>i", "i.yes>w", "w.ok>>r"));
        assertNull(inside.nodes().get("i").warning());
        GraphValidator.Result own = check(graph(List.of(start(), until("r", laps(">=", 2), 5), waitSecs("w", "1")), "s.out>r", "r.body>w", "w.ok>>r"));
        assertNull(own.nodes().get("r").warning());
    }

    @Test
    public void everyTypeHasASpecAndTheLimitsStayPut() {
        for (RoutineGraph.Type t : RoutineGraph.Type.values()) {
            assertTrue(t.toString(), GraphSpecs.node(t) != null);
        }
        for (RoutineGraph.Type t : List.of(RoutineGraph.Type.MINE, RoutineGraph.Type.CRAFT, RoutineGraph.Type.EAT, RoutineGraph.Type.FOLLOW, RoutineGraph.Type.SUB)) {
            assertEquals(t.toString(), List.of(RoutineGraph.OK, RoutineGraph.FAILED), GraphSpecs.node(t).outputs());
            assertEquals(t.toString(), "act", GraphSpecs.node(t).group());
            assertTrue(t.acts);
        }
        assertEquals(64, GraphSpecs.MAX_MOB_RADIUS);
        assertEquals(16, GraphSpecs.MAX_BLOCK_RADIUS);
    }

    @Test
    public void everyRoutineGetsAskedAboutOnce() {
        // eight levels, each calling the next ten times. walking that as a tree is a million looks at the routines folder
        int[] asked = {0};
        GraphValidator.Checks wide = new GraphValidator.Checks() {
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
            public List<String> callsOf(String id) {
                asked[0]++;
                int level = Integer.parseInt(id.substring(1));
                return level >= 6 ? List.of() : java.util.Collections.nCopies(10, "d" + (level + 1));
            }
        };
        GraphValidator.Result r = GraphValidator.validate(graph(List.of(start(), sub("n", "d0"), sub("m", "d0")), "s.out>n", "n.ok>m"), wide);
        assertTrue(r.firstError(), r.ok());
        assertEquals(7, asked[0]);
    }

    @Test
    public void aSharedRoutineStillCountsForTheDepth() {
        // d0 gets to the bottom the short way first, the long way only finds it already worked out
        Map<String, List<String>> calls = new java.util.HashMap<>();
        calls.put("d0", List.of("tall", "c1"));
        calls.put("tall", List.of("t1"));
        calls.put("t1", List.of("t2"));
        calls.put("t2", List.of("t3"));
        calls.put("t3", List.of("t4"));
        calls.put("c1", List.of("c2"));
        calls.put("c2", List.of("c3"));
        calls.put("c3", List.of("c4"));
        calls.put("c4", List.of("c5"));
        calls.put("c5", List.of("c6"));
        calls.put("c6", List.of("tall"));
        GraphValidator.Result r = GraphValidator.validate(graph(List.of(start(), sub("n", "d0")), "s.out>n"), calling(calls));
        assertTrue(err(r, "n"), err(r, "n").contains("deep"));
    }

    @Test
    public void aCraftWithNoRecipeWarnsButStillRuns() {
        GraphValidator.Checks noRecipes = new GraphValidator.Checks() {
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
            public boolean craftable(String item) {
                return false;
            }
        };
        GraphValidator.Result r = GraphValidator.validate(graph(List.of(start(), craft("n", "diamond", 1)), "s.out>n"), noRecipes);
        assertTrue(r.firstError(), r.ok());
        assertTrue(r.nodes().get("n").warning(), r.nodes().get("n").warning().contains("recipe"));
        assertNull(check(graph(List.of(start(), craft("n", "stick", 1)), "s.out>n")).nodes().get("n").warning());
    }
}
