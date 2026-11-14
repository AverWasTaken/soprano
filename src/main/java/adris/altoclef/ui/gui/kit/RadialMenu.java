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

// the effortless build mode's picker: a ring of round shape tiles around a "none" hub, centred, with a panel either
// side. the left one is about blocks (what goes down, where it comes from, what the clicks touch), the right one about
// the shape picked (its name, how it's clicked out, its options). every option is a label with a segmented control
// under it, the full width of the panel, so nothing crowds: one decision per line, the same rhythm all the way down.
// hovering the ring goes by angle once the pointer is past the hub, so a flick towards a tile is enough. no minecraft in
// here, the preview tests draw it
public class RadialMenu extends Widget {

    public record Entry(Icon icon, String label) {
    }

    // what a panel holds, top to bottom
    public sealed interface Item permits Title, Choice, Stepper, Action, Note {
    }

    // the panel's heading, and a line under it saying what to do
    public record Title(String text, String hint) implements Item {
    }

    // a label over a segmented control, one of the options picked
    public record Choice(String label, List<String> options, int picked, IntConsumer onPick) implements Item {
    }

    // one compact line: a name with a detail after it, a - n + stepper, and maybe a chip that turns on and off (the
    // mix's slots: block, share, weight, "Turn")
    public record Stepper(String label, String detail, int value, int min, int max, IntConsumer onSet, String toggle,
                          boolean toggled, Runnable onToggle) implements Item {
    }

    // a full width button
    public record Action(String label, Runnable onClick) implements Item {
    }

    // a quiet line of text
    public record Note(String text) implements Item {
    }

    public interface Listener {
        // index into the entries, -1 for the hub (none)
        void select(int index);
    }

    public static final int HUB = -1;
    public static final int NOTHING = -2;

    static final int RING_R = 116;
    static final int TILE = 56;
    static final int HUB_R = 36;
    static final int PANEL_W = 288;
    static final int GAP = 36;
    static final int PAD = 18;
    // between one option and the next, and between a label and its control
    static final int ITEM_GAP = 16;
    static final int LABEL_GAP = 7;
    static final int CONTROL_H = 28;
    static final int STEP_H = 26;
    static final int SEG_PAD = 10;

    private final List<Entry> entries;
    private final Listener listener;
    private List<Item> left = List.of();
    private List<Item> right = List.of();
    private int selected = HUB;
    private int hover = NOTHING;
    private float pointerX = Float.NaN;
    private float pointerY = Float.NaN;
    // hit boxes from the last draw
    private final List<Hit> hits = new ArrayList<>();
    // the panels' boxes from the last draw, the ring ignores the pointer over them
    private final float[] leftBox = new float[4];
    private final float[] rightBox = new float[4];
    private float leftW = -1;
    private float rightW = -1;

    private record Hit(float x, float y, float w, float h, Runnable run) {
        boolean in(float px, float py) {
            return px >= x && py >= y && px < x + w && py < y + h;
        }
    }

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

    public void setPanels(List<Item> left, List<Item> right) {
        this.left = List.copyOf(left);
        this.right = List.copyOf(right);
    }

    // ----- geometry

    // the ring is always in the middle of the screen, the panels hang off its sides
    float centerX() {
        return x + w / 2f;
    }

    float centerY() {
        return y + h / 2f;
    }

    // the ring grows with the number of tiles so neighbours keep a gap between them
    float ringR() {
        float fit = (TILE + 10) * entries.size() / (float) (2 * Math.PI);
        return theme.pxf(Math.max(RING_R, fit));
    }

    float tileX(int i) {
        return centerX() + ringR() * (float) Math.sin(angle(i));
    }

    float tileY(int i) {
        return centerY() - ringR() * (float) Math.cos(angle(i));
    }

    // clockwise from the top
    double angle(int i) {
        return 2 * Math.PI * i / entries.size();
    }

    float ringOuter() {
        return ringR() + theme.pxf(TILE) / 2f + theme.pxf(12);
    }

    float rightX() {
        return centerX() + ringOuter() + theme.pxf(GAP);
    }

    float leftX(float panelW) {
        return centerX() - ringOuter() - theme.pxf(GAP) - panelW;
    }

    // which tile the pointer means: the hub inside it, a tile by angle outside it, nothing far off the ring
    public int pick(float px, float py) {
        float dx = px - centerX();
        float dy = py - centerY();
        double d = Math.sqrt(dx * dx + dy * dy);
        if (d <= theme.pxf(HUB_R)) {
            return HUB;
        }
        if (d > ringR() + theme.pxf(TILE)) {
            return NOTHING;
        }
        double a = Math.atan2(dx, -dy);
        if (a < 0) {
            a += 2 * Math.PI;
        }
        double step = 2 * Math.PI / entries.size();
        return (int) Math.round(a / step) % entries.size();
    }

