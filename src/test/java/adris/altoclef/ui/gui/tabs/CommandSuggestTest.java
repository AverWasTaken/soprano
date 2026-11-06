package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.SuggestList;
import adris.altoclef.ui.gui.kit.SuggestList.Entry;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.RoutineDraft;
import adris.altoclef.ui.gui.model.RoutineDraft.Kind;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// the commands tab's type-ahead: the list opens as you type, keys stay with the box, and everything that moves the box
// out from under the list takes the list down with it
public class CommandSuggestTest {

    private FakeFacade facade;
    private Preview p;
    private CommandsTab tab;

    @Before
    public void setUp() throws Exception {
        facade = new FakeFacade();
        facade.routines.seed();
        facade.routines.seedGraphs();
        open(false);
    }

    private void open(boolean secondTab) throws Exception {
        CommandsTab[] holder = new CommandsTab[1];
        p = new Preview(1920, 1200, 1f, facade, pv -> {
            holder[0] = new CommandsTab(pv.theme, pv.facade, pv.ctx);
            return secondTab ? List.of(holder[0], new BlankTab(pv.theme)) : List.of(holder[0]);
        });
        tab = holder[0];
        tab.onShown();
        settle();
    }

    private void settle() {
        p.tick();
        p.settle();
    }

    // Diamond_gear is get first, equip second
    private StepRow row(int i) {
        return tab.editor().rows().get(i);
    }

    private StepField equipBox() {
        return row(1).itemField();
    }

    private SuggestList typeInto(StepField f, String s) {
        p.shell.focus(f);
        settle();
        f.setText("");
        p.type(s);
        settle();
        return f.suggest();
    }

    private RoutineDraft.Row draftRow(int i) {
        return tab.editor().draft().rows.get(i);
    }

    private static List<String> ids(List<Entry> rows) {
        List<String> out = new ArrayList<>();
        for (Entry e : rows) {
            out.add(e.id());
        }
        return out;
    }

    // ----- ranking

    @Test
    public void prefixBeatsAWordInTheMiddle() {
        List<Entry> got = new IdLists(facade).items().find("iron", 50);
        // inside a tier it's the facade's order, the get tab's grid does the same
        assertEquals("iron_pickaxe", got.get(0).id());
        // raw_iron only has iron after an underscore, so it comes after every iron_ something
        assertEquals("raw_iron", got.get(got.size() - 1).id());
        assertEquals("Iron Pickaxe", got.get(0).label());
        assertEquals("minecraft:iron_pickaxe", got.get(0).icon());
    }

    @Test
    public void spacesMeanUnderscoresAndTheLimitHolds() {
        assertEquals("iron_ingot", new IdLists(facade).items().find("iron ingot", 50).get(0).id());
        assertEquals(3, new IdLists(facade).items().find("iron", 3).size());
    }

    @Test
    public void blocksMobsAndPlayersHaveTheirOwnLists() {
        IdLists l = new IdLists(facade);
        assertTrue(ids(l.blocks().find("log", 50)).containsAll(List.of("oak_log", "birch_log", "spruce_log")));
        assertEquals(List.of("zombie", "zombie_villager", "zombified_piglin"), ids(l.mobs().find("zomb", 50)));
        assertEquals("minecraft:zombie_spawn_egg", l.mobs().find("zombie", 1).get(0).icon());
        // you are on your own tab list, but you are not who you'd follow
        assertEquals(List.of("Steve"), ids(l.players().find("st", 50)));
        assertTrue(l.players().find("aver", 50).isEmpty());
        assertTrue(l.players().find("st", 50).get(0).face());
        assertSame(null, l.forKind("COORD"));
        assertTrue(IdLists.NONE.items().find("iron", 50).isEmpty());
    }

    // ----- opening

