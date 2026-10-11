package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.RowWidgets;
import adris.altoclef.ui.gui.kit.TextMeasurer;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.Basket;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// the right hand card: title, the lines, a one line total and the big button. in mine mode there is also the one
// shared "stop after" box above the total. it only shows what the basket says, the tab owns the basket and tells this
// to refresh() after it changes it
final class GetBasketPanel extends Widget {

    private final GetBasketRow.Actions actions;
    private final RowWidgets<GetBasketRow.Item> list;
    private final Button clear;
    private final GetGoButton go;
    private final IconButton limitMinus;
    private final IconButton limitPlus;
    private final GetCountField limitField;
    private final List<Widget> kids;
    private Basket basket = new Basket(false);
    private boolean mine;
    private Function<Basket.Line, ItemEntry> resolve = l -> new ItemEntry(l.name(), ItemEntry.titleCase(l.name()), "minecraft:" + l.name(), l.maxStack());
    private String summary = "";
    private long now;

    GetBasketPanel(Theme theme, GetBasketRow.Actions actions, Runnable onGo, Runnable onClear) {
        super(theme);
        this.actions = actions;
        list = new RowWidgets<>(theme, theme.px(44), () -> new GetBasketRow(theme, actions));
        list.setKeyFunction(it -> it.entry().name());
        clear = new Button(theme, "Clear", Button.Kind.GHOST, null, onClear);
        clear.setFocusable(false);
        go = new GetGoButton(theme, onGo);
        limitMinus = new IconButton(theme, Icon.MINUS, "Less", () -> nudgeLimit(-1));
        limitPlus = new IconButton(theme, Icon.PLUS, "More", () -> nudgeLimit(1));
        limitMinus.setFocusable(false);
        limitPlus.setFocusable(false);
        limitField = new GetCountField(theme, new GetCountField.Host() {
            @Override
            public void commit(int n) {
                actions.setLimit(n);
                limitField.sync(actions.limit());
            }

            @Override
            public void step(int dir, int mods) {
                actions.setLimit(Math.max(0, actions.limit() + dir * Basket.stepFor(mods, 64)));
                limitField.sync(actions.limit());
            }

            @Override
            public void done() {
                actions.blur();
            }
        });
        kids = List.of(list, clear, go, limitMinus, limitField, limitPlus);
        setLimitVisible(false);
    }

    private void nudgeLimit(int dir) {
        IconButton b = dir < 0 ? limitMinus : limitPlus;
        actions.setLimit(Math.max(0, actions.limit() + dir * Basket.stepFor(b.lastMods(), 64)));
        limitField.sync(actions.limit());
    }

    private void setLimitVisible(boolean on) {
        limitMinus.setVisible(on);
        limitPlus.setVisible(on);
        limitField.setVisible(on);
    }

    void bind(Basket b, boolean mineMode, Function<Basket.Line, ItemEntry> resolver) {
        basket = b;
        mine = mineMode;
        resolve = resolver;
        setLimitVisible(mine);
        layout();
        refresh();
    }

    void refresh() {
        List<GetBasketRow.Item> items = new ArrayList<>();
        for (Basket.Line l : basket.lines()) {
            items.add(new GetBasketRow.Item(resolve.apply(l), l.count(), mine));
        }
        list.setItems(items);
        summary = basket.summaryLine();
        go.setText(basket.buttonLabel());
        go.setEnabled(!basket.isEmpty());
        clear.setVisible(!basket.isEmpty());
        // a box you are typing in keeps its text, everyone else follows the basket
        if (mine && !limitField.isFocused()) {
            limitField.bind(basket.limit(), "any");
        }
    }

    // true while any count box (a row's or the limit) has the keyboard
    boolean typing() {
        if (limitField.isFocused()) {
            return true;
        }
        for (Widget row : list.children()) {
            for (Widget k : row.children()) {
                if (k instanceof GetCountField && k.isFocused()) {
                    return true;
                }
            }
        }
        return false;
    }

    RowWidgets<GetBasketRow.Item> list() {
        return list;
    }

    GetGoButton goButton() {
        return go;
    }

    Button clearButton() {
        return clear;
    }

    GetCountField limitField() {
        return limitField;
    }

