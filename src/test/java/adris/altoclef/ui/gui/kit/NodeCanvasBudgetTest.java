package adris.altoclef.ui.gui.kit;

import adris.altoclef.ui.gui.preview.Java2dCanvas;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

// 200 cards and 400 wires, every card on screen at every zoom, which no real window manages at 1.5. this is a call
// budget: it catches someone putting a measure or a scissor in the frame, real fps needs the game. the limits are what
// got measured when this was written, plus 20%. a fill/blit switch is a pipeline switch in the game, those are the
// expensive ones: a card drawn top to bottom is about 18 of them, which is what the batches are there to stop
public class NodeCanvasBudgetTest {

    private record Budget(int calls, int switches, int pageSwitches) {
    }

    private static final Budget[] LIMITS = {
            // 0.5, 7975 calls, 5 switches, 0 pages
            new Budget(9570, 6, 1),
            // 0.75 and 1, 16111 calls, 5 switches, 2 pages
            new Budget(19333, 6, 3),
            new Budget(19333, 6, 3),
            // 1.25 and 1.5, 16159 calls, 5 switches, 2 pages. only the dots differ
            new Budget(19391, 6, 3),
            new Budget(19391, 6, 3),
    };

    private GlyphAtlas glyphs;
    private ShapeAtlas shapes;
    private Theme theme;
    private NodeCanvasFixtures.Host host;
    private NodeCanvas canvas;

    @Before
    public void setUp() throws Exception {
        glyphs = new GlyphAtlas(AwtFonts.load());
        shapes = new ShapeAtlas();
        theme = new Theme(1f, glyphs);
        host = new NodeCanvasFixtures.Host(NodeCanvasFixtures.stressCards(), NodeCanvasFixtures.stressWires());
        canvas = new NodeCanvas(theme, host, host);
    }

    private static Frame frame() {
        return new Frame(10_000_000_000L, 1f / 60f, -100, -100);
    }

    // big enough that culling doesn't help at this zoom
    private void showAll(int zoomIndex) {
        float z = CanvasView.zoomOf(zoomIndex);
        canvas.setBounds(0, 0, Math.round(4600 * z), Math.round(1400 * z));
        canvas.view().restore(16, 60, zoomIndex);
    }

    private CountingCanvas draw() {
        CountingCanvas c = new CountingCanvas(glyphs, shapes, 8000, 3000);
        c.setTheme(theme);
        canvas.draw(c, frame());
        // the first frame fills the caches, the one after is the one that counts
        c.reset();
        canvas.draw(c, frame());
        assertEquals(0, c.depth);
        return c;
    }

