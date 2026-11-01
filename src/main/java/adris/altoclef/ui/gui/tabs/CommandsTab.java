package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.RoutinesAccess;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Routine;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunState;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunStatus;
import adris.altoclef.ui.gui.facade.RoutinesAccess.StoreState;
import adris.altoclef.ui.gui.facade.WorldAccess.WaypointEntry;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.RowWidgets;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

// saved routines (CustomTasks.json, the same ones #custom runs) on the left, the picked one's editor on the right. the
// run itself lives in the game, not here: the tab only reads status() every tick, so closing the gui mid run and
// opening it again shows the same run. a run that ends badly while you watch gets a toast
public final class CommandsTab implements Tab, RoutineListRow.Host, RoutineEditor.Listener {

    // design sizes, the mock's list column and the gap to the editor card
    private static final int LIST_W = 272;
    private static final int EDITOR_GAP = 26;
    private static final int INFO_H = 30;
    private static final int BANNER_H = 52;
    private static final int TIP_TICKS = 10;

    private final Theme theme;
    private final BotFacade facade;
    private final ShellContext ctx;
    private final Button newBtn;
    private final RoutineList list;
    private final RoutineEditor editor;
    private final View view;

    private String query = "";
    private StoreState store = new StoreState(true, null);
    private List<Routine> all = List.of();
    private List<Routine> shown = List.of();
    private final Map<String, Integer> problems = new HashMap<>();
    private RunStatus status = RoutinesAccess.IDLE_STATUS;
    private boolean statusSeen;
    private String selectedId;
    private boolean opened;
    private Map<String, ItemEntry> items;
    private List<String> waypointNames = List.of();
    private String tip;
    private int tipTicks;

    public CommandsTab(Theme theme, BotFacade facade, ShellContext ctx) {
        this.theme = theme;
        this.facade = facade;
        this.ctx = ctx;
        newBtn = new Button(theme, "New", Button.Kind.SECONDARY, Icon.PLUS, this::newRoutine);
        list = new RoutineList();
        editor = new RoutineEditor(theme, ctx, this);
        view = new View();
    }

    @Override
    public String id() {
        return "commands";
    }

    @Override
    public String title() {
        return "Commands";
    }

    @Override
    public Icon icon() {
        return Icon.ROUTINE;
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
        return "Search commands";
    }

    @Override
    public void onSearch(String q) {
        query = q == null ? "" : q;
        poll();
    }

    @Override
    public void onShown() {
        tip = null;
        poll();
    }

    // ----- data

    private RoutinesAccess r() {
        return facade.routines();
    }

    @Override
    public RoutinesAccess routines() {
        return r();
    }

    private void poll() {
        Safe.run("commands poll", () -> {
            // waypoints can change while the gui is open (the waypoints tab is one click away)
            readWaypoints();
            readStore();
            readStatus();
        });
    }

    private void readStore() {
        StoreState s = r().storeState();
        store = s == null ? new StoreState(false, "couldn't read CustomTasks.json") : s;
        editor.setLocked(!store.ok());
        List<Routine> next = r().list();
        next = next == null ? List.of() : List.copyOf(next);
        boolean changed = !next.equals(all);
        all = next;
        if (changed) {
            recount();
        }
        List<Routine> filtered = filter(all, query);
        if (!filtered.equals(shown) || changed) {
            shown = filtered;
            list.setItems(shown);
        }
        if (!opened) {
            // first look: the routine that's running, else the first one, else a blank new one
            opened = true;
            RunStatus st = r().status();
            Routine pick = st != null && st.state() == RunState.RUNNING ? find(st.id()) : null;
            if (pick == null && !all.isEmpty()) {
                pick = all.get(0);
            }
            open(pick);
            return;
        }
        if (changed && selectedId != null) {
            Routine sel = find(selectedId);
            if (sel == null) {
                // deleted, or renamed by somebody else: fall back to the first one
                open(all.isEmpty() ? null : all.get(0));
            } else if (!editor.dirty()) {
                // a hand edit or an #altoreload changed it under a clean editor, show the new version
                editor.open(sel);
            }
        }
    }

    // the red count per routine only changes when the file does, so validate runs then and not every tick
    private void recount() {
        problems.clear();
        for (Routine x : all) {
            int bad = 0;
            try {
                List<String> v = r().validate(x.steps() == null ? List.of() : x.steps());
                if (v != null) {
                    for (String e : v) {
                        if (e != null) {
                            bad++;
                        }
                    }
                }
            } catch (Throwable t) {
                Safe.report("commands validate", t);
            }
            if (x.id() != null) {
                problems.put(x.id().toLowerCase(Locale.ROOT), bad);
            }
        }
    }

