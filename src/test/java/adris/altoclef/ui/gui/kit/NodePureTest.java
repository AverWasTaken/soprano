package adris.altoclef.ui.gui.kit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

// the bits of the node canvas with no pixels in them
public class NodePureTest {

    private static final float EPS = 0.01f;

    // ----- geometry

    @Test
    public void cardsMatchTheMockHeights() {
        assertEquals(176, NodeGeometry.width(false));
        assertEquals(112, NodeGeometry.width(true));
        // two outs and a line: head 30 + 10 + 2 rows
        assertEquals(84, NodeGeometry.height(2, 1, false, false));
        // no ports and nothing to say still leaves the 32 minimum
        assertEquals(62, NodeGeometry.height(0, 0, false, false));
        // one out and two lines: the text decides
        assertEquals(30 + 8 + 30 + 8, NodeGeometry.height(1, 2, false, false));
        assertEquals(84 + 21, NodeGeometry.height(2, 1, true, false));
        assertEquals(30 + 7 + 16 + 4 + 30 + 8, NodeGeometry.height(2, 2, false, true));
    }

    @Test
    public void portsSitOnRows() {
        assertEquals(46, NodeGeometry.inPortY());
        assertEquals(46, NodeGeometry.outPortY(0));
        assertEquals(68, NodeGeometry.outPortY(1));
        assertEquals(46, NodeGeometry.outPortY(-1));
        assertEquals(176, NodeGeometry.outPortX(false));
        assertEquals(112, NodeGeometry.outPortX(true));
    }

    @Test
    public void snapGoesToTheGrid() {
        assertEquals(8, NodeGeometry.snap(5f));
        assertEquals(0, NodeGeometry.snap(3f));
        assertEquals(-8, NodeGeometry.snap(-5f));
    }

    // ----- view

    @Test
    public void worldAndScreenRoundTrip() {
        CanvasView v = new CanvasView(1.25f);
        v.setOrigin(100, 50);
        v.panBy(30, -20);
        v.zoomAt(1, 400, 300);
        assertEquals(217f, v.worldX(v.screenX(217f)), EPS);
        assertEquals(-40f, v.worldY(v.screenY(-40f)), EPS);
        assertEquals(1.25f * 1.25f, v.k(), EPS);
    }

    @Test
    public void zoomKeepsThePointUnderTheCursor() {
        CanvasView v = new CanvasView(1f);
        v.setOrigin(10, 10);
        v.panBy(50, 70);
        for (int steps : new int[]{-1, -1, 1, 1, 1, 1}) {
            float wx = v.worldX(333);
            float wy = v.worldY(222);
            v.zoomAt(steps, 333, 222);
            assertEquals(wx, v.worldX(333), EPS);
            assertEquals(wy, v.worldY(222), EPS);
        }
    }

    @Test
    public void zoomStopsAtTheEnds() {
        CanvasView v = new CanvasView(1f);
        assertTrue(v.zoomAt(-1, 0, 0));
        assertTrue(v.zoomAt(-1, 0, 0));
        assertFalse(v.zoomAt(-1, 0, 0));
        assertEquals(0.5f, v.zoom(), EPS);
        v.zoomAt(100, 0, 0);
        assertEquals(1.5f, v.zoom(), EPS);
        assertFalse(v.zoomAt(1, 0, 0));
    }

    @Test
    public void panSurvivesAScaleChange() {
        CanvasView a = new CanvasView(1f);
        a.panBy(100, 40);
        CanvasView b = new CanvasView(2f);
        b.restore(a.panX(), a.panY(), a.zoomIndex());
        // the same world point lands at twice the device px: the view didn't move, the pixels got bigger
        assertEquals(2 * a.screenX(10), b.screenX(10), EPS);
    }

    @Test
    public void fitPicksTheBiggestStepThatFitsAndNeverGoesOverOne() {
        CanvasView v = new CanvasView(1f);
        CanvasView.Rect whole = new CanvasView.Rect(0, 0, 1000, 600);
        v.fit(new CanvasView.Rect(0, 0, 100, 100), whole, whole);
        assertEquals(1f, v.zoom(), EPS);
        v.fit(new CanvasView.Rect(0, 0, 1200, 100), whole, whole);
        assertEquals(0.75f, v.zoom(), EPS);
        v.fit(new CanvasView.Rect(0, 0, 5000, 100), whole, whole);
        assertEquals(0.5f, v.zoom(), EPS);
    }

