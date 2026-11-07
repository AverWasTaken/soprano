package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Popover;
import adris.altoclef.ui.gui.kit.Tile;
import adris.altoclef.ui.gui.kit.TileGrid;
import adris.altoclef.ui.gui.kit.Tooltip;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.SettingCategories;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// the settings tab through a real shell and the fake facade: what a click, a popover or a search actually sends down
// the #set road. facade.actions is the record of every set and reset that would have run
public class SettingsTabTest {

    private FakeFacade f;
    private Preview p;
    private SettingsTab tab;

    @Before
    public void setUp() throws Exception {
        f = new FakeFacade();
        p = new Preview(f, pv -> {
            tab = new SettingsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(tab);
        });
        p.settle();
    }

    private void click(Widget w) {
        p.click(w.x() + w.w() / 2f, w.y() + w.h() / 2f);
        p.settle();
    }

    // narrows the grid to one setting and returns its tile, on screen
    private Tile show(String query, String name) {
        tab.onSearch(query);
        p.settle();
        p.settle();
        Tile t = tab.tileOf(name);
        assertNotNull(name, t);
        assertTrue(name + " is not in the results", tab.grid().tiles().contains(t));
        return t;
    }

    private Popover open(Tile t) {
        // the lower sections are below the fold at this size
        tab.grid().scrollToTile(t);
        p.advance(400);
        p.settle();
        p.settle();
        click(t);
        Popover pop = tab.openPopover();
        assertNotNull("no popover for " + t.name(), pop);
        assertSame(pop, p.shell.overlay());
        return pop;
    }

    // the wheel all the way down, then the Show all button under the last section
    private void showAll() {
        TileGrid g = tab.grid();
        for (int i = 0; i < 60; i++) {
            p.shell.scroll(g.x() + 40, g.y() + 40, -1);
        }
        p.advance(600);
        p.settle();
        click(tab.showAll().button());
    }

    private void selectAllAndType(String text) {
        p.shell.keyDown(Keys.A, Keys.MOD_CTRL);
        p.shell.keyUp(Keys.A);
        p.type(text);
    }

    private SettingView view(String name) {
        return f.settings.get(name);
    }

    private List<String> titles() {
        List<String> out = new ArrayList<>();
        for (TileGrid.Section s : tab.grid().sections()) {
            out.add(s.title());
        }
        return out;
    }

    // ----- the first page

    @Test
    public void firstPageIsTheFortyTilesInTheMocksSixSections() {
        assertEquals(SettingsTab.Page.HOME, tab.page());
        assertEquals(List.of("Travel", "Movement", "Mining and building", "Combat and safety", "Rendering", "Chat and HUD"), titles());
        List<String> names = new ArrayList<>();
        for (SettingsHome.Section s : SettingsHome.SECTIONS) {
            for (SettingsHome.Entry e : s.entries()) {
                names.add(e.name());
            }
        }
        assertEquals(40, names.size());
        assertEquals(40, new HashSet<>(names).size());
        assertEquals(40, tab.grid().tiles().size());
        for (int i = 0; i < names.size(); i++) {
            assertSame(tab.tileOf(names.get(i)), tab.grid().tiles().get(i));
        }
        // fast mode and boats show what they really are, the mock lit them for show
        assertEquals("Off", tab.tileOf("fastMode").valueText());
        assertEquals("Off", tab.tileOf("allowBoats").valueText());
        assertEquals("12", tab.tileOf("fastModeMinHealth").valueText());
        assertEquals("Smart", tab.tileOf("altoForceFieldStrategy").valueText());
        assertEquals("Red", tab.tileOf("colorCurrentPath").valueText());
        assertEquals("#", tab.tileOf("prefix").valueText());
        assertEquals("1", tab.tileOf("altoHudScale").valueText());
        assertNotNull(tab.showAll().button());
        assertTrue(tab.grid().children().contains(tab.showAll()));
    }

    @Test
    public void namesAreTheFieldNameSplitWithoutAlto() {
        assertEquals("Fast Mode", tab.tileOf("fastMode").name());
        assertEquals("Mob Defense", tab.tileOf("altoMobDefense").name());
        assertEquals("Max Fall Height No Water", tab.tileOf("maxFallHeightNoWater").name());
        assertEquals("Primary Timeout MS", tab.tileOf("primaryTimeoutMS").name());
    }