    private void readStatus() {
        RunStatus st = r().status();
        st = st == null ? RoutinesAccess.IDLE_STATUS : st;
        RunStatus was = status;
        boolean first = !statusSeen;
        statusSeen = true;
        status = st;
        editor.setStatus(st);
        // an end that happens while the tab is up gets a toast, including one that came straight out of run() (a
        // first step that fails at once never shows RUNNING). the first look after opening the gui doesn't: an old
        // failure shows in the rows instead
        if (!first && st.state() != RunState.RUNNING && !st.equals(was)) {
            String line = endLine(st);
            if (line != null) {
                ctx.toast(Result.fail(line));
            }
        }
    }

    // "Diamond gear failed at step 3/5: no path to waypoint portal"
    static String endLine(RunStatus st) {
        String word = switch (st.state()) {
            case FAILED -> "failed";
            case CANCELLED -> "stopped";
            case REPLACED -> "was replaced";
            default -> null;
        };
        if (word == null) {
            return null;
        }
        String name = st.name() == null || st.name().isBlank() ? st.id() : st.name();
        String line = name + " " + word + " at step " + st.step() + "/" + st.total();
        String msg = st.message() == null ? "" : st.message().trim();
        // a stop button's message is just "stopped", saying it twice reads like a stutter
        if (!msg.isEmpty() && !msg.equalsIgnoreCase(word) && !msg.equalsIgnoreCase("stopped")) {
            line += ": " + msg;
        }
        return line;
    }

    static List<Routine> filter(List<Routine> all, String q) {
        String qq = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (qq.isEmpty()) {
            return all;
        }
        List<Routine> out = new ArrayList<>();
        for (Routine x : all) {
            String name = x.name() == null ? "" : x.name().toLowerCase(Locale.ROOT);
            String id = x.id() == null ? "" : x.id().toLowerCase(Locale.ROOT);
            if (name.contains(qq) || id.contains(qq)) {
                out.add(x);
            }
        }
        return out;
    }

    private Routine find(String id) {
        if (id == null) {
            return null;
        }
        for (Routine x : all) {
            if (id.equalsIgnoreCase(x.id())) {
                return x;
            }
        }
        return null;
    }

    private void open(Routine x) {
        selectedId = x == null ? null : x.id();
        editor.open(x);
    }

    private void newRoutine() {
        if (!store.ok()) {
            return;
        }
        selectedId = null;
        editor.openNew();
    }

    // ----- RoutineListRow.Host

    @Override
    public String selectedId() {
        return selectedId;
    }

    @Override
    public RunStatus status() {
        return status;
    }

    @Override
    public int problems(String id) {
        Integer n = id == null ? null : problems.get(id.toLowerCase(Locale.ROOT));
        return n == null ? 0 : n;
    }

    @Override
    public int count() {
        return shown.size();
    }

    @Override
    public void select(Routine x) {
        if (x != null && !x.id().equalsIgnoreCase(selectedId == null ? "" : selectedId)) {
            open(x);
        }
    }

    // ----- RoutineEditor.Listener (and the rows' Run / Stop)

    @Override
    public void run(String id) {
        Safe.run("commands run", () -> r().run(id));
        poll();
    }

    @Override
    public void stop() {
        Safe.run("commands stop", () -> r().stop());
        poll();
    }

    @Override
    public void saved(String id) {
        // a rename has a new id, point at it first so the poll doesn't think the old one vanished
        selectedId = id;
        poll();
        Routine x = find(id);
        if (x != null) {
            open(x);
        }
    }

    @Override
    public void deleted(String id) {
        boolean ok = false;
        try {
            ok = r().delete(id);
        } catch (Throwable t) {
            Safe.report("commands delete", t);
        }
        if (!ok) {
            ctx.toast(Result.fail("Couldn't delete it, CustomTasks.json didn't take the change"));
            return;
        }
        poll();
        open(all.isEmpty() ? null : all.get(0));
    }

