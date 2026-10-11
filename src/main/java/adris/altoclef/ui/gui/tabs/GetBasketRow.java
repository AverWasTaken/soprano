package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.FadeCanvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.RowWidgets;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.Basket;

import java.util.List;

// one line of the basket: icon, name, and in a get basket [-] count [+] x on the right. a mine row is just icon, name and
// x, the mine limit lives under the list. at rest only the count shows, the buttons fade in when the row is hovered or
// you are typing in it, so a long basket is a quiet list and not a wall of buttons
final class GetBasketRow extends RowWidgets.Row<GetBasketRow.Item> {

    record Item(ItemEntry entry, int count, boolean mine) {
    }

    interface Actions {
        void step(String name, int delta);

        void set(String name, int count);

        void remove(String name);

        // -1 when the line is gone
        int countOf(String name);

        void blur();

        // the shared mine limit, 0 is no limit
        void setLimit(int n);

        int limit();
    }

    private final Actions actions;
    private final IconButton minus;
    private final IconButton plus;
    private final IconButton remove;
    private final GetCountField count;
    private final List<Widget> getKids;
    private final List<Widget> mineKids;
    private final Anim hotA = new Anim(0f);
    private final FadeCanvas fade = new FadeCanvas();
    private Item item;
    private String shownName = "";
    private int iconX;

    GetBasketRow(Theme theme, Actions actions) {
        super(theme);
        this.actions = actions;
        minus = new IconButton(theme, Icon.MINUS, "Less", () -> nudge(-1));
        plus = new IconButton(theme, Icon.PLUS, "More", () -> nudge(1));
        remove = new IconButton(theme, Icon.CLOSE, "Remove", () -> {
            if (item != null) {
                actions.remove(item.entry().name());
            }
        });
        minus.setFocusable(false);
        plus.setFocusable(false);
        remove.setFocusable(false);
        count = new GetCountField(theme, new GetCountField.Host() {
            @Override
            public void commit(int n) {
                if (item != null) {
                    actions.set(item.entry().name(), n);
                    syncText();
                }
            }

            @Override
            public void step(int dir, int mods) {
                if (item != null) {
                    actions.step(item.entry().name(), dir * Basket.stepFor(mods, item.entry().maxStack()));
                    syncText();
                }
            }

            @Override
            public void done() {
                actions.blur();
            }
        });
        getKids = List.of(minus, count, plus, remove);
        mineKids = List.of(remove);
        setControlsVisible(false);
    }

    IconButton minus() {
        return minus;
    }

    IconButton plus() {
        return plus;
    }

    IconButton removeButton() {
        return remove;
    }

    TextField countField() {
        return count;
    }

    String name() {
        return item == null ? null : item.entry().name();
    }

    private boolean mine() {
        return item != null && item.mine();
    }

    private void nudge(int dir) {
        IconButton b = dir < 0 ? minus : plus;
        if (item == null) {
            return;
        }
        actions.step(item.entry().name(), dir * Basket.stepFor(b.lastMods(), item.entry().maxStack()));
    }

    // pulls the number back from the basket, for when this row is focused and the list skipped rebinding it
    private void syncText() {
        if (item == null) {
            return;
        }
        int c = actions.countOf(item.entry().name());
        if (c >= 0) {
            count.sync(c);
        }
    }

    @Override
    public void bind(Item next, int index) {
        item = next;
        count.bind(next.mine() ? 0 : next.count(), "0");
        hotA.snap(hot() ? 1f : 0f);
        setControlsVisible(hot());
        refreshName();
    }

    // a narrow basket gives the name the room instead of the bigger icon
    private int iconPx() {
        return w >= theme.px(270) ? theme.itemIconPx() * 2 : theme.itemIconPx();
    }

    private int btn() {
        return theme.px(22);
    }

    @Override
    protected void layout() {
        int b = btn();
        int gap = theme.px(2);
        int cw = theme.px(48);
        int by = y + (h - b) / 2;
        int right = x + w - theme.px(6);
        remove.setBounds(right - b, by, b, b);
        plus.setBounds(right - b - gap - b, by, b, b);
        count.setBounds(plus.x() - gap - cw, y + (h - theme.px(28)) / 2, cw, theme.px(28));
        minus.setBounds(count.x() - gap - b, by, b, b);
        iconX = x + theme.px(10);
        refreshName();
    }

    private void refreshName() {
        if (item == null || w <= 0) {
            shownName = "";
            return;
        }
        // a mine row only has the x on the right
        int stop = mine() ? remove.x() : minus.x();
        float room = stop - theme.px(8) - (iconX + iconPx() + theme.px(10));
        shownName = theme.measurer.ellipsize(item.entry().displayName(), theme.body, Math.max(0, room));
    }

    private void setControlsVisible(boolean on) {
        minus.setVisible(on && !mine());
        plus.setVisible(on && !mine());
        remove.setVisible(on);
    }

    private boolean hot() {
        return hovered || count.isFocused();
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        Widget d = super.mouseMove(mx, my);
        boolean on = hot();
        if (remove.visible() != on) {
            setControlsVisible(on);
            d = super.mouseMove(mx, my);
        }
        return d;
    }

    @Override
    public List<Widget> children() {
        return mine() ? mineKids : getKids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        if (item == null) {
            return;
        }
        long now = f.nowNanos();
        boolean hot = hot();
        if (remove.visible() != hot) {
            setControlsVisible(hot);
        }
        hotA.set(hot ? 1f : 0f, now, Theme.ANIM_FAST);
        float v = hotA.value(now);
        if (v > 0.01f) {
            c.fillRoundRect(x, y + theme.px(1), w, h - theme.px(2), theme.pxf(Theme.ROW_RADIUS), Theme.fade(Theme.HOVER, v));
        }
        int s = iconPx();
        c.itemIcon(item.entry().iconItemId(), iconX, y + (h - s) / 2, s);
        float tx = iconX + s + theme.px(10);
        c.text(shownName, tx, y + h / 2f + (c.ascent(theme.body) - c.descent(theme.body)) / 2f, theme.body, Theme.TEXT);

        if (v > 0.02f) {
            Canvas g = fade.reset(c, v, 0f);
            if (!mine()) {
                minus.draw(g, f);
                plus.draw(g, f);
            }
            remove.draw(g, f);
        }
        if (mine()) {
            return;
        }
        // at rest the number hugs the right edge, on hover it slides left to make room for the buttons
        if (count.isFocused() || v >= 0.99f) {
            count.draw(c, f);
        } else {
            String t = count.text();
            float tw = c.measure(t, theme.body);
            float restX = x + w - theme.px(10) - tw;
            float boxX = count.x() + Math.max(theme.px(4), (count.w() - tw) / 2f);
            float base = count.y() + count.h() / 2f + (c.ascent(theme.body) - c.descent(theme.body)) / 2f;
            c.text(t, restX + (boxX - restX) * v, base, theme.body, Theme.TEXT_2);
        }
    }
}
