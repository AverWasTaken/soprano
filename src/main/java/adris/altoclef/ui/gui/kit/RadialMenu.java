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

// a ring of wedges round an empty middle, and a panel of option chips beside it: the effortless build mode's shape
// picker. the first wedge, at the top, is "none". hovering goes by angle once the pointer is out of the middle, so a
// short flick towards a wedge is enough. the wedges are drawn by hand into a picture (the canvas has no arcs), redrawn
// only when the hover or the pick moves. no minecraft in here, the preview tests draw it
public class RadialMenu extends Widget {

    // picture is the wedge's 3D drawing (null for the flat glyph), group the coloured arc it sits on (0 for none)
    public record Entry(Icon icon, String label, PixelImage picture, int group) {
        public Entry(Icon icon, String label) {
            this(icon, label, null, 0);
        }
    }

    // one line of the panel: a label, a row of chips with one picked, and maybe a chip of its own on the end that turns
    // on and off by itself (the mix's "Turn"). no choices makes it a heading
    public record Row(String label, List<String> choices, int picked, IntConsumer onPick, String toggle, boolean toggled,
                      Runnable onToggle) {

        public Row(String label, List<String> choices, int picked, IntConsumer onPick) {
            this(label, choices, picked, onPick, null, false, null);
        }

        public static Row heading(String label) {
            return new Row(label, List.of(), -1, i -> {
            });
        }
    }

    public interface Listener {
        // index into the entries, -1 for none
        void select(int index);
    }

    // the none wedge
    public static final int HUB = -1;
    // the empty middle, or far off the ring
    public static final int NOTHING = -2;

    static final int RING_OUT = 150;
    static final int RING_IN = 66;
    static final int ARC = 6;
    static final int ICON = 46;
    static final int PANEL_W = 300;
    static final int GAP = 28;
    static final int PAD = 14;
    static final int ROW_GAP = 10;
    static final int CHIP_H = 24;
    static final int CHIP_PAD = 10;
    static final int CHIP_GAP = 6;
    // the gap between two wedges, in pixels at the outer edge
    private static final float WEDGE_GAP = 3f;
    // the coloured arcs under the wedges, one per group of shapes
    static final int[] GROUP_RGB = {0, 0x5BD27A, 0x4F8DF7, 0x9B6BF2, 0xF2A93B};

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
    private float labelW;
    private PixelImage ring;
    private long ringKey = Long.MIN_VALUE;

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

    // ----- geometry. segment 0 is none at the top, entry i is segment i + 1, clockwise

    private int segments() {
        return entries.size() + 1;
    }

    float centerX() {
        float pw = rows.isEmpty() ? 0 : panelW > 0 ? panelW : theme.pxf(PANEL_W);
        float total = 2 * theme.pxf(RING_OUT) + (rows.isEmpty() ? 0 : theme.pxf(GAP) + pw);
        return x + (w - total) / 2f + theme.pxf(RING_OUT);
    }

    float centerY() {
        return y + h / 2f;
    }

    double angle(int segment) {
        return 2 * Math.PI * segment / segments();
    }

    private float iconRadius() {
        return (theme.pxf(RING_OUT) + theme.pxf(RING_IN)) / 2f;
    }

    float tileX(int entry) {
        return centerX() + iconRadius() * (float) Math.sin(angle(entry + 1));
    }

    float tileY(int entry) {
        return centerY() - iconRadius() * (float) Math.cos(angle(entry + 1));
    }

    float panelX() {
        return centerX() + theme.pxf(RING_OUT) + theme.pxf(GAP);
    }