    @Override
    public ItemEntry item(String id) {
        if (items == null) {
            items = new HashMap<>();
            try {
                for (ItemEntry e : facade.items().gettable()) {
                    items.put(e.name(), e);
                }
            } catch (Throwable t) {
                Safe.report("commands items", t);
            }
        }
        return id == null ? null : items.get(id.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public List<String> waypoints() {
        return waypointNames;
    }

    private void readWaypoints() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            List<WaypointEntry> wps = facade.world().waypoints();
            if (wps != null) {
                for (WaypointEntry w : wps) {
                    if (w.name() != null && !w.name().isBlank()) {
                        names.add(w.name());
                    }
                }
            }
        } catch (Throwable t) {
            Safe.report("commands waypoints", t);
        }
        waypointNames = List.copyOf(names);
    }

    @Override
    public List<Routine> all() {
        return all;
    }

    // ----- the list

    private final class RoutineList extends RowWidgets<Routine> {

        RoutineList() {
            super(CommandsTab.this.theme, CommandsTab.this.theme.px(RoutineListRow.ROW_H),
                    () -> new RoutineListRow(CommandsTab.this.theme, CommandsTab.this));
            setKeyFunction(x -> x.id() == null ? "" : x.id().toLowerCase(Locale.ROOT));
        }

        @Override
        public void draw(Canvas c, Frame f) {
            ListCard.card(theme, c, this, f.nowNanos(), items.size() * rowHeight());
            super.draw(c, f);
        }
    }

    // ----- the view

    private final class View extends Widget {

        private final List<Widget> kids;
        private int top;
        private int listW;
        // what the last layout assumed about the banner
        private boolean laidOutOk = true;

        View() {
            super(CommandsTab.this.theme);
            kids = List.of(newBtn, list, editor);
        }

        @Override
        protected void layout() {
            int pad = theme.px(Theme.PAD);
            laidOutOk = store.ok();
            // the banner takes 52 + 12 off the top while the file is broken
            top = y + pad + (store.ok() ? 0 : theme.px(BANNER_H) + theme.px(Theme.SPACE_3));
            int inner = w - 2 * pad;
            // the mock's 272, squeezed on a small window so the editor keeps room for its boxes
            listW = Math.max(theme.px(200), Math.min(theme.px(LIST_W), Math.round(inner * 0.34f)));
            int ch = theme.px(Theme.CONTROL_H);
            int nw = newBtn.preferredWidth();
            newBtn.setBounds(x + pad + listW - nw, top, nw, ch);
            int lt = top + theme.px(INFO_H) + theme.px(Theme.GAP);
            int gutter = ListCard.gutter(theme);
            list.setBounds(x + pad, lt, listW + gutter, Math.max(0, y + h - pad - lt));
            int ex = x + pad + listW + theme.px(EDITOR_GAP);
            editor.setBounds(ex, top, Math.max(theme.px(240), x + w - pad - ex), Math.max(0, y + h - pad - top));
        }

        @Override
        public List<Widget> children() {
            return kids;
        }

        @Override
        public Widget mouseMove(float mx, float my) {
            Widget d = super.mouseMove(mx, my);
            String t = tipAt(mx, my);
            if (!Objects.equals(t, tip)) {
                tip = t;
                tipTicks = 0;
            }
            return d;
        }

        // a grey Run says why after the pointer rests on it a moment
        private String tipAt(float mx, float my) {
            Button run = editor.runButton();
            if (run.contains(mx, my) && !run.enabled()) {
                return editor.runBlocked();
            }
            for (Widget w : list.children()) {
                if (w instanceof RoutineListRow row && row.goButton().contains(mx, my) && !row.goButton().enabled()) {
                    return row.blocked();
                }
            }
            return null;
        }

        @Override
        public void tick() {
            poll();
            if (store.ok() != laidOutOk) {
                layout();
            }
            newBtn.setEnabled(store.ok());
            super.tick();
            if (tip != null && tipTicks < TIP_TICKS) {
                tipTicks++;
            }
        }

        @Override
        public void draw(Canvas c, Frame f) {
            if (store.ok() != laidOutOk) {
                layout();
            }
            if (!store.ok()) {
                drawBanner(c);
            }
            int pad = theme.px(Theme.PAD);
            int ch = theme.px(Theme.CONTROL_H);
            String countLine = shown.size() == all.size()
                    ? all.size() + (all.size() == 1 ? " command" : " commands")
                    : shown.size() + " of " + all.size() + (all.size() == 1 ? " command" : " commands");
            float room = newBtn.x() - theme.px(Theme.GAP) - (x + pad);
            c.text(c.ellipsize(countLine, theme.label, Math.max(0, room)), x + pad, mid(c, theme.label, top, ch), theme.label, theme.color(Theme.TEXT));
            newBtn.draw(c, f);
            if (shown.isEmpty()) {
                drawEmpty(c);
            } else {
                list.draw(c, f);
            }
            editor.draw(c, f);
            drawTip(c, f);
        }

