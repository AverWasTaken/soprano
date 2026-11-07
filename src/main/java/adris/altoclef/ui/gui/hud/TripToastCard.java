package adris.altoclef.ui.gui.hud;

import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Easing;
import adris.altoclef.ui.gui.kit.FadeCanvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.TextMeasurer;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.TextWrap;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Weight;
import baritone.behavior.trip.Trip;
import baritone.behavior.trip.TripToast;

import java.util.ArrayList;
import java.util.List;

// the "may i collect some blocks?" toast in the bottom right corner, and the collecting / done ones after it. sizes are
// gui-mock/hud's (design px, hud.css .toast). no minecraft in here, the hud glue in mc/TripToastHud feeds it a view, the
// keys' names and where the corner is, and the previews feed it the same things on a Java2dCanvas
public final class TripToastCard {

    public enum Hit {
        YES,
        NO,
        STOP
    }

    // design px, hud.css
    static final int WIDTH = 380;
    static final int PAD = 12;
    static final int TILE = 36;
    static final int TILE_RADIUS = 9;
    static final int ITEM = 24;
    static final int GAP = 12;
    static final int LINE = 17;
    static final int BTN_H = 28;
    static final int KEY = 18;
    // how far in from the window's corner, like .toasts { right: 16px; bottom: 16px }
    public static final int MARGIN = 16;
    // the one bit of motion: 24 px in from the right while it fades up
    private static final int SLIDE = 24;

    private final Theme theme;
    private final TextStyle titleStyle;
    private final TextStyle bodyStyle;
    private final TextStyle metaStyle;
    private final TextStyle btnStyle;
    private final TextStyle keyStyle;
    private final FadeCanvas fade = new FadeCanvas();

    // what is on screen. a view going away keeps drawing for a short fade so it doesn't just blink out
    private TripToast.View view;
    private TripToast.View leaving;
    private long shownAt = -1;
    private long leftAt = -1;
    private String yesKey = "Y";
    private String noKey = "N";

    // the last layout, in device px, so clicks and hover hit what was drawn
    private float x;
    private float y;
    private float w;
    private float h;
    private final List<Btn> buttons = new ArrayList<>();
    private Hit hover;

    private record Btn(Hit hit, String label, String key, boolean primary, float x, float y, float w, float h) {
        boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    public TripToastCard(Theme theme) {
        this.theme = theme;
        this.titleStyle = new TextStyle(Weight.SEMIBOLD, theme.px(14));
        this.bodyStyle = new TextStyle(Weight.REGULAR, theme.px(13));
        this.metaStyle = new TextStyle(Weight.MEDIUM, theme.px(12), true);
        this.btnStyle = new TextStyle(Weight.MEDIUM, theme.px(13));
        this.keyStyle = new TextStyle(Weight.SEMIBOLD, theme.px(11));
    }

    public Theme theme() {
        return theme;
    }

    // for the atlas prewarm, these sizes aren't the theme's own
    public TextStyle[] styles() {
        return new TextStyle[]{titleStyle, bodyStyle, metaStyle, btnStyle, keyStyle};
    }

    // the keys as the controls screen names them, so a rebind shows up on the chip too. null or blank means unbound,
    // and an unbound key gets no chip at all instead of a lying one
    public void setKeys(String yes, String no) {
        yesKey = yes;
        noKey = no;
    }

    // a new id is a new toast (slides in), the same id is the same toast with new numbers. true when a new one showed,
    // that's when the hud plays its sound
    public boolean show(TripToast.View next, long nowNanos) {
        TripToast.View v = next != null && next.kind() != TripToast.Kind.NONE ? next : null;
        if (v == null) {
            if (view != null) {
                leaving = view;
                leftAt = nowNanos;
                view = null;
                buttons.clear();
                hover = null;
            }
            return false;
        }
        boolean fresh = view == null || view.id() != v.id();
        view = v;
        if (fresh) {
            shownAt = nowNanos;
            leaving = null;
        }
        return fresh;
    }

    // a rebuilt card (new scale) carries on where the old one was: same toast, no second slide in
    public void adopt(TripToastCard old) {
        view = old.view;
        leaving = old.leaving;
        shownAt = old.shownAt;
        leftAt = old.leftAt;
        yesKey = old.yesKey;
        noKey = old.noKey;
    }

    public boolean visible() {
        return view != null || leaving != null;
    }

    public TripToast.View view() {
        return view;
    }

    // ----- layout

    // the card's height for a view, design px turned device px
    public float heightFor(TripToast.View v) {
        float top = Math.max(theme.pxf(TILE), textHeight(v));
        float acts = v.kind() == TripToast.Kind.DONE ? 0f : theme.pxf(GAP) + theme.pxf(BTN_H);
        return theme.pxf(PAD) + top + acts + theme.pxf(PAD);
    }

