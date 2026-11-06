package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Graph;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphRunStatus;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Routine;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunState;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunStatus;
import adris.altoclef.ui.gui.facade.RoutinesAccess.StoreState;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.CanvasView;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.NodeCard;
import adris.altoclef.ui.gui.kit.NodeWire;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NodeEditorTest {

    private FakeFacade facade;
    private Preview p;
    private CommandsTab tab;
    private NodeSession session;

    @Before
    public void setUp() throws Exception {
        facade = new FakeFacade();
        for (String s : new String[]{"oak_log", "bread", "iron_ingot"}) {
            facade.items.gettable.add(new ItemEntry(s, ItemEntry.titleCase(s), "minecraft:" + s, 64));
        }
        facade.routines.seed();
        session = new NodeSession();
    }

    private void open() throws Exception {
        CommandsTab[] holder = new CommandsTab[1];
        p = new Preview(1920, 1200, 1f, facade, pv -> {
            holder[0] = new CommandsTab(pv.theme, pv.facade, pv.ctx, session);
            return List.of(holder[0]);
        });
        tab = holder[0];
        tab.onShown();
        settle();
    }

    private void settle() {
        p.tick();
        p.settle();
    }

    private NodeEditor ed() {
        return tab.nodeEditor();
    }

    private void click(Widget w) {
        p.click(w.x() + w.w() / 2f, w.y() + w.h() / 2f);
        settle();
    }

    private void openGraph(String id) throws Exception {
        open();
        tab.select(tab.list().items().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow());
        settle();
    }

    private NodeCard card(String id) {
        return ed().cards().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private float screenX(String id, int dx) {
        return ed().canvas().view().screenX(ed().draft().node(id).x() + dx);
    }

    private float screenY(String id, int dy) {
        return ed().canvas().view().screenY(ed().draft().node(id).y() + dy);
    }

    // the middle of a card's header, where nothing but the card is
    private void clickCard(String id) {
        p.click(screenX(id, 60), screenY(id, 14));
        settle();
    }

    private NodeWire wire(String from, String to) {
        return ed().wires().stream().filter(w -> w.from().equals(from) && w.to().equals(to)).findFirst().orElseThrow();
    }

    private void toNodes() {
        p.click(tab.editor().viewSwitch().x() + tab.editor().viewSwitch().w() - 10f, tab.editor().viewSwitch().y() + 10f);
        settle();
    }

    private NodeConfirm confirm() {
        return p.shell.overlay() instanceof NodeConfirm c ? c : null;
    }

    private void choose(String label) {
        for (Button b : confirm().buttons()) {
            if (b.text().equals(label)) {
                click(b);
                return;
            }
        }
        throw new AssertionError("no button called " + label);
    }

    // ----- the setting

    @Test
    public void withTheSettingOffNothingNewExists() throws Exception {
        facade.routines.seedGraphs();
        facade.routines.graphsOn = false;
        open();
        // the fake lists its files with the setting off, like the real one, so this is the tab doing the hiding
        assertFalse(facade.routines.listGraphs().isEmpty());
        assertEquals(3, tab.list().items().size());
        assertNull(tab.nodeEditor());
        assertEquals(3, tab.content().children().size());
        assertEquals(6, tab.editor().children().size());
        assertNull(tab.editor().viewSwitch());
        assertFalse(tab.isGraph("Wood_loop"));
        assertEquals(new java.util.ArrayList<String>(), facade.actions.stream().filter(a -> a.contains("Graph")).toList());
    }

    @Test
    public void theSettingGoingOffMidEditGoesBackToTheList() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        assertTrue(tab.nodesView());
        facade.routines.graphsOn = false;
        settle();
        assertFalse(tab.nodesView());
        assertEquals(3, tab.list().items().size());
    }

    // ----- the list

    @Test
    public void graphsJoinTheListAndAGraphWinsAClash() throws Exception {
        facade.routines.seedGraphs();
        facade.routines.graphFiles.put("Base_trip", new Graph("Base_trip", "Base trip", "", List.of(), List.of()));
        open();
        List<Routine> rows = tab.list().items();
        assertEquals(1, rows.stream().filter(r -> r.id().equals("Base_trip")).count());
        assertTrue(tab.isGraph("Base_trip"));
        assertEquals(2 + 5, rows.size());
    }

    @Test
    public void aBrokenFileIsARedRowThatOpensReadOnly() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Bad_file");
        assertTrue(tab.nodesView());
        assertEquals("line 3: expected a comma", tab.fileError("Bad_file"));
        assertTrue(ed().readOnly());
        assertFalse(ed().saveButton().enabled());
        assertFalse(ed().runButton().enabled());
        assertTrue(ed().messageText().startsWith("Line 3"));
    }

    @Test
    public void theRowCountsRedNodes() throws Exception {
        facade.routines.seedGraphs();
        open();
        assertTrue(tab.problems("Half_built") > 0);
        assertEquals(0, tab.problems("Wood_loop"));
    }

    @Test
    public void aRowRunsAGraphAsAGraph() throws Exception {
        facade.routines.seedGraphs();
        open();
        tab.run("Wood_loop");
        assertTrue(facade.actions.contains("runGraph Wood_loop"));
        tab.run("Base_trip");
        assertTrue(facade.actions.contains("run Base_trip"));
    }

    // ----- opening and switching

    @Test
    public void openingAGraphGoesToNodesAndSoDoesTheSwitch() throws Exception {
        facade.routines.seedGraphs();
        open();
        assertFalse(tab.nodesView());
        toNodes();
        assertTrue(tab.nodesView());
        assertEquals("Diamond gear", ed().draft().name());
        assertEquals(1, tab.content().children().size());
    }

    @Test
    public void aListRoutineInNodesWritesNothingUntilItsSaved() throws Exception {
        facade.routines.seedGraphs();
        open();
        toNodes();
        assertFalse(ed().dirty());
        assertFalse(ed().saveButton().enabled());
        assertFalse(facade.routines.graphFiles.containsKey("Diamond_gear"));
        // a clean run is just the list routine running
        click(ed().runButton());
        assertTrue(facade.actions.contains("run Diamond_gear"));
        assertFalse(facade.actions.stream().anyMatch(a -> a.startsWith("saveGraph")));
    }

    @Test
    public void anEditOnAListRoutineSaysWhatSavingDoesAndSavingUpgrades() throws Exception {
        facade.routines.seedGraphs();
        open();
        toNodes();
        ed().draft().setDescription("Edited");
        ed().soft();
        settle();
        assertEquals("Saving turns this into a node routine", ed().messageText());
        click(ed().saveButton());
        assertTrue(facade.routines.graphFiles.containsKey("Diamond_gear"));
        assertTrue(facade.routines.list.stream().noneMatch(r -> r.id().equals("Diamond_gear")));
        assertTrue(ed().draft().file);
        assertFalse(ed().dirty());
    }

    @Test
    public void runWithEditsSavesFirst() throws Exception {
        facade.routines.seedGraphs();
        open();
        toNodes();
        ed().draft().setDescription("Edited");
        ed().soft();
        settle();
        click(ed().runButton());
        assertTrue(facade.actions.contains("saveGraph Diamond gear"));
        assertTrue(facade.actions.contains("runGraph Diamond_gear"));
    }

    @Test
    public void listIsOffForAGraphFileAndSaysWhy() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        assertEquals(0, ed().viewSwitch().off());
        p.click(ed().viewSwitch().x() + 8f, ed().viewSwitch().y() + 10f);
        settle();
        assertTrue(tab.nodesView());
        assertEquals("Saved as a node routine, the list view can't edit it", ed().tipAt(ed().viewSwitch().x() + 8f, ed().viewSwitch().y() + 10f));
    }

    @Test
    public void listFromACleanRoutineJustGoesBack() throws Exception {
        facade.routines.seedGraphs();
        open();
        toNodes();
        p.click(ed().viewSwitch().x() + 8f, ed().viewSwitch().y() + 10f);
        settle();
        assertFalse(tab.nodesView());
        assertEquals("Diamond_gear", tab.selectedId());
    }

    @Test
    public void listWithEditsAsksAndDontSaveThrowsThemAway() throws Exception {
        facade.routines.seedGraphs();
        open();
        toNodes();
        ed().draft().setDescription("Edited");
        ed().soft();
        settle();
        p.click(ed().viewSwitch().x() + 8f, ed().viewSwitch().y() + 10f);
        settle();
        assertTrue(tab.nodesView());
        assertNotNull(confirm());
        choose("Don't save");
        assertFalse(tab.nodesView());
        assertFalse(facade.routines.graphFiles.containsKey("Diamond_gear"));
    }

    @Test
    public void pickingAnotherRoutineWithEditsAsks() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        ed().draft().setDescription("Edited");
        ed().soft();
        settle();
        int night = ed().pickerIds().indexOf("Night_guard");
        ed().pickForTest(night);
        settle();
        assertNotNull(confirm());
        assertEquals("Wood_loop", ed().draft().id);
        choose("Cancel");
        assertEquals("Wood_loop", ed().draft().id);
        ed().pickForTest(night);
        settle();
        choose("Save");
        assertEquals("Night_guard", ed().draft().id);
        assertEquals("Edited", facade.routines.graphFiles.get("Wood_loop").description());
    }

    @Test
    public void aSaveThatFailsStaysAndSays() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        ed().draft().setDescription("Edited");
        ed().soft();
        settle();
        facade.routines.failNextSave = true;
        click(ed().saveButton());
        assertTrue(ed().dirty());
        assertEquals("Couldn't write the file", ed().messageText());
    }

    @Test
    public void theSearchFiltersThePicker() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        tab.onSearch("night");
        settle();
        assertEquals(List.of("Night_guard"), ed().pickerIds().subList(0, 1));
        assertTrue(ed().pickerIds().contains(null));
    }

    @Test
    public void aNewOneIsBlankAndDeletingAFileAsks() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        ed().pickForTest(ed().pickerIds().size() - 1);
        settle();
        assertNotNull(confirm());
        choose("Delete");
        assertFalse(facade.routines.graphFiles.containsKey("Wood_loop"));
        assertNotEquals("Wood_loop", tab.selectedId());
    }

    // ----- the canvas

    @Test
    public void selectingShowsTheInspectorAndAFloatKeepsItsPress() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        clickCard("g");
        assertEquals("g", ed().selectedCard());
        NodeInspector insp = ed().inspector();
        assertTrue(ed().children().contains(insp));
        // a press on the inspector's blank bit doesn't pan what's behind it
        float pan = ed().canvas().view().panX();
        float bx = insp.x() + insp.w() / 2f;
        float by = insp.y() + insp.h() - 60f;
        p.press(bx, by);
        p.drag(bx - 80, by);
        p.release(bx - 80, by);
        settle();
        assertEquals(pan, ed().canvas().view().panX(), 0.01f);
        // and the wheel doesn't zoom it either
        int zoom = ed().canvas().view().zoomIndex();
        p.scroll(bx, by, 1);
        assertEquals(zoom, ed().canvas().view().zoomIndex());
    }

    @Test
    public void deleteAfterAPressOnAFloatDoesntEatTheNode() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        clickCard("g");
        NodeInspector insp = ed().inspector();
        p.click(insp.x() + insp.w() / 2f, insp.y() + insp.h() - 60f);
        settle();
        p.key(Keys.DELETE);
        assertNotNull(ed().draft().node("g"));
        // a press on the card itself and it does go
        clickCard("g");
        p.key(Keys.DELETE);
        settle();
        assertNull(ed().draft().node("g"));
    }

    @Test
    public void startSaysNoToDelete() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        clickCard("s");
        p.key(Keys.DELETE);
        settle();
        assertNotNull(ed().draft().node("s"));
        assertFalse(ed().dirty());
    }

    @Test
    public void escOutOfABoxThenDeselectsThenTheShellGetsIt() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        clickCard("m");
        Widget field = firstField();
        click(field);
        assertTrue(field.isFocused());
        p.key(Keys.ESCAPE);
        settle();
        assertFalse(field.isFocused());
        assertTrue("the canvas has the keyboard back", ed().canvas().isFocused());
        p.key(Keys.ESCAPE);
        settle();
        assertNull(ed().selectedCard());
    }

    private Widget firstField() {
        for (Widget w : ed().inspector().bodyWidgets()) {
            if (w instanceof StepField) {
                return w;
            }
        }
        throw new AssertionError("no field in the inspector");
    }

    @Test
    public void typingInABoxKeepsItFocusedAndUpdatesTheCard() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        clickCard("m");
        StepField block = (StepField) firstField();
        click(block);
        p.type("x");
        settle();
        assertTrue(block.isFocused());
        assertEquals("oak_logx", ed().draft().node("m").params().get("block"));
        assertEquals("8 oak_logx", card("m").summary());
        assertEquals("8 oak_logx", ed().inspector().summaryLine());
        assertTrue(ed().dirty());
    }

    @Test
    public void aWaitsSecondsKeepTheirPointTypedAndLoaded() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Night_guard");
        String id = ed().draft().add("wait", 900, 300);
        ed().hard();
        clickCard(id);
        StepField secs = null;
        for (Widget w : ed().inspector().bodyWidgets()) {
            if (w instanceof StepField f && "5".equals(f.text())) {
                secs = f;
            }
        }
        assertNotNull(secs);
        click(secs);
        p.key(Keys.END);
        p.key(Keys.BACKSPACE);
        p.type("0.5");
        settle();
        assertEquals("0.5", ed().draft().node(id).params().get("secs"));
        // and it comes back from a save the same way
        ed().nameField().setText("Night guard");
        click(ed().saveButton());
        clickCard(id);
        boolean seen = false;
        for (Widget w : ed().inspector().bodyWidgets()) {
            seen |= w instanceof StepField f && "0.5".equals(f.text());
        }
        assertTrue(seen);
    }

    @Test
    public void aWireDroppedOnACardAnywhereConnects() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        // g's failed port, onto the middle of the stop card and not its dot
        p.press(screenX("g", 176), screenY("g", 30 + 16 + 22));
        p.drag(screenX("z", 60), screenY("z", 40));
        p.release(screenX("z", 60), screenY("z", 40));
        settle();
        assertEquals("z", ed().draft().edges().stream().filter(e -> e.from().equals("g") && e.port().equals("failed")).findFirst().orElseThrow().to());
    }

    @Test
    public void aWireLetGoOnNothingOpensTheMenuAndPickingWiresIt() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        float tx = screenX("g", 300);
        float ty = screenY("g", 400);
        p.press(screenX("g", 176), screenY("g", 30 + 16 + 22));
        p.drag(tx, ty);
        p.release(tx, ty);
        settle();
        assertTrue(p.shell.overlay() instanceof AddNodeMenu);
        AddNodeMenu menu = (AddNodeMenu) p.shell.overlay();
        int before = ed().draft().nodes().size();
        p.click(menu.x() + menu.w() / 2f, menu.rowY("Eat"));
        settle();
        assertEquals(before + 1, ed().draft().nodes().size());
        String added = ed().selectedCard();
        assertEquals("eat", ed().draft().node(added).type());
        assertEquals(added, ed().draft().edges().stream().filter(e -> e.from().equals("g") && e.port().equals("failed")).findFirst().orElseThrow().to());
    }

    @Test
    public void aSelectedWireGoesWithItsChipOrDelete() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        String key = wire("g", "m").key();
        ed().wireClicked(key);
        settle();
        assertEquals(key, ed().selectedWire());
        assertFalse(ed().children().contains(ed().inspector()));
        ed().deleteSelected();
        settle();
        assertTrue(ed().wires().stream().noneMatch(w -> w.key().equals(key)));
        assertNull(ed().selectedWire());
    }

    @Test
    public void aNewListIsHandedOverSoTheCanvasNotices() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        List<NodeCard> before = ed().cards();
        settle();
        assertTrue(before == ed().cards());
        ed().move("g", 400, 48);
        assertFalse(before == ed().cards());
    }

    @Test
    public void wheelOverTheCanvasZoomsAndFitNeverGoesPastOne() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        CanvasView v = ed().canvas().view();
        float fitted = v.zoom();
        assertTrue(fitted <= 1f);
        p.scroll(ed().canvas().x() + 500f, ed().canvas().y() + 400f, 1);
        assertTrue(v.zoom() > fitted);
        p.scroll(ed().canvas().x() + 500f, ed().canvas().y() + 400f, 1);
        p.scroll(ed().canvas().x() + 500f, ed().canvas().y() + 400f, 1);
        click(ed().fitButton());
        assertEquals(fitted, v.zoom(), 0.001f);
    }

    // ----- the palette

    @Test
    public void thePaletteOpensOnAnEmptyOneAndFoldsOnOneWithNodes() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        assertFalse(ed().palette().open());
        ed().pickForTest(ed().pickerIds().indexOf(null));
        settle();
        assertTrue(ed().palette().open());
    }

    @Test
    public void aClickAddsAfterTheSelectedNodeOnItsFreePort() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        clickCard("m");
        // m.ok goes back to the repeat, and failed isn't "next", so there's no port for it: the middle of the view
        NodeEntries.Entry eat = entry("Eat");
        int before = ed().draft().edges().size();
        ed().clicked(eat);
        assertEquals(before, ed().draft().edges().size());
        String eatId = ed().selectedCard();
        // this one has room, so the next node hangs off it
        ed().clicked(entry("Stop"));
        String stopId = ed().selectedCard();
        assertEquals(stopId, ed().draft().edges().stream().filter(e -> e.from().equals(eatId) && e.port().equals("ok")).findFirst().orElseThrow().to());
        assertEquals(ed().draft().node(eatId).x() + 176 + 48, ed().draft().node(stopId).x());
    }

    @Test
    public void addingAfterAvoidsWhatsAlreadyThere() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        ed().wireClicked("g:ok:m:in");
        ed().deleteSelected();
        settle();
        clickCard("g");
        ed().clicked(entry("Eat"));
        String id = ed().selectedCard();
        // m sits exactly where it would have landed
        assertNotEquals(ed().draft().node("m").y(), ed().draft().node(id).y());
    }

    @Test
    public void aConditionBecomesAnIfWithItPreset() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        ed().clicked(entry("Inventory full"));
        var n = ed().draft().node(ed().selectedCard());
        assertEquals("if", n.type());
        assertEquals("free_slots", n.cond().kind());
        assertEquals("0", n.cond().args().get("value"));
    }

    @Test
    public void theFoldButtonFolds() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        click(ed().palette().foldButton());
        assertTrue(ed().palette().open());
        click(ed().palette().foldButton());
        assertFalse(ed().palette().open());
    }

    @Test
    public void aDragFromThePaletteDropsWhereYouLetGo() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        click(ed().palette().foldButton());
        NodePalette pal = ed().palette();
        float sx = pal.x() + 40f;
        float sy = pal.rowY("Eat");
        float ex = ed().canvas().x() + 700f;
        float ey = ed().canvas().y() + 500f;
        int before = ed().draft().nodes().size();
        p.press(sx, sy);
        p.drag(ex, ey);
        p.release(ex, ey);
        settle();
        assertEquals(before + 1, ed().draft().nodes().size());
        assertEquals("eat", ed().draft().node(ed().selectedCard()).type());
    }

    @Test
    public void aDragLetGoOverAFloatDoesNothing() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        click(ed().palette().foldButton());
        NodePalette pal = ed().palette();
        int before = ed().draft().nodes().size();
        p.press(pal.x() + 40f, pal.rowY("Eat"));
        p.drag(pal.x() + 60f, pal.y() + 5f);
        p.release(pal.x() + 60f, pal.y() + 5f);
        settle();
        assertEquals(before, ed().draft().nodes().size());
    }

    private NodeEntries.Entry entry(String label) {
        return NodeEntries.lines(facade.routines).stream().filter(l -> l.entry() != null && l.entry().label().equals(label)).findFirst().orElseThrow().entry();
    }

    // ----- validation

    @Test
    public void aBadNodeIsRedWithItsWhyAndTheLineJumpsToIt() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Half_built");
        assertEquals(NodeCard.State.BAD, card("m").state());
        assertNotNull(card("m").why());
        assertFalse(ed().runButton().enabled());
        // saving a half built one is fine, only Run minds
        ed().draft().setDescription("Later");
        ed().soft();
        settle();
        assertTrue(ed().saveButton().enabled());
        assertTrue(ed().messageText().contains("fixing before it can run"));
        assertTrue(ed().runBlocked().contains("fixing before it can run"));
        // the line is drawn by now, a click on it selects the first bad one
        p.click(messageX(), messageY());
        settle();
        assertEquals("m", ed().selectedCard());
    }

    private float messageX() {
        return ed().messageRect()[0] + 4f;
    }

    private float messageY() {
        return ed().messageRect()[1] + 4f;
    }

    // ----- the run

    private void woodLoopRunning() {
        facade.routines.status = new RunStatus("Wood_loop", "Wood loop", 3, 5, "", RunState.RUNNING, null);
        facade.routines.graphStatus = new GraphRunStatus("Wood_loop", RunState.RUNNING, null, "m", null, List.of("s", "r", "g", "m"),
                Map.of("s", "OK", "g", "OK", "r", "YES"), Map.of("r", 3), Set.of("s:out:r:in", "r:body:g:in", "g:ok:m:in", "m:ok:r:again"));
    }

    @Test
    public void aRunShowsOnTheCardsAndTheWires() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        woodLoopRunning();
        settle();
        assertEquals(NodeCard.State.ACTIVE, card("m").state());
        assertEquals(NodeCard.State.DONE, card("g").state());
        assertEquals("3/20", card("r").badgeText());
        assertEquals(NodeCard.State.IDLE, card("z").state());
        assertTrue(wire("g", "m").ran());
        assertFalse(wire("g", "m").dim());
        assertTrue(wire("r", "z").dim());
        assertEquals("Stop", ed().runButton().text());
    }

    @Test
    public void anEditEndsTheHighlight() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        woodLoopRunning();
        settle();
        ed().draft().setDescription("Edited");
        ed().soft();
        settle();
        assertEquals(NodeCard.State.IDLE, card("m").state());
        assertNull(card("r").badgeText());
    }

    @Test
    public void somebodyElsesRunDoesntLightThisOne() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Night_guard");
        woodLoopRunning();
        settle();
        for (NodeCard c : ed().cards()) {
            assertEquals(NodeCard.State.IDLE, c.state());
        }
    }

    @Test
    public void aFailedRunMarksWhereItStopped() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        facade.routines.status = new RunStatus("Wood_loop", "Wood loop", 3, 5, "", RunState.FAILED, "no path");
        facade.routines.graphStatus = new GraphRunStatus("Wood_loop", RunState.FAILED, "no path", null, "g", List.of("s", "r", "g"),
                Map.of("s", "OK", "g", "FAILED", "r", "YES"), Map.of("r", 1), Set.of("s:out:r:in", "r:body:g:in"));
        settle();
        assertEquals(NodeCard.State.FAILED, card("g").state());
        assertEquals("Failed: no path", ed().messageText());
    }

    @Test
    public void aListRunHighlightsTheChainByStepNumber() throws Exception {
        facade.routines.seedGraphs();
        open();
        toNodes();
        String first = ed().draft().chain().get(0);
        String second = ed().draft().chain().get(1);
        facade.routines.status = new RunStatus("Diamond_gear", "Diamond gear", 2, 2, "", RunState.RUNNING, null);
        settle();
        assertEquals(NodeCard.State.DONE, card(first).state());
        assertEquals(NodeCard.State.ACTIVE, card(second).state());
        assertTrue(wire(first, second).ran());
    }

    @Test
    public void theRunStopsWithStop() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        click(ed().runButton());
        assertTrue(facade.actions.contains("runGraph Wood_loop"));
        settle();
        assertEquals("Stop", ed().runButton().text());
        click(ed().runButton());
        assertTrue(facade.actions.contains("stop"));
    }

    // ----- changes from elsewhere

    @Test
    public void aHandEditShowsUnderACleanDraftOnly() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        Graph g = facade.routines.graphFiles.get("Wood_loop");
        facade.routines.graphFiles.put("Wood_loop", new Graph(g.id(), g.name(), "Hand edit", g.nodes(), g.edges()));
        for (int i = 0; i < 25; i++) {
            p.tick();
        }
        assertEquals("Hand edit", ed().draft().description());
        ed().draft().setDescription("Mine");
        ed().soft();
        facade.routines.graphFiles.put("Wood_loop", new Graph(g.id(), g.name(), "Hand edit 2", g.nodes(), g.edges()));
        for (int i = 0; i < 25; i++) {
            p.tick();
        }
        assertEquals("Mine", ed().draft().description());
    }

    @Test
    public void aBrokenStoreLocksIt() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        facade.routines.store = new StoreState(false, "bad json");
        settle();
        assertTrue(ed().readOnly());
        assertFalse(ed().children().contains(ed().palette()));
        assertEquals("Editing is off", ed().messageText());
    }

    @Test
    public void aDraftFollowsItsFileGoingBadAndComingBack() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        Graph good = facade.routines.graphFiles.remove("Wood_loop");
        facade.routines.badFiles.put("Wood_loop", "line 1: nope");
        for (int i = 0; i < 25; i++) {
            p.tick();
        }
        assertNotNull(ed().draft().badFile);
        assertTrue(ed().readOnly());
        facade.routines.badFiles.remove("Wood_loop");
        facade.routines.graphFiles.put("Wood_loop", good);
        for (int i = 0; i < 25; i++) {
            p.tick();
        }
        assertNull(ed().draft().badFile);
        assertFalse(ed().readOnly());
    }

    @Test
    public void editsSurviveTheirFileGoingBad() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        ed().draft().setDescription("Mine");
        ed().soft();
        facade.routines.graphFiles.remove("Wood_loop");
        facade.routines.badFiles.put("Wood_loop", "line 1: nope");
        for (int i = 0; i < 25; i++) {
            p.tick();
        }
        assertEquals("Mine", ed().draft().description());
    }

    @Test
    public void lockedWithEditsStillAsksBeforeDroppingThem() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        ed().draft().setDescription("Mine");
        ed().soft();
        facade.routines.store = new StoreState(false, "bad json");
        settle();
        ed().pickForTest(ed().pickerIds().indexOf("Night_guard"));
        settle();
        assertNotNull(confirm());
        assertEquals(2, confirm().buttons().size());
        assertEquals("Wood_loop", ed().draft().id);
    }

    @Test
    public void aRowsRedCountFollowsASave() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        assertEquals(0, tab.problems("Wood_loop"));
        ed().draft().setParam("m", "block", "");
        ed().soft();
        settle();
        click(ed().saveButton());
        assertTrue(tab.problems("Wood_loop") > 0);
        ed().draft().setParam("m", "block", "oak_log");
        ed().soft();
        settle();
        click(ed().saveButton());
        assertEquals(0, tab.problems("Wood_loop"));
    }

    @Test
    public void aRowsRedCountFollowsAHandEditThatKeepsTheName() throws Exception {
        facade.routines.seedGraphs();
        open();
        assertEquals(0, tab.problems("Wood_loop"));
        Graph g = facade.routines.graphFiles.get("Wood_loop");
        List<adris.altoclef.ui.gui.facade.RoutinesAccess.GraphNode> broken = new java.util.ArrayList<>(g.nodes());
        broken.replaceAll(n -> n.id().equals("m") ? new adris.altoclef.ui.gui.facade.RoutinesAccess.GraphNode("m", "mine", n.x(), n.y(), Map.of("n", "8"), null) : n);
        facade.routines.graphFiles.put("Wood_loop", new Graph(g.id(), g.name(), g.description(), broken, g.edges()));
        for (int i = 0; i < 110; i++) {
            p.tick();
        }
        assertTrue(tab.problems("Wood_loop") > 0);
    }

    @Test
    public void aPressOnTheHeadRowDoesntLeaveDeleteArmed() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        clickCard("g");
        click(ed().nameField());
        assertTrue(ed().nameField().isFocused());
        p.key(Keys.ESCAPE);
        settle();
        assertTrue(ed().canvas().isFocused());
        p.key(Keys.DELETE);
        settle();
        assertNotNull(ed().draft().node("g"));
    }

    @Test
    public void aRunningRoutineSaysStopEvenWithEdits() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        woodLoopRunning();
        settle();
        ed().draft().setDescription("Edited");
        ed().soft();
        settle();
        assertEquals("Stop", ed().runButton().text());
        click(ed().runButton());
        assertTrue(facade.actions.contains("stop"));
        assertFalse(facade.actions.stream().anyMatch(a -> a.startsWith("saveGraph")));
    }

    @Test
    public void aPresetsLabelFollowsItsValue() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        ed().clicked(entry("Inventory full"));
        settle();
        int presetAt = facade.routines.condSpecs().size();
        adris.altoclef.ui.gui.kit.Dropdown kind = null;
        StepField value = null;
        for (Widget w : ed().inspector().bodyWidgets()) {
            if (kind == null && w instanceof adris.altoclef.ui.gui.kit.Dropdown d) {
                kind = d;
            }
            if (w instanceof StepField f && "0".equals(f.text())) {
                value = f;
            }
        }
        assertEquals(presetAt, kind.selected());
        click(value);
        p.key(Keys.END);
        p.type("3");
        settle();
        assertTrue(value.isFocused());
        assertNotEquals(presetAt, kind.selected());
    }

    @Test
    public void aRoutinePickerFindsItsIdInAnyCase() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        String id = ed().draft().add("sub", 1000, 1000);
        ed().draft().setParam(id, "routine", "night_GUARD");
        ed().hard();
        ed().select(id);
        settle();
        adris.altoclef.ui.gui.kit.Dropdown picker = null;
        for (Widget w : ed().inspector().bodyWidgets()) {
            if (w instanceof adris.altoclef.ui.gui.kit.Dropdown d) {
                picker = d;
            }
        }
        int expected = 0;
        List<Routine> all = tab.all();
        int k = 0;
        for (Routine r : all) {
            if (r.id().equalsIgnoreCase("Wood_loop")) {
                continue;
            }
            if (r.id().equals("Night_guard")) {
                expected = k;
            }
            k++;
        }
        assertEquals(expected, picker.selected());
    }

    @Test
    public void listEditsOutliveAGraphFileTurningUpWithTheirId() throws Exception {
        facade.routines.seedGraphs();
        open();
        p.click(tab.editor().descField().x() + 10, tab.editor().descField().y() + 10);
        p.type("half done");
        settle();
        assertTrue(tab.editor().dirty());
        Graph g = facade.routines.graphFiles.get("Wood_loop");
        facade.routines.graphFiles.put("Diamond_gear", new Graph("Diamond_gear", "Diamond gear", "", g.nodes(), g.edges()));
        tab.onShown();
        settle();
        assertFalse(tab.nodesView());
        assertTrue(tab.editor().dirty());
    }

    // ----- the rebuild

    @Test
    public void aFileThatWentWhileTheGuiWasShutIsntAGhost() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        facade.routines.graphFiles.remove("Wood_loop");
        open();
        assertNotEquals("Wood_loop", tab.selectedId());
        assertFalse(tab.nodesView());
    }

    @Test
    public void theDraftAndTheViewSurviveARebuild() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        ed().draft().setDescription("Unsaved");
        ed().soft();
        settle();
        clickCard("g");
        ed().canvas().zoomStep(-1);
        ed().canvas().view().panBy(50, 20);
        ed().viewChanged();
        float pan = ed().canvas().view().panX();
        int zoom = ed().canvas().view().zoomIndex();

        open();
        assertTrue(tab.nodesView());
        assertEquals("Unsaved", ed().draft().description());
        assertEquals("Unsaved", ed().descField().text());
        assertEquals("g", ed().selectedCard());
        assertEquals(zoom, ed().canvas().view().zoomIndex());
        assertEquals(pan, ed().canvas().view().panX(), 0.01f);
    }

    @Test
    public void aFreshSessionStartsClean() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        session = new NodeSession();
        open();
        assertFalse(tab.nodesView());
        assertNull(tab.nodeEditor());
    }

    // ----- the inspector's boxes

    @Test
    public void everyNodeTypeBuildsAnInspector() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        for (var spec : facade.routines.nodeSpecs()) {
            if (spec.inputs().isEmpty()) {
                continue;
            }
            String id = ed().draft().add(spec.type(), 1000, 1000);
            ed().select(id);
            p.settle();
            assertEquals(id, ed().inspector().nodeId());
        }
    }

    @Test
    public void aConditionTooDeepIsReadOnlyAndNotFlattened() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        var leaf = new adris.altoclef.ui.gui.facade.RoutinesAccess.CondTree("leaf", "health", Map.of("op", ">=", "value", "1"), List.of());
        var inner = new adris.altoclef.ui.gui.facade.RoutinesAccess.CondTree("any", null, Map.of(), List.of(leaf, leaf));
        var deep = new adris.altoclef.ui.gui.facade.RoutinesAccess.CondTree("all", null, Map.of(), List.of(leaf, inner));
        ed().draft().setCond("r", deep);
        ed().hard();
        clickCard("r");
        settle();
        assertEquals(deep, ed().draft().node("r").cond());
        assertTrue(ed().inspector().bodyWidgets().stream().anyMatch(w -> w instanceof Button b && "Replace".equals(b.text())));
    }

    // the shell focuses whatever's under a click by its bounds, so a box half under the head can't be up for that
    @Test
    public void aHalfScrolledBoxIsntClickable() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        var row = new adris.altoclef.ui.gui.facade.RoutinesAccess.CondTree("leaf", "has_item", Map.of("item", "oak_log", "op", ">=", "value", "1"), List.of());
        ed().draft().setCond("r", new adris.altoclef.ui.gui.facade.RoutinesAccess.CondTree("all", null, Map.of(), List.of(row, row, row, row, row, row, row, row)));
        ed().hard();
        clickCard("r");
        Widget box = ed().inspector().bodyBox();
        p.scroll(box.x() + box.w() / 2f, box.y() + box.h() / 2f, -1);
        p.advance(500);
        settle();
        boolean clipped = false;
        for (Widget w : ed().inspector().bodyWidgets()) {
            boolean whole = w.y() >= box.y() && w.y() + w.h() <= box.y() + box.h();
            clipped |= !whole;
            if (!whole) {
                assertFalse(box.children().contains(w));
            }
        }
        assertTrue("eight rows have to overflow this box or this proves nothing", clipped);
    }

    @Test
    public void listWhileLockedWithEditsOffersNoSave() throws Exception {
        facade.routines.seedGraphs();
        open();
        toNodes();
        ed().draft().setDescription("Edited");
        ed().soft();
        facade.routines.store = new StoreState(false, "bad json");
        settle();
        p.click(ed().viewSwitch().x() + 8f, ed().viewSwitch().y() + 10f);
        settle();
        assertNotNull(confirm());
        assertEquals(2, confirm().buttons().size());
    }

    @Test
    public void anUnwiredFailedPortIsExplained() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Wood_loop");
        clickCard("g");
        assertTrue(ed().inspector().hasNote("Nothing on failed, so a failure stops the whole run"));
    }

    @Test
    public void aGetNodeAddsItemsThroughItsBasket() throws Exception {
        facade.routines.seedGraphs();
        openGraph("Night_guard");
        clickCard("b");
        StepField add = null;
        for (Widget w : ed().inspector().bodyWidgets()) {
            if (w instanceof StepField f) {
                add = f;
            }
        }
        assertNotNull(add);
        click(add);
        p.type("stick");
        p.key(Keys.ENTER);
        settle();
        assertTrue(add.isFocused());
        assertEquals("", add.text());
        assertTrue(ed().draft().node("b").params().get("line").contains("stick"));
    }
}
