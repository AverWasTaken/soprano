package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.facade.WorldAccess.WaypointEntry;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Dropdown;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Segmented;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.Coords;

import java.util.List;

// saved places, nearest first. adding is one row at the top, deleting asks inside the row it happens in: no modal,
// and anything that is not "yes" (esc, a click elsewhere, moving on) is a no
public final class WaypointsTab implements Tab {

    private static final int REFRESH_TICKS = 20;
    private static final int CONFIRM_TICKS = 100;

    private final Theme theme;
    private final BotFacade facade;
    private final ShellContext ctx;
    private final TextField nameField;
    private final Dropdown tagDrop;
    private final Button addBtn;
    private final Segmented sort;
    private final WaypointRows rows;
    private final WaypointList list;
    private final View view;

    private String query = "";
    private String addError;
    private WaypointEntry confirming;
    private WorldAccess.Position lastPos;
    private String dimension = "";
    private String info = "";
    private int shown;
    private int total;
    private int ticks;
    private int confirmTicks;
    private List<String> tagNames;

    public WaypointsTab(Theme theme, BotFacade facade, ShellContext ctx) {
        this.theme = theme;
        this.facade = facade;
        this.ctx = ctx;
        nameField = new TextField(theme, "Name", s -> add());
        nameField.setOnChange(s -> {
            addError = null;
            nameField.setInvalid(false);
        });
        tagNames = tags();
        tagDrop = new Dropdown(theme, tagNames, 0, i -> { }, ctx);
        addBtn = new Button(theme, "Add here", Button.Kind.PRIMARY, null, this::add);
        sort = new Segmented(theme, List.of("Distance", "Name"), 0, i -> refresh());
        rows = new WaypointRows(theme, () -> lastPos, () -> confirming);
        list = new WaypointList();
        view = new View();
    }

    @Override
    public String id() {
        return "waypoints";
    }

    @Override
    public String title() {
        return "Waypoints";
    }

    @Override
    public Icon icon() {
        return Icon.WAYPOINTS;
    }

    @Override
    public Widget content() {
        return view;
    }

    @Override
    public boolean hasSearch() {
        return true;
    }

    @Override
    public String searchPlaceholder() {
        return "Search waypoints";
    }

    @Override
    public void onSearch(String q) {
        query = q == null ? "" : q;
        confirming = null;
        refresh();
    }

    @Override
    public void onShown() {
        confirming = null;
        ticks = 0;
        refresh();
    }

    @Override
    public void onHidden() {
        confirming = null;
    }

    // ----- data

    private List<String> tags() {
        try {
            List<String> t = facade.world().waypointTags();
            if (t != null && !t.isEmpty()) {
                return t;
            }
        } catch (Throwable t) {
            Safe.report("waypoint tags", t);
        }
        return List.of("user");
    }

    private void refresh() {
        Safe.run("waypoints refresh", () -> {
            WorldAccess w = facade.world();
            List<WaypointEntry> all = w.waypoints() == null ? List.of() : w.waypoints();
            lastPos = w.playerPos();
            dimension = w.dimension() == null ? "" : w.dimension();
            List<WaypointEntry> arranged = WaypointRules.arrange(all, query, sort.selected() == 1, lastPos);
            if (!arranged.equals(list.items())) {
                list.setItems(arranged);
            }
            list.setEmptyText(all.isEmpty() ? "No waypoints yet. Add one here." : "No waypoints match");
            if (confirming != null && !arranged.contains(confirming)) {
                confirming = null;
            }
            shown = arranged.size();
            total = all.size();
            String dim = Coords.dimensionName(dimension);
            info = (dim.isEmpty() ? "" : dim + " · ") + WaypointRules.count(shown, total);
            String keep = tagDrop.selected() >= 0 && tagDrop.selected() < tagNames.size() ? tagNames.get(tagDrop.selected()) : null;
            List<String> now = tags();
            int at = keep == null ? 0 : Math.max(0, now.indexOf(keep));
            tagDrop.setOptions(now, at);
            tagNames = now;
        });
    }

