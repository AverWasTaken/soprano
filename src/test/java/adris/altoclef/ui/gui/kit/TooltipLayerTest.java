package adris.altoclef.ui.gui.kit;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// the hover card layer: waits for the pointer to rest, draws last, takes no input, never closes a popover
public class TooltipLayerTest {

    private static final long MS = 1_000_000L;

    private final StubCanvas canvas = new StubCanvas();
    private final Theme theme = new Theme(1f, canvas);
    private final List<String> clicks = new ArrayList<>();
    private Shell shell;
    private TileGrid grid;
    private Tile fast;
    private Tile next;
    private Tile under;
    private Tile pick;
    private Popover pop;
    private long clock = 1000;

    @Before
    public void setUp() {
        grid = new TileGrid(theme);
        fast = new Tile(theme).setName("Fast Mode").setSwitch(true).setOnClick(() -> clicks.add("fast"))
                .setTooltip(new Tooltip("Fast Mode", "Moves like a fast player.", List.of("#set fastMode", "Default: Off")));
        next = new Tile(theme).setName("Allow Boats").setSwitch(true).setTooltip(Tooltip.of("Rows across water."));
        pick = new Tile(theme).setName("Min Health").setPick("12");
        pick.setOnClick(() -> {
            pop = Popover.number(shell, pick, "Min Health", NumberField.NumberFormat.INT, () -> "12", v -> { }, "12", null);
            pop.open();
        });
        Tile d = new Tile(theme).setName("Boat Pickup").setSwitch(false);
        // the row under, so the card hangs over something clickable
        under = new Tile(theme).setName("Under").setSwitch(false).setOnClick(() -> clicks.add("under"));
        grid.setSections(List.of(new TileGrid.Section("Travel", List.of(fast, next, pick, d, under))));
        shell = new Shell(theme, List.of(new Tab() {
            @Override
            public String id() {
                return "settings";
            }

            @Override
            public String title() {
                return "Settings";
            }

            @Override
            public Icon icon() {
                return Icon.SETTINGS;
            }

            @Override
            public Widget content() {
                return grid;
            }
        }), new ShellTest.FakeStatus(), k -> "Key " + k, 344, k -> { }, Clipboard.inMemory(), () -> { });
        shell.setViewport(1920, 1080);
        draw(16);
    }

    private void draw(long ms) {
        clock += ms;
        canvas.calls.clear();
        shell.draw(canvas, new Frame(clock * MS, 0.016f, -1, -1));
    }

    private void hover(Widget w) {
        shell.mouseMove(w.x() + w.w() / 2f, w.y() + w.h() / 2f);
    }

    @Test
    public void showsAfterTheRestDelayUnderTheTile() {
        hover(fast);
        draw(300);
        assertNull(shell.tooltips().showing(clock * MS));
        assertNull(canvas.textCall("#set fastMode"));
        draw(250);
        assertSame(fast, shell.tooltips().showing(clock * MS));
        StubCanvas.Call cmd = canvas.textCall("#set fastMode");
        assertNotNull(cmd);
        assertNotNull(canvas.textCall("Default: Off"));
        assertNotNull(canvas.textCall("Moves like a fast player."));
        assertTrue(cmd.y() > fast.y() + fast.h());
    }

    @Test
    public void movingToTheNextTileSwitchesStraightAway() {
        hover(fast);
        draw(600);
        hover(next);
        draw(16);
        assertSame(next, shell.tooltips().showing(clock * MS));
        assertNotNull(canvas.textCall("Rows across water."));
    }

    @Test
    public void itTakesNoInputAClickGoesToWhatIsUnderIt() {
        hover(fast);
        draw(600);
        // the card hangs over the row below; click through it onto that tile
        float x = under.x() + 20;
        float y = under.y() + 20;
        shell.mouseDown(x, y, 0, 0);
        shell.mouseUp(x, y, 0, 0);
        assertEquals(List.of("under"), clicks);
        // and the click put the card away
        draw(16);
        assertNull(canvas.textCall("#set fastMode"));
    }

    @Test
    public void itNeverClosesAPopoverAndDrawsOverIt() {
        shell.mouseMove(pick.x() + 10, pick.y() + 10);
        shell.mouseDown(pick.x() + 10, pick.y() + 10, 0, 0);
        shell.mouseUp(pick.x() + 10, pick.y() + 10, 0, 0);
        draw(16);
        assertTrue(pop.isOpen());
        // with a card up, the page's tiles have no tips: you are not pointing at them
        hover(fast);
        draw(800);
        assertNull(shell.tooltips().showing(clock * MS));
        assertTrue(pop.isOpen());
        // forced onto a tile anyway, it is drawn after the popover and the popover stays
        shell.tooltips().track(fast, clock * MS - 600 * MS);
        draw(16);
        assertTrue(pop.isOpen());
        assertSame(pop, shell.overlay());
        int popTitle = -1;
        int tip = -1;
        for (int i = 0; i < canvas.calls.size(); i++) {
            StubCanvas.Call k = canvas.calls.get(i);
            if ("Min Health".equals(k.text()) && popTitle < 0 && k.y() > pick.y() + pick.h()) {
                popTitle = i;
            }
            if ("#set fastMode".equals(k.text())) {
                tip = i;
            }
        }
        assertTrue(popTitle >= 0 && tip > popTitle);
    }

    @Test
    public void keptInsideThePanel() {
        // the right column's card would run off the panel edge if it lined up with the tile
        Tile right = grid.tiles().get(3);
        right.setTooltip(new Tooltip("Boat Pickup", "Breaks the boat and picks it back up on the far side, so you keep it.",
                List.of("#set boatPickup")));
        hover(right);
        draw(600);
        StubCanvas.Call shadow = null;
        for (StubCanvas.Call k : canvas.calls) {
            if (k.kind().equals("shadow") && k.y() > right.y()) {
                shadow = k;
            }
        }
        assertNotNull(shadow);
        assertTrue(shadow.x() + shadow.w() <= shell.x() + shell.w() - 8);
    }

    @Test
    public void aTallCardInAShortRegionIsPinnedInside() {
        TooltipLayer layer = new TooltipLayer(theme);
        Tile t = new Tile(theme).setName("x").setTooltip(new Tooltip("Title",
                "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen "
                        + "seventeen eighteen nineteen twenty twenty one twenty two twenty three twenty four",
                List.of("#set x", "Default: Off")));
        // the region is 200 tall and the tile sits in its middle: neither side has room for the card
        t.setBounds(100, 160, 160, 80);
        layer.track(t, 0);
        StubCanvas sc = new StubCanvas();
        layer.draw(sc, 600 * MS, 0, 100, 800, 200);
        StubCanvas.Call card = null;
        for (StubCanvas.Call k : sc.calls) {
            if (k.kind().equals("shadow")) {
                card = k;
            }
        }
        assertNotNull(card);
        assertTrue(card.y() >= 108);
        assertTrue(card.y() + card.h() <= 292);
    }
}
