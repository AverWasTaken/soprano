package adris.altoclef.ui.gui.mc;

import adris.altoclef.AltoSettings;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.Baritone;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

// altoHudScale sizes the status panel the same way it sizes the corner toast
public class StatusPanelHudTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @After
    public void restore() {
        Baritone.settings().altoHudScale.reset();
    }

    @Test
    public void hudScaleSizesThePanel() {
        assertEquals(1f, StatusPanelHud.scale(2, AltoSettings.hudScale()), 1e-6);
        assertEquals(2f, StatusPanelHud.scale(4, AltoSettings.hudScale()), 1e-6);
        Baritone.settings().altoHudScale.value = 1.5f;
        assertEquals(1.5f, StatusPanelHud.scale(2, AltoSettings.hudScale()), 1e-6);
        Baritone.settings().altoHudScale.value = 0.5f;
        assertEquals(0.5f, StatusPanelHud.scale(2, AltoSettings.hudScale()), 1e-6);
        // the same number the toast gets, always
        Baritone.settings().altoHudScale.value = 1.3f;
        assertEquals(adris.altoclef.ui.gui.hud.ToastPlacement.scale(KitSurface.scaleFor(3), AltoSettings.hudScale()),
                StatusPanelHud.scale(3, AltoSettings.hudScale()), 1e-6);
    }
}
