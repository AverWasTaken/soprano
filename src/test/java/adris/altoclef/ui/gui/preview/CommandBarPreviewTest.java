package adris.altoclef.ui.gui.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.kit.CommandBar;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.TextField;

import org.junit.Test;

// the command bar under every tab, in the states the help tab's command line used to show: idle, typing, a line
// that ran, a line that failed, and the arrow keys bringing old lines back
public class CommandBarPreviewTest {

    private static void clickBar(Preview p) {
        TextField f = p.shell.commandBar().field();
        p.click(f.x() + 40, f.y() + f.h() / 2f);
    }

    @Test
    public void states() throws Exception {
        FakeFacade f = new FakeFacade();
        f.status.activity = new StatusAccess.Activity(true, "Mining iron_ore", "goal 120 64 -30", null);
        Preview p = new Preview(1600, 900, f, ShellPreviewTest::tabs);
        p.tick();
        p.settle();
        CommandBar bar = p.shell.commandBar();
        assertEquals(CommandBar.IDLE_HINT, bar.inlineText());
        p.savePanel("cmdbar_idle");

        clickBar(p);
        assertSame(bar.field(), p.shell.focusedWidget());
        p.type("goto 100 64 -30");
        p.advance(100);
        p.savePanel("cmdbar_typing");

        p.key(Keys.ENTER);
        p.advance(300);
        p.settle();
        assertEquals("goto 100 64 -30", f.commands.lastRun);
        assertEquals("", bar.field().text());
        assertTrue(bar.inlineText().startsWith("Ran goto 100 64 -30"));
        p.savePanel("cmdbar_run_ok");

        // a failure keeps the text and shows red, once the first toast has gone
        p.advance(3500);
        p.settle();
        p.advance(300);
        f.commands.failNext = true;
        p.type("#gett oak_log 4");
        p.key(Keys.ENTER);
        p.advance(300);
        p.settle();
        assertEquals("gett oak_log 4", f.commands.lastRun);
        assertEquals("gett oak_log 4", bar.field().text());
        assertTrue(bar.inlineText().contains("Unknown command"));
        p.savePanel("cmdbar_run_fail");

        // up brings the last lines back, esc leaves the bar with the line still in it
        bar.field().setText("");
        p.key(Keys.UP);
        assertEquals("gett oak_log 4", bar.field().text());
        p.key(Keys.UP);
        assertEquals("goto 100 64 -30", bar.field().text());
        p.key(Keys.ESCAPE);
        assertNull(p.shell.focusedWidget());
        assertEquals("goto 100 64 -30", bar.field().text());
        p.advance(300);
        p.savePanel("cmdbar_left_with_text");
    }

    @Test
    public void smallWindow() throws Exception {
        FakeFacade f = new FakeFacade();
        Preview p = new Preview(960, 540, f, ShellPreviewTest::tabs);
        p.settle();
        p.save("cmdbar_small_idle");
        clickBar(p);
        p.type("follow player Steve");
        p.advance(100);
        p.save("cmdbar_small_typing");
    }
}