    private void add() {
        String name = nameField.text().trim();
        if (name.isEmpty()) {
            addError = "Give it a name";
            nameField.setInvalid(true);
            return;
        }
        List<String> names = tagNames;
        String tag = tagDrop.selected() >= 0 && tagDrop.selected() < names.size() ? names.get(tagDrop.selected()) : "user";
        Result r;
        try {
            r = facade.world().addWaypointHere(name, tag);
        } catch (Throwable t) {
            Safe.report("waypoint add", t);
            r = Result.fail("Could not save the waypoint. Try #wp save in chat.");
        }
        ctx.toast(r);
        if (r != null && r.ok()) {
            nameField.setText("");
            addError = null;
            refresh();
        }
    }

    private void ask(WaypointEntry e) {
        confirming = e;
        confirmTicks = 0;
    }

    private void goTo(WaypointEntry e) {
        ctx.toast(call("waypoint go", () -> facade.world().goTo(e.x(), e.y(), e.z()), "Could not start. Try #goto in chat."));
    }

    private void copy(WaypointEntry e) {
        String text = WaypointRules.coords(e);
        try {
            ctx.clipboard().set(text);
        } catch (Throwable t) {
            Safe.report("waypoint copy", t);
            ctx.toast(Result.fail("Could not copy. Select the numbers by hand."));
            return;
        }
        ctx.toast("Copied " + text, null);
    }

    private void delete(WaypointEntry e) {
        confirming = null;
        ctx.toast(call("waypoint delete", () -> facade.world().deleteWaypoint(e), "Could not delete it. Try #wp delete in chat."));
        refresh();
    }

    private Result call(String where, java.util.function.Supplier<Result> action, String failLine) {
        try {
            Result r = action.get();
            return r == null ? Result.fail(failLine) : r;
        } catch (Throwable t) {
            Safe.report(where, t);
            return Result.fail(failLine);
        }
    }

    // ----- the list

    private final class WaypointList extends VirtualList<WaypointEntry> {

        WaypointList() {
            super(WaypointsTab.this.theme, WaypointsTab.this.theme.px(44), WaypointsTab.this.rows);
            setSelectable(true);
            setKeyFunction(e -> e);
            onClick((e, i) -> goTo(e));
            onRowMouseDown(WaypointsTab.this::rowMouse);
        }

        @Override
        public boolean keyDown(int key, int mods) {
            if (!focused) {
                return false;
            }
            if (confirming != null) {
                if (key == Keys.ESCAPE) {
                    confirming = null;
                    return true;
                }
                if (Keys.isEnter(key)) {
                    delete(confirming);
                    return true;
                }
                // moving to another row is a no
                confirming = null;
            }
            int sel = selected();
            if (key == Keys.DELETE && sel >= 0 && sel < items.size()) {
                ask(items.get(sel));
                return true;
            }
            return super.keyDown(key, mods);
        }

        // the empty space under the rows and the scrollbar never reach the row callback, and scrolling would leave
        // an enter that deletes something you can no longer see
        @Override
        public boolean mouseDown(float mx, float my, int button, int mods) {
            if (confirming != null && contains(mx, my) && (indexAt(mx, my) < 0 || mx >= x + w - theme.px(14))) {
                confirming = null;
            }
            return super.mouseDown(mx, my, button, mods);
        }

        @Override
        public boolean scroll(float mx, float my, float dy) {
            if (confirming != null && contains(mx, my)) {
                confirming = null;
            }
            return super.scroll(mx, my, dy);
        }

        // clicking away, tabbing off, the search box taking focus: all of them mean no
        @Override
        public void setFocused(boolean f) {
            super.setFocused(f);
            if (!f) {
                confirming = null;
            }
        }
    }

