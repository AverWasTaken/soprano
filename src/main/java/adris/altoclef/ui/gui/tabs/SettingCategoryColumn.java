package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Control;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Toggle;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.SettingsFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// the left column: All and the categories with their counts, and the "modified only" switch pinned to the bottom.
// up and down move the selection while it has focus
final class SettingCategoryColumn extends Control {

    private final List<String> labels = new ArrayList<>();
    private final List<String> counts = new ArrayList<>();
    private final Consumer<String> onCategory;
    private final Toggle modifiedToggle;
    private final Toggle advancedToggle;
    private final List<Widget> kids;
    private final TextStyle tabular;
    private final List<String> shown = new ArrayList<>();
    private int shownW = -1;
    private boolean dirty = true;
    private int modifiedCountFor = -1;
    private String modifiedText = "";
    private String advancedText = "";
    private int advancedCountFor = -1;
    private int selected;
    private int hoverIdx = -1;
    private int modifiedCount;
    private int advancedCount;

    SettingCategoryColumn(Theme theme, Consumer<String> onCategory, Consumer<Boolean> onModifiedOnly, Consumer<Boolean> onAdvanced) {
        super(theme);
        this.onCategory = onCategory;
        modifiedToggle = new Toggle(theme, false, onModifiedOnly);
        advancedToggle = new Toggle(theme, false, onAdvanced);
        kids = List.of(modifiedToggle, advancedToggle);
        tabular = theme.small.asTabular();
        labels.add(SettingsFilter.ALL);
        counts.add("0");
    }

    String selectedLabel() {
        return labels.get(selected);
    }

    // total is the "All" count. a selected category that no longer exists falls back to All
    void setData(Map<String, Integer> perCategory, int total, String selectedCategory, int modified, boolean modifiedOnly,
                 int advanced, boolean showAdvanced) {
        labels.clear();
        counts.clear();
        labels.add(SettingsFilter.ALL);
        counts.add(String.valueOf(total));
        for (var e : perCategory.entrySet()) {
            labels.add(e.getKey());
            counts.add(String.valueOf(e.getValue()));
        }
        dirty = true;
        selected = Math.max(0, labels.indexOf(selectedCategory));
        modifiedCount = modified;
        modifiedToggle.setOn(modifiedOnly);
        advancedCount = advanced;
        advancedToggle.setOn(showAdvanced);
    }

    // middle of a category's pill, or -1 when it is not in the column (tests click here)
    int centreYOf(String label) {
        int i = labels.indexOf(label);
        return i < 0 ? -1 : Math.round(top() + i * itemH() + itemH() / 2f);
    }

    Toggle modifiedToggle() {
        return modifiedToggle;
    }

    @Override
    public boolean focusable() {
        return visible;
    }

    private int top() {
        return y + theme.px(12);
    }

    private int modBlockH() {
        return theme.px(104);
    }

    private int itemH() {
        int room = h - theme.px(12) - modBlockH() - theme.px(8);
        return Math.max(theme.px(22), Math.min(theme.px(30), room / Math.max(1, labels.size())));
    }

    @Override
    protected void layout() {
        int tw = modifiedToggle.preferredWidth();
        int th = modifiedToggle.preferredHeight();
        modifiedToggle.setBounds(x + theme.px(14), y + h - theme.px(14) - th, tw, th);
        advancedToggle.setBounds(x + theme.px(14), modifiedToggle.y() - theme.px(44), tw, th);
    }

    private int indexAt(float mx, float my) {
        if (mx < x || mx >= x + w) {
            return -1;
        }
        int i = (int) Math.floor((my - top()) / itemH());
        return i >= 0 && i < labels.size() ? i : -1;
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        hoverIdx = contains(mx, my) ? indexAt(mx, my) : -1;
        return super.mouseMove(mx, my);
    }

    private void choose(int i) {
        if (i < 0 || i >= labels.size() || i == selected) {
            return;
        }
        selected = i;
        onCategory.accept(labels.get(i));
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        if (super.mouseDown(mx, my, button, mods)) {
            return true;
        }
        if (button == 0) {
            choose(indexAt(mx, my));
        }
        return true;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (!focused) {
            return false;
        }
        if (key == Keys.DOWN) {
            choose(Math.min(labels.size() - 1, selected + 1));
            return true;
        }
        if (key == Keys.UP) {
            choose(Math.max(0, selected - 1));
            return true;
        }
        return false;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        int ih = itemH();
        int pad = theme.px(8);
        float r = theme.pxf(Theme.ROW_RADIUS);
        // cut once per width or data change, not once per label per frame
        if (shownW != w || shown.size() != labels.size() || dirty) {
            dirty = false;
            shownW = w;
            shown.clear();
            for (int i = 0; i < labels.size(); i++) {
                float nw = c.measure(counts.get(i), tabular);
                shown.add(theme.measurer.ellipsize(labels.get(i), theme.smallLabel, Math.max(0, w - 2 * (pad + theme.px(8)) - nw - theme.px(6))));
            }
        }
        for (int i = 0; i < labels.size(); i++) {
            float ry = top() + i * ih;
            boolean on = i == selected;
            if (on) {
                c.fillRoundRect(x + pad, ry, w - 2 * pad, ih, r, theme.color(Theme.ACCENT_SOFT));
            } else if (i == hoverIdx) {
                c.fillRoundRect(x + pad, ry, w - 2 * pad, ih, r, Theme.fade(theme.color(Theme.HOVER), 0.55f));
            }
            if (focused && on) {
                c.strokeRoundRect(x + pad, ry, w - 2 * pad, ih, r, theme.color(Theme.HAIRLINE_STRONG));
            }
            float base = baseline(c, theme.smallLabel, ry, ih);
            String n = counts.get(i);
            float nw = c.measure(n, tabular);
            float inner = pad + theme.px(8);
            c.text(n, x + w - inner - nw, base, tabular, on ? theme.color(Theme.ACCENT) : theme.color(Theme.TEXT_3));
            c.text(shown.get(i), x + inner, base, theme.smallLabel, on ? theme.color(Theme.ACCENT) : theme.color(Theme.TEXT_2));
        }
        int by = y + h - modBlockH();
        c.fillRect(x + theme.px(14), by, w - theme.px(28), 1, theme.color(Theme.HAIRLINE));
        c.text("Show advanced", x + theme.px(14), advancedToggle.y() - theme.px(6), theme.smallLabel, theme.color(Theme.TEXT_2));
        advancedToggle.draw(c, f);
        if (advancedCountFor != advancedCount) {
            advancedCountFor = advancedCount;
            advancedText = advancedCount == 0 ? "None advanced" : advancedCount + " advanced";
        }
        c.text(advancedText, advancedToggle.x() + advancedToggle.w() + theme.px(8), baseline(c, theme.small, advancedToggle.y(), advancedToggle.h()),
                theme.small, theme.color(Theme.TEXT_3));

        c.text("Modified only", x + theme.px(14), modifiedToggle.y() - theme.px(6), theme.smallLabel, theme.color(Theme.TEXT_2));
        modifiedToggle.draw(c, f);
        if (modifiedCountFor != modifiedCount) {
            modifiedCountFor = modifiedCount;
            modifiedText = modifiedCount == 0 ? "None modified" : modifiedCount + " modified";
        }
        c.text(modifiedText, modifiedToggle.x() + modifiedToggle.w() + theme.px(8), baseline(c, theme.small, modifiedToggle.y(), modifiedToggle.h()),
                theme.small, theme.color(Theme.TEXT_3));
    }
}
