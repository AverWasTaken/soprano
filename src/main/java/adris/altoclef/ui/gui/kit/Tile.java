package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.List;

// one square in a TileGrid: icon top left, value top right, name bottom left. a switch tile is lit (accent circle,
// "On") or dim, a pick tile opens a popover and shows its value with a little chevron. the caller owns the tile and
// keeps it across rebuilds (same object per setting), that is what keeps keyboard focus on it when the grid reflows.
// it never talks to settings itself: setters for what it shows, a Runnable for the click
public class Tile extends Control implements HasTooltip {

    public enum Kind { SWITCH, PICK }

    private Kind kind = Kind.SWITCH;
    private boolean on;
    private String name = "";
    private String value = "";
    private Icon icon = Icon.SETTINGS;
    private Integer swatch;
    private boolean chevron = true;
    private boolean open;
    private Runnable onClick;
    private Tooltip tooltip;
    TileGrid owner;

    private final TextStyle nameStyle;
    private final Anim hoverA = new Anim(0f);
    private final Anim litA = new Anim(0f);
    private boolean held;
    private boolean litSnapped;
    private String wrapFor;
    private float wrapW = -1f;
    private List<String> nameLines = List.of();

    public Tile(Theme theme) {
        super(theme);
        nameStyle = new TextStyle(Weight.MEDIUM, theme.px(13));
    }

    // ----- what it shows. all return this so a caller can build one in a line

    // a switch: lit and "On", or dim and "Off". the click handler does the flipping and calls this again
    public Tile setSwitch(boolean value) {
        kind = Kind.SWITCH;
        on = value;
        return this;
    }

    // a tile that opens something. value is the short text in the corner ("12", "Smart", "Red")
    public Tile setPick(String shown) {
        kind = Kind.PICK;
        value = shown == null ? "" : shown;
        return this;
    }

    public Tile setName(String n) {
        name = n == null ? "" : n;
        return this;
    }

    public Tile setIcon(Icon i) {
        icon = i;
        return this;
    }

    // a colour tile draws a dot of the colour in the grey circle instead of a glyph. rgb, no alpha. null for a glyph
    public Tile setSwatch(Integer rgb) {
        swatch = rgb;
        return this;
    }

    // pick tiles get the chevron by default, it is the hint that a click opens something
    public Tile setChevron(boolean c) {
        chevron = c;
        return this;
    }

    public Tile setOnClick(Runnable r) {
        onClick = r;
        return this;
    }

    public Tile setTooltip(Tooltip t) {
        tooltip = t;
        return this;
    }

    // a popover hangs off this tile: it keeps the hover fill so you can see what it belongs to
    public void setOpen(boolean o) {
        open = o;
    }

    public Kind kind() {
        return kind;
    }

    public boolean on() {
        return on;
    }

    public boolean open() {
        return open;
    }

    public String name() {
        return name;
    }

    // what the corner says, the words a player reads: On/Off for a switch
    public String valueText() {
        return kind == Kind.SWITCH ? (on ? "On" : "Off") : value;
    }

    @Override
    public Tooltip tooltip() {
        return tooltip;
    }

    // ----- input

    // the grid clips its tiles: half a tile poking out under the toolbar must not take hovers or clicks there
    @Override
    public boolean contains(float px, float py) {
        return super.contains(px, py) && (owner == null || owner.viewContains(px, py));
    }

    @Override
    public boolean focusable() {
        return visible;
    }

    @Override
    public void setFocused(boolean f) {
        super.setFocused(f);
        if (f && owner != null) {
            owner.reveal(this);
        }
    }

    public void click() {
        if (onClick != null) {
            Safe.run("tile click", onClick);
        }
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        held = true;
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        if (!held) {
            return false;
        }
        held = false;
        if (button == 0 && contains(mx, my)) {
            click();
        }
        return true;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (!focused) {
            return false;
        }
        if (Keys.isEnter(key) || key == Keys.SPACE) {
            click();
            return true;
        }
        // page keys belong to the grid; a focused tile should not make them dead
        return owner != null && owner.pageKey(key);
    }

    // ----- drawing

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        boolean lit = kind == Kind.SWITCH && on;
        // the first frame lands where it is, a tile scrolled into view must not fade its light in
        if (!litSnapped) {
            litSnapped = true;
            litA.snap(lit ? 1f : 0f);
        }
        litA.set(lit ? 1f : 0f, now, Theme.ANIM_FAST);
        hoverTo(hoverA, hovered || open);
        float lv = litA.value(now);
        float hv = hoverA.value(now);
        float r = theme.pxf(Theme.CARD_RADIUS);

