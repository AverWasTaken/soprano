package adris.altoclef.ui.gui.update;

import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.TextMeasurer;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.TextWrap;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import baritone.utils.update.Snapshot;
import baritone.utils.update.UpdateState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// the update card in the corner of the title screen, as a kit widget with no minecraft in it (the preview draws it
// too). one line when there's nothing to do, a headline and a button or two when there is. the text is built once per
// state change, only the download counter and bar read the snapshot every frame
public final class UpdateCard extends Widget {

    public interface Actions {
        void update();

        void recheck();

        void whatsNew();

        void openPage(String url);

        void quit();

        void openMods(Path folder);
    }

    // design px. the card never asks for more than RICH_W, and the host can pull that down to stay clear of the logo
    public static final int RICH_W = 300;
    public static final int MIN_W = 180;
    // a rich card fits its words and buttons, but never gets narrower than this so the states don't jump around
    public static final int MIN_RICH_W = 240;
    private static final int PAD = 12;
    private static final int PAD_COMPACT = 8;
    private static final int BAR_H = 3;
    private static final String COUNTER_WORST = "999.9 of 999.9 MB";

    private record Line(String text, TextStyle style, int colour, int dx, float baseline) {
    }

    private final Actions actions;
    private final List<Line> lines = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private final List<Widget> kids = new ArrayList<>();
    // where each button sits inside the card, so layout() can move them with it
    private final List<int[]> slots = new ArrayList<>();
    private Snapshot current;
    private String key = "";
    private int maxDesignW = RICH_W;
    private int cardW;
    private int cardH;
    private boolean compact;
    // the download counter's right edge and baseline, and the bar's top. only set while downloading
    private boolean downloading;
    private int counterRight;
    private float counterBaseline;
    private int barTop;
    // the widest thing added so far, measured from the card's left edge, and the inner width the card ended up with
    private float needW;
    private int innerUsed;
    private Widget focusedKid;

    public UpdateCard(Theme theme, Actions actions) {
        super(theme);
        this.actions = actions;
    }

    // the host knows how much room there is before the logo starts
    public void setMaxWidth(int designPx) {
        int clamped = Math.max(MIN_W, Math.min(RICH_W, designPx));
        if (clamped != maxDesignW) {
            maxDesignW = clamped;
            key = "";
        }
    }

    public void show(Snapshot s) {
        current = s;
        if (s == null) {
            return;
        }
        String k = keyOf(s);
        if (!k.equals(key)) {
            key = k;
            rebuild(s);
            layout();
        }
    }

    public Snapshot shown() {
        return current;
    }

    // everything that changes the words or the buttons. bytes are left out on purpose, they move every tick
    private String keyOf(Snapshot s) {
        Snapshot.Release r = s.release();
        return s.state() + "|" + s.canInstall() + "|" + s.cannotInstallReason() + "|" + (r == null ? "" : r.version())
                + "|" + s.error() + "|" + s.unfinishedLastTime() + "|" + s.oldJarName() + "|" + s.stagedName() + "|"
                + s.finalName() + "|" + s.currentVersion() + "|" + maxDesignW;
    }

    @Override
    public int preferredWidth() {
        return cardW;
    }

