package adris.altoclef.ui.gui.kit;

import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

// the real shell's mouse and keys, into a canvas in a tab. the wood loop sits where the mock has it, panned to a known spot
public class NodeCanvasTest {

    private static final class CanvasTab implements Tab {
        final NodeCanvas canvas;

        CanvasTab(NodeCanvas canvas) {
            this.canvas = canvas;
        }

        @Override
        public String id() {
            return "nodes";
        }

        @Override
        public String title() {
            return "Nodes";
        }

        @Override
        public Icon icon() {
            return Icon.NODES;
        }

        @Override
        public Widget content() {
            return canvas;
        }
    }

    private NodeCanvasFixtures.Host host;
    private NodeCanvas canvas;
    private Preview p;

    @Before
    public void setUp() throws Exception {
        host = new NodeCanvasFixtures.Host(NodeCanvasFixtures.woodLoopCards(), NodeCanvasFixtures.woodLoopWires());
        p = new Preview(1920, 1200, 1f, new FakeFacade(), pv -> {
            canvas = new NodeCanvas(pv.theme, host, host);
            return List.of(new CanvasTab(canvas));
        });
        p.settle();
        canvas.view().restore(40, 100, 2);
    }

    private float sx(float wx) {
        return canvas.view().screenX(wx);
    }

    private float sy(float wy) {
        return canvas.view().screenY(wy);
    }

    // the bottom right corner, nothing lives there
    private float emptyX() {
        return canvas.x() + canvas.w() - 60;
    }

    private float emptyY() {
        return canvas.y() + canvas.h() - 60;
    }

    private void drag(float x0, float y0, float x1, float y1) {
        p.press(x0, y0);
        p.drag((x0 + x1) / 2f, (y0 + y1) / 2f);
        p.drag(x1, y1);
        p.release(x1, y1);
    }

    private void drawn() {
        p.render();
    }

    @Test
    public void draggingFromAPortToACardConnects() {
        // g's failed port, dropped in the middle of m's body and not on its dot
        drag(sx(384 + 176), sy(40 + 68), sx(608 + 90), sy(40 + 60));
        assertEquals(List.of("connect g failed m"), host.events);
    }

    @Test
    public void droppingOnTheInputDotConnectsToo() {
        drag(sx(384 + 176), sy(40 + 68), sx(608 - 4), sy(40 + 46));
        assertEquals(List.of("connect g failed m"), host.events);
    }

    @Test
    public void droppingOnEmptyAsksWhatToAdd() {
        drag(sx(384 + 176), sy(40 + 68), sx(900), sy(500));
        assertEquals(List.of("drop g failed 900 500"), host.events);
    }

    @Test
    public void aWireToItselfOrToStartDoesNothing() {
        drag(sx(384 + 176), sy(40 + 68), sx(384 + 50), sy(40 + 50));
        drag(sx(384 + 176), sy(40 + 68), sx(50), sy(40 + 30));
        assertEquals(List.of(), host.events);
    }

    @Test
    public void theGhostOnlyLastsAsLongAsTheDrag() {
        StubCanvas stub = new StubCanvas(1920, 1200);
        p.press(sx(384 + 176), sy(40 + 68));
        p.drag(sx(900), sy(500));
        canvas.draw(stub, p.frame());
        int colour = p.theme.color(Theme.ACCENT);
        assertTrue(stub.calls.stream().anyMatch(c -> c.kind().equals("fillRect") && c.argb() == colour));
        p.release(sx(900), sy(500));
        stub.calls.clear();
        canvas.draw(stub, p.frame());
        assertFalse(stub.calls.stream().anyMatch(c -> c.kind().equals("fillRect") && c.argb() == colour));
    }

    @Test
    public void pressingACardSelectsItAndDraggingMovesItSnapped() {
        float x0 = sx(384 + 50);
        float y0 = sy(40 + 10);
        p.press(x0, y0);
        assertEquals(List.of("select g"), host.events);
        p.drag(x0 + 37, y0 + 21);
        p.release(x0 + 37, y0 + 21);
        // 421 and 61, onto the 8 grid
        assertEquals(List.of("select g", "move g 424 64"), host.events);
    }

