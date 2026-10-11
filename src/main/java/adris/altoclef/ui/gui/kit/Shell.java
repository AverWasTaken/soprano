package adris.altoclef.ui.gui.kit;

import adris.altoclef.ui.gui.facade.KeyNames;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.model.KeyBind;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

// the chrome around the tabs: panel, sidebar, header with search, status strip, toast layer and the overlay layer for
// dropdowns. it also owns focus and routes every key and click, so a tab never has to care who is focused. the scrim
// behind the panel is the screen's job. everything a tab, the status facade or a callback throws is caught and logged
// once, the panel stays up
public class Shell extends Widget implements ShellContext {

    private final List<Tab> tabs;
    private final Widget[] contents;
    private final StatusAccess status;
    private final KeyNames keyNames;
    private final KeyBind openKey;
    private final Clipboard clipboard;
    private final Runnable onCloseRequest;

    private final SearchField search;
    private final KeyCapture keyCapture;
    private final Button stop;
    private final Toast toast;
    private final List<Widget> kids = new ArrayList<>();
    private final FadeCanvas fade = new FadeCanvas();

    private final Anim openAnim = new Anim(1f);
    private final Anim barY = new Anim(0f);
    private final Anim[] tabHover;
    private final Anim[] tabActive;
    private final Anim busyAnim = new Anim(0f);

    private int active;
    private int hoverTab = -1;
    private Widget focused;
    private Widget overlay;
    private boolean closing;
    private boolean closeFired;
    private long lastNow;
    private int winW;
    private int winH;

    private StatusAccess.Activity activity = new StatusAccess.Activity(false, "Idle", "", null);
    private float lineW;
    private float detailW;
    private float phaseW;

    private int sideW;
    private int headerH;
    private int statusH;
    private int contentX;
    private int contentY;
    private int contentW;
    private int contentH;

    public Shell(Theme theme, List<Tab> tabs, StatusAccess status, KeyNames keyNames, int currentOpenKey,
                 IntConsumer onRebind, Clipboard clipboard, Runnable onCloseRequest) {
        super(theme);
        this.tabs = List.copyOf(tabs);
        this.status = status;
        this.keyNames = keyNames;
        this.openKey = new KeyBind(currentOpenKey);
        this.clipboard = clipboard;
        this.onCloseRequest = onCloseRequest;
        TextField.defaultClipboard = clipboard;

        contents = new Widget[this.tabs.size()];
        tabHover = new Anim[this.tabs.size()];
        tabActive = new Anim[this.tabs.size()];
        for (int i = 0; i < contents.length; i++) {
            Tab t = this.tabs.get(i);
            Widget content = null;
            try {
                content = t.content();
            } catch (Throwable e) {
                Safe.report("tab content " + i, e);
            }
            contents[i] = content != null ? content : new Blank(theme);
            tabHover[i] = new Anim(0f);
            tabActive[i] = new Anim(i == 0 ? 1f : 0f);
        }

        search = new SearchField(theme, "Search", q -> {
            if (this.tabs.isEmpty()) {
                return;
            }
            Tab t = this.tabs.get(active);
            Safe.run("tab search", () -> t.onSearch(q));
        });
        keyCapture = new KeyCapture(theme, openKey, keyNames, k -> {
            // the key is still physically down and will start repeating: swallow it until it comes up
            suppressedKey = k;
            if (onRebind != null) {
                Safe.run("rebind", () -> onRebind.accept(k));
            }
        }, this);
        stop = new Button(theme, "Stop", Button.Kind.DANGER, Icon.STOP, this::stopClicked);
        toast = new Toast(theme);

        kids.add(contents.length > 0 ? contents[0] : new Blank(theme));
        kids.add(search);
        kids.add(keyCapture);
        kids.add(stop);

        if (!this.tabs.isEmpty()) {
            Tab first = this.tabs.get(0);
            applySearchFor(first);
            Safe.run("tab shown", first::onShown);
        }
    }