    @Override
    public int preferredHeight() {
        return cardH;
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public boolean contains(float px, float py) {
        return visible && px >= x && py >= y && px < x + cardW && py < y + cardH;
    }

    // ----- building

    private void rebuild(Snapshot s) {
        lines.clear();
        buttons.clear();
        kids.clear();
        slots.clear();
        if (focusedKid != null) {
            focusedKid.setFocused(false);
            focusedKid = null;
        }
        compact = false;
        downloading = false;
        needW = 0f;
        innerUsed = 0;
        String have = s.currentVersion() == null || s.currentVersion().isBlank() ? "" : "v" + s.currentVersion();
        String name = have.isEmpty() ? "Soprano" : "Soprano " + have;
        Snapshot.Release r = s.release();
        String next = r == null || r.version() == null ? "the update" : "v" + r.version();
        switch (s.state()) {
            case DISABLED -> compactRow(name, null, null);
            case CHECKING -> compactRow(name, "checking…", null);
            case UP_TO_DATE -> compactRow(name, "up to date", null);
            case CHECK_FAILED -> compactRow(name, "couldn't check", new Button(theme, "Retry", Button.Kind.GHOST, null, actions::recheck));
            case AVAILABLE -> available(s, have, next);
            case DOWNLOADING -> downloading(next);
            case READY -> ready(s, next);
            case STUCK -> stuck(s);
            case FAILED -> failed(s);
        }
    }

    private void compactRow(String name, String status, Button b) {
        compact = true;
        TextMeasurer m = theme.measurer;
        int pad = theme.px(PAD_COMPACT);
        int gap = theme.px(Theme.GAP);
        int rowH = theme.px(Theme.CONTROL_H);
        float base = pad + rowH / 2f + (m.ascent(theme.label) - m.descent(theme.label)) / 2f;
        int cx = pad;
        lines.add(new Line(name, theme.label, Theme.TEXT, cx, base));
        cx += Math.round(m.measure(name, theme.label));
        if (status != null) {
            cx += gap;
            float sb = pad + rowH / 2f + (m.ascent(theme.small) - m.descent(theme.small)) / 2f;
            lines.add(new Line(status, theme.small, Theme.TEXT_2, cx, sb));
            cx += Math.round(m.measure(status, theme.small));
        }
        if (b != null) {
            cx += theme.px(Theme.SPACE_1);
            add(b, cx, pad, b.preferredWidth(), rowH);
            cx += b.preferredWidth();
        }
        cardW = cx + pad;
        cardH = rowH + 2 * pad;
    }

    private void available(Snapshot s, String have, String next) {
        int y = headline(next + " is out");
        if (!have.isEmpty()) {
            y = small("you have " + have, Theme.TEXT_2, y);
        }
        Button first;
        if (s.canInstall()) {
            first = new Button(theme, "Update", Button.Kind.PRIMARY, null, actions::update);
        } else {
            if (s.cannotInstallReason() != null && !s.cannotInstallReason().isBlank()) {
                y = small(s.cannotInstallReason(), Theme.TEXT_3, y);
            }
            first = pageButton(s, Button.Kind.PRIMARY);
        }
        Button notes = new Button(theme, "What's new", Button.Kind.GHOST, null, actions::whatsNew);
        finish(y, first, notes);
    }

    private void downloading(String next) {
        downloading = true;
        TextMeasurer m = theme.measurer;
        int pad = theme.px(PAD);
        int max = innerW();
        TextStyle counter = theme.small.asTabular();
        String head = "Downloading " + next;
        float headW = m.measure(head, theme.label);
        float counterW = m.measure(COUNTER_WORST, counter);
        int y = pad;
        line(head, theme.label, Theme.TEXT, pad, y + m.ascent(theme.label));
        if (headW + theme.px(Theme.GAP) + counterW <= max) {
            // same baseline as the headline, the kit's label and small share a baseline fine
            counterBaseline = y + m.ascent(theme.label);
            needW = Math.max(needW, pad + headW + theme.px(Theme.GAP) + counterW);
            y += Math.round(m.lineHeight(theme.label));
        } else {
            y += Math.round(m.lineHeight(theme.label)) + theme.px(Theme.SPACE_1);
            counterBaseline = y + m.ascent(counter);
            needW = Math.max(needW, pad + counterW);
            y += Math.round(m.lineHeight(counter));
        }
        y += theme.px(Theme.SPACE_3);
        barTop = y;
        y += theme.px(BAR_H);
        settle();
        counterRight = pad + innerUsed;
        cardH = y + pad;
    }

    private void ready(Snapshot s, String next) {
        int y = headline(next + " is ready");
        y = small("restart Minecraft to finish", Theme.TEXT_2, y);
        if (s.unfinishedLastTime()) {
            y = small("last time the swap didn't finish, it tries again when the game closes", Theme.TEXT_2, y);
        }
        if (s.error() != null && !s.error().isBlank()) {
            y = small(s.error(), Theme.DANGER, y);
        }
        finish(y, new Button(theme, "Quit game", Button.Kind.PRIMARY, null, actions::quit));
    }

    private void stuck(Snapshot s) {
        int y = headline("The update needs a hand");
        y = small("the swap didn't go through twice. to finish it by hand:", Theme.TEXT_2, y);
        y = step(1, "close Minecraft", y);
        y = step(2, "in the mods folder, delete " + or(s.oldJarName(), "the old soprano jar"), y);
        y = step(3, "rename " + or(s.stagedName(), "the .new jar") + " to " + or(s.finalName(), "the jar's name"), y);
        Path mods = s.modsFolder();
        if (mods != null) {
            finish(y, new Button(theme, "Open mods folder", Button.Kind.SECONDARY, null, () -> actions.openMods(mods)));
        } else {
            finish(y);
        }
    }

    private void failed(Snapshot s) {
        int y = headline("The update didn't finish");
        if (s.error() != null && !s.error().isBlank()) {
            y = small(s.error(), Theme.DANGER, y);
        }
        if (s.canInstall()) {
            finish(y, new Button(theme, "Retry", Button.Kind.PRIMARY, null, actions::update), pageButton(s, Button.Kind.GHOST));
        } else {
            finish(y, pageButton(s, Button.Kind.PRIMARY));
        }
    }

    private Button pageButton(Snapshot s, Button.Kind kind) {
        Snapshot.Release r = s.release();
        String url = r != null && r.htmlUrl() != null && !r.htmlUrl().isBlank() ? r.htmlUrl() : s.releasesPageUrl();
        return new Button(theme, "Open download page", kind, null, () -> actions.openPage(url));
    }

    private static String or(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    // the most a rich card's content may be wide: words wrap at this, buttons stack past it
    private int innerW() {
        return theme.px(maxDesignW) - 2 * theme.px(PAD);
    }

    private void line(String text, TextStyle style, int colour, int dx, float baseline) {
        lines.add(new Line(text, style, colour, dx, baseline));
        needW = Math.max(needW, dx + theme.measurer.measure(text, style));
    }

    // the card is as wide as the widest thing in it, within the floor and the room before the logo
    private void settle() {
        int pad = theme.px(PAD);
        int floor = theme.px(MIN_RICH_W) - 2 * pad;
        innerUsed = Math.max(floor, Math.min(innerW(), (int) Math.ceil(needW) - pad));
        cardW = pad * 2 + innerUsed;
    }

    // the one label line at the top. returns the y under it
    private int headline(String text) {
        TextMeasurer m = theme.measurer;
        int pad = theme.px(PAD);
        int y = pad;
        for (String l : TextWrap.wrap(m, text, theme.label, innerW())) {
            line(l, theme.label, Theme.TEXT, pad, y + m.ascent(theme.label));
            y += Math.round(m.lineHeight(theme.label));
        }
        return y;
    }

    private int small(String text, int colour, int y) {
        return paragraph(text, colour, theme.px(PAD), innerW(), y + theme.px(Theme.SPACE_1));
    }

    // "1" in the quiet grey, the step next to it, wrapped lines hang under the step and not the number
    private int step(int n, String text, int y) {
        TextMeasurer m = theme.measurer;
        int pad = theme.px(PAD);
        int indent = Math.round(m.measure("3", theme.smallLabel)) + theme.px(Theme.GAP);
        y += theme.px(Theme.SPACE_1);
        line(Integer.toString(n), theme.smallLabel, Theme.TEXT_3, pad, y + m.ascent(theme.small));
        return paragraph(text, Theme.TEXT_2, pad + indent, innerW() - indent, y);
    }

    private int paragraph(String text, int colour, int dx, int width, int y) {
        TextMeasurer m = theme.measurer;
        for (String l : TextWrap.wrap(m, text, theme.small, width)) {
            line(l, theme.small, colour, dx, y + m.ascent(theme.small));
            y += Math.round(m.lineHeight(theme.small));
        }
        return y;
    }

    // the button row. side by side when they fit, one under the other when the card had to go narrow
    private void finish(int y, Button... row) {
        int pad = theme.px(PAD);
        int max = innerW();
        int gap = theme.px(Theme.GAP);
        int ch = theme.px(Theme.CONTROL_H);
        if (row.length > 0) {
            y += theme.px(Theme.SPACE_3);
            int total = -gap;
            for (Button b : row) {
                total += b.preferredWidth() + gap;
            }
            if (total <= max) {
                needW = Math.max(needW, pad + total);
                int bx = pad;
                for (Button b : row) {
                    add(b, bx, y, b.preferredWidth(), ch);
                    bx += b.preferredWidth() + gap;
                }
                y += ch;
            } else {
                for (int i = 0; i < row.length; i++) {
                    Button b = row[i];
                    int bw = Math.min(max, b.preferredWidth());
                    needW = Math.max(needW, pad + bw);
                    add(b, pad, y, bw, ch);
                    y += ch + (i + 1 < row.length ? gap : 0);
                }
            }
        }
        settle();
        cardH = y + pad;
    }

    private void add(Button b, int dx, int dy, int w, int h) {
        buttons.add(b);
        kids.add(b);
        slots.add(new int[]{dx, dy, w, h});
    }

    @Override
    protected void layout() {
        for (int i = 0; i < buttons.size(); i++) {
            int[] s = slots.get(i);
            buttons.get(i).setBounds(x + s[0], y + s[1], s[2], s[3]);
        }
    }

    // ----- drawing

    @Override
    public void draw(Canvas c, Frame f) {
        if (current == null || cardW <= 0) {
            return;
        }
        theme.floating(c, x, y, cardW, cardH, theme.pxf(Theme.CARD_RADIUS));
        for (Line l : lines) {
            c.text(l.text, x + l.dx, y + l.baseline, l.style, theme.color(l.colour));
        }
        if (downloading) {
            drawProgress(c);
        }
        for (Button b : buttons) {
            b.draw(c, f);
        }
    }

    private void drawProgress(Canvas c) {
        Snapshot s = current;
        TextStyle style = theme.small.asTabular();
        String counter = counter(s.downloadedBytes(), s.totalBytes());
        float cw = c.measure(counter, style);
        c.text(counter, x + counterRight - cw, y + counterBaseline, style, theme.color(Theme.TEXT_2));
        int pad = theme.px(PAD);
        int inner = innerUsed;
        float bh = theme.px(BAR_H);
        float r = bh / 2f;
        c.fillRoundRect(x + pad, y + barTop, inner, bh, r, theme.color(Theme.FIELD));
        float frac = s.totalBytes() > 0 ? Math.max(0f, Math.min(1f, s.downloadedBytes() / (float) s.totalBytes())) : 0f;
        float fw = inner * frac;
        if (fw >= 1f) {
            c.fillRoundRect(x + pad, y + barTop, fw, bh, r, theme.color(Theme.ACCENT));
        }
    }

    public static String counter(long done, long total) {
        return total > 0 ? mb(done) + " of " + mb(total) + " MB" : mb(done) + " MB so far";
    }

    public static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    // ----- keyboard focus, the host (vanilla's focus walk) hands it over and takes it back

    public boolean hasFocusable() {
        for (Button b : buttons) {
            if (b.focusable()) {
                return true;
            }
        }
        return false;
    }

    public Widget focused() {
        return focusedKid;
    }

    public void focusEnd(boolean last) {
        List<Widget> all = new ArrayList<>();
        collectFocusable(all);
        if (all.isEmpty()) {
            return;
        }
        focus(all.get(last ? all.size() - 1 : 0));
    }

    // false when the step walks off the card's end, the host moves on to vanilla's next widget then
    public boolean cycleFocus(boolean back) {
        List<Widget> all = new ArrayList<>();
        collectFocusable(all);
        int i = all.indexOf(focusedKid);
        int next = i + (back ? -1 : 1);
        if (i < 0 || next < 0 || next >= all.size()) {
            blur();
            return false;
        }
        focus(all.get(next));
        return true;
    }

    public void focus(Widget w) {
        if (focusedKid != null && focusedKid != w) {
            focusedKid.setFocused(false);
        }
        focusedKid = w;
        if (w != null) {
            w.setFocused(true);
        }
    }

    public void blur() {
        focus(null);
    }

    @Override
    public boolean keyDown(int key, int mods) {
        return focusedKid != null && focusedKid.keyDown(key, mods);
    }

    // one line for the narrator: the headline and the quiet lines after it
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            if (l.style == theme.smallLabel) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(l.text);
        }
        if (downloading && current != null) {
            sb.append(", ").append(counter(current.downloadedBytes(), current.totalBytes()));
        }
        return sb.toString();
    }

    public String focusedLabel() {
        return focusedKid instanceof Button b ? b.text() : null;
    }

    // test hooks
    public List<Button> buttons() {
        return buttons;
    }

    public boolean compact() {
        return compact;
    }
}
