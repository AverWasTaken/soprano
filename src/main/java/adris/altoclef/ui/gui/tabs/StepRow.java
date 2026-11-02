package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Dropdown;
import adris.altoclef.ui.gui.kit.FadeCanvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.RoutineDraft;
import adris.altoclef.ui.gui.model.RoutineDraft.Kind;
import adris.altoclef.ui.gui.model.RoutineDraft.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// one step of a routine: its number, the type picker, the type's boxes, then up / down / delete. a get step hangs the
// get tab's basket under itself, a size down so five items still fit. a bad step goes faint red with the reason under
// its boxes, and while the routine runs the number turns into the run's dot, a check, or the warning where it stopped
final class StepRow extends Widget {

    interface Host {
        RoutineDraft draft();

        // a box changed: check the rows again, nothing gets rebuilt under the caret
        void edited();

        // rows came, went, moved or changed kind: everything gets bound again
        void structure();

        // only a row's height changed (the add box came or went): place the rows again, bind nothing
        void structureSoft();

        boolean locked();

        ItemEntry item(String id);

        List<String> waypoints();

        ShellContext ctx();
    }

    enum Mark { NONE, NOW, DONE, FAIL }

    // design sizes. the columns are the mock's grid: number, type, the boxes, the three actions
    static final int PAD_Y = 7;
    static final int IDX_W = 22;
    // the mock had 136, inter at 14 needs a little more for "Go to waypoint" next to the chevron
    static final int TYPE_W = 144;
    static final int ACT = 28;
    static final int ROW_GAP = 6;
    static final int LINE_H = 40;
    static final int WHY_H = 15;
    // an empty x or z is fine, lineFor fills in ~ (where you stand)
    private static final Pattern COORD = Pattern.compile("|~|~?[+-]?\\d+");

    private final Host host;
    private final Dropdown type;
    private final StepField item;
    private final StepField secs;
    private final StepField raw;
    private final StepField[] xyz = new StepField[3];
    private final Dropdown waypoint;
    private final IconButton up;
    private final IconButton down;
    private final IconButton bin;
    private final StepField add;
    private final List<BasketLine> lines = new ArrayList<>();
    private final List<Widget> kids = new ArrayList<>();
    private final BasketLine.Owner basketOwner = new BasketOwner();
    private final FadeCanvas fade = new FadeCanvas();
    private List<String> wpOptions = List.of();

    private Row row;
    private int index;
    private boolean last;
    private String why;
    private Mark mark = Mark.NONE;
    private String failMsg;

    StepRow(Theme theme, Host host) {
        super(theme);
        this.host = host;
        ShellContext ctx = host.ctx();
        type = new Dropdown(theme, Kind.labels(), 0, i -> {
            Kind k = Kind.values()[Math.max(0, Math.min(Kind.values().length - 1, i))];
            host.draft().setKind(index, k);
            host.structure();
        }, ctx);
        item = new StepField(theme, "", StepField.Lead.ITEM, id -> host.item(id) != null, null);
        item.setOnChange(t -> put(row != null && row.kind == Kind.GOTO_BLOCK ? "block" : "item", t.trim()));
        secs = new StepField(theme, "5", StepField.Lead.NONE, null, null);
        secs.edit().setCharFilter(ch -> (ch >= '0' && ch <= '9') || ch == '.');
        secs.edit().setMaxLength(6);
        secs.setOnChange(t -> put("seconds", t.trim()));
        raw = new StepField(theme, "Any command, like farm 30", StepField.Lead.HASH, null, null);
        raw.setOnChange(t -> put("line", t));
        String[] keys = {"x", "y", "z"};
        for (int k = 0; k < 3; k++) {
            String key = keys[k];
            xyz[k] = new StepField(theme, key.toUpperCase(java.util.Locale.ROOT), StepField.Lead.NONE, null, null);
            xyz[k].edit().setCharFilter(ch -> (ch >= '0' && ch <= '9') || ch == '-' || ch == '+' || ch == '~');
            xyz[k].edit().setMaxLength(10);
            xyz[k].setOnChange(t -> put(key, t.trim()));
        }
        waypoint = new Dropdown(theme, List.of("Pick a waypoint"), 0, i -> {
            if (row == null || i < 0 || i >= wpOptions.size()) {
                return;
            }
            String v = wpOptions.get(i);
            row.f.put("waypoint", host.waypoints().contains(v) || v.equals(row.get("waypoint")) ? v : "");
            host.edited();
        }, ctx);
        up = new IconButton(theme, Icon.CHEVRON_UP, "Move up", () -> moveBy(-1));
        down = new IconButton(theme, Icon.CHEVRON_DOWN, "Move down", () -> moveBy(1));
        bin = new Bin(theme, "Delete step", () -> {
            host.draft().remove(index);
            host.structure();
        });
        for (IconButton b : new IconButton[]{up, down, bin}) {
            b.setFocusable(false);
        }
        add = new StepField(theme, "Add an item, like diamond_pickaxe", StepField.Lead.SEARCH, null, t -> {
            if (row == null || t.isBlank()) {
                return;
            }
            RoutineDraft.addItem(row, t);
            host.structure();
            // the rebuild kept this box (it is the same row widget), so it stays focused for the next item
            addField().setText("");
        });
    }

