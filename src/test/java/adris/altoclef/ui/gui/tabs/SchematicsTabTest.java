package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SchematicsAccess.ImportReport;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Row;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SchematicsTabTest {

    private FakeFacade facade;
    private Preview p;
    private SchematicsTab tab;

    @Before
    public void setUp() throws Exception {
        facade = new FakeFacade();
        SchematicsTab[] holder = new SchematicsTab[1];
        p = new Preview(facade, pv -> {
            holder[0] = new SchematicsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(holder[0]);
        });
        tab = holder[0];
        tab.onShown();
        p.settle();
    }

    private List<String> paths() {
        List<String> out = new ArrayList<>();
        for (Row r : tab.list().items()) {
            out.add(r.relativePath());
        }
        return out;
    }

    private void clickRow(int i) {
        p.click(tab.list().x() + 60, tab.rowMidY(i));
        p.settle();
    }

    private void clickBuild() {
        p.click(tab.buildButton().x() + 10, tab.buildButton().y() + 10);
        p.settle();
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            p.tick();
        }
    }

    @Test
    public void sidebarContract() {
        assertEquals("schematics", tab.id());
        assertEquals("Schematics", tab.title());
        assertTrue(tab.hasSearch());
        assertEquals("Search schematics", tab.searchPlaceholder());
    }

    @Test
    public void listsEveryFileInTheFolder() {
        assertEquals(List.of("Small Castle.litematic", "tower.schem", "broken.litematic", "villages/farm.schematic"), paths());
        assertEquals("4 schematics · 1 didn't parse", tab.countLine());
        assertNull(tab.selectedPath());
        assertFalse(tab.buildButton().enabled());
    }

    @Test
    public void searchFiltersByPath() {
        tab.onSearch("villages");
        assertEquals(List.of("villages/farm.schematic"), paths());
        assertEquals("1 of 4 schematics · 1 didn't parse", tab.countLine());
        tab.onSearch("LITEMATIC");
        assertEquals(List.of("Small Castle.litematic", "broken.litematic"), paths());
        tab.onSearch("zzz");
        assertTrue(paths().isEmpty());
        assertEquals("No schematics match", tab.listState());
        tab.onSearch("");
        assertEquals(4, paths().size());
    }

    @Test
    public void clickingARowShowsItsMaterials() {
        clickRow(0);
        assertEquals("Small Castle.litematic", tab.selectedPath());
        assertTrue(tab.pane().showsList());
        assertEquals("3,557 blocks · 7 kinds · 3,490 short", tab.pane().summary());
        assertEquals("3,490 short. It pauses when it runs out.", tab.pane().warning());
        assertTrue(tab.buildButton().enabled());
        // 7 items, the two folded kinds, one note
        assertEquals(10, tab.pane().list().items().size());
    }

    @Test
    public void keyboardSelectsToo() {
        p.click(tab.list().x() + 60, tab.rowMidY(0));
        p.key(Keys.DOWN);
        p.key(Keys.ENTER);
        assertEquals("tower.schem", tab.selectedPath());
    }

    @Test
    public void foldedKindsOpenOnClick() {
        clickRow(0);
        var mats = tab.pane().list();
        // the section rows sit after the seven items
        float y = mats.y() + 7 * mats.rowHeight() + mats.rowHeight() / 2f;
        p.click(mats.x() + 40, y);
        p.settle();
        assertTrue(tab.pane().openSections().contains("Can't place"));
        assertEquals(11, mats.items().size());
        assertEquals(MaterialRows.Type.SUB, mats.items().get(8).type());
        p.click(mats.x() + 40, y);
        p.settle();
        assertEquals(10, mats.items().size());
    }

    @Test
    public void everythingInHandReadsAsCovered() {
        for (String id : List.of("minecraft:stone_bricks", "minecraft:oak_log", "minecraft:glass", "minecraft:oak_stairs",
                "minecraft:torch", "minecraft:farmland", "minecraft:beacon")) {
            facade.schematics.have.put(id, 5000);
        }
        clickRow(0);
        assertEquals("3,557 blocks · 7 kinds · all in your inventory", tab.pane().summary());
        // fire can't be placed, that still gets a line
        assertEquals("Some blocks can't be placed, those spots stay empty.", tab.pane().warning());
    }

    @Test
    public void inventoryChangesShowUpOnTick() {
        clickRow(0);
        facade.schematics.have.put("minecraft:stone_bricks", 3120);
        ticks(1);
        assertEquals("3,557 blocks · 7 kinds · 370 short", tab.pane().summary());
    }

    @Test
    public void selectionIsByPathNotIndex() {
        clickRow(1);
        assertEquals("tower.schem", tab.selectedPath());
        // a new file lands at the end, the index of nothing else moves, but a vanished first row would
        facade.schematics.remove("Small Castle.litematic");
        ticks(1);
        assertEquals(List.of("tower.schem", "broken.litematic", "villages/farm.schematic"), paths());
        assertEquals("tower.schem", tab.selectedPath());
        assertEquals(0, tab.list().items().indexOf(tab.list().items().stream().filter(r -> r.relativePath().equals("tower.schem")).findFirst().get()));
    }

    @Test
    public void aVanishedSelectionFallsBackToNothing() {
        clickRow(0);
        clickBuild();
        assertTrue(tab.armed());
        facade.schematics.remove("Small Castle.litematic");
        ticks(1);
        assertNull(tab.selectedPath());
        assertFalse(tab.armed());
        assertFalse(tab.buildButton().enabled());
        assertFalse(tab.pane().showsList());
    }

    @Test
    public void buildTakesTwoClicks() {
        clickRow(0);
        clickBuild();
        assertTrue(tab.armed());
        assertEquals("Click again to build", tab.buildButton().text());
        assertTrue(facade.actions.isEmpty());
        clickBuild();
        assertFalse(tab.armed());
        assertEquals("Build at my feet", tab.buildButton().text());
        assertEquals(List.of("schematics build Small Castle.litematic"), facade.actions);
        assertEquals("Building Small Castle.litematic at your feet", p.shell.toast().currentLine());
        assertTrue(p.shell.toast().currentOk());
    }

    @Test
    public void anArmedButtonLapsesOnItsOwn() {
        clickRow(0);
        clickBuild();
        ticks(59);
        assertTrue(tab.armed());
        ticks(1);
        assertFalse(tab.armed());
        clickBuild();
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void changingTheSelectionDisarms() {
        clickRow(0);
        clickBuild();
        clickRow(3);
        assertFalse(tab.armed());
        assertEquals("villages/farm.schematic", tab.selectedPath());
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void leavingTheTabOrSearchingDisarms() {
        clickRow(0);
        clickBuild();
        tab.onHidden();
        assertFalse(tab.armed());
        clickBuild();
        tab.onSearch("a");
        assertFalse(tab.armed());
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void aFailedBuildToastsRed() {
        facade.schematics.buildError = "No world loaded";
        clickRow(0);
        clickBuild();
        clickBuild();
        p.settle();
        assertEquals("No world loaded", p.shell.toast().currentLine());
        assertFalse(p.shell.toast().currentOk());
    }

    @Test
    public void noWorldMeansNoBuild() {
        facade.world.pos = null;
        clickRow(0);
        assertFalse(tab.buildButton().enabled());
        assertEquals("Join a world to build", tab.pane().warning());
        clickBuild();
        assertFalse(tab.armed());
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void aBrokenFileIsSelectableButNotBuildable() {
        clickRow(2);
        assertEquals("broken.litematic", tab.selectedPath());
        assertEquals("EOFException: unexpected end of file", tab.pane().errorText());
        assertFalse(tab.pane().showsList());
        assertFalse(tab.buildButton().enabled());
        assertEquals("Didn't parse", tab.pane().summary());
    }

    @Test
    public void importToastsTheReportAndShowsTheFile() {
        p.click(tab.importButton().x() + 10, tab.importButton().y() + 10);
        p.settle();
        assertEquals(List.of("schematics pick"), facade.actions);
        assertEquals("Imported picked.litematic", p.shell.toast().currentLine());
        assertTrue(paths().contains("picked.litematic"));
        assertEquals("5 schematics · 1 didn't parse", tab.countLine());
    }

    @Test
    public void aCancelledPickerIsAQuietToast() {
        facade.schematics.nextPick = ImportReport.nothingChosen();
        p.click(tab.importButton().x() + 10, tab.importButton().y() + 10);
        p.settle();
        assertEquals("Nothing chosen", p.shell.toast().currentLine());
        assertEquals(4, paths().size());
    }

    @Test
    public void openFolderToasts() {
        p.click(tab.folderButton().x() + 10, tab.folderButton().y() + 10);
        p.settle();
        assertEquals(List.of("schematics folder"), facade.actions);
        assertEquals("Opened the schematics folder", p.shell.toast().currentLine());
    }

    @Test
    public void refreshKeepsTheRowsUpWhileItScans() {
        facade.schematics.loadingPolls = 3;
        p.click(tab.refreshButton().x() + 10, tab.refreshButton().y() + 10);
        p.settle();
        assertEquals(List.of("schematics refresh"), facade.actions);
        assertEquals(4, paths().size());
        assertEquals("4 schematics · 1 didn't parse · Refreshing…", tab.countLine());
        assertEquals("", tab.listState());
        ticks(3);
        assertEquals("4 schematics · 1 didn't parse", tab.countLine());
    }

    @Test
    public void theFirstScanHasItsOwnLine() {
        facade.schematics.clear();
        facade.schematics.loadingForever = true;
        tab.onShown();
        assertTrue(paths().isEmpty());
        assertEquals("Reading the schematics folder…", tab.listState());
        assertEquals("", tab.countLine());
        assertFalse(tab.pane().visible());
        facade.schematics.loadingForever = false;
        ticks(1);
        assertEquals("", tab.listState());
        assertEquals("0 schematics", tab.countLine());
        assertFalse(tab.pane().visible());
        facade.schematics.importDropped(List.of(java.nio.file.Path.of("new.litematic")));
        ticks(1);
        assertTrue(tab.pane().visible());
    }

    @Test
    public void materialsStillCountingShowsALoadingPane() {
        facade.schematics.loadingForever = true;
        clickRow(0);
        assertTrue(tab.pane().loading());
        assertEquals("Counting blocks…", tab.pane().summary());
        // no building before the count says what you are short of
        assertFalse(tab.buildButton().enabled());
        assertEquals("Counting blocks…", tab.pane().warning());
        facade.schematics.loadingForever = false;
        ticks(1);
        assertTrue(tab.buildButton().enabled());
    }

    @Test
    public void aFileThatCannotBeCountedStaysUnbuildable() {
        facade.schematics.importDropped(List.of(java.nio.file.Path.of("odd.litematic")));
        facade.schematics.forgetMaterials("odd.litematic");
        ticks(1);
        clickRow(4);
        assertEquals("Couldn't read this file", tab.pane().errorText());
        assertEquals("Couldn't count the blocks", tab.pane().warning());
        assertFalse(tab.buildButton().enabled());
    }

    @Test
    public void tooltipShowsTheErrorOrTheCutPathAfterARest() {
        p.hover(tab.list().x() + 60, tab.rowMidY(2));
        p.settle();
        assertNull(tab.tipText());
        ticks(10);
        assertEquals("EOFException: unexpected end of file", tab.tipText());
        p.hover(-100, -100);
        p.settle();
        assertNull(tab.tipText());
        // a path that fits gets no tip, however long it is
        p.hover(tab.list().x() + 60, tab.rowMidY(3));
        p.settle();
        ticks(10);
        assertNull(tab.tipText());
        // the fake keeps the file name only, the real importer keeps a dropped folder's sub folders
        facade.schematics.importDropped(List.of(java.nio.file.Path.of("a path long enough that the row has to cut it short somewhere.litematic")));
        ticks(1);
        p.hover(tab.list().x() + 60, tab.rowMidY(4));
        p.settle();
        ticks(10);
        assertEquals("a path long enough that the row has to cut it short somewhere.litematic", tab.tipText());
    }

    @Test
    public void unchangedDataNeverRebuildsTheLists() {
        clickRow(0);
        List<Row> rowsBefore = tab.list().items();
        var matsBefore = tab.pane().list().items();
        ticks(5);
        assertSame(rowsBefore, tab.list().items());
        assertSame(matsBefore, tab.pane().list().items());
        // a rescan that finds the same files leaves the rows alone too (the fake answers LOADING to the count as
        // well while it polls, so only the rows are checked past the refresh)
        facade.schematics.loadingPolls = 2;
        p.click(tab.refreshButton().x() + 10, tab.refreshButton().y() + 10);
        ticks(5);
        assertSame(rowsBefore, tab.list().items());
    }

    @Test
    public void theWarningMarkIsForUnknownBlocksOnly() {
        assertFalse(tab.warned(0));
        assertTrue(tab.warned(1));
        assertFalse(tab.warned(2));
        assertFalse(tab.warned(3));
    }

    @Test
    public void theKeyboardCursorFollowsTheSelectionThroughARefresh() {
        clickRow(2);
        assertEquals(2, tab.list().selected());
        facade.schematics.remove("Small Castle.litematic");
        ticks(1);
        assertEquals(1, tab.list().selected());
        tab.onSearch("broken");
        assertEquals(0, tab.list().selected());
        tab.onSearch("tower");
        assertEquals(-1, tab.list().selected());
        assertEquals("broken.litematic", tab.selectedPath());
    }

    @Test
    public void tabOrderIsToolbarThenListThenPane() {
        p.key(Keys.TAB);
        assertSame(p.shell.search(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.importButton(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.folderButton(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.refreshButton(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.list(), p.shell.focusedWidget());
    }

    @Test
    public void rules() {
        assertEquals("1 schematic", SchematicRules.count(1, 1, 0));
        assertEquals("0 schematics", SchematicRules.count(0, 0, 0));
        assertEquals("2 of 9 schematics · 3 didn't parse", SchematicRules.count(2, 9, 3));
        assertEquals("120 B", SchematicRules.fileSize(120));
        assertEquals("47 KB", SchematicRules.fileSize(48_211));
        assertEquals("1.5 MB", SchematicRules.fileSize(1_572_864));
        assertEquals("3,120", SchematicRules.num(3120));
        List<Row> rows = facade.schematics.list().rows();
        assertEquals("Litematica v6 · 24 x 18 x 24 · 47 KB", SchematicRules.meta(rows.get(0)));
        assertEquals("Litematica · 120 B", SchematicRules.meta(rows.get(2)));
        assertEquals("Didn't parse", SchematicRules.sub(rows.get(2)));
        assertEquals("MCEdit · 15 x 6 x 12", SchematicRules.sub(rows.get(3)));
    }
}
