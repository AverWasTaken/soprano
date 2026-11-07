package adris.altoclef.ui.gui.hud;

import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.BrandArt;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.FadeCanvas;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.PixelImage;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShapeAtlas;
import adris.altoclef.ui.gui.kit.TextMeasurer;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Weight;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// the task tree in the top left, as gui-mock/hud draws it (hud.css .status): a glass panel, one row per task with its
// icon, each child hanging off its parent's icon by a little elbow line, a progress bar under the leaf when its state
// is a count, a two line compact version when there's no room for the tree, and an "Idle" pill for a few seconds after
// the last task ends. no minecraft in here: mc/StatusPanelHud feeds it rows and a room, the previews do the same on a
// Java2dCanvas. everything is laid out in whole device px so the 1 px lines stay 1 px
public final class StatusPanel {

    public enum Role {
        ROOT,   // the chain name or the root task, what you asked for
        MID,
        LEAF,   // the thing actually happening
        ONLY,   // root and leaf at once
        MORE    // the fold, "3 more tasks"
    }

    public enum Mode {
        NONE,
        TREE,
        COMPACT,
        IDLE
    }

    public record Row(String name, String state, Role role, Icon icon) {
    }

    // what got laid out: the fitted words and where they go
    record Laid(int depth, String name, String meta, Role role, Icon icon) {
    }

    // one connector, device px, t thick (1 design px, so 2 at double size like the mock's close ups). a column band
    // x..x+t-1 from top down to bend (exclusive), t nested quarter rings with the outer one's r x r slice at (x, bend),
    // then a row band y-t+1..y from x + r out to right (exclusive)
    record Elbow(int x, int top, int bend, int y, int right, int r, int t) {
    }

    // design px, hud.css
    public static final int MARGIN = 16;
    static final int ROW = 26;
    static final int ICON = 16;
    static final int ICON_GAP = 8;
    static final int INDENT = 22;
    // the flex gap (8) plus the meta's own padding-left (16)
    static final int META_GAP = 24;
    static final int PAD_Y = 8;
    static final int PAD_L = 10;
    static final int PAD_R = 12;
    static final int MIN_W = 220;
    static final int MAX_W = 340;
    // the tree lines: 4 clear of the parent icon's bottom, 4 short of the child icon, a 5 round elbow. the gaps never go
    // under 3 device px whatever the scale, at half size 2 px read as touching
    static final int LINE_GAP = 4;
    static final int ELBOW = 5;
    static final int MIN_GAP_PX = 3;
    // the narrowest tree worth drawing, under this it goes compact
    public static final int NARROW = 160;
    // beside the boss bars only with the whole max width to spare, else under them. squeezed to 220 next to the
    // dragon's bar on a small window it read "Beating the …", one bar's height lower it reads fine
    public static final int BESIDE_BOSS = MAX_W;
    // narrower than this and even the compact one is just a smear of dots
    static final int TINY = 120;
    static final int BAR_TOP = 4;
    static final int BAR_H = 3;
    static final int BAR_BELOW = 2;
    static final int META_LINE = 15;
    static final int COMPACT_PAD_Y = 7;
    static final int SUB_PULL = 3;
    static final int SUB_X = 24;
    static final int PILL_PAD_Y = 6;
    static final int PILL_PAD_L = 8;
    static final int PILL_PAD_R = 12;
    static final int PILL_ROW = 18;
    static final int PILL_GAP = 7;
    static final int PILL_RADIUS = 15;
    // the old overlay folded at 10 lines with 8 px steps. at 22 px a step, 10 deep is 200 px of staircase and the words
    // get nothing, so it folds sooner: the top, "n more tasks", and the last few, which are the ones that mean anything
    static final int MAX_ROWS = 6;
    static final long IDLE_MS = 3000;
    // a chain can be empty for a tick between one task and the next while the runner carries on. that is not the end,
    // so the last rows hang on this long before the pill gets a look in, and coming back inside it is no fresh fade
    public static final long GRACE_MS = 250;
    static final String IDLE = "Idle";
    private static final Pattern FRACTION = Pattern.compile("(\\d{1,9})\\s*/\\s*(\\d{1,9})");

