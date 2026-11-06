package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphRunStatus;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunState;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunStatus;
import adris.altoclef.ui.gui.kit.CanvasView;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertTrue;

// the node editor in the real shell, in both appearances. the look gets judged against the mock by eye, a png nobody opened
// proves nothing
public class NodeEditorPreviewTest {

    private FakeFacade facade;
    private Preview p;
    private CommandsTab tab;

    @Before
    public void setUp() throws Exception {
        facade = new FakeFacade();
        for (String s : new String[]{"oak_log", "bread", "iron_ingot", "diamond_sword", "stick"}) {
            facade.items.gettable.add(new ItemEntry(s, ItemEntry.titleCase(s), "minecraft:" + s, 64));
        }
        facade.routines.seed();
        facade.routines.seedGraphs();
        CommandsTab[] holder = new CommandsTab[1];
        p = new Preview(1920, 1200, 1f, facade, pv -> {
            holder[0] = new CommandsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(holder[0]);
        });
        tab = holder[0];
        tab.onShown();
        p.tick();
        p.settle();
    }

    private NodeEditor ed() {
        return tab.nodeEditor();
    }

    private void openGraph(String id) {
        tab.select(tab.list().items().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow());
        settled();
    }

    private void settled() {
        p.tick();
        p.settle();
        p.advance(500);
    }

    private void both(String name) throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            p.shell.setDarkMode(dark);
            p.advance(500);
            Path out = p.savePanel("nodes_" + name + (dark ? "_dark" : "_light"));
            assertTrue(Files.size(out) > 0);
        }
    }

    private void woodLoopMidRun() {
        facade.routines.status = new RunStatus("Wood_loop", "Wood loop", 3, 5, "", RunState.RUNNING, null);
        facade.routines.graphStatus = new GraphRunStatus("Wood_loop", RunState.RUNNING, null, "m", null, List.of("s", "r", "g", "m"),
                Map.of("s", "OK", "g", "OK", "r", "YES"), Map.of("r", 3), Set.of("s:out:r:in", "r:body:g:in", "g:ok:m:in", "m:ok:r:again"));
    }

    @Test
    public void listViewWithTheSwitch() throws Exception {
        both("list_switch");
    }

    @Test
    public void emptyOne() throws Exception {
        tab.pick(null);
        tab.showNodes();
        settled();
        both("empty");
    }

    @Test
    public void woodLoopIdle() throws Exception {
        openGraph("Wood_loop");
        both("wood_idle");
    }

    @Test
    public void woodLoopRunning() throws Exception {
        openGraph("Wood_loop");
        woodLoopMidRun();
        settled();
        both("wood_running");
    }

    @Test
    public void woodLoopRunningWithTheInspector() throws Exception {
        openGraph("Wood_loop");
        woodLoopMidRun();
        ed().select("r");
        settled();
        both("wood_running_inspector");
    }

    @Test
    public void failedRun() throws Exception {
        openGraph("Wood_loop");
        facade.routines.status = new RunStatus("Wood_loop", "Wood loop", 3, 5, "", RunState.FAILED, "no path to the nearest oak_log");
        facade.routines.graphStatus = new GraphRunStatus("Wood_loop", RunState.FAILED, "no path to the nearest oak_log", null, "g", List.of("s", "r", "g"),
                Map.of("s", "OK", "g", "FAILED", "r", "YES"), Map.of("r", 1), Set.of("s:out:r:in", "r:body:g:in"));
        settled();
        both("failed_toast");
        // long enough for the toast to get out of the way of the line it's sitting on
        p.advance(8000);
        both("failed");
    }

    @Test
    public void validatorErrors() throws Exception {
        openGraph("Half_built");
        both("errors");
        ed().select("m");
        settled();
        both("errors_inspector");
    }

    @Test
    public void wireSelected() throws Exception {
        openGraph("Wood_loop");
        ed().wireClicked("g:ok:m:in");
        settled();
        both("wire_selected");
    }

    @Test
    public void addMenuOpen() throws Exception {
        openGraph("Wood_loop");
        ed().dropAt("g", "failed", 600f, 320f);
        settled();
        both("add_menu");
    }

    @Test
    public void paletteOpen() throws Exception {
        openGraph("Wood_loop");
        ed().palette().setOpen(true);
        ed().foldChanged();
        settled();
        both("palette");
    }

    @Test
    public void confirmPopover() throws Exception {
        openGraph("Wood_loop");
        ed().draft().setDescription("Edited");
        ed().soft();
        ed().guard(() -> {
        });
        settled();
        both("confirm");
    }

    @Test
    public void inspectorOfEveryKind() throws Exception {
        openGraph("Wood_loop");
        ed().select("r");
        settled();
        both("insp_cond");
        ed().select("g");
        settled();
        both("insp_goto_block");
        ed().select("m");
        settled();
        both("insp_mine");
        ed().select("s");
        settled();
        both("insp_start");
        ed().select("z");
        settled();
        both("insp_stop");

        openGraph("Night_guard");
        ed().select("b");
        settled();
        both("insp_get");
        ed().select("w");
        settled();
        both("insp_wait_until");
        ed().select("g");
        settled();
        both("insp_goto_waypoint");

        for (String type : new String[]{"equip", "raw", "craft", "eat", "follow", "sub", "if", "wait"}) {
            String id = ed().draft().add(type, 1000, 400);
            ed().hard();
            ed().select(id);
            settled();
            both("insp_" + type);
        }
    }

    @Test
    public void everyConditionRow() throws Exception {
        openGraph("Night_guard");
        for (var spec : facade.routines.condSpecs()) {
            String id = ed().draft().add("if", 1000, 400);
            ed().draft().setCond(id, adris.altoclef.ui.gui.model.CondDraft.leaf(spec.kind(), adris.altoclef.ui.gui.model.CondDraft.blankRow(spec).args));
            ed().hard();
            ed().select(id);
            settled();
            both("cond_" + spec.kind());
            ed().draft().remove(id);
            ed().hard();
        }
    }

    @Test
    public void everyZoom() throws Exception {
        openGraph("Wood_loop");
        for (int i : new int[]{0, 1, 2, 4}) {
            ed().canvas().view().restore(ed().canvas().view().panX(), ed().canvas().view().panY(), i);
            ed().viewChanged();
            settled();
            both("zoom_" + Math.round(CanvasView.zoomOf(i) * 100));
        }
    }

    @Test
    public void twoHundredNodesAtHalf() throws Exception {
        facade.routines.seedStress();
        tab.onShown();
        openGraph("Stress");
        ed().canvas().view().restore(16, 60, 0);
        ed().viewChanged();
        settled();
        both("stress_50");
    }
}
