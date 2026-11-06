package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SettingsAccess.Kind;
import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.NumberField;
import adris.altoclef.ui.gui.kit.Popover;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Tile;
import adris.altoclef.ui.gui.kit.TileGrid;
import adris.altoclef.ui.gui.kit.Tooltip;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.NumberRules;
import adris.altoclef.ui.gui.model.SettingCategories;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// every setting as a tile. the first page is the 40 people touch (SettingsHome), Show all is everything grouped by
// category, the header search looks through all of it. a switch tile flips on click, anything else opens a popover
// that commits live. every change goes the #set road (McSettings.set: parse, userChanged, save) and then the one
// setting is read back and pushed into its tile, the list is never rebuilt for a single edit
public final class SettingsTab implements Tab {

    private static final int SHOW_ALL_H = 46;

    // the same order the old category dropdown had, roughly most used first. anything SettingCategories grows later
    // still shows up, after these
    private static final List<String> CATEGORY_ORDER = List.of(SettingCategories.GENERAL, SettingCategories.MOVEMENT,
            SettingCategories.MINING, SettingCategories.BUILDING, SettingCategories.ELYTRA, SettingCategories.RENDERING,
            SettingCategories.CHAT, SettingCategories.INTERFACE, SettingCategories.ALTO, SettingCategories.PATHING,
            SettingCategories.CACHE);

    enum Page { HOME, ALL }

    private final BotFacade facade;
    private final ShellContext ctx;
    private final Theme theme;
    private final TileGrid grid;
    private final SettingsBar bar;
    private final ShowAllRow showAll;
    // one Tile per setting for the life of the tab: the grid keeps focus on a tile across rebuilds by identity
    private final Map<String, Tile> tiles = new HashMap<>();
    private final Map<String, String> descriptions = new HashMap<>();
    private final Map<String, List<String>> oldNames = new HashMap<>();
    private final Map<String, SettingView> byName = new LinkedHashMap<>();

    private Page page = Page.HOME;
    private String query = "";
    private Popover open;

