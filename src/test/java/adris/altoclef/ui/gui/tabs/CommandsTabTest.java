package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Routine;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunState;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunStatus;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Step;
import adris.altoclef.ui.gui.facade.RoutinesAccess.StepType;
import adris.altoclef.ui.gui.facade.RoutinesAccess.StoreState;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CommandsTabTest {

    private FakeFacade facade;
    private Preview p;
    private CommandsTab tab;

    @Before
    public void setUp() throws Exception {
        facade = new FakeFacade();
        for (String s : new String[]{"diamond_chestplate", "diamond_leggings", "diamond_helmet", "diamond_boots"}) {
            facade.items.gettable.add(new ItemEntry(s, ItemEntry.titleCase(s), "minecraft:" + s, 1));
        }
        facade.routines.seed();
        open(1920, 1080);
    }

    private void open(int w, int h) throws Exception {
        CommandsTab[] holder = new CommandsTab[1];
        p = new Preview(w, h, facade, pv -> {
            holder[0] = new CommandsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(holder[0]);
        });
        tab = holder[0];
        tab.onShown();
        p.tick();
        p.settle();
    }

    private Routine routine(String id) {
        return facade.routines.list.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private void click(Button b) {
        p.click(b.x() + b.w() / 2f, b.y() + b.h() / 2f);
        p.tick();
        p.settle();
    }

    private void pick(String id) {
        tab.select(routine(id));
        p.tick();
        p.settle();
    }

    @Test
    public void opensOnTheFirstRoutine() {
        assertEquals("Diamond_gear", tab.selectedId());
        assertEquals(3, tab.list().items().size());
        assertEquals(2, tab.editor().rows().size());
        assertFalse(tab.editor().dirty());
        assertTrue(tab.editor().runButton().enabled());
    }

    @Test
    public void aRedRowBlocksRunButNotSave() {
        pick("Base_trip");
        StepRow bad = tab.editor().rows().get(1);
        assertNotNull(bad.why());
        assertFalse(tab.editor().runButton().enabled());
        assertTrue(tab.editor().runBlocked().startsWith("1 step needs fixing"));
        // the list row says it too, and its Run is grey with the same reason
        assertEquals(1, tab.problems("Base_trip"));
        // an edit makes it saveable even with the red row still there
        p.click(tab.editor().descField().x() + 10, tab.editor().descField().y() + 10);
        p.type("half done");
        p.settle();
        assertTrue(tab.editor().dirty());
        assertTrue(tab.editor().saveButton().enabled());
        click(tab.editor().saveButton());
        assertTrue(facade.actions.contains("save Base trip"));
        assertEquals("half done", routine("Base_trip").description());
        assertFalse(tab.editor().runButton().enabled());
    }

    @Test
    public void runSavesFirstThenRuns() {
        p.click(tab.editor().descField().x() + 10, tab.editor().descField().y() + 10);
        p.type("!");
        p.settle();
        click(tab.editor().runButton());
        int save = facade.actions.indexOf("save Diamond gear");
        int run = facade.actions.indexOf("run Diamond_gear");
        assertTrue(save >= 0 && run > save);
        assertEquals(RunState.RUNNING, facade.routines.status.state());
        // the editor's Run turns into Stop for its own run
        assertEquals("Stop", tab.editor().runButton().text());
        click(tab.editor().runButton());
        assertTrue(facade.actions.contains("stop"));
    }

    @Test
    public void aFailedRunToastsOnce() {
        facade.routines.status = new RunStatus("Diamond_gear", "Diamond gear", 2, 2, "equip diamond", RunState.RUNNING, null);
        p.tick();
        facade.routines.status = new RunStatus("Diamond_gear", "Diamond gear", 2, 2, "equip diamond", RunState.FAILED, "no diamond armor to equip");
        p.tick();
        p.settle();
        assertEquals("Diamond gear failed at step 2/2: no diamond armor to equip", p.shell.toast().currentLine());
        // the failed step says it as well, until the routine is edited
        StepRow last = tab.editor().rows().get(1);
        assertEquals(StepRow.Mark.FAIL, markOf(last));
    }

    private StepRow.Mark markOf(StepRow r) {
        return r.mark();
    }

    @Test
    public void aRunThatFailsAtOnceStillToasts() {
        facade.routines.failOnRun = "no diamonds anywhere";
        click(tab.editor().runButton());
        assertEquals("Diamond gear failed at step 1/2: no diamonds anywhere", p.shell.toast().currentLine());
    }

    @Test
    public void aBrokenFileTakesNoClicksInTheRows() {
        facade.routines.store = new StoreState(false, "bad");
        p.tick();
        p.settle();
        StepRow row = tab.editor().rows().get(0);
        // the type picker sits right after the number column
        p.click(row.x() + 60, row.y() + 15);
        p.settle();
        assertEquals(0, facade.actions.size());
        assertTrue(row.mouseDown(row.x() + 60, row.y() + 15, 0, 0));
    }

    @Test
    public void endLinesDontStutter() {
        assertEquals("Nether trip stopped at step 3/5",
                CommandsTab.endLine(new RunStatus("n", "Nether trip", 3, 5, "x", RunState.CANCELLED, "stopped")));
        assertEquals("Nether trip was replaced at step 1/5: #gamer took over",
                CommandsTab.endLine(new RunStatus("n", "Nether trip", 1, 5, "x", RunState.REPLACED, "#gamer took over")));
        assertNull(CommandsTab.endLine(new RunStatus("n", "Nether trip", 5, 5, "x", RunState.DONE, null)));
    }

    @Test
    public void reopeningTheGuiShowsTheLiveRun() throws Exception {
        facade.routines.status = new RunStatus("Nether_trip", "Nether trip", 2, 5, "get diamond_sword", RunState.RUNNING, null);
        open(1920, 1080);
        assertEquals("Nether_trip", tab.selectedId());
        assertEquals(StepRow.Mark.DONE, markOf(tab.editor().rows().get(0)));
        assertEquals(StepRow.Mark.NOW, markOf(tab.editor().rows().get(1)));
    }

    @Test
    public void aBrokenFileIsReadOnly() {
        facade.routines.store = new StoreState(false, "entry 5 (mine_run) has no command line");
        p.tick();
        p.settle();
        assertFalse(tab.newButton().enabled());
        assertFalse(tab.editor().nameField().editable());
        assertFalse(tab.editor().saveButton().enabled());
        assertFalse(tab.editor().deleteButton().enabled());
        assertFalse(tab.editor().addButton().visible());
        // nothing changed, so the saved routine still runs
        assertTrue(tab.editor().runButton().enabled());
    }

    @Test
    public void aDimensionGotoGoesRed() {
        facade.routines.list.add(new Routine("Portal", "Portal", "", List.of(new Step(StepType.GOTO, "goto 0 70 0 nether"))));
        p.tick();
        pick("Portal");
        assertEquals("use a Raw step to change dimension", tab.editor().rows().get(0).why());
        assertFalse(tab.editor().runButton().enabled());
    }

    @Test
    public void deleteAsksFirst() {
        click(tab.editor().deleteButton());
        assertTrue(facade.routines.list.stream().anyMatch(r -> r.id().equals("Diamond_gear")));
        assertEquals("Keep", tab.editor().saveButton().text());
        click(tab.editor().deleteButton());
        assertFalse(facade.routines.list.stream().anyMatch(r -> r.id().equals("Diamond_gear")));
        assertEquals("Nether_trip", tab.selectedId());
    }

    @Test
    public void newRoutineNeedsANameAndAStep() {
        click(tab.newButton());
        assertNull(tab.selectedId());
        assertFalse(tab.editor().runButton().enabled());
        p.type("Wood run");
        p.settle();
        assertEquals("Add a step first", tab.editor().runBlocked());
        click(tab.editor().addButton());
        assertEquals(1, tab.editor().rows().size());
        // a fresh get step has no items, so it's red until one goes in
        assertNotNull(tab.editor().rows().get(0).why());
    }

    @Test
    public void shots() throws Exception {
        for (int[] size : new int[][]{{1920, 1080}, {1280, 720}}) {
            open(size[0], size[1]);
            for (boolean dark : new boolean[]{true, false}) {
                p.shell.setDarkMode(dark);
                p.advance(500);
                p.savePanel("commands_list_" + (dark ? "dark_" : "light_") + size[0]);
            }
            p.shell.setDarkMode(true);
            p.advance(500);
        }
        open(1920, 1080);
        pick("Nether_trip");
        facade.routines.status = new RunStatus("Nether_trip", "Nether trip", 2, 5, "get diamond_sword", RunState.RUNNING, null);
        p.tick();
        p.hover(tab.editor().rows().get(0).x() + 200, tab.editor().rows().get(0).y() + 15);
        p.settle();
        p.advance(300);
        p.savePanel("commands_running");
        facade.routines.status = new RunStatus("Nether_trip", "Nether trip", 3, 5, "wp goto home", RunState.FAILED, "no path to waypoint home");
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("commands_failed");
        pick("Base_trip");
        p.advance(300);
        p.savePanel("commands_bad_row");
        click(tab.newButton());
        p.advance(300);
        p.savePanel("commands_new");
        facade.routines.store = new StoreState(false, "entry 5 (mine_run) has no command line");
        pick("Diamond_gear");
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("commands_store_error");
    }
}