    private final Theme theme;
    private final TextStyle labelStyle;
    private final TextStyle rootStyle;
    private final TextStyle metaStyle;
    private final TextStyle subStyle;
    private final FadeCanvas fade = new FadeCanvas();
    private final PixelImage logo;

    // what the overlay said last
    private List<Row> rows = List.of();
    private int keepTop = 1;
    private String timer;
    private boolean had;
    private long idleSince = -1;
    private long emptySince = -1;
    private long shownAt = -1;

    // the last layout
    private Mode mode = Mode.NONE;
    private final List<Laid> laid = new ArrayList<>();
    private float progress = -1f;
    private String subName;
    private String subMeta;
    private int x;
    private int y;
    private int w;
    private int h;
    private Object layoutKey;

    public StatusPanel(Theme theme) {
        this.theme = theme;
        this.labelStyle = new TextStyle(Weight.MEDIUM, theme.px(14));
        this.rootStyle = new TextStyle(Weight.SEMIBOLD, theme.px(14));
        this.metaStyle = new TextStyle(Weight.MEDIUM, theme.px(12), true);
        this.subStyle = new TextStyle(Weight.MEDIUM, theme.px(12));
        PixelImage mark = null;
        try {
            mark = BrandArt.logo(Math.max(1, Math.round(theme.pxf(ICON))));
        } catch (Throwable t) {
            // no logo is a pill with just the word, not a reason to lose the panel
            Safe.report("status panel logo", t);
        }
        this.logo = mark;
    }

    public Theme theme() {
        return theme;
    }

    // for the atlas prewarm, these sizes aren't the theme's own
    public TextStyle[] styles() {
        return new TextStyle[]{labelStyle, rootStyle, metaStyle, subStyle};
    }

    // ----- state

    // every frame. rows null means the panel is switched off (altoShowTaskChains), which also forgets the idle pill.
    // an empty list after some rows is a task that just ended: the pill shows for IDLE_MS, then it's gone
    public void update(List<Row> next, int top, String time, long nowMs) {
        if (next == null) {
            reset();
            return;
        }
        if (!next.isEmpty()) {
            if (!had || idleSince >= 0 || shownAt < 0) {
                shownAt = nowMs;
            }
            had = true;
            idleSince = -1;
            emptySince = -1;
            take(next, top, time);
            return;
        }
        if (!had) {
            take(next, top, time);
            return;
        }
        if (idleSince < 0) {
            if (emptySince < 0) {
                emptySince = nowMs;
            }
            if (nowMs - emptySince < GRACE_MS) {
                // keep the old rows up through the gap, timer and all (an empty chain has no timer)
                return;
            }
            idleSince = nowMs;
            take(next, top, time);
        } else if (nowMs - idleSince >= IDLE_MS + fadeMs()) {
            had = false;
            idleSince = -1;
            emptySince = -1;
            shownAt = -1;
        }
    }

    private void take(List<Row> next, int top, String time) {
        rows = next;
        keepTop = Math.max(1, top);
        timer = time;
    }

    // a rebuilt panel (new scale) carries on where the old one was: no second fade in, the pill keeps its clock
    public void adopt(StatusPanel old) {
        rows = old.rows;
        keepTop = old.keepTop;
        timer = old.timer;
        had = old.had;
        idleSince = old.idleSince;
        emptySince = old.emptySince;
        shownAt = old.shownAt;
    }

    public void reset() {
        rows = List.of();
        had = false;
        idleSince = -1;
        emptySince = -1;
        shownAt = -1;
        mode = Mode.NONE;
        layoutKey = null;
    }

    // what the panel wants to be before it knows its room
    public boolean wantsTree() {
        return !rows.isEmpty();
    }

    public boolean wantsIdle() {
        return rows.isEmpty() && idleSince >= 0;
    }

    public boolean wantsAnything() {
        return wantsTree() || wantsIdle();
    }

    // ----- layout