    @Test
    public void tooltipsAreTheSentenceThenSetNameAndDefault() {
        Tooltip t = tab.tileOf("fastMode").tooltip();
        assertEquals("Fast Mode", t.title());
        assertEquals(SettingsHome.entry("fastMode").sentence(), t.body());
        assertEquals(List.of("#set fastMode", "Default: Off"), t.footer());
        // off the first page it is the generated description
        Tooltip d = tab.tileOf("chatDebug").tooltip();
        assertEquals("Print debug chat.", d.body());
        assertEquals(List.of("#set chatDebug", "Default: Off"), d.footer());
        assertEquals(List.of("#set altoMobDefense", "Default: On"), tab.tileOf("altoMobDefense").tooltip().footer());
        assertEquals("Default: Purple", tab.tileOf("colorNextPath").tooltip().footer().get(1));
    }

    // ----- switches

    @Test
    public void clickingASwitchTileSavesItAndClickingAgainResetsIt() {
        Tile t = tab.tileOf("fastMode");
        click(t);
        assertEquals("set fastMode true", f.actions.get(f.actions.size() - 1));
        assertTrue(t.on());
        assertEquals("true", view("fastMode").value());
        // back to its default is a reset, so settings.txt does not keep a line for it
        click(t);
        assertEquals("set reset fastMode", f.actions.get(f.actions.size() - 1));
        assertFalse(t.on());
    }

    @Test
    public void spaceOnAFocusedSwitchTileSavesItToo() {
        Tile t = tab.tileOf("allowBoats");
        p.shell.focus(t);
        p.key(Keys.SPACE);
        p.settle();
        assertEquals("set allowBoats true", f.actions.get(f.actions.size() - 1));
        assertTrue(t.on());
        // the same tile in a search shows the same state, it is the same tile
        assertSame(t, show("boats", "allowBoats"));
        assertTrue(t.on());
    }

    // ----- popovers, one per kind, every commit through set

    @Test
    public void numberPopoverCommitsThroughSetAndTheTileFollows() {
        Tile t = tab.tileOf("fastModeMinHealth");
        open(t);
        selectAllAndType("8");
        p.key(Keys.ENTER);
        p.settle();
        assertNull(p.shell.overlay());
        assertEquals("set fastModeMinHealth 8", f.actions.get(f.actions.size() - 1));
        assertEquals("8", t.valueText());
    }

    @Test
    public void numberPopoverRefusesSomethingThatIsNotANumber() {
        Tile t = tab.tileOf("mobAvoidanceRadius");
        int before = f.actions.size();
        open(t);
        // a whole number box never lets the point in, a lone minus is what gets through and does not parse
        selectAllAndType("-");
        p.key(Keys.ENTER);
        p.settle();
        // stays open saying why, nothing saved, the tile keeps its value
        assertNotNull(tab.openPopover());
        assertEquals(before, f.actions.size());
        assertEquals("8", t.valueText());
        p.key(Keys.ESCAPE);
        p.settle();
        assertNull(p.shell.overlay());
        assertEquals(before, f.actions.size());
    }

    @Test
    public void numberPopoverCommitsOnAnOutsideClick() {
        Tile t = tab.tileOf("maxFallHeightNoWater");
        open(t);
        selectAllAndType("6");
        // somewhere in the header, not a tile
        p.click(p.shell.contentX() + 40, p.shell.y() + 20);
        p.settle();
        assertNull(p.shell.overlay());
        assertEquals("set maxFallHeightNoWater 6", f.actions.get(f.actions.size() - 1));
        assertEquals("6", t.valueText());
    }

    @Test
    public void choicePopoverCommitsTheConstantName() {
        Tile t = tab.tileOf("altoForceFieldStrategy");
        open(t);
        // Smart is the last one, up is Delay
        p.key(Keys.UP);
        p.key(Keys.ENTER);
        p.settle();
        assertEquals("set altoForceFieldStrategy DELAY", f.actions.get(f.actions.size() - 1));
        assertEquals("Delay", t.valueText());
    }

