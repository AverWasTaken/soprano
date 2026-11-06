package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Tile;
import adris.altoclef.ui.gui.kit.TileGrid;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// the first page as a grid of common settings: it and Show all between them cover every setting once, a tile and the
// same setting in a search are one tile, keyboard and scroll reach everything, and it holds up small and in both looks
public class CommonSettingsTest {

    private SettingsTab tab;
    private FakeFacade f;

    private Preview open(int width, int height) throws Exception {
        f = new FakeFacade();
        Preview p = new Preview(width, height, f, pv -> {
            tab = new SettingsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(tab);
        });
        p.settle();
        return p;
    }

    @Test
    public void firstPageAndShowAllCoverTheSettingsWithoutDuplicates() throws Exception {
        Preview p = open(1600, 900);
        Set<Tile> home = new HashSet<>(tab.grid().tiles());
        assertEquals(tab.grid().tiles().size(), home.size());
        for (int i = 0; i < 60; i++) {
            p.shell.scroll(tab.grid().x() + 40, tab.grid().y() + 40, -1);
        }
        p.advance(600);
        p.settle();
        p.click(tab.showAll().button().x() + 4, tab.showAll().button().y() + 4);
        p.settle();
        List<Tile> all = tab.grid().tiles();
        assertEquals(f.settings.map.size(), new HashSet<>(all).size());
        assertTrue(new HashSet<>(all).containsAll(home));
        // every section in Show all has something in it
        for (TileGrid.Section s : tab.grid().sections()) {
            assertFalse(s.title(), s.tiles().isEmpty());
        }
    }

    @Test
    public void clickAndKeyboardSaveAndStayInSyncWithSearch() throws Exception {
        Preview p = open(1600, 900);
        Tile parkour = tab.tileOf("allowParkour");
        p.click(parkour.x() + parkour.w() / 2f, parkour.y() + parkour.h() / 2f);
        p.settle();
        assertTrue(parkour.on());
        assertEquals("set allowParkour true", f.actions.get(f.actions.size() - 1));
        p.shell.focus(parkour);
        p.key(Keys.ENTER);
        p.settle();
        assertFalse(parkour.on());
        assertEquals("set reset allowParkour", f.actions.get(f.actions.size() - 1));
        tab.onSearch("parkour");
        p.settle();
        assertTrue(tab.grid().tiles().contains(parkour));
        assertFalse(parkour.on());
        tab.onSearch("");
        p.settle();
        assertSame(parkour, tab.tileOf("allowParkour"));
    }

    @Test
    public void rejectedNumberKeepsTheSavedValue() throws Exception {
        Preview p = open(1600, 900);
        Tile hud = tab.tileOf("altoHudScale");
        int before = f.actions.size();
        tab.grid().scrollToTile(hud);
        p.advance(400);
        p.settle();
        p.settle();
        p.click(hud.x() + hud.w() / 2f, hud.y() + hud.h() / 2f);
        p.settle();
        assertNotNull(tab.openPopover());
        p.shell.keyDown(Keys.A, Keys.MOD_CTRL);
        p.shell.keyUp(Keys.A);
        p.type("-");
        // an outside click keeps the old value for a draft that does not parse, and says nothing
        p.click(p.shell.contentX() + 40, p.shell.y() + 20);
        p.settle();
        assertEquals(before, f.actions.size());
        assertEquals("1", hud.valueText());
    }

    @Test
    public void keyboardFocusRevealsOffscreenTilesAndTheWheelReachesShowAll() throws Exception {
        Preview p = open(960, 540);
        TileGrid grid = tab.grid();
        Tile last = tab.tileOf("altoHudScale");
        p.shell.focus(last);
        // the reveal starts on the next draw and then glides, so one frame to start it and one after it landed
        p.settle();
        p.advance(500);
        p.settle();
        assertTrue(last.y() >= grid.y());
        assertTrue(last.y() + last.h() <= grid.y() + grid.h());
        p.shell.blur();
        for (int i = 0; i < 40; i++) {
            p.shell.scroll(grid.x() + 40, grid.y() + 40, -1);
        }
        p.advance(600);
        p.settle();
        var button = tab.showAll().button();
        assertTrue(button.y() + button.h() <= grid.y() + grid.h());
        assertTrue(button.y() >= grid.y());
    }

    @Test
    public void gridInBothAppearancesAndMinimumSize() throws Exception {
        Preview p = open(1920, 1080);
        assertEquals(4, tab.grid().columns());
        Tile parkour = tab.tileOf("allowParkour");
        p.hover(parkour.x() + parkour.w() / 2f, parkour.y() + parkour.h() / 2f);
        for (boolean dark : new boolean[]{true, false}) {
            p.shell.setDarkMode(dark);
            p.settle();
            p.advance(500);
            p.savePanel("settings_grid_" + (dark ? "dark" : "light"));
        }
        p.shell.setBounds(p.shell.x(), p.shell.y(), p.theme.px(560), p.theme.px(400));
        p.settle();
        p.savePanel("settings_grid_minimum");
        // two columns when there is no room for four, and every tile stays inside the grid sideways
        assertEquals(2, tab.grid().columns());
        for (Tile t : tab.grid().tiles()) {
            assertTrue(t.name(), t.x() >= tab.grid().x());
            assertTrue(t.name(), t.x() + t.w() <= tab.grid().x() + tab.grid().w());
        }
    }

    @Test
    public void tilesFitAtEveryScale() throws Exception {
        for (int height : new int[]{540, 1080, 1440, 2160}) {
            Preview p = open(height * 16 / 9, height);
            for (Tile t : tab.grid().tiles()) {
                if (!t.visible() || t.h() == 0) {
                    continue;
                }
                assertTrue(height + " " + t.name(), t.x() + t.w() <= tab.grid().x() + tab.grid().w());
                assertTrue(height + " " + t.name(), t.x() >= tab.grid().x());
            }
        }
    }
}
