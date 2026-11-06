package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.List;

// the hover cards. draw only: no input, no focus, nothing it does can close a popover. the shell feeds it the innermost
// HasTooltip under the pointer after every move, it waits for the pointer to rest, then hangs the card under the thing
// (above when there is no room), kept inside the panel. same rest-then-show idea as the schematics tab's tip
public final class TooltipLayer {

    static final int DELAY_MS = 500;
    // leaving one tile for the next goes through a gap; the card waits this long before deciding you left
    static final int GRACE_MS = 80;
    private static final int MAX_W = 280;
    private static final int MAX_BODY_LINES = 8;

    private final Theme theme;
    private final TextStyle titleStyle;
    private final TextStyle bodyStyle;
    private Widget hovered;
    private long hoverSince;
    private Widget shown;
    private long leftAt;
    // a click or a key put the card away; it stays away until the pointer finds something else
    private Widget blockedOn;

    public TooltipLayer(Theme theme) {
        this.theme = theme;
        titleStyle = new TextStyle(Weight.SEMIBOLD, theme.px(13));
        bodyStyle = new TextStyle(Weight.REGULAR, theme.px(13));
    }

    // the innermost widget under the pointer that has a tooltip right now, walking the same topmost-child-first order
    // the input does. null when there is none
    public static Widget find(Widget root, float mx, float my) {
        if (root == null || !root.contains(mx, my)) {
            return null;
        }
        List<Widget> kids = root.children();
        for (int i = kids.size() - 1; i >= 0; i--) {
            Widget k = kids.get(i);
            if (k.contains(mx, my)) {
                Widget d = find(k, mx, my);
                if (d != null) {
                    return d;
                }
                break;
            }
        }
        return has(root) ? root : null;
    }

    private static boolean has(Widget w) {
        if (!(w instanceof HasTooltip ht)) {
            return false;
        }
        Tooltip t = ask(ht);
        return t != null && !t.empty();
    }

    // a widget whose tooltip() throws just has no tooltip
    private static Tooltip ask(HasTooltip ht) {
        try {
            return ht.tooltip();
        } catch (Throwable e) {
            Safe.report("tooltip", e);
            return null;
        }
    }

    public void track(Widget w, long nowNanos) {
        if (w == hovered) {
            return;
        }
        if (w != blockedOn) {
            blockedOn = null;
        }
        hovered = w;
        hoverSince = nowNanos;
        if (shown != null) {
            if (w == null) {
                leftAt = nowNanos;
            } else if (blockedOn == null) {
                // a card is already up: the next tile's shows straight away, waiting again would feel broken
                shown = w;
            }
        }
    }

    public void dismiss() {
        shown = null;
        blockedOn = hovered;
    }

    public void clear() {
        shown = null;
        hovered = null;
        blockedOn = null;
    }

    // the widget whose card is up, for tests
    public Widget showing(long nowNanos) {
        update(nowNanos);
        return shown;
    }

    private void update(long nowNanos) {
        if (shown == null && hovered != null && blockedOn == null && nowNanos - hoverSince >= Anim.ms(DELAY_MS)) {
            shown = hovered;
        }
        if (shown != null && hovered == null && nowNanos - leftAt >= Anim.ms(GRACE_MS)) {
            shown = null;
        }
        if (shown != null && !shown.visible()) {
            shown = null;
        }
    }

    // region is where the card has to stay, the panel
    public void draw(Canvas c, long nowNanos, float rx, float ry, float rw, float rh) {
        update(nowNanos);
        Widget target = shown;
        if (!(target instanceof HasTooltip ht)) {
            return;
        }
        Tooltip t = ask(ht);
        if (t == null || t.empty()) {
            return;
        }
        float margin = theme.px(8);
        // a card taller than the panel loses body lines until it fits; the title and #set line matter most
        Card card = layout(c, t, MAX_BODY_LINES);
        for (int lines = MAX_BODY_LINES - 1; card.h > rh - 2 * margin && lines >= 1; lines--) {
            card = layout(c, t, lines);
        }
        float gap = theme.px(6);
        float left = Math.max(rx + margin, Math.min(rx + rw - margin - card.w, target.x()));
        float below = target.y() + target.h() + gap;
        float above = target.y() - gap - card.h;
        float top = below + card.h <= ry + rh - margin || above < ry + margin ? below : above;
        // neither side fits (a tall card in a short panel): pin it inside rather than hang off the edge
        top = Math.max(ry + margin, Math.min(ry + rh - margin - card.h, top));
        card.draw(c, left, top);
    }

