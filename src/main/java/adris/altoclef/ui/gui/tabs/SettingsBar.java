package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Control;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.ArrayList;
import java.util.List;

// the strip on top of Show all: Back on the left, Reset all on the right. Reset all asks first, in place: the button
// turns into "Reset 5 changed settings?" with Cancel and a red Reset, same arm-then-commit idea as the waypoint delete
final class SettingsBar extends Control {

    // design px. the grid puts its first heading right under this
    static final int HEIGHT = 44;
    private static final int BUTTON_TOP = 12;

    private final Button back;
    private final Button resetAll;
    private final Button cancel;
    private final Button confirm;
    private final List<Widget> kids = new ArrayList<>();
    private boolean armed;
    private int changed;

    SettingsBar(Theme theme, Runnable onBack, Runnable onResetAll) {
        super(theme);
        back = new Button(theme, "Back", Button.Kind.GHOST, Icon.CHEVRON_LEFT, onBack);
        resetAll = new Button(theme, "Reset all", Button.Kind.SECONDARY, null, () -> setArmed(true));
        cancel = new Button(theme, "Cancel", Button.Kind.GHOST, null, () -> setArmed(false));
        confirm = new Button(theme, "Reset", Button.Kind.DANGER, null, () -> {
            setArmed(false);
            onResetAll.run();
        });
        sync();
    }

    // how many settings are not at their default. nothing changed means nothing to reset, the button says so by going grey
    void setChanged(int n) {
        changed = n;
        if (n == 0) {
            armed = false;
        }
        resetAll.setEnabled(n > 0);
        sync();
    }

    void setArmed(boolean a) {
        armed = a && changed > 0;
        sync();
    }

    boolean armed() {
        return armed;
    }

    String prompt() {
        return "Reset " + changed + (changed == 1 ? " changed setting?" : " changed settings?");
    }

    private void sync() {
        kids.clear();
        kids.add(back);
        if (armed) {
            kids.add(cancel);
            kids.add(confirm);
        } else {
            kids.add(resetAll);
        }
        // a button that just left the bar must not keep focus, or enter would press a button nobody can see
        for (Button b : List.of(resetAll, cancel, confirm)) {
            if (!kids.contains(b) && b.isFocused()) {
                b.setFocused(false);
            }
        }
        layout();
    }

    @Override
    protected void layout() {
        int bh = theme.px(Theme.CONTROL_H);
        int by = y + theme.px(BUTTON_TOP);
        back.setBounds(x, by, back.preferredWidth(), bh);
        int right = x + w;
        if (armed) {
            int cw = confirm.preferredWidth();
            confirm.setBounds(right - cw, by, cw, bh);
            int kw = cancel.preferredWidth();
            cancel.setBounds(right - cw - theme.px(Theme.GAP) - kw, by, kw, bh);
        } else {
            int rw = resetAll.preferredWidth();
            resetAll.setBounds(right - rw, by, rw, bh);
        }
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        for (Widget k : kids) {
            k.draw(c, f);
        }
        if (armed) {
            String p = prompt();
            float tw = c.measure(p, theme.body);
            float tx = cancel.x() - theme.px(12) - tw;
            // a narrow panel would push it into Back, it gives up before it overlaps
            if (tx > back.x() + back.w() + theme.px(8)) {
                c.text(p, tx, baseline(c, theme.body, cancel.y(), cancel.h()), theme.body, theme.color(Theme.TEXT_2));
            }
        }
    }

    // ----- for tests

    Button back() {
        return back;
    }

    Button resetAll() {
        return resetAll;
    }

    Button cancel() {
        return cancel;
    }

    Button confirm() {
        return confirm;
    }
}
