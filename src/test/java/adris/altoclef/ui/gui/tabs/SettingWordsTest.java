package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SettingsAccess.Kind;
import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingWordsTest {

    private static SettingView v(Kind kind, String type, String value, String def) {
        return new SettingView("x", kind, type, value, def, !value.equals(def), List.of());
    }

    @Test
    public void splitKeepsEveryWordAndAcronyms() {
        assertEquals("Allow Parkour", SettingWords.split("allowParkour"));
        assertEquals("Primary Timeout MS", SettingWords.split("primaryTimeoutMS"));
        assertEquals("Allow Jump At Build Limit", SettingWords.split("allowJumpAtBuildLimit"));
        assertEquals("Render Goal XZ Beacon", SettingWords.split("renderGoalXZBeacon"));
        assertEquals("", SettingWords.split(""));
    }

    @Test
    public void altoIsDroppedOnScreenOnly() {
        assertEquals("Mob Defense", SettingWords.label("altoMobDefense"));
        assertEquals("Hud Scale", SettingWords.label("altoHudScale"));
        // a name that merely starts with the letters keeps them
        assertEquals("Altogether", SettingWords.label("altogether"));
        assertEquals("Allow Break", SettingWords.label("allowBreak"));
    }

    @Test
    public void cornerValuesAreShort() {
        assertEquals("On", SettingWords.shown(v(Kind.BOOLEAN, "Boolean", "true", "false"), null));
        assertEquals("12", SettingWords.shown(v(Kind.DOUBLE, "Double", "12.0", "12.0"), null));
        assertEquals("0.75", SettingWords.shown(v(Kind.FLOAT, "Float", "0.75", "1.0"), null));
        assertEquals("100", SettingWords.shown(v(Kind.INTEGER, "Integer", "100", "1"), null));
        assertEquals("Left right", SettingWords.shown(v(Kind.ENUM, "Mirror", "LEFT_RIGHT", "NONE"), null));
        assertEquals("Red", SettingWords.shown(v(Kind.COLOR, "Color", "217,24,43", "217,24,43"), null));
        assertEquals("#010203", SettingWords.shown(v(Kind.COLOR, "Color", "1,2,3", "1,2,3"), null));
        assertEquals("Right Shift", SettingWords.shown(v(Kind.KEY, "Integer", "344", "344"), k -> k == 344 ? "Right Shift" : ""));
        assertEquals("None", SettingWords.shown(v(Kind.KEY, "Integer", "-1", "344"), k -> ""));
        assertEquals("None", SettingWords.shown(v(Kind.STRING, "String", "", "x"), null));
        assertEquals("3 blocks", SettingWords.shown(v(Kind.OTHER, "List<Block>", "a,b,c", ""), null));
        assertEquals("1 item", SettingWords.shown(v(Kind.OTHER, "List<Item>", "minecraft:stick", ""), null));
        assertEquals("None", SettingWords.shown(v(Kind.OTHER, "List<Block>", "", "a"), null));
        // the value side of a map entry may be a list, commas and all
        assertEquals("2 entries", SettingWords.shown(v(Kind.OTHER, "Map<Block, List<Block>>", "a->b,c,d->e", ""), null));
        assertEquals("1 entry", SettingWords.shown(v(Kind.OTHER, "Map<Block, List<Block>>", "a->b,c", ""), null));
    }

    @Test
    public void defaultsTypedByHandCountAsTheDefault() {
        assertTrue(SettingWords.sameValue(Kind.DOUBLE, "12", "12.0"));
        assertFalse(SettingWords.sameValue(Kind.DOUBLE, "12.5", "12.0"));
        assertTrue(SettingWords.sameValue(Kind.ENUM, "smart", "SMART"));
        assertTrue(SettingWords.sameValue(Kind.COLOR, "1, 2, 3", "1,2,3"));
        assertFalse(SettingWords.sameValue(Kind.STRING, "# ", "#"));
        assertFalse(SettingWords.sameValue(Kind.INTEGER, "abc", "1"));
    }

    @Test
    public void searchNeedsEveryWordSomewhere() {
        assertTrue(SettingWords.matches("parkour place", "allowParkourPlace", List.of(), ""));
        assertTrue(SettingWords.matches("mob defense", "altoMobDefense", List.of(), ""));
        assertTrue(SettingWords.matches("alto mob", "altoMobDefense", List.of(), ""));
        assertTrue(SettingWords.matches("CREEPERS", "altoMobDefense", List.of(), "Runs from creepers."));
        assertTrue(SettingWords.matches("experimental", "fastMode", List.of("experimentalmovement"), ""));
        assertFalse(SettingWords.matches("parkour boat", "allowParkourPlace", List.of(), ""));
        assertTrue(SettingWords.matches("  ", "anything", List.of(), ""));
    }
}
