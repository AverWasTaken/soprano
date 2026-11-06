package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.Scroller;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.TextWrap;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.List;

// floats top left over the canvas and folds into one button. click a row and it goes after the selected node, drag it and
// a chip follows the mouse until you let go on the canvas
final class NodePalette extends Widget {

    interface Host {
        void clicked(NodeEntries.Entry e);

        void dropped(NodeEntries.Entry e, float mx, float my);

        // the canvas doesn't get to think this press was its own
        void pressed();

        void foldChanged();
    }

    static final int W = 176;
    private static final int HEAD_H = 38;
    private static final int SHUT = 40;
    private static final int DRAG_THRESHOLD = 4;
    private static final String HINT = "Drag onto the canvas, or click to add after the selected node";

    private final Host host;
    private final List<NodeEntries.Line> lines;
    private final IconButton fold;
    private final List<Widget> kids;
    private final Scroller scroller = new Scroller();
    private final TextStyle rowStyle;
    private boolean open;
    private NodeEntries.Entry hot;
    private NodeEntries.Entry pressed;
    private boolean carrying;
    private float pressX;
    private float pressY;
    private float mouseX;
    private float mouseY;
    private long now;
    private int maxH;

    NodePalette(Theme theme, List<NodeEntries.Line> lines, Host host) {
        super(theme);
        this.host = host;
        this.lines = lines;
        rowStyle = NodeEntries.rowStyle(theme);
        fold = new IconButton(theme, Icon.PLUS, "Add a node", () -> {
            setOpen(!open);
            host.foldChanged();
        });
        fold.setFocusable(false);
        kids = List.of(fold);
    }

    boolean open() {
        return open;
    }

    void setOpen(boolean v) {
        open = v;
        fold.setIcon(v ? Icon.CHEVRON_LEFT : Icon.PLUS);
        if (w > 0) {
            place();
        }
    }

    void setMaxHeight(int px) {
        maxH = px;
    }

    int wantedW() {
        return theme.px(open ? W : SHUT);
    }

    private List<String> hintLines() {
        return TextWrap.wrap(theme.measurer, HINT, theme.small, Math.max(1, theme.px(W - 24)));
    }

    private int hintH() {
        return hintLines().size() * theme.px(15) + theme.px(16);
    }

    int wantedH() {
        if (!open) {
            return theme.px(SHUT);
        }
        int want = theme.px(HEAD_H) + NodeEntries.heightOf(theme, lines) + theme.px(6) + hintH();
        return maxH > 0 ? Math.min(maxH, want) : want;
    }

    private int bodyTop() {
        return y + theme.px(HEAD_H);
    }

    private int bodyH() {
        return Math.max(0, h - theme.px(HEAD_H) - hintH());
    }

    @Override
    protected void layout() {
        place();
    }

    private void place() {
        int b = theme.px(28);
        if (open) {
            fold.setBounds(x + w - theme.px(6) - b, y + (theme.px(HEAD_H) - b) / 2, b, b);
            scroller.setSizes(NodeEntries.heightOf(theme, lines) + theme.px(6), bodyH());
        } else {
            fold.setBounds(x + (w - b) / 2, y + (h - b) / 2, b, b);
        }
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    private NodeEntries.Entry entryAt(float mx, float my) {
        if (!open || my < bodyTop() || my >= bodyTop() + bodyH() || mx < x || mx >= x + w) {
            return null;
        }
        return NodeEntries.at(theme, lines, my - bodyTop() + scroller.offset(now));
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        host.pressed();
        if (!super.mouseDown(mx, my, button, mods) && button == 0) {
            pressed = entryAt(mx, my);
            carrying = false;
            pressX = mouseX = mx;
            pressY = mouseY = my;
        }
        return true;
    }

    @Override
    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        if (pressed == null) {
            return super.mouseDrag(mx, my, button, dx, dy);
        }
        mouseX = mx;
        mouseY = my;
        if (!carrying && Math.hypot(mx - pressX, my - pressY) >= theme.px(DRAG_THRESHOLD)) {
            carrying = true;
        }
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        if (pressed == null) {
            return super.mouseUp(mx, my, button, mods);
        }
        NodeEntries.Entry e = pressed;
        boolean drop = carrying;
        cancel();
        if (drop) {
            host.dropped(e, mx, my);
        } else if (contains(mx, my)) {
            host.clicked(e);
        }
        return true;
    }

    void cancel() {
        pressed = null;
        carrying = false;
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        Widget d = super.mouseMove(mx, my);
        hot = hovered ? entryAt(mx, my) : null;
        return d;
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        if (!contains(mx, my)) {
            return false;
        }
        if (open) {
            scroller.scrollBy(-dy * theme.px(NodeEntries.ROW) * 2, now);
        }
        return true;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        now = f.nowNanos();
        float r = theme.pxf(Theme.CARD_RADIUS);
        theme.card(c, x, y, w, h, r, true);
        fold.draw(c, f);
        if (!open) {
            return;
        }
        TextStyle l = theme.label;
        c.text("Add a node", x + theme.px(12), y + theme.px(HEAD_H) / 2f + (c.ascent(l) - c.descent(l)) / 2f, l, theme.color(Theme.TEXT));
        float off = scroller.offset(now);
        c.pushClip(x + 1, bodyTop(), w - 2, bodyH());
        try {
            NodeEntries.draw(c, theme, lines, x + theme.px(6), bodyTop() - off, w - 2 * theme.px(6), hot, rowStyle);
        } finally {
            c.popClip();
        }
        NodeEntries.drawThumb(c, theme, scroller, now, hovered, x + w, bodyTop(), bodyH());
        int top = bodyTop() + bodyH();
        c.fillRect(x + 1, top, w - 2, 1, theme.color(Theme.HAIRLINE));
        TextStyle s = theme.small;
        float ly = top + theme.px(7);
        for (String line : hintLines()) {
            c.text(line, x + theme.px(12), ly + c.ascent(s), s, theme.color(Theme.TEXT_3));
            ly += theme.px(15);
        }
    }

    // on top of everything, the editor draws it last
    void drawCarried(Canvas c) {
        if (!carrying || pressed == null) {
            return;
        }
        TextStyle s = rowStyle;
        int is = theme.px(Theme.ICON);
        float tw = c.measure(pressed.label(), s);
        float cw = theme.px(10) + is + theme.px(8) + tw + theme.px(10);
        float ch = theme.px(30);
        float cx = mouseX - theme.px(20);
        float cy = mouseY - theme.px(15);
        theme.floating(c, cx, cy, cw, ch, theme.px(8));
        c.icon(pressed.icon(), cx + theme.px(10), cy + (ch - is) / 2f, is, NodeEntries.ink(theme, pressed.group()));
        c.text(pressed.label(), cx + theme.px(10) + is + theme.px(8), cy + ch / 2f + (c.ascent(s) - c.descent(s)) / 2f, s, theme.color(Theme.TEXT));
    }

    // test hooks
    IconButton foldButton() {
        return fold;
    }

    float rowY(String label) {
        float cy = bodyTop() - scroller.offset(now);
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
