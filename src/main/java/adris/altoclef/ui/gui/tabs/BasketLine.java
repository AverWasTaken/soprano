package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Weight;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.RoutineDraft;
import adris.altoclef.ui.gui.model.RoutineDraft.Item;
import adris.altoclef.ui.gui.model.RoutineDraft.Row;

import java.util.List;

// one item in a get step: the texture, the name over its id, the count and the bin. the list view's row has the steppers,
// the inspector's narrower box goes without them (the mock's one size down) and up and down in the count still work
final class BasketLine extends Widget {

    interface Owner {
        Row row();

        // the step has a problem, so a bad count or an unknown id can go red
        boolean red();

        boolean locked();

        ItemEntry item(String id);

        void edited();

        void structure();

        void blur();
    }

    private final Owner owner;
    private final boolean steppers;
    private final IconButton minus;
    private final IconButton plus;
    private final IconButton remove;
    private final GetCountField count;
    private final List<Widget> lineKids;
    private final TextStyle nameStyle;
    private Item it;
    private int i;

    BasketLine(Theme theme, Owner owner, boolean steppers) {
        super(theme);
        this.owner = owner;
        this.steppers = steppers;
        nameStyle = steppers ? theme.body : new TextStyle(Weight.REGULAR, theme.px(13));
        minus = new IconButton(theme, Icon.MINUS, "Fewer", () -> nudge(-1));
        plus = new IconButton(theme, Icon.PLUS, "More", () -> nudge(1));
        remove = new StepRow.Bin(theme, "Remove", () -> {
            Row row = owner.row();
            if (row != null && it != null) {
                row.items.remove(it);
                owner.structure();
            }
        });
        for (IconButton b : new IconButton[]{minus, plus, remove}) {
            b.setFocusable(false);
        }
        count = new GetCountField(theme, new GetCountField.Host() {
            @Override
            public void commit(int n) {
                set(n);
            }

            @Override
            public void step(int dir, int mods) {
                nudge(dir);
            }

            @Override
            public void done() {
                owner.blur();
            }
        });
        // a count you are still typing counts as typed, so the line and the red follow every key
        count.setOnChange(t -> {
            if (it != null) {
                it.count = t.trim();
                owner.edited();
            }
        });
        lineKids = steppers ? List.of(minus, count, plus, remove) : List.of(count, remove);
    }

    void bind(Item item, int idx) {
        boolean same = item == it;
        it = item;
        i = idx;
        if (!same || !count.isFocused()) {
            count.bind(RoutineDraft.countOf(item), "1");
            if (item.count.isEmpty()) {
                count.setText("");
            }
        }
    }

    void setRed(boolean bad) {
        int n = it == null ? 0 : RoutineDraft.countOf(it);
        count.setInvalid(bad && (n < 1 || n > 9999));
    }

    void unfocus() {
        count.setFocused(false);
    }

    boolean countFocused() {
        return count.isFocused();
    }

    void settle() {
        if (it == null || count.isFocused()) {
            return;
        }
        String t = count.text().trim();
        if (!t.isEmpty() && !t.equals(it.count.trim())) {
            it.count = t;
            owner.edited();
        }
    }

    private void nudge(int dir) {
        if (it != null) {
            set(Math.max(1, Math.min(9999, RoutineDraft.countOf(it) + dir)));
        }
    }

    private void set(int n) {
        if (it == null) {
            return;
        }
        it.count = String.valueOf(Math.max(1, Math.min(9999, n)));
        count.sync(RoutineDraft.countOf(it));
        owner.edited();
    }

    private int btn() {
        return theme.px(28);
    }

    private int countW() {
        return theme.px(steppers ? 48 : 44);
    }

    @Override
    protected void layout() {
        int b = btn();
        int gap = theme.px(Theme.SPACE_1);
        int by = y + (h - b) / 2;
        int right = x + w - theme.px(4);
        remove.setBounds(right - b, by, b, b);
        if (steppers) {
            plus.setBounds(remove.x() - gap - b, by, b, b);
            count.setBounds(plus.x() - gap - countW(), by, countW(), b);
            minus.setBounds(count.x() - gap - b, by, b, b);
        } else {
            count.setBounds(remove.x() - gap - countW(), by, countW(), b);
        }
    }

    @Override
    public List<Widget> children() {
        return owner.locked() ? List.of() : lineKids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        // drawn by the row so the basket's box goes under it
    }

    void drawLine(Canvas c, Frame f, boolean locked) {
        if (it == null) {
            return;
        }
        if (i > 0) {
            c.fillRect(x + theme.px(6), y, w - 2 * theme.px(6), 1, theme.color(Theme.HAIRLINE));
        }
        ItemEntry e = owner.item(it.id);
        int s = theme.itemIconPx();
        int ix = x + theme.px(8);
        if (e != null) {
            c.itemIcon(e.iconItemId(), ix, y + (h - s) / 2f, s);
        } else {
            int is = theme.px(Theme.ICON);
            c.icon(Icon.GET, ix + (s - is) / 2f, y + (h - is) / 2f, is, theme.color(Theme.TEXT_3));
        }
        float tx = ix + s + theme.px(10);
        // the steppers only show on the line you're on, the name gets their room the rest of the time
        boolean hot = steppers && (hovered || count.isFocused());
        float stop = locked ? x + w - theme.px(48) - theme.px(12) : (hot ? minus.x() : count.x()) - theme.px(Theme.GAP);
        float room = Math.max(0, stop - tx);
        String name = e != null ? e.displayName() : ItemEntry.titleCase(it.id);
        float nameH = c.ascent(nameStyle) + c.descent(nameStyle);
        float idH = c.ascent(theme.small) + c.descent(theme.small);
        float top = y + (h - nameH - idH) / 2f;
        c.text(c.ellipsize(name, nameStyle, room), tx, top + c.ascent(nameStyle), nameStyle, theme.color(Theme.TEXT));
        // on a red row an id the catalogue doesn't know is probably the reason, so it gets the red too
        c.text(c.ellipsize(it.id, theme.small, room), tx, top + nameH + c.ascent(theme.small), theme.small,
                theme.color(e == null && owner.red() ? Theme.DANGER : Theme.TEXT_3));
        if (locked) {
            // read only: the number as text where the box would be, no steppers
            String n = String.valueOf(RoutineDraft.countOf(it));
            TextStyle ts = theme.body.asTabular();
            float tw = c.measure(n, ts);
            c.text(n, x + w - theme.px(12) - tw, y + h / 2f + (c.ascent(ts) - c.descent(ts)) / 2f, ts, theme.color(Theme.TEXT_2));
            return;
        }
        if (hot) {
            minus.draw(c, f);
            plus.draw(c, f);
        }
        count.draw(c, f);
        remove.draw(c, f);
    }
}
