package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Control;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Scroller;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.List;
import java.util.function.Consumer;

// what opens when a wire gets let go on empty canvas: the palette's list at the spot, picking one adds it and wires it.
// the shell closes it on an outside click or esc, which just drops the wire
final class AddNodeMenu extends Control {

    private static final int W = 200;
    private static final int PAD = 6;
    private static final int MAX_H = 360;

    private final List<NodeEntries.Line> lines;
    private final Consumer<NodeEntries.Entry> onPick;
    private final Scroller scroller = new Scroller();
    private final TextStyle rowStyle;
    private NodeEntries.Entry hot;

    AddNodeMenu(Theme theme, List<NodeEntries.Line> lines, float mx, float my, int boundX, int boundY, int boundW, int boundH, Consumer<NodeEntries.Entry> onPick) {
        super(theme);
        this.lines = lines;
        this.onPick = onPick;
        rowStyle = NodeEntries.rowStyle(theme);
        int mw = theme.px(W);
        int content = NodeEntries.heightOf(theme, lines) + 2 * theme.px(PAD);
        int mh = Math.min(Math.min(theme.px(MAX_H), content), Math.max(theme.px(80), boundH - 2 * theme.px(PAD)));
        int px = Math.round(mx) + theme.px(4);
        int py = Math.round(my) - theme.px(8);
        // stays inside the canvas, a menu half off the panel is a menu you can't finish reading
        px = Math.max(boundX, Math.min(px, boundX + boundW - mw));
        py = Math.max(boundY, Math.min(py, boundY + boundH - mh));
        setBounds(px, py, mw, mh);
        scroller.setSizes(content, mh);
        visible = true;
    }

    private NodeEntries.Entry entryAt(float mx, float my) {
        if (!contains(mx, my)) {
            return null;
        }
        return NodeEntries.at(theme, lines, my - y - theme.px(PAD) + scroller.offset(now));
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        Widget d = super.mouseMove(mx, my);
        hot = entryAt(mx, my);
        return d;
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        NodeEntries.Entry e = entryAt(mx, my);
        if (e != null && button == 0) {
            onPick.accept(e);
        }
        return true;
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        if (!contains(mx, my)) {
            return false;
        }
        scroller.scrollBy(-dy * theme.px(NodeEntries.ROW) * 2, now);
        return true;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        float r = theme.pxf(Theme.CARD_RADIUS);
        theme.floating(c, x, y, w, h, r);
        int pad = theme.px(PAD);
        c.pushClip(x + 1, y + 1, w - 2, h - 2);
        try {
            NodeEntries.draw(c, theme, lines, x + pad, y + pad - scroller.offset(now), w - 2 * pad, hot, rowStyle);
        } finally {
            c.popClip();
        }
        NodeEntries.drawThumb(c, theme, scroller, now, hovered, x + w, y, h);
    }

    // test hook: where a row is
    float rowY(String label) {
        float cy = y + theme.px(PAD) - scroller.offset(now);
        for (NodeEntries.Line l : lines) {
            float lh = theme.px(l.entry() == null ? NodeEntries.HEAD : NodeEntries.ROW);
            if (l.entry() != null && l.entry().label().equals(label)) {
                return cy + lh / 2f;
            }
            cy += lh;
        }
        return -1;
    }
}
