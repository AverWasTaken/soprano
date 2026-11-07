package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.ui.gui.facade.Result;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// the help tab's command line cases, moved with it into the shell's command bar, plus the shell's half: the bar only
// takes keys while it has focus and never takes them from an open popover
public class CommandBarTest {

    private static final long MS = 1_000_000L;

    private final StubCanvas canvas = new StubCanvas(1600, 900);
    private final Theme theme = new Theme(1f, canvas);
    private final List<Result> toasts = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();
    private boolean failNext;
    private long ms = 1000;

    private Result fakeRun(String line) {
        ran.add(line);
        if (failNext) {
            failNext = false;
            return Result.fail("Unknown command \"" + line.split(" ")[0] + "\"", line);
        }
        return Result.ok("Ran " + line.split(" ")[0], line);
    }

    private CommandBar bar() {
        CommandBar b = new CommandBar(theme, toasts::add);
        b.setRunner(this::fakeRun);
        b.setBounds(0, 600, 1040, 40);
        // the shell focuses whatever was clicked; here it is just the flag
        b.field().setFocused(true);
        frame(b);
        return b;
    }

    private void frame(Widget w) {
        canvas.calls.clear();
        w.draw(canvas, new Frame(ms * MS, 0.016f, -1, -1));
    }

    private static void enter(CommandBar b, String line) {
        b.field().setText(line);
        b.field().keyDown(Keys.ENTER, 0);
    }

    @Test
    public void cleanLineDropsThePrefixAndTheSpaces() {
        assertEquals("goto 1 2 3", CommandBar.cleanLine("  #goto 1 2 3 "));
        assertEquals("goto 1 2 3", CommandBar.cleanLine("# goto 1 2 3"));
        assertEquals("goto", CommandBar.cleanLine("##goto"));
        assertEquals("", CommandBar.cleanLine("#"));
        assertEquals("", CommandBar.cleanLine(null));
    }

    @Test
    public void idleShowsTheHintAndThePlaceholder() {
        CommandBar b = bar();
        assertTrue(canvas.texts().contains(CommandBar.IDLE_HINT));
        assertTrue(canvas.texts().contains("Run a command, like goto 100 64 -30"));
        assertEquals(Theme.TEXT_3, canvas.textCall(CommandBar.IDLE_HINT).argb());
        assertEquals(CommandBar.IDLE_HINT, b.inlineText());
    }

    @Test
    public void aNarrowBarDropsTheHintButKeepsAResult() {
        CommandBar b = bar();
        b.setBounds(0, 600, 300, 40);
        frame(b);
        assertFalse(canvas.texts().contains(CommandBar.IDLE_HINT));
        // the field gets the whole row back, up to the right inset
        assertEquals(300 - theme.px(Theme.PAD) - theme.px(Theme.PAD + Theme.SPACE_3), b.field().w());
        failNext = true;
        enter(b, "gett");
        frame(b);
        assertTrue(canvas.calls.stream().anyMatch(k -> k.kind().equals("text") && k.argb() == Theme.DANGER));
    }

    @Test
    public void enterRunsTheLineWithoutThePrefix() {
        CommandBar b = bar();
        enter(b, "#goto 100 64 -30");
        assertEquals(List.of("goto 100 64 -30"), ran);
        assertEquals(1, toasts.size());
        assertTrue(toasts.get(0).ok());
        assertEquals("", b.field().text());
        assertTrue(b.inlineText().startsWith("Ran goto 100 64 -30"));
        frame(b);
        assertEquals(Theme.ACCENT_TEXT, canvas.textCall(b.inlineText()).argb());
    }

    // there is no Run button in the bar (the mock has none), so the old dim-while-blank case is this one: a blank
    // line, or one that is only the prefix, runs nothing, toasts nothing and is not history
    @Test
    public void aBlankLineRunsNothing() {
        CommandBar b = bar();
        enter(b, "");
        enter(b, "  # ");
        assertTrue(ran.isEmpty());
        assertTrue(toasts.isEmpty());
        assertEquals(0, b.history().size());
        assertNull(b.lastResult());
    }

