package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Control;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Theme;

// the big white button. the kit's primary button dims itself when disabled, which on a white fill reads as a grey pill
// that still looks clickable. this one goes properly flat with text_3 instead
final class GetGoButton extends Control {

    private final Runnable onClick;
    private final Anim hoverA = new Anim(0f);
    private String text = "";
    private boolean enabled;
    private boolean held;

    GetGoButton(Theme theme, Runnable onClick) {
        super(theme);
        this.onClick = onClick;
    }

    void setText(String t) {
        text = t == null ? "" : t;
    }

    String text() {
        return text;
    }

    void setEnabled(boolean e) {
        enabled = e;
        if (!e) {
            held = false;
        }
    }

    boolean enabled() {
        return enabled;
    }

    @Override
    public boolean focusable() {
        return visible && enabled;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        hoverTo(hoverA, hovered && enabled);
        float hv = hoverA.value(now);
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        int fill;
        int col;
        if (enabled) {
            fill = held ? 0xFFC8C8CD : Theme.lerp(Theme.ACCENT, 0xFFE4E4E7, hv);
            col = Theme.ON_ACCENT;
        } else {
            fill = Theme.HOVER;
            col = Theme.TEXT_3;
        }
        c.fillRoundRect(x, y, w, h, r, fill);
        if (focused) {
            focusRing(c, r, 1f);
        }
        String shown = theme.measurer.ellipsize(text, theme.label, w - 2 * theme.px(12));
        float tw = c.measure(shown, theme.label);
        c.text(shown, x + (w - tw) / 2f, baseline(c, theme.label, y, h), theme.label, col);
    }

    private void fire() {
        if (enabled && onClick != null) {
            Safe.run("get go", onClick);
        }
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my) || button != 0 || !enabled) {
            return false;
        }
        held = true;
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        boolean was = held;
        held = false;
        if (was && enabled && button == 0 && contains(mx, my)) {
            fire();
        }
        return was;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (focused && enabled && (Keys.isEnter(key) || key == Keys.SPACE)) {
            fire();
            return true;
        }
        return false;
    }
}
