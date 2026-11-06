package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.kit.Theme;

import java.util.function.Consumer;
import java.util.function.Predicate;

// the commands tab's text box. same field as everywhere, plus what sits in front of the text: the item's own texture
// where the search glass would be, the quiet # of a raw command, or the glass itself for the add box. it can also go
// read only for a CustomTasks.json that didn't load
final class StepField extends TextField {

    enum Lead { NONE, ITEM, HASH, SEARCH }

    private final Lead lead;
    private final Predicate<String> knownItem;
    private boolean editable = true;
    private boolean rightAligned;
    private final String hint;

    StepField(Theme theme, String placeholder, Lead lead, Predicate<String> knownItem, Consumer<String> onCommit) {
        super(theme, placeholder, onCommit);
        hint = placeholder == null ? "" : placeholder;
        this.lead = lead;
        this.knownItem = knownItem == null ? s -> false : knownItem;
    }

    void setEditable(boolean e) {
        editable = e;
        if (!e) {
            setFocused(false);
        }
    }

    // the inspector's numbers, like the mock. moving the left inset is all it takes, caret and clicks measure from there
    void setRightAligned(boolean on) {
        rightAligned = on;
    }

    boolean editable() {
        return editable;
    }

    // for code that refreshes a box every frame: a box you are typing in keeps your text
    void show(String s) {
        String v = s == null ? "" : s;
        if (!isFocused() && !v.equals(text())) {
            setText(v);
        }
    }

    @Override
    protected int leftInset() {
        if (rightAligned) {
            String t = text().isEmpty() ? hint : text();
            return Math.max(theme.px(10), w - theme.px(10) - Math.round(theme.measurer.measure(t, theme.body)));
        }
        return switch (lead) {
            case NONE -> super.leftInset();
            case ITEM -> theme.px(7) + theme.itemIconPx() + theme.px(7);
            case SEARCH -> theme.px(7) + theme.px(Theme.ICON) + theme.px(5);
            case HASH -> theme.px(10) + Math.round(theme.measurer.measure("#", theme.body)) + theme.px(6);
        };
    }

    @Override
    protected void drawAdornments(Canvas c, Frame f) {
        float left = x + theme.px(7);
        switch (lead) {
            case ITEM -> {
                String id = text().trim().toLowerCase(java.util.Locale.ROOT);
                int s = theme.itemIconPx();
                if (!id.isEmpty() && knownItem.test(id)) {
                    c.itemIcon(id, left, y + (h - s) / 2f, s);
                } else {
                    // nothing typed or not an item (yet): the parcel stands in, like the get tab's empty basket
                    int is = theme.px(Theme.ICON);
                    c.icon(Icon.GET, left + (s - is) / 2f, y + (h - is) / 2f, is, theme.color(Theme.TEXT_3));
                }
            }
            case SEARCH -> {
                int is = theme.px(Theme.ICON);
                c.icon(Icon.SEARCH, left, y + (h - is) / 2f, is, theme.color(Theme.TEXT_3));
            }
            case HASH -> c.text("#", x + theme.px(10), baseline(c, theme.body, y, h), theme.body, theme.color(Theme.TEXT_3));
            case NONE -> {
            }
        }
    }

    @Override
    public boolean focusable() {
        return editable && super.focusable();
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        return editable && super.mouseDown(mx, my, button, mods);
    }

    @Override
    public boolean keyDown(int key, int mods) {
        return editable && super.keyDown(key, mods);
    }

    @Override
    public boolean charTyped(char ch) {
        return editable && super.charTyped(ch);
    }
}
