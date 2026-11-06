package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.RoutinesAccess;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Routine;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunState;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunStatus;
import adris.altoclef.ui.gui.facade.RoutinesAccess.SaveResult;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Scroller;
import adris.altoclef.ui.gui.kit.Segmented;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.RoutineDraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

// the right half of the commands tab: name and description, the steps top to bottom, and delete / save / run along
// the bottom. it owns the draft (the boxes as typed) and asks the facade for every rule: lineFor turns boxes into
// lines, validate says which rows are red, save says whether the name is free. the steps area is its own widget so a
// "List | Nodes" switch can go in the steps header later and swap what sits under it
final class RoutineEditor extends Widget implements StepRow.Host {

    interface Listener {
        RoutinesAccess routines();

        ItemEntry item(String id);

        // what the id boxes suggest while you type
        default IdLists ids() {
            return IdLists.NONE;
        }

        List<String> waypoints();

        List<Routine> all();

        // the store took it, under this id
        void saved(String id);

        void deleted(String id);

        void run(String id);

        void stop();

        // the node editor's switch only exists with this on, off is the editor exactly as it was
        default boolean graphsEnabled() {
            return false;
        }

        default void showNodes() {
        }
    }

    // design sizes, the mock's head / steps head / foot
    private static final int HEAD_PAD = 12;
    private static final int LABEL_H = 15;
    private static final int NAME_W = 196;
    private static final int STEPS_HEAD_H = 46;
    private static final int FOOT_H = 54;

    private final ShellContext ctx;
    private final Listener listener;
    private final StepField nameField;
    private final StepField descField;
    private final StepList steps;
    private final Button delBtn;
    private final Button saveBtn;
    private final Button runBtn;
    private final List<Widget> kids;
    private final List<Widget> kidsWithSwitch = new ArrayList<>();
    private Segmented viewSwitch;

    private RoutineDraft draft = RoutineDraft.blank();
    private List<String> errs = List.of();
    private RunStatus status = RoutinesAccess.IDLE_STATUS;
    private boolean locked;
    private boolean confirmDelete;
    private String saveError;
    private int headBottom;
    private int footTop;

    RoutineEditor(Theme theme, ShellContext ctx, Listener listener) {
        super(theme);
        this.ctx = ctx;
        this.listener = listener;
        nameField = new StepField(theme, "Like Diamond gear", StepField.Lead.NONE, null, null);
        nameField.edit().setMaxLength(64);
        nameField.setOnChange(t -> {
            draft.name = t;
            edited();
        });
        descField = new StepField(theme, "Optional, shows in the list", StepField.Lead.NONE, null, null);
        descField.edit().setMaxLength(120);
        descField.setOnChange(t -> {
            draft.description = t;
            edited();
        });
        steps = new StepList();
        delBtn = new Button(theme, "Delete", Button.Kind.GHOST, Icon.TRASH, this::deleteClicked);
        saveBtn = new Button(theme, "Save", Button.Kind.SECONDARY, null, this::saveClicked);
        runBtn = new Button(theme, "Run", Button.Kind.PRIMARY, null, this::runClicked);
        kids = List.of(nameField, descField, steps, delBtn, saveBtn, runBtn);
        kidsWithSwitch.addAll(kids);
    }

    // ----- state in

    void open(Routine r) {
        draft = RoutineDraft.open(r, listener.routines());
        confirmDelete = false;
        saveError = null;
        ctx.blur();
        nameField.setText(draft.name);
        descField.setText(draft.description);
        steps.scroller.snapTo(0);
        structure();
    }

    void openNew() {
        open(null);
        if (!locked) {
            ctx.focus(nameField);
        }
    }

    String draftId() {
        return draft.id;
    }

    boolean dirty() {
        return draft.dirty(listener.routines());
    }

    // a new one is always "dirty" because Run has to save it, this says whether anybody put anything in it
    boolean hasEdits() {
        return draft.isNew() ? !draft.name.isEmpty() || !draft.description.isEmpty() || !draft.rows.isEmpty() : dirty();
    }

    // what going to the node view does first. false = it didn't save, the error is in the footer and we stay
    boolean saveIfDirty() {
        return !hasEdits() || save();
    }

    boolean trySave() {
        return save();
    }

    void setLocked(boolean on) {
        if (on == locked) {
            return;
        }
        locked = on;
        if (on) {
            // an open picker or a caret in a count box would outlive the lock otherwise
            ctx.closeOverlay();
            ctx.blur();
        }
        nameField.setEditable(!on);
        descField.setEditable(!on);
        steps.bindAll();
    }