    @Test
    public void dragMathsUsesTheZoom() {
        canvas.view().restore(40, 100, 0);
        float x0 = sx(384 + 50);
        float y0 = sy(40 + 10);
        p.press(x0, y0);
        p.drag(x0 + 40, y0);
        p.release(x0 + 40, y0);
        // 40 screen px at half zoom is 80 world
        assertEquals("move g 464 40", host.events.get(1));
    }

    @Test
    public void wigglingUnderTheThresholdIsAClick() {
        float x0 = sx(384 + 50);
        float y0 = sy(40 + 10);
        p.press(x0, y0);
        p.drag(x0 + 2, y0 + 1);
        p.release(x0 + 2, y0 + 1);
        assertEquals(List.of("select g"), host.events);
    }

    @Test
    public void pressingTheAlreadySelectedCardDoesNotReselect() {
        host.selectedCard = "g";
        p.click(sx(384 + 50), sy(40 + 10));
        assertEquals(List.of(), host.events);
    }

    @Test
    public void aDraggedCardFloatsOverTheOthers() {
        StubCanvas stub = new StubCanvas(1920, 1200);
        canvas.draw(stub, p.frame());
        assertTrue(stub.texts().indexOf("Go to block") < stub.texts().indexOf("Mine"));
        p.press(sx(384 + 50), sy(40 + 10));
        p.drag(sx(608 + 50), sy(40 + 10));
        stub.calls.clear();
        canvas.draw(stub, p.frame());
        assertTrue(stub.texts().indexOf("Mine") < stub.texts().indexOf("Go to block"));
        p.release(sx(608 + 50), sy(40 + 10));
        assertEquals("move g 608 40", host.events.get(1));
    }

    @Test
    public void cardsOnTopOfEachOtherDoNotShareABatch() {
        // anything of the top card has to land after all of the one under it, or the lower title shows through
        host.cards = List.of(NodeCanvasFixtures.at(host.cards.get(2), 200, 200), NodeCanvasFixtures.at(host.cards.get(3), 230, 210));
        host.wires = List.of();
        StubCanvas stub = new StubCanvas(1920, 1200);
        canvas.draw(stub, p.frame());
        int lowerTitle = -1;
        int upperFill = -1;
        for (int i = 0; i < stub.calls.size(); i++) {
            StubCanvas.Call c = stub.calls.get(i);
            if ("Go to block".equals(c.text())) {
                lowerTitle = i;
            }
            if (c.kind().equals("fillRect") && lowerTitle >= 0 && c.x() == Math.round(sx(230))) {
                upperFill = i;
                break;
            }
        }
        assertTrue(lowerTitle >= 0);
        assertTrue(upperFill > lowerTitle);
    }

    @Test
    public void cardsThatDoNotTouchShareOne() {
        // the point of the batches: every fill of every card comes before every text
        StubCanvas stub = new StubCanvas(1920, 1200);
        canvas.draw(stub, p.frame());
        int firstText = -1;
        int lastBodyFill = -1;
        int surface = p.theme.color(Theme.SURFACE);
        for (int i = 0; i < stub.calls.size(); i++) {
            StubCanvas.Call c = stub.calls.get(i);
            if (c.kind().equals("text") && firstText < 0) {
                firstText = i;
            }
            if (c.kind().equals("fillRect") && c.argb() == surface) {
                lastBodyFill = i;
            }
        }
        assertTrue(lastBodyFill < firstText);
    }

    private NodeCard withOuts(NodeCard c, List<NodeCard.Port> outs) {
        return new NodeCard(c.id(), c.x(), c.y(), c.small(), c.title(), c.icon(), c.group(), c.summary(), c.itemIds(), outs,
                c.hasIn(), c.inWired(), c.state(), c.badgeText(), c.badgeIcon(), c.why(), c.warn());
    }