    private StepField addField() {
        return add;
    }

    private void put(String key, String value) {
        if (row != null) {
            row.f.put(key, value);
            host.edited();
        }
    }

    private void moveBy(int dir) {
        host.draft().move(index, dir);
        host.structure();
    }

    // ----- binding

    void bind(Row r, int idx, boolean isLast) {
        boolean sameRow = r == row;
        row = r;
        index = idx;
        last = isLast;
        type.setSelected(r.kind.ordinal());
        if (!sameRow) {
            // a recycled widget showing another step must not keep somebody's caret
            for (StepField f : allFields()) {
                f.setFocused(false);
            }
        }
        item.setPlaceholder(r.kind == Kind.GOTO_BLOCK ? "Block, like crafting_table" : "Item to hold, like diamond_sword");
        item.show(r.kind == Kind.GOTO_BLOCK ? r.get("block") : r.get("item"));
        secs.show(r.get("seconds"));
        raw.show(r.get("line"));
        xyz[0].show(r.get("x"));
        xyz[1].show(r.get("y"));
        xyz[2].show(r.get("z"));
        bindWaypoints();
        while (lines.size() < r.items.size()) {
            lines.add(new BasketLine(theme, basketOwner, true));
        }
        while (lines.size() > r.items.size()) {
            lines.remove(lines.size() - 1).unfocus();
        }
        for (int i = 0; i < r.items.size(); i++) {
            lines.get(i).bind(r.items.get(i), i);
        }
        boolean locked = host.locked();
        for (StepField f : allFields()) {
            f.setEditable(!locked);
        }
        up.setEnabled(idx > 0);
        down.setEnabled(!isLast);
        rebuildKids();
    }

    // the routine's waypoint stays in the list even when this world doesn't have it, so opening a step never
    // quietly swaps it for another one
    private void bindWaypoints() {
        String cur = row == null ? "" : row.get("waypoint");
        List<String> opts = waypointOptions(cur, host.waypoints());
        wpOptions = opts;
        int sel = cur.isEmpty() ? 0 : Math.max(0, opts.indexOf(cur));
        waypoint.setOptions(opts, sel);
    }

    static List<String> waypointOptions(String cur, List<String> known) {
        List<String> opts = new ArrayList<>();
        if (cur.isEmpty()) {
            opts.add("Pick a waypoint");
        } else if (!known.contains(cur)) {
            opts.add(cur);
        }
        opts.addAll(known);
        return opts;
    }

    void setState(String error, Mark m, String failMessage) {
        why = error;
        mark = m == null ? Mark.NONE : m;
        failMsg = failMessage;
        boolean bad = error != null;
        item.setInvalid(bad);
        secs.setInvalid(bad);
        raw.setInvalid(bad);
        add.setInvalid(bad && row != null && row.items.isEmpty());
        // a coordinate box only goes red when it's the one that is wrong, the other two are probably fine
        for (int k = 0; k < 3; k++) {
            String v = xyz[k].text().trim();
            boolean ok = COORD.matcher(v).matches();
            xyz[k].setInvalid(bad && !ok);
        }
        if (bad && row != null && row.kind == Kind.GOTO_COORDS && !xyz[0].invalid() && !xyz[1].invalid() && !xyz[2].invalid()) {
            for (StepField f : xyz) {
                f.setInvalid(true);
            }
        }
        for (BasketLine l : lines) {
            l.setRed(bad);
        }
    }

