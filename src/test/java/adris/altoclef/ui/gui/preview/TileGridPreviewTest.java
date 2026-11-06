package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.NumberField;
import adris.altoclef.ui.gui.kit.Popover;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Tile;
import adris.altoclef.ui.gui.kit.TileGrid;
import adris.altoclef.ui.gui.kit.Tooltip;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.tabs.Tabs;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// the kit's tile grid and popovers in the real shell, at the mock's 2x, so they can be held against the approved
// mock v4 shots (gui-mock/tools/cmp.py). the tab here is a stand in with the mock's first three sections; the real
// settings tab is built on this kit by the next piece of work
public class TileGridPreviewTest {

    private static final class GridTab implements Tab {
        final TileGrid grid;

        GridTab(TileGrid grid) {
            this.grid = grid;
        }

        @Override
        public String id() {
            return "settings";
        }

        @Override
        public String title() {
            return "Settings";
        }

        @Override
        public Icon icon() {
            return Icon.SETTINGS;
        }

        @Override
        public Widget content() {
            return grid;
        }

        @Override
        public boolean hasSearch() {
            return true;
        }

        @Override
        public String searchPlaceholder() {
            return "Search settings";
        }
    }

    private static Tile sw(Theme t, String name, Icon icon, boolean on) {
        return new Tile(t).setName(name).setIcon(icon).setSwitch(on);
    }

    private static Tile pick(Theme t, String name, Icon icon, String value) {
        return new Tile(t).setName(name).setIcon(icon).setPick(value);
    }

    private Tile minHealth;
    private Tile fastMode;
    private Tile colour;
    private Tile fieldStrategy;
    private String health = "12";

    private TileGrid build(Theme t, ShellContext ctx) {
        fastMode = sw(t, "Fast Mode", Icon.BOLT, true).setTooltip(new Tooltip("Fast Mode",
                "Moves like a fast player: diagonal jumps, swimming, boats and other quick tricks all switch on. Takes more risks.",
                List.of("#set fastMode", "Default: Off")));
        minHealth = pick(t, "Fast Mode Min Health", Icon.WARNING, health);
        minHealth.setOnClick(() -> Popover.number(ctx, minHealth, "Fast Mode Min Health", NumberField.NumberFormat.DOUBLE,
                () -> health, v -> health = v, "12", null).open());
        colour = pick(t, "Color Current Path", null, "Red").setSwatch(0xD9182B);
        colour.setOnClick(() -> Popover.color(ctx, colour, "Color Current Path", () -> 0xD9182B, v -> { }, 0xD9182B).open());
        fieldStrategy = pick(t, "Alto Force Field Strategy", Icon.RING, "Smart");
        fieldStrategy.setOnClick(() -> Popover.choice(ctx, fieldStrategy, "Alto Force Field Strategy",
                List.of("Off", "Fastest", "Smart", "Always"), () -> 2, v -> { }, 2).open());
        TileGrid g = new TileGrid(t);
        g.setSections(List.of(
                new TileGrid.Section("Travel", List.of(fastMode, sw(t, "Allow Boats", Icon.BOAT, true), minHealth,
                        sw(t, "Boat Pickup", Icon.BACKPACK, true), sw(t, "Elytra Auto Jump", Icon.LEVEL_UP, false),
                        sw(t, "Elytra Conserve Fireworks", Icon.CLOUD, false), sw(t, "Elytra Auto Swap", Icon.ROTATE_RIGHT, true),
                        sw(t, "Allow Swimming", Icon.WATER, false))),
                new TileGrid.Section("Movement", List.of(sw(t, "Allow Sprint", Icon.RUN, true), sw(t, "Allow Parkour", Icon.PARKOUR, true),
                        pick(t, "Max Fall Height No Water", Icon.LEVEL_DOWN, "4"), sw(t, "Free Look", Icon.PLAYER, true),
                        sw(t, "Allow Water Bucket Fall", Icon.WATER, true), sw(t, "Allow Ladder Clutch", Icon.LADDER, false),
                        sw(t, "Allow Parkour Place", Icon.BLOCK, false), sw(t, "Allow Diagonal Descend", Icon.LEVEL_DOWN, false))),
                new TileGrid.Section("Mining and building", List.of(sw(t, "Allow Break", Icon.PICKAXE, true),
                        sw(t, "Allow Place", Icon.BLOCK, true), sw(t, "Auto Tool", Icon.WRENCH, true),
                        sw(t, "Allow Inventory", Icon.BACKPACK, false), sw(t, "Legit Mine", Icon.EYE, false),
                        sw(t, "Allow Downward", Icon.LEVEL_DOWN, true), sw(t, "Mine Scan Dropped Items", Icon.GET, true),
                        sw(t, "Build In Layers", Icon.SCHEMATICS, false))),
                new TileGrid.Section("Combat and safety", List.of(sw(t, "Alto Mob Defense", Icon.ENTITY, true),
                        sw(t, "Alto Auto Eat", Icon.POWER, true), fieldStrategy, sw(t, "Alto Dodge Projectiles", Icon.MOVE, true))),
                new TileGrid.Section("Rendering", List.of(sw(t, "Render Path", Icon.EYE, true), sw(t, "Render Goal", Icon.PIN, true),
                        colour, pick(t, "Color Next Path", null, "Purple").setSwatch(0xC77DFF)))));
        return g;
    }