    IconButton limitPlus() {
        return limitPlus;
    }

    IconButton limitMinus() {
        return limitMinus;
    }

    String summary() {
        return summary;
    }

    // scrolls the line for a name into view, for when a click added it below the fold
    void reveal(String name) {
        int row = 0;
        for (Basket.Line l : basket.lines()) {
            if (l.name().equals(name)) {
                float h = list.rowHeight();
                list.scroller().ensureVisible(row * h, (row + 1) * h, now);
                return;
            }
            row++;
        }
    }

    private int titleH() {
        return theme.px(44);
    }

    private int footerH() {
        return mine ? theme.px(120) : theme.px(80);
    }

    @Override
    protected void layout() {
        int pad = theme.px(12);
        int cw = clear.preferredWidth();
        int ch = theme.px(28);
        clear.setBounds(x + w - theme.px(8) - cw, y + (titleH() - ch) / 2, cw, ch);
        int side = theme.px(6);
        list.setBounds(x + side, y + titleH() + 1, w - 2 * side, h - titleH() - 1 - footerH());
        int bh = theme.px(32);
        go.setBounds(x + pad, y + h - pad - bh, w - 2 * pad, bh);
        // limit row: label on the left, [-] box [+] on the right
        int b = theme.px(22);
        int gap = theme.px(2);
        int boxW = theme.px(48);
        int footTop = y + h - footerH();
        int ly = footTop + theme.px(8);
        int rowH = theme.px(28);
        int right = x + w - pad;
        limitPlus.setBounds(right - b, ly + (rowH - b) / 2, b, b);
        limitField.setBounds(limitPlus.x() - gap - boxW, ly, boxW, rowH);
        limitMinus.setBounds(limitField.x() - gap - b, ly + (rowH - b) / 2, b, b);
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    // greedy word wrap, one long word is left to the ellipsis of whoever draws it
    static List<String> wrap(TextMeasurer m, String text, TextStyle style, float maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tryLine = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && m.measure(tryLine, style) > maxW) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(tryLine);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        now = f.nowNanos();
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        c.fillRoundRect(x, y, w, h, r, Theme.RAISED);
        c.strokeRoundRect(x, y, w, h, r, Theme.HAIRLINE);

        int pad = theme.px(12);
        float tb = y + titleH() / 2f + (c.ascent(theme.label) - c.descent(theme.label)) / 2f;
        c.text("Basket", x + pad + theme.px(2), tb, theme.label, Theme.TEXT);
        c.fillRect(x + 1, y + titleH(), w - 2, 1, Theme.HAIRLINE);
        list.draw(c, f);
        if (basket.isEmpty()) {
            drawHint(c);
        }
        if (clear.visible()) {
            clear.draw(c, f);
        }

        int footTop = y + h - footerH();
        c.fillRect(x + 1, footTop, w - 2, 1, Theme.HAIRLINE);
        TextStyle small = theme.small;
        float summaryTop = footTop + theme.px(14);
        if (mine) {
            float lb = limitField.y() + limitField.h() / 2f + (c.ascent(theme.body) - c.descent(theme.body)) / 2f;
            c.text("Stop after", x + pad, lb, theme.body, Theme.TEXT_2);
            limitMinus.draw(c, f);
            limitField.draw(c, f);
            limitPlus.draw(c, f);
            summaryTop += theme.px(40);
        }
        String shown = c.ellipsize(summary, small, w - 2 * pad);
        c.text(shown, x + pad, summaryTop + c.ascent(small), small, Theme.TEXT_2);
        go.draw(c, f);
    }

    private void drawHint(Canvas c) {
        String msg = mine
                ? "Click a block to add it, click it again to take it out. Leave the limit empty to keep mining."
                : "Click an item to add it. Shift-click adds a stack, Ctrl-click adds 10.";
        TextStyle small = theme.small;
        float maxW = list.w() - 2 * theme.px(14);
        float step = (float) Math.ceil(c.lineHeight(small)) + theme.px(3);
        float base = list.y() + theme.px(14) + c.ascent(small);
        for (String line : wrap(c, msg, small, maxW)) {
            c.text(line, list.x() + theme.px(14), base, small, Theme.TEXT_2);
            base += step;
        }
    }
}
