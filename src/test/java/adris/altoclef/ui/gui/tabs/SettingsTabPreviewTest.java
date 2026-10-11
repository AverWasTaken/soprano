package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.SettingCategories;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

// lives next to the tab (not in the preview package) because it clicks things by asking the tab where they are
public class SettingsTabPreviewTest {

    private SettingsTab tab;

    private Preview open(int w, int h) throws Exception {
        Preview p = new Preview(w, h, new FakeFacade(), pv -> {
            tab = new SettingsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(tab);
        });
        p.settle();
        p.advance(400);
        p.settle();
        return p;
    }

    // through the shell's own box, so the header says what the list is filtered by
    private void search(Preview p, String text) {
        p.shell.search().clearAndNotify();
        clickCentre(p, p.shell.search());
        p.type(text);
    }

    private SettingRow show(Preview p, String name) {
        search(p, name);
        p.settle();
        p.advance(300);
        p.settle();
        for (SettingRow r : tab.rows()) {
            if (name.equals(r.name()) && r.visible()) {
                return r;
            }
        }
        throw new AssertionError(name + " is not on screen");
    }

    private void clickCentre(Preview p, Widget w) {
        p.click(w.x() + w.w() / 2f, w.y() + w.h() / 2f);
        p.settle();
    }

    @Test
    public void states() throws Exception {
        Preview p = open(1600, 900);
        assertTrue(p.savePanel("settings_default").toFile().length() > 0);

        // hovering a row fills the detail strip
        SettingRow first = tab.rows().get(2);
        p.hover(first.x() + 40, first.y() + first.h() / 2f);
        p.settle();
        p.savePanel("settings_hover");

        // a category
        var col = tab.column();
        p.click(col.x() + col.w() / 2f, col.centreYOf(SettingCategories.MOVEMENT));
        p.settle();
        p.savePanel("settings_category_movement");

        // back to all, then the search
        p.click(col.x() + col.w() / 2f, col.centreYOf("All"));
        p.settle();
        p.type("parkour");
        p.settle();
        p.savePanel("settings_search_parkour");
        p.shell.search().clearAndNotify();

        // modified only
        clickCentre(p, col.modifiedToggle());
        p.advance(300);
        p.savePanel("settings_modified_only");
        clickCentre(p, col.modifiedToggle());

        // a number box with an error
        SettingRow r = show(p, "followRadius");
        clickCentre(p, r.number().field());
        p.shell.keyDown(Keys.A, 2);
        p.type("-");
        p.key(Keys.ENTER);
        p.settle();
        p.savePanel("settings_number_error");

        // the colour editor
        r = show(p, "colorCurrentPath");
        clickCentre(p, r.channel(1));
        p.savePanel("settings_colour");

        // the enum dropdown open
        r = show(p, "mirror");
        clickCentre(p, r.dropdown());
        p.advance(200);
        p.savePanel("settings_enum_open");
        p.key(Keys.ESCAPE);

        // the key capture waiting
        clickCentre(p, tab.keyCard().capture());
        p.advance(200);
        p.savePanel("settings_key_capture");
        p.key(Keys.ESCAPE);

        // the other editor with a parse error
        r = show(p, "blocksToAvoid");
        clickCentre(p, r.other());
        p.type("!nope");
        p.key(Keys.ENTER);
        p.settle();
        p.savePanel("settings_other_error");

        // no results
        search(p, "zzzzzz");
        p.settle();
        p.savePanel("settings_no_results");
    }

    @Test
    public void smallWindow() throws Exception {
        Preview p = open(960, 540);
        p.savePanel("settings_960x540");
        SettingRow r = show(p, "colorCurrentPath");
        p.hover(r.x() + 40, r.y() + r.h() / 2f);
        p.settle();
        p.savePanel("settings_960x540_colour");
        p.shell.search().clearAndNotify();
        p.settle();
        var col = tab.column();
        p.click(col.x() + col.w() / 2f, col.centreYOf(SettingCategories.PATHING));
        p.settle();
        p.savePanel("settings_960x540_category");
    }
}
