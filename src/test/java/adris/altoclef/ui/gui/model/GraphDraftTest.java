package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.RoutinesAccess;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondSpec;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondTree;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Graph;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphEdge;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphNode;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.FakeRoutines;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GraphDraftTest {

    private FakeRoutines a;

    @Before
    public void setUp() {
        a = new FakeFacade().routines;
        a.graphsOn = true;
    }

    private static String toPort(GraphDraft d, String from, String to) {
        for (GraphEdge e : d.edges()) {
            if (e.from().equals(from) && e.to().equals(to)) {
                return e.toPort();
            }
        }
        return null;
    }

    @Test
    public void aBlankOneIsJustAStartAndClean() {
        GraphDraft d = GraphDraft.blank(a);
        assertEquals(1, d.nodes().size());
        assertTrue(d.isStart(d.nodes().get(0)));
        assertFalse(d.dirty());
        d.setName("Thing");
        assertTrue(d.dirty());
    }

    @Test
    public void newIdsAreTheFirstFreeOne() {
        GraphDraft d = GraphDraft.blank(a);
        assertEquals("n1", d.add("raw", 0, 0));
        assertEquals("n2", d.add("raw", 0, 0));
        d.remove("n1");
        assertEquals("n1", d.add("raw", 0, 0));
    }

    @Test
    public void startCantBeDeletedAndRemovingANodeTakesItsWires() {
        GraphDraft d = GraphDraft.blank(a);
        String n = d.add("raw", 0, 0);
        d.connect("s", "out", n);
        assertFalse(d.remove("s"));
        assertTrue(d.remove(n));
        assertTrue(d.edges().isEmpty());
    }

    @Test
    public void oneWirePerOutputAndNeverIntoStartOrItself() {
        GraphDraft d = GraphDraft.blank(a);
        String x = d.add("raw", 0, 0);
        String y = d.add("raw", 0, 0);
        d.connect("s", "out", x);
        d.connect("s", "out", y);
        assertEquals(1, d.edges().size());
        assertEquals(y, d.edges().get(0).to());
        assertFalse(d.connect(x, "ok", "s"));
        assertFalse(d.connect(x, "ok", x));
        // inputs take as many as you like
        d.connect(x, "ok", y);
        assertEquals(2, d.edges().size());
    }

    // nested repeats, with a wire out of the inner body straight back to the outer repeat. in both orders of making it,
    // since the classification can't depend on which wire went down first
    @Test
    public void nestedRepeatsClassifyTheSameInBothOrders() {
        for (boolean innerFirst : new boolean[]{true, false}) {
            GraphDraft d = GraphDraft.blank(a);
            String r1 = d.add("repeat", 0, 0);
            String r2 = d.add("repeat", 0, 0);
            String x = d.add("raw", 0, 0);
            String b = d.add("raw", 0, 0);
            d.connect("s", "out", r1);
            if (innerFirst) {
                d.connect(x, "ok", r2);
                d.connect(x, "failed", r1);
                d.connect(b, "ok", r1);
                d.connect(r2, "body", x);
                d.connect(r2, "done", b);
                d.connect(r1, "body", r2);
            } else {
                d.connect(r1, "body", r2);
                d.connect(r2, "body", x);
                d.connect(r2, "done", b);
                d.connect(b, "ok", r1);
                d.connect(x, "failed", r1);
                d.connect(x, "ok", r2);
            }
            assertEquals("again", toPort(d, x, r2));
            assertEquals("again", toPort(d, b, r1));
            assertEquals("in", toPort(d, r1, r2));
            assertEquals("in", toPort(d, "s", r1));
            assertEquals("again", toPort(d, x, r1));
        }
    }

    // the body gets built after the wire back. that first wire has to flip once there's a body it's the end of
    @Test
    public void aWireBackFlipsToAgainWhenTheBodyArrivesLater() {
        GraphDraft d = GraphDraft.blank(a);
        String r = d.add("repeat", 0, 0);
        String end = d.add("raw", 0, 0);
        d.connect("s", "out", r);
        d.connect(end, "ok", r);
        assertEquals("in", toPort(d, end, r));
        d.connect(r, "body", end);
        assertEquals("again", toPort(d, end, r));
        // and back when the body goes away again
        d.disconnect("" + r + ":body:" + end + ":in");
        assertEquals("in", toPort(d, end, r));
    }

    @Test
    public void aFreshlyOpenedGraphIsntDirtyEvenWithWrongToPorts() {
        a.seedGraphs();
        Graph g = a.graphFiles.get("Wood_loop");
        // the file lost its again, normalizing puts it back and that isn't an edit anybody made
        List<GraphEdge> edges = g.edges().stream().map(e -> new GraphEdge(e.from(), e.port(), e.to(), "in")).toList();
        GraphDraft d = GraphDraft.open(a, new Graph(g.id(), g.name(), g.description(), g.nodes(), edges), true);
        assertEquals("again", toPort(d, "m", "r"));
        assertFalse(d.dirty());
    }

    @Test
    public void aDeadWireOnLoadIsPrunedAndThatIsDirty() {
        Graph g = new Graph("x", "X", "", List.of(
                new GraphNode("s", "start", 0, 0, Map.of(), null),
                new GraphNode("w", "wait", 0, 0, Map.of("mode", "secs", "secs", "5"), null),
                new GraphNode("z", "stop", 0, 0, Map.of(), null)),
                List.of(new GraphEdge("s", "out", "w", "in"), new GraphEdge("w", "timeout", "z", "in")));
        GraphDraft d = GraphDraft.open(a, g, true);
        assertEquals(1, d.edges().size());
        assertTrue(d.dirty());
    }

    @Test
    public void changingAModePrunesTheWireThatLostItsPort() {
        GraphDraft d = GraphDraft.blank(a);
        String w = d.add("wait", 0, 0);
        String z = d.add("stop", 0, 0);
        d.setParam(w, "mode", "until");
        d.connect(w, "timeout", z);
        assertEquals(1, d.edges().size());
        d.setParam(w, "mode", "secs");
        assertTrue(d.edges().isEmpty());
    }

    @Test
    public void aNewNodeAsksForWhatItsSpecAsks() {
        GraphDraft d = GraphDraft.blank(a);
        String w = d.add("wait", 0, 0);
        assertEquals("secs", d.node(w).params().get("mode"));
        assertEquals("5", d.node(w).params().get("secs"));
        // no hidden condition in a wait that doesn't have one, then one to start from when it does
        assertNull(d.node(w).cond());
        d.setParam(w, "mode", "until");
        assertTrue(d.node(w).cond() != null);
        String m = d.add("mine", 0, 0);
        assertEquals("16", d.node(m).params().get("n"));
    }

    @Test
    public void secondsKeepTheirPoint() {
        GraphDraft d = GraphDraft.blank(a);
        String w = d.add("wait", 0, 0);
        d.setParam(w, "secs", "0.5");
        assertEquals("0.5", d.node(w).params().get("secs"));
        assertEquals("0.5", d.toGraph().nodes().get(1).params().get("secs"));
    }

    @Test
    public void thePaletteHangsOffTheFirstFreePortAndNeverAFailureOne() {
        GraphDraft d = GraphDraft.blank(a);
        String x = d.add("raw", 0, 0);
        String y = d.add("raw", 0, 0);
        assertEquals("ok", d.firstFreePort(x));
        d.connect(x, "ok", y);
        // failed is the only one left, and that's not "what happens next"
        assertNull(d.firstFreePort(x));
    }

    @Test
    public void theChainFollowsTheWiresThatCarryOn() {
        GraphDraft d = GraphDraft.blank(a);
        String x = d.add("raw", 0, 0);
        String y = d.add("raw", 0, 0);
        String z = d.add("stop", 0, 0);
        d.connect("s", "out", x);
        d.connect(x, "ok", y);
        d.connect(y, "ok", z);
        assertEquals(List.of(x, y), d.chain());
    }

    @Test
    public void aNameEditMovesTheShownIdOnlyWhenItReallyChanged() {
        a.seedGraphs();
        GraphDraft d = GraphDraft.open(a, a.graphFiles.get("Wood_loop"), true);
        assertEquals("Wood_loop", d.shownId());
        d.setName("Oak loop");
        assertEquals("Oak_loop", d.shownId());
    }

    @Test
    public void staleOnlyWhenTheStoredOneChanged() {
        a.seedGraphs();
        Graph stored = a.graphFiles.get("Wood_loop");
        GraphDraft d = GraphDraft.open(a, stored, true);
        assertFalse(d.staleAgainst(stored));
        assertTrue(d.staleAgainst(new Graph(stored.id(), stored.name(), "Changed", stored.nodes(), stored.edges())));
    }

    @Test
    public void blankConditionsReadOffTheSpecs() {
        List<CondSpec> specs = ((RoutinesAccess) a).condSpecs();
        CondTree t = CondDraft.blankTree(specs);
        assertEquals("leaf", t.op());
        assertEquals(specs.get(0).kind(), t.kind());
    }
}