    @Test
    public void everyZoomStaysUnderItsBudget() throws IOException {
        StringBuilder report = new StringBuilder("zoom calls fills blits switches pages texts icons items clips shadows\n");
        for (int i = 0; i < CanvasView.steps(); i++) {
            showAll(i);
            CountingCanvas c = draw();
            report.append(String.format("%.2f %d %d %d %d %d %d %d %d %d %d%n", CanvasView.zoomOf(i), c.calls(), c.fills, c.blits,
                    c.switches, c.pageSwitches, c.texts, c.icons, c.items, c.clips, c.shadows));
            assertTrue("scissor pushes at " + i + ": " + c.clips, c.clips <= 2);
            Budget b = LIMITS[i];
            assertTrue("calls at zoom " + i + ": " + c.calls() + " > " + b.calls, c.calls() <= b.calls);
            assertTrue("switches at zoom " + i + ": " + c.switches + " > " + b.switches, c.switches <= b.switches);
            assertTrue("page switches at zoom " + i + ": " + c.pageSwitches + " > " + b.pageSwitches, c.pageSwitches <= b.pageSwitches);
        }
        report.append(java2dTimes());
        Path out = Paths.get("build", "gui-preview", "nodecanvas_budget.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.out.println(report);
    }

    @Test
    public void noTextNoIconsNoShadowAtHalfZoom() {
        showAll(0);
        CountingCanvas c = draw();
        assertEquals(0, c.texts);
        assertEquals(0, c.icons);
        assertEquals(0, c.items);
        assertEquals(0, c.shadows);
    }

    @Test
    public void noShadowsWhenAScreenfulOfCardsIsUp() {
        showAll(2);
        assertEquals(0, draw().shadows);
        // and a few of them do get one
        canvas.setBounds(0, 0, 800, 500);
        assertTrue(draw().shadows > 0);
    }

    @Test
    public void offScreenCostsNothing() {
        canvas.setBounds(0, 0, 1600, 900);
        canvas.view().restore(50000, 50000, 2);
        CountingCanvas c = draw();
        assertEquals(0, c.texts);
        // the field's own rounded fill and hairline is all that's left once the graph is culled
        assertEquals(8, c.blits);
    }

    @Test
    public void textIsMeasuredOnceNotPerFrameAndAMoveCostsNothing() {
        int[] measures = new int[1];
        TextMeasurer counting = new TextMeasurer() {
            @Override
            public float measure(String s, TextStyle style) {
                measures[0]++;
                return glyphs.measure(s, style);
            }

            @Override
            public float ascent(TextStyle style) {
                return glyphs.ascent(style);
            }

            @Override
            public float descent(TextStyle style) {
                return glyphs.descent(style);
            }
        };
        NodeCanvas c2 = new NodeCanvas(new Theme(1f, counting), host, host);
        c2.setBounds(0, 0, 4600, 1400);
        CountingCanvas c = new CountingCanvas(glyphs, shapes, 8000, 3000);
        c.setTheme(theme);
        c2.draw(c, frame());
        assertTrue(measures[0] > 0);
        measures[0] = 0;
        c2.draw(c, frame());
        c2.draw(c, frame());
        assertEquals(0, measures[0]);
        // a new list with one card somewhere else: same text, same layouts
        host.move("n7", 100, 200);
        c2.draw(c, frame());
        assertEquals(0, measures[0]);
        // a new zoom measures once, then never again
        c2.view().restore(0, 0, 3);
        c2.draw(c, frame());
        assertTrue(measures[0] > 0);
        measures[0] = 0;
        c2.draw(c, frame());
        assertEquals(0, measures[0]);
    }

    @Test
    public void zoomingKeepsTheLineBreaks() {
        NodeCard repeat = host.cards.get(0);
        int height = canvas.cardHeight(repeat);
        for (int i = 0; i < CanvasView.steps(); i++) {
            showAll(i);
            draw();
            assertEquals(height, canvas.cardHeight(repeat));
        }
    }

    @Test
    public void itemSizesAreWholeMultiplesOf16OrNothing() {
        assertEquals(0, NodeCardLayout.itemSize(1f, 0.5f));
        assertEquals(16, NodeCardLayout.itemSize(1f, 0.75f));
        assertEquals(16, NodeCardLayout.itemSize(1f, 1f));
        assertEquals(16, NodeCardLayout.itemSize(1f, 1.25f));
        assertEquals(32, NodeCardLayout.itemSize(1f, 1.5f));
        assertEquals(0, NodeCardLayout.itemSize(0.75f, 0.75f));
        // big scale, small zoom: under 0.75 nothing shows no matter how big it would come out
        assertEquals(0, NodeCardLayout.itemSize(2f, 0.5f));
    }

    @Test
    public void textHidesByZoomAndByRealSize() {
        assertFalse(NodeCardLayout.showsText(1f, 0.5f));
        assertTrue(NodeCardLayout.showsText(1f, 0.75f));
        assertFalse(NodeCardLayout.showsText(0.75f, 0.75f));
        assertTrue(NodeCardLayout.showsText(0.75f, 1f));
        assertFalse(NodeCardLayout.showsText(2f, 0.5f));
    }

    // information only: this is a software blitter on whatever box runs the build, the game's a different animal
    private String java2dTimes() {
        StringBuilder sb = new StringBuilder("\njava2d frame, 1600x900 window, median of 40:\n");
        for (int i = 0; i < CanvasView.steps(); i++) {
            canvas.setBounds(0, 0, 1600, 900);
            canvas.view().restore(16, 60, i);
            Java2dCanvas c = new Java2dCanvas(1600, 900, glyphs, shapes);
            c.setTheme(theme);
            canvas.draw(c, frame());
            long[] times = new long[40];
            for (int t = 0; t < times.length; t++) {
                long t0 = System.nanoTime();
                canvas.draw(c, frame());
                times[t] = System.nanoTime() - t0;
            }
            java.util.Arrays.sort(times);
            sb.append(String.format("%.2f %.2f ms%n", CanvasView.zoomOf(i), times[20] / 1e6));
        }
        // 1600x900 culls most of the graph above 0.5, so these two are all 200 cards and 400 wires for real
        sb.append("java2d frame, all 200 cards on screen, median of 40:\n");
        for (int i : new int[]{0, 2}) {
            showAll(i);
            Java2dCanvas c = new Java2dCanvas(canvas.w(), canvas.h(), glyphs, shapes);
            c.setTheme(theme);
            canvas.draw(c, frame());
            long[] times = new long[40];
            for (int t = 0; t < times.length; t++) {
                long t0 = System.nanoTime();
                canvas.draw(c, frame());
                times[t] = System.nanoTime() - t0;
            }
            java.util.Arrays.sort(times);
            sb.append(String.format("%.2f %.2f ms%n", CanvasView.zoomOf(i), times[20] / 1e6));
        }
        return sb.toString();
    }
}