    @Test
    public void fitCentresOnTheRoomItUsed() {
        CanvasView v = new CanvasView(1f);
        CanvasView.Rect room = new CanvasView.Rect(200, 0, 600, 600);
        v.fit(new CanvasView.Rect(50, 50, 100, 100), room, room);
        assertEquals(200 + 300 - 50, v.screenX(50), EPS);
        assertEquals(300 - 50, v.screenY(50), EPS);
    }

    @Test
    public void fitIgnoresTheInspectorWhenBesideItWouldBeUnreadable() {
        CanvasView v = new CanvasView(1f);
        CanvasView.Rect whole = new CanvasView.Rect(0, 0, 1000, 600);
        CanvasView.Rect free = new CanvasView.Rect(0, 0, 600, 600);
        // fits at 0.75 beside it: stays beside it
        v.fit(new CanvasView.Rect(0, 0, 760, 100), free, whole);
        assertEquals(0.75f, v.zoom(), EPS);
        assertEquals((600 - 760 * 0.75f) / 2f, v.screenX(0), EPS);
        // only 0.5 beside it, 1 over everything: the whole canvas it is
        v.fit(new CanvasView.Rect(0, 0, 900, 100), free, whole);
        assertEquals(1f, v.zoom(), EPS);
        assertEquals(50f, v.screenX(0), EPS);
    }

    // ----- wires

    private static WireRouter.In in(float ax, float ay, float bx, float by, int port) {
        return new WireRouter.In(ax, ay, bx, by, port, ay - 46, by - 46);
    }

    @Test
    public void sameRowIsOneStraightLine() {
        WireRouter.Route r = WireRouter.route(in(100, 50, 300, 50, 0), 0);
        assertEquals(2, r.xs().length);
        assertEquals(300 - WireRouter.END_SHORT, r.xs()[1], EPS);
        assertFalse(r.back());
    }

    @Test
    public void forwardGoesThroughTheMiddleWithAFanOffset() {
        WireRouter.Route r0 = WireRouter.route(in(100, 50, 306, 150, 0), 0);
        WireRouter.Route r2 = WireRouter.route(in(100, 50, 306, 150, 2), 0);
        assertEquals(200f, r0.xs()[1], EPS);
        assertEquals(200f, r0.xs()[2], EPS);
        assertEquals(208f, r2.xs()[1], EPS);
        // the chip sits on the riser, on the wire
        assertEquals(200f, r0.midX(), EPS);
        assertEquals(100f, r0.midY(), EPS);
    }

    @Test
    public void aWireGoingBackwardsGoesUpAndOver() {
        WireRouter.Route r = WireRouter.route(new WireRouter.In(800, 86, 160, 86, 0, 40, 40), 0);
        assertTrue(r.back());
        assertEquals(6, r.xs().length);
        assertEquals(40f - 26f, r.ys()[2], EPS);
        assertEquals(40f - 26f, r.midY(), EPS);
        assertEquals(40f - 26f, r.minY(), EPS);
    }

    @Test
    public void nearlyTouchingCountsAsBackward() {
        assertTrue(WireRouter.isBack(100, 129));
        assertFalse(WireRouter.isBack(100, 130));
    }

    @Test
    public void overlappingLoopsGetTheirOwnLanes() {
        List<WireRouter.Route> rs = WireRouter.routeAll(List.of(
                new WireRouter.In(800, 86, 160, 86, 0, 40, 40),
                new WireRouter.In(700, 86, 200, 86, 0, 40, 40),
                new WireRouter.In(100, 86, 400, 86, 0, 40, 40),
                new WireRouter.In(3000, 86, 2000, 86, 0, 40, 40)));
        assertEquals(14f, rs.get(0).ys()[2], EPS);
        assertEquals(14f - 8f, rs.get(1).ys()[2], EPS);
        // a forward wire and a far away loop don't take a lane
        assertFalse(rs.get(2).back());
        assertEquals(14f, rs.get(3).ys()[2], EPS);
    }