    // a tab with no content widget still gets a slot
    private static final class Blank extends Widget {
        Blank(Theme theme) {
            super(theme);
        }

        @Override
        public void draw(Canvas c, Frame f) {
        }
    }

    // ----- accessors the screen and the tests use

    public int activeIndex() {
        return active;
    }

    public Widget focusedWidget() {
        return focused;
    }

    public SearchField search() {
        return search;
    }

    public Button stopButton() {
        return stop;
    }

    public KeyCapture keyCapture() {
        return keyCapture;
    }

    public Toast toast() {
        return toast;
    }

    public Widget overlay() {
        return overlay;
    }

    public boolean closing() {
        return closing;
    }

    public int contentX() {
        return contentX;
    }

    public int contentY() {
        return contentY;
    }

    public int contentW() {
        return contentW;
    }

    public int contentH() {
        return contentH;
    }

    public int tabRowY(int i) {
        return y + headerH + i * theme.px(Theme.ROW_H);
    }

    public int tabRowH() {
        return theme.px(Theme.ROW_H);
    }

    public int sidebarW() {
        return sideW;
    }

    // the other capture control (settings tab) changed the bind, keep the close key honest
    public void setOpenKey(int key) {
        openKey.key = key;
    }

    // same, for a rebind that came from a key press (a tab's own KeyCapture): the key is still down and will repeat
    public void openKeyRebound(int key) {
        openKey.key = key;
        suppressedKey = key;
    }

    // guiKeyEnabled off means the key does nothing at all, closing included
    public void setOpenKeyEnabled(boolean enabled) {
        openKey.enabled = enabled;
    }

    // the screen forwards key releases so a freshly bound key held down does not close the panel through key repeat
    public void keyUp(int key) {
        if (key == suppressedKey) {
            suppressedKey = -2;
        }
    }

    private int suppressedKey = -2;
    private boolean disposed;

    public int openKey() {
        return openKey.key;
    }

    // ----- sizing

    // 62% by 70% of the window, clamped, centred. min and max are in design pixels so they scale with everything else
    public void setViewport(int windowW, int windowH) {
        // the screen may call this every frame, relaying out every time would also snap the sliding tab bar
        if (windowW == winW && windowH == winH && w > 0) {
            return;
        }
        winW = windowW;
        winH = windowH;
        int pw = Math.round(windowW * 0.68f);
        int ph = Math.round(windowH * 0.76f);
        pw = Math.max(theme.px(760), Math.min(theme.px(1400), pw));
        ph = Math.max(theme.px(480), Math.min(theme.px(900), ph));
        int margin = theme.px(8);
        pw = Math.min(pw, Math.max(1, windowW - 2 * margin));
        ph = Math.min(ph, Math.max(1, windowH - 2 * margin));
        setBounds((windowW - pw) / 2, (windowH - ph) / 2, pw, ph);
    }

    @Override
    protected void layout() {
        sideW = theme.px(Theme.SIDEBAR_W);
        headerH = theme.px(Theme.HEADER_H);
        statusH = theme.px(Theme.STATUS_H);
        contentX = x + sideW + 1;
        contentY = y + headerH + 1;
        contentW = Math.max(0, w - sideW - 1);
        contentH = Math.max(0, y + h - statusH - contentY);

        placeContent(active);
        toast.setBounds(contentX, contentY, contentW, contentH);

        int sw = Math.min(theme.px(260), Math.max(theme.px(120), contentW - 2 * theme.px(Theme.PAD) - theme.px(120)));
        int ch = theme.px(Theme.CONTROL_H);
        search.setBounds(x + w - theme.px(Theme.PAD) - sw, y + (headerH - ch) / 2, sw, ch);

        int footerLabelW = Math.round(theme.measurer.measure("Open key", theme.small));
        int capW = Math.max(theme.px(64), sideW - 2 * theme.px(Theme.PAD) - footerLabelW - theme.px(8));
        keyCapture.setBounds(x + sideW - theme.px(Theme.PAD) - capW, y + h - statusH - theme.px(Theme.PAD) - ch, capW, ch);

        int sh = theme.px(28);
        int sbw = stop.preferredWidth();
        stop.setBounds(x + w - theme.px(Theme.PAD) - sbw, y + h - statusH + (statusH - sh) / 2, sbw, sh);
        barY.snap(tabRowY(active) + tabRowH() / 2f);
    }