    public float width() {
        return theme.pxf(WIDTH);
    }

    private float textHeight(TripToast.View v) {
        float hgt = theme.pxf(1) + theme.pxf(LINE) + theme.pxf(3) + bodyLines(v).size() * theme.pxf(LINE);
        if (v.kind() == TripToast.Kind.COLLECTING && v.need() > 0) {
            hgt += theme.pxf(10) + theme.pxf(4);
        }
        return hgt;
    }

    private float textWidth() {
        return theme.pxf(WIDTH) - 2 * theme.pxf(PAD) - theme.pxf(TILE) - theme.pxf(GAP);
    }

    private List<String> bodyLines(TripToast.View v) {
        List<String> lines = TextWrap.wrap(theme.measurer, body(v), bodyStyle, textWidth());
        return lines.size() > 3 ? lines.subList(0, 3) : lines;
    }

    // bottom right, `right` and `bottom` px in from the window's edges (the hud glue lifts it over the hotbar or the
    // subtitles). whole px so the hairlines stay one pixel
    public void place(TripToast.View v, float winW, float winH, float right, float bottom) {
        w = Math.round(width());
        h = Math.round(heightFor(v));
        x = Math.round(winW - right - w);
        y = Math.round(winH - bottom - h);
        buttons.clear();
        if (v.kind() == TripToast.Kind.DONE) {
            return;
        }
        float by = y + h - theme.pxf(PAD) - theme.pxf(BTN_H);
        float right0 = x + w - theme.pxf(PAD);
        if (v.kind() == TripToast.Kind.ASK) {
            float yw = buttonWidth("Yes", yesKey);
            buttons.add(new Btn(Hit.YES, "Yes", yesKey, true, right0 - yw, by, yw, theme.pxf(BTN_H)));
            float nw = buttonWidth("No", noKey);
            buttons.add(new Btn(Hit.NO, "No", noKey, false, right0 - yw - theme.pxf(8) - nw, by, nw, theme.pxf(BTN_H)));
        } else {
            float sw = buttonWidth("Stop", noKey);
            buttons.add(new Btn(Hit.STOP, "Stop", noKey, false, right0 - sw, by, sw, theme.pxf(BTN_H)));
        }
    }

    private float buttonWidth(String label, String key) {
        float wdt = theme.pxf(12) + theme.measurer.measure(label, btnStyle);
        if (hasKey(key)) {
            wdt += theme.pxf(8) + keyWidth(key) + theme.pxf(6);
        } else {
            wdt += theme.pxf(12);
        }
        return Math.round(wdt);
    }

    private float keyWidth(String key) {
        return Math.max(theme.pxf(KEY), theme.measurer.measure(key, keyStyle) + 2 * theme.pxf(5));
    }