    private void replaceCard(String id, List<NodeCard.Port> outs) {
        List<NodeCard> next = new ArrayList<>();
        for (NodeCard c : host.cards) {
            next.add(c.id().equals(id) ? withOuts(c, outs) : c);
        }
        host.cards = next;
    }

    @Test
    public void aPortThatGoesAwayMidDragTakesTheWireWithIt() {
        p.press(sx(384 + 176), sy(40 + 68));
        p.drag(sx(900), sy(500));
        replaceCard("g", List.of(new NodeCard.Port("ok", true)));
        p.release(sx(900), sy(500));
        assertEquals(List.of(), host.events);
    }

    @Test
    public void portsThatShuffleMidDragConnectWhatWasGrabbed() {
        p.press(sx(384 + 176), sy(40 + 68));
        p.drag(sx(900), sy(500));
        replaceCard("g", List.of(new NodeCard.Port("failed", false), new NodeCard.Port("ok", true)));
        drawn();
        p.release(sx(900), sy(500));
        assertEquals(List.of("drop g failed 900 500"), host.events);
    }

    @Test
    public void aCardDeletedMidDragDoesNotThrow() {
        p.press(sx(384 + 176), sy(40 + 68));
        p.drag(sx(900), sy(500));
        host.cards = host.cards.stream().filter(c -> !c.id().equals("g")).toList();
        drawn();
        p.release(sx(900), sy(500));
        assertEquals(List.of(), host.events);
    }

    @Test
    public void aClickFindsTheWireThatIsDrawnOnTop() {
        // two wires down one line. the first in the list ran, so it's drawn over the second
        host.cards = List.of(NodeCanvasFixtures.at(host.cards.get(0), 0, 100), NodeCanvasFixtures.at(host.cards.get(4), 400, 100),
                NodeCanvasFixtures.at(host.cards.get(3), 400, 300));
        host.wires = List.of(new NodeWire("s", "out", "z", "in", true, false), new NodeWire("s", "out", "m", "in"));
        p.click(sx(200), sy(100 + 46));
        assertEquals(List.of("wire s:out:z:in"), host.events);
    }

    @Test
    public void escLetsGoOfWhatIsHeld() {
        p.press(sx(434), sy(50));
        p.drag(sx(500), sy(90));
        assertTrue(canvas.keyDown(Keys.ESCAPE, 0));
        // still selected: that esc was about the drag
        assertEquals("g", host.selectedCard);
        assertFalse(canvas.mouseUp(sx(500), sy(90), 0, 0));
        assertTrue(host.events.stream().noneMatch(e -> e.startsWith("move")));
    }

    @Test
    public void aPressThatWentElsewhereEndsAStaleOne() {
        p.press(sx(434), sy(50));
        canvas.pressedElsewhere();
        assertFalse(canvas.mouseDrag(600, 300, 0, 1, 1));
    }

    @Test
    public void aPressOutsideEndsAStaleOneToo() {
        p.press(sx(434), sy(50));
        assertFalse(canvas.mouseDown(canvas.x() - 5, canvas.y() - 5, 0, 0));
        assertFalse(canvas.mouseDrag(600, 300, 0, 1, 1));
    }

    @Test
    public void noChipOnAReadOnlyGraph() {
        host.readOnly = true;
        float mx = (160 + 176 + 384 - 6) / 2f + 4;
        float my = 40 + 68 + (176 + 46 - 40 - 68) / 2f;
        host.selectedWire = "r:done:z:in";
        StubCanvas stub = new StubCanvas(1920, 1200);
        canvas.draw(stub, p.frame());
        assertEquals(0, stub.calls.stream().filter(c -> Icon.TRASH.name().equals(c.text())).count());
        p.click(sx(mx), sy(my));
        assertFalse(host.events.contains("delete"));
    }

    // ----- the corner calls

    private static final class CornerRecorder extends StubCanvas {
        final List<float[]> clips = new ArrayList<>();

        @Override
        public void pushClip(float x, float y, float w, float h) {
            clips.add(new float[]{x, y, w, h});
            super.pushClip(x, y, w, h);
        }
    }