    // into the room the hud found for it. returns what it's going to draw, NONE when it doesn't fit at all
    public Mode layout(PanelPlacement.Room room) {
        Mode want = wantsTree() ? Mode.TREE : wantsIdle() ? Mode.IDLE : Mode.NONE;
        Object key = List.of(want, rows, keepTop, Objects.toString(timer), room, theme.scale);
        if (key.equals(layoutKey)) {
            return mode;
        }
        layoutKey = key;
        laid.clear();
        subName = null;
        subMeta = null;
        progress = -1f;
        x = Math.round(room.x());
        y = Math.round(room.y());
        int roomW = (int) Math.floor(room.w());
        int roomH = (int) Math.floor(room.h());
        mode = switch (want) {
            case TREE -> layoutTree(roomW, roomH) ? Mode.TREE : layoutCompact(roomW, roomH) ? Mode.COMPACT : Mode.NONE;
            case IDLE -> layoutPill(roomW, roomH) ? Mode.IDLE : Mode.NONE;
            default -> Mode.NONE;
        };
        return mode;
    }

    private boolean layoutTree(int roomW, int roomH) {
        int rowH = px(ROW);
        int maxW = Math.min(px(MAX_W), roomW);
        if (maxW < px(NARROW)) {
            return false;
        }
        Row leaf = rows.get(rows.size() - 1);
        float p = progress(leaf.state());
        int bar = p >= 0f ? barTall() : 0;
        int fit = (roomH - 2 * px(PAD_Y) - bar) / rowH;
        int maxRows = Math.min(MAX_ROWS, fit);
        if (rows.size() > maxRows && maxRows < keepTop + 2) {
            return false;
        }
        List<Row> shown = fold(rows, keepTop, maxRows);
        TextMeasurer m = theme.measurer;
        int lead = px(ICON) + px(ICON_GAP);
        float natural = 0f;
        for (int i = 0; i < shown.size(); i++) {
            Row r = shown.get(i);
            String meta = meta(r);
            float nw = indent(i) + (r.role() == Role.MORE ? 0 : lead) + m.measure(r.name(), style(r.role()));
            if (!meta.isEmpty()) {
                nw += px(META_GAP) + m.measure(meta, metaStyle);
            }
            natural = Math.max(natural, nw);
        }
        w = clamp((int) Math.ceil(px(PAD_L) + natural + px(PAD_R)), Math.min(px(MIN_W), maxW), maxW);
        int inner = w - px(PAD_L) - px(PAD_R);
        for (int i = 0; i < shown.size(); i++) {
            Row r = shown.get(i);
            String[] fitted = fit(r.name(), style(r.role()), meta(r), inner - indent(i) - (r.role() == Role.MORE ? 0 : lead));
            laid.add(new Laid(i, fitted[0], fitted[1], r.role(), r.icon()));
        }
        progress = p;
        h = px(PAD_Y) + shown.size() * rowH + bar + px(PAD_Y);
        return true;
    }

    // hud.css .status.compact: the leaf on top, what it's all for underneath
    private boolean layoutCompact(int roomW, int roomH) {
        Row leaf = rows.get(rows.size() - 1);
        Row root = rows.get(0);
        boolean only = rows.size() == 1;
        String leafMeta = only ? meta(root) : nz(leaf.state());
        String sub = only ? null : root.name();
        String subRight = only ? "" : nz(timer);
        int rowH = px(ROW);
        int height = px(COMPACT_PAD_Y) + rowH + (sub != null ? px(META_LINE) - px(SUB_PULL) : 0) + px(COMPACT_PAD_Y);
        int maxW = Math.min(px(MAX_W), roomW);
        if (height > roomH || maxW < px(TINY)) {
            return false;
        }
        TextMeasurer m = theme.measurer;
        int lead = px(ICON) + px(ICON_GAP);
        float top = lead + m.measure(leaf.name(), style(Role.LEAF)) + (leafMeta.isEmpty() ? 0f : px(META_GAP) + m.measure(leafMeta, metaStyle));
        float under = sub == null ? 0f : px(SUB_X) + m.measure(sub, subStyle) + (subRight.isEmpty() ? 0f : px(META_GAP) + m.measure(subRight, metaStyle));
        w = Math.min(maxW, (int) Math.ceil(px(PAD_L) + Math.max(top, under) + px(PAD_R)));
        int inner = w - px(PAD_L) - px(PAD_R);
        String[] fitted = fit(leaf.name(), style(Role.LEAF), leafMeta, inner - lead);
        laid.add(new Laid(0, fitted[0], fitted[1], only ? Role.ONLY : Role.LEAF, leaf.icon()));
        if (sub != null) {
            String[] s = fit(sub, subStyle, subRight, inner - px(SUB_X));
            subName = s[0];
            subMeta = s[1];
        }
        h = height;
        return true;
    }