    private Preview preview(boolean dark) throws Exception {
        FakeFacade f = new FakeFacade();
        f.status.activity = new StatusAccess.Activity(true, "Mining iron_ore", "goal 120 64 -30", null);
        List<Tab> tabs = new ArrayList<>();
        Preview p = new Preview(3840, 2160, 2f, f, pv -> {
            for (Tab t : Tabs.create(pv.facade, pv.theme, pv.ctx)) {
                tabs.add(t.id().equals("settings") ? new GridTab(build(pv.theme, pv.ctx)) : t);
            }
            return tabs;
        });
        p.shell.setDarkMode(dark);
        p.shell.switchTab(tabs.size() - 1);
        p.tick();
        p.settle();
        p.advance(500);
        return p;
    }

    private static void clickTile(Preview p, Tile t) {
        p.click(t.x() + t.w() / 2f, t.y() + t.h() / 2f);
        p.hover(-100, -100);
        p.settle();
        p.advance(400);
    }

    @Test
    public void gridPopoversAndTooltipInBothThemes() throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            String theme = dark ? "dark" : "light";
            Preview p = preview(dark);
            p.savePanelCutout("kit/settings_grid_" + theme, 48);

            clickTile(p, minHealth);
            assertNotNull(p.shell.overlay());
            p.savePanelCutout("kit/settings_grid_pop_number_" + theme, 48);
            p.key(adris.altoclef.ui.gui.kit.Keys.ESCAPE);

            p.hover(fastMode.x() + fastMode.w() / 2f, fastMode.y() + fastMode.h() / 2f);
            p.settle();
            p.advance(700);
            assertTrue(p.shell.tooltips().showing(p.now) == fastMode);
            p.savePanelCutout("kit/settings_grid_tip_" + theme, 48);
            p.hover(-100, -100);

            if (dark) {
                // further down the page: scroll so they are on screen, then open
                TileGrid g = findGrid(p);
                g.scroller().snapTo(g.scroller().maxOffset());
                p.settle();
                p.advance(100);
                clickTile(p, colour);
                p.savePanelCutout("kit/settings_grid_pop_colour_dark", 48);
                p.key(adris.altoclef.ui.gui.kit.Keys.ESCAPE);
                clickTile(p, fieldStrategy);
                p.savePanelCutout("kit/settings_grid_pop_choice_dark", 48);
                p.key(adris.altoclef.ui.gui.kit.Keys.ESCAPE);
            }
        }
    }

    private static TileGrid findGrid(Preview p) {
        for (Widget w : p.shell.children()) {
            if (w instanceof TileGrid g) {
                return g;
            }
        }
        throw new AssertionError("no grid in the shell");
    }
}