    @Test
    public void typingOpensItUnderTheBox() {
        StepField f = equipBox();
        SuggestList s = typeInto(f, "iron");
        assertTrue(s.isOpen());
        assertSame(s, p.shell.floating());
        assertEquals("iron_pickaxe", s.rows().get(0).id());
        assertEquals(-1, s.hot());
        // under the box, or over it when the panel has no room under it. never on top of the box, never off the panel
        assertTrue(s.y() >= f.y() + f.h() || s.y() + s.h() <= f.y());
        assertTrue(s.y() >= p.shell.contentY());
        assertTrue(s.y() + s.h() <= p.shell.y() + p.shell.h());
        assertTrue(s.h() <= 8 * p.theme.px(Theme.ROW_H) + 2 * p.theme.px(4));
    }

    @Test
    public void focusAloneOrAnEmptyBoxShowsNothing() {
        StepField f = equipBox();
        p.shell.focus(f);
        settle();
        // it says diamond already, clicking in to fix it shouldn't throw a list over the next step
        assertFalse(f.suggest().isOpen());
        f.setText("");
        p.type("i");
        assertTrue(f.suggest().isOpen());
        p.key(Keys.BACKSPACE);
        assertFalse(f.suggest().isOpen());
        assertNull(p.shell.floating());
    }

    @Test
    public void exactlyTheOnlyMatchNeedsNoList() {
        SuggestList s = typeInto(equipBox(), "diamond_swor");
        assertTrue(s.isOpen());
        p.type("d");
        assertFalse(s.isOpen());
    }

    @Test
    public void downOnAClosedListBringsItBackLit() {
        StepField f = equipBox();
        SuggestList s = typeInto(f, "iron");
        p.key(Keys.ESCAPE);
        assertFalse(s.isOpen());
        p.key(Keys.DOWN);
        assertTrue(s.isOpen());
        assertEquals(0, s.hot());
    }

    // ----- keys

    @Test
    public void arrowsMoveAndEnterPicks() {
        StepField f = equipBox();
        SuggestList s = typeInto(f, "iron");
        p.key(Keys.DOWN);
        assertEquals(0, s.hot());
        p.key(Keys.DOWN);
        assertEquals(1, s.hot());
        p.key(Keys.UP);
        p.key(Keys.UP);
        assertEquals(-1, s.hot());
        p.key(Keys.DOWN);
        p.key(Keys.ENTER);
        assertEquals("iron_pickaxe", f.text());
        assertEquals("iron_pickaxe", draftRow(1).get("item"));
        assertFalse(s.isOpen());
        assertSame(f, p.shell.focusedWidget());
    }

    @Test
    public void tabPicksTheLitRowAndKeepsFocus() {
        StepField f = equipBox();
        SuggestList s = typeInto(f, "iron");
        String second = s.rows().get(1).id();
        p.key(Keys.DOWN);
        p.key(Keys.DOWN);
        p.key(Keys.TAB);
        assertEquals(second, f.text());
        assertSame(f, p.shell.focusedWidget());
        assertFalse(s.isOpen());
    }

    @Test
    public void tabWithNothingLitIsStillTab() {
        StepField f = equipBox();
        SuggestList s = typeInto(f, "iron");
        p.key(Keys.TAB);
        assertFalse(s.isOpen());
        assertTrue(p.shell.focusedWidget() != f);
        assertEquals("iron", f.text());
    }

    @Test
    public void escClosesTheListAndLeavesTheBoxAlone() {
        StepField f = equipBox();
        SuggestList s = typeInto(f, "iron");
        p.key(Keys.DOWN);
        p.key(Keys.ESCAPE);
        assertFalse(s.isOpen());
        assertSame(f, p.shell.focusedWidget());
        assertEquals("iron", f.text());
        // the second esc puts the old text back, and that is not typing: no list comes back with it
        p.key(Keys.ESCAPE);
        assertEquals("", f.text());
        p.key(Keys.ESCAPE);
        assertFalse(s.isOpen());
        assertNull(p.shell.floating());
    }

    @Test
    public void escAfterAPickDoesntBringTheListBack() {
        StepField f = equipBox();
        SuggestList s = typeInto(f, "iron");
        p.key(Keys.DOWN);
        p.key(Keys.ENTER);
        p.key(Keys.ESCAPE);
        assertFalse(s.isOpen());
    }