    @Test
    public void theDefaultCornerClipsTheWholeRingDownToOneQuarter() {
        for (int quadrant : new int[]{ShapeAtlas.TL, ShapeAtlas.TR, ShapeAtlas.BL, ShapeAtlas.BR}) {
            CornerRecorder c = new CornerRecorder();
            c.cornerLine(100, 200, 10, quadrant, 0xFFFFFFFF);
            assertEquals(0, c.clipDepth);
            assertArrayEquals(new float[]{100, 200, 10, 10}, c.clips.get(0), 0f);
            StubCanvas.Call ring = c.calls.get(0);
            assertEquals("strokeRoundRect", ring.kind());
            assertEquals(quadrant == ShapeAtlas.TR || quadrant == ShapeAtlas.BR ? 90f : 100f, ring.x(), 0f);
            assertEquals(quadrant == ShapeAtlas.BL || quadrant == ShapeAtlas.BR ? 190f : 200f, ring.y(), 0f);
            assertEquals(20f, ring.w(), 0f);
        }
    }

    @Test
    public void aZeroRadiusCornerIsNothing() {
        CornerRecorder c = new CornerRecorder();
        c.cornerLine(0, 0, 0, ShapeAtlas.TL, 0xFFFFFFFF);
        c.cornerFill(0, 0, 0, ShapeAtlas.TL, 0xFFFFFFFF);
        assertTrue(c.calls.isEmpty());
        assertTrue(c.clips.isEmpty());
    }

    @Test
    public void aTexturedCornerIsOneBlitAndNoScissor() {
        CountingCanvas c = new CountingCanvas(p);
        c.cornerLine(10, 10, 8, ShapeAtlas.BR, 0xFFFFFFFF);
        c.cornerFill(10, 10, 8, ShapeAtlas.BR, 0xFFFFFFFF);
        assertEquals(2, c.blits);
        assertEquals(0, c.clips);
        // ring(0) would hand back ring(1), so r 0 has to stop before it gets there
        c.reset();
        c.cornerLine(10, 10, 0, ShapeAtlas.BR, 0xFFFFFFFF);
        c.cornerLine(10, 10, 8, ShapeAtlas.BR, 0);
        assertEquals(0, c.blits);
    }

    @Test
    public void fadeCanvasFadesAndShiftsCorners() {
        StubCanvas base = new StubCanvas();
        new FadeCanvas().reset(base, 0.5f, 7f).cornerLine(10, 20, 4, ShapeAtlas.TL, 0xFF112233);
        StubCanvas.Call ring = base.calls.get(0);
        assertEquals(27f, ring.y(), 0f);
        assertEquals(Theme.alpha(0xFF112233, 0.5f), ring.argb());
    }

    @Test
    public void pressingAWireSelectsItAndTheChipDeletesIt() {
        // r's done wire comes down the riser at the middle: that's where it's a wire and not a card
        float mx = (160 + 176 + 384 - 6) / 2f + 4;
        float my = 40 + 68 + (176 + 46 - 40 - 68) / 2f;
        p.click(sx(mx), sy(my));
        assertEquals(List.of("wire r:done:z:in"), host.events);
        drawn();
        p.click(sx(mx), sy(my));
        assertEquals(List.of("wire r:done:z:in", "delete"), host.events);
    }

    @Test
    public void pressingAFarAwayWireMissesIt() {
        float mx = (160 + 176 + 384 - 6) / 2f + 4;
        float my = 40 + 68 + (176 + 46 - 40 - 68) / 2f;
        p.click(sx(mx + 20), sy(my));
        assertTrue(host.events.stream().noneMatch(e -> e.startsWith("wire")));
    }