    // the run moved on: the numbers turn into dots and checks. cheap, it only touches the row marks
    void setStatus(RunStatus st) {
        status = st == null ? RoutinesAccess.IDLE_STATUS : st;
        steps.marks();
    }

    // ----- StepRow.Host

    @Override
    public RoutineDraft draft() {
        return draft;
    }

    @Override
    public void edited() {
        confirmDelete = false;
        saveError = null;
        validate();
        steps.marks();
    }

    @Override
    public void structure() {
        confirmDelete = false;
        saveError = null;
        validate();
        steps.bindAll();
    }

    // nothing to do right now: the steps place themselves every frame. placing here would reshuffle the live list
    // while the mouse handler is still walking it
    @Override
    public void structureSoft() {
    }

    @Override
    public boolean locked() {
        return locked;
    }

    @Override
    public ItemEntry item(String id) {
        return listener.item(id);
    }

    @Override
    public IdLists ids() {
        return listener.ids();
    }

    @Override
    public List<String> waypoints() {
        return listener.waypoints();
    }

    @Override
    public ShellContext ctx() {
        return ctx;
    }

    private void validate() {
        List<String> v = null;
        try {
            v = listener.routines().validate(draft.steps(listener.routines()));
        } catch (Throwable t) {
            Safe.report("routines validate", t);
        }
        errs = v == null ? Collections.nCopies(draft.rows.size(), null) : v;
    }

    private String errorAt(int i) {
        return i < errs.size() ? errs.get(i) : null;
    }

    int badCount() {
        int n = 0;
        for (String e : errs) {
            if (e != null) {
                n++;
            }
        }
        return n;
    }

    // ----- the id line and the buttons

    private String shownId() {
        return draft.shownId();
    }

    private boolean clash() {
        String id = shownId();
        if (id == null) {
            return false;
        }
        for (Routine r : listener.all()) {
            if (r.id() != null && r.id().equalsIgnoreCase(id) && (draft.id == null || !r.id().equalsIgnoreCase(draft.id))) {
                return true;
            }
        }
        return false;
    }

    private boolean runningMine() {
        return draft.id != null && status.state() == RunState.RUNNING && draft.id.equalsIgnoreCase(status.id());
    }

    // why Run is grey, or null when it isn't. the footer says it too, this is the tooltip's line
    String runBlocked() {
        if (runningMine()) {
            return null;
        }
        int bad = badCount();
        if (bad > 0) {
            return bad + (bad == 1 ? " step needs" : " steps need") + " fixing before it can run";
        }
        if (draft.rows.isEmpty()) {
            return "Add a step first";
        }
        if (shownId() == null) {
            return "Give it a name first";
        }
        if (clash()) {
            return "That name is taken, pick another one";
        }
        if (locked && dirty()) {
            return "CustomTasks.json has an error, so this can't be saved";
        }
        return null;
    }

    private void syncButtons() {
        boolean dirty = dirty();
        boolean running = runningMine();
        runBtn.setText(running ? "Stop" : "Run");
        runBtn.setKind(running ? Button.Kind.DANGER : Button.Kind.PRIMARY);
        runBtn.setEnabled(running || runBlocked() == null);
        saveBtn.setText(confirmDelete ? "Keep" : "Save");
        saveBtn.setEnabled(confirmDelete || (!locked && dirty && shownId() != null && !clash()));
        delBtn.setKind(confirmDelete ? Button.Kind.DANGER : Button.Kind.GHOST);
        delBtn.setEnabled(!locked && draft.id != null);
    }

    // ----- actions

    private void saveClicked() {
        if (confirmDelete) {
            confirmDelete = false;
            return;
        }
        save();
    }

    private boolean save() {
        if (locked) {
            return false;
        }
        SaveResult r;
        try {
            r = listener.routines().save(draft.toRoutine(listener.routines()));
        } catch (Throwable t) {
            Safe.report("routines save", t);
            r = new SaveResult(false, "saving broke, the log has the details");
        }
        if (r == null || !r.ok()) {
            saveError = sentence(r == null || r.error() == null ? "couldn't save" : r.error());
            return false;
        }
        // the store makes the id out of the name the same way the id line does
        listener.saved(RoutineDraft.idFor(draft.name));
        return true;
    }