    // ----- get's add box

    @Test
    public void enterWithARowLitAddsThePick() {
        StepField add = row(0).addField();
        SuggestList s = typeInto(add, "iron_s");
        assertEquals("iron_sword", s.rows().get(0).id());
        p.key(Keys.DOWN);
        p.key(Keys.ENTER);
        settle();
        List<String> got = new ArrayList<>();
        for (RoutineDraft.Item it : draftRow(0).items) {
            got.add(it.id);
        }
        assertTrue(got.contains("iron_sword"));
        assertFalse(got.contains("iron_s"));
        assertEquals("", add.text());
    }

    @Test
    public void enterWithNothingLitAddsWhatYouTyped() {
        StepField add = row(0).addField();
        typeInto(add, "iron_s");
        p.key(Keys.ENTER);
        settle();
        List<String> got = new ArrayList<>();
        for (RoutineDraft.Item it : draftRow(0).items) {
            got.add(it.id);
        }
        // the same as before there was a list: what's in the box goes in, the validator gets to say it's wrong
        assertTrue(got.contains("iron_s"));
        assertFalse(got.contains("iron_sword"));
    }

    // ----- the pointer

    @Test
    public void clickingARowPicksIt() {
        StepField f = equipBox();
        SuggestList s = typeInto(f, "iron");
        String second = s.rows().get(1).id();
        int rowH = p.theme.px(Theme.ROW_H);
        p.click(s.x() + s.w() / 2f, s.y() + p.theme.px(4) + rowH * 1.5f);
        settle();
        assertEquals(second, f.text());
        assertSame(f, p.shell.focusedWidget());
        assertFalse(s.isOpen());
    }

    @Test
    public void aPointerRestingWhereTheListOpensLightsNothing() {
        StepField f = equipBox();
        SuggestList s = typeInto(f, "iron");
        // find out where it goes, then start over with the pointer parked right there before it opens
        float px = s.x() + 20f;
        float py = s.y() + s.h() / 2f;
        p.key(Keys.ESCAPE);
        p.key(Keys.ESCAPE);
        p.hover(px, py);
        f.setText("");
        p.type("iron");
        settle();
        settle();
        assertTrue(s.isOpen());
        assertTrue(s.contains(px, py));
        assertEquals(-1, s.hot());
        p.hover(px, py + 3f);
        assertTrue(s.hot() >= 0);
    }

    @Test
    public void aPointerThatWasAlreadyThereLightsNothing() {
        StepField f = equipBox();
        SuggestList s = typeInto(f, "iron");
        // a real move onto the list lights the row under it
        p.hover(s.x() + 20f, s.y() + s.h() / 2f);
        assertTrue(s.hot() >= 0);
        // another letter is a new list under a pointer that didn't move: the frames that follow replay the same
        // position, and none of them gets to light a row for enter to pick
        p.type("_");
        settle();
        settle();
        assertTrue(s.isOpen());
        assertTrue(s.contains(p.mouseX, p.mouseY));
        assertEquals(-1, s.hot());
    }

    @Test
    public void theWheelScrollsTheListOrClosesIt() {
        StepField f = equipBox();
        SuggestList s = typeInto(f, "iron");
        assertTrue(s.rows().size() > 8);
        p.scroll(s.x() + 10f, s.y() + 10f, -1f);
        assertTrue(s.isOpen());
        // the wheel over the steps would slide the box out from under its list
        p.scroll(tab.editor().x() + 10f, tab.editor().y() + tab.editor().h() - 10f, -1f);
        assertFalse(s.isOpen());
        assertNull(p.shell.floating());
    }

    // ----- things that take the box away

    @Test
    public void aPooledRowBoundToAnotherStepClosesIt() {
        StepRow r = row(1);
        SuggestList s = typeInto(r.itemField(), "iron");
        assertTrue(s.isOpen());
        r.bind(RoutineDraft.blankRow(Kind.EQUIP), 1, true);
        assertFalse(s.isOpen());
        assertNull(p.shell.floating());
        settle();
    }