        int fill = Theme.lerp(theme.color(Theme.SURFACE), theme.color(Theme.HOVER), hv);
        if (held) {
            fill = theme.color(Theme.PRESSED);
        }
        c.fillRoundRect(x, y, w, h, r, fill);
        if (focused) {
            // the mock's 2 px accent ring sits on the edge instead of the hairline
            c.strokeRoundRect(x - 1, y - 1, w + 2, h + 2, r + 1, theme.color(Theme.ACCENT));
            c.strokeRoundRect(x, y, w, h, r, theme.color(Theme.ACCENT));
        } else {
            c.strokeRoundRect(x, y, w, h, r, theme.color(Theme.HAIRLINE));
        }

        int padX = theme.px(12);
        int padY = theme.px(10);
        int circle = theme.px(26);
        float cx = x + padX;
        float cy = y + padY;
        drawIcon(c, cx, cy, circle, lv);

        // the corner value, vertically centred on the circle, right aligned with the chevron past it
        TextStyle vs = theme.smallLabel;
        float right = x + w - padX;
        float base = baseline(c, vs, cy, circle);
        int valueCol;
        if (kind == Kind.PICK) {
            valueCol = theme.color(Theme.TEXT);
        } else {
            valueCol = Theme.lerp(theme.color(Theme.TEXT_3), theme.color(Theme.ACCENT_TEXT), lv);
        }
        if (kind == Kind.PICK && chevron) {
            int cs = theme.px(12);
            // margin-right -3 in the mock: the glyph's own padding would otherwise push it in from the edge
            float chx = right + theme.px(3) - cs;
            c.icon(Icon.CHEVRON_DOWN, chx, cy + (circle - cs) / 2f, cs, theme.color(Theme.TEXT_3));
            right = chx - theme.px(5);
        }
        float room = right - (cx + circle + theme.px(8));
        String shown = theme.measurer.ellipsize(valueText(), vs, Math.max(0f, room));
        if (!shown.isEmpty()) {
            float tw = c.measure(shown, vs);
            c.text(shown, right - tw, base, vs, valueCol);
        }

        // the name, glued to the bottom so the last lines line up across a row
        int lh = theme.px(15);
        List<String> lines = nameLines(w - 2f * padX);
        int nameCol = kind == Kind.SWITCH
                ? Theme.lerp(theme.color(Theme.TEXT_2), theme.color(Theme.TEXT), lv)
                : theme.color(Theme.TEXT);
        float top = y + h - padY - lines.size() * lh;
        for (int i = 0; i < lines.size(); i++) {
            c.text(lines.get(i), x + padX, baseline(c, nameStyle, top + i * lh, lh), nameStyle, nameCol);
        }
    }

    private void drawIcon(Canvas c, float cx, float cy, int size, float lv) {
        float rr = size / 2f;
        if (swatch != null) {
            c.fillRoundRect(cx, cy, size, size, rr, theme.color(Theme.RAISED));
            int d = theme.px(14);
            float dx = cx + (size - d) / 2f;
            float dy = cy + (size - d) / 2f;
            c.fillRoundRect(dx, dy, d, d, d / 2f, 0xFF000000 | swatch);
            // a hairline so white on a white card and black on a black one still read as a dot
            c.strokeRoundRect(dx, dy, d, d, d / 2f, Theme.fade(theme.color(Theme.TEXT), 0.3f));
            return;
        }
        int bg = Theme.lerp(theme.color(Theme.RAISED), theme.color(Theme.ACCENT), lv);
        c.fillRoundRect(cx, cy, size, size, rr, bg);
        if (icon == null) {
            return;
        }
        int glyph;
        if (kind == Kind.PICK) {
            glyph = theme.color(Theme.TEXT);
        } else {
            glyph = Theme.lerp(theme.color(Theme.TEXT_3), theme.color(Theme.ON_ACCENT), lv);
        }
        int is = theme.px(Theme.ICON);
        c.icon(icon, cx + (size - is) / 2f, cy + (size - is) / 2f, is, glyph);
    }

    // two lines at most, the second one ellipsized with whatever did not fit. cached per name and width, a grid
    // redraws every tile every frame and wrapping is a pile of measure calls
    List<String> nameLines(float room) {
        if (name.equals(wrapFor) && room == wrapW) {
            return nameLines;
        }
        wrapFor = name;
        wrapW = room;
        if (name.isEmpty() || room <= 0f) {
            nameLines = List.of();
            return nameLines;
        }
        List<String> all = TextWrap.words(theme.measurer, name, nameStyle, room);
        if (all.size() <= 2) {
            nameLines = all;
            return nameLines;
        }
        List<String> two = new ArrayList<>(all.subList(0, 1));
        two.add(theme.measurer.ellipsize(String.join(" ", all.subList(1, all.size())), nameStyle, room));
        nameLines = two;
        return nameLines;
    }
}