    @Test
    public void draggingEmptyPansAndAClickDeselects() {
        float x0 = emptyX();
        float y0 = emptyY();
        float px = canvas.view().panX();
        drag(x0, y0, x0 + 30, y0 - 20);
        assertEquals(px + 30, canvas.view().panX(), 0.01f);
        assertTrue(host.events.contains("view"));
        assertFalse(host.events.contains("select null"));

        host.events.clear();
        host.selectedCard = "g";
        p.click(emptyX(), emptyY());
        assertEquals(List.of("select null"), host.events);

        host.events.clear();
        p.click(emptyX(), emptyY());
        assertEquals(List.of(), host.events);
    }

    @Test
    public void theWheelIsOneStepAtTheCursor() {
        float mx = sx(500);
        float my = sy(90);
        float wx = canvas.view().worldX(mx);
        p.scroll(mx, my, 1f);
        assertEquals(1.25f, canvas.view().zoom(), 0.001f);
        assertEquals(wx, canvas.view().worldX(mx), 0.01f);
        assertTrue(host.events.contains("view"));
        p.scroll(mx, my, -2f);
        assertEquals(1f, canvas.view().zoom(), 0.001f);
    }

    @Test
    public void theWheelWaitsWhileSomethingIsHeld() {
        p.press(sx(434), sy(50));
        p.scroll(sx(434), sy(50), 1f);
        assertEquals(1f, canvas.view().zoom(), 0.001f);
        p.release(sx(434), sy(50));
    }

    @Test
    public void onlyTakesDragsAndUpsForItsOwnPress() {
        assertFalse(canvas.mouseDrag(500, 500, 0, 1, 1));
        assertFalse(canvas.mouseUp(500, 500, 0, 0));
        // pressed somewhere that isn't us
        assertFalse(canvas.mouseDown(canvas.x() - 5, canvas.y() - 5, 0, 0));
        assertFalse(canvas.mouseUp(500, 500, 0, 0));
    }

    @Test
    public void onlyTheLeftButton() {
        float mx = sx(434);
        float my = sy(50);
        assertFalse(canvas.mouseDown(mx, my, 1, 0));
        assertFalse(canvas.mouseDrag(mx + 20, my, 1, 20, 0));
        assertFalse(canvas.mouseUp(mx + 20, my, 1, 0));
        assertEquals(List.of(), host.events);
    }

    @Test
    public void aLostUpDoesNotCarryIntoTheNextPress() {
        p.press(sx(434), sy(50));
        p.drag(sx(500), sy(90));
        // the up never comes. the next press starts over and moves nothing
        host.events.clear();
        p.click(emptyX(), emptyY());
        assertTrue(host.events.stream().noneMatch(e -> e.startsWith("move")));
    }

    @Test
    public void hidingCancelsAPress() {
        p.press(sx(434), sy(50));
        p.drag(sx(500), sy(90));
        canvas.setVisible(false);
        canvas.setVisible(true);
        assertFalse(canvas.mouseDrag(600, 300, 0, 1, 1));
        assertFalse(canvas.mouseUp(600, 300, 0, 0));
        assertTrue(host.events.stream().noneMatch(e -> e.startsWith("move")));
    }

    @Test
    public void cancelPressIsThereForATabSwitch() {
        p.press(sx(434), sy(50));
        canvas.cancelPress();
        assertFalse(canvas.mouseUp(600, 300, 0, 0));
    }

    @Test
    public void readOnlyLooksButDoesNotTouch() {
        host.readOnly = true;
        drag(sx(384 + 176), sy(40 + 68), sx(608 + 90), sy(40 + 60));
        assertTrue(host.events.stream().noneMatch(e -> e.startsWith("connect")));
        host.events.clear();
        drag(sx(434), sy(50), sx(500), sy(90));
        assertTrue(host.events.stream().noneMatch(e -> e.startsWith("move")));
        host.selectedCard = "g";
        p.click(sx(434), sy(50));
        p.key(Keys.DELETE);
        assertFalse(host.events.contains("delete"));
    }

    @Test
    public void clickingFocusesItSoDeleteArrives() {
        p.click(sx(434), sy(50));
        assertTrue(canvas.isFocused());
        p.key(Keys.DELETE);
        assertTrue(host.events.contains("delete"));
    }

