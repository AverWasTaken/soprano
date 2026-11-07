package adris.altoclef.ui.gui.hud;

import adris.altoclef.ui.gui.kit.Icon;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

// the old overlay's line building, now outside minecraft
public class StatusRowsTest {

    private static StatusRows.TaskLine t(String hud, String state, boolean plumbing) {
        return new StatusRows.TaskLine(hud, state, hud.replace(" ", "") + "Task{x}", "debug " + state, plumbing);
    }

    @Test
    public void nothingRunningIsNoRows() {
        assertTrue(StatusRows.build("Chain", "Chain", List.of(), false).isEmpty());
        assertTrue(StatusRows.build("Chain", "Chain", null, false).isEmpty());
    }

    @Test
    public void headerRootMidLeaf() {
        List<StatusPanel.Row> rows = StatusRows.build("Getting blocks for the path", "Getting blocks for the path",
                List.of(t("Mine and collect", "", false), t("Mining dirt", "12/24", false)), false);
        assertEquals(3, rows.size());
        assertEquals(StatusPanel.Role.ROOT, rows.get(0).role());
        assertEquals(Icon.GOTO, rows.get(0).icon());
        assertEquals(StatusPanel.Role.MID, rows.get(1).role());
        assertEquals(Icon.GET, rows.get(1).icon());
        assertEquals(StatusPanel.Role.LEAF, rows.get(2).role());
        assertEquals("12/24", rows.get(2).state());
        assertEquals(2, StatusRows.keepTop("x"));
        assertEquals(1, StatusRows.keepTop(null));
    }

    @Test
    public void oneTaskAndNoHeaderIsBothEnds() {
        List<StatusPanel.Row> rows = StatusRows.build(null, null, List.of(t("Eating", "", false)), false);
        assertEquals(1, rows.size());
        assertEquals(StatusPanel.Role.ONLY, rows.get(0).role());
    }

    @Test
    public void plumbingHidesUnlessItIsTheLeaf() {
        List<StatusPanel.Row> rows = StatusRows.build(null, null,
                List.of(t("Crafting 4 Oak Planks", "", false), t("Clicking a slot", "", true), t("Moving items", "", true)), false);
        assertEquals(2, rows.size());
        assertEquals("Moving items", rows.get(1).name());
    }

    @Test
    public void twinsMergeAndTheLeafMovesUp() {
        List<StatusPanel.Row> rows = StatusRows.build(null, null,
                List.of(t("Getting food", "", false), t("Crafting 4 Oak Planks", "", false), t("Crafting 4 Oak Planks", "2/4", false)), false);
        assertEquals(2, rows.size());
        assertEquals(StatusPanel.Role.LEAF, rows.get(1).role());
        assertEquals("2/4", rows.get(1).state());
    }

    // the developer view is the old hud: debug names and states, plumbing shown, no merging. icons still go off the hud
    // name, a class name with braces means nothing to TaskIcons
    @Test
    public void detailedShowsEverythingRaw() {
        List<StatusPanel.Row> rows = StatusRows.build("GamerChain", "Beating the game",
                List.of(t("Crafting 4 Oak Planks", "", false), t("Crafting 4 Oak Planks", "", false), t("Clicking a slot", "a", true), t("Mining dirt", "b", false)), true);
        assertEquals(5, rows.size());
        assertEquals("GamerChain", rows.get(0).name());
        assertEquals(Icon.BOLT, rows.get(0).icon());
        assertEquals("ClickingaslotTask{x}", rows.get(3).name());
        assertEquals("debug a", rows.get(3).state());
        assertEquals(Icon.PICKAXE, rows.get(4).icon());
    }
}