    @Test
    public void aFailureKeepsTheTextAndToastsRed() {
        CommandBar b = bar();
        failNext = true;
        enter(b, "gett oak_log");
        assertEquals("gett oak_log", b.field().text());
        assertTrue(b.field().invalid());
        assertFalse(b.lastResult().ok());
        assertEquals("Unknown command \"gett\"", b.inlineText());
        assertEquals(1, toasts.size());
        assertFalse(toasts.get(0).ok());
        frame(b);
        assertEquals(Theme.DANGER, canvas.textCall("Unknown command \"gett\"").argb());
    }

    @Test
    public void typingAgainClearsTheRedBorder() {
        CommandBar b = bar();
        failNext = true;
        enter(b, "gett");
        assertTrue(b.field().invalid());
        b.field().charTyped('x');
        assertFalse(b.field().invalid());
    }

    @Test
    public void upArrowWalksTheSessionHistory() {
        CommandBar b = bar();
        for (String line : new String[] {"stop", "follow players", "get stick 4"}) {
            enter(b, line);
        }
        assertEquals("", b.field().text());
        b.field().setText("half");
        b.field().keyDown(Keys.UP, 0);
        assertEquals("get stick 4", b.field().text());
        b.field().keyDown(Keys.UP, 0);
        assertEquals("follow players", b.field().text());
        b.field().keyDown(Keys.DOWN, 0);
        b.field().keyDown(Keys.DOWN, 0);
        assertEquals("half", b.field().text());
    }

    @Test
    public void historyStopsAtTwenty() {
        CommandBar b = bar();
        for (int i = 0; i < 30; i++) {
            enter(b, "get stick " + i);
        }
        assertEquals(20, b.history().size());
    }

    @Test
    public void recallingAGoodLineClearsTheRedBorder() {
        CommandBar b = bar();
        enter(b, "stop");
        failNext = true;
        enter(b, "gett");
        assertTrue(b.field().invalid());
        b.field().keyDown(Keys.UP, 0);
        b.field().keyDown(Keys.UP, 0);
        assertEquals("stop", b.field().text());
        assertFalse(b.field().invalid());
    }

    @Test
    public void numLockDoesNotBreakTheArrowKeys() {
        CommandBar b = bar();
        enter(b, "stop");
        assertTrue(b.field().keyDown(Keys.UP, 32));
        assertEquals("stop", b.field().text());
    }

    @Test
    public void aHashTypedFirstIsSwallowedSinceOneIsAlreadyDrawn() {
        CommandBar b = bar();
        b.field().charTyped('#');
        assertEquals("", b.field().text());
        b.field().charTyped('g');
        b.field().charTyped('#');
        assertEquals("g#", b.field().text());
    }

    @Test
    public void aThrowingRunnerBecomesAFailureNotACrash() {
        CommandBar b = bar();
        b.setRunner(line -> {
            throw new IllegalStateException("boom");
        });
        enter(b, "stop");
        assertFalse(b.lastResult().ok());
        assertEquals(1, toasts.size());
        assertFalse(toasts.get(0).ok());
    }

    @Test
    public void noRunnerIsAFailureToo() {
        CommandBar b = new CommandBar(theme, toasts::add);
        b.setBounds(0, 600, 1040, 40);
        b.field().setFocused(true);
        enter(b, "stop");
        assertFalse(b.lastResult().ok());
        assertEquals("stop", b.field().text());
    }

    @Test
    public void escLeavesTheFieldToTheShellAndKeepsTheText() {
        CommandBar b = bar();
        b.field().setText("goto 1 2");
        b.field().charTyped('3');
        assertFalse(b.field().keyDown(Keys.ESCAPE, 0));
        assertEquals("goto 1 23", b.field().text());
    }

    // ----- in the shell

    private Shell shell;
    private ShellTest.FakeTab tab;

    private Shell shell() {
        tab = new ShellTest.FakeTab(theme, "get", true);
        shell = new Shell(theme, List.of(tab), new ShellTest.FakeStatus(), k -> "Key " + k, 344, k -> { },
                Clipboard.inMemory(), () -> { });
        shell.setCommandRunner(this::fakeRun);
        shell.setViewport(1920, 1080);
        shell.open(ms * MS);
        shell.keyUp(344);
        ms += 400;
        frame(shell);
        return shell;
    }

    private void click(float x, float y) {
        shell.mouseMove(x, y);
        shell.mouseDown(x, y, 0, 0);
        shell.mouseUp(x, y, 0, 0);
    }

