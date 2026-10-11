package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Dropdown;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Toggle;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.SettingsFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// categories are a submenu now. the two common filters stay beside it instead of claiming a second sidebar
final class SettingsToolbar extends Widget {

    private final List<String> labels = new ArrayList<>();
    private final Consumer<String> onCategory;
    private final Dropdown categories;
    private final Toggle modifiedToggle;
    private final Toggle advancedToggle;
    private final List<Widget> kids;
    private int selected;

    SettingsToolbar(Theme theme, ShellContext ctx, Consumer<String> onCategory,
                    Consumer<Boolean> onModifiedOnly, Consumer<Boolean> onAdvanced) {
        super(theme);
        this.onCategory = onCategory;
        labels.add(SettingsFilter.ALL);
        categories = new Dropdown(theme, List.of("All settings"), 0, this::choose, ctx);
        modifiedToggle = new Toggle(theme, false, onModifiedOnly);
        advancedToggle = new Toggle(theme, false, onAdvanced);
        kids = List.of(categories, advancedToggle, modifiedToggle);
    }

    String selectedLabel() {
        return labels.get(selected);
    }

    Dropdown categories() {
        return categories;
    }

    Toggle modifiedToggle() {
        return modifiedToggle;
    }

    void setData(Map<String, Integer> perCategory, String selectedCategory, boolean modifiedOnly, boolean showAdvanced) {
        labels.clear();
        labels.add(SettingsFilter.ALL);
        labels.addAll(perCategory.keySet());
        selected = Math.max(0, labels.indexOf(selectedCategory));
        List<String> options = new ArrayList<>();
        int total = perCategory.values().stream().mapToInt(Integer::intValue).sum();
        options.add("All settings  \u00b7  " + total);
        for (var e : perCategory.entrySet()) {
            options.add(e.getKey() + "  \u00b7  " + e.getValue());
        }
        categories.setOptions(options, selected);
        modifiedToggle.setOn(modifiedOnly);
        advancedToggle.setOn(showAdvanced);
    }

    int heightFor(int width) {
        return theme.px(width < theme.px(340) ? 66 : Theme.CONTROL_H);
    }

    private void choose(int i) {
        selected = i;
        onCategory.accept(labels.get(i));
    }

    @Override
    protected void layout() {
        int ch = theme.px(Theme.CONTROL_H);
        boolean narrow = w < theme.px(340);
        categories.setBounds(x, y, narrow ? w : Math.min(theme.px(260), w - theme.px(240)), ch);
        int fy = narrow ? y + ch + theme.px(6) : y;
        int th = modifiedToggle.preferredHeight();
        int tw = modifiedToggle.preferredWidth();
        int mw = (int) Math.ceil(theme.measurer.measure("Modified", theme.small));
        int aw = (int) Math.ceil(theme.measurer.measure("Advanced", theme.small));
        modifiedToggle.setBounds(x + w - mw - theme.px(8) - tw, fy + (ch - th) / 2, tw, th);
        advancedToggle.setBounds(modifiedToggle.x() - theme.px(20) - aw - theme.px(8) - tw, fy + (ch - th) / 2, tw, th);
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        categories.draw(c, f);
        for (Toggle toggle : List.of(advancedToggle, modifiedToggle)) {
            toggle.draw(c, f);
            String label = toggle == advancedToggle ? "Advanced" : "Modified";
            float base = toggle.y() + toggle.h() / 2f + (c.ascent(theme.small) - c.descent(theme.small)) / 2f;
            c.text(label, toggle.x() + toggle.w() + theme.px(8), base, theme.small, theme.color(Theme.TEXT_2));
        }
    }
}
