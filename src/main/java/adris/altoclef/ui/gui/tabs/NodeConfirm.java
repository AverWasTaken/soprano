package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Control;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.TextWrap;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.ArrayList;
import java.util.List;

// the "you have unsaved edits" popover. lives in the shell's overlay slot, so an outside click or esc is the same as
// Cancel, and a button only does anything once the popover is gone
final class NodeConfirm extends Control {

    record Choice(String label, Button.Kind kind, Runnable action) {
    }

    private static final int W = 340;
    private static final int PAD = 16;

    private final List<Button> buttons = new ArrayList<>();
    private final List<String> lines;
    private final String title;

    NodeConfirm(Theme theme, ShellContext ctx, String title, String body, List<Choice> choices, int cx, int cy) {
        super(theme);
        this.title = title;
        int pad = theme.px(PAD);
        lines = body == null || body.isEmpty() ? List.of() : TextWrap.wrap(theme.measurer, body, theme.small, theme.px(W) - 2f * pad);
        for (Choice ch : choices) {
            buttons.add(new Button(theme, ch.label(), ch.kind(), null, () -> {
                ctx.closeOverlay();
                ch.action().run();
            }));
        }
        int mw = theme.px(W);
        int mh = pad + theme.px(20) + (lines.isEmpty() ? 0 : theme.px(4) + lines.size() * theme.px(15)) + theme.px(Theme.SPACE_3) + theme.px(Theme.CONTROL_H) + pad;
        setBounds(cx - mw / 2, cy - mh / 2, mw, mh);
        visible = true;
    }

    @Override
    protected void layout() {
        int right = x + w - theme.px(PAD);
        int by = y + h - theme.px(PAD) - theme.px(Theme.CONTROL_H);
        for (int i = buttons.size() - 1; i >= 0; i--) {
            Button b = buttons.get(i);
            int bw = b.preferredWidth();
            b.setBounds(right - bw, by, bw, theme.px(Theme.CONTROL_H));
            right -= bw + theme.px(Theme.GAP);
        }
    }

    @Override
    public List<Widget> children() {
        return List.copyOf(buttons);
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        super.mouseDown(mx, my, button, mods);
        return true;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        theme.floating(c, x, y, w, h, theme.pxf(Theme.CARD_RADIUS));
        int pad = theme.px(PAD);
        TextStyle l = theme.label;
        float ty = y + pad;
        c.text(c.ellipsize(title, l, w - 2f * pad), x + pad, ty + c.ascent(l), l, theme.color(Theme.TEXT));
        ty += theme.px(20) + theme.px(4);
        TextStyle s = theme.small;
        for (String line : lines) {
            c.text(line, x + pad, ty + c.ascent(s), s, theme.color(Theme.TEXT_2));
            ty += theme.px(15);
        }
        for (Button b : buttons) {
            b.draw(c, f);
        }
    }

    // test hook
    List<Button> buttons() {
        return buttons;
    }
}
