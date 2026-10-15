package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Line;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Materials;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Row;
import adris.altoclef.ui.gui.facade.SchematicsAccess.State;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// the right hand card: the selected file's name and numbers on top, its material list in the middle, the build bar
// at the bottom. the list takes whatever height is left, which is also where a placement map will go one day: it
// shares that room, nothing reserves a blank box for it now. the tab owns the data and tells this what to show
final class SchematicPane extends Widget {

    private static final String CANT_PLACE = "Can't place";
    private static final String NOT_COUNTED = "Not counted";

    private enum Body { NOTHING, LOADING, ERROR, LIST }

    private final VirtualList<MaterialRows.Entry> list;
    private final Button build;
    private final List<Widget> kids;
    private final Set<String> open = new HashSet<>();

    private Row row;
    private Materials materials;
    private boolean world = true;
    private boolean armed;
    private Body body = Body.NOTHING;
    private String error = "";
    private String name = "";
    private String meta = "";
    private String summaryHead = "";
    private String summaryTail = "";
    private boolean summaryShort;
    private String unknown = "";
    private String warning = "";
    // the glyph only goes with a real shortfall, the quiet lines (counting, no world) do without
    private boolean warningIcon;
    private int headerH;
    private int listTop;
    private int listH;

    SchematicPane(Theme theme, Runnable onBuild) {
        super(theme);
        list = new VirtualList<>(theme, theme.px(40), new MaterialRows(theme));
        list.setKeyFunction(MaterialRows.Entry::key);
        list.onClick((e, i) -> {
            if (e.type() == MaterialRows.Type.SECTION) {
                if (!open.remove(e.label())) {
                    open.add(e.label());
                }
                rebuild();
            }
        });
        build = new Button(theme, "Build at my feet", Button.Kind.PRIMARY, null, onBuild);
        build.setEnabled(false);
        kids = List.of(list, build);
    }

    // the tab calls this whenever the selection, the count or the player's inventory moved. a null row is nothing selected
    void show(Row r, Materials m, boolean hasWorld) {
        boolean changed = !sameRow(r) || (m == null ? materials != null : !m.equals(materials)) || hasWorld != world;
        row = r;
        materials = m;
        world = hasWorld;
        if (changed) {
            rebuild();
        }
    }

    private boolean sameRow(Row r) {
        return r == null ? row == null : r.equals(row);
    }

    void setArmed(boolean on) {
        armed = on;
        build.setText(on ? "Click again to build" : "Build at my feet");
        build.setKind(on ? Button.Kind.DANGER : Button.Kind.PRIMARY);
        layout();
    }

    boolean armed() {
        return armed;
    }

    private void rebuild() {
        List<MaterialRows.Entry> entries = new ArrayList<>();
        warning = "";
        unknown = "";
        summaryTail = "";
        summaryShort = false;
        if (row == null) {
            body = Body.NOTHING;
            name = "";
            meta = "";
            summaryHead = "";
        } else {
            name = row.name();
            meta = SchematicRules.meta(row);
            unknown = row.unknownSummary() == null ? "" : row.unknownSummary();
            if (!row.ok()) {
                body = Body.ERROR;
                error = row.error();
                summaryHead = "Didn't parse";
            } else if (materials == null || materials.state() == State.LOADING) {
                body = Body.LOADING;
                summaryHead = "Counting blocks…";
            } else if (materials.error() != null) {
                body = Body.ERROR;
                error = materials.error();
                summaryHead = "Couldn't count the blocks";
            } else {
                body = Body.LIST;
                summaryHead = SchematicRules.summaryHead(materials);
                summaryTail = SchematicRules.summaryTail(materials);
                summaryShort = materials.totalMissing() > 0;
                warning = SchematicRules.warning(materials);
                fill(entries);
            }
        }
        list.setItems(entries);
        // the button waits for the count: the warning line next to it is what tells you what you are short of
        build.setEnabled(body == Body.LIST && world);
        warningIcon = !warning.isEmpty();
        if (!world) {
            warning = "Join a world to build";
            warningIcon = false;
        } else if (body == Body.LOADING) {
            warning = "Counting blocks…";
        } else if (body == Body.ERROR && row != null && row.ok()) {
            warning = "Couldn't count the blocks";
        }
        layout();
    }

    private void fill(List<MaterialRows.Entry> out) {
        for (Line l : materials.items()) {
            out.add(MaterialRows.Entry.item(l));
        }
        section(out, CANT_PLACE, materials.cantPlace());
        section(out, NOT_COUNTED, materials.notCounted());
        for (String n : materials.notes()) {
            out.add(MaterialRows.Entry.note(n));
        }
    }

    private void section(List<MaterialRows.Entry> out, String label, List<Line> lines) {
        if (lines.isEmpty()) {
            return;
        }
        boolean isOpen = open.contains(label);
        out.add(MaterialRows.Entry.section(label, lines.size(), isOpen));
        if (isOpen) {
            for (Line l : lines) {
                out.add(MaterialRows.Entry.sub(l));
            }
        }
    }

    private int footerH() {
        return theme.px(Theme.SPACE_3 * 2 + Theme.CONTROL_H);
    }

