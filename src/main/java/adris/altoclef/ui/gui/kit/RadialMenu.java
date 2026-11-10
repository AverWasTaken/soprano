/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

// a ring of round tiles around a hub, and a panel of option chips beside it. the effortless build mode's shape
// picker. hovering goes by angle once the pointer is past the hub, so a flick towards a tile is enough, you don't
// have to land on it. the hub is "none". no minecraft in here, the preview tests draw it
public class RadialMenu extends Widget {

    public record Entry(Icon icon, String label) {
    }

    // one line of the panel: a label and a row of chips, one of them picked
    public record Row(String label, List<String> choices, int picked, IntConsumer onPick) {
    }

    public interface Listener {
        // index into the entries, -1 for the hub (none)
        void select(int index);
    }

    public static final int HUB = -1;
    public static final int NOTHING = -2;

    static final int RING_R = 112;
    static final int TILE = 54;
    static final int HUB_R = 34;
    static final int PANEL_W = 300;
    static final int GAP = 28;
    static final int PAD = 14;
    static final int ROW_GAP = 10;
    static final int CHIP_H = 24;
    static final int CHIP_PAD = 10;
    static final int CHIP_GAP = 6;

    private final List<Entry> entries;
    private final Listener listener;
    private List<Row> rows = List.of();
    private int selected = HUB;
    private int hover = NOTHING;
    // chip hit boxes from the last draw: row, chip, x, y, w, h
    private final List<float[]> chips = new ArrayList<>();
    private float hoverChipX = Float.NaN;
    private float hoverChipY = Float.NaN;
    // the panel is as wide as its widest row of chips, measured on the last draw. the ring centres against it
    private float panelW = -1;

    public RadialMenu(Theme theme, List<Entry> entries, Listener listener) {
        super(theme);
        this.entries = List.copyOf(entries);
        this.listener = listener;
    }

    public void setSelected(int index) {
        this.selected = index;
    }

    public int selected() {
        return selected;
    }

    public int hover() {
        return hover;
    }

    public void setRows(List<Row> rows) {
        this.rows = List.copyOf(rows);
    }

    // ----- geometry

    float centerX() {
        // the ring sits left of middle so ring and panel together are centred
        float pw = rows.isEmpty() ? 0 : panelW > 0 ? panelW : theme.pxf(PANEL_W);
        float total = 2 * (theme.pxf(RING_R) + theme.pxf(TILE) / 2f) + (rows.isEmpty() ? 0 : theme.pxf(GAP) + pw);
        return x + (w - total) / 2f + theme.pxf(RING_R) + theme.pxf(TILE) / 2f;
    }

    float centerY() {
        return y + h / 2f;
    }

    float tileX(int i) {
        return centerX() + theme.pxf(RING_R) * (float) Math.sin(angle(i));
    }

    float tileY(int i) {
        return centerY() - theme.pxf(RING_R) * (float) Math.cos(angle(i));
    }

    // clockwise from the top
    double angle(int i) {
        return 2 * Math.PI * i / entries.size();
    }

    float panelX() {
        return centerX() + theme.pxf(RING_R) + theme.pxf(TILE) / 2f + theme.pxf(GAP);
    }

    // which tile the pointer means: the hub inside it, a tile by angle outside it, nothing far off the ring
    public int pick(float px, float py) {
        float dx = px - centerX();
        float dy = py - centerY();
        double d = Math.sqrt(dx * dx + dy * dy);
        if (d <= theme.pxf(HUB_R)) {
            return HUB;
        }
        if (d > theme.pxf(RING_R) + theme.pxf(TILE)) {
            return NOTHING;
        }
        double a = Math.atan2(dx, -dy);
        if (a < 0) {
            a += 2 * Math.PI;
        }
        double step = 2 * Math.PI / entries.size();
        return (int) Math.round(a / step) % entries.size();
    }

    // ----- input

    @Override
    public Widget mouseMove(float mx, float my) {
        super.mouseMove(mx, my);
        hover = pick(mx, my);
        hoverChipX = mx;
        hoverChipY = my;
        return this;
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        for (float[] c : chips) {
            if (mx >= c[2] && my >= c[3] && mx < c[2] + c[4] && my < c[3] + c[5]) {
                Row r = rows.get((int) c[0]);
                r.onPick().accept((int) c[1]);
                return true;
            }
        }
        int p = pick(mx, my);
        if (p != NOTHING) {
            selected = p;
            listener.select(p);
            return true;
        }
        return false;
    }

    // ----- drawing