    @Test
    public void colourPopoverBackToDefaultIsAReset() {
        Tile t = tab.tileOf("colorNextPath");
        assertEquals("Green", t.valueText());
        Popover pop = open(t);
        Button reset = (Button) pop.children().get(1);
        click(reset);
        assertEquals("set reset colorNextPath", f.actions.get(f.actions.size() - 1));
        assertEquals("Purple", t.valueText());
        // live: the card stays open on the new value
        assertSame(pop, p.shell.overlay());
    }

    @Test
    public void colourSwatchCommitsAsRgb() {
        Tile t = tab.tileOf("colorCurrentPath");
        Popover pop = open(t);
        // the fourth dot of the first row is green, same spot PopoverTest clicks, at this scale
        float dx = pop.x() + p.theme.px(12) + p.theme.px(6) + p.theme.px(232 - 24 - 12 - 32) + p.theme.px(16);
        float dy = pop.y() + p.theme.px(12) + p.theme.px(16) + p.theme.px(10) + p.theme.px(2) + p.theme.px(16);
        p.click(dx, dy);
        p.settle();
        assertEquals("set colorCurrentPath 48,209,88", f.actions.get(f.actions.size() - 1));
        assertEquals("Green", t.valueText());
    }

    @Test
    public void textPopoverCommitsAString() {
        Tile t = tab.tileOf("prefix");
        open(t);
        selectAllAndType("!");
        p.key(Keys.ENTER);
        p.settle();
        assertEquals("set prefix !", f.actions.get(f.actions.size() - 1));
        assertEquals("!", t.valueText());
    }

    @Test
    public void listPopoverRunsTheRealParserFirst() {
        Tile t = show("blocks to avoid", "blocksToAvoid");
        assertEquals("None", t.valueText());
        int before = f.actions.size();
        open(t);
        selectAllAndType("!nope");
        p.key(Keys.ENTER);
        p.settle();
        // the fake parser does not know that block: open, nothing saved
        assertNotNull(tab.openPopover());
        assertEquals(before, f.actions.size());
        selectAllAndType("minecraft:stone,minecraft:dirt");
        p.key(Keys.ENTER);
        p.settle();
        assertNull(p.shell.overlay());
        assertEquals("set blocksToAvoid minecraft:stone,minecraft:dirt", f.actions.get(f.actions.size() - 1));
        assertEquals("2 blocks", t.valueText());
    }

    @Test
    public void keyPopoverRebindsAndTellsTheShell() {
        Tile t = show("gui key", "guiKey");
        assertEquals("Right Shift", t.valueText());
        open(t);
        // the key cap has focus: enter starts the capture, the next key is the bind
        p.shell.keyDown(Keys.ENTER, 0);
        p.shell.keyUp(Keys.ENTER);
        p.shell.keyDown(Keys.A, 0);
        p.shell.keyUp(Keys.A);
        p.settle();
        assertTrue(f.actions.contains("set guiKey " + Keys.A));
        assertEquals(Keys.A, p.shell.openKey());
        assertEquals(f.keyNames().name(Keys.A), t.valueText());
    }

    @Test
    public void closingAPopoverDoesNotSaveAnything() {
        int before = f.actions.size();
        open(tab.tileOf("altoHudScale"));
        p.key(Keys.ESCAPE);
        p.settle();
        assertNull(p.shell.overlay());
        assertEquals(before, f.actions.size());
    }

    // ----- show all and reset all