    // hud.css .status.idle: the soprano mark and one word, a pill
    private boolean layoutPill(int roomW, int roomH) {
        int logoW = logo == null ? 0 : px(ICON) + px(PILL_GAP);
        w = (int) Math.ceil(px(PILL_PAD_L) + logoW + theme.measurer.measure(IDLE, labelStyle) + px(PILL_PAD_R));
        h = 2 * px(PILL_PAD_Y) + px(PILL_ROW);
        return w <= roomW && h <= roomH;
    }

    // both whole when they fit. otherwise a short meta (a count, a timer) stays and the name gives way, and a meta that
    // would leave the name next to nothing is dropped, like the old overlay did
    private String[] fit(String name, TextStyle nameStyle, String meta, float room) {
        TextMeasurer m = theme.measurer;
        String n = nz(name);
        String mt = nz(meta);
        if (mt.isEmpty()) {
            return new String[]{m.ellipsize(n, nameStyle, room), ""};
        }
        float nameW = m.measure(n, nameStyle);
        float metaW = m.measure(mt, metaStyle);
        float gap = px(META_GAP);
        if (nameW + gap + metaW <= room) {
            return new String[]{n, mt};
        }
        // the name keeps over half the row. a squeezed leaf loses its count before its name turns into "Brea…", the bar
        // under it still says how far along it is
        float metaRoom = room - gap - Math.min(nameW, room * 0.55f);
        if (metaRoom < 3 * m.measure("…", metaStyle)) {
            return new String[]{m.ellipsize(n, nameStyle, room), ""};
        }
        String fm = m.ellipsize(mt, metaStyle, metaRoom);
        return new String[]{m.ellipsize(n, nameStyle, room - gap - m.measure(fm, metaStyle)), fm};
    }

    private String meta(Row r) {
        String state = nz(r.state());
        boolean top = r.role() == Role.ROOT || r.role() == Role.ONLY;
        if (!top || timer == null || timer.isEmpty()) {
            return state;
        }
        return state.isEmpty() ? timer : state + "  " + timer;
    }

    // ----- drawing

    public void draw(Canvas c, long nowMs) {
        if (mode == Mode.NONE) {
            return;
        }
        float a = alpha(nowMs);
        if (a <= 0f) {
            return;
        }
        Canvas g = a < 1f ? fade.reset(c, a, 0f) : c;
        switch (mode) {
            case TREE -> drawTree(g);
            case COMPACT -> drawCompact(g);
            case IDLE -> drawPill(g);
            default -> {
            }
        }
    }

    // a fade in when it shows up, the pill fades out at the end of its few seconds
    float alpha(long nowMs) {
        long f = fadeMs();
        if (mode == Mode.IDLE) {
            if (idleSince < 0) {
                return 0f;
            }
            long left = idleSince + IDLE_MS + f - nowMs;
            return f == 0 ? (left > 0 ? 1f : 0f) : clamp01(left / (float) f);
        }
        if (f == 0 || shownAt < 0) {
            return 1f;
        }
        return clamp01((nowMs - shownAt) / (float) f);
    }