    private void clickBar() {
        TextField f = shell.commandBar().field();
        click(f.x() + 40, f.y() + f.h() / 2f);
    }

    private void type(String s) {
        for (char c : s.toCharArray()) {
            shell.charTyped(c);
        }
    }

    @Test
    public void theBarSitsOverTheStatusStripAndEverythingElseStopsAboveIt() {
        shell();
        CommandBar b = shell.commandBar();
        assertEquals(shell.x(), b.x());
        assertEquals(shell.w(), b.w());
        assertEquals(theme.px(Theme.CMD_H), b.h());
        assertEquals(shell.y() + shell.h() - theme.px(Theme.STATUS_H), b.y() + b.h());
        assertEquals(b.y(), shell.contentY() + shell.contentH());
        assertTrue(shell.keyCapture().y() + shell.keyCapture().h() < b.y());
        // the field ends before the hint and the hint ends over the stop button's right edge
        assertTrue(b.field().x() + b.field().w() < shell.stopButton().x() + shell.stopButton().w());
        assertEquals(theme.px(28), b.field().h());
    }

    @Test
    public void clickingTheBarFocusesItAndTypingAndEnterRunTheLine() {
        shell();
        clickBar();
        assertSame(shell.commandBar().field(), shell.focusedWidget());
        type("goto 100 64 -30");
        assertEquals("goto 100 64 -30", shell.commandBar().field().text());
        shell.keyDown(Keys.ENTER, 0);
        assertEquals(List.of("goto 100 64 -30"), ran);
        // the shell's own toast, same as every other button
        assertEquals(1, shell.toast().pending());
        assertTrue(shell.toast().currentOk());
    }

    @Test
    public void escLeavesTheBarWithoutClosingThePanel() {
        shell();
        clickBar();
        type("mine");
        shell.keyDown(Keys.ESCAPE, 0);
        assertNull(shell.focusedWidget());
        assertFalse(shell.closing());
        assertEquals("mine", shell.commandBar().field().text());
    }

    @Test
    public void theBarOnlyTakesKeysWhileFocused() {
        shell();
        // nobody focused: typing goes where it always went, the tab's search
        type("iron");
        assertSame(shell.search(), shell.focusedWidget());
        assertEquals("", shell.commandBar().field().text());
        assertEquals("iron", shell.search().text());
        // and up does not walk the bar's history from out here
        shell.keyDown(Keys.UP, 0);
        assertEquals("", shell.commandBar().field().text());
    }

    @Test
    public void theOpenKeyDoesNotCloseThePanelWhileTypingInTheBar() {
        shell();
        clickBar();
        shell.keyDown(344, 0);
        assertFalse(shell.closing());
    }

    // an overlay that writes down what reached it
    private static final class Recorder extends Widget {
        final StringBuilder chars = new StringBuilder();

        Recorder(Theme theme) {
            super(theme);
        }

        @Override
        public boolean charTyped(char c) {
            chars.append(c);
            return true;
        }

        @Override
        public boolean keyDown(int key, int mods) {
            return key != Keys.ESCAPE;
        }

        @Override
        public void draw(Canvas c, Frame f) {
        }
    }

    @Test
    public void anOpenOverlayKeepsItsKeysAndAClickOnTheBarOnlyClosesIt() {
        shell();
        clickBar();
        type("st");
        Recorder pop = new Recorder(theme);
        pop.setBounds(shell.contentX() + 20, shell.contentY() + 20, 200, 100);
        shell.openOverlay(pop);
        type("op");
        shell.keyDown(Keys.ENTER, 0);
        shell.keyDown(Keys.UP, 0);
        assertEquals("op", pop.chars.toString());
        assertEquals("st", shell.commandBar().field().text());
        assertTrue(ran.isEmpty());
        // one overlay rule: the click that dismisses it does nothing else
        shell.blur();
        clickBar();
        assertNull(shell.overlay());
        assertNotSame(shell.commandBar().field(), shell.focusedWidget());
    }

    @Test
    public void aTabSwitchOrTheTabKeyMovesFocusOffTheBar() {
        shell();
        clickBar();
        shell.keyDown(Keys.TAB, 0);
        assertSame(shell.appearance(), shell.focusedWidget());
    }
}
