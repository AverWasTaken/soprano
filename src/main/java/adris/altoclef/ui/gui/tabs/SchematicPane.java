package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SchematicsAccess.BuildState;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Building;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Footprint;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Line;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Materials;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Placement;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Row;
import adris.altoclef.ui.gui.facade.SchematicsAccess.State;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Terrain;
import adris.altoclef.ui.gui.facade.WorldAccess.Position;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// the right hand card: the selected file's name and numbers on top, the placement map and its controls under that,
// the material list in whatever is left, the build bar at the bottom. the tab owns the data and tells this what to
// show; every button and the drag go back to the tab through the listener
final class SchematicPane extends Widget {

    interface Listener {
        void build();

        // world blocks, mods of the click (shift moves eight)
        void move(int dx, int dy, int dz, int mods);

        void rotate(boolean clockwise);

        void mirror();

        void placeAtFeet();
    }

    private static final String CANT_PLACE = "Can't place";
    private static final String NOT_COUNTED = "Not counted";
    private static final String BUILD = "Build here";

    private enum Body { NOTHING, LOADING, ERROR, LIST }

    private final VirtualList<MaterialRows.Entry> list;
    private final Button build;
    private final PlacementMap map;
    private final IconButton west;
    private final IconButton north;
    private final IconButton south;
    private final IconButton east;
    private final IconButton up;
    private final IconButton down;
    private final IconButton turnLeft;
    private final IconButton turnRight;
    private final Button mirror;
    private final Button feet;
    private final List<Widget> kids;
    private final List<Button> nudges;
    private final Set<String> open = new HashSet<>();

    private Row row;
    private Materials materials;
    private boolean world = true;
    private boolean armed;
    private Body body = Body.NOTHING;
    private PlacementMap.Mode mode = PlacementMap.Mode.HIDDEN;
    private boolean controlsShown;
    private Building building = Building.IDLE;
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

    SchematicPane(Theme theme, Listener listener) {
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
        build = new Button(theme, BUILD, Button.Kind.PRIMARY, null, listener::build);
        build.setEnabled(false);
        map = new PlacementMap(theme, (dx, dz) -> listener.move(dx, 0, dz, 0));
        west = nudge(Icon.CHEVRON_LEFT, "West", listener, -1, 0, 0);
        north = nudge(Icon.CHEVRON_UP, "North", listener, 0, 0, -1);
        south = nudge(Icon.CHEVRON_DOWN, "South", listener, 0, 0, 1);
        east = nudge(Icon.CHEVRON_RIGHT, "East", listener, 1, 0, 0);
        up = nudge(Icon.LEVEL_UP, "Up one", listener, 0, 1, 0);
        down = nudge(Icon.LEVEL_DOWN, "Down one", listener, 0, -1, 0);
        turnLeft = new IconButton(theme, Icon.ROTATE_LEFT, "Turn left", () -> listener.rotate(false));
        turnRight = new IconButton(theme, Icon.ROTATE_RIGHT, "Turn right", () -> listener.rotate(true));
        mirror = new Squarish(theme, "Mirror", Icon.FLIP, listener::mirror);
        feet = new Squarish(theme, "Move to me", Icon.PIN, listener::placeAtFeet);
        nudges = List.of(west, north, south, east);
        kids = List.of(list, build, map, west, north, south, east, up, down, turnLeft, turnRight, mirror, feet);
        setVisibleControls(false);
    }

    private IconButton nudge(Icon icon, String tip, Listener l, int dx, int dy, int dz) {
        IconButton[] self = new IconButton[1];
        self[0] = new IconButton(theme, icon, tip, () -> l.move(dx, dy, dz, self[0].lastMods()));
        return self[0];
    }

    // a ghost button with a glyph and a word that drops the word when the row is tight: then it is a square like the
    // icon buttons next to it, not a square with a glyph left of nothing, and the word moves to the tooltip
    private static final class Squarish extends Button {
        private final String word;

        Squarish(Theme theme, String text, Icon icon, Runnable onClick) {
            super(theme, text, Kind.GHOST, icon, onClick);
            this.word = text;
        }