    String why() {
        return why;
    }

    Mark mark() {
        return mark;
    }

    private List<StepField> allFields() {
        return List.of(item, secs, raw, xyz[0], xyz[1], xyz[2], add);
    }

    private void rebuildKids() {
        kids.clear();
        if (row == null) {
            return;
        }
        kids.add(type);
        switch (row.kind) {
            case EQUIP, GOTO_BLOCK -> kids.add(item);
            case WAIT -> kids.add(secs);
            case RAW -> kids.add(raw);
            case GOTO_COORDS -> kids.addAll(List.of(xyz));
            case GOTO_WAYPOINT -> kids.add(waypoint);
            case GET -> {
                kids.addAll(lines);
                if (addShown()) {
                    kids.add(add);
                }
            }
        }
        if (!host.locked()) {
            kids.addAll(List.of(up, down, bin));
        }
    }

    // ----- sizes

    // the add box only shows on the step you're working in (or when there is nothing yet). five quiet item lines read
    // better than five item lines and a box
    private boolean addShown() {
        return row != null && row.kind == Kind.GET && !host.locked() && (row.items.isEmpty() || hovered || add.isFocused() || anyCountFocused());
    }

    private boolean anyCountFocused() {
        for (BasketLine l : lines) {
            if (l.countFocused()) {
                return true;
            }
        }
        return false;
    }

    private int basketH() {
        if (row == null || row.kind != Kind.GET) {
            return 0;
        }
        int h = row.items.size() * theme.px(LINE_H);
        if (addShown()) {
            h += theme.px(6) + theme.px(28) + theme.px(6);
        }
        return h;
    }

    private String whyText() {
        if (why != null) {
            // the rules talk in lowercase like the chat does, a line under a box starts with a capital
            return why.isEmpty() ? why : Character.toUpperCase(why.charAt(0)) + why.substring(1);
        }
        if (mark == Mark.FAIL && failMsg != null && !failMsg.isBlank()) {
            return "Last run stopped here: " + failMsg;
        }
        return null;
    }

    int heightFor() {
        int h = theme.px(PAD_Y) * 2 + theme.px(Theme.CONTROL_H);
        int b = basketH();
        if (b > 0) {
            h += theme.px(ROW_GAP) + b;
        }
        if (whyText() != null) {
            h += theme.px(ROW_GAP) + theme.px(WHY_H) - theme.px(2);
        }
        return h;
    }

    // ----- layout

    private int fieldsX() {
        return x + theme.px(4) + theme.px(IDX_W) + theme.px(Theme.GAP) + theme.px(TYPE_W) + theme.px(Theme.GAP);
    }

    // the basket hangs under the boxes like the mock, but in a narrow editor it moves left under the type picker too,
    // or "Diamond Chestplate" turns into "Diamond ..." at 720p
    private int basketX() {
        int fx = fieldsX();
        if (x + w - theme.px(Theme.GAP) - fx >= theme.px(330)) {
            return fx;
        }
        return x + theme.px(4) + theme.px(IDX_W) + theme.px(Theme.GAP);
    }

    private int actsW() {
        return theme.px(ACT) * 3 + theme.px(2) * 2;
    }

    private int fieldsR() {
        return x + w - theme.px(Theme.GAP) - actsW() - theme.px(Theme.GAP);
    }

