package baritone.api;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.SettingsUtil;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// the gui's list and map popovers hand the text straight to #set's parser, and settings.txt is that same text, so an
// emptied collection has to survive save and load like everything else. save is settingToString per modified setting
// and load is applyLine per line, that pair is what runs here (the real file lives in the game directory)
public class SettingsCollectionsTest {

    @BeforeClass
    public static void bootstrap() {
        // block parsing and the default block lists need the registries
        ItemFixture.boot();
    }

    // what settings.txt would hold for these settings, read back into fresh ones
    private static Settings saveAndLoad(Settings settings) {
        Settings fresh = new Settings();
        for (Settings.Setting<?> s : SettingsUtil.modifiedSettings(settings)) {
            SettingsUtil.applyLine(fresh, SettingsUtil.settingToString(s));
        }
        return fresh;
    }

    @Test
    public void clearingAListWithADefaultSticksAfterReload() {
        Settings settings = new Settings();
        assertFalse(settings.blocksToAvoidBreaking.value.isEmpty());
        SettingsUtil.parseAndApply(settings, "blockstoavoidbreaking", "");
        assertTrue(settings.blocksToAvoidBreaking.value.isEmpty());
        assertEquals("blocksToAvoidBreaking ", SettingsUtil.settingToString(settings.blocksToAvoidBreaking));
        Settings back = saveAndLoad(settings);
        assertTrue(back.blocksToAvoidBreaking.value.isEmpty());
    }

    @Test
    public void aMapSurvivesSaveAndLoadAndSoDoesClearingIt() {
        Settings settings = new Settings();
        SettingsUtil.parseAndApply(settings, "buildsubstitutes", "minecraft:stone->minecraft:dirt,minecraft:cobblestone");
        assertEquals(List.of(Blocks.DIRT, Blocks.COBBLESTONE), settings.buildSubstitutes.value.get(Blocks.STONE));
        Settings back = saveAndLoad(settings);
        assertEquals(List.of(Blocks.DIRT, Blocks.COBBLESTONE), back.buildSubstitutes.value.get(Blocks.STONE));

        // emptied: used to be one entry with no arrow and an index out of bounds on load
        SettingsUtil.parseAndApply(settings, "buildsubstitutes", "");
        assertTrue(settings.buildSubstitutes.value.isEmpty());
        // a fresh map, not the default one, so it still counts as changed and gets saved
        assertNotSame(settings.buildSubstitutes.defaultValue, settings.buildSubstitutes.value);
        assertEquals("buildSubstitutes ", SettingsUtil.settingToString(settings.buildSubstitutes));
        Settings cleared = saveAndLoad(settings);
        assertTrue(cleared.buildSubstitutes.value.isEmpty());
    }

    @Test
    public void parseValueChecksWithoutApplying() {
        Settings settings = new Settings();
        Object parsed = SettingsUtil.parseValue(settings.allowBreak, "false");
        assertEquals(false, parsed);
        assertTrue(settings.allowBreak.value);
        boolean threw = false;
        try {
            SettingsUtil.parseValue(settings.blocksToAvoid, "minecraft:not_a_block_at_all");
        } catch (RuntimeException e) {
            threw = true;
        }
        assertTrue(threw);
        assertSame(settings.blocksToAvoid.defaultValue, settings.blocksToAvoid.value);
    }
}