    @Test
    public void backspaceDeletesToo() {
        p.click(sx(434), sy(50));
        p.key(Keys.BACKSPACE);
        assertTrue(host.events.contains("delete"));
    }

    @Test
    public void escDeselectsThenLetsGo() {
        p.click(sx(434), sy(50));
        assertTrue(canvas.keyDown(Keys.ESCAPE, 0));
        assertEquals("select null", host.events.get(host.events.size() - 1));
        // nothing selected now: not ours, the shell blurs and the esc after that closes the panel
        assertFalse(canvas.keyDown(Keys.ESCAPE, 0));
    }

    @Test
    public void deleteWithNothingSelectedIsNotOurs() {
        assertFalse(canvas.keyDown(Keys.DELETE, 0));
    }

    @Test
    public void aPressOnSomethingFloatingDisarmsDelete() {
        p.click(sx(434), sy(50));
        canvas.pressedElsewhere();
        assertFalse(canvas.keyDown(Keys.DELETE, 0));
        p.click(sx(434), sy(50));
        assertTrue(canvas.keyDown(Keys.DELETE, 0));
    }

    @Test
    public void hoverLightsAPortWithoutAPress() {
        p.hover(sx(384 + 176), sy(40 + 46));
        int colour = p.theme.color(Theme.ACCENT);
        CountingCanvas c = new CountingCanvas(p);
        canvas.draw(c, p.frame());
        assertTrue(c.cornerColours.contains(colour));
        p.hover(emptyX(), emptyY());
        c = new CountingCanvas(p);
        canvas.draw(c, p.frame());
        assertFalse(c.cornerColours.contains(colour));
    }

    @Test
    public void wiringLightsTheInputOfTheCardUnderTheMouse() {
        p.press(sx(384 + 176), sy(40 + 68));
        p.drag(sx(608 + 90), sy(40 + 60));
        // the output we're dragging from, plus m's input
        CountingCanvas c = new CountingCanvas(p);
        canvas.draw(c, p.frame());
        assertEquals(8, c.cornerColours.stream().filter(x -> x == p.theme.color(Theme.ACCENT)).count());
        p.release(sx(608 + 90), sy(40 + 60));
    }

    @Test
    public void aNewListIsWhatTriggersARebuild() {
        drawn();
        List<NodeCard> moved = new ArrayList<>();
        for (NodeCard c : host.cards) {
            moved.add(c.id().equals("g") ? NodeCanvasFixtures.at(c, 600, 300) : c);
        }
        host.cards = moved;
        p.click(sx(600 + 50), sy(300 + 10));
        assertEquals("select g", host.events.get(host.events.size() - 1));
    }

    @Test
    public void aWireToANodeThatIsGoneIsJustNotThere() {
        host.wires = List.of(new NodeWire("s", "out", "gone", "in"), new NodeWire("nope", "ok", "r", "in"));
        drawn();
        p.click(sx(100), sy(40));
        assertEquals(List.of(), host.events.stream().filter(e -> e.startsWith("wire")).toList());
    }

    @Test
    public void anEmptyGraphDrawsAndFitsWithoutFuss() {
        host.cards = List.of();
        host.wires = List.of();
        drawn();
        CanvasView.Rect room = new CanvasView.Rect(0, 0, canvas.w(), canvas.h());
        canvas.fit(room, room);
        assertEquals(1f, canvas.view().zoom(), 0.001f);
    }

    @Test
    public void fitTakesTheLoopLaneIntoAccount() {
        CanvasView.Rect b = canvas.worldBounds();
        assertEquals(40f - 26f, b.y(), 0.01f);
        // past the last card: the loop leaves the mine card and goes out 18 before it turns up
        assertEquals(608f + 176f + 18f, b.x() + b.w(), 0.01f);
    }

    @Test
    public void cardHeightAgreesWithTheTextItHas() {
        NodeCard start = host.cards.get(0);
        // "When you press Run" takes two lines on a small card
        assertEquals(30 + 8 + 30 + 8, canvas.cardHeight(start));
    }
}
