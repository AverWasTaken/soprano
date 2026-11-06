package adris.altoclef.ui.gui.kit;

import java.util.List;
import java.util.function.Consumer;

// the type-ahead under an id box. it lives in the shell's floating layer, so it never has the keyboard: the field keeps
// focus and hands it up, down, enter, tab and esc while the list is open. that's the whole trick, a popover would have
// stolen the caret. rows look like the dropdown's menu so the two read as one family
public final class SuggestList extends Control {

    // id goes into the box, label is what you read, icon is an item id (or a player name when face is on), null for none
    public record Entry(String id, String label, String icon, boolean face) {

        public static Entry item(String id, String label, String iconItemId) {
            return new Entry(id, label, iconItemId, false);
        }

        public static Entry player(String name) {
            return new Entry(name, name, name, true);
        }
    }

    // ranks whatever it knows for the query and hands back at most limit rows, best first
    public interface Source {
        List<Entry> find(String query, int limit);
    }

    static final int MAX_ROWS = 8;
    // enough to scroll through, not so many a one letter query builds a list of a thousand
    static final int LIMIT = 50;

    private final TextField owner;
    private final ShellContext ctx;
    private Source source;
    private final Consumer<String> onPick;
    private final Spring reveal = new Spring(0f);
    private final FadeCanvas fade = new FadeCanvas();
    private List<Entry> rows = List.of();
    private int hot = -1;
    private int first;
    private boolean open;
    private boolean revealed;
    private boolean down = true;
    private int shown;
    // where the list may go. the shell keeps it inside the panel, a list hanging off the bottom over the game world
    // looked lost. MIN/MAX until someone says, then it's the window
    private int roomTop = Integer.MIN_VALUE;
    private int roomBottom = Integer.MAX_VALUE;
    private int roomLeft = Integer.MIN_VALUE;
    private int roomRight = Integer.MAX_VALUE;
    private float lastMx = Float.NaN;
    private float lastMy = Float.NaN;
    // false right after opening: the next pointer report is only where it already was, not a move
    private boolean armed;

    // onPick gets the picked id after it went into the box (the get step's add box adds it from there)
    public SuggestList(Theme theme, TextField owner, ShellContext ctx, Source source, Consumer<String> onPick) {
        super(theme);
        this.owner = owner;
        this.ctx = ctx;
        this.source = source;
        this.onPick = onPick;
        visible = false;
        owner.setSuggest(this);
    }

    // one item box serves equip and go to block, so the list it asks changes with the step's kind
    public void setSource(Source s) {
        source = s;
        if (open) {
            close();
        }
    }

    public boolean isOpen() {
        return open;
    }

    public List<Entry> rows() {
        return rows;
    }

    public int hot() {
        return hot;
    }

    // the field's text changed under your fingers. an empty box shows nothing, and a box that already says exactly
    // the one thing there is to pick doesn't need a list telling it so
    void update(String text) {
        String q = text == null ? "" : text.trim();
        List<Entry> found = q.isEmpty() || source == null ? List.of() : safeFind(q);
        if (found.isEmpty() || (found.size() == 1 && found.get(0).id().equalsIgnoreCase(q))) {
            close();
            return;
        }
        rows = found;
        hot = -1;
        first = 0;
        if (!open) {
            open = true;
            visible = true;
            revealed = false;
            armed = false;
            // the old spot from the last time it was open would catch clicks until the next draw places it
            setBounds(0, 0, 0, 0);
            ctx.showFloating(this);
        }
    }

    private List<Entry> safeFind(String q) {
        try {
            List<Entry> out = source.find(q, LIMIT);
            return out == null ? List.of() : out;
        } catch (Throwable t) {
            Safe.report("suggest find", t);
            return List.of();
        }
    }

    public void close() {
        boolean was = open;
        open = false;
        visible = false;
        hot = -1;
        if (was) {
            ctx.hideFloating(this);
        }
    }

