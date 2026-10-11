package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.facade.WorldAccess.WaypointEntry;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;
import adris.altoclef.ui.gui.model.Coords;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

// one waypoint row: icon, name and tag, coordinates under them, distance on the right. the actions are painted on
// the row (not widgets) and hit tested from the same zones, so a recycled row can never keep a stale button
final class WaypointRows implements VirtualList.RowRenderer<WaypointEntry> {

    static final int GO = 0;
    static final int COPY = 1;
    static final int DELETE = 2;
    static final int YES = 3;
    static final int NO = 4;
    static final int PIN = 5;

    private final Theme theme;
    private final Supplier<WorldAccess.Position> pos;
    private final Supplier<WaypointEntry> confirming;
    // where each visible row was last painted: x, y, w, h
    private final Map<Integer, int[]> painted = new HashMap<>();

    WaypointRows(Theme theme, Supplier<WorldAccess.Position> pos, Supplier<WaypointEntry> confirming) {
        this.theme = theme;
        this.pos = pos;
        this.confirming = confirming;
    }

    // x, y, w, h of one action in a row at rx, ry. go copy and delete sit left of the distance, yes and no take the
    // same slot while a delete is being confirmed
    int[] zone(int which, int rx, int ry, int rw, int rh) {
        int bh = theme.px(28);
        int by = ry + (rh - bh) / 2;
        int right = rx + rw - theme.px(12) - theme.px(64) - theme.px(8);
        int del = right - theme.px(28);
        int copy = del - theme.px(2) - theme.px(28);
        int go = copy - theme.px(2) - theme.px(36);
        int pin = go - theme.px(2) - theme.px(28);
        int no = right - theme.px(44);
        int yes = no - theme.px(4) - theme.px(48);
        return switch (which) {
            case GO -> new int[]{go, by, theme.px(36), bh};
            case COPY -> new int[]{copy, by, theme.px(28), bh};
            case DELETE -> new int[]{del, by, theme.px(28), bh};
            case YES -> new int[]{yes, by, theme.px(48), bh};
            case PIN -> new int[]{pin, by, theme.px(28), bh};
            default -> new int[]{no, by, theme.px(44), bh};
        };
    }

    // which action the point is on in row `index`, or -1. only what the row is showing counts
    int hit(int index, boolean confirm, float mx, float my) {
        int[] g = painted.get(index);
        if (g == null) {
            return -1;
        }
        int[] order = confirm ? new int[]{YES, NO} : new int[]{PIN, GO, COPY, DELETE};
        for (int which : order) {
            int[] z = zone(which, g[0], g[1], g[2], g[3]);
            if (inside(z, mx, my)) {
                return which;
            }
        }
        return -1;
    }

    int[] paintedZone(int index, int which) {
        int[] g = painted.get(index);
        return g == null ? null : zone(which, g[0], g[1], g[2], g[3]);
    }

    private static boolean inside(int[] z, float mx, float my) {
        return mx >= z[0] && mx < z[0] + z[2] && my >= z[1] && my < z[1] + z[3];
    }

    @Override
    public void draw(Canvas c, Frame f, WaypointEntry e, int index, int rx, int ry, int rw, int rh, boolean hot) {
        painted.put(index, new int[]{rx, ry, rw, rh});
        boolean confirm = e.equals(confirming.get());
        float rr = theme.pxf(Theme.ROW_RADIUS);
        if (confirm) {
            c.fillRoundRect(rx, ry, rw, rh, rr, Theme.fade(theme.color(Theme.DANGER_FILL), 0.55f));
        } else if (hot) {
            c.fillRoundRect(rx, ry, rw, rh, rr, theme.color(Theme.HOVER));
        } else {
            c.fillRect(rx + theme.px(12), ry + rh - 1, rw - theme.px(24), 1, theme.color(Theme.HAIRLINE));
        }

        int is = theme.px(Theme.ICON);
        int pad = theme.px(12);
        c.icon(Icon.WAYPOINTS, rx + pad, ry + (rh - is) / 2f, is, theme.color(Theme.TEXT_3));

        TextStyle body = theme.body;
        TextStyle small = theme.small.asTabular();
        float lhBody = c.lineHeight(body);
        float lhSmall = c.lineHeight(small);
        float gap = theme.px(2);
        float top = ry + (rh - (lhBody + gap + lhSmall)) / 2f;
        float base1 = top + c.ascent(body);
        float base2 = top + lhBody + gap + c.ascent(small);

        float nameX = rx + pad + is + theme.px(12);
        int[] yes = zone(YES, rx, ry, rw, rh);
        float limit = yes[0] - theme.px(118);
        String tag = e.tag() == null ? "" : e.tag();
        float pillPad = theme.px(8);
        float pillW = tag.isEmpty() ? 0f : c.measure(tag, theme.smallLabel) + 2 * pillPad;
        float pillGap = tag.isEmpty() ? 0f : theme.px(8);
        float nameRoom = Math.max(theme.px(40), limit - nameX - pillW - pillGap);
        String name = c.ellipsize(e.name() == null ? "" : e.name(), body, nameRoom);
        c.text(name, nameX, base1, body, theme.color(Theme.TEXT));
        if (!tag.isEmpty()) {
            float px = nameX + c.measure(name, body) + pillGap;
            float ph = theme.px(18);
            float py = top + (lhBody - ph) / 2f;
            c.fillRoundRect(px, py, pillW, ph, rr, theme.color(Theme.RAISED));
            c.strokeRoundRect(px, py, pillW, ph, rr, hot || confirm ? theme.color(Theme.HAIRLINE_STRONG) : theme.color(Theme.HAIRLINE));
            c.text(tag, px + pillPad, py + ph / 2f + (c.ascent(theme.smallLabel) - c.descent(theme.smallLabel)) / 2f, theme.smallLabel, theme.color(Theme.TEXT_2));
        }
        c.text(WaypointRules.coords(e), nameX, base2, small, theme.color(Theme.TEXT_2));

        String dist = Coords.formatDistance(WaypointRules.distance(e, pos.get()));
        boolean pinned = !confirm && WaypointRules.isPinned(e);
        if (!dist.isEmpty()) {
            TextStyle d = body.asTabular();
            c.text(dist, rx + rw - pad - c.measure(dist, d), mid(c, d, ry, rh), d, theme.color(Theme.TEXT_2));
        }
        // a pinned waypoint wears a small pin to the left of its distance even at rest, so it reads as a favourite
        if (pinned) {
            int pis = theme.px(Theme.ICON);
            float dx = rx + rw - pad - (dist.isEmpty() ? 0f : c.measure(dist, body.asTabular())) - theme.px(6) - pis;
            c.icon(Icon.PIN, dx, mid(c, body, ry, rh) - pis / 2f, pis, theme.color(Theme.ACCENT));
        }

        if (confirm) {
            confirmPair(c, f, rx, ry, rw, rh);
        } else if (hot) {
            actions(c, f, rx, ry, rw, rh);
        }
    }

