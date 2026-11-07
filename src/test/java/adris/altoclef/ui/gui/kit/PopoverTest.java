package adris.altoclef.ui.gui.kit;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// popovers through a real Shell, because the contract is between the two: the shell hands every key and char to the
// overlay, closes it on an outside click and tells it so. the tab is one TileGrid of pick tiles
public class PopoverTest {

    private static final long MS = 1_000_000L;
    private static final int RSHIFT = 344;

    private final StubCanvas canvas = new StubCanvas();
    private final Theme theme = new Theme(1f, canvas);
    private final List<String> commits = new ArrayList<>();
    private Shell shell;
    private TileGrid grid;
    private long clock = 1000;

    private String num = "12";
    private String text = "#";
    private int colour = 0xD9182B;
    private int choice = 1;
    private int key = RSHIFT;
    private final List<String> options = List.of("Off", "Smart", "Always");

    private Tile numTile;
    private Tile textTile;
    private Tile colourTile;
    private Tile choiceTile;
    private Tile keyTile;
    private Tile below;
    private Popover pop;

    private final class GridTab implements Tab {
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
    }

    @Before
    public void setUp() {
        grid = new TileGrid(theme);
        numTile = new Tile(theme).setName("Fast Mode Min Health").setPick(num)
                .setOnClick(() -> open(Popover.number(shell, numTile, "Fast Mode Min Health", NumberField.NumberFormat.DOUBLE,
                        () -> num, v -> {
                            num = v;
                            commits.add("num " + v);
                        }, "12", null)));
        textTile = new Tile(theme).setName("Prefix").setPick(text)
                .setOnClick(() -> open(Popover.text(shell, textTile, "Prefix", () -> text, v -> {
                    text = v;
                    commits.add("text " + v);
                }, "#", v -> v.isEmpty() ? "Cannot be empty." : null)));
        colourTile = new Tile(theme).setName("Color Current Path").setPick("Red").setSwatch(colour)
                .setOnClick(() -> open(Popover.color(shell, colourTile, "Color Current Path", () -> colour, v -> {
                    colour = v;
                    commits.add("colour " + Integer.toHexString(v));
                }, 0x4DA3FF)));
        choiceTile = new Tile(theme).setName("Force Field Strategy").setPick("Smart")
                .setOnClick(() -> open(Popover.choice(shell, choiceTile, "Force Field Strategy", options, () -> choice, v -> {
                    choice = v;
                    commits.add("choice " + v);
                }, 1)));
        keyTile = new Tile(theme).setName("Open Key").setPick("Right Shift")
                .setOnClick(() -> open(Popover.key(shell, keyTile, "Open Key", k -> "Key " + k, () -> key, v -> {
                    key = v;
                    commits.add("key " + v);
                }, RSHIFT)));
        below = new Tile(theme).setName("Below").setSwitch(false).setOnClick(() -> commits.add("below clicked"));
        grid.setSections(List.of(new TileGrid.Section("Things", List.of(numTile, textTile, colourTile, choiceTile, keyTile, below))));
        shell = new Shell(theme, List.of(new GridTab()), new ShellTest.FakeStatus(), k -> "Key " + k, RSHIFT,
                k -> { }, Clipboard.inMemory(), () -> { });
        shell.setViewport(1920, 1080);
        draw();
    }

    private void open(Popover p) {
        pop = p;
        p.open();
    }

    private void draw() {
        clock += 16;
        canvas.calls.clear();
        shell.draw(canvas, new Frame(clock * MS, 0.016f, -1, -1));
    }

    private void drawAfter(long ms) {
        clock += ms;
        canvas.calls.clear();
        shell.draw(canvas, new Frame(clock * MS, 0.016f, -1, -1));
    }

    private void click(float x, float y) {
        shell.mouseMove(x, y);
        shell.mouseDown(x, y, 0, 0);
        shell.mouseUp(x, y, 0, 0);
        draw();
    }

