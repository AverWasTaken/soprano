package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Tile;
import adris.altoclef.ui.gui.kit.TileGrid;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the look of the settings tab, in pngs under build/gui-preview (settings_page_*): the first page, halfway down,
// Show all, a search, every popover kind and a tooltip, dark and light. lives next to the tab because it clicks
// things by asking the tab where they are
public class SettingsTabPreviewTest {

    private SettingsTab tab;

    private Preview open(int w, int h, boolean dark) throws Exception {
        Preview p = new Preview(w, h, new FakeFacade(), pv -> {
            tab = new SettingsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(tab);
        });
        p.shell.setDarkMode(dark);
        rest(p);
        return p;
    }

    // animations done, the pointer parked somewhere harmless
    private static void rest(Preview p) {
        p.settle();
        p.advance(500);
        p.settle();
    }

    private void clickCentre(Preview p, Widget w) {
        p.click(w.x() + w.w() / 2f, w.y() + w.h() / 2f);
        p.settle();
    }

    // typed into the header box, so the shot shows what was searched for
    private void search(Preview p, String q) {
        p.shell.search().clearAndNotify();
        Widget box = p.shell.search();
        p.click(box.x() + box.w() / 2f, box.y() + box.h() / 2f);
        p.type(q);
        rest(p);
    }

    private void clearSearch(Preview p) {
        p.shell.search().clearAndNotify();
        p.shell.blur();
        rest(p);
    }

    private void popover(Preview p, String name, String shot) throws Exception {
        Tile t = tab.tileOf(name);
        if (!tab.grid().tiles().contains(t)) {
            search(p, SettingWords.label(name).toLowerCase(java.util.Locale.ROOT));
        }
        tab.grid().scrollToTile(t);
        rest(p);
        clickCentre(p, t);
        p.advance(300);
        p.settle();
        assertNotNull(name + " popover", tab.openPopover());
        p.savePanel(shot);
        p.key(Keys.ESCAPE);
        p.settle();
        assertNull(p.shell.overlay());
        clearSearch(p);
    }

    @Test
    public void everyStateInBothAppearances() throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            String s = dark ? "_dark" : "_light";
            Preview p = open(1920, 1080, dark);
            p.savePanel("settings_page_home" + s);

            // halfway down the first page
            TileGrid grid = tab.grid();
            for (int i = 0; i < 4; i++) {
                p.shell.scroll(grid.x() + 40, grid.y() + 40, -1);
            }
            rest(p);
            p.savePanel("settings_page_scrolled" + s);
            grid.scrollToTop();
            rest(p);

            // a tooltip on a tile
            Tile fast = tab.tileOf("fastMode");
            p.hover(fast.x() + fast.w() / 2f, fast.y() + fast.h() / 2f);
            p.settle();
            p.advance(700);
            p.settle();
            p.advance(200);
            p.settle();
            p.savePanel("settings_page_tooltip" + s);
            p.hover(-100, -100);
            rest(p);

            // every popover kind
            popover(p, "fastModeMinHealth", "settings_page_pop_number" + s);
            popover(p, "altoForceFieldStrategy", "settings_page_pop_choice" + s);
            popover(p, "colorCurrentPath", "settings_page_pop_colour" + s);
            popover(p, "prefix", "settings_page_pop_text" + s);
            popover(p, "blocksToAvoidBreaking", "settings_page_pop_list" + s);
            popover(p, "guiKey", "settings_page_pop_key" + s);

            // search results, from both pages
            search(p, "allow");
            assertTrue(tab.grid().tiles().size() > 5);
            p.savePanel("settings_page_search" + s);
            search(p, "zzzz");
            p.savePanel("settings_page_search_empty" + s);
            clearSearch(p);

            // show all, then reset all asking first. the button is under the last section
            for (int i = 0; i < 60; i++) {
                p.shell.scroll(grid.x() + 40, grid.y() + 40, -1);
            }
            rest(p);
            p.savePanel("settings_page_bottom" + s);
            clickCentre(p, tab.showAll().button());
            rest(p);
            p.savePanel("settings_page_all" + s);
            clickCentre(p, tab.bar().resetAll());
            rest(p);
            p.savePanel("settings_page_reset_confirm" + s);
            clickCentre(p, tab.bar().cancel());
        }
    }

    @Test
    public void smallWindowGoesToTwoColumns() throws Exception {
        Preview p = open(960, 540, true);
        p.savePanel("settings_page_960x540");
        p.shell.setBounds(p.shell.x(), p.shell.y(), p.theme.px(560), p.theme.px(400));
        rest(p);
        p.savePanel("settings_page_minimum");
    }
}
