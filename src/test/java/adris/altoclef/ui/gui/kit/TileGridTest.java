package adris.altoclef.ui.gui.kit;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// scale 1, so design px are device px: a header row is 14 + 17 + 8 = 39, a tile row 80 plus 8 when another follows
public class TileGridTest {

    private static final long MS = 1_000_000L;

    private final StubCanvas canvas = new StubCanvas();
    private final Theme theme = new Theme(1f, canvas);
    private TileGrid grid;
    private final List<String> clicks = new ArrayList<>();
    private List<Tile> a;
    private List<Tile> b;
    private List<Tile> c;
    private long clock = 1000;

    private List<Tile> tiles(String prefix, int n) {
        List<Tile> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String name = prefix + i;
            out.add(new Tile(theme).setName(name).setSwitch(i % 2 == 0).setOnClick(() -> clicks.add(name)));
        }
        return out;
    }

    @Before
    public void setUp() {
        grid = new TileGrid(theme);
        a = tiles("a", 5);
        b = tiles("b", 1);
        c = tiles("c", 8);
        grid.setSections(List.of(new TileGrid.Section("Alpha", a), new TileGrid.Section("Empty", List.of()),
                new TileGrid.Section("Beta", b), new TileGrid.Section("Gamma", c)));
        // inner width 700 - 16 - 16 = 668, over the 560 that four columns need
        grid.setBounds(0, 0, 700, 300);
        draw();
    }

    private void draw() {
        clock += 16;
        canvas.calls.clear();
        grid.draw(canvas, new Frame(clock * MS, 0.016f, -1, -1));
    }

    private void drawAfter(long ms) {
        clock += ms;
        canvas.calls.clear();
        grid.draw(canvas, new Frame(clock * MS, 0.016f, -1, -1));
    }

    @Test
    public void unevenSectionsFlattenIntoHeadersAndRowsOfFour() {
        List<TileGrid.Row> rows = grid.items();
        // Alpha, [4], [1], Beta, [1], Gamma, [4], [4]. the empty section leaves nothing behind, not even its header
        assertEquals(8, rows.size());
        assertTrue(rows.get(0).header());
        assertEquals("Alpha", rows.get(0).title());
        assertEquals(4, rows.get(1).tiles().size());
        assertEquals(1, rows.get(2).tiles().size());
        assertEquals("Beta", rows.get(3).title());
        assertEquals(1, rows.get(4).tiles().size());
        assertEquals("Gamma", rows.get(5).title());
        assertEquals(4, rows.get(6).tiles().size());
        assertEquals(4, rows.get(7).tiles().size());
        int[] tops = {0, 39, 127, 207, 246, 326, 365, 453};
        for (int i = 0; i < tops.length; i++) {
            assertEquals("row " + i, tops[i], rows.get(i).top());
        }
        // a row followed by another of its section carries the 8 gap, the last of a section does not
        assertEquals(88, rows.get(1).height());
        assertEquals(80, rows.get(2).height());
    }

    @Test
    public void scrollExtentsCoverTheLastRowPlusTheBottomPad() {
        // last row ends at 533, 20 of padding under it
        assertEquals(553f, grid.scroller().contentHeight(), 0.01f);
        assertEquals(253f, grid.scroller().maxOffset(), 0.01f);
        grid.scroll(10, 10, -100);
        drawAfter(400);
        assertEquals(253f, grid.scroller().offset(clock * MS), 0.01f);
        // the last tile now ends 20 above the bottom
        Tile last = c.get(7);
        assertEquals(300 - 20, last.y() + last.h());
    }

    @Test
    public void tilesSitInFourEqualColumnsAndDropToTwoWhenNarrow() {
        assertEquals(4, grid.columns());
        Tile first = a.get(0);
        Tile second = a.get(1);
        assertEquals(16, first.x());
        assertEquals(39, first.y());
        assertEquals(80, first.h());
        assertEquals(161, first.w());
        assertEquals(16 + 161 + 8, second.x());
        assertEquals(a.get(3).x() + a.get(3).w(), 700 - 16);
        grid.setBounds(0, 0, 500, 300);
        draw();
        assertEquals(2, grid.columns());
        // 5 tiles in twos: Alpha, [2], [2], [1]
        assertEquals(2, grid.items().get(1).tiles().size());
        assertEquals(1, grid.items().get(3).tiles().size());
    }

    @Test
    public void clickAndHoverOnlyInsideTheVisibleGrid() {
        Tile t = a.get(1);
        float cx = t.x() + t.w() / 2f;
        float cy = t.y() + t.h() / 2f;
        grid.mouseMove(cx, cy);
        assertTrue(t.hovered);
        assertFalse(a.get(0).hovered);
        grid.mouseDown(cx, cy, 0, 0);
        grid.mouseUp(cx, cy, 0, 0);
        assertEquals(List.of("a1"), clicks);
        // a release somewhere else is not a click
        grid.mouseDown(cx, cy, 0, 0);
        grid.mouseUp(cx, 600, 0, 0);
        assertEquals(1, clicks.size());
        // b0's row runs 246..326, the grid stops at 300: the bit under the edge is not the tile's any more
        Tile half = b.get(0);
        assertTrue(half.contains(half.x() + 10, 290));
        assertFalse(half.contains(half.x() + 10, 310));
        grid.mouseMove(half.x() + 10, 310);
        assertFalse(half.hovered);
    }

    @Test
    public void enterOrSpaceOnAFocusedTileClicksIt() {
        Tile t = c.get(2);
        assertFalse(t.keyDown(Keys.ENTER, 0));
        t.setFocused(true);
        assertTrue(t.keyDown(Keys.ENTER, 0));
        assertTrue(t.keyDown(Keys.SPACE, 0));
        assertEquals(List.of("c2", "c2"), clicks);
    }

    @Test
    public void aFocusedTileScrollsIntoView() {
        Tile t = c.get(7);
        assertTrue(t.y() > 300);
        t.setFocused(true);
        draw();
        // its row 453..533, 8 under it: 541 - 300
        assertEquals(241f, grid.scroller().target(), 0.01f);
        drawAfter(400);
        assertTrue(t.y() >= 0 && t.y() + t.h() <= 300);
        // and back up: the first row brings its header along
        t.setFocused(false);
        a.get(0).setFocused(true);
        drawAfter(16);
        assertEquals(0f, grid.scroller().target(), 0.01f);
    }

    @Test
    public void tabWalksTheTilesNotTheGrid() {
        List<Widget> f = new ArrayList<>();
        grid.collectFocusable(f);
        assertEquals(14, f.size());
        assertSame(a.get(0), f.get(0));
        assertFalse(f.contains(grid));
    }

    @Test
    public void wheelAndResizeTellWhoeverHangsOffATile() {
        int[] moved = {0};
        grid.setOnViewMoved(() -> moved[0]++);
        grid.scroll(10, 10, -1);
        assertEquals(1, moved[0]);
        // already at that spot: no news
        draw();
        assertEquals(1, moved[0]);
        grid.setBounds(0, 0, 700, 280);
        draw();
        assertEquals(2, moved[0]);
    }

    @Test
    public void rebuildingWithTheSameTilesKeepsThem() {
        Tile t = c.get(3);
        t.setFocused(true);
        grid.setSections(List.of(new TileGrid.Section("Gamma", c)));
        draw();
        assertTrue(grid.children().contains(t));
        assertTrue(t.isFocused());
        assertEquals(3, grid.items().size());
        // dropped tiles are let go
        assertFalse(grid.children().contains(a.get(0)));
        assertTrue(a.get(0).owner == null);
    }

    @Test
    public void headersAndTileFacesAreDrawn() {
        // past the rows' fade in, so the colours are the real ones
        drawAfter(400);
        assertNotNull(canvas.textCall("Alpha"));
        assertNotNull(canvas.textCall("Beta"));
        // a0 is a lit switch, a1 a dim one
        StubCanvas.Call on = canvas.textCall("On");
        StubCanvas.Call off = canvas.textCall("Off");
        assertNotNull(on);
        assertNotNull(off);
        assertEquals(theme.color(Theme.ACCENT_TEXT), on.argb());
        assertEquals(theme.color(Theme.TEXT_3), off.argb());
    }

    @Test
    public void pickTileShowsItsValueAndAChevron() {
        Tile pick = new Tile(theme).setName("Fast Mode Min Health").setPick("12").setIcon(Icon.WARNING);
        pick.setBounds(0, 0, 200, 80);
        StubCanvas sc = new StubCanvas();
        pick.draw(sc, new Frame(clock * MS, 0.016f, -1, -1));
        assertNotNull(sc.textCall("12"));
        assertTrue(sc.calls.stream().anyMatch(k -> k.kind().equals("icon") && k.text().equals("CHEVRON_DOWN")));
        assertEquals(theme.color(Theme.TEXT), sc.textCall("12").argb());
    }

    @Test
    public void longNamesStopAtTwoLinesWithAnEllipsis() {
        Tile t = new Tile(theme).setName("Plan Ahead Primary Timeout Milliseconds Before Giving Up Entirely");
        // 13 px medium at 0.6 em is 7.8 a char, 100 px holds 12 of them
        List<String> lines = t.nameLines(100f);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).endsWith("…"));
        assertEquals(List.of("Allow Sprint"), new Tile(theme).setName("Allow Sprint").nameLines(200f));
    }

    @Test
    public void droppingTheBottomWidgetDropsItsFocus() {
        Button more = new Button(theme, "Show all settings", () -> clicks.add("more"));
        grid.setBottom(more, 46);
        draw();
        more.setFocused(true);
        // focused and off screen: the grid brings it up
        drawAfter(16);
        // the slot runs 533..579, the view is 300 tall
        assertEquals(279f, grid.scroller().target(), 0.01f);
        grid.setBottom(null, 0);
        assertFalse(more.isFocused());
    }
}