    private void click(Widget w) {
        click(w.x() + w.w() / 2f, w.y() + w.h() / 2f);
    }

    private void press(int k) {
        shell.keyDown(k, 0);
        shell.keyUp(k);
        draw();
    }

    private void type(String s) {
        for (char ch : s.toCharArray()) {
            shell.charTyped(ch);
        }
        draw();
    }

    // somewhere in the panel that is not the popover and not a tile: the empty grid under the last row
    private void clickEmpty() {
        click(grid.x() + 30, grid.y() + grid.h() - 10);
    }

    @Test
    public void clickingAPickTileOpensItsPopoverUnderTheTile() {
        click(numTile);
        assertSame(pop, shell.overlay());
        assertTrue(pop.isOpen());
        assertTrue(numTile.open());
        assertEquals(numTile.x(), pop.x());
        assertEquals(numTile.y() + numTile.h() + 6, pop.y());
        assertNotNull(canvas.textCall("Fast Mode Min Health"));
        assertNotNull(canvas.textCall("Back to default"));
    }

    @Test
    public void enterCommitsTheTypedNumberAndCloses() {
        click(numTile);
        // the value comes up selected, typing replaces it
        type("30");
        press(Keys.ENTER);
        assertEquals(List.of("num 30"), commits);
        assertNull(shell.overlay());
        assertFalse(numTile.open());
        assertFalse(pop.isOpen());
    }

    @Test
    public void escapeDropsTheDraft() {
        click(numTile);
        type("5");
        press(Keys.ESCAPE);
        assertTrue(commits.isEmpty());
        assertNull(shell.overlay());
        assertEquals("12", num);
    }

    @Test
    public void anOutsideClickCommitsAValidDraftAndDoesNothingElse() {
        click(numTile);
        type("7");
        click(below);
        assertEquals(List.of("num 7"), commits);
        assertNull(shell.overlay());
    }

    @Test
    public void anOutsideClickWithABadDraftKeepsTheOldValue() {
        click(numTile);
        type("-");
        clickEmpty();
        assertTrue(commits.isEmpty());
        assertNull(shell.overlay());
        assertEquals("12", num);
    }

    @Test
    public void enterOnABadDraftStaysOpenAndSaysWhy() {
        click(textTile);
        // select all and wipe it: the prefix may not be empty
        shell.keyDown(Keys.A, Keys.MOD_CTRL);
        press(Keys.BACKSPACE);
        press(Keys.ENTER);
        assertTrue(commits.isEmpty());
        assertSame(pop, shell.overlay());
        assertNotNull(canvas.textCall("Cannot be empty."));
        type("!");
        press(Keys.ENTER);
        assertEquals(List.of("text !"), commits);
        assertNull(shell.overlay());
    }

    @Test
    public void minusAndPlusCommitRightAwayAndUpDownToo() {
        click(numTile);
        Popover.NumberBody body = (Popover.NumberBody) pop.children().get(0);
        Button plus = (Button) body.children().get(2);
        click(plus);
        assertEquals(List.of("num 12.1"), commits);
        assertSame(pop, shell.overlay());
        press(Keys.DOWN);
        assertEquals("12", num);
    }

    @Test
    public void tabCyclesTheCardsOwnControls() {
        click(colourTile);
        Popover.ColourBody body = (Popover.ColourBody) pop.children().get(0);
        List<Widget> fields = body.children();
        assertSame(fields.get(0), pop.focusChild());
        press(Keys.TAB);
        assertSame(fields.get(1), pop.focusChild());
        press(Keys.TAB);
        assertSame(fields.get(2), pop.focusChild());
        // red is not the default, so Back to default is live and a stop
        press(Keys.TAB);
        assertTrue(pop.focusChild() instanceof Button);
        press(Keys.TAB);
        assertSame(fields.get(0), pop.focusChild());
        shell.keyDown(Keys.TAB, Keys.MOD_SHIFT);
        assertTrue(pop.focusChild() instanceof Button);
        // shell focus never moved off the page
        assertNull(shell.focusedWidget());
    }