    @Test
    public void lanesAreReallyFreeNotJustCounted() {
        // the middle one overlaps both, the other two don't overlap each other. counting gives the last two one lane
        List<WireRouter.Route> rs = WireRouter.routeAll(List.of(
                new WireRouter.In(1000, 86, 0, 86, 0, 40, 40),
                new WireRouter.In(2000, 86, 1000, 86, 0, 40, 40),
                new WireRouter.In(3000, 86, 2000, 86, 0, 40, 40)));
        assertEquals(14f, rs.get(0).ys()[2], EPS);
        assertEquals(6f, rs.get(1).ys()[2], EPS);
        assertEquals(14f, rs.get(2).ys()[2], EPS);
    }

    @Test
    public void lanesOffTheGridStillKeepTheirDistance() {
        // tops 33 and 40, so the closest lanes come out at 7 and 14, and one step up from 14 is 1 px from 7
        List<WireRouter.Route> rs = WireRouter.routeAll(List.of(
                new WireRouter.In(800, 86, 160, 86, 0, 33, 33),
                new WireRouter.In(700, 86, 200, 86, 0, 40, 40)));
        assertEquals(7f, rs.get(0).ys()[2], EPS);
        assertEquals(-2f, rs.get(1).ys()[2], EPS);
    }

    @Test
    public void aLaneIsSkippedOnlyIfItIsTaken() {
        // a loop from a higher row sits in between: the wire under it still gets its own closest lane
        List<WireRouter.Route> rs = WireRouter.routeAll(List.of(
                new WireRouter.In(800, 86, 160, 86, 0, 40, 40),
                new WireRouter.In(700, 286, 200, 286, 0, 240, 240),
                new WireRouter.In(600, 86, 300, 86, 0, 40, 40),
                new WireRouter.In(500, 86, 300, 86, 0, 40, 40)));
        assertEquals(14f, rs.get(0).ys()[2], EPS);
        assertEquals(214f, rs.get(1).ys()[2], EPS);
        assertEquals(6f, rs.get(2).ys()[2], EPS);
        assertEquals(-2f, rs.get(3).ys()[2], EPS);
    }

    private static WireRouter.Elbows elbows(int thick, int radius, int... xy) {
        int n = xy.length / 2;
        int[] sx = new int[n];
        int[] sy = new int[n];
        for (int i = 0; i < n; i++) {
            sx[i] = xy[2 * i];
            sy[i] = xy[2 * i + 1];
        }
        return WireRouter.elbows(sx, sy, n, radius, thick);
    }

    @Test
    public void anElbowIsTwoTrimmedLegsAndOneSlice() {
        // right then down
        WireRouter.Elbows e = elbows(1, 10, 0, 0, 100, 0, 100, 50);
        assertEquals(List.of(new WireRouter.Seg(0, 0, 91, 1), new WireRouter.Seg(100, 10, 1, 40)), e.segs());
        assertEquals(List.of(new WireRouter.Corner(91, 0, 10, ShapeAtlas.TR)), e.corners());
    }

    @Test
    public void everyTurnPicksTheRightQuadrant() {
        assertEquals(ShapeAtlas.TR, elbows(1, 4, 0, 0, 20, 0, 20, 20).corners().get(0).quadrant());
        assertEquals(ShapeAtlas.TL, elbows(1, 4, 20, 0, 0, 0, 0, 20).corners().get(0).quadrant());
        assertEquals(ShapeAtlas.BR, elbows(1, 4, 0, 20, 20, 20, 20, 0).corners().get(0).quadrant());
        assertEquals(ShapeAtlas.BL, elbows(1, 4, 20, 20, 0, 20, 0, 0).corners().get(0).quadrant());
        // and the same corners entered from the vertical leg
        assertEquals(ShapeAtlas.TR, elbows(1, 4, 20, 20, 20, 0, 0, 0).corners().get(0).quadrant());
        assertEquals(ShapeAtlas.BL, elbows(1, 4, 0, 0, 0, 20, 20, 20).corners().get(0).quadrant());
    }

    @Test
    public void theRadiusIsClampedToHalfTheShorterLeg() {
        WireRouter.Elbows e = elbows(1, 10, 0, 0, 100, 0, 100, 8);
        assertEquals(4, e.corners().get(0).r());
    }