        @Override
        public int preferredWidth() {
            return text.isEmpty() ? theme.px(Theme.CONTROL_H) : super.preferredWidth();
        }

        // the word while it is not on the button, null while it is
        String tooltip() {
            return text.isEmpty() ? word : null;
        }
    }

    // what the control under the pointer would say in a tooltip, null when there is no control there or it says
    // its own name already. the tab draws it with the same card it uses for the list rows
    String tipAt(float mx, float my) {
        for (Button b : controls()) {
            if (!b.visible() || !b.contains(mx, my)) {
                continue;
            }
            if (b instanceof IconButton ib) {
                return ib.tooltip();
            }
            if (b instanceof Squarish sq) {
                return sq.tooltip();
            }
        }
        return null;
    }

    // the tab calls this whenever the selection, the count or the player's inventory moved. a null row is nothing selected
    void show(Row r, Materials m, boolean hasWorld) {
        boolean changed = !sameRow(r) || (m == null ? materials != null : !m.equals(materials)) || hasWorld != world;
        if (!sameRow(r)) {
            map.cancelDrag();
        }
        row = r;
        materials = m;
        world = hasWorld;
        if (changed) {
            rebuild();
        }
    }

    // and this every tick with where the file goes: the map band, the controls and the build bar follow it. terrain
    // and player may be null, the building state is the last request whatever file it was for
    void site(Placement placement, Footprint footprint, Terrain terrain, Position player, float yaw, Building b) {
        PlacementMap.Mode next;
        if (row == null || !row.ok()) {
            next = PlacementMap.Mode.HIDDEN;
        } else if (!world || placement == null) {
            next = PlacementMap.Mode.NO_WORLD;
        } else if (!placement.inThisDimension()) {
            next = PlacementMap.Mode.ELSEWHERE;
        } else {
            next = PlacementMap.Mode.MAP;
        }
        Building mine = b != null && row != null && row.relativePath().equals(b.relativePath()) ? b : Building.IDLE;
        boolean relayout = (next == PlacementMap.Mode.HIDDEN) != (mode == PlacementMap.Mode.HIDDEN);
        boolean refresh = next != mode || !mine.equals(building);
        mode = next;
        building = mine;
        map.show(mode, placement, footprint, terrain, player, yaw);
        if (refresh) {
            boolean onMap = mode == PlacementMap.Mode.MAP;
            for (Button n : nudges) {
                n.setEnabled(onMap);
            }
            up.setEnabled(onMap);
            down.setEnabled(onMap);
            turnLeft.setEnabled(onMap);
            turnRight.setEnabled(onMap);
            mirror.setEnabled(onMap);
            feet.setEnabled(onMap || mode == PlacementMap.Mode.ELSEWHERE);
            updateBuildBar();
        }
        if (relayout) {
            layout();
        }
    }

    private boolean sameRow(Row r) {
        return r == null ? row == null : r.equals(row);
    }

    void setArmed(boolean on) {
        armed = on;
        build.setText(on ? "Click again to build" : BUILD);
        build.setKind(on ? Button.Kind.DANGER : Button.Kind.PRIMARY);
        layout();
    }

    boolean armed() {
        return armed;
    }