    @Test
    public void swatchesCommitOnClickAndTheFieldsOnAnOutsideClick() {
        click(colourTile);
        // the fourth dot of the first row is green
        float dx = pop.x() + 12 + 6 + (232 - 24 - 12 - 32) + 16;
        float dy = pop.y() + 12 + 16 + 10 + 2 + 16;
        click(dx, dy);
        assertEquals(List.of("colour 30d158"), commits);
        assertSame(pop, shell.overlay());
        Popover.ColourBody body = (Popover.ColourBody) pop.children().get(0);
        TextField r = (TextField) body.children().get(0);
        assertEquals("48", r.text());
        assertSame(r, pop.focusChild());
        press(Keys.BACKSPACE);
        press(Keys.BACKSPACE);
        type("255");
        clickEmpty();
        assertEquals(List.of("colour 30d158", "colour ffd158"), commits);
    }

    @Test
    public void backToDefaultCommitsTheDefault() {
        click(colourTile);
        Widget reset = pop.children().get(1);
        click(reset);
        assertEquals(List.of("colour 4da3ff"), commits);
        assertSame(pop, shell.overlay());
        // it is the default now, the button goes dim
        assertFalse(((Button) reset).enabled());
    }

    @Test
    public void choiceListTakesArrowsAndClicks() {
        click(choiceTile);
        press(Keys.DOWN);
        press(Keys.ENTER);
        assertEquals(List.of("choice 2"), commits);
        assertNull(shell.overlay());
        click(choiceTile);
        Widget list = pop.children().get(0);
        // first option row
        click(list.x() + 20, list.y() + 16);
        assertEquals(List.of("choice 2", "choice 0"), commits);
        assertSame(pop, shell.overlay());
        assertNotNull(canvas.textCall("Always"));
    }

    @Test
    public void keyPopoverCapturesAndEscapeOnlyStopsTheCapture() {
        click(keyTile);
        Popover.KeyBody body = (Popover.KeyBody) pop.children().get(0);
        click(body.cap);
        assertTrue(body.capturing());
        // esc stops waiting for a key, the card stays
        press(Keys.ESCAPE);
        assertFalse(body.capturing());
        assertSame(pop, shell.overlay());
        click(body.cap);
        shell.keyDown(Keys.A, 0);
        assertEquals(List.of("key " + Keys.A), commits);
        assertNull(shell.overlay());
        // the bound key is still down: its repeat must not reach the page under the closed card
        shell.focus(keyTile);
        shell.keyDown(Keys.A, 0);
        assertNull(shell.overlay());
        shell.keyUp(Keys.A);
    }

    @Test
    public void enterOnTheKeyCapStartsACaptureAndItsRepeatIsNotTheBind() {
        click(keyTile);
        Popover.KeyBody body = (Popover.KeyBody) pop.children().get(0);
        assertSame(body.cap, pop.focusChild());
        shell.keyDown(Keys.ENTER, 0);
        assertTrue(body.capturing());
        // key repeat of the same enter
        shell.keyDown(Keys.ENTER, 0);
        assertTrue(body.capturing());
        assertTrue(commits.isEmpty());
        shell.keyUp(Keys.ENTER);
        shell.keyDown(Keys.ENTER, 0);
        assertEquals(List.of("key " + Keys.ENTER), commits);
    }

    @Test
    public void theEnterThatOpenedItDoesNotAlsoCloseIt() {
        shell.focus(numTile);
        shell.keyDown(Keys.ENTER, 0);
        assertSame(pop, shell.overlay());
        // the repeat
        shell.keyDown(Keys.ENTER, 0);
        assertSame(pop, shell.overlay());
        shell.keyUp(Keys.ENTER);
        shell.keyDown(Keys.ENTER, 0);
        shell.keyUp(Keys.ENTER);
        assertNull(shell.overlay());
        // focus goes back where it was
        assertSame(numTile, shell.focusedWidget());
    }