    @Test
    public void aTinyRadiusIsASquareJoinNotASmudge() {
        // ring(1) is one faint pixel, and ring(0) would quietly hand back ring(1)
        WireRouter.Elbows e = elbows(1, 10, 0, 0, 100, 0, 100, 3);
        assertTrue(e.corners().isEmpty());
        assertTrue(e.segs().contains(new WireRouter.Seg(100, 0, 1, 1)));
    }

    @Test
    public void aTwoPixelWireIsTwoRingsAndStopsBeforeRingZero() {
        WireRouter.Elbows e = elbows(2, 10, 0, 0, 100, 0, 100, 50);
        assertEquals(2, e.corners().size());
        assertEquals(10, e.corners().get(0).r());
        assertEquals(9, e.corners().get(1).r());
        // both rings share the outside edge the leg runs along, the bottom of the top leg stays one row in
        assertEquals(e.corners().get(0).left(), e.corners().get(1).left());
        assertEquals(e.corners().get(0).top() + 1, e.corners().get(1).top());

        WireRouter.Elbows tight = elbows(4, 10, 0, 0, 100, 0, 100, 6);
        for (WireRouter.Corner c : tight.corners()) {
            assertTrue(c.r() >= 1);
        }
    }

    @Test
    public void legsAndCornersNeverOverlapAnotherPixel() {
        // translucent wires would show every double covered pixel, so walk a loop back and count coverage
        int[] sx = {800, 818, 818, 136, 136, 154};
        int[] sy = {86, 86, 14, 14, 86, 86};
        for (int thick : new int[]{1, 2}) {
            int[] xs = sx.clone();
            int[] ys = sy.clone();
            Map<Long, Integer> covered = new HashMap<>();
            WireRouter.Elbows e = WireRouter.elbows(xs, ys, 6, 10, thick);
            for (WireRouter.Seg s : e.segs()) {
                for (int x = s.x(); x < s.x() + s.w(); x++) {
                    for (int y = s.y(); y < s.y() + s.h(); y++) {
                        covered.merge(((long) x << 32) | (y & 0xFFFFFFFFL), 1, Integer::sum);
                    }
                }
            }
            for (WireRouter.Corner c : e.corners()) {
                if (c.r() == 10) {
                    for (WireRouter.Seg s : e.segs()) {
                        boolean apart = s.x() + s.w() <= c.left() || c.left() + c.r() <= s.x()
                                || s.y() + s.h() <= c.top() || c.top() + c.r() <= s.y();
                        assertTrue("segment " + s + " runs under corner " + c, apart);
                    }
                }
            }
            for (int count : covered.values()) {
                assertEquals(1, count);
            }
        }
    }

    @Test
    public void roundingThatCollapsesALegDoesNotLeaveACorner() {
        // same y after rounding: the two riser points fall out and it's one line
        WireRouter.Elbows e = elbows(1, 10, 0, 50, 100, 50, 100, 50, 200, 50);
        assertTrue(e.corners().isEmpty());
        assertEquals(List.of(new WireRouter.Seg(0, 50, 200, 1)), e.segs());
    }

    @Test
    public void aWireDraggedLeftStillComesOutWhole() {
        WireRouter.Elbows e = elbows(1, 10, 100, 0, 50, 0, 50, 40, 0, 40);
        assertEquals(2, e.corners().size());
        assertEquals(3, e.segs().size());
    }

    // ----- hits

    private static NodeHits.Box box(String id, float x, float y, int outs) {
        return new NodeHits.Box(id, x, y, 176, 84, true, outs);
    }

    @Test
    public void topCardWins() {
        List<NodeHits.Box> boxes = List.of(box("a", 0, 0, 2), box("b", 50, 20, 2));
        assertEquals(1, NodeHits.card(boxes, 100, 50));
        assertEquals(0, NodeHits.card(boxes, 10, 5));
        assertEquals(-1, NodeHits.card(boxes, 500, 50));
    }