    private Card layout(TextMeasurer m, Tooltip t, int maxLines) {
        int padX = theme.px(14);
        float room = theme.px(MAX_W) - 2f * padX;
        List<String> body = t.body().isEmpty() ? List.of() : TextWrap.words(m, t.body(), bodyStyle, room);
        if (body.size() > maxLines) {
            List<String> cut = new ArrayList<>(body.subList(0, maxLines));
            String last = String.join(" ", body.subList(maxLines - 1, body.size()));
            cut.set(maxLines - 1, m.ellipsize(last, bodyStyle, room));
            body = cut;
        }
        String title = t.title().isEmpty() ? "" : m.ellipsize(t.title(), titleStyle, room);
        float widest = title.isEmpty() ? 0f : m.measure(title, titleStyle);
        for (String l : body) {
            widest = Math.max(widest, m.measure(l, bodyStyle));
        }
        List<String> foot = new ArrayList<>();
        for (int i = 0; i < t.footer().size(); i++) {
            TextStyle s = i == 0 ? theme.smallLabel : theme.small;
            String l = m.ellipsize(t.footer().get(i), s, room);
            foot.add(l);
            widest = Math.max(widest, m.measure(l, s));
        }
        float h = theme.px(12);
        if (!title.isEmpty()) {
            h += theme.px(16);
        }
        if (!body.isEmpty()) {
            h += (title.isEmpty() ? 0 : theme.px(4)) + body.size() * theme.px(18);
        }
        if (!foot.isEmpty()) {
            boolean above = !title.isEmpty() || !body.isEmpty();
            h += (above ? theme.px(10) + 1 + theme.px(8) : 0) + foot.size() * theme.px(15) + (foot.size() - 1) * theme.px(3);
        }
        h += theme.px(12);
        float w = Math.min(theme.px(MAX_W), (float) Math.ceil(widest) + 2f * padX);
        return new Card(title, body, foot, w, h);
    }

    private final class Card {
        final String title;
        final List<String> body;
        final List<String> foot;
        final float w;
        final float h;

        Card(String title, List<String> body, List<String> foot, float w, float h) {
            this.title = title;
            this.body = body;
            this.foot = foot;
            this.w = w;
            this.h = h;
        }

        void draw(Canvas c, float left, float top) {
            theme.floating(c, left, top, w, h, theme.pxf(Theme.CARD_RADIUS));
            float tx = left + theme.px(14);
            float yy = top + theme.px(12);
            if (!title.isEmpty()) {
                c.text(title, tx, mid(c, titleStyle, yy, theme.px(16)), titleStyle, theme.color(Theme.TEXT));
                yy += theme.px(16);
            }
            if (!body.isEmpty()) {
                yy += title.isEmpty() ? 0 : theme.px(4);
                // a lone sentence is the main thing on the card and reads in full colour, under a title it steps back
                int col = theme.color(title.isEmpty() ? Theme.TEXT : Theme.TEXT_2);
                for (String l : body) {
                    c.text(l, tx, mid(c, bodyStyle, yy, theme.px(18)), bodyStyle, col);
                    yy += theme.px(18);
                }
            }
            if (!foot.isEmpty()) {
                if (!title.isEmpty() || !body.isEmpty()) {
                    yy += theme.px(10);
                    c.fillRect(tx, yy, w - 2f * theme.px(14), 1, theme.color(Theme.HAIRLINE));
                    yy += 1 + theme.px(8);
                }
                for (int i = 0; i < foot.size(); i++) {
                    TextStyle s = i == 0 ? theme.smallLabel : theme.small;
                    int col = theme.color(i == 0 ? Theme.TEXT : Theme.TEXT_3);
                    c.text(foot.get(i), tx, mid(c, s, yy, theme.px(15)), s, col);
                    yy += theme.px(15) + theme.px(3);
                }
            }
        }
    }

    private static float mid(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }
}