    // which wedge the pointer means: by angle anywhere past a small dead zone in the middle, nothing in the middle (so
    // letting go there changes nothing) or far off the ring
    public int pick(float px, float py) {
        float dx = px - centerX();
        float dy = py - centerY();
        double d = Math.sqrt(dx * dx + dy * dy);
        if (d < theme.pxf(RING_IN) * 0.4f || d > theme.pxf(RING_OUT) * 1.35f) {
            return NOTHING;
        }
        double a = Math.atan2(dx, -dy);
        if (a < 0) {
            a += 2 * Math.PI;
        }
        double step = 2 * Math.PI / segments();
        int seg = (int) Math.round(a / step) % segments();
        return seg - 1;
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
                if (c[1] < 0) {
                    r.onToggle().run();
                } else {
                    r.onPick().accept((int) c[1]);
                }
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
        // the panel's width moves the ring's centre, so it's measured before anything is drawn
        measurePanel(c);
        float cx = centerX();
        float cy = centerY();
        float out = theme.pxf(RING_OUT);
        int size = Math.max(8, Math.round(2 * out) + 2);
        long key = ((long) size << 32) ^ ((long) (hover + 3) << 16) ^ (selected + 3) ^ ((long) c.color(Theme.ACCENT) << 40);
        if (ring == null || ringKey != key || ring.width() != size) {
            ring = drawRing(size, c.color(Theme.ACCENT));
            ringKey = key;
        }
        c.image(ring, cx - size / 2f, cy - size / 2f, size, size, 0xFFFFFFFF);

        float is = theme.pxf(ICON);
        for (int seg = 0; seg < segments(); seg++) {
            float ix = cx + iconRadius() * (float) Math.sin(angle(seg));
            float iy = cy - iconRadius() * (float) Math.cos(angle(seg));
            boolean on = seg - 1 == selected;
            if (seg == 0) {
                float g = is * 0.55f;
                c.icon(Icon.CLOSE, ix - g / 2f, iy - g / 2f, g, c.color(Theme.TEXT));
                continue;
            }
            Entry e = entries.get(seg - 1);
            if (e.picture() != null) {
                c.image(e.picture(), ix - e.picture().width() / 2f, iy - e.picture().height() / 2f,
                        e.picture().width(), e.picture().height(), 0xFFFFFFFF);
            } else {
                float g = is * 0.6f;
                c.icon(e.icon(), ix - g / 2f, iy - g / 2f, g, on ? c.color(Theme.ON_ACCENT) : c.color(Theme.TEXT));
            }
        }

        // what the pointer is on (or what's picked), under the ring
        int named = hover != NOTHING ? hover : selected;
        String title = named == HUB ? "None, build mode off" : named >= 0 ? entries.get(named).label() : "";
        float tw = c.measure(title, theme.label);
        c.text(title, cx - tw / 2f, cy + out + theme.pxf(26), theme.label, c.color(Theme.TEXT));

        drawPanel(c);
    }

    // the wedges, by hand: every pixel of the ring finds its segment from its angle, with a soft edge all round and a gap
    // between neighbours. hovered is lighter, picked is the accent, and the inner edge carries its group's colour
    private PixelImage drawRing(int size, int accent) {
        PixelImage img = new PixelImage(size, size);
        int[] px = img.pixels();
        float half = size / 2f;
        float out = theme.pxf(RING_OUT);
        float in = theme.pxf(RING_IN);
        float arc = theme.pxf(ARC);
        int n = segments();
        double step = 2 * Math.PI / n;
        for (int yy = 0; yy < size; yy++) {
            for (int xx = 0; xx < size; xx++) {
                float dx = xx + 0.5f - half;
                float dy = yy + 0.5f - half;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                float radial = Math.min(r - in, out - r) + 0.5f;
                if (radial <= 0) {
                    continue;
                }
                double a = Math.atan2(dx, -dy);
                if (a < 0) {
                    a += 2 * Math.PI;
                }
                int seg = (int) Math.round(a / step) % n;
                double off = Math.abs(a - seg * step);
                if (off > Math.PI) {
                    off = 2 * Math.PI - off;
                }
                // the gap is a fixed width in pixels, so it's an angle that shrinks outwards
                float side = (float) ((step / 2 - off) * r) - WEDGE_GAP / 2f + 0.5f;
                if (side <= 0) {
                    continue;
                }
                float edge = Math.min(1f, Math.min(radial, side));
                int entry = seg - 1;
                int rgb;
                int alpha;
                boolean inArc = r - in < arc && entry >= 0 && entries.get(entry).group() > 0;
                if (inArc) {
                    rgb = GROUP_RGB[Math.min(GROUP_RGB.length - 1, entries.get(entry).group())];
                    alpha = 235;
                } else if (entry == selected) {
                    rgb = accent & 0xFFFFFF;
                    alpha = 230;
                } else if (entry == hover) {
                    rgb = 0x4A4A50;
                    alpha = 215;
                } else {
                    rgb = 0x202024;
                    alpha = 185;
                }
                px[yy * size + xx] = (Math.round(alpha * edge) << 24) | rgb;
            }
        }
        img.bump();
        return img;
    }