    @Test
    public void portsAreFoundOnTheEdgeAndByRow() {
        List<NodeHits.Box> boxes = List.of(box("a", 0, 0, 2));
        assertEquals(new NodeHits.PortHit(0, 0), NodeHits.port(boxes, 176, 46, 7));
        assertEquals(new NodeHits.PortHit(0, 1), NodeHits.port(boxes, 180, 66, 7));
        assertEquals(new NodeHits.PortHit(0, -1), NodeHits.port(boxes, -3, 44, 7));
        assertNull(NodeHits.port(boxes, 90, 46, 7));
        assertNull(NodeHits.port(boxes, 176, 20, 7));
    }

    @Test
    public void overlappingBoxesPickTheCloserPort() {
        List<NodeHits.Box> boxes = List.of(box("a", 0, 0, 2));
        // half zoom: the 14 screen px box is 28 world, wider than the 22 row
        assertEquals(new NodeHits.PortHit(0, 1), NodeHits.port(boxes, 176, 60, 14));
        assertEquals(new NodeHits.PortHit(0, 0), NodeHits.port(boxes, 176, 54, 14));
    }

    @Test
    public void aCardOnTopHidesThePortUnderIt() {
        List<NodeHits.Box> boxes = List.of(box("under", 0, 0, 2), box("over", 100, 0, 2));
        assertNull(NodeHits.port(boxes, 176, 46, 7));
    }

    @Test
    public void startHasNoInputToHit() {
        List<NodeHits.Box> boxes = List.of(new NodeHits.Box("s", 0, 0, 112, 60, false, 1));
        assertNull(NodeHits.port(boxes, 0, 46, 7));
    }

    @Test
    public void wiresAreHitWithinReachAndLastDrawnWins() {
        WireRouter.Route a = WireRouter.route(in(0, 50, 300, 50, 0), 0);
        WireRouter.Route b = WireRouter.route(in(0, 53, 300, 53, 0), 0);
        assertEquals(0, NodeHits.wire(List.of(a), 100, 55, 6));
        assertEquals(-1, NodeHits.wire(List.of(a), 100, 57, 6));
        assertEquals(1, NodeHits.wire(List.of(a, b), 100, 51, 6));
    }

    @Test
    public void distanceRunsAlongEveryLeg() {
        WireRouter.Route r = WireRouter.route(in(0, 0, 206, 100, 0), 0);
        assertEquals(0f, NodeHits.distance(r, 50, 0), EPS);
        assertEquals(3f, NodeHits.distance(r, 103, 50), EPS);
        assertEquals(0f, NodeHits.distance(r, 150, 100), EPS);
    }

    // ----- arrange

    private static AutoArrange.Node node(String id) {
        return new AutoArrange.Node(id, 84);
    }

    private static AutoArrange.Edge edge(String a, String b) {
        return new AutoArrange.Edge(a, b, false);
    }

    private static Map<String, AutoArrange.Placed> byId(List<AutoArrange.Placed> placed) {
        Map<String, AutoArrange.Placed> out = new HashMap<>();
        for (AutoArrange.Placed p : placed) {
            out.put(p.id(), p);
        }
        return out;
    }

    @Test
    public void layersFollowTheLongestPath() {
        Map<String, AutoArrange.Placed> p = byId(AutoArrange.arrange("s", List.of(node("s"), node("a"), node("b"), node("c")),
                List.of(edge("s", "a"), edge("a", "b"), edge("b", "c"), edge("s", "c"))));
        assertEquals(0, p.get("s").x());
        assertEquals(224, p.get("a").x());
        assertEquals(448, p.get("b").x());
        assertEquals(672, p.get("c").x());
    }

    @Test
    public void againWiresDoNotPushALayer() {
        Map<String, AutoArrange.Placed> p = byId(AutoArrange.arrange("s", List.of(node("s"), node("r"), node("g"), node("m")),
                List.of(edge("s", "r"), edge("r", "g"), edge("g", "m"), new AutoArrange.Edge("m", "r", true))));
        assertEquals(224, p.get("r").x());
        assertEquals(672, p.get("m").x());
    }

    @Test
    public void aRealCycleStillTerminatesAndLaysOut() {
        Map<String, AutoArrange.Placed> p = byId(AutoArrange.arrange("s", List.of(node("s"), node("a"), node("b")),
                List.of(edge("s", "a"), edge("a", "b"), edge("b", "a"), edge("b", "s"))));
        assertEquals(0, p.get("s").x());
        assertEquals(224, p.get("a").x());
        assertEquals(448, p.get("b").x());
    }

