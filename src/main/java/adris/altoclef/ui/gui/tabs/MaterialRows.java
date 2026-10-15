package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Kind;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Line;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;

// the rows of a material list. an ITEM row is the icon, the name, "have / needed" on the right and a two pixel bar
// under the name that fills with the accent as the player's stack grows, so the whole list reads as a checklist. the
// folded kinds (can't place, not counted) are one SECTION row each with a chevron, their lines only exist while open,
// and the facade's notes sit at the bottom as quiet small text
final class MaterialRows implements VirtualList.RowRenderer<MaterialRows.Entry> {

    enum Type { ITEM, SECTION, SUB, NOTE }

    // key tells rows apart across refreshes: the item id, the section name, or the note text
    record Entry(Type type, String key, Line line, String label, int count, boolean open) {

        static Entry item(Line l) {
            return new Entry(Type.ITEM, "item:" + l.itemId(), l, l.displayName(), 0, false);
        }

        static Entry sub(Line l) {
            return new Entry(Type.SUB, "sub:" + l.itemId(), l, l.displayName(), 0, false);
        }

        static Entry section(String name, int count, boolean open) {
            return new Entry(Type.SECTION, "section:" + name, null, name, count, open);
        }

        static Entry note(String text) {
            return new Entry(Type.NOTE, "note:" + text, null, text, 0, false);
        }
    }

    private final Theme theme;

    MaterialRows(Theme theme) {
        this.theme = theme;
    }

    @Override
    public void draw(Canvas c, Frame f, Entry e, int index, int rx, int ry, int rw, int rh, boolean hot) {
        int side = theme.px(Theme.SPACE_2);
        if (hot && e.type() != Type.NOTE) {
            c.fillRoundRect(rx + side, ry + theme.px(1), rw - 2 * side, rh - theme.px(2), theme.pxf(Theme.ROW_RADIUS), theme.color(Theme.HOVER));
        }
        switch (e.type()) {
            case ITEM, SUB -> line(c, e, rx, ry, rw, rh);
            case SECTION -> section(c, e, rx, ry, rw, rh, hot);
            default -> {
                float tx = rx + 2 * side;
                String s = c.ellipsize(e.label(), theme.small, Math.max(0, rw - 4 * side));
                c.text(s, tx, mid(c, theme.small, ry, rh), theme.small, theme.color(Theme.TEXT_3));
            }
        }
    }

    private void line(Canvas c, Entry e, int rx, int ry, int rw, int rh) {
        Line l = e.line();
        boolean item = l.kind() == Kind.ITEM;
        int side = theme.px(Theme.SPACE_2);
        int is = theme.itemIconPx();
        float ix = rx + 2 * side;
        drawIcon(c, l, ix, ry, rh);

        TextStyle num = theme.small.asTabular();
        String counts = SchematicRules.counts(l);
        float numW = c.measure(counts, num);
        float right = rx + rw - 2 * side;
        int numCol = item ? (l.covered() ? Theme.TEXT_2 : Theme.DANGER) : Theme.TEXT_3;
        c.text(counts, right - numW, mid(c, num, ry, rh), num, theme.color(numCol));

        float tx = ix + is + theme.px(Theme.SPACE_3);
        float room = Math.max(theme.px(40), right - numW - theme.px(Theme.SPACE_3) - tx);
        float nameH = c.lineHeight(theme.body);
        float barH = item ? theme.px(2) : 0;
        float barGap = item ? theme.px(3) : 0;
        float top = ry + (rh - nameH - barGap - barH) / 2f;
        String name = c.ellipsize(e.label(), theme.body, room);
        float base = top + c.ascent(theme.body);
        float adv = c.text(name, tx, base, theme.body, theme.color(Theme.TEXT));
        if (name.equals(e.label())) {
            drawNote(c, l.note(), tx + adv, base, room - adv);
        }
        if (item) {
            float by = top + nameH + barGap;
            c.fillRoundRect(tx, by, room, barH, barH / 2f, theme.color(Theme.HAIRLINE));
            float fill = room * SchematicRules.coverage(l);
            if (fill >= 1f) {
                c.fillRoundRect(tx, by, fill, barH, barH / 2f, theme.color(Theme.ACCENT));
            }
        }
    }

    // a block with no item to stand for it gets the cube glyph, never an empty square
    private void drawIcon(Canvas c, Line l, float ix, int ry, int rh) {
        int is = theme.itemIconPx();
        if (l.iconItemId() == null || l.iconItemId().isEmpty()) {
            int gs = theme.px(Theme.ICON);
            c.icon(Icon.BLOCK, ix + (is - gs) / 2f, ry + (rh - gs) / 2f, gs, theme.color(Theme.TEXT_3));
        } else {
            c.itemIcon(l.iconItemId(), ix, ry + (rh - is) / 2f, is);
        }
    }

    // the note rides after the name in small text, and is the first thing to go when the row is narrow
    private void drawNote(Canvas c, String note, float afterName, float base, float left) {
        if (note == null || note.isEmpty()) {
            return;
        }
        float gap = theme.px(6);
        if (left - gap > theme.px(30)) {
            c.text(c.ellipsize(note, theme.small, left - gap), afterName + gap, base, theme.small, theme.color(Theme.TEXT_3));
        }
    }

    private void section(Canvas c, Entry e, int rx, int ry, int rw, int rh, boolean hot) {
        int side = theme.px(Theme.SPACE_2);
        int is = theme.px(Theme.ICON);
        float ix = rx + 2 * side;
        c.icon(e.open() ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT, ix, ry + (rh - is) / 2f, is, theme.color(hot ? Theme.TEXT : Theme.TEXT_2));
        float tx = ix + is + theme.px(Theme.SPACE_2);
        float base = mid(c, theme.label, ry, rh);
        float adv = c.text(e.label(), tx, base, theme.label, theme.color(hot ? Theme.TEXT : Theme.TEXT_2));
        c.text(String.valueOf(e.count()), tx + adv + theme.px(6), base, theme.body.asTabular(), theme.color(Theme.TEXT_3));
    }

    private static float mid(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }
}