    private static boolean inBox(float[] b, float px, float py) {
        return px >= b[0] && py >= b[1] && px < b[0] + b[2] && py < b[1] + b[3];
    }

    // ----- input

    @Override
    public Widget mouseMove(float mx, float my) {
        super.mouseMove(mx, my);
        pointerX = mx;
        pointerY = my;
        hover = inBox(leftBox, mx, my) || inBox(rightBox, mx, my) ? NOTHING : pick(mx, my);
        return this;
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        for (Hit hit : hits) {
            if (hit.in(mx, my)) {
                hit.run().run();
                return true;
            }
        }
        if (inBox(leftBox, mx, my) || inBox(rightBox, mx, my)) {
            return true;
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
        hits.clear();
        leftW = panelWidth(c, left);
        rightW = panelWidth(c, right);
        drawRing(c);
        drawPanel(c, left, leftX(leftW), leftW, leftBox);
        drawPanel(c, right, rightX(), rightW, rightBox);
    }

    private void drawRing(Canvas c) {
        float cx = centerX();
        float cy = centerY();
        float tile = theme.pxf(TILE);
        float back = ringOuter();
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
        c.text(title, cx - tw / 2f, cy + back + theme.pxf(28), theme.label, c.color(Theme.TEXT));
    }

    // ----- panels

    // as wide as PANEL_W, or wider when a control's options can't fit at equal widths
    private float panelWidth(Canvas c, List<Item> items) {
        if (items.isEmpty()) {
            return 0;
        }
        float pad = theme.pxf(PAD);
        float inner = theme.pxf(PANEL_W) - 2 * pad;
        for (Item it : items) {
            if (it instanceof Choice ch && !ch.options().isEmpty()) {
                float widest = 0;
                for (String o : ch.options()) {
                    widest = Math.max(widest, c.measure(o, theme.small));
                }
                inner = Math.max(inner, ch.options().size() * (widest + 2 * theme.pxf(SEG_PAD)));
            } else if (it instanceof Stepper st) {
                // the name and its share, then the stepper and the toggle's column, capped so one long name can't
                // stretch the panel across the screen
                float text = c.measure(st.label(), theme.small) + (st.detail() == null ? 0 : theme.pxf(8) + c.measure(st.detail(), theme.small));
                float controls = theme.pxf(STEP_H) * 2 + theme.pxf(26) + c.measure("Turn", theme.small) + 2 * theme.pxf(SEG_PAD) + theme.pxf(8);
                inner = Math.max(inner, Math.min(text, theme.pxf(200)) + theme.pxf(10) + controls);
            } else if (it instanceof Title t) {
                inner = Math.max(inner, Math.min(c.measure(t.text(), theme.title), theme.pxf(PANEL_W * 2)));
            }
        }
        return inner + 2 * pad;
    }

    private float itemHeight(Canvas c, Item it) {
        return switch (it) {
            case Title t -> c.lineHeight(theme.title) + (t.hint() == null ? 0 : theme.pxf(4) + c.lineHeight(theme.small));
            case Choice ch -> c.lineHeight(theme.smallLabel) + theme.pxf(LABEL_GAP) + theme.pxf(CONTROL_H);
            case Stepper s -> theme.pxf(STEP_H);
            case Action a -> theme.pxf(CONTROL_H);
            case Note n -> c.lineHeight(theme.small);
        };
    }

    // steppers sit closer together than options: a mix of nine slots reads as one list, not nine settings
    private float gapBefore(Item prev, Item it) {
        if (prev == null) {
            return 0;
        }
        if (prev instanceof Stepper && it instanceof Stepper) {
            return theme.pxf(6);
        }
        if (prev instanceof Title) {
            return theme.pxf(ITEM_GAP + 4);
        }
        return theme.pxf(ITEM_GAP);
    }

    private void drawPanel(Canvas c, List<Item> items, float px, float pw, float[] box) {
        if (items.isEmpty()) {
            box[0] = box[1] = box[2] = box[3] = 0;
            return;
        }
        float pad = theme.pxf(PAD);
        float ph = 2 * pad;
        Item prev = null;
        for (Item it : items) {
            ph += gapBefore(prev, it) + itemHeight(c, it);
            prev = it;
        }
        // centred on the ring, kept on screen
        float py = Math.max(y + theme.pxf(12), Math.min(centerY() - ph / 2f, y + h - ph - theme.pxf(12)));
        box[0] = px;
        box[1] = py;
        box[2] = pw;
        box[3] = ph;
        float r = theme.pxf(Theme.CARD_RADIUS);
        c.shadow(px, py, pw, ph, r, c.color(Theme.SHADOW));
        c.fillRoundRect(px, py, pw, ph, r, c.color(Theme.PANEL) & 0xF2FFFFFF);
        float ix = px + pad;
        float iw = pw - 2 * pad;
        float iy = py + pad;
        prev = null;
        for (Item it : items) {
            float gap = gapBefore(prev, it);
            if (gap > 0 && !(prev instanceof Stepper && it instanceof Stepper) && !(prev instanceof Title)
                    && it instanceof Title) {
                // a second title starts a new part of the panel: a hairline across
                c.fillRect(ix, iy + gap / 2f, iw, 1, c.color(Theme.HAIRLINE));
            }
            iy += gap;
            float ih = itemHeight(c, it);
            switch (it) {
                case Title t -> drawTitle(c, t, ix, iy, iw);
                case Choice ch -> drawChoice(c, ch, ix, iy, iw);
                case Stepper s -> drawStepper(c, s, ix, iy, iw);
                case Action a -> drawAction(c, a, ix, iy, iw);
                case Note n -> c.text(fit(c, n.text(), theme.small, iw), ix, iy + c.ascent(theme.small), theme.small, c.color(Theme.TEXT_3));
            }
            iy += ih;
            prev = it;
        }
    }

    private void drawTitle(Canvas c, Title t, float x, float y, float w) {
        c.text(fit(c, t.text(), theme.title, w), x, y + c.ascent(theme.title), theme.title, c.color(Theme.TEXT));
        if (t.hint() != null) {
            float hy = y + c.lineHeight(theme.title) + theme.pxf(4);
            c.text(fit(c, t.hint(), theme.small, w), x, hy + c.ascent(theme.small), theme.small, c.color(Theme.TEXT_2));
        }
    }

    private void drawChoice(Canvas c, Choice ch, float x, float y, float w) {
        c.text(ch.label().toUpperCase(java.util.Locale.ROOT), x, y + c.ascent(theme.smallLabel), theme.smallLabel, c.color(Theme.TEXT_3));
        float sy = y + c.lineHeight(theme.smallLabel) + theme.pxf(LABEL_GAP);
        segmented(c, ch.options(), ch.picked(), ch.onPick(), x, sy, w, theme.pxf(CONTROL_H));
    }

    // a track with the options at equal widths, the picked one raised in the accent colour
    private void segmented(Canvas c, List<String> options, int picked, IntConsumer onPick, float x, float y, float w, float h) {
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        c.fillRoundRect(x, y, w, h, r, c.color(Theme.FIELD));
        int n = options.size();
        float seg = w / n;
        float inset = theme.pxf(2);
        for (int i = 0; i < n; i++) {
            float sx = x + i * seg;
            boolean on = i == picked;
            boolean hv = pointerX >= sx && pointerY >= y && pointerX < sx + seg && pointerY < y + h;
            if (on) {
                c.fillRoundRect(sx + inset, y + inset, seg - 2 * inset, h - 2 * inset, r - inset, c.color(Theme.ACCENT));
            } else if (hv) {
                c.fillRoundRect(sx + inset, y + inset, seg - 2 * inset, h - 2 * inset, r - inset, c.color(Theme.HOVER));
            } else if (i > 0 && i - 1 != picked) {
                // a thin divider between two options that aren't picked
                c.fillRect(sx, y + h * 0.28f, 1, h * 0.44f, c.color(Theme.HAIRLINE));
            }
            String s = fit(c, options.get(i), theme.small, seg - theme.pxf(6));
            float tw = c.measure(s, theme.small);
            float base = y + (h + c.ascent(theme.small) - c.descent(theme.small)) / 2f;
            c.text(s, sx + (seg - tw) / 2f, base, theme.small, on ? c.color(Theme.ON_ACCENT) : c.color(Theme.TEXT));
            int at = i;
            hits.add(new Hit(sx, y, seg, h, () -> onPick.accept(at)));
        }
    }

    private void drawStepper(Canvas c, Stepper s, float x, float y, float w) {
        float h = theme.pxf(STEP_H);
        float btn = h;
        float valueW = theme.pxf(26);
        // the toggle's column is kept even on a line without one, so the steppers line up down the list
        float toggleW = c.measure("Turn", theme.small) + 2 * theme.pxf(SEG_PAD);
        if (s.toggle() != null) {
            toggleW = Math.max(toggleW, c.measure(s.toggle(), theme.small) + 2 * theme.pxf(SEG_PAD));
        }
        float controls = btn * 2 + valueW + toggleW + theme.pxf(8);
        float base = y + (h + c.ascent(theme.small) - c.descent(theme.small)) / 2f;
        boolean off = s.value() <= s.min();
        float textW = w - controls - theme.pxf(10);
        float lw = c.measure(fit(c, s.label(), theme.small, textW), theme.small);
        c.text(fit(c, s.label(), theme.small, textW), x, base, theme.small, off ? c.color(Theme.TEXT_3) : c.color(Theme.TEXT));
        if (s.detail() != null && lw + theme.pxf(8) < textW) {
            c.text(fit(c, s.detail(), theme.small, textW - lw - theme.pxf(8)), x + lw + theme.pxf(8), base, theme.small, c.color(Theme.TEXT_3));
        }
        float cx = x + w - controls;
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        float tx = cx + btn * 2 + valueW + theme.pxf(8);
        c.fillRoundRect(cx, y, btn * 2 + valueW, h, r, c.color(Theme.FIELD));
        button(c, Icon.MINUS, cx, y, btn, h, s.value() > s.min(), () -> s.onSet().accept(s.value() - 1));
        String v = s.value() <= s.min() ? "off" : String.valueOf(s.value());
        float vw = c.measure(v, theme.small);
        c.text(v, cx + btn + (valueW - vw) / 2f, base, theme.small, off ? c.color(Theme.TEXT_3) : c.color(Theme.TEXT));
        button(c, Icon.PLUS, cx + btn + valueW, y, btn, h, s.value() < s.max(), () -> s.onSet().accept(s.value() + 1));
        if (s.toggle() != null) {
            boolean on = s.toggled();
            boolean hv = pointerX >= tx && pointerY >= y && pointerX < tx + toggleW && pointerY < y + h;
            c.fillRoundRect(tx, y, toggleW, h, r, on ? c.color(Theme.ACCENT) : hv ? c.color(Theme.HOVER) : c.color(Theme.RAISED));
            float tw = c.measure(s.toggle(), theme.small);
            c.text(s.toggle(), tx + (toggleW - tw) / 2f, base, theme.small, on ? c.color(Theme.ON_ACCENT) : c.color(Theme.TEXT));
            hits.add(new Hit(tx, y, toggleW, h, s.onToggle()));
        }
    }

    private void button(Canvas c, Icon icon, float x, float y, float w, float h, boolean enabled, Runnable run) {
        boolean hv = enabled && pointerX >= x && pointerY >= y && pointerX < x + w && pointerY < y + h;
        if (hv) {
            c.fillRoundRect(x + theme.pxf(2), y + theme.pxf(2), w - theme.pxf(4), h - theme.pxf(4), theme.pxf(Theme.CONTROL_RADIUS) - theme.pxf(2), c.color(Theme.HOVER));
        }
        float is = h * 0.5f;
        c.icon(icon, x + (w - is) / 2f, y + (h - is) / 2f, is, enabled ? c.color(Theme.TEXT) : c.color(Theme.TEXT_3));
        if (enabled) {
            hits.add(new Hit(x, y, w, h, run));
        }
    }

    private void drawAction(Canvas c, Action a, float x, float y, float w) {
        float h = theme.pxf(CONTROL_H);
        boolean hv = pointerX >= x && pointerY >= y && pointerX < x + w && pointerY < y + h;
        c.fillRoundRect(x, y, w, h, theme.pxf(Theme.CONTROL_RADIUS), hv ? c.color(Theme.HOVER) : c.color(Theme.RAISED));
        float tw = c.measure(a.label(), theme.small);
        float base = y + (h + c.ascent(theme.small) - c.descent(theme.small)) / 2f;
        c.text(a.label(), x + (w - tw) / 2f, base, theme.small, c.color(Theme.TEXT));
        hits.add(new Hit(x, y, w, h, a.onClick()));
    }

    // the string cut down with an ellipsis until it fits
    private static String fit(Canvas c, String s, TextStyle style, float w) {
        if (s == null || c.measure(s, style) <= w) {
            return s;
        }
        String t = s;
        while (t.length() > 1 && c.measure(t + "…", style) > w) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }
}