    private void placeContent(int i) {
        if (i >= 0 && i < contents.length) {
            Widget c = contents[i];
            Safe.run("tab layout", () -> c.setBounds(contentX, contentY, contentW, contentH));
        }
    }

    // ----- open and close

    public void open(long nowNanos) {
        if (disposed) {
            // reopened after dispose: the visible tab was told it went away, tell it it is back
            disposed = false;
            if (active >= 0 && active < tabs.size()) {
                Tab t = tabs.get(active);
                Safe.run("tab shown", t::onShown);
            }
        }
        closing = false;
        closeFired = false;
        openAnim.snap(0f);
        openAnim.set(1f, nowNanos, Theme.ANIM_SLOW);
        lastNow = nowNanos;
    }

    public void close(long nowNanos) {
        if (closing) {
            return;
        }
        closing = true;
        closeFired = false;
        openAnim.set(0f, nowNanos, Theme.ANIM_SLOW);
        lastNow = nowNanos;
    }

    // the close animation has played out
    public boolean closed(long nowNanos) {
        return closing && !openAnim.running(nowNanos) && openAnim.target() == 0f;
    }

    // for the screen being removed: tells the visible tab it is going away
    public void dispose() {
        disposed = true;
        if (active >= 0 && active < tabs.size()) {
            Tab t = tabs.get(active);
            Safe.run("tab hidden", t::onHidden);
        }
    }

    // ----- tabs

    public void switchTab(int i) {
        if (i < 0 || i >= tabs.size() || i == active) {
            return;
        }
        blur();
        closeOverlay();
        Tab old = tabs.get(active);
        Safe.run("tab hidden", old::onHidden);
        active = i;
        kids.set(0, contents[i]);
        placeContent(i);
        Tab t = tabs.get(i);
        applySearchFor(t);
        Safe.run("tab shown", t::onShown);
        barY.set(tabRowY(i) + tabRowH() / 2f, lastNow, Theme.ANIM);
    }

    // the search box belongs to the shell, the tab only says whether it wants one
    private void applySearchFor(Tab t) {
        boolean has = Safe.test("tab hasSearch", t::hasSearch);
        search.setVisible(has);
        search.setText("");
        try {
            search.setPlaceholder(t.searchPlaceholder());
        } catch (Throwable e) {
            Safe.report("tab placeholder", e);
        }
        if (has) {
            Safe.run("tab search reset", () -> t.onSearch(""));
        }
    }

    // ----- status

    private void stopClicked() {
        Result r = null;
        try {
            r = status.stop();
        } catch (Throwable e) {
            Safe.report("status stop", e);
            r = Result.fail("Could not stop. Try #stop in chat.");
        }
        if (r != null) {
            toast.show(r);
        }
    }

    private void pollStatus() {
        StatusAccess.Activity a;
        try {
            a = status.activity();
        } catch (Throwable e) {
            Safe.report("status activity", e);
            return;
        }
        if (a == null || a.equals(activity)) {
            return;
        }
        activity = a;
        TextMeasurer m = theme.measurer;
        lineW = a.line() == null || a.line().isEmpty() ? 0f : m.measure(a.line(), theme.body);
        detailW = a.detail() == null || a.detail().isEmpty() ? 0f : m.measure(a.detail(), theme.small);
        phaseW = a.gamerPhase() == null ? 0f : m.measure(a.gamerPhase(), theme.smallLabel);
    }

    // ----- ShellContext

    @Override
    public Theme theme() {
        return theme;
    }

    @Override
    public Clipboard clipboard() {
        return clipboard;
    }

