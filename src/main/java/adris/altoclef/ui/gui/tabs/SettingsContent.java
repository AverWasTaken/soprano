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

// categories live in a popover so the setting and its editor can share the whole row. the strip below keeps
// descriptions readable without making every row tall
final class SettingsContent extends Widget {

    private final SettingsToolbar toolbar;
    private final SettingKeyCard keyCard;
    private final SettingsBrowser browser;
    private final List<Widget> kids;
    private final BiConsumer<Float, Float> beforeInput;

    private String countLine = "";
    private SettingView detail;
    private String detailText = "";
    private int detailY;
    private int detailH;
    private int detailLines;
    private int detailPad;
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

    SettingsContent(Theme theme, SettingsToolbar toolbar, SettingKeyCard keyCard, RowWidgets<SettingView> list, CommonSettings common,
                    BiConsumer<Float, Float> beforeInput) {
        super(theme);
        this.toolbar = toolbar;
        this.keyCard = keyCard;
        browser = new SettingsBrowser(theme, common, list);
        this.beforeInput = beforeInput;
        kids = List.of(toolbar, keyCard, browser);
    }

    SettingsBrowser browser() {
        return browser;
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
        wrapped = null;
        int pad = theme.px(Theme.PAD);
        int inner = Math.max(0, w - 2 * pad);
        boolean compact = h < theme.px(340);
        int cardH = SettingKeyCard.heightFor(theme, inner);
        int cardY = y + pad;
        // the sidebar already has the open key; a short window needs room for an actual settings tile
        keyCard.setVisible(!compact);
        keyCard.setBounds(x + pad, cardY, inner, cardH);
        int navY = compact ? cardY : cardY + cardH + theme.px(12);
        int navH = toolbar.heightFor(inner);
        toolbar.setBounds(x + pad, navY, inner, navH);
        countY = navY + navH + theme.px(6);
        detailLines = compact ? 1 : 2;
        detailPad = theme.px(compact ? 6 : 10);
        float titleH = theme.measurer.ascent(theme.label) + theme.measurer.descent(theme.label);
        float lineH = theme.measurer.ascent(theme.small) + theme.measurer.descent(theme.small);
        // font metrics round independently at each scale. a fixed 56px box cannot promise two lines fit
        detailH = (int) Math.ceil(2 * detailPad + titleH + theme.px(3)
                + detailLines * lineH + (detailLines - 1) * theme.px(1));
        detailY = y + h - detailH - pad;
        int listY = countY + theme.px(22);
        browser.setBounds(x + pad, listY, inner, Math.max(0, detailY - theme.px(8) - listY));
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        toolbar.draw(c, f);
        if (keyCard.visible()) {
            keyCard.draw(c, f);
        }

        int mx = x;
        int pad = theme.px(Theme.PAD);
        c.text(countLine, mx + pad, countY + theme.px(14), theme.small, theme.color(Theme.TEXT_3));

        browser.draw(c, f);
        drawDetail(c, mx, pad);
    }

    private void drawDetail(Canvas c, int mx, int pad) {
        int dw = w;
        theme.recess(c, mx + pad, detailY, dw - 2 * pad, detailH, theme.pxf(10));
        c.pushClip(mx + pad, detailY, Math.max(0, dw - 2 * pad), detailH);
        try {
            drawDetailText(c, mx, pad);
        } finally {
            c.popClip();
        }
    }

    private void drawDetailText(Canvas c, int mx, int pad) {
        int dw = w;
        float tx = mx + pad + theme.px(12);
        float room = dw - 2 * pad - theme.px(24);
        float lh1 = c.ascent(theme.label) + c.descent(theme.label);
        float lh2 = c.ascent(theme.small) + c.descent(theme.small);
        float top = detailY + detailPad;
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
            c.text(hintShown, tx, detailY + detailH / 2f + (c.ascent(theme.small) - c.descent(theme.small)) / 2f, theme.small, theme.color(Theme.TEXT_3));
            return;
        }
        c.text(defShown, tx + room - defW, top + c.ascent(theme.label), theme.small, theme.color(Theme.TEXT_3));
        c.text(nameShown, tx, top + c.ascent(theme.label), theme.label, theme.color(Theme.TEXT));
        String text = detailText.isEmpty() ? "No description." : detailText;
        // wrapping measures a lot, and the text only changes when the mouse moves to another row
        if (wrapped == null || !text.equals(wrappedText) || wrappedW != room) {
            wrapped = wrap(c, text, room, detailLines);
            wrappedText = text;
            wrappedW = room;
        }
        List<String> lines = wrapped;
        for (int i = 0; i < lines.size(); i++) {
            c.text(lines.get(i), tx, top + lh1 + theme.px(3) + c.ascent(theme.small) + i * (lh2 + theme.px(1)), theme.small,
                    detailText.isEmpty() ? theme.color(Theme.TEXT_3) : theme.color(Theme.TEXT_2));
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