    @Override
    protected void layout() {
        if (row == null) {
            return;
        }
        int ch = theme.px(Theme.CONTROL_H);
        int top = y + theme.px(PAD_Y);
        int gap = theme.px(Theme.GAP);
        type.setBounds(x + theme.px(4) + theme.px(IDX_W) + gap, top, theme.px(TYPE_W), ch);
        int fx = fieldsX();
        int fw = Math.max(theme.px(40), fieldsR() - fx);
        item.setBounds(fx, top, fw, ch);
        raw.setBounds(fx, top, fw, ch);
        waypoint.setBounds(fx, top, fw, ch);
        int sw = theme.px(72);
        secs.setBounds(fx, top, sw, ch);
        int cw = (fw - 2 * gap) / 3;
        for (int k = 0; k < 3; k++) {
            xyz[k].setBounds(fx + k * (cw + gap), top, k == 2 ? fw - 2 * (cw + gap) : cw, ch);
        }
        int act = theme.px(ACT);
        int ay = top + (ch - act) / 2;
        int ar = x + w - theme.px(Theme.GAP);
        bin.setBounds(ar - act, ay, act, act);
        down.setBounds(bin.x() - theme.px(2) - act, ay, act, act);
        up.setBounds(down.x() - theme.px(2) - act, ay, act, act);
        // the basket spans the boxes and the actions, like the mock's grid-column 3 / 5
        int bx = basketX();
        int bw = ar - bx;
        int by = top + ch + theme.px(ROW_GAP);
        for (int i = 0; i < lines.size(); i++) {
            lines.get(i).setBounds(bx, by + i * theme.px(LINE_H), bw, theme.px(LINE_H));
        }
        add.setBounds(bx + theme.px(6), by + lines.size() * theme.px(LINE_H) + theme.px(6), bw - 2 * theme.px(6), theme.px(28));
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        boolean was = addShown();
        Widget d = super.mouseMove(mx, my);
        if (addShown() != was) {
            rebuildKids();
            host.structureSoft();
        }
        return d;
    }