    private void drawTree(Canvas g) {
        HudGlass.draw(g, theme, x, y, w, h, theme.pxf(Theme.CARD_RADIUS));
        int rowH = px(ROW);
        int iconS = px(ICON);
        int lead = iconS + px(ICON_GAP);
        int left = x + px(PAD_L);
        int right = x + w - px(PAD_R);
        int lineColor = Theme.fade(theme.color(Theme.HAIRLINE_STRONG), 0.8f);
        TextMeasurer m = theme.measurer;
        for (Laid l : laid) {
            int rowTop = y + px(PAD_Y) + l.depth() * rowH;
            int ix = left + indent(l.depth());
            int iy = rowTop + (rowH - iconS) / 2;
            if (l.depth() > 0) {
                drawElbow(g, elbowAt(left, rowTop, l.depth()), lineColor);
            }
            if (l.role() != Role.MORE && l.icon() != null) {
                g.icon(l.icon(), ix, iy, iconS, iconColor(l.role()));
            }
            TextStyle st = style(l.role());
            // the fold has no icon, its words sit in the icon's spot so the line still stops 4 px short of something
            int tx = l.role() == Role.MORE ? ix : ix + lead;
            g.text(l.name(), tx, middle(rowTop, rowH, st), st, labelColor(l.role()));
            if (!l.meta().isEmpty()) {
                g.text(l.meta(), right - m.measure(l.meta(), metaStyle), middle(rowTop, rowH, metaStyle), metaStyle, theme.color(Theme.TEXT_3));
            }
        }
        if (progress >= 0f && !laid.isEmpty()) {
            Laid leaf = laid.get(laid.size() - 1);
            int bx = left + indent(leaf.depth()) + lead;
            int by = y + px(PAD_Y) + laid.size() * rowH + px(BAR_TOP);
            int bw = right - bx;
            int bh = Math.max(1, px(BAR_H));
            float br = Math.max(1f, theme.pxf(2));
            g.fillRoundRect(bx, by, bw, bh, br, theme.dark() ? 0x1FFFFFFF : 0x14000000);
            float fill = Math.round(bw * progress);
            if (fill >= 1f) {
                g.fillRoundRect(bx, by, Math.max(fill, bh), bh, br, theme.color(Theme.ACCENT));
            }
        }
    }

    private void drawCompact(Canvas g) {
        HudGlass.draw(g, theme, x, y, w, h, theme.pxf(Theme.CARD_RADIUS));
        Laid l = laid.get(0);
        int rowH = px(ROW);
        int iconS = px(ICON);
        int left = x + px(PAD_L);
        int right = x + w - px(PAD_R);
        int rowTop = y + px(COMPACT_PAD_Y);
        TextMeasurer m = theme.measurer;
        g.icon(l.icon(), left, rowTop + (rowH - iconS) / 2, iconS, iconColor(l.role()));
        g.text(l.name(), left + iconS + px(ICON_GAP), middle(rowTop, rowH, labelStyle), labelStyle, theme.color(Theme.TEXT));
        if (!l.meta().isEmpty()) {
            g.text(l.meta(), right - m.measure(l.meta(), metaStyle), middle(rowTop, rowH, metaStyle), metaStyle, theme.color(Theme.TEXT_3));
        }
        if (subName != null) {
            int subTop = rowTop + rowH - px(SUB_PULL);
            int lh = px(META_LINE);
            g.text(subName, left + px(SUB_X), middle(subTop, lh, subStyle), subStyle, theme.color(Theme.TEXT_3));
            if (!subMeta.isEmpty()) {
                g.text(subMeta, right - m.measure(subMeta, metaStyle), middle(subTop, lh, metaStyle), metaStyle, theme.color(Theme.TEXT_3));
            }
        }
    }

    private void drawPill(Canvas g) {
        HudGlass.draw(g, theme, x, y, w, h, Math.min(h / 2f, theme.pxf(PILL_RADIUS)));
        int rowTop = y + px(PILL_PAD_Y);
        int rowH = px(PILL_ROW);
        int lx = x + px(PILL_PAD_L);
        if (logo != null) {
            int s = px(ICON);
            g.image(logo, lx, rowTop + (rowH - s) / 2, s, s, theme.color(Theme.TEXT));
            lx += s + px(PILL_GAP);
        }
        g.text(IDLE, lx, middle(rowTop, rowH, labelStyle), labelStyle, theme.color(Theme.TEXT_2));
    }

    // flex align-items: center. the glyphs' box sits in the middle of the row
    private float middle(int top, int tall, TextStyle st) {
        TextMeasurer m = theme.measurer;
        return top + (tall - (m.ascent(st) + m.descent(st))) / 2f + m.ascent(st);
    }

    // ----- the tree lines

    // the line into the row at depth (> 0) whose top is rowTop, left being the content box's left edge. the parent is the
    // row right above, one step in: the tree is a staircase, every line is one deeper than the one before
    Elbow elbowAt(int left, int rowTop, int depth) {
        int rowH = px(ROW);
        int iconS = px(ICON);
        int iy = rowTop + (rowH - iconS) / 2;
        return elbow(left + indent(depth - 1), iy - rowH + iconS, left + indent(depth), iy + iconS / 2, iconS);
    }