    // the field asks first with every key while the list is open. true means it was the list's. enter and tab with
    // nothing highlighted go back to the box, so a plain enter still does what it did before there was a list
    boolean key(int key) {
        if (!open) {
            return false;
        }
        int n = rows.size();
        switch (key) {
            case Keys.DOWN -> hot = hot < 0 ? 0 : Math.min(n - 1, hot + 1);
            // up off the top row goes back to nothing picked, like a browser's address bar
            case Keys.UP -> hot = hot < 0 ? n - 1 : hot - 1;
            case Keys.PAGE_DOWN -> hot = Math.min(n - 1, Math.max(0, hot) + MAX_ROWS);
            case Keys.PAGE_UP -> hot = Math.max(0, hot - MAX_ROWS);
            case Keys.ENTER, Keys.KP_ENTER, Keys.TAB -> {
                if (hot < 0) {
                    close();
                    return false;
                }
                pick(hot);
                return true;
            }
            case Keys.ESCAPE -> close();
            default -> {
                return false;
            }
        }
        ensureHot();
        return true;
    }

    // down on a box with text in it and no list: bring the list back with the first row lit
    boolean reopen(String text) {
        update(text);
        if (!open) {
            return false;
        }
        hot = 0;
        return true;
    }

    public void pick(int i) {
        if (i < 0 || i >= rows.size()) {
            return;
        }
        String id = rows.get(i).id();
        close();
        owner.acceptSuggestion(id);
        if (onPick != null) {
            Safe.run("suggest pick", () -> onPick.accept(id));
        }
    }

    private int rowH() {
        return theme.px(Theme.ROW_H);
    }

    private int pad() {
        return theme.px(4);
    }

    private void ensureHot() {
        int n = rows.size();
        int vis = Math.min(n, shown > 0 ? shown : MAX_ROWS);
        if (hot >= 0 && hot < first) {
            first = hot;
        } else if (hot >= first + vis) {
            first = hot - vis + 1;
        }
        first = Math.max(0, Math.min(n - vis, first));
    }

    public void setRoom(int left, int top, int right, int bottom) {
        roomLeft = left;
        roomTop = top;
        roomRight = right;
        roomBottom = bottom;
    }

    // under the box, or over it when the room runs out first (the dropdown's rule). worked out every frame from the box itself, so a box
    // that moved takes its list along instead of leaving it floating over the wrong row
    private void place(int viewW, int viewH) {
        int rowH = rowH();
        int pad = pad();
        int want = Math.min(rows.size(), MAX_ROWS);
        int gap = theme.px(4);
        int bottom = Math.min(viewH, roomBottom);
        int top = Math.max(0, roomTop);
        int below = bottom - (owner.y() + owner.h()) - gap - theme.px(8);
        int above = owner.y() - top - gap - theme.px(8);
        down = below >= want * rowH + 2 * pad || below >= above;
        int room = (down ? below : above) - 2 * pad;
        shown = Math.max(1, Math.min(want, room / rowH));
        int mh = shown * rowH + 2 * pad;
        int mw = Math.max(owner.w(), theme.px(280));
        int left = Math.max(0, roomLeft) + theme.px(8);
        int right = Math.min(viewW, roomRight) - theme.px(8);
        int mx = Math.max(left, Math.min(owner.x(), right - mw));
        int my = down ? owner.y() + owner.h() + gap : owner.y() - gap - mh;
        setBounds(mx, my, mw, mh);
        ensureHot();
    }

    // the box went away (hidden, recycled for another step, lost focus some way we didn't hear about): so do we
    private boolean ownerGone() {
        if (!owner.visible() || !owner.isFocused() || owner.w() <= 0) {
            return true;
        }
        // scrolled off without the wheel (a scrollbar drag, scroll into view): a list for a box you can't see
        return owner.y() + owner.h() <= roomTop || owner.y() >= roomBottom;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        if (!open) {
            return;
        }
        if (ownerGone()) {
            close();
            return;
        }
        place(c.width(), c.height());
        if (!revealed) {
            // the first frame of an opening; typing more letters into an open list must not replay it every key
            revealed = true;
            reveal.snap(0f);
            reveal.set(1f, now, Theme.ANIM_FAST);
        }
        float a = reveal.value(now);
        Canvas g = a >= 0.999f ? c : fade.reset(c, a, (1f - a) * theme.px(4) * (down ? -1 : 1));
        float r = theme.pxf(Theme.CARD_RADIUS);
        theme.floating(g, x, y, w, h, r);
        int rowH = rowH();
        int pad = pad();
        boolean bar = rows.size() > shown;
        int barW = bar ? theme.px(3) + theme.px(4) : 0;
        g.pushClip(x, y + 1, w, h - 2);
        for (int i = 0; i < shown && first + i < rows.size(); i++) {
            int idx = first + i;
            drawRow(g, rows.get(idx), idx == hot, y + pad + i * rowH, rowH, pad, barW);
        }
        g.popClip();
        if (bar) {
            float track = h - 2 * pad;
            float thumb = Math.max(theme.px(18), track * shown / rows.size());
            float top = y + pad + (track - thumb) * first / Math.max(1, rows.size() - shown);
            int bw = theme.px(3);
            g.fillRoundRect(x + w - pad - bw, top, bw, thumb, bw / 2f, theme.color(Theme.HAIRLINE_STRONG));
        }
    }