    @Override
    public void toast(Result result) {
        toast.show(result);
    }

    @Override
    public void toast(String line, String command) {
        toast.show(line, command);
    }

    @Override
    public void focus(Widget widget) {
        if (widget == null) {
            blur();
            return;
        }
        // the flag check matters: a recycled row widget can be defocused behind our back and come straight back
        if (focused == widget && widget.isFocused()) {
            return;
        }
        if (focused != null && focused != widget) {
            focused.setFocused(false);
        }
        focused = widget;
        widget.setFocused(true);
    }

    @Override
    public void blur() {
        if (focused != null) {
            focused.setFocused(false);
            focused = null;
        }
    }

    @Override
    public void openOverlay(Widget w) {
        closeOverlay();
        overlay = w;
        w.setVisible(true);
    }

    @Override
    public void closeOverlay() {
        if (overlay != null) {
            overlay.setVisible(false);
            overlay = null;
        }
    }

    @Override
    public void requestClose() {
        close(lastNow);
    }

    // ----- drawing

    @Override
    public void draw(Canvas c, Frame f) {
        try {
            drawShell(c, f);
        } catch (Throwable t) {
            Safe.report("shell draw", t);
        }
    }

    private void drawShell(Canvas c, Frame f) {
        long now = f.nowNanos();
        lastNow = now;
        if (closing && !closeFired && !openAnim.running(now) && openAnim.target() == 0f) {
            closeFired = true;
            if (onCloseRequest != null) {
                Safe.run("close request", onCloseRequest);
            }
        }
        float a = openAnim.value(now);
        if (a <= 0.003f) {
            return;
        }
        Canvas g = c;
        if (a < 0.999f) {
            g = fade.reset(c, a, (1f - a) * theme.px(8));
        }

        float r = theme.pxf(Theme.PANEL_RADIUS);
        g.shadow(x, y, w, h, r, Theme.SHADOW);
        g.fillRoundRect(x, y, w, h, r, Theme.PANEL);

        drawSidebar(g, f, now, r);
        drawHeader(g, f, now);

        g.pushClip(contentX, contentY, contentW, contentH);
        Widget content = kids.get(0);
        Canvas target = g;
        Safe.run("tab draw", () -> content.draw(target, f));
        g.popClip();

        drawStatus(g, f, now);
        toast.draw(g, f);
        g.strokeRoundRect(x, y, w, h, r, Theme.HAIRLINE);
        drawBar(g, now);

        Widget o = overlay;
        if (o != null && o.visible()) {
            Safe.run("overlay draw", () -> o.draw(drawTarget(c, a), f));
        }
    }

    // fresh FadeCanvas state for a nested draw, or the plain canvas once the panel is fully open
    private Canvas drawTarget(Canvas c, float a) {
        if (a >= 0.999f) {
            return c;
        }
        return fade.reset(c, a, (1f - a) * theme.px(8));
    }

