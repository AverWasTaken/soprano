package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertTrue;

// the schematics tab in every state worth looking at, written to build/gui-preview
public class SchematicsTabPreviewTest {

    private FakeFacade facade;
    private Preview p;
    private SchematicsTab tab;

    private void open(int w, int h) throws Exception {
        facade = new FakeFacade();
        SchematicsTab[] holder = new SchematicsTab[1];
        p = new Preview(w, h, facade, pv -> {
            holder[0] = new SchematicsTab(pv.theme, pv.facade, pv.ctx);
            return List.<Tab>of(holder[0]);
        });
        tab = holder[0];
        tab.onShown();
        p.settle();
        p.advance(300);
    }

    private void clickRow(int i) {
        p.click(tab.list().x() + 60, tab.rowMidY(i));
        p.settle();
        p.advance(300);
        p.hover(-100, -100);
    }

    private void coverEverything() {
        for (String id : List.of("minecraft:stone_bricks", "minecraft:oak_log", "minecraft:glass", "minecraft:oak_stairs",
                "minecraft:torch", "minecraft:farmland", "minecraft:beacon")) {
            facade.schematics.have.put(id, 5000);
        }
    }

    @Test
    public void states() throws Exception {
        open(1600, 900);
        p.hover(-100, -100);
        p.savePanel("schematics_nothing_selected");

        // the castle with a few stacks in the inventory: the common case
        clickRow(0);
        p.savePanel("schematics_missing");

        // the folded kinds open
        var mats = tab.pane().list();
        float sy = mats.y() + 7 * mats.rowHeight() + mats.rowHeight() / 2f;
        p.click(mats.x() + 40, sy);
        p.settle();
        p.advance(300);
        p.click(mats.x() + 40, sy + 2 * mats.rowHeight());
        p.settle();
        p.advance(300);
        p.hover(-100, -100);
        p.savePanel("schematics_sections_open");

        // the first click arms the button
        p.click(tab.buildButton().x() + 10, tab.buildButton().y() + 10);
        p.settle();
        p.advance(200);
        p.hover(-100, -100);
        p.savePanel("schematics_armed");
        tab.onHidden();
        tab.onShown();

        // everything in hand
        coverEverything();
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_happy");

        // a file naming blocks this version doesn't know
        clickRow(1);
        p.savePanel("schematics_unknown_blocks");

        // a file that didn't parse, and its tooltip
        clickRow(2);
        p.savePanel("schematics_error_row");
        p.hover(tab.list().x() + 60, tab.rowMidY(2));
        for (int i = 0; i < 12; i++) {
            p.tick();
        }
        p.advance(100);
        p.savePanel("schematics_error_tooltip");
        p.hover(-100, -100);

        // the search box narrows the list
        p.click(p.shell.search().x() + 20, p.shell.search().y() + 10);
        p.type("villages");
        p.advance(200);
        p.hover(-100, -100);
        p.savePanel("schematics_search");
        p.type("zzz");
        p.advance(200);
        p.savePanel("schematics_no_match");
        assertTrue(Files.size(Preview.OUT.resolve("schematics_missing.png")) > 1000);
    }

    @Test
    public void loadingAndEmpty() throws Exception {
        open(1600, 900);
        p.hover(-100, -100);
        // the rows stay up while a refresh runs
        facade.schematics.loadingForever = true;
        p.click(tab.refreshButton().x() + 10, tab.refreshButton().y() + 10);
        p.settle();
        p.advance(300);
        p.hover(-100, -100);
        p.savePanel("schematics_refreshing");

        // the count of a file still running
        facade.schematics.loadingForever = false;
        p.tick();
        clickRow(0);
        facade.schematics.loadingForever = true;
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_counting");

        // the very first scan, nothing to show yet
        facade.schematics.clear();
        tab.onShown();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_loading");

        // an empty folder
        facade.schematics.loadingForever = false;
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_empty");
    }

    @Test
    public void lightAndLongNames() throws Exception {
        open(1600, 900);
        p.shell.setDarkMode(false);
        p.settle();
        p.advance(400);
        clickRow(0);
        p.savePanel("schematics_light");

        p.shell.setDarkMode(true);
        p.settle();
        p.advance(400);
        facade.schematics.importDropped(List.of(java.nio.file.Path.of("builds/a rather long schematic name that keeps going past the card.litematic")));
        p.tick();
        p.settle();
        p.advance(300);
        clickRow(4);
        p.hover(tab.list().x() + 60, tab.rowMidY(4));
        for (int i = 0; i < 12; i++) {
            p.tick();
        }
        p.advance(100);
        p.savePanel("schematics_long_name_tooltip");
    }

    @Test
    public void smallWindow() throws Exception {
        open(960, 540);
        p.hover(-100, -100);
        clickRow(0);
        p.save("schematics_960");
        facade.world.pos = null;
        p.tick();
        p.settle();
        p.save("schematics_960_no_world");
    }
}
