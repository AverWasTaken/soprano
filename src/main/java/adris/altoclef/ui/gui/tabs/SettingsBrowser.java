package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.RowWidgets;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualView;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.List;

// one scrollbar for the tiles and the rows below them. the row list still only builds its visible editors
final class SettingsBrowser extends VirtualView<SettingView> {

    private final CommonSettings common;
    private final RowWidgets<SettingView> list;

    SettingsBrowser(Theme theme, CommonSettings common, RowWidgets<SettingView> list) {
        super(theme);
        this.common = common;
        this.list = list;
        list.setScrollbarVisible(false);
        common.setReveal((top, bottom) -> scroller.ensureVisible(top, bottom, now));
    }

    private int headerHeight() {
        return common.visible() && common.size() > 0 ? common.heightFor(Math.max(0, w - gutter())) + theme.px(30) : 0;
    }

    @Override
    protected int contentHeight() {
        return headerHeight() + list.items().size() * list.rowHeight();
    }

    @Override
    protected int step() {
        return list.rowHeight();
    }

    @Override
    protected void layout() {
        super.layout();
        sync();
    }

    private void sync() {
        int off = Math.round(scroller.offset(now));
        int header = headerHeight();
        int width = Math.max(0, w - gutter());
        common.setBounds(x, y - off, width, common.heightFor(width));
        int top = Math.min(h, Math.max(0, header - off));
        list.setBounds(x, y + top, width, Math.max(0, h - top));
        list.scroller().snapTo(Math.max(0, off - header));
    }

    void refresh(boolean resetScroll) {
        if (resetScroll) {
            scroller.snapTo(0f);
        }
        layout();
    }

    @Override
    public List<Widget> children() {
        return common.visible() ? List.of(common, list) : List.of(list);
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        sync();
        return super.mouseMove(contains(mx, my) ? mx : -Float.MAX_VALUE, contains(mx, my) ? my : -Float.MAX_VALUE);
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        if (super.mouseDown(mx, my, button, mods)) {
            return true;
        }
        sync();
        for (Widget child : children()) {
            if (child.mouseDown(mx, my, button, mods)) {
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        boolean handled = super.mouseUp(mx, my, button, mods);
        if (!contains(mx, my)) {
            mx = -Float.MAX_VALUE;
            my = -Float.MAX_VALUE;
        }
        for (Widget child : children()) {
            handled |= child.mouseUp(mx, my, button, mods);
        }
        return handled;
    }

    @Override
    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        if (super.mouseDrag(mx, my, button, dx, dy)) {
            return true;
        }
        for (Widget child : children()) {
            if (child.mouseDrag(mx, my, button, dx, dy)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        resize();
        sync();
        c.pushClip(x, y, w, h);
        try {
            if (common.visible() && common.size() > 0) {
                common.draw(c, f);
                c.text("Advanced settings", x + theme.px(6), common.y() + common.h() + theme.px(20), theme.label, theme.color(Theme.TEXT));
            }
            theme.card(c, list.x(), list.y(), list.w(), list.h(), theme.pxf(12), false);
            list.draw(c, f);
        } finally {
            c.popClip();
        }
        drawScrollbar(c);
    }
}