    private void rebuild() {
        List<MaterialRows.Entry> entries = new ArrayList<>();
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
                fill(entries);
            }
        }
        list.setItems(entries);
        updateBuildBar();
        layout();
    }

    // the button waits for the count and for a placement in this dimension. the line next to it says what is in
    // the way, or what you are short of, or how the last build request is going
    private void updateBuildBar() {
        boolean inFlight = building.state() == BuildState.PARSING;
        build.setEnabled(body == Body.LIST && world && mode == PlacementMap.Mode.MAP && !inFlight);
        warningIcon = false;
        if (!world) {
            warning = "Join a world to build";
        } else if (building.state() != BuildState.IDLE) {
            warning = SchematicRules.buildingLine(building);
        } else if (mode == PlacementMap.Mode.ELSEWHERE) {
            warning = "Placed in " + SchematicRules.dimensionName(map.elsewhere()) + ". Move it here first.";
        } else if (body == Body.LOADING) {
            warning = "Counting blocks…";
        } else if (body == Body.ERROR && row != null && row.ok()) {
            warning = "Couldn't count the blocks";
        } else if (body == Body.LIST) {
            warning = SchematicRules.warning(materials);
            warningIcon = !warning.isEmpty();
        } else {
            warning = "";
        }
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

    // the controls band: one row of buttons, or two when the card is too narrow for one, 4 px above and below each
    private int controlsH(int rows) {
        return theme.px(Theme.SPACE_1 * (rows + 1) + Theme.CONTROL_H * rows);
    }

    private boolean showsMap() {
        return body != Body.NOTHING && mode != PlacementMap.Mode.HIDDEN;
    }

    private void setVisibleControls(boolean on) {
        controlsShown = on;
        for (Widget k : kids) {
            if (k != list && k != build && k != map) {
                k.setVisible(on);
            }
        }
    }

    // header, map band, controls, list, build bar, hairlines between. the map takes half of what the fixed parts
    // leave, 48 to 176, and the list gets the rest: a short window ends up with a small map and a row or two of
    // materials, which beats either one vanishing. a window too short even for that loses the controls first (the
    // footprint still drags) and the band after
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
        int side = theme.px(1);
        int top = y + headerH + 1;
        int footTop = y + h - footerH();
        boolean showMap = showsMap();
        map.setVisible(showMap);
        setVisibleControls(false);
        listTop = top;
        if (showMap) {
            // the control rows sit on the list's inset, not the header's: at the 640 floor the pane is about 160
            // px and those 16 px are what keep "Move to me" on its button
            int inset = theme.px(Theme.GAP);
            int rows = controlRows(inset);
            int room = footTop - 1 - top - controlsH(rows) - 2;
            boolean controls = room >= 0;
            if (!controls) {
                room = footTop - 1 - top - 1;
            }
            int mapH = Math.max(theme.px(48), Math.min(theme.px(176), Math.round(room * 0.5f)));
            if (mapH > room) {
                mapH = Math.max(0, room);
            }
            map.setVisible(mapH > 0);
            map.setBounds(x + side, top, w - 2 * side, mapH);
            listTop = top + mapH + 1;
            if (controls) {
                setVisibleControls(true);
                layoutControls(inset, listTop + theme.px(Theme.SPACE_1), rows);
                listTop += controlsH(rows) + 1;
            }
        }
        listH = Math.max(0, footTop - 1 - listTop);
        list.setBounds(x + side, listTop, w - 2 * side, listH);
        int bw = build.preferredWidth();
        build.setBounds(x + w - pad - bw, y + h - theme.px(Theme.SPACE_3) - bh, bw, bh);
    }

    // how many rows the controls take at this width, and the words that survive. one row with both words, one row
    // with mirror as a glyph (the flip glyph reads on its own, a lone pin does not, and move to me is the way out of
    // the other-dimension state), then two rows: the arrows and the layer buttons on the first, the turns, mirror
    // and move to me on the second, mirror's word going first. at the 640 floor the pane is about 215 design px
    // and that second row is still too long, so there it is three: move to me on a row of its own, word and all.
    // nothing is ever dropped, every button is reachable at the floor; the word on move to me only goes below it
    private int controlRows(int pad) {
        int ch = theme.px(Theme.CONTROL_H);
        int gap = theme.px(Theme.SPACE_1);
        int avail = w - 2 * pad;
        mirror.setText("Mirror");
        feet.setText("Move to me");
        if (oneRow(ch, gap) <= avail) {
            return 1;
        }
        mirror.setText("");
        if (oneRow(ch, gap) <= avail) {
            return 1;
        }
        mirror.setText("Mirror");
        if (secondRow(ch, gap) <= avail) {
            return 2;
        }
        mirror.setText("");
        if (secondRow(ch, gap) <= avail) {
            return 2;
        }
        mirror.setText("Mirror");
        if (2 * ch + mirror.preferredWidth() > avail) {
            mirror.setText("");
        }
        if (feet.preferredWidth() > avail) {
            feet.setText("");
        }
        return 3;
    }

    private int oneRow(int ch, int gap) {
        return 4 * ch + gap + 2 * ch + gap + secondRow(ch, gap);
    }

    private int secondRow(int ch, int gap) {
        return 2 * ch + mirror.preferredWidth() + gap + feet.preferredWidth();
    }

    private void layoutControls(int pad, int top, int rows) {
        int ch = theme.px(Theme.CONTROL_H);
        int gap = theme.px(Theme.SPACE_1);
        int cx = x + pad;
        for (Button n : nudges) {
            n.setBounds(cx, top, ch, ch);
            cx += ch;
        }
        cx += gap;
        up.setBounds(cx, top, ch, ch);
        cx += ch;
        down.setBounds(cx, top, ch, ch);
        if (rows > 1) {
            top += ch + gap;
            cx = x + pad;
        } else {
            cx += ch + gap;
        }
        turnLeft.setBounds(cx, top, ch, ch);
        cx += ch;
        turnRight.setBounds(cx, top, ch, ch);
        cx += ch;
        mirror.setBounds(cx, top, mirror.preferredWidth(), ch);
        if (rows > 2) {
            top += ch + gap;
        }
        int fw = feet.preferredWidth();
        feet.setBounds(x + w - pad - fw, top, fw, ch);
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        theme.card(c, x, y, w, h, theme.pxf(Theme.CARD_RADIUS), false);
        int pad = theme.px(Theme.PAD);
        int hairline = theme.color(Theme.HAIRLINE);
        if (body != Body.NOTHING) {
            drawHeader(c, pad);
            c.fillRect(x + 1, y + headerH, w - 2, 1, hairline);
        }
        if (map.visible()) {
            map.draw(c, f);
            c.fillRect(x + 1, map.y() + map.h(), w - 2, 1, hairline);
        }
        if (controlsShown) {
            for (Widget k : kids) {
                if (k != list && k != build && k != map) {
                    k.draw(c, f);
                }
            }
            c.fillRect(x + 1, listTop - 1, w - 2, 1, hairline);
        }
        if (body == Body.LIST) {
            list.draw(c, f);
        } else {
            drawState(c);
        }
        int footTop = y + h - footerH();
        c.fillRect(x + 1, footTop, w - 2, 1, hairline);
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
        if (listH <= 0) {
            return;
        }
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
        boolean failed = building.state() == BuildState.ERROR;
        int is = theme.px(Theme.ICON);
        float tx = x + pad;
        float base = build.y() + build.h() / 2f + (c.ascent(theme.small) - c.descent(theme.small)) / 2f;
        if (danger) {
            c.icon(Icon.WARNING, tx, build.y() + (build.h() - is) / 2f, is, theme.color(Theme.DANGER));
            tx += is + theme.px(6);
        }
        float room = build.x() - theme.px(Theme.GAP) - tx;
        if (room > theme.px(30)) {
            int col = failed ? Theme.DANGER : danger ? Theme.TEXT_2 : Theme.TEXT_3;
            c.text(c.ellipsize(warning, theme.small, room), tx, base, theme.small, theme.color(col));
        }
    }

    // test hooks
    VirtualList<MaterialRows.Entry> list() {
        return list;
    }

    Button buildButton() {
        return build;
    }

    PlacementMap map() {
        return map;
    }

    List<Button> controls() {
        return List.of(west, north, south, east, up, down, turnLeft, turnRight, mirror, feet);
    }

    Button control(String name) {
        return switch (name) {
            case "west" -> west;
            case "north" -> north;
            case "south" -> south;
            case "east" -> east;
            case "up" -> up;
            case "down" -> down;
            case "turnLeft" -> turnLeft;
            case "turnRight" -> turnRight;
            case "mirror" -> mirror;
            default -> feet;
        };
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