    @Test
    public void anotherRoutineClosesIt() {
        SuggestList s = typeInto(equipBox(), "iron");
        tab.select(facade.routines.list.stream().filter(x -> x.id().equals("Nether_trip")).findFirst().orElseThrow());
        settle();
        assertFalse(s.isOpen());
    }

    @Test
    public void movingOrResizingThePanelClosesIt() {
        SuggestList s = typeInto(equipBox(), "iron");
        p.shell.setViewport(1600, 1000);
        assertFalse(s.isOpen());
        assertNull(p.shell.floating());
    }

    @Test
    public void switchingTabsClosesIt() throws Exception {
        open(true);
        SuggestList s = typeInto(equipBox(), "iron");
        assertTrue(s.isOpen());
        p.shell.switchTab(1);
        assertFalse(s.isOpen());
        assertNull(p.shell.floating());
    }

    @Test
    public void aPopoverTakesItsPlace() {
        SuggestList s = typeInto(equipBox(), "iron");
        p.shell.openOverlay(new BlankTab(p.theme).content());
        assertFalse(s.isOpen());
        assertNull(p.shell.floating());
    }

    // ----- the node inspector

    private NodeEditor ed() {
        return tab.nodeEditor();
    }

    private void openGraph(String id) {
        tab.select(tab.list().items().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow());
        settle();
    }

    private static void suggestBoxes(Widget w, List<StepField> out) {
        if (w instanceof StepField f && f.suggest() != null) {
            out.add(f);
        }
        for (Widget k : w.children()) {
            suggestBoxes(k, out);
        }
    }

    private StepField inspectorBox() {
        List<StepField> boxes = new ArrayList<>();
        suggestBoxes(ed().inspector(), boxes);
        assertFalse("the inspector has a box with a list", boxes.isEmpty());
        return boxes.get(0);
    }

    @Test
    public void theMineNodesBlockBoxSuggestsBlocks() {
        openGraph("Wood_loop");
        ed().select("m");
        settle();
        StepField f = inspectorBox();
        SuggestList s = typeInto(f, "log");
        assertTrue(s.isOpen());
        assertTrue(ids(s.rows()).contains("birch_log"));
        String first = s.rows().get(0).id();
        p.key(Keys.DOWN);
        p.key(Keys.ENTER);
        settle();
        assertEquals(first, f.text());
        assertEquals(first, ed().draft().node("m").params().get("block"));
    }

    @Test
    public void theConditionsItemBoxSuggestsItems() {
        openGraph("Wood_loop");
        ed().select("r");
        settle();
        SuggestList s = typeInto(inspectorBox(), "oak");
        assertTrue(s.isOpen());
        assertTrue(ids(s.rows()).contains("oak_planks"));
    }

    @Test
    public void deleteInTheBoxIsTheBoxsDelete() {
        openGraph("Wood_loop");
        // a real press on the card arms the canvas's delete
        p.click(ed().canvas().view().screenX(ed().draft().node("m").x() + 60), ed().canvas().view().screenY(ed().draft().node("m").y() + 14));
        settle();
        StepField f = inspectorBox();
        SuggestList s = typeInto(f, "log");
        assertTrue(s.isOpen());
        p.key(Keys.LEFT);
        p.key(Keys.DELETE);
        settle();
        assertNotNull(ed().draft().node("m"));
        assertEquals("lo", f.text());
    }

    private static final class BlankTab implements Tab {
        private final Widget content;

        BlankTab(Theme theme) {
            content = new Widget(theme) {
                @Override
                public void draw(adris.altoclef.ui.gui.kit.Canvas c, adris.altoclef.ui.gui.kit.Frame f) {
                }
            };
        }

        @Override
        public String id() {
            return "blank";
        }

        @Override
        public String title() {
            return "Blank";
        }

        @Override
        public Icon icon() {
            return Icon.GET;
        }

        @Override
        public Widget content() {
            return content;
        }
    }
}