    private void drawPanel(Canvas c) {
        chips.clear();
        if (rows.isEmpty()) {
            return;
        }
        float pad = theme.pxf(PAD);
        float chipH = theme.pxf(CHIP_H);
        float gap = theme.pxf(CHIP_GAP);
        float labelW = this.labelW;
        float ph = pad * 2;
        for (int r = 0; r < rows.size(); r++) {
            ph += rowHeight(c, rows.get(r)) + (r > 0 ? theme.pxf(ROW_GAP) : 0);
        }
        float px = panelX();
        float py = Math.max(y + pad, centerY() - ph / 2f);
        c.shadow(px, py, panelW, ph, theme.pxf(Theme.CARD_RADIUS), c.color(Theme.SHADOW));
        c.fillRoundRect(px, py, panelW, ph, theme.pxf(Theme.CARD_RADIUS), c.color(Theme.PANEL) & 0xF0FFFFFF);
        float ry = py + pad;
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            if (r > 0) {
                ry += theme.pxf(ROW_GAP);
            }
            float rh = rowHeight(c, row);
            if (row.choices().isEmpty()) {
                // a heading, with a hairline above it when it isn't the first thing
                if (r > 0) {
                    c.fillRect(px + pad, ry - theme.pxf(ROW_GAP) / 2f, panelW - 2 * pad, 1, c.color(Theme.HAIRLINE));
                }
                c.text(row.label(), px + pad, ry + rh - c.descent(theme.label) - theme.pxf(2), theme.label, c.color(Theme.TEXT));
                ry += rh;
                continue;
            }
            float base = ry + (chipH + c.ascent(theme.smallLabel) - c.descent(theme.smallLabel)) / 2f;
            c.text(row.label(), px + pad, base, theme.smallLabel, c.color(Theme.TEXT_2));
            float cx = px + pad + labelW;
            for (int i = 0; i < row.choices().size(); i++) {
                float cw = chip(c, row.choices().get(i), cx, ry, i == row.picked());
                chips.add(new float[]{r, i, cx, ry, cw, chipH});
                cx += cw + gap;
            }
            if (row.toggle() != null) {
                cx += gap;
                float cw = chip(c, row.toggle(), cx, ry, row.toggled());
                // chip -1 is the toggle
                chips.add(new float[]{r, -1, cx, ry, cw, chipH});
            }
            ry += rh;
        }
    }

    // a label column as wide as the widest label, the chips start where it ends. the panel is as wide as the widest row
    private void measurePanel(Canvas c) {
        float pad = theme.pxf(PAD);
        float lw = 0;
        float chipsW = 0;
        float headW = 0;
        for (Row row : rows) {
            if (row.choices().isEmpty()) {
                headW = Math.max(headW, c.measure(row.label(), theme.label));
                continue;
            }
            lw = Math.max(lw, c.measure(row.label(), theme.smallLabel));
            chipsW = Math.max(chipsW, rowChipsWidth(c, row));
        }
        labelW = lw + theme.pxf(12);
        panelW = Math.max(labelW + chipsW, headW) + 2 * pad;
    }

    private float rowHeight(Canvas c, Row row) {
        return row.choices().isEmpty() ? c.lineHeight(theme.label) + theme.pxf(4) : theme.pxf(CHIP_H);
    }

    private float rowChipsWidth(Canvas c, Row row) {
        float w = -theme.pxf(CHIP_GAP);
        for (String s : row.choices()) {
            w += c.measure(s, theme.small) + 2 * theme.pxf(CHIP_PAD) + theme.pxf(CHIP_GAP);
        }
        if (row.toggle() != null) {
            w += 2 * theme.pxf(CHIP_GAP) + c.measure(row.toggle(), theme.small) + 2 * theme.pxf(CHIP_PAD);
        }
        return w;
    }

    // one pill, picked or not, hover from the last pointer position. returns its width
    private float chip(Canvas c, String s, float cx, float cy, boolean on) {
        float cw = c.measure(s, theme.small) + 2 * theme.pxf(CHIP_PAD);
        float chipH = theme.pxf(CHIP_H);
        boolean hv = hoverChipX >= cx && hoverChipY >= cy && hoverChipX < cx + cw && hoverChipY < cy + chipH;
        int fill = on ? c.color(Theme.ACCENT) : hv ? c.color(Theme.HOVER) : c.color(Theme.RAISED);
        c.fillRoundRect(cx, cy, cw, chipH, chipH / 2f, fill);
        float base = cy + (chipH + c.ascent(theme.small) - c.descent(theme.small)) / 2f;
        c.text(s, cx + theme.pxf(CHIP_PAD), base, theme.small, on ? c.color(Theme.ON_ACCENT) : c.color(Theme.TEXT));
        return cw;
    }
}