    @Test
    public void showAllListsEverySettingByCategoryAndBackReturns() {
        showAll();
        assertEquals(SettingsTab.Page.ALL, tab.page());
        assertEquals(f.settings.map.size(), tab.grid().tiles().size());
        assertEquals(f.settings.map.size(), new HashSet<>(tab.grid().tiles()).size());
        assertTrue(tab.grid().children().contains(tab.bar()));
        assertFalse(tab.grid().children().contains(tab.showAll()));
        for (String title : titles()) {
            assertTrue(title, SettingCategories.names().contains(title));
        }
        // the alto block reads by what the tile says: Auto Eat before Mob Defense
        List<Tile> tasks = null;
        for (TileGrid.Section s : tab.grid().sections()) {
            if (s.title().equals(SettingCategories.ALTO)) {
                tasks = s.tiles();
            }
        }
        assertNotNull(tasks);
        assertTrue(tasks.indexOf(tab.tileOf("altoAutoEat")) < tasks.indexOf(tab.tileOf("altoMobDefense")));
        click(tab.bar().back());
        assertEquals(SettingsTab.Page.HOME, tab.page());
        assertEquals(40, tab.grid().tiles().size());
    }

    @Test
    public void searchingOnShowAllDropsTheBarUntilTheSearchIsCleared() {
        showAll();
        assertTrue(tab.grid().children().contains(tab.bar()));
        tab.onSearch("parkour");
        p.settle();
        assertFalse(tab.grid().children().contains(tab.bar()));
        tab.onSearch("");
        p.settle();
        assertTrue(tab.grid().children().contains(tab.bar()));
        assertEquals(SettingsTab.Page.ALL, tab.page());
    }

    @Test
    public void resetAllAsksFirstThenResetsEveryChangedSetting() {
        click(tab.tileOf("fastMode"));
        showAll();
        assertTrue(tab.bar().resetAll().enabled());
        click(tab.bar().resetAll());
        assertTrue(tab.bar().armed());
        assertFalse(f.actions.contains("set reset all"));
        // cancel backs out
        click(tab.bar().cancel());
        assertFalse(tab.bar().armed());
        assertFalse(f.actions.contains("set reset all"));
        click(tab.bar().resetAll());
        click(tab.bar().confirm());
        assertTrue(f.actions.contains("set reset all"));
        for (SettingView v : f.settings.all()) {
            assertFalse(v.name(), v.modified());
        }
        assertFalse(tab.tileOf("fastMode").on());
        assertEquals("3", tab.tileOf("maxFallHeightNoWater").valueText());
        // nothing left to reset
        assertFalse(tab.bar().resetAll().enabled());
    }

    // ----- search

    @Test
    public void searchFindsByLabelRawNameAndTooltip() {
        // the name on the tile
        show("mob defense", "altoMobDefense");
        // the real one
        show("altomobdefense", "altoMobDefense");
        // a word only the tooltip sentence has
        show("creepers", "altoMobDefense");
        // a word only the javadoc description has, for a setting off the first page
        show("debug chat", "chatDebug");
        // results come grouped like Show all, from both pages
        tab.onSearch("allow");
        p.settle();
        Set<Tile> hits = new HashSet<>(tab.grid().tiles());
        assertTrue(hits.contains(tab.tileOf("allowBreak")));
        assertTrue(hits.contains(tab.tileOf("allowParkourPlace")));
        assertFalse(hits.contains(tab.tileOf("renderPath")));
        assertFalse(tab.grid().children().contains(tab.showAll()));
    }

    @Test
    public void aRenamedSettingIsFoundByItsOldName() {
        show("experimentalmovement", "fastMode");
        show("experimental min health", "fastModeMinHealth");
    }

    @Test
    public void searchThroughTheHeaderBoxAndNoResults() {
        p.shell.search().clearAndNotify();
        Widget box = p.shell.search();
        p.click(box.x() + box.w() / 2f, box.y() + box.h() / 2f);
        p.type("parkour");
        p.settle();
        assertTrue(tab.grid().tiles().contains(tab.tileOf("allowParkour")));
        p.type("zzzz");
        p.settle();
        assertTrue(tab.grid().tiles().isEmpty());
        // clearing it brings the first page back
        p.shell.search().clearAndNotify();
        p.settle();
        assertEquals(40, tab.grid().tiles().size());
    }

    @Test
    public void tilesAreKeptAcrossRebuilds() {
        Tile t = tab.tileOf("allowBreak");
        showAll();
        assertSame(t, tab.tileOf("allowBreak"));
        assertTrue(tab.grid().tiles().contains(t));
        tab.onShown();
        p.settle();
        assertSame(t, tab.tileOf("allowBreak"));
    }
}