    // parent icon's left and bottom, child icon's left and middle row, all device px
    Elbow elbow(int parentX, int parentBottom, int childX, int childMid, int iconS) {
        int gap = Math.max(MIN_GAP_PX, px(LINE_GAP));
        int t = Math.max(1, px(1));
        int r = Math.max(t + 1, px(ELBOW));
        // centred on the parent icon. an even icon with a 1 px line has two centre columns, the left one it is
        int lx = parentX + (iconS - t) / 2;
        int top = parentBottom + gap;
        int bend = childMid - r + 1;
        if (bend < top) {
            // only at silly scales: a tighter elbow rather than a line poking up into the parent's icon
            r = Math.max(1, childMid - top + 1);
            bend = childMid - r + 1;
            t = Math.min(t, r);
        }
        return new Elbow(lx, top, bend, childMid, childX - gap, r, t);
    }

    private static void drawElbow(Canvas g, Elbow e, int argb) {
        int t = e.t();
        if (e.bend() > e.top()) {
            g.fillRect(e.x(), e.top(), t, e.bend() - e.top(), argb);
        }
        // a thick round is nested 1 px rings, each one in and one smaller: they all start at the bend row and all end
        // on the column before x + r
        for (int k = 0; k < t; k++) {
            g.cornerLine(e.x() + k, e.bend(), e.r() - k, ShapeAtlas.BL, argb);
        }
        int from = e.x() + e.r();
        if (e.right() > from) {
            g.fillRect(from, e.y() - t + 1, e.right() - from, t, argb);
        }
    }

    // ----- bits

    // "12/24", "rods (3/7)", "4 / 9 dug": the first count in the state. -1 for none
    public static float progress(String state) {
        if (state == null || state.isEmpty()) {
            return -1f;
        }
        Matcher mt = FRACTION.matcher(state);
        if (!mt.find()) {
            return -1f;
        }
        long have = Long.parseLong(mt.group(1));
        long need = Long.parseLong(mt.group(2));
        if (need <= 0) {
            return -1f;
        }
        return clamp01(have / (float) need);
    }

    static List<Row> fold(List<Row> rows, int keepTop, int maxRows) {
        int n = rows.size();
        if (n <= maxRows) {
            return rows;
        }
        int skip = n - maxRows + 1;
        List<Row> out = new ArrayList<>(rows.subList(0, keepTop));
        // skip is never under 2, the fold line takes a row of its own
        out.add(new Row(skip + " more tasks", "", Role.MORE, null));
        out.addAll(rows.subList(keepTop + skip, n));
        return out;
    }

    private TextStyle style(Role r) {
        return r == Role.ROOT || r == Role.ONLY ? rootStyle : labelStyle;
    }

    private int labelColor(Role r) {
        return switch (r) {
            case ROOT, ONLY, LEAF -> theme.color(Theme.TEXT);
            case MID -> theme.color(Theme.TEXT_2);
            case MORE -> theme.color(Theme.TEXT_3);
        };
    }

    private int iconColor(Role r) {
        return switch (r) {
            case ROOT -> theme.color(Theme.TEXT);
            case LEAF, ONLY -> theme.color(Theme.ACCENT_TEXT);
            default -> theme.color(Theme.TEXT_2);
        };
    }

    int barTall() {
        return px(BAR_TOP) + Math.max(1, px(BAR_H)) + px(BAR_BELOW);
    }

    int indent(int depth) {
        return Math.round(theme.pxf(INDENT * depth));
    }

    private int px(int design) {
        return Math.round(theme.pxf(design));
    }

    // plain ms, the panel runs on the wall clock (Anim.ms is nanos, which cost the first preview its whole tree)
    private static long fadeMs() {
        return Anim.enabled ? Theme.ANIM_FAST : 0L;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ----- what got laid out, for the hud glue and the tests

    public Mode mode() {
        return mode;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int w() {
        return w;
    }

    public int h() {
        return h;
    }

    List<Laid> laid() {
        return laid;
    }

    float progressShown() {
        return progress;
    }

    String sub() {
        return subName;
    }
}