    // read only means read only: the dimmed pickers would still open, and a stepper would still step
    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (host.locked()) {
            return contains(mx, my);
        }
        return super.mouseDown(mx, my, button, mods);
    }

    @Override
    public void tick() {
        super.tick();
        // escape in a count box puts the old number back without telling anyone, so the draft follows the box
        // once it lets go of the keyboard
        for (BasketLine l : lines) {
            l.settle();
        }
        // focus moving out of the basket hides the add box again, which changes the row's height
        if (row != null && row.kind == Kind.GET && kids.contains(add) != addShown()) {
            rebuildKids();
            host.structureSoft();
        }
    }

    // ----- draw

    @Override
    public void draw(Canvas c, Frame f) {
        if (row == null) {
            return;
        }
        float r = theme.pxf(Theme.ROW_RADIUS);
        if (why != null) {
            c.fillRoundRect(x, y + 1, w, h - 2, r, Theme.fade(theme.color(Theme.DANGER_FILL), 0.55f));
        } else if (hovered) {
            c.fillRoundRect(x, y + 1, w, h - 2, r, Theme.fade(theme.color(Theme.HOVER), 0.6f));
        }
        if (!last) {
            c.fillRect(x + theme.px(Theme.SPACE_2), y + h - 1, w - 2 * theme.px(Theme.SPACE_2), 1, theme.color(Theme.HAIRLINE));
        }
        drawIndex(c);
        boolean locked = host.locked();
        // a picker you can't open is dimmed, like a disabled control everywhere else
        Canvas dim = locked ? fade.reset(c, 0.4f, 0f) : c;
        type.draw(dim, f);
        int ch = theme.px(Theme.CONTROL_H);
        int top = y + theme.px(PAD_Y);
        switch (row.kind) {
            case EQUIP, GOTO_BLOCK -> item.draw(c, f);
            case RAW -> raw.draw(c, f);
            case GOTO_COORDS -> {
                for (StepField s : xyz) {
                    s.draw(c, f);
                }
            }
            case GOTO_WAYPOINT -> {
                waypoint.draw(dim, f);
                if (why != null) {
                    c.strokeRoundRect(waypoint.x(), waypoint.y(), waypoint.w(), waypoint.h(), theme.pxf(Theme.CONTROL_RADIUS), theme.color(Theme.DANGER));
                }
            }
            case WAIT -> {
                secs.draw(c, f);
                float ux = secs.x() + secs.w() + theme.px(Theme.GAP);
                c.text("seconds", ux, mid(c, theme.body, top, ch), theme.body, theme.color(Theme.TEXT_2));
            }
            case GET -> {
                int n = row.items.size();
                String sum = n == 0 ? "No items yet" : n + (n == 1 ? " item" : " items") + " · " + row.itemTotal() + " total";
                TextStyle s = theme.small.asTabular();
                c.text(c.ellipsize(sum, s, Math.max(0, fieldsR() - fieldsX())), fieldsX(), mid(c, s, top, ch), s, theme.color(Theme.TEXT_3));
                drawBasket(c, f);
            }
        }
        if (!locked) {
            // up and down only show on the row you're on, the bin is always there and only goes red under the pointer
            boolean hot = hovered || focusWithin();
            if (hot) {
                up.draw(c, f);
                down.draw(c, f);
            }
            bin.draw(c, f);
        }
        String wt = whyText();
        if (wt != null) {
            TextStyle s = theme.small;
            float wy = y + h - theme.px(PAD_Y) - theme.px(WHY_H);
            float room = x + w - theme.px(Theme.GAP) - fieldsX();
            c.text(c.ellipsize(wt, s, Math.max(0, room)), fieldsX(), wy + c.ascent(s), s, theme.color(Theme.DANGER));
        }
    }

    private boolean focusWithin() {
        for (Widget k : kids) {
            if (k.isFocused()) {
                return true;
            }
        }
        return anyCountFocused();
    }

    private void drawIndex(Canvas c) {
        int ch = theme.px(Theme.CONTROL_H);
        float cx = x + theme.px(4) + theme.px(IDX_W) / 2f;
        float cy = y + theme.px(PAD_Y) + ch / 2f;
        switch (mark) {
            case NOW -> {
                int s = theme.px(12);
                c.icon(Icon.DOT, cx - s / 2f, cy - s / 2f, s, theme.color(Theme.ACCENT));
            }
            case DONE -> {
                int s = theme.px(Theme.ICON);
                c.icon(Icon.CHECK, cx - s / 2f, cy - s / 2f, s, theme.color(Theme.TEXT_3));
            }
            case FAIL -> {
                int s = theme.px(Theme.ICON);
                c.icon(Icon.WARNING, cx - s / 2f, cy - s / 2f, s, theme.color(Theme.DANGER));
            }
            case NONE -> {
                TextStyle s = theme.smallLabel.asTabular();
                String n = String.valueOf(index + 1);
                float tw = c.measure(n, s);
                c.text(n, cx - tw / 2f, mid(c, s, y + theme.px(PAD_Y), ch), s, theme.color(Theme.TEXT_3));
            }
        }
    }

    // the basket: a hairline box under the row, lines with a hairline between them, the add box at the bottom
    private void drawBasket(Canvas c, Frame f) {
        int b = basketH();
        if (b <= 0) {
            return;
        }
        int by = y + theme.px(PAD_Y) + theme.px(Theme.CONTROL_H) + theme.px(ROW_GAP);
        int bx = basketX();
        int bw = x + w - theme.px(Theme.GAP) - bx;
        c.strokeRoundRect(bx, by, bw, b, theme.pxf(Theme.ROW_RADIUS), theme.color(Theme.HAIRLINE));
        boolean locked = host.locked();
        for (BasketLine l : lines) {
            l.drawLine(c, f, locked);
        }
        if (kids.contains(add)) {
            add.draw(c, f);
        }
    }

    private static float mid(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }

    // the bin sits in TEXT_3 and only goes red when you're actually on it, same as the get basket's
    private final class BasketOwner implements BasketLine.Owner {
        @Override
        public Row row() {
            return row;
        }

        @Override
        public boolean red() {
            return why != null;
        }

        @Override
        public boolean locked() {
            return host.locked();
        }

        @Override
        public ItemEntry item(String id) {
            return host.item(id);
        }

        @Override
        public void edited() {
            host.edited();
        }

        @Override
        public void structure() {
            host.structure();
        }

        @Override
        public void blur() {
            host.ctx().blur();
        }
    }

    static final class Bin extends IconButton {

        Bin(Theme theme, String tip, Runnable onClick) {
            super(theme, Icon.TRASH, tip, onClick);
        }

        @Override
        protected void drawContent(Canvas c, int col, float hv) {
            float is = iconSize();
            int tint = Theme.lerp(theme.color(Theme.TEXT_3), theme.color(Theme.DANGER), hv);
            c.icon(icon, x + (w - is) / 2f, y + (h - is) / 2f, is, tint);
        }
    }
}
