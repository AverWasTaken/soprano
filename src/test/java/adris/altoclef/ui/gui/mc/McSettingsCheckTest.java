package adris.altoclef.ui.gui.mc;

import adris.altoclef.util.helpers.ItemFixture;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// check is the validator under the list, map and number popovers: the real #set parser, nothing applied, and a
// sentence a player can read instead of whatever the parser threw
public class McSettingsCheckTest {

    @BeforeClass
    public static void bootstrap() {
        // block names need the registries
        ItemFixture.boot();
    }

    private final McSettings s = new McSettings();

    @Test
    public void goodTextPassesAndNothingChanges() {
        String before = s.get("blocksToAvoid").value();
        assertNull(s.check("allowBreak", "false"));
        assertNull(s.check("blocksToAvoid", "minecraft:stone,minecraft:dirt"));
        assertNull(s.check("blocksToAvoid", ""));
        assertNull(s.check("buildSubstitutes", ""));
        assertNull(s.check("buildSubstitutes", "minecraft:stone->minecraft:dirt"));
        assertEquals(before, s.get("blocksToAvoid").value());
    }

    @Test
    public void badTextGetsASentenceNotJava() {
        String block = s.check("blocksToAvoid", "minecraft:not_a_block_at_all");
        assertNotNull(block);
        assertTrue(block, block.contains("not_a_block_at_all"));
        // a map entry with no arrow is an index out of bounds in the parser
        String map = s.check("buildSubstitutes", "minecraft:stone");
        assertNotNull(map);
        assertFalse(map, map.contains("Index"));
        assertFalse(map, map.contains("bounds"));
        assertEquals("Not a number. Try something like 64 or 1.5.", s.check("maxFallHeightNoWater", "abc"));
        assertNotNull(s.check("nonsenseSettingName", "1"));
    }

    @Test
    public void oldNamesComeFromTheRealRenames() {
        assertEquals(List.of("experimentalmovement"), s.oldNames("fastMode"));
        assertEquals(List.of("experimentalminhealth"), s.oldNames("fastModeMinHealth"));
        assertEquals(List.of(), s.oldNames("allowBreak"));
    }
}
