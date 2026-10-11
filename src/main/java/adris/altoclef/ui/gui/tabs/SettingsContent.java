package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.RowWidgets;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

// the settings tab's page: category column on the left, then the pinned key card, a count line, the list, and a strip
// at the bottom that spells out the description of whatever row you are on in full (the rows cut it off to one line)
final class SettingsContent extends Widget {

    private final SettingCategoryColumn column;
    private final SettingKeyCard keyCard;
    private final RowWidgets<SettingView> list;
    private final List<Widget> kids;
    private final BiConsumer<Float, Float> beforeInput;

    private String countLine = "";
    private SettingView detail;
    private String detailText = "";
    private int detailY;
    private int detailH;
    private int countY;
    private SettingView shownFor;
    private float shownRoom = -1;
    private String hintShown = "";
    private String defShown = "";
    private String nameShown = "";
    private float defW;
    private List<String> wrapped;
    private String wrappedText;
    private float wrappedW;

    SettingsContent(Theme theme, SettingCategoryColumn column, SettingKeyCard keyCard, RowWidgets<SettingView> list,
                    BiConsumer<Float, Float> beforeInput) {
        super(theme);
        this.column = column;
        this.keyCard = keyCard;
        this.list = list;
        this.beforeInput = beforeInput;
        kids = List.of(column, keyCard, list);
    }

    // a click or a scroll may re-filter or move rows, so a pending edit lands first. the click position goes along so
    // a click inside the box being typed in (moving the caret) does not count as leaving it
    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (contains(mx, my)) {
            beforeInput.accept(mx, my);
        }
        return super.mouseDown(mx, my, button, mods);
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        if (contains(mx, my)) {
            beforeInput.accept(Float.NaN, Float.NaN);
        }
        return super.scroll(mx, my, dy);
    }

    void setCountLine(String s) {
        countLine = s;
    }

    SettingView detail() {
        return detail;
    }

    void showDetail(SettingView v, String description) {
        detail = v;
        detailText = description == null ? "" : description;
    }

    void clearDetail() {
        detail = null;
        detailText = "";
    }

    @Override
    protected void layout() {
        // 190 fits "Pathing and processes" and its count at full size. a small window gets less and the long names get cut
        int colW = Math.round(Math.max(theme.px(120), Math.min(theme.px(190), w * 0.24f)));
        column.setBounds(x, y, colW, h);
        int mx = x + colW + 1;
        int mw = w - colW - 1;
        int pad = theme.px(Theme.PAD);
        int cardW = mw - 2 * pad;
        int cardH = SettingKeyCard.heightFor(theme, cardW);
        int cardY = y + theme.px(12);
        keyCard.setBounds(mx + pad, cardY, cardW, cardH);
        countY = cardY + cardH + theme.px(8);
        int countH = theme.px(24);
        detailH = theme.px(64);
        detailY = y + h - detailH;
        int listY = countY + countH;
        list.setBounds(mx, listY, mw, Math.max(0, detailY - listY));
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        column.draw(c, f);
        c.fillRect(column.x() + column.w(), y, 1, h, Theme.HAIRLINE);
        keyCard.draw(c, f);

        int mx = column.x() + column.w() + 1;
        int pad = theme.px(Theme.PAD);
        c.text(countLine, mx + pad, countY + theme.px(14), theme.small, Theme.TEXT_3);

        list.draw(c, f);
        drawDetail(c, mx, pad);
    }

    private void drawDetail(Canvas c, int mx, int pad) {
        int dw = x + w - mx;
        c.fillRect(mx, detailY, dw, detailH, Theme.SIDEBAR);
        c.fillRect(mx, detailY, dw, 1, Theme.HAIRLINE);
        float tx = mx + pad;
        float room = dw - 2 * pad;
        float lh1 = c.ascent(theme.label) + c.descent(theme.label);
        float lh2 = c.ascent(theme.small) + c.descent(theme.small);
        float top = detailY + theme.px(10);
        // the strings below change when the mouse moves to another row or the panel is resized, not every frame
        if (shownFor != detail || shownRoom != room) {
            shownFor = detail;
            shownRoom = room;
            hintShown = theme.measurer.ellipsize("Hover a setting to read all of its description here.", theme.small, room);
            if (detail != null) {
                String def = "Default " + (detail.defaultValue() == null || detail.defaultValue().isEmpty() ? "empty" : detail.defaultValue());
                defShown = theme.measurer.ellipsize(def, theme.small, room * 0.4f);
                defW = c.measure(defShown, theme.small);
                nameShown = theme.measurer.ellipsize(detail.name(), theme.label, room - defW - theme.px(12));
            }
        }
        if (detail == null) {
            c.text(hintShown, tx, detailY + detailH / 2f + (c.ascent(theme.small) - c.descent(theme.small)) / 2f, theme.small, Theme.TEXT_3);
            return;
        }
        c.text(defShown, tx + room - defW, top + c.ascent(theme.label), theme.small, Theme.TEXT_3);
        c.text(nameShown, tx, top + c.ascent(theme.label), theme.label, Theme.TEXT);
        String text = detailText.isEmpty() ? "No description." : detailText;
        // wrapping measures a lot, and the text only changes when the mouse moves to another row
        if (wrapped == null || !text.equals(wrappedText) || wrappedW != room) {
            wrapped = wrap(c, text, room, 2);
            wrappedText = text;
            wrappedW = room;
        }
        List<String> lines = wrapped;
        for (int i = 0; i < lines.size(); i++) {
            c.text(lines.get(i), tx, top + lh1 + theme.px(3) + c.ascent(theme.small) + i * (lh2 + theme.px(1)), theme.small,
                    detailText.isEmpty() ? Theme.TEXT_3 : Theme.TEXT_2);
        }
    }

    // greedy word wrap into at most maxLines. the last line takes whatever is left and the ellipsis cuts it
    private List<String> wrap(Canvas c, String text, float width, int maxLines) {
        List<String> out = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String cand = line.length() == 0 ? words[i] : line + " " + words[i];
            if (line.length() > 0 && c.measure(cand, theme.small) > width) {
                if (out.size() == maxLines - 1) {
                    StringBuilder rest = new StringBuilder(line);
                    for (int k = i; k < words.length; k++) {
                        rest.append(' ').append(words[k]);
                    }
                    out.add(c.ellipsize(rest.toString(), theme.small, width));
                    return out;
                }
                out.add(line.toString());
                line.setLength(0);
                line.append(words[i]);
            } else {
                line.setLength(0);
                line.append(cand);
            }
        }
        if (line.length() > 0) {
            out.add(c.ellipsize(line.toString(), theme.small, width));
        }
        return out;
    }
}
