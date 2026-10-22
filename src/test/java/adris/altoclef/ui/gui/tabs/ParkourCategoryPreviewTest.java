package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SettingsAccess.Kind;
import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.model.SettingCategories;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the Parkour shelf of the settings tab, with the real jump settings and their real descriptions on it
public class ParkourCategoryPreviewTest {

    private static final String[][] BOOLS = {
            {"allowParkour", "false"}, {"allowParkourAscend", "true"}, {"allowParkourPlace", "false"}, {"allowNeos", "false"},
            {"allowMomentumJumps", "false"}, {"allowClimbJumps", "false"}, {"allowTungstenJumps", "false"},
            {"sprintJumping", "false"}, {"headHitters", "false"},
    };

    private SettingsTab tab;

    @Test
    public void theParkourShelfHasEveryJump() throws Exception {
        FakeFacade facade = new FakeFacade();
        Properties desc = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/adris/altoclef/ui/gui/settings-descriptions.properties")) {
            desc.load(in);
        }
        facade.settings.map.remove("allowParkour");
        facade.settings.map.remove("allowParkourPlace");
        for (String[] b : BOOLS) {
            facade.settings.map.put(b[0], new SettingView(b[0], Kind.BOOLEAN, "Boolean", b[1], b[1], false, List.of()));
            facade.settings.descriptions.put(b[0], desc.getProperty(b[0], ""));
        }
        facade.settings.map.put("tungstenPlannerBudget", new SettingView("tungstenPlannerBudget", Kind.INTEGER, "Integer", "400000", "400000", false, List.of()));
        facade.settings.descriptions.put("tungstenPlannerBudget", desc.getProperty("tungstenPlannerBudget", ""));
        facade.settings.map.put("jumpPenalty", new SettingView("jumpPenalty", Kind.DOUBLE, "Double", "2.0", "2.0", false, List.of()));
        facade.settings.descriptions.put("jumpPenalty", desc.getProperty("jumpPenalty", ""));

        Preview p = new Preview(1600, 900, facade, pv -> {
            tab = new SettingsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(tab);
        });
        p.settle();
        tab.setAdvanced(true);
        p.settle();
        p.click(tab.toolbar().categories().x() + tab.toolbar().categories().w() / 2f,
                tab.toolbar().categories().y() + tab.toolbar().categories().h() / 2f);
        p.settle();
        p.type(SettingCategories.PARKOUR);
        p.key(Keys.ENTER);
        p.settle();
        p.advance(400);
        p.settle();
        assertTrue(p.savePanel("settings_category_parkour").toFile().length() > 0);
        long shown = tab.rows().stream().filter(r -> r.visible()).count();
        assertTrue("rows on screen: " + shown, shown >= 6);
        for (String[] b : BOOLS) {
            assertEquals(b[0], SettingCategories.PARKOUR, SettingCategories.categoryOf(b[0]));
        }
    }
}