    @Override
    protected void layout() {
        int pad = theme.px(Theme.PAD);
        int bh = theme.px(Theme.CONTROL_H);
        var m = theme.measurer;
        if (body == Body.NOTHING) {
            headerH = 0;
        } else {
            float hh = theme.px(Theme.SPACE_3) + m.lineHeight(theme.title) + theme.px(2) + m.lineHeight(theme.small)
                    + theme.px(Theme.SPACE_2) + m.lineHeight(theme.body) + theme.px(Theme.SPACE_3);
            if (!unknown.isEmpty()) {
                hh += theme.px(2) + m.lineHeight(theme.small);
            }
            headerH = Math.round(hh);
        }
        listTop = y + headerH + 1;
        listH = Math.max(0, h - headerH - 1 - footerH() - 1);
        int side = theme.px(1);
        list.setBounds(x + side, listTop, w - 2 * side, listH);
        int bw = build.preferredWidth();
        build.setBounds(x + w - pad - bw, y + h - theme.px(Theme.SPACE_3) - bh, bw, bh);
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        theme.card(c, x, y, w, h, theme.pxf(Theme.CARD_RADIUS), false);
        int pad = theme.px(Theme.PAD);
        if (body != Body.NOTHING) {
            drawHeader(c, pad);
            c.fillRect(x + 1, y + headerH, w - 2, 1, theme.color(Theme.HAIRLINE));
        }
        if (body == Body.LIST) {
            list.draw(c, f);
        } else {
            drawState(c);
        }
        int footTop = y + h - footerH();
        c.fillRect(x + 1, footTop, w - 2, 1, theme.color(Theme.HAIRLINE));
        drawWarning(c, pad);
        build.draw(c, f);
    }

    private void drawHeader(Canvas c, int pad) {
        float tx = x + pad;
        float room = Math.max(0, w - 2 * pad);
        float yy = y + theme.px(Theme.SPACE_3);
        c.text(c.ellipsize(name, theme.title, room), tx, yy + c.ascent(theme.title), theme.title, theme.color(Theme.TEXT));
        yy += c.lineHeight(theme.title) + theme.px(2);
        TextStyle small = theme.small.asTabular();
        c.text(c.ellipsize(meta, small, room), tx, yy + c.ascent(small), small, theme.color(Theme.TEXT_3));
        yy += c.lineHeight(small) + theme.px(Theme.SPACE_2);
        TextStyle body = theme.body.asTabular();
        String head = c.ellipsize(summaryHead, body, room);
        float adv = c.text(head, tx, yy + c.ascent(body), body, theme.color(Theme.TEXT_2));
        if (!summaryTail.isEmpty() && head.equals(summaryHead) && room - adv > 0) {
            c.text(c.ellipsize(summaryTail, body, room - adv), tx + adv, yy + c.ascent(body), body,
                    theme.color(summaryShort ? Theme.DANGER : Theme.TEXT_2));
        }
        if (!unknown.isEmpty()) {
            yy += c.lineHeight(body) + theme.px(2);
            c.text(c.ellipsize(unknown, theme.small, room), tx, yy + c.ascent(theme.small), theme.small, theme.color(Theme.DANGER));
        }
    }

    // nothing selected, counting, or a file that didn't open: two centred lines in the list's place
    private void drawState(Canvas c) {
        String lead;
        String rest;
        int leadCol = Theme.TEXT_2;
        switch (body) {
            case LOADING -> {
                lead = "Counting blocks…";
                rest = "";
            }
            case ERROR -> {
                lead = error;
                rest = "Re-export it from the mod that made it, or try Refresh.";
                leadCol = Theme.DANGER;
            }
            default -> {
                lead = "Pick a schematic";
                rest = "Its block list and the build button show here.";
            }
        }
        float room = Math.max(theme.px(80), w - 2 * theme.px(32));
        List<String> lines = new ArrayList<>(HelpList.wrap(c, lead, theme.small, room));
        int leadLines = lines.size();
        if (!rest.isEmpty()) {
            lines.addAll(HelpList.wrap(c, rest, theme.small, room));
        }
        float lh = c.lineHeight(theme.small) + theme.px(2);
        float top = listTop + (listH - lines.size() * lh) / 2f;
        c.pushClip(x, listTop, w, listH);
        try {
            for (int i = 0; i < lines.size(); i++) {
                String s = lines.get(i);
                float tw = c.measure(s, theme.small);
                c.text(s, x + (w - tw) / 2f, top + i * lh + c.ascent(theme.small), theme.small,
                        theme.color(i < leadLines ? leadCol : Theme.TEXT_3));
            }
        } finally {
            c.popClip();
        }
    }

    private void drawWarning(Canvas c, int pad) {
        if (warning.isEmpty()) {
            return;
        }
        boolean danger = warningIcon;
        int is = theme.px(Theme.ICON);
        float tx = x + pad;
        float base = build.y() + build.h() / 2f + (c.ascent(theme.small) - c.descent(theme.small)) / 2f;
        if (danger) {
            c.icon(Icon.WARNING, tx, build.y() + (build.h() - is) / 2f, is, theme.color(Theme.DANGER));
            tx += is + theme.px(6);
        }
        float room = build.x() - theme.px(Theme.GAP) - tx;
        if (room > theme.px(30)) {
            c.text(c.ellipsize(warning, theme.small, room), tx, base, theme.small, theme.color(danger ? Theme.TEXT_2 : Theme.TEXT_3));
        }
    }

    // test hooks
    VirtualList<MaterialRows.Entry> list() {
        return list;
    }

    Button buildButton() {
        return build;
    }

    String summary() {
        return summaryHead + summaryTail;
    }

    String warning() {
        return warning;
    }

    String errorText() {
        return body == Body.ERROR ? error : null;
    }

    boolean showsList() {
        return body == Body.LIST;
    }

    boolean loading() {
        return body == Body.LOADING;
    }

    Set<String> openSections() {
        return open;
    }
}