    @Override
    public void draw(Canvas c, Frame f) {
        float cx = centerX();
        float cy = centerY();
        float tile = theme.pxf(TILE);
        float back = theme.pxf(RING_R) + tile / 2f + theme.pxf(12);
        c.shadow(cx - back, cy - back, 2 * back, 2 * back, back, c.color(Theme.SHADOW));
        c.fillRoundRect(cx - back, cy - back, 2 * back, 2 * back, back, c.color(Theme.PANEL) & 0xE6FFFFFF);

        for (int i = 0; i < entries.size(); i++) {
            float tx = tileX(i) - tile / 2f;
            float ty = tileY(i) - tile / 2f;
            boolean on = i == selected;
            boolean hv = i == hover;
            int fill = on ? c.color(Theme.ACCENT) : hv ? c.color(Theme.HOVER) : c.color(Theme.RAISED);
            c.fillRoundRect(tx, ty, tile, tile, tile / 2f, fill);
            if (hv && !on) {
                c.strokeRoundRect(tx, ty, tile, tile, tile / 2f, c.color(Theme.HAIRLINE_STRONG));
            }
            float is = tile * 0.5f;
            c.icon(entries.get(i).icon(), tx + (tile - is) / 2f, ty + (tile - is) / 2f, is,
                    on ? c.color(Theme.ON_ACCENT) : c.color(Theme.TEXT));
        }

        float hub = theme.pxf(HUB_R);
        boolean hubOn = selected == HUB;
        c.fillRoundRect(cx - hub, cy - hub, 2 * hub, 2 * hub, hub,
                hubOn ? c.color(Theme.ACCENT) : hover == HUB ? c.color(Theme.HOVER) : c.color(Theme.SURFACE));
        float is = hub * 0.7f;
        c.icon(Icon.CLOSE, cx - is / 2f, cy - is / 2f - theme.pxf(5), is, hubOn ? c.color(Theme.ON_ACCENT) : c.color(Theme.TEXT_2));
        String none = "None";
        float nw = c.measure(none, theme.small);
        c.text(none, cx - nw / 2f, cy + hub * 0.62f, theme.small, hubOn ? c.color(Theme.ON_ACCENT) : c.color(Theme.TEXT_2));

        // what the pointer is on (or what's picked), under the ring
        int named = hover != NOTHING ? hover : selected;
        String title = named == HUB ? "None, build mode off" : named >= 0 ? entries.get(named).label() : "";
        float tw = c.measure(title, theme.label);
        c.text(title, cx - tw / 2f, cy + back + theme.pxf(26), theme.label, c.color(Theme.TEXT));

        drawPanel(c);
    }

    private void drawPanel(Canvas c) {
        chips.clear();
        if (rows.isEmpty()) {
            return;
        }
        float pad = theme.pxf(PAD);
        float widest = theme.pxf(PANEL_W) - 2 * pad;
        for (Row row : rows) {
            float rw = -theme.pxf(CHIP_GAP);
            for (String s : row.choices()) {
                rw += c.measure(s, theme.small) + 2 * theme.pxf(CHIP_PAD) + theme.pxf(CHIP_GAP);
            }
            widest = Math.max(widest, Math.max(rw, c.measure(row.label(), theme.smallLabel)));
        }
        panelW = widest + 2 * pad;
        float px = panelX();
        float pw = panelW;
        float labelH = c.lineHeight(theme.smallLabel);
        float rowH = labelH + theme.pxf(4) + theme.pxf(CHIP_H);
        float ph = pad * 2 + rows.size() * rowH + (rows.size() - 1) * theme.pxf(ROW_GAP);
        float py = centerY() - ph / 2f;
        c.shadow(px, py, pw, ph, theme.pxf(Theme.CARD_RADIUS), c.color(Theme.SHADOW));
        c.fillRoundRect(px, py, pw, ph, theme.pxf(Theme.CARD_RADIUS), c.color(Theme.PANEL) & 0xF0FFFFFF);
        float ry = py + pad;
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            c.text(row.label(), px + pad, ry + c.ascent(theme.smallLabel), theme.smallLabel, c.color(Theme.TEXT_2));
            float cy = ry + labelH + theme.pxf(4);
            float cx = px + pad;
            for (int i = 0; i < row.choices().size(); i++) {
                String s = row.choices().get(i);
                float cw = c.measure(s, theme.small) + 2 * theme.pxf(CHIP_PAD);
                float chipH = theme.pxf(CHIP_H);
                boolean on = i == row.picked();
                boolean hv = hoverChipX >= cx && hoverChipY >= cy && hoverChipX < cx + cw && hoverChipY < cy + chipH;
                int fill = on ? c.color(Theme.ACCENT) : hv ? c.color(Theme.HOVER) : c.color(Theme.RAISED);
                c.fillRoundRect(cx, cy, cw, chipH, chipH / 2f, fill);
                float base = cy + (chipH + c.ascent(theme.small) - c.descent(theme.small)) / 2f;
                c.text(s, cx + theme.pxf(CHIP_PAD), base, theme.small, on ? c.color(Theme.ON_ACCENT) : c.color(Theme.TEXT));
                chips.add(new float[]{r, i, cx, cy, cw, chipH});
                cx += cw + theme.pxf(CHIP_GAP);
            }
            ry += rowH + theme.pxf(ROW_GAP);
        }
    }
}
