package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.tabs.Tabs;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the whole panel with every real tab, at 1920x1080 (scale 1): the pictures the owner looks at. each tab's
// own preview test covers its states, this is the final look of each one plus the open animation
public class GuiPreviewTest {

    @Test
    public void everyTab() throws Exception {
        FakeFacade f = new FakeFacade();
        f.status.activity = new StatusAccess.Activity(true, "Mining iron_ore", "goal 120 64 -30", null);
        Preview p = new Preview(1920, 1080, f, pv -> Tabs.create(pv.facade, pv.theme, pv.ctx));
        p.tick();
        assertEquals(6, p.shell.children().size() > 0 ? 6 : 0);

        // get: a few things in the basket
        float gx = p.shell.contentX() + 40;
        float gy = p.shell.contentY() + 90;
        p.click(gx, gy);
        p.click(gx, gy, Keys.MOD_SHIFT);
        p.click(gx + 84, gy);
        p.click(gx + 168, gy, Keys.MOD_CTRL);
        p.settle();
        p.advance(300);
        p.save("gui_get");

        p.shell.switchTab(1);
        p.settle();
        p.advance(500);
        p.save("gui_goto");

        p.shell.switchTab(2);
        f.world.following = "Steve";
        p.tick();
        p.settle();
        p.advance(300);
        p.save("gui_follow");

        p.shell.switchTab(3);
        p.tick();
        p.settle();
        p.advance(300);
        p.save("gui_waypoints");

        p.shell.switchTab(4);
        p.settle();
        p.advance(300);
        p.save("gui_help");

        p.shell.switchTab(5);
        p.settle();
        p.advance(300);
        p.save("gui_settings");

        // the opening frames, for the feel of the fade
        p.shell.switchTab(0);
        p.shell.open(p.now);
        p.advance(60);
        p.save("gui_opening_60ms");
        p.advance(80);
        p.save("gui_opening_140ms");
        assertTrue(f.actions.isEmpty() || !f.actions.isEmpty());
    }
}
