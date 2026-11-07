package adris.altoclef.ui;

import adris.altoclef.ui.gui.hud.StatusPanel;
import adris.altoclef.ui.gui.hud.StatusRows;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.Baritone;
import baritone.api.Settings;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

// the panel's settings, the ones the old overlay had: altoShowTaskChains, altoShowTimer and altoHudDetailed here,
// altoHudScale in StatusPanelHudTest
public class CommandStatusOverlayTest {

    private static final List<StatusRows.TaskLine> LINES = List.of(
            new StatusRows.TaskLine("Mine and collect", "", "MineAndCollectTask{dirt}", "", false),
            new StatusRows.TaskLine("Clicking a slot", "", "ClickSlotTask{3}", "slot 3", true),
            new StatusRows.TaskLine("Mining dirt", "12/24", "MineAndCollectTask{dirt}", "Block in range, mining...", false));

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @After
    public void restore() {
        Settings s = Baritone.settings();
        s.altoShowTaskChains.reset();
        s.altoShowTimer.reset();
        s.altoHudDetailed.reset();
    }

    @Test
    public void showTaskChainsOffMeansNoPanelAtAll() {
        CommandStatusOverlay o = new CommandStatusOverlay();
        o.layoutLines("GetChain", "Getting blocks for the path", LINES, 1000);
        assertEquals(3, o.shownRows().size());
        Baritone.settings().altoShowTaskChains.value = false;
        // null, not empty: empty is "the task ended" and would put the idle pill up
        assertNull(o.shownRows());
    }

    @Test
    public void timerOnlyWhenAskedFor() {
        CommandStatusOverlay o = new CommandStatusOverlay();
        o.layoutLines("GetChain", "Getting blocks for the path", LINES, 1000);
        assertNull(o.timer());
        Baritone.settings().altoShowTimer.value = true;
        o.layoutLines("GetChain", "Getting blocks for the path", LINES, 66_000);
        assertNotNull(o.timer());
        assertTrue(o.timer(), o.timer().matches("\\d\\d:\\d\\d"));
        // nothing running clears it, and a 10 s gap starts it over
        o.layoutLines(null, null, List.of(), 67_000);
        assertNull(o.timer());
        o.layoutLines(null, null, List.of(), 90_000);
        o.layoutLines("GetChain", "Getting blocks for the path", LINES, 95_000);
        assertEquals("00:05", o.timer());
    }

    @Test
    public void detailedIsTheDeveloperView() {
        CommandStatusOverlay o = new CommandStatusOverlay();
        o.layoutLines("GetChain", "Getting blocks for the path", LINES, 1000);
        List<StatusPanel.Row> plain = o.shownRows();
        assertEquals("Getting blocks for the path", plain.get(0).name());
        assertEquals("plumbing hidden", 3, plain.size());
        assertEquals("12/24", plain.get(2).state());

        Baritone.settings().altoHudDetailed.value = true;
        o.layoutLines("GetChain", "Getting blocks for the path", LINES, 2000);
        List<StatusPanel.Row> raw = o.shownRows();
        assertEquals("GetChain", raw.get(0).name());
        assertEquals("plumbing shown", 4, raw.size());
        assertEquals("ClickSlotTask{3}", raw.get(2).name());
        assertEquals("Block in range, mining...", raw.get(3).state());
    }

    @Test
    public void elapsedFormat() {
        assertEquals("00:00", CommandStatusOverlay.formatElapsed(0));
        assertEquals("01:05", CommandStatusOverlay.formatElapsed(65_000));
        assertEquals("1:00:01", CommandStatusOverlay.formatElapsed(3_601_000));
    }
}