        // a card with the danger edge across the top, same red an invalid box uses
        private void drawBanner(Canvas c) {
            int pad = theme.px(Theme.PAD);
            float bx = x + pad;
            float by = y + pad;
            float bw = w - 2 * pad;
            float bh = theme.px(BANNER_H);
            float rad = theme.pxf(Theme.CARD_RADIUS);
            c.fillRoundRect(bx, by, bw, bh, rad, theme.color(Theme.SURFACE));
            c.strokeRoundRect(bx, by, bw, bh, rad, Theme.fade(theme.color(Theme.DANGER), 0.5f));
            int is = theme.px(Theme.ICON);
            c.icon(Icon.WARNING, bx + pad, by + (bh - is) / 2f, is, theme.color(Theme.DANGER));
            float tx = bx + pad + is + theme.px(Theme.SPACE_3);
            float tr = bw - (tx - bx) - pad;
            TextStyle l = theme.label;
            TextStyle s = theme.small;
            float lh = c.lineHeight(l);
            float sh = c.lineHeight(s);
            float t0 = by + (bh - lh - theme.px(2) - sh) / 2f;
            c.text("CustomTasks.json has an error", tx, t0 + c.ascent(l), l, theme.color(Theme.TEXT));
            String err = store.error() == null || store.error().isBlank() ? "it couldn't be read" : store.error();
            String line = sentence(err) + ". Fix the file, then run #altoreload. Editing is off until then.";
            c.text(c.ellipsize(line, s, Math.max(0, tr)), tx, t0 + lh + theme.px(2) + c.ascent(s), s, theme.color(Theme.TEXT_2));
        }

        private void drawEmpty(Canvas c) {
            String s = all.isEmpty() ? "No commands yet. Press New to make one." : "No commands match";
            TextStyle st = theme.small;
            float room = listW - 2 * theme.px(24);
            List<String> lines = HelpList.wrap(c, s, st, Math.max(theme.px(80), room));
            float lh = c.lineHeight(st) + theme.px(2);
            float t0 = list.y() + (list.h() - lines.size() * lh) / 2f;
            float cx = list.x() + listW / 2f;
            for (int i = 0; i < lines.size(); i++) {
                float tw = c.measure(lines.get(i), st);
                c.text(lines.get(i), cx - tw / 2f, t0 + i * lh + c.ascent(st), st, theme.color(Theme.TEXT_3));
            }
        }

        private void drawTip(Canvas c, Frame f) {
            if (tip == null || tipTicks < TIP_TICKS) {
                return;
            }
            TextStyle s = theme.small;
            List<String> lines = HelpList.wrap(c, tip, s, theme.px(300));
            float tw = 0;
            for (String l : lines) {
                tw = Math.max(tw, c.measure(l, s));
            }
            float lh = c.lineHeight(s);
            float bw = tw + 2 * theme.px(10);
            float bh = lines.size() * lh + 2 * theme.px(6);
            float bx = f.mouseX() + theme.px(12);
            float by = f.mouseY() + theme.px(16);
            if (bx + bw > x + w - theme.px(8)) {
                bx = x + w - theme.px(8) - bw;
            }
            if (by + bh > y + h - theme.px(8)) {
                by = f.mouseY() - theme.px(8) - bh;
            }
            theme.floating(c, bx, by, bw, bh, theme.pxf(Theme.CONTROL_RADIUS));
            for (int i = 0; i < lines.size(); i++) {
                c.text(lines.get(i), bx + theme.px(10), by + theme.px(6) + i * lh + c.ascent(s), s, theme.color(Theme.TEXT));
            }
        }

        private float mid(Canvas c, TextStyle s, float top, float height) {
            return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
        }
    }

    private static String sentence(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    // test hooks
    RoutineEditor editor() {
        return editor;
    }

    RowWidgets<Routine> list() {
        return list;
    }

    Button newButton() {
        return newBtn;
    }

    String tipText() {
        return tip != null && tipTicks >= TIP_TICKS ? tip : null;
    }
}