    @Test
    public void theSpaceThatOpenedItDoesNotTypeIntoIt() {
        shell.focus(textTile);
        shell.keyDown(Keys.SPACE, 0);
        shell.charTyped(' ');
        shell.keyUp(Keys.SPACE);
        Popover.TextBody body = (Popover.TextBody) pop.children().get(0);
        assertEquals("#", ((TextField) body.children().get(0)).text());
    }

    @Test
    public void theWheelOutsideClosesItKeepingTheDraft() {
        click(numTile);
        type("9");
        shell.scroll(grid.x() + 30, grid.y() + grid.h() - 10, -1);
        assertEquals(List.of("num 9"), commits);
        assertNull(shell.overlay());
    }

    @Test
    public void theTileMovingClosesIt() {
        click(numTile);
        type("4");
        shell.setViewport(1600, 900);
        draw();
        assertNull(shell.overlay());
        assertEquals(List.of("num 4"), commits);
    }

    @Test
    public void anotherOverlayClosesItThroughTheCallbackOnce() {
        click(numTile);
        type("3");
        int[] closed = {0};
        pop.onClosed(() -> closed[0]++);
        Widget other = new Label(theme, "x", theme.body, Theme.TEXT);
        shell.openOverlay(other);
        assertEquals(1, closed[0]);
        assertEquals(List.of("num 3"), commits);
        assertSame(other, shell.overlay());
        // a stale popover asked to close must not take the new overlay down with it
        pop.dismiss();
        assertSame(other, shell.overlay());
        assertEquals(1, closed[0]);
    }

    @Test
    public void closeOverlayRunsTheCallbackOnceAndClearsIt() {
        int[] n = {0};
        Widget w = new Label(theme, "x", theme.body, Theme.TEXT);
        shell.openOverlay(w, () -> n[0]++);
        shell.closeOverlay();
        shell.closeOverlay();
        assertEquals(1, n[0]);
        assertFalse(w.visible());
    }

    @Test
    public void flipsAboveWhenTheBottomIsTooClose() {
        // a short panel: the second row has no room under it
        shell.setViewport(1000, 560);
        draw();
        grid.scroller().snapTo(grid.scroller().maxOffset());
        draw();
        click(below);
        assertTrue(commits.contains("below clicked"));
        click(keyTile);
        assertTrue(pop.y() + pop.h() <= keyTile.y());
    }

    // the game's order for a typed digit: key down, char, key up
    private void realType(String s) {
        for (char ch : s.toCharArray()) {
            shell.keyDown(ch, 0);
            shell.charTyped(ch);
            shell.keyUp(ch);
        }
        draw();
    }

    @Test
    public void theFirstRealKeystrokeReplacesTheValue() {
        click(numTile);
        realType("3");
        press(Keys.ENTER);
        assertEquals(List.of("num 3"), commits);
    }

    @Test
    public void backspaceOnAFreshValueWipesIt() {
        click(numTile);
        press(Keys.BACKSPACE);
        realType("8");
        press(Keys.ENTER);
        assertEquals(List.of("num 8"), commits);
    }

    @Test
    public void aHeldEscapeThatStoppedACaptureDoesNotAlsoClose() {
        click(keyTile);
        Popover.KeyBody body = (Popover.KeyBody) pop.children().get(0);
        click(body.cap);
        shell.keyDown(Keys.ESCAPE, 0);
        // the repeat
        shell.keyDown(Keys.ESCAPE, 0);
        assertSame(pop, shell.overlay());
        shell.keyUp(Keys.ESCAPE);
        shell.keyDown(Keys.ESCAPE, 0);
        assertNull(shell.overlay());
        assertTrue(commits.isEmpty());
    }