    private void drawRow(Canvas g, Entry e, boolean lit, float ry, int rowH, int pad, int barW) {
        if (lit) {
            g.fillRoundRect(x + pad, ry, w - 2 * pad - barW, rowH, theme.pxf(Theme.ROW_RADIUS), theme.color(Theme.ACCENT_SOFT));
        }
        int s = theme.itemIconPx();
        float left = x + pad + theme.px(10);
        float iy = ry + (rowH - s) / 2f;
        if (e.face() && e.icon() != null) {
            g.playerFace(e.icon(), left, iy, s);
        } else if (e.icon() != null && !e.icon().isEmpty()) {
            g.itemIcon(e.icon(), left, iy, s);
        } else {
            int is = theme.px(Theme.ICON);
            g.icon(Icon.GET, left + (s - is) / 2f, ry + (rowH - is) / 2f, is, theme.color(Theme.TEXT_3));
        }
        float tx = left + s + theme.px(10);
        float right = x + w - pad - barW - theme.px(10);
        // the id is what lands in the box, so it gets to stay readable; the name gives up room first
        String id = sameWords(e.label(), e.id()) ? "" : e.id();
        float idW = 0f;
        if (!id.isEmpty()) {
            float cap = (right - tx) * 0.5f;
            id = theme.measurer.ellipsize(id, theme.small, cap);
            idW = theme.measurer.measure(id, theme.small);
            g.text(id, right - idW, baseline(g, theme.small, ry, rowH), theme.small, theme.color(Theme.TEXT_3));
        }
        float room = right - tx - (idW > 0 ? idW + theme.px(12) : 0);
        String label = theme.measurer.ellipsize(e.label() == null ? e.id() : e.label(), theme.body, Math.max(0f, room));
        g.text(label, tx, baseline(g, theme.body, ry, rowH), theme.body, theme.color(Theme.TEXT));
    }

    // "Steve" over "Steve" is one word said twice; "Oak Log" over oak_log is not, the id is the part you type
    private static boolean sameWords(String label, String id) {
        return label == null || label.equals(id);
    }

    private int rowAt(float mx, float my) {
        if (!open || !contains(mx, my)) {
            return -1;
        }
        int i = (int) Math.floor((my - y - pad()) / rowH());
        int idx = first + i;
        return i < 0 || i >= shown || idx >= rows.size() ? -1 : idx;
    }

    // only a pointer that really moved lights a row. the shell replays the last position every frame, and a list that
    // pops up under a resting pointer must not have enter pick whatever happened to land there
    @Override
    public Widget mouseMove(float mx, float my) {
        hovered = open && contains(mx, my);
        boolean moved = armed && (mx != lastMx || my != lastMy);
        armed = open;
        lastMx = mx;
        lastMy = my;
        int idx = rowAt(mx, my);
        if (idx >= 0 && moved) {
            hot = idx;
        }
        return hovered ? this : null;
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!open || !contains(mx, my)) {
            return false;
        }
        int idx = rowAt(mx, my);
        if (idx >= 0 && button == 0) {
            pick(idx);
        }
        return true;
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        if (!open || !contains(mx, my)) {
            return false;
        }
        int vis = Math.max(1, shown);
        first = Math.max(0, Math.min(rows.size() - vis, first - Math.round(Math.signum(dy))));
        return true;
    }
}