    private void drawSidebar(Canvas g, Frame f, long now, float r) {
        // only the top left corner is round: the panel's own corners cover the rest, so square off the others
        g.fillRoundRect(x, y, sideW, h - statusH, r, Theme.SIDEBAR);
        g.fillRect(x + sideW / 2f, y, sideW / 2f, h - statusH, Theme.SIDEBAR);
        g.fillRect(x, y + (h - statusH) / 2f, sideW / 2f, (h - statusH) / 2f, Theme.SIDEBAR);
        g.fillRect(x + sideW, y, 1, h - statusH, Theme.HAIRLINE);

        int pad = theme.px(Theme.PAD);
        g.text("soprano", x + pad + theme.px(8), baselineIn(g, theme.label, y, headerH), theme.label, Theme.TEXT_2);

        int rowH = tabRowH();
        // the active pill slides between rows (same anim as the accent bar), hovered rows get a fainter one
        if (!tabs.isEmpty()) {
            float py = barY.value(now) - rowH / 2f;
            g.fillRoundRect(x + theme.px(8), py, sideW - theme.px(16), rowH, theme.pxf(Theme.ROW_RADIUS), Theme.HOVER);
        }
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            tabHover[i].set(i == hoverTab ? 1f : 0f, now, Theme.ANIM_FAST);
            tabActive[i].set(i == active ? 1f : 0f, now, Theme.ANIM_FAST);
            float hv = tabHover[i].value(now);
            float av = tabActive[i].value(now);
            float ry = tabRowY(i);
            float rx = x + theme.px(8);
            float rw = sideW - theme.px(16);
            if (hv > 0.01f && av < 0.99f) {
                g.fillRoundRect(rx, ry, rw, rowH, theme.pxf(Theme.ROW_RADIUS), Theme.fade(Theme.HOVER, hv * 0.5f * (1f - av)));
            }
            int col = Theme.lerp(Theme.lerp(Theme.TEXT_2, Theme.TEXT, hv * 0.7f), Theme.TEXT, av);
            int is = theme.px(Theme.ICON);
            Icon icon = tabIcon(t);
            if (icon != null) {
                g.icon(icon, rx + theme.px(10), ry + (rowH - is) / 2f, is, col);
            }
            String title = tabTitle(t);
            g.text(title, rx + theme.px(10) + is + theme.px(10), baselineIn(g, theme.label, ry, rowH), theme.label, col);
        }