    @Test
    public void aSelfWireDoesNotHang() {
        Map<String, AutoArrange.Placed> p = byId(AutoArrange.arrange("s", List.of(node("s"), node("a")),
                List.of(edge("s", "a"), edge("a", "a"))));
        assertEquals(224, p.get("a").x());
    }

    @Test
    public void stackingUsesRealHeightsAndSnaps() {
        List<AutoArrange.Node> nodes = List.of(node("s"), new AutoArrange.Node("a", 101), node("b"));
        Map<String, AutoArrange.Placed> p = byId(AutoArrange.arrange("s", nodes, List.of(edge("s", "a"), edge("s", "b"))));
        assertEquals(0, p.get("a").y());
        // 101 + 32 = 133, up to the next 8
        assertEquals(136, p.get("b").y());
    }

    @Test
    public void barycenterUntanglesACrossing() {
        // a and b are a's and b's parents, given crossed: x1 wants to sit by b, x2 by a
        List<AutoArrange.Node> nodes = List.of(node("s"), node("a"), node("b"), node("x1"), node("x2"));
        Map<String, AutoArrange.Placed> p = byId(AutoArrange.arrange("s", nodes,
                List.of(edge("s", "a"), edge("s", "b"), edge("b", "x1"), edge("a", "x2"))));
        assertTrue(p.get("x2").y() < p.get("x1").y());
    }

    @Test
    public void unreachableNodesGoInARowUnderEverything() {
        Map<String, AutoArrange.Placed> p = byId(AutoArrange.arrange("s", List.of(node("s"), node("a"), node("lost1"), node("lost2")),
                List.of(edge("s", "a"), edge("lost1", "lost2"))));
        assertEquals(p.get("lost1").y(), p.get("lost2").y());
        assertTrue(p.get("lost1").y() >= 84 + 32);
        assertEquals(0, p.get("lost1").x());
        assertEquals(224, p.get("lost2").x());
        assertEquals(0, p.get("lost1").y() % 8);
    }

    @Test
    public void noStartMeansEverythingIsUnreachableAndNothingBreaks() {
        List<AutoArrange.Placed> placed = AutoArrange.arrange("missing", List.of(node("a"), node("b")), List.of(edge("a", "b"), edge("a", "gone")));
        assertEquals(2, placed.size());
        assertEquals(0, placed.get(0).y());
    }

    @Test
    public void sameInputSameLayoutAndPlacedInInputOrder() {
        List<AutoArrange.Node> nodes = new ArrayList<>();
        List<AutoArrange.Edge> edges = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            nodes.add(node("n" + i));
            if (i > 0) {
                edges.add(edge("n" + (i * 7 % 13 % Math.max(1, i)), "n" + i));
            }
        }
        List<AutoArrange.Placed> a = AutoArrange.arrange("n0", nodes, edges);
        List<AutoArrange.Placed> b = AutoArrange.arrange("n0", nodes, edges);
        assertEquals(a, b);
        assertEquals("n0", a.get(0).id());
        assertEquals("n39", a.get(39).id());
    }

    // ----- icons

    @Test
    public void iconLookupFallsBackByGroup() {
        assertEquals(Icon.REPEAT, NodeCard.iconFor("REPEAT", "flow"));
        assertEquals(Icon.PICKAXE, NodeCard.iconFor("mine", "act"));
        assertEquals(Icon.BOLT, NodeCard.iconFor("teleport", "act"));
        assertEquals(Icon.NODES, NodeCard.iconFor("teleport", "flow"));
        assertEquals(Icon.RING, NodeCard.iconFor(null, null));
    }

    // ----- theme

    @Test
    public void flowTokensHaveBothAppearances() {
        Theme t = StubCanvas.theme();
        assertEquals(0xFFBF5AF2, t.color(Theme.FLOW));
        assertEquals(0xFFD49BF7, t.color(Theme.FLOW_INK));
        t.setDark(false);
        assertEquals(0xFFAF52DE, t.color(Theme.FLOW));
        assertEquals(0xFF8E3DB8, t.color(Theme.FLOW_INK));
    }
}