    private void actions(Canvas c, Frame f, int rx, int ry, int rw, int rh) {
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        int is = theme.px(Theme.ICON);

        int[] pin = zone(PIN, rx, ry, rw, rh);
        boolean over = inside(pin, f.mouseX(), f.mouseY());
        if (over) {
            c.fillRoundRect(pin[0], pin[1], pin[2], pin[3], r, theme.color(Theme.PRESSED));
        }
        c.icon(Icon.PIN, pin[0] + (pin[2] - is) / 2f, pin[1] + (pin[3] - is) / 2f, is, over ? theme.color(Theme.TEXT) : theme.color(Theme.TEXT_2));

        int[] go = zone(GO, rx, ry, rw, rh);
        over = inside(go, f.mouseX(), f.mouseY());
        if (over) {
            c.fillRoundRect(go[0], go[1], go[2], go[3], r, theme.color(Theme.PRESSED));
        }
        float tw = c.measure("Go", theme.label);
        c.text("Go", go[0] + (go[2] - tw) / 2f, mid(c, theme.label, go[1], go[3]), theme.label, over ? theme.color(Theme.TEXT) : theme.color(Theme.TEXT_2));

        int[] copy = zone(COPY, rx, ry, rw, rh);
        over = inside(copy, f.mouseX(), f.mouseY());
        if (over) {
            c.fillRoundRect(copy[0], copy[1], copy[2], copy[3], r, theme.color(Theme.PRESSED));
        }
        c.icon(Icon.COPY, copy[0] + (copy[2] - is) / 2f, copy[1] + (copy[3] - is) / 2f, is, over ? theme.color(Theme.TEXT) : theme.color(Theme.TEXT_2));

        int[] del = zone(DELETE, rx, ry, rw, rh);
        over = inside(del, f.mouseX(), f.mouseY());
        if (over) {
            c.fillRoundRect(del[0], del[1], del[2], del[3], r, theme.color(Theme.DANGER_FILL));
        }
        c.icon(Icon.TRASH, del[0] + (del[2] - is) / 2f, del[1] + (del[3] - is) / 2f, is, theme.color(Theme.DANGER));
    }

    private void confirmPair(Canvas c, Frame f, int rx, int ry, int rw, int rh) {
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        int[] yes = zone(YES, rx, ry, rw, rh);
        int[] no = zone(NO, rx, ry, rw, rh);
        String q = "Delete?";
        c.text(q, yes[0] - theme.px(10) - c.measure(q, theme.label), mid(c, theme.label, yes[1], yes[3]), theme.label, theme.color(Theme.TEXT));

        boolean overYes = inside(yes, f.mouseX(), f.mouseY());
        c.fillRoundRect(yes[0], yes[1], yes[2], yes[3], r, overYes ? 0xFF4A2626 : theme.color(Theme.DANGER_FILL));
        c.strokeRoundRect(yes[0], yes[1], yes[2], yes[3], r, Theme.fade(theme.color(Theme.DANGER), 0.5f));
        float yw = c.measure("Yes", theme.label);
        c.text("Yes", yes[0] + (yes[2] - yw) / 2f, mid(c, theme.label, yes[1], yes[3]), theme.label, theme.color(Theme.DANGER));

        boolean overNo = inside(no, f.mouseX(), f.mouseY());
        c.fillRoundRect(no[0], no[1], no[2], no[3], r, overNo ? theme.color(Theme.PRESSED) : theme.color(Theme.RAISED));
        c.strokeRoundRect(no[0], no[1], no[2], no[3], r, theme.color(Theme.HAIRLINE_STRONG));
        float nw = c.measure("No", theme.label);
        c.text("No", no[0] + (no[2] - nw) / 2f, mid(c, theme.label, no[1], no[3]), theme.label, theme.color(Theme.TEXT));
    }

    private static float mid(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }
}