    @Test
    public void upAndDownNudgeTheFocusedChannel() {
        click(colourTile);
        // red is 217 24 43, R has focus
        press(Keys.UP);
        shell.keyDown(Keys.UP, Keys.MOD_SHIFT);
        shell.keyUp(Keys.UP);
        Popover.ColourBody body = (Popover.ColourBody) pop.children().get(0);
        TextField r = (TextField) body.children().get(0);
        assertEquals("228", r.text());
        for (int i = 0; i < 4; i++) {
            shell.keyDown(Keys.UP, Keys.MOD_SHIFT);
            shell.keyUp(Keys.UP);
        }
        assertEquals("255", r.text());
        assertTrue(commits.isEmpty());
        press(Keys.ENTER);
        assertEquals(List.of("colour ff182b"), commits);
    }

    @Test
    public void aGridResizeClosesItEvenWhenTheTileStaysPut() {
        click(numTile);
        type("6");
        int y0 = numTile.y();
        grid.setBounds(grid.x(), grid.y(), grid.w(), grid.h() - 40);
        draw();
        assertEquals(y0, numTile.y());
        assertNull(shell.overlay());
        assertEquals(List.of("num 6"), commits);
    }

    @Test
    public void aTileDroppedFromTheGridLosesFocusAndItsPopover() {
        shell.focus(textTile);
        assertTrue(textTile.isFocused());
        grid.setSections(List.of(new TileGrid.Section("Things", List.of(numTile))));
        assertFalse(textTile.isFocused());
        // enter now goes nowhere instead of to the ghost
        shell.keyDown(Keys.ENTER, 0);
        shell.keyUp(Keys.ENTER);
        assertNull(shell.overlay());
        // a popover whose tile leaves goes with it
        grid.setSections(List.of(new TileGrid.Section("Things", List.of(numTile, colourTile))));
        draw();
        click(colourTile);
        grid.setSections(List.of(new TileGrid.Section("Things", List.of(numTile))));
        draw();
        assertNull(shell.overlay());
    }

    @Test
    public void aHeldKeyStaysSwallowedThroughAnotherKey() {
        click(keyTile);
        Popover.KeyBody body = (Popover.KeyBody) pop.children().get(0);
        click(body.cap);
        shell.keyDown(Keys.ESCAPE, 0);
        // shift goes down and up while escape is still held
        shell.keyDown(Keys.LEFT_SHIFT, Keys.MOD_SHIFT);
        shell.keyUp(Keys.LEFT_SHIFT);
        shell.keyDown(Keys.ESCAPE, 0);
        assertSame(pop, shell.overlay());
        shell.keyUp(Keys.ESCAPE);
    }

    @Test
    public void aLongListInAShortPanelStaysInsideIt() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add("Option " + i);
        }
        Tile t = new Tile(theme).setName("Many").setPick("Option 0");
        t.setOnClick(() -> open(Popover.choice(shell, t, "Many", many, () -> 0, v -> { }, 0)));
        grid.setSections(List.of(new TileGrid.Section("Things", List.of(numTile, textTile, colourTile, t))));
        shell.setViewport(1000, 560);
        draw();
        click(t);
        assertSame(pop, shell.overlay());
        assertTrue(pop.y() >= grid.y() + 8);
        assertTrue(pop.y() + pop.h() <= grid.y() + grid.h() - 8);
        // fewer rows than options: the list scrolls instead
        assertTrue(pop.children().get(0).h() < 10 * 32);
    }

    @Test
    public void aResetThatGoesDimHandsFocusBack() {
        click(colourTile);
        // R, G, B, then Back to default
        press(Keys.TAB);
        press(Keys.TAB);
        press(Keys.TAB);
        assertTrue(pop.focusChild() instanceof Button);
        press(Keys.ENTER);
        assertEquals(List.of("colour 4da3ff"), commits);
        assertFalse(pop.focusChild() instanceof Button);
        press(Keys.ENTER);
        assertNull(shell.overlay());
    }
}
