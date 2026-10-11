package adris.altoclef.ui.gui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class WordingTest {

    @Test
    public void replacesEveryCasing() {
        assertEquals("Get items, soprano style", Wording.soprano("Get items, altoclef style"));
        assertEquals("Soprano settings", Wording.soprano("AltoClef settings"));
        assertEquals("Soprano's task", Wording.soprano("AltoClef's task"));
        assertEquals("the Soprano brain and Soprano again", Wording.soprano("the ALTOCLEF brain and Altoclef again"));
    }

    @Test
    public void leavesSettingNamesAndPlainTextAlone() {
        String same = "altoIdleCommand runs when idle";
        assertSame(same, Wording.soprano(same));
        assertNull(Wording.soprano(null));
        assertEquals("", Wording.soprano(""));
    }
}