    private void runClicked() {
        if (runningMine()) {
            listener.stop();
            return;
        }
        if (runBlocked() != null) {
            return;
        }
        String id = draft.id;
        if (dirty()) {
            if (!save()) {
                return;
            }
            id = RoutineDraft.idFor(draft.name);
        }
        listener.run(id);
    }

    // the first click only asks, the second one deletes. same idea as the schematics build button
    private void deleteClicked() {
        if (locked || draft.id == null) {
            return;
        }
        if (!confirmDelete) {
            confirmDelete = true;
            return;
        }
        confirmDelete = false;
        listener.deleted(draft.id);
    }

    private static String sentence(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    // ----- layout

    @Override
    protected void layout() {
        int pad = theme.px(Theme.PAD);
        int gap = theme.px(Theme.GAP);
        int ch = theme.px(Theme.CONTROL_H);
        int fy = y + theme.px(HEAD_PAD) + theme.px(LABEL_H) + theme.px(Theme.SPACE_1);
        int nw = theme.px(NAME_W);
        nameField.setBounds(x + pad, fy, nw, ch);
        descField.setBounds(x + pad + nw + gap, fy, Math.max(theme.px(60), w - 2 * pad - nw - gap), ch);
        // the id line hangs 6 under the boxes, then the head's own bottom padding
        headBottom = fy + ch + theme.px(6) + theme.px(LABEL_H) + theme.px(HEAD_PAD);
        footTop = y + h - theme.px(FOOT_H);
        int by = footTop + (theme.px(FOOT_H) - ch) / 2;
        int bw = theme.px(72);
        runBtn.setBounds(x + w - pad - bw, by, bw, ch);
        saveBtn.setBounds(runBtn.x() - gap - bw, by, bw, ch);
        int dw = delBtn.preferredWidth();
        delBtn.setBounds(saveBtn.x() - gap - dw, by, dw, ch);
        int sTop = headBottom + 1 + theme.px(STEPS_HEAD_H);
        steps.setBounds(x + theme.px(Theme.SPACE_2), sTop, w - theme.px(Theme.SPACE_2) - 1, Math.max(0, footTop - 1 - sTop));
        if (viewSwitch != null) {
            int sw = viewSwitch.preferredWidth();
            viewSwitch.setBounds(x + w - pad - sw, headBottom + 1 + (theme.px(STEPS_HEAD_H) - ch) / 2, sw, ch);
        }
    }

    @Override
    public List<Widget> children() {
        return switchOn() ? kidsWithSwitch : kids;
    }

    private boolean switchOn() {
        if (!listener.graphsEnabled()) {
            return false;
        }
        if (viewSwitch == null) {
            // built the first time it's asked for: with the editor off there's no switch at all, not a hidden one
            viewSwitch = new Segmented(theme, List.of("List", "Nodes"), 0, i -> {
                viewSwitch.setSelected(0);
                if (i == 1) {
                    listener.showNodes();
                }
            });
            kidsWithSwitch.add(viewSwitch);
            layout();
        }
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        syncButtons();
    }

    @Override
    public void draw(Canvas c, Frame f) {
        syncButtons();
        theme.card(c, x, y, w, h, theme.pxf(Theme.CARD_RADIUS), false);
        int pad = theme.px(Theme.PAD);
        TextStyle sl = theme.smallLabel;
        float ly = y + theme.px(HEAD_PAD);
        c.text("Name", nameField.x(), ly + c.ascent(sl), sl, theme.color(Theme.TEXT_2));
        c.text("Description", descField.x(), ly + c.ascent(sl), sl, theme.color(Theme.TEXT_2));
        nameField.setInvalid(nameBad());
        nameField.draw(c, f);
        descField.draw(c, f);
        drawIdLine(c);

        // hairlines inside the card's stroke, like the mock's cm-rule
        int hl = 1;
        c.fillRect(x + hl, headBottom, w - 2 * hl, 1, theme.color(Theme.HAIRLINE));
        c.fillRect(x + hl, footTop - 1, w - 2 * hl, 1, theme.color(Theme.HAIRLINE));

        float shTop = headBottom + 1;
        float base = mid(c, theme.label, shTop, theme.px(STEPS_HEAD_H));
        c.text("Steps", x + pad, base, theme.label, theme.color(Theme.TEXT));
        int n = draft.rows.size();
        if (n > 0) {
            float sx = x + pad + c.measure("Steps", theme.label) + theme.px(10);
            c.text(n + (n == 1 ? " step" : " steps") + ", run top to bottom", sx, base, theme.small.asTabular(), theme.color(Theme.TEXT_3));
        }
        if (switchOn()) {
            viewSwitch.draw(c, f);
        }

        steps.draw(c, f);
        drawFootMessage(c);
        delBtn.draw(c, f);
        saveBtn.draw(c, f);
        runBtn.draw(c, f);
    }

    private boolean nameBad() {
        String id = shownId();
        return (id == null && (draft.id != null || !draft.name.isEmpty())) || clash();
    }

    private void drawIdLine(Canvas c) {
        String id = shownId();
        boolean bad;
        String line;
        if (id == null) {
            bad = draft.id != null || !draft.name.isEmpty();
            line = bad ? "Give it a name" : "The name becomes its #custom id";
        } else if (clash()) {
            bad = true;
            line = "There is already a command called " + id;
        } else {
            bad = false;
            line = "Runs as #custom " + id;
        }
        TextStyle s = theme.small;
        float iy = nameField.y() + nameField.h() + theme.px(6);
        float room = w - 2 * theme.px(Theme.PAD);
        c.text(c.ellipsize(line, s, room), x + theme.px(Theme.PAD), iy + c.ascent(s), s, theme.color(bad ? Theme.DANGER : Theme.TEXT_3));
    }

    private void drawFootMessage(Canvas c) {
        String msg;
        int col = Theme.TEXT_3;
        int bad = badCount();
        boolean dirty = dirty();
        if (confirmDelete) {
            msg = "Delete " + draft.name + "? This removes it from CustomTasks.json";
            col = Theme.TEXT;
        } else if (saveError != null) {
            msg = saveError;
            col = Theme.DANGER;
        } else if (locked) {
            msg = "Editing is off";
        } else if (bad > 0) {
            msg = bad + (bad == 1 ? " step needs" : " steps need") + " fixing before it can run";
            col = Theme.DANGER;
        } else if (draft.rows.isEmpty()) {
            msg = "Add a step to run it";
        } else if (dirty) {
            msg = draft.id != null ? "Unsaved changes. Run saves first" : "Not saved yet. Run saves first";
        } else {
            msg = "";
        }
        if (msg.isEmpty()) {
            return;
        }
        float left = x + theme.px(Theme.PAD);
        float room = Math.max(0, delBtn.x() - theme.px(Theme.GAP) - left);
        TextStyle s = theme.small;
        c.text(c.ellipsize(msg, s, room), left, mid(c, s, footTop, theme.px(FOOT_H)), s, theme.color(col));
    }

    private static float mid(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }

    // test hooks
    StepField nameField() {
        return nameField;
    }

    StepField descField() {
        return descField;
    }

    Button runButton() {
        return runBtn;
    }

    Button saveButton() {
        return saveBtn;
    }

    Button deleteButton() {
        return delBtn;
    }

    Segmented viewSwitch() {
        return viewSwitch;
    }

    Button addButton() {
        return steps.addBtn;
    }

    List<StepRow> rows() {
        return steps.rows.subList(0, Math.min(steps.rows.size(), draft.rows.size()));
    }

    // ----- the steps, a scrolling column of real widgets. rows aren't one height (a get step carries its basket), so
    // this isn't a RowWidgets: a routine is a handful of steps (100 at the very most), every one of them is a widget

    private final class StepList extends Widget {

        private final Scroller scroller = new Scroller();
        private final List<StepRow> rows = new ArrayList<>();
        private final List<Widget> live = new ArrayList<>();
        private final Button addBtn;
        private long now;
        private boolean scrollToEnd;

        StepList() {
            super(RoutineEditor.this.theme);
            addBtn = new Button(theme, "Add step", Button.Kind.GHOST, Icon.PLUS, () -> {
                draft.add(RoutineDraft.Kind.GET);
                scrollToEnd = true;
                structure();
            });
        }

        void bindAll() {
            int n = draft.rows.size();
            while (rows.size() < n) {
                rows.add(new StepRow(theme, RoutineEditor.this));
            }
            for (int i = 0; i < n; i++) {
                rows.get(i).bind(draft.rows.get(i), i, i == n - 1);
            }
            for (int i = n; i < rows.size(); i++) {
                rows.get(i).setFocused(false);
            }
            addBtn.setVisible(!locked);
            marks();
            place();
        }

        void marks() {
            boolean mine = draft.id != null && draft.id.equalsIgnoreCase(status.id()) && !dirty();
            RunState s = status.state();
            int step = status.step();
            for (int i = 0; i < Math.min(rows.size(), draft.rows.size()); i++) {
                StepRow.Mark m = StepRow.Mark.NONE;
                if (mine) {
                    int k = i + 1;
                    if (s == RunState.RUNNING) {
                        m = k == step ? StepRow.Mark.NOW : k < step ? StepRow.Mark.DONE : StepRow.Mark.NONE;
                    } else if (s == RunState.DONE) {
                        m = StepRow.Mark.DONE;
                    } else if (s == RunState.FAILED && k == step) {
                        m = StepRow.Mark.FAIL;
                    } else if (s != RunState.IDLE && k < step) {
                        m = StepRow.Mark.DONE;
                    }
                }
                rows.get(i).setState(errorAt(i), m, status.message());
            }
            place();
        }

        // stacks the rows from the scroll offset down. a handful of widgets, so it runs every frame and nobody has to
        // remember to call it when a row grows
        void place() {
            int n = Math.min(rows.size(), draft.rows.size());
            int rw = w - theme.px(Theme.SPACE_2);
            int content = 0;
            int[] hs = new int[n];
            for (int i = 0; i < n; i++) {
                hs[i] = rows.get(i).heightFor();
                content += hs[i];
            }
            int addH = locked ? 0 : theme.px(Theme.SPACE_1) + theme.px(Theme.CONTROL_H);
            scroller.setSizes(content + addH + theme.px(Theme.SPACE_2), h);
            if (scrollToEnd) {
                scrollToEnd = false;
                scroller.scrollTo(scroller.maxOffset(), now);
            }
            float off = scroller.offset(now);
            int cy = Math.round(y - off);
            live.clear();
            for (int i = 0; i < n; i++) {
                StepRow r = rows.get(i);
                if (r.x() != x || r.y() != cy || r.w() != rw || r.h() != hs[i]) {
                    r.setBounds(x, cy, rw, hs[i]);
                }
                if (cy + hs[i] > y && cy < y + h) {
                    live.add(r);
                }
                cy += hs[i];
            }
            if (!locked) {
                int aw = addBtn.preferredWidth();
                addBtn.setBounds(x + theme.px(Theme.SPACE_1), cy + theme.px(Theme.SPACE_1), aw, theme.px(Theme.CONTROL_H));
                if (addBtn.y() + addBtn.h() > y && addBtn.y() < y + h) {
                    live.add(addBtn);
                }
            }
        }

        @Override
        protected void layout() {
            place();
        }

        @Override
        public List<Widget> children() {
            return live;
        }

        @Override
        public boolean mouseDown(float mx, float my, int button, int mods) {
            // a row half scrolled out must not take a click that landed outside the steps box
            return contains(mx, my) && super.mouseDown(mx, my, button, mods);
        }

        @Override
        public Widget mouseMove(float mx, float my) {
            if (!contains(mx, my)) {
                // off the box everything under it is unhovered, even the bits of rows scrolled out of sight
                for (Widget k : live) {
                    k.mouseMove(-1e6f, -1e6f);
                }
                hovered = false;
                return null;
            }
            return super.mouseMove(mx, my);
        }

        @Override
        public boolean scroll(float mx, float my, float dy) {
            if (!contains(mx, my)) {
                return false;
            }
            if (super.scroll(mx, my, dy)) {
                return true;
            }
            if (!scroller.scrollable()) {
                return false;
            }
            scroller.scrollBy(-dy * theme.px(StepRow.LINE_H) * 2, now);
            return true;
        }

        @Override
        public void draw(Canvas c, Frame f) {
            now = f.nowNanos();
            place();
            c.pushClip(x, y, w, h);
            try {
                for (Widget k : live) {
                    k.draw(c, f);
                }
            } finally {
                c.popClip();
            }
            drawScrollbar(c);
        }

        private void drawScrollbar(Canvas c) {
            float vis = scroller.visibility(now, hovered);
            if (vis < 0.01f || !scroller.scrollable()) {
                return;
            }
            float trackY = y + theme.px(4);
            float trackH = h - theme.px(8);
            float th = scroller.thumbHeight(trackH, theme.px(24));
            float tt = scroller.thumbTop(trackH, theme.px(24), now);
            float bw = theme.px(Theme.SCROLLBAR_W);
            float bx = x + w - bw - theme.px(1);
            c.fillRoundRect(bx, trackY + tt, bw, th, bw / 2f, Theme.fade(theme.color(Theme.TEXT_3), vis));
        }
    }
}