    public SettingsTab(Theme theme, BotFacade facade, ShellContext ctx) {
        this.theme = theme;
        this.facade = facade;
        this.ctx = ctx;
        // a popover closes itself when its tile scrolls or the grid resizes, TileGrid sees to that
        grid = new TileGrid(theme);
        bar = new SettingsBar(theme, () -> go(Page.HOME), this::resetAll);
        showAll = new ShowAllRow(theme, () -> go(Page.ALL));
        reload();
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

    @Override
    public void onSearch(String q) {
        String next = q == null ? "" : q;
        boolean changed = !next.trim().equals(query.trim());
        query = next;
        // an armed Reset all hidden behind a search should not still be armed when it comes back
        if (changed) {
            bar.setArmed(false);
        }
        render(changed);
    }

    @Override
    public void onShown() {
        reload();
    }

    // ----- state

    private void reload() {
        List<SettingView> got = null;
        try {
            got = facade.settings().all();
        } catch (Throwable t) {
            Safe.report("settings snapshot", t);
        }
        byName.clear();
        for (SettingView v : got == null ? List.<SettingView>of() : got) {
            byName.put(v.name(), v);
        }
        // a setting that went away (it never does, but a test fake can) takes its tile with it
        tiles.keySet().retainAll(byName.keySet());
        for (SettingView v : byName.values()) {
            bind(tile(v.name()), v);
        }
        render(false);
    }

    private void go(Page p) {
        page = p;
        bar.setArmed(false);
        render(true);
    }

    private void render(boolean toTop) {
        boolean searching = !query.isBlank();
        List<TileGrid.Section> sections;
        if (searching) {
            List<SettingView> hits = new ArrayList<>();
            for (SettingView v : byName.values()) {
                if (SettingWords.matches(query, v.name(), oldNamesOf(v.name()), sentence(v.name()))) {
                    hits.add(v);
                }
            }
            sections = byCategory(hits);
        } else if (page == Page.ALL) {
            sections = byCategory(new ArrayList<>(byName.values()));
        } else {
            sections = home();
        }
        bar.setChanged(changedCount());
        // results are their own page: a Back over them would flip the page underneath and change nothing on screen
        grid.setTop(page == Page.ALL && !searching ? bar : null, SettingsBar.HEIGHT);
        grid.setBottom(page == Page.HOME && !searching ? showAll : null, SHOW_ALL_H);
        grid.setSections(sections);
        grid.setEmptyText(emptyText());
        if (toTop) {
            grid.scrollToTop();
        }
    }

    private List<TileGrid.Section> home() {
        List<TileGrid.Section> out = new ArrayList<>();
        for (SettingsHome.Section s : SettingsHome.SECTIONS) {
            List<Tile> ts = new ArrayList<>();
            for (SettingsHome.Entry e : s.entries()) {
                if (byName.containsKey(e.name())) {
                    ts.add(tiles.get(e.name()));
                }
            }
            if (!ts.isEmpty()) {
                out.add(new TileGrid.Section(s.title(), ts));
            }
        }
        return out;
    }

    private List<TileGrid.Section> byCategory(List<SettingView> views) {
        Map<String, List<SettingView>> groups = new LinkedHashMap<>();
        for (String c : CATEGORY_ORDER) {
            groups.put(c, new ArrayList<>());
        }
        for (SettingView v : views) {
            groups.computeIfAbsent(SettingCategories.categoryOf(v.name()), c -> new ArrayList<>()).add(v);
        }
        List<TileGrid.Section> out = new ArrayList<>();
        for (Map.Entry<String, List<SettingView>> g : groups.entrySet()) {
            if (g.getValue().isEmpty()) {
                continue;
            }
            // by what the tile says, so the alto block reads A to Z without its prefix
            List<SettingView> sorted = new ArrayList<>(g.getValue());
            sorted.sort(Comparator.comparing((SettingView v) -> SettingWords.label(v.name()).toLowerCase(Locale.ROOT))
                    .thenComparing(SettingView::name));
            List<Tile> ts = new ArrayList<>(sorted.size());
            for (SettingView v : sorted) {
                ts.add(tiles.get(v.name()));
            }
            out.add(new TileGrid.Section(g.getKey(), ts));
        }
        return out;
    }

    private String emptyText() {
        if (byName.isEmpty()) {
            return "Settings are not available right now.";
        }
        return "No setting called \"" + query.trim() + "\". Try a shorter word.";
    }

    private int changedCount() {
        int n = 0;
        for (SettingView v : byName.values()) {
            if (v.modified()) {
                n++;
            }
        }
        return n;
    }

    // ----- words

    private String description(String name) {
        return descriptions.computeIfAbsent(name, n -> {
            try {
                String d = facade.settings().description(n);
                return d == null ? "" : d;
            } catch (Throwable t) {
                Safe.report("setting description", t);
                return "";
            }
        });
    }

    private List<String> oldNamesOf(String name) {
        return oldNames.computeIfAbsent(name, n -> {
            try {
                List<String> o = facade.settings().oldNames(n);
                return o == null ? List.of() : o;
            } catch (Throwable t) {
                Safe.report("setting old names", t);
                return List.of();
            }
        });
    }

    // the hand written sentence for the first page, the javadoc for the rest
    String sentence(String name) {
        SettingsHome.Entry e = SettingsHome.entry(name);
        return e != null ? e.sentence() : description(name);
    }

    private String keyName(int k) {
        try {
            return facade.keyNames().name(k);
        } catch (Throwable t) {
            Safe.report("key name", t);
            return null;
        }
    }

    // ----- tiles

    private Tile tile(String name) {
        return tiles.computeIfAbsent(name, n -> new Tile(theme).setOnClick(() -> clicked(n)));
    }

    private void bind(Tile t, SettingView v) {
        SettingsHome.Entry e = SettingsHome.entry(v.name());
        t.setName(SettingWords.label(v.name()));
        t.setIcon(e != null ? e.icon() : Icon.GEAR);
        if (v.kind() == Kind.BOOLEAN) {
            t.setSwitch(Boolean.parseBoolean(v.value().trim()));
        } else {
            t.setPick(SettingWords.shown(v, this::keyName));
        }
        t.setSwatch(v.kind() == Kind.COLOR ? rgbOf(v.value()) : null);
        t.setTooltip(new Tooltip(SettingWords.label(v.name()), sentence(v.name()),
                List.of("#set " + v.name(), "Default: " + SettingWords.shownDefault(v, this::keyName))));
    }

    private void clicked(String name) {
        SettingView v = byName.get(name);
        Tile t = tiles.get(name);
        if (v == null || t == null) {
            return;
        }
        if (v.kind() == Kind.BOOLEAN) {
            apply(name, String.valueOf(!Boolean.parseBoolean(v.value().trim())));
            return;
        }
        Popover p = popover(v, t);
        open = p;
        p.onClosed(() -> {
            if (open == p) {
                open = null;
            }
            refreshOne(name);
        }).open();
    }

    private Popover popover(SettingView v, Tile t) {
        String name = v.name();
        String title = SettingWords.label(name);
        return switch (v.kind()) {
            case INTEGER, LONG, FLOAT, DOUBLE -> Popover.number(ctx, t, title, format(v.kind()),
                    () -> SettingWords.number(current(name).value()), s -> commit(name, s),
                    SettingWords.number(v.defaultValue()), s -> check(name, s));
            case ENUM -> {
                List<String> constants = v.options();
                List<String> words = constants.stream().map(SettingWords::word).toList();
                yield Popover.choice(ctx, t, title, words, () -> indexOf(constants, current(name).value()), i -> {
                    if (i >= 0 && i < constants.size()) {
                        commit(name, constants.get(i));
                    }
                }, indexOf(constants, v.defaultValue()));
            }
            case COLOR -> Popover.color(ctx, t, title, () -> rgbOrBlack(current(name).value()),
                    c -> commit(name, NumberRules.formatRgb((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF)),
                    rgbOrBlack(v.defaultValue()));
            case KEY -> Popover.key(ctx, t, title, facade.keyNames(), () -> parseKey(current(name).value()),
                    k -> keyCaptured(name, k, parseKey(v.defaultValue())), parseKey(v.defaultValue()));
            // strings, and lists and maps in the parser's own syntax ("minecraft:stone,minecraft:dirt")
            default -> Popover.text(ctx, t, title, () -> current(name).value(), s -> commit(name, s), v.defaultValue(),
                    s -> check(name, s));
        };
    }

    private SettingView current(String name) {
        return byName.get(name);
    }

    private static NumberField.NumberFormat format(Kind k) {
        return switch (k) {
            case INTEGER -> NumberField.NumberFormat.INT;
            case LONG -> NumberField.NumberFormat.LONG;
            case FLOAT -> NumberField.NumberFormat.FLOAT;
            default -> NumberField.NumberFormat.DOUBLE;
        };
    }

    // ----- changes, all through the facade

    // a popover commits live (every -/+ and swatch), so it only speaks up when something went wrong. the tile already
    // shows the new value
    private void commit(String name, String text) {
        apply(name, text, true);
    }

    // a key the player just pressed is still held down: the shell has to know it is the open key now and swallow its
    // repeat, or it closes the panel. backspace unbinds, nothing is held then. every other way guiKey changes (back to
    // default, reset all, #set) reaches the shell through GuiScreen.syncPreferences, no swallowing wanted there.
    // the popover's own Back to default lands here too, by mouse, so the default skips it. a captured key that happens
    // to be the default is still fine: the shell swallows whatever key closed a popover
    private void keyCaptured(String name, int key, int def) {
        Result r = apply(name, String.valueOf(key), true);
        if (r.ok() && key >= 0 && key != def) {
            Safe.run("key rebound", () -> ctx.openKeyRebound(key));
        }
    }

    Result apply(String name, String text) {
        return apply(name, text, false);
    }

    // typing the default back in (or Back to default) is a reset, so settings.txt does not keep a line that says the
    // same as nothing
    private Result apply(String name, String text, boolean quiet) {
        SettingView v = byName.get(name);
        Result r;
        try {
            boolean toDefault = v != null && SettingWords.sameValue(v.kind(), text, v.defaultValue());
            r = toDefault ? facade.settings().reset(name) : facade.settings().set(name, text);
        } catch (Throwable t) {
            Safe.report("setting set", t);
            r = Result.fail("Could not change " + name + ".");
        }
        if (r == null) {
            r = Result.fail("Could not change " + name + ".");
        }
        // a popover already checked the text with the real parser, so a failure here is rare and always gets its toast
        if (!r.ok() || !quiet) {
            ctx.toast(r);
        }
        if (r.ok()) {
            refreshOne(name);
        }
        return r;
    }

    private String check(String name, String text) {
        try {
            return facade.settings().check(name, text);
        } catch (Throwable t) {
            Safe.report("setting check", t);
            return null;
        }
    }

    private void resetAll() {
        Result r;
        try {
            r = facade.settings().resetAll();
        } catch (Throwable t) {
            Safe.report("settings reset all", t);
            r = Result.fail("Could not reset the settings.");
        }
        if (r != null) {
            ctx.toast(r);
        }
        reload();
    }

    // re-read one setting and push it into its tile and the reset count
    private void refreshOne(String name) {
        SettingView v = null;
        try {
            v = facade.settings().get(name);
        } catch (Throwable t) {
            Safe.report("setting get", t);
        }
        if (v == null) {
            return;
        }
        byName.put(v.name(), v);
        bind(tile(v.name()), v);
        bar.setChanged(changedCount());
    }

    // ----- parsing the #set text forms

    private static Integer rgbOf(String s) {
        int[] rgb = NumberRules.parseRgb(s);
        return rgb == null ? null : (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
    }

    private static int rgbOrBlack(String s) {
        Integer c = rgbOf(s);
        return c == null ? 0 : c;
    }

    private static int indexOf(List<String> constants, String value) {
        String v = value == null ? "" : value.trim();
        for (int i = 0; i < constants.size(); i++) {
            if (constants.get(i).equalsIgnoreCase(v)) {
                return i;
            }
        }
        return -1;
    }

    private static int parseKey(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return -1;
        }
    }

    // ----- the Show all button under the first page, centred, chevron after the words like the mock

    static final class ShowAllRow extends Widget {
        private final Button button;

        ShowAllRow(Theme theme, Runnable onClick) {
            super(theme);
            button = new Button(theme, "Show all settings", Button.Kind.SECONDARY, Icon.CHEVRON_RIGHT, onClick) {
                @Override
                protected void drawContent(Canvas c, int col, float hv) {
                    // Button puts its icon first, a "go further" chevron reads after the words
                    float is = theme.px(Theme.ICON);
                    float gap = theme.px(6);
                    float tw = c.measure(text(), theme.label);
                    float cx = x + (w - tw - gap - is) / 2f;
                    c.text(text(), cx, baseline(c, theme.label, y, h), theme.label, col);
                    c.icon(Icon.CHEVRON_RIGHT, cx + tw + gap, y + (h - is) / 2f, is, col);
                }
            };
        }

        @Override
        protected void layout() {
            int bw = button.preferredWidth();
            int bh = theme.px(Theme.CONTROL_H);
            button.setBounds(x + (w - bw) / 2, y + theme.px(16), bw, bh);
        }

        @Override
        public List<Widget> children() {
            return List.of(button);
        }

        @Override
        public void draw(Canvas c, Frame f) {
            button.draw(c, f);
        }

        Button button() {
            return button;
        }
    }

    // ----- for tests

    TileGrid grid() {
        return grid;
    }

    SettingsBar bar() {
        return bar;
    }

    ShowAllRow showAll() {
        return showAll;
    }

    Page page() {
        return page;
    }

    Tile tileOf(String name) {
        return tiles.get(name);
    }

    Popover openPopover() {
        return open != null && open.isOpen() ? open : null;
    }
}