        g.text("Open key", x + pad, baselineIn(g, theme.small, keyCapture.y(), keyCapture.h()), theme.small, Theme.TEXT_3);
        keyCapture.draw(g, f);
    }

    private static Icon tabIcon(Tab t) {
        try {
            return t.icon();
        } catch (Throwable e) {
            Safe.report("tab icon", e);
            return null;
        }
    }

    private static String tabTitle(Tab t) {
        try {
            String s = t.title();
            return s == null ? "" : s;
        } catch (Throwable e) {
            Safe.report("tab title", e);
            return "";
        }
    }

    private void drawBar(Canvas g, long now) {
        if (tabs.isEmpty()) {
            return;
        }
        float cy = barY.value(now);
        float bh = theme.px(20);
        g.fillRect(x, cy - bh / 2f, theme.px(Theme.TAB_BAR_W), bh, Theme.ACCENT);
    }

    private void drawHeader(Canvas g, Frame f, long now) {
        g.fillRect(contentX, y + headerH, contentW, 1, Theme.HAIRLINE);
        if (tabs.isEmpty()) {
            return;
        }
        int pad = theme.px(Theme.PAD);
        String title = tabTitle(tabs.get(active));
        float room = contentW - 2 * pad - (search.visible() ? search.w() + pad : 0);
        title = theme.measurer.ellipsize(title, theme.title, Math.max(0f, room));
        g.text(title, contentX + pad, baselineIn(g, theme.title, y, headerH), theme.title, Theme.TEXT);
        if (search.visible()) {
            search.draw(g, f);
        }
    }

    private void drawStatus(Canvas g, Frame f, long now) {
        pollStatusIfIdle();
        int top = y + h - statusH;
        g.fillRect(x, top, w, 1, Theme.HAIRLINE);
        int pad = theme.px(Theme.PAD);
        busyAnim.set(activity.busy() ? 1f : 0f, now, Theme.ANIM);
        float bv = busyAnim.value(now);
        int is = theme.px(12);
        float iy = top + 1 + (statusH - 1 - is) / 2f;
        g.icon(Icon.RING, x + pad, iy, is, Theme.fade(Theme.HAIRLINE_STRONG, 1f - bv));
        g.icon(Icon.DOT, x + pad, iy, is, Theme.fade(Theme.ACCENT, bv));

        float right = stop.x() - theme.px(Theme.GAP);
        String phase = activity.gamerPhase();
        if (phase != null) {
            float pw = phaseW + theme.px(16);
            float ph = theme.px(22);
            float px = right - pw;
            g.fillRoundRect(px, top + 1 + (statusH - 1 - ph) / 2f, pw, ph, ph / 2f, Theme.RAISED);
            g.text(phase, px + theme.px(8), baselineIn(g, theme.smallLabel, top + 1, statusH - 1), theme.smallLabel, Theme.TEXT_2);
            right = px - theme.px(Theme.GAP);
        }

        float tx = x + pad + is + theme.px(10);
        float room = Math.max(0f, right - tx);
        String line = activity.line() == null ? "" : activity.line();
        float base = baselineIn(g, theme.body, top + 1, statusH - 1);
        String shown = lineW <= room ? line : theme.measurer.ellipsize(line, theme.body, room);
        g.text(shown, tx, base, theme.body, Theme.TEXT);
        String detail = activity.detail();
        if (detail != null && !detail.isEmpty() && lineW < room) {
            float dx = tx + lineW + theme.px(6);
            String dot = "·";
            float dotW = g.measure(dot, theme.body);
            float dRoom = right - dx - dotW - theme.px(6);
            if (dRoom > theme.px(24)) {
                g.text(dot, dx, base, theme.body, Theme.TEXT_3);
                String d = detailW <= dRoom ? detail : theme.measurer.ellipsize(detail, theme.small, dRoom);
                g.text(d, dx + dotW + theme.px(6), baselineIn(g, theme.small, top + 1, statusH - 1), theme.small, Theme.TEXT_2);
            }
        }
        stop.draw(g, f);
    }

    private void pollStatusIfIdle() {
        // tick polls every 50 ms, this only matters before the first tick
        if (!polled) {
            polled = true;
            pollStatus();
        }
    }

    private boolean polled;

    private static float baselineIn(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }

    // ----- input

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public void tick() {
        pollStatus();
        for (Widget k : kids) {
            Safe.run("tick", k::tick);
        }
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        try {
            Widget o = overlay;
            if (o != null && o.visible()) {
                o.mouseMove(mx, my);
            }
            hoverTab = tabAt(mx, my);
            return super.mouseMove(mx, my);
        } catch (Throwable t) {
            Safe.report("shell mouseMove", t);
            return null;
        }
    }

    private int tabAt(float mx, float my) {
        if (mx < x + theme.px(8) || mx >= x + sideW - theme.px(8)) {
            return -1;
        }
        for (int i = 0; i < tabs.size(); i++) {
            float ry = tabRowY(i);
            if (my >= ry && my < ry + tabRowH()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        try {
            Widget o = overlay;
            if (o != null && o.visible()) {
                if (o.contains(mx, my)) {
                    o.mouseDown(mx, my, button, mods);
                } else {
                    // the click that dismisses a popup does nothing else, or clicking the dropdown again would reopen it
                    closeOverlay();
                }
                return true;
            }
            if (!contains(mx, my)) {
                return false;
            }
            int t = tabAt(mx, my);
            if (t >= 0) {
                switchTab(t);
                return true;
            }
            super.mouseDown(mx, my, button, mods);
            retarget(mx, my);
            return true;
        } catch (Throwable t) {
            Safe.report("shell mouseDown", t);
            return true;
        }
    }

    // after a click: whatever focus-on-click widget is under the pointer gets focus, otherwise focus is dropped
    private void retarget(float mx, float my) {
        List<Widget> all = new ArrayList<>();
        collectFocusable(all);
        all.add(keyCapture);
        Widget hit = null;
        for (int i = all.size() - 1; i >= 0; i--) {
            Widget k = all.get(i);
            if (k instanceof FocusOnClick && k.contains(mx, my)) {
                hit = k;
                break;
            }
        }
        if (hit == null) {
            blur();
        } else {
            focus(hit);
        }
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        try {
            Widget o = overlay;
            if (o != null && o.visible()) {
                return o.mouseUp(mx, my, button, mods);
            }
            return super.mouseUp(mx, my, button, mods);
        } catch (Throwable t) {
            Safe.report("shell mouseUp", t);
            return false;
        }
    }

    @Override
    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        try {
            Widget o = overlay;
            if (o != null && o.visible()) {
                return o.mouseDrag(mx, my, button, dx, dy);
            }
            return super.mouseDrag(mx, my, button, dx, dy);
        } catch (Throwable t) {
            Safe.report("shell mouseDrag", t);
            return false;
        }
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        try {
            Widget o = overlay;
            if (o != null && o.visible()) {
                o.scroll(mx, my, dy);
                return true;
            }
            return super.scroll(mx, my, dy);
        } catch (Throwable t) {
            Safe.report("shell scroll", t);
            return false;
        }
    }

    // search, the tab's content, then stop. the footer key capture is click only
    @Override
    public void collectFocusable(List<Widget> out) {
        if (search.visible()) {
            search.collectFocusable(out);
        }
        kids.get(0).collectFocusable(out);
        stop.collectFocusable(out);
    }

    private void cycleFocus(boolean back) {
        List<Widget> list = new ArrayList<>();
        Safe.run("collect focusable", () -> collectFocusable(list));
        if (list.isEmpty()) {
            return;
        }
        int n = list.size();
        int i = list.indexOf(focused);
        int next = i < 0 ? (back ? n - 1 : 0) : (i + (back ? -1 : 1) + n) % n;
        focus(list.get(next));
    }

    @Override
    public boolean keyDown(int key, int mods) {
        try {
            return route(key, mods);
        } catch (Throwable t) {
            Safe.report("shell keyDown", t);
            return true;
        }
    }

    // focus on a widget that was hidden or recycled (a pooled settings row, say) is not focus any more
    private void validateFocus() {
        if (focused != null && (!focused.visible() || !focused.isFocused())) {
            blur();
        }
    }

    private boolean route(int key, int mods) {
        Widget o = overlay;
        if (o != null && o.visible()) {
            if (!o.keyDown(key, mods) && key == Keys.ESCAPE) {
                closeOverlay();
            }
            return true;
        }
        validateFocus();
        // a rebind in progress owns every key, tab and esc included
        if (focused instanceof KeyCapture kc && kc.capturing()) {
            boolean spent = Safe.test("key capture", () -> kc.keyDown(key, mods));
            // whatever ended the capture (bound, cancelled with esc, cleared) is still down and will repeat
            suppressedKey = key;
            return spent;
        }
        if (key == suppressedKey) {
            return true;
        }
        suppressedKey = -2;
        if (key == Keys.TAB) {
            cycleFocus(Keys.shift(mods));
            return true;
        }
        if (key == Keys.ESCAPE) {
            if (focused != null) {
                if (Safe.test("esc focused", () -> focused.keyDown(key, mods))) {
                    return true;
                }
                blur();
                return true;
            }
            if (search.visible() && search.keyDown(key, mods)) {
                return true;
            }
            requestClose();
            return true;
        }
        if (focused != null) {
            Widget f = focused;
            if (Safe.test("key", () -> f.keyDown(key, mods))) {
                if (f instanceof KeyCapture) {
                    // enter or space just started a capture, the repeat must not bind itself
                    suppressedKey = key;
                }
                return true;
            }
        }
        // the open key closes it again, but never while you are typing: a bind on a letter would otherwise eat text
        if (openKey.closes(key) && !(focused instanceof TextField)) {
            requestClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char c) {
        try {
            Widget o = overlay;
            if (o != null && o.visible()) {
                o.charTyped(c);
                return true;
            }
            validateFocus();
            if (focused != null) {
                Widget f = focused;
                // the char that comes with a rebind keypress is not search text
                if (Safe.test("char", () -> f.charTyped(c)) || f instanceof TextField || f instanceof KeyCapture) {
                    return true;
                }
                // a click on a list leaves it focused, typing should still reach the search box
            }
            if (c > 32 && c != 127 && search.visible()) {
                focus(search);
                return search.charTyped(c);
            }
            return false;
        } catch (Throwable t) {
            Safe.report("shell charTyped", t);
            return true;
        }
    }
}