    private boolean rowMouse(WaypointEntry e, int index, float mx, float my, int button, int mods) {
        if (button != 0) {
            return true;
        }
        boolean mine = confirming != null && confirming.equals(e);
        if (confirming != null) {
            int hit = mine ? rows.hit(index, true, mx, my) : -1;
            if (hit == WaypointRows.YES) {
                delete(e);
                return true;
            }
            confirming = null;
            if (mine) {
                return true;
            }
        }
        switch (rows.hit(index, false, mx, my)) {
            case WaypointRows.GO -> goTo(e);
            case WaypointRows.COPY -> copy(e);
            case WaypointRows.DELETE -> ask(e);
            case WaypointRows.PIN -> {
                boolean now = WaypointRules.togglePin(e);
                ctx.toast(now ? "Pinned " + e.name() : "Unpinned " + e.name(), null);
                refresh();
            }
            default -> {
            }
        }
        return true;
    }

    // ----- the view

    private final class View extends Widget {

        private final List<Widget> kids;
        private int errorTop;
        private int infoTop;

        View() {
            super(WaypointsTab.this.theme);
            kids = List.of(nameField, tagDrop, addBtn, sort, list);
        }

        @Override
        protected void layout() {
            int pad = theme.px(Theme.PAD);
            int gap = theme.px(Theme.GAP);
            int ch = theme.px(Theme.CONTROL_H);
            int top = y + pad;
            int right = x + w - pad;
            int addW = addBtn.preferredWidth();
            addBtn.setBounds(right - addW, top, addW, ch);
            int dropW = theme.px(132);
            tagDrop.setBounds(addBtn.x() - gap - dropW, top, dropW, ch);
            nameField.setBounds(x + pad, top, Math.max(theme.px(80), tagDrop.x() - gap - (x + pad)), ch);
            errorTop = top + ch + theme.px(4);
            infoTop = errorTop + theme.px(16) + theme.px(4);
            int sw = sort.preferredWidth();
            sort.setBounds(right - sw, infoTop, sw, ch);
            int lt = infoTop + ch + theme.px(8);
            list.setBounds(x + pad, lt, w - 2 * pad, Math.max(0, y + h - lt - theme.px(8)));
        }

        @Override
        public List<Widget> children() {
            return kids;
        }

        @Override
        public void tick() {
            super.tick();
            if (++ticks % REFRESH_TICKS == 0) {
                refresh();
            }
            // an unanswered "Delete?" lapses on its own, so an enter hours later cannot land on it
            if (confirming != null && ++confirmTicks >= CONFIRM_TICKS) {
                confirming = null;
            }
        }

        @Override
        public void draw(Canvas c, Frame f) {
            for (Widget k : kids) {
                k.draw(c, f);
            }
            int pad = theme.px(Theme.PAD);
            if (addError != null) {
                c.text(addError, x + pad + theme.px(2), mid(c, theme.small, errorTop, theme.px(16)), theme.small, theme.color(Theme.DANGER));
            }
            String line = c.ellipsize(info, theme.small, Math.max(0, sort.x() - theme.px(Theme.GAP) - (x + pad)));
            c.text(line, x + pad + theme.px(2), mid(c, theme.small, infoTop, theme.px(Theme.CONTROL_H)), theme.small, theme.color(Theme.TEXT_2));
        }

        private float mid(Canvas c, adris.altoclef.ui.gui.kit.TextStyle s, float top, float height) {
            return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
        }
    }

    // test hooks
    TextField nameField() {
        return nameField;
    }

    Dropdown tagDrop() {
        return tagDrop;
    }

    Button addButton() {
        return addBtn;
    }

    Segmented sort() {
        return sort;
    }

    VirtualList<WaypointEntry> list() {
        return list;
    }

    WaypointEntry confirming() {
        return confirming;
    }

    String addError() {
        return addError;
    }

    String info() {
        return info;
    }

    int[] zone(int index, int which) {
        return rows.paintedZone(index, which);
    }
}
