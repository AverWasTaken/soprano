package adris.altoclef.ui.gui.kit;

import java.util.function.Consumer;

// the on/off switch. the knob slides, the track fills cyan. setOn is for code (snaps, no callback), clicking is for
// people (animates, calls back)
public class Toggle extends Control {

    private boolean on;
    private boolean held;
    private final Consumer<Boolean> onChange;
    private final Spring slide;
    private final Anim hoverA = new Anim(0f);

    public Toggle(Theme theme, boolean on, Consumer<Boolean> onChange) {
        super(theme);
        this.on = on;
        this.onChange = onChange;
        this.slide = new Spring(on ? 1f : 0f);
    }

    public boolean on() {
        return on;
    }

    public void setOn(boolean v) {
        on = v;
        slide.snap(v ? 1f : 0f);
    }

    @Override
    public int preferredWidth() {
        return theme.px(34);
    }

    @Override
    public int preferredHeight() {
        return theme.px(18);
    }

    @Override
    public boolean focusable() {
        return visible;
    }

    private void flip() {
        on = !on;
        slide.set(on ? 1f : 0f, now, Theme.ANIM_FAST);
        if (onChange != null) {
            boolean v = on;
            Safe.run("toggle", () -> onChange.accept(v));
        }
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        hoverTo(hoverA, hovered);
        float tw = theme.px(34);
        float th = theme.px(18);
        float tx = x + (w - tw) / 2f;
        float ty = y + (h - th) / 2f;
        float s = slide.value(now);
        float hv = hoverA.value(now);
        int track = Theme.lerp(theme.color(Theme.HAIRLINE_STRONG), theme.color(Theme.ACCENT), s);
        track = Theme.lerp(track, on ? theme.color(Theme.ACCENT_HOVER) : theme.color(Theme.TEXT_3), hv * 0.6f);
        c.fillRoundRect(tx, ty, tw, th, th / 2f, track);
        theme.well(c, tx, ty, tw, th, th / 2f, 0.5f);
        if (focused) {
            c.strokeRoundRect(tx - 1, ty - 1, tw + 2, th + 2, th / 2f + 1, theme.color(Theme.HAIRLINE_STRONG));
        }
        float knob = theme.px(14);
        float pad = (th - knob) / 2f;
        float kx = tx + pad + (tw - knob - 2 * pad) * s;
        c.shadow(kx, ty + pad - knob / 4f, knob, knob, knob / 2f, Theme.fade(theme.color(Theme.SHADOW), 0.6f));
        c.fillRoundRect(kx, ty + pad, knob, knob, knob / 2f, 0xFFFFFFFF);
    }

    // a release only counts if the press started here, or dragging a text selection across a toggle would flip it
    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        held = contains(mx, my) && button == 0;
        return held;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        boolean was = held;
        held = false;
        if (was && button == 0 && contains(mx, my)) {
            flip();
        }
        return was;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (focused && (Keys.isEnter(key) || key == Keys.SPACE)) {
            flip();
            return true;
        }
        return false;
    }
}