    private static boolean hasKey(String key) {
        return key != null && !key.isBlank();
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float w() {
        return w;
    }

    public float h() {
        return h;
    }

    // ----- input. px are device px, the same space it was drawn in

    public Hit hit(float px, float py) {
        if (view == null) {
            return null;
        }
        for (Btn b : buttons) {
            if (b.contains(px, py)) {
                return b.hit;
            }
        }
        return null;
    }

    // where a button ended up, {x, y, w, h} in device px. null when this toast has no such button
    public float[] bounds(Hit which) {
        for (Btn b : buttons) {
            if (b.hit == which) {
                return new float[]{b.x, b.y, b.w, b.h};
            }
        }
        return null;
    }

    public boolean contains(float px, float py) {
        return view != null && px >= x && px < x + w && py >= y && py < y + h;
    }

    public void mouseMove(float px, float py) {
        hover = hit(px, py);
    }

    public void clearHover() {
        hover = null;
    }

    // ----- drawing

    public void draw(Canvas c, Frame f) {
        long now = f.nowNanos();
        TripToast.View v = view != null ? view : leaving;
        if (v == null) {
            return;
        }
        float a;
        float dx = 0f;
        if (view != null) {
            long slow = Anim.enabled ? Anim.ms(Theme.ANIM_SLOW) : 0L;
            float p = slow == 0 ? 1f : Math.min(1f, (now - shownAt) / (float) slow);
            p = Easing.outCubic(Math.max(0f, p));
            a = p;
            dx = (1f - p) * theme.pxf(SLIDE);
        } else {
            long fast = Anim.enabled ? Anim.ms(Theme.ANIM_FAST) : 0L;
            float p = fast == 0 ? 1f : Math.min(1f, (now - leftAt) / (float) fast);
            if (p >= 1f) {
                leaving = null;
                return;
            }
            a = 1f - p;
        }
        Canvas g = a < 1f ? fade.reset(c, a, 0f) : c;
        float ox = x + Math.round(dx);
        glass(g, ox, y, w, h);
        drawTile(g, v, ox + theme.pxf(PAD), y + theme.pxf(PAD));
        drawText(g, v, ox + theme.pxf(PAD) + theme.pxf(TILE) + theme.pxf(GAP), y + theme.pxf(PAD));
        if (view != null) {
            for (Btn b : buttons) {
                drawButton(g, b, Math.round(dx));
            }
        }
    }

    // hud.css .glass: the panel colour at 80% (84% light), the kit hairline at half, a one px light line along the top
    // edge and the soft shadow. no blur, the world behind is moving and a blur pass every frame is not worth it
    private void glass(Canvas g, float gx, float gy, float gw, float gh) {
        boolean dark = theme.dark();
        float r = theme.pxf(Theme.CARD_RADIUS);
        g.shadow(gx, gy, gw, gh, r, Theme.fade(theme.color(Theme.SHADOW), 0.5f));
        g.fillRoundRect(gx, gy, gw, gh, r, Theme.fade(theme.color(Theme.PANEL), dark ? 0.80f : 0.84f));
        g.strokeRoundRect(gx, gy, gw, gh, r, Theme.fade(theme.color(Theme.HAIRLINE_STRONG), 0.5f));
        int line = dark ? 0x1AFFFFFF : 0xA6FFFFFF;
        g.fillRect(gx + r, gy, gw - 2 * r, 1, line);
    }

    private void drawTile(Canvas g, TripToast.View v, float tx, float ty) {
        float s = theme.pxf(TILE);
        float r = theme.pxf(TILE_RADIUS);
        if (v.kind() == TripToast.Kind.DONE) {
            g.fillRoundRect(tx, ty, s, s, r, theme.color(Theme.ACCENT));
            float is = theme.pxf(18);
            g.icon(Icon.CHECK, tx + (s - is) / 2f, ty + (s - is) / 2f, is, theme.color(Theme.ON_ACCENT));
            return;
        }
        g.fillRoundRect(tx, ty, s, s, r, theme.color(Theme.RAISED));
        g.strokeRoundRect(tx, ty, s, s, r, theme.color(Theme.HAIRLINE));
        float is = Math.round(theme.pxf(ITEM));
        g.itemIcon(itemFor(v), Math.round(tx + (s - is) / 2f), Math.round(ty + (s - is) / 2f), is);
    }

    private void drawText(Canvas g, TripToast.View v, float tx, float ty) {
        TextMeasurer m = theme.measurer;
        float tw = textWidth();
        float top = ty + theme.pxf(1);
        float base = baseline(top, titleStyle);
        String meta = meta(v);
        float metaW = meta == null ? 0f : m.measure(meta, metaStyle);
        float titleRoom = meta == null ? tw : tw - metaW - theme.pxf(8);
        g.text(m.ellipsize(title(v), titleStyle, titleRoom), tx, base, titleStyle, theme.color(Theme.TEXT));
        if (meta != null) {
            // baseline aligned with the title, like align-items: baseline
            g.text(meta, tx + tw - metaW, base, metaStyle, theme.color(Theme.TEXT_3));
        }
        float ly = top + theme.pxf(LINE) + theme.pxf(3);
        for (String line : bodyLines(v)) {
            g.text(line, tx, baseline(ly, bodyStyle), bodyStyle, theme.color(Theme.TEXT_2));
            ly += theme.pxf(LINE);
        }
        if (v.kind() == TripToast.Kind.COLLECTING && v.need() > 0) {
            float py = ly + theme.pxf(10);
            float ph = theme.pxf(4);
            float pr = theme.pxf(2);
            g.fillRoundRect(tx, py, tw, ph, pr, theme.dark() ? 0x1FFFFFFF : 0x14000000);
            float fill = tw * Math.min(1f, v.have() / (float) v.need());
            if (fill >= 1f) {
                g.fillRoundRect(tx, py, Math.max(fill, ph), ph, pr, theme.color(Theme.ACCENT));
            }
        }
    }

    // the line box is 17 px and the glyphs sit in the middle of it, which is what css does with line-height
    private float baseline(float lineTop, TextStyle style) {
        TextMeasurer m = theme.measurer;
        return lineTop + (theme.pxf(LINE) - (m.ascent(style) + m.descent(style))) / 2f + m.ascent(style);
    }

    private void drawButton(Canvas g, Btn b, float dx) {
        float bx = b.x + dx;
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        boolean hot = hover == b.hit;
        int ink;
        if (b.primary) {
            g.fillRoundRect(bx, b.y, b.w, b.h, r, theme.color(hot ? Theme.ACCENT_HOVER : Theme.ACCENT));
            ink = theme.color(Theme.ON_ACCENT);
        } else {
            g.fillRoundRect(bx, b.y, b.w, b.h, r, theme.color(hot ? Theme.HOVER : Theme.RAISED));
            g.strokeRoundRect(bx, b.y, b.w, b.h, r, Theme.fade(theme.color(Theme.HAIRLINE_STRONG), 0.6f));
            ink = theme.color(Theme.TEXT);
        }
        TextMeasurer m = theme.measurer;
        float lx = bx + theme.pxf(12);
        float base = b.y + (b.h - (m.ascent(btnStyle) + m.descent(btnStyle))) / 2f + m.ascent(btnStyle);
        g.text(b.label, lx, base, btnStyle, ink);
        if (!hasKey(b.key)) {
            return;
        }
        float kw = keyWidth(b.key);
        float kh = theme.pxf(KEY);
        float kx = bx + b.w - theme.pxf(6) - kw;
        float ky = b.y + (b.h - kh) / 2f;
        int chip = b.primary ? 0x38FFFFFF : theme.dark() ? 0x1AFFFFFF : 0x12000000;
        g.fillRoundRect(kx, ky, kw, kh, theme.pxf(5), chip);
        float kt = m.measure(b.key, keyStyle);
        float kb = ky + (kh - (m.ascent(keyStyle) + m.descent(keyStyle))) / 2f + m.ascent(keyStyle);
        // .key { opacity: 0.9 } on top of the button's own ink
        g.text(b.key, kx + (kw - kt) / 2f, kb, keyStyle, Theme.fade(ink, 0.9f));
    }

    // ----- words. plain and from the player's side, same voice as the chat line

    static String itemFor(TripToast.View v) {
        boolean pick = switch (v.kind()) {
            case ASK -> v.ask() == Trip.Ask.FASTER && v.pickaxe();
            case COLLECTING -> v.phase() == TripToast.Phase.PICKAXE;
            default -> false;
        };
        return pick ? "minecraft:stone_pickaxe" : "minecraft:dirt";
    }

    static String title(TripToast.View v) {
        return switch (v.kind()) {
            case ASK -> v.ask() == Trip.Ask.FASTER ? "Faster path found" : "Path needs blocks";
            case COLLECTING -> v.need() > 0 ? "Collecting blocks" : "Making a stone pickaxe";
            case DONE -> v.need() > 0 ? "Got " + blocks(v.need()) + (v.pickaxe() ? " and a pickaxe" : "") : "Got a stone pickaxe";
            default -> "";
        };
    }

    static String meta(TripToast.View v) {
        if (v.kind() == TripToast.Kind.ASK && v.ask() == Trip.Ask.FASTER && v.savedTicks() >= 20) {
            return "saves " + TripToast.duration(v.savedTicks());
        }
        if (v.kind() == TripToast.Kind.COLLECTING && v.need() > 0) {
            return v.have() + " / " + v.need();
        }
        return null;
    }

    static String body(TripToast.View v) {
        return switch (v.kind()) {
            case ASK -> v.ask() == Trip.Ask.FASTER ? fasterBody(v) : "I need " + v.blocks() + (v.blocks() == 1 ? " more block" : " more blocks") + " for this path.\nCollect some?";
            case COLLECTING -> collectingBody(v);
            case DONE -> "Carrying on with the path.";
            default -> "";
        };
    }

    private static String fasterBody(TripToast.View v) {
        if (v.blocks() > 0 && v.pickaxe()) {
            return "It needs " + blocks(v.blocks()) + " and a stone pickaxe.\nGet them first?";
        }
        if (v.pickaxe()) {
            return "It needs a stone pickaxe.\nMake one first?";
        }
        return "It needs " + blocks(v.blocks()) + ".\nGet them first?";
    }

    private static String collectingBody(TripToast.View v) {
        TripToast.Phase phase = v.phase() == null ? TripToast.Phase.BY_HAND : v.phase();
        return switch (phase) {
            case PICKAXE -> v.need() > 0 ? "Making a stone pickaxe first, then blocks." : "Then back on the path.";
            case BY_HAND -> "Mining dirt nearby, then back on the path.";
            case MINING -> "Mining nearby, then back on the path.";
        };
    }

    private static String blocks(int n) {
        return n + (n == 1 ? " block" : " blocks");
    }
}
