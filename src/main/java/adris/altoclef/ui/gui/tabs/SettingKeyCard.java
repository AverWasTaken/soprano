package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.KeyNames;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.KeyCapture;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Toggle;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

// the open key, pinned above the list because it is the one setting people come here for. a card, not a row: the
// capture control and the on/off switch for the hotkey sit on the right, or under the text when the panel is narrow
final class SettingKeyCard extends Widget {

    private static final String TITLE = "Open key";
    private static final String ENABLED = "Enabled";
    private static final String BODY = "Opens this panel. Press a key to rebind, Backspace to unbind.";

    private final KeyCapture capture;
    private final Toggle enabled;
    private final List<Widget> kids;
    private boolean compact;
    private int labelW;
    private String bodyShown;

    SettingKeyCard(Theme theme, ShellContext ctx, KeyNames names, int key, boolean on, IntConsumer onKey, Consumer<Boolean> onEnabled) {
        super(theme);
        capture = new KeyCapture(theme, key, names, onKey, ctx);
        enabled = new Toggle(theme, on, onEnabled);
        kids = List.of(capture, enabled);
    }

    void setKey(int key) {
        capture.setKey(key);
    }

    void setEnabled(boolean on) {
        enabled.setOn(on);
    }

    KeyCapture capture() {
        return capture;
    }

    Toggle enabledToggle() {
        return enabled;
    }

    static boolean compactFor(Theme theme, int width) {
        return width < theme.px(520);
    }

    static int heightFor(Theme theme, int width) {
        return theme.px(compactFor(theme, width) ? 96 : 68);
    }

    @Override
    protected void layout() {
        compact = compactFor(theme, w);
        int pad = theme.px(16);
        int ch = theme.px(Theme.CONTROL_H);
        int kw = theme.px(112);
        int tw = enabled.preferredWidth();
        int th = enabled.preferredHeight();
        int cy = compact ? y + h - theme.px(12) - ch : y + (h - ch) / 2;
        int right = x + w - pad;
        enabled.setBounds(right - tw, cy + (ch - th) / 2, tw, th);
        // the label sits between the key box and the switch, measured so it never touches either
        labelW = Math.round(theme.measurer.measure(ENABLED, theme.small));
        capture.setBounds(right - tw - theme.px(8) - labelW - theme.px(14) - kw, cy, kw, ch);
        bodyShown = null;
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        float r = theme.pxf(8);
        c.fillRoundRect(x, y, w, h, r, Theme.RAISED);
        c.strokeRoundRect(x, y, w, h, r, Theme.HAIRLINE);
        int pad = theme.px(16);
        int is = theme.px(Theme.ICON);
        float textX = x + pad + is + theme.px(12);
        float lh1 = c.ascent(theme.label) + c.descent(theme.label);
        float lh2 = c.ascent(theme.small) + c.descent(theme.small);
        float gap = theme.px(3);
        float block = lh1 + gap + lh2;
        float top = compact ? y + theme.px(14) : y + (h - block) / 2f;
        float room = (compact ? x + w - pad : capture.x() - theme.px(16)) - textX;
        c.icon(Icon.KEY, x + pad, top + (block - is) / 2f, is, Theme.TEXT_2);
        c.text(TITLE, textX, top + c.ascent(theme.label), theme.label, Theme.TEXT);
        if (bodyShown == null) {
            bodyShown = theme.measurer.ellipsize(BODY, theme.small, Math.max(0, room));
        }
        c.text(bodyShown, textX, top + lh1 + gap + c.ascent(theme.small), theme.small, Theme.TEXT_2);

        capture.draw(c, f);
        c.text(ENABLED, enabled.x() - theme.px(8) - labelW, enabled.y() + enabled.h() / 2f + (c.ascent(theme.small) - c.descent(theme.small)) / 2f,
                theme.small, Theme.TEXT_2);
        enabled.draw(c, f);
    }
}
