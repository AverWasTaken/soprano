package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.List;

// sections of equal tiles that scroll inside the panel. the sections get flattened into rows (a header, or up to four
// tiles) and the rows are the VirtualView's items, so wheel, thumb, page keys and the fading bar are VirtualView's.
// sizes are the mock's at design scale: 4 columns (2 when the panel is narrow), 80 tall tiles, 8 between them, a
// 14/17/8 header. tiles are real widgets (children), so Tab walks them and the grid scrolls a focused one into view.
// an optional top and bottom widget scroll along with the tiles (the back bar, the show all button)
public class TileGrid extends VirtualView<TileGrid.Row> {

    public record Section(String title, List<Tile> tiles) {
        public Section {
            title = title == null ? "" : title;
            tiles = tiles == null ? List.of() : List.copyOf(tiles);
        }
    }

    // a flattened row. a header has a title and no tiles, a tile row has tiles and no title. top and height are device
    // px in content space, fixed when the grid reflows
    public static final class Row {
        final String title;
        final List<Tile> tiles;
        int top;
        int height;

        Row(String title, List<Tile> tiles) {
            this.title = title;
            this.tiles = tiles;
        }

        public boolean header() {
            return title != null;
        }

        public String title() {
            return title;
        }

        public List<Tile> tiles() {
            return tiles;
        }

        public int top() {
            return top;
        }

        public int height() {
            return height;
        }
    }

    static final int TILE_H = 80;
    static final int GAP = 8;
    static final int HEAD_ABOVE = 14;
    static final int HEAD_LINE = 17;
    static final int HEAD_BELOW = 8;
    static final int PAD_LEFT = 16;
    static final int PAD_RIGHT = 6;
    static final int PAD_BOTTOM = 20;
    // under this much room four columns squeeze names into three lines, so it drops to two (the mock's 760 px page)
    static final int FOUR_COLUMNS_MIN = 560;

    private final TextStyle headStyle;
    private List<Section> sections = List.of();
    private final List<Tile> allTiles = new ArrayList<>();
    private final List<Widget> kids = new ArrayList<>();
    private Widget top;
    private int topH;
    private Widget bottom;
    private int bottomH;
    private int columns = -1;
    private int contentH;
    private Tile revealPending;
    private Runnable onViewMoved;
    private Popover popover;
    private Widget revealedSlot;
    private float lastOffset;

    public TileGrid(Theme theme) {
        super(theme);
        headStyle = new TextStyle(Weight.SEMIBOLD, theme.px(14));
        wheelRows = 1;
        // rows are rebuilt on every reflow; a row is the same row while it holds the same title or the same tiles
        setKeyFunction(r -> r.header() ? "h:" + r.title : r.tiles);
    }

    // ----- what goes in

    // the tiles are the caller's: hand the same Tile objects back on every rebuild and focus survives it
    public void setSections(List<Section> next) {
        List<Tile> before = new ArrayList<>(allTiles);
        sections = next == null ? List.of() : List.copyOf(next);
        allTiles.clear();
        for (Section s : sections) {
            for (Tile t : s.tiles()) {
                t.owner = this;
                allTiles.add(t);
            }
        }
        // a tile that is gone lets go of the grid and of focus, or the shell would keep sending enter to a ghost
        for (Tile t : before) {
            if (t.owner == this && !allTiles.contains(t)) {
                t.owner = null;
                if (t.isFocused()) {
                    t.setFocused(false);
                }
            }
        }
        rebuildKids();
        reflow();
    }

    // a widget that scrolls with the content above the first section, heightDesign tall in design px. null to drop it
    public void setTop(Widget w, int heightDesign) {
        if (top != w) {
            letGo(top);
        }
        top = w;
        topH = w == null ? 0 : heightDesign;
        rebuildKids();
        reflow();
    }

    // same under the last section
    public void setBottom(Widget w, int heightDesign) {
        if (bottom != w) {
            letGo(bottom);
        }
        bottom = w;
        bottomH = w == null ? 0 : heightDesign;
        rebuildKids();
        reflow();
    }

    // a dropped top or bottom widget takes its focus with it, same reason as dropped tiles
    private static void letGo(Widget old) {
        if (old == null) {
            return;
        }
        List<Widget> l = new ArrayList<>();
        old.collectFocusable(l);
        for (Widget k : l) {
            if (k.isFocused()) {
                k.setFocused(false);
            }
        }
    }

    // the popover anchored to a tile closes on this: the wheel, the thumb, page keys, a reveal, a new size
    public void setOnViewMoved(Runnable r) {
        onViewMoved = r;
    }

    public List<Section> sections() {
        return sections;
    }

    public List<Tile> tiles() {
        return allTiles;
    }

    public int columns() {
        return columns > 0 ? columns : columnsFor(w);
    }

    private void rebuildKids() {
        kids.clear();
        if (top != null) {
            kids.add(top);
        }
        kids.addAll(allTiles);
        if (bottom != null) {
            kids.add(bottom);
        }
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    // ----- geometry

    private int padRight() {
        return theme.px(PAD_RIGHT) + gutter();
    }

    private int innerW(int width) {
        return Math.max(0, width - theme.px(PAD_LEFT) - padRight());
    }

    private int columnsFor(int width) {
        return innerW(width) >= theme.px(FOUR_COLUMNS_MIN) ? 4 : 2;
    }

    private int headH() {
        return theme.px(HEAD_ABOVE) + theme.px(HEAD_LINE) + theme.px(HEAD_BELOW);
    }

    // the sections cut into rows for the current column count. a tile row carries the gap under it when another tile
    // row of the same section follows, so a header always sits 14 under the last tiles
    private void reflow() {
        columns = columnsFor(w);
        List<Row> rows = new ArrayList<>();
        int yy = topH > 0 ? theme.px(topH) : 0;
        int tileH = theme.px(TILE_H);
        int gap = theme.px(GAP);
        for (Section s : sections) {
            if (s.tiles().isEmpty()) {
                continue;
            }
            if (!s.title().isEmpty()) {
                Row head = new Row(s.title(), null);
                head.top = yy;
                head.height = headH();
                yy += head.height;
                rows.add(head);
            }
            List<Tile> ts = s.tiles();
            for (int i = 0; i < ts.size(); i += columns) {
                Row r = new Row(null, List.copyOf(ts.subList(i, Math.min(ts.size(), i + columns))));
                boolean more = i + columns < ts.size();
                r.top = yy;
                r.height = tileH + (more ? gap : 0);
                yy += r.height;
                rows.add(r);
            }
        }
        int body = yy;
        if (bottom != null) {
            body += theme.px(bottomH);
        }
        contentH = rows.isEmpty() && top == null && bottom == null ? 0 : body + theme.px(PAD_BOTTOM);
        setItems(rows);
        place();
    }

    @Override
    protected int contentHeight() {
        return contentH;
    }

    @Override
    protected int step() {
        return theme.px(TILE_H + GAP);
    }

    @Override
    protected void layout() {
        if (columnsFor(w) != columns) {
            reflow();
        }
        super.layout();
        place();
        movedIfNeeded();
    }

    // only the visible part of the grid takes hovers and clicks for its tiles
    boolean viewContains(float px, float py) {
        return contains(px, py);
    }

    // every tile and the top/bottom widgets get their on screen bounds for the current scroll. cheap enough to do
    // for all 365 settings every frame, and it means focus and hit tests never see a stale spot
    private void place() {
        float off = scroller.offset(now);
        int left = x + theme.px(PAD_LEFT);
        int inner = innerW(w);
        int gap = theme.px(GAP);
        int tileH = theme.px(TILE_H);
        int cols = Math.max(1, columns());
        float cell = (inner - (cols - 1) * gap) / (float) cols;
        if (top != null) {
            top.setBounds(left, Math.round(y - off), inner, theme.px(topH));
        }
        for (Row r : items) {
            if (r.header()) {
                continue;
            }
            int ty = Math.round(y + r.top - off);
            for (int i = 0; i < r.tiles.size(); i++) {
                int tx0 = Math.round(i * (cell + gap));
                int tx1 = Math.round(i * (cell + gap) + cell);
                r.tiles.get(i).setBounds(left + tx0, ty, tx1 - tx0, tileH);
            }
        }
        if (bottom != null) {
            int by = items.isEmpty() ? (top != null ? theme.px(topH) : 0) : last().top + last().height;
            bottom.setBounds(left, Math.round(y + by - off), inner, theme.px(bottomH));
        }
    }

    private Row last() {
        return items.get(items.size() - 1);
    }

    private Row rowOf(Tile t) {
        for (Row r : items) {
            if (!r.header() && r.tiles.contains(t)) {
                return r;
            }
        }
        return null;
    }

    // a tile took focus (tab, or code). the scroll happens on the next frame, which has a clock
    void reveal(Tile t) {
        revealPending = t;
    }

    // a different page in the same grid starts at its top, not wherever the last page was left
    public void scrollToTop() {
        scroller.snapTo(0f);
    }

    // scrolls so the tile's row is on screen, with the section header too when it is the section's first row
    public void scrollToTile(Tile t) {
        int i = items.indexOf(rowOf(t));
        if (i < 0) {
            return;
        }
        Row r = items.get(i);
        int from = i > 0 && items.get(i - 1).header() ? items.get(i - 1).top : r.top - theme.px(GAP);
        scroller.ensureVisible(Math.max(0, from), r.top + theme.px(TILE_H) + theme.px(GAP), now);
    }

    // tab onto a control in the top or bottom widget (show all, back) scrolls it into view, same as a tile
    private void revealSlots() {
        Widget hit = focusedIn(top);
        int slotTop = 0;
        int slotH = theme.px(topH);
        if (hit == null) {
            hit = focusedIn(bottom);
            slotTop = items.isEmpty() ? (top != null ? theme.px(topH) : 0) : last().top + last().height;
            slotH = theme.px(bottomH);
        }
        if (hit != null && hit != revealedSlot) {
            scroller.ensureVisible(slotTop, slotTop + slotH, now);
        }
        revealedSlot = hit;
    }

    private static Widget focusedIn(Widget slot) {
        if (slot == null || !slot.visible()) {
            return null;
        }
        List<Widget> l = new ArrayList<>();
        slot.collectFocusable(l);
        for (Widget k : l) {
            if (k.isFocused()) {
                return k;
            }
        }
        return null;
    }

    boolean pageKey(int key) {
        if (key != Keys.PAGE_DOWN && key != Keys.PAGE_UP && key != Keys.HOME && key != Keys.END) {
            return false;
        }
        boolean was = focused;
        focused = true;
        boolean used = super.keyDown(key, 0);
        focused = was;
        return used;
    }

    private void movedIfNeeded() {
        float t = scroller.target();
        if (t != lastOffset) {
            lastOffset = t;
            fireMoved();
        }
    }

    private int lastW = -1;
    private int lastH = -1;

    private void fireMoved() {
        // a popover hanging off one of our tiles would be pointing at the wrong spot now
        Popover p = popover;
        popover = null;
        if (p != null && p.isOpen()) {
            p.dismiss();
        }
        if (onViewMoved != null) {
            Safe.run("grid moved", onViewMoved);
        }
    }

    // Popover.open tells us, so a scroll or a resize closes it without the caller wiring anything
    void popoverOpened(Popover p) {
        popover = p;
    }

    // ----- drawing

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        if (w != lastW || h != lastH) {
            boolean first = lastW < 0;
            lastW = w;
            lastH = h;
            if (!first) {
                fireMoved();
            }
        }
        resize();
        if (revealPending != null) {
            Tile t = revealPending;
            revealPending = null;
            if (t.isFocused()) {
                scrollToTile(t);
            }
        }
        movedIfNeeded();
        revealSlots();
        place();
        float off = scroller.offset(now);
        int firstRow = -1;
        int lastRow = -1;
        for (int i = 0; i < items.size(); i++) {
            Row r = items.get(i);
            if (r.top + r.height < off || r.top > off + h) {
                continue;
            }
            if (firstRow < 0) {
                firstRow = i;
            }
            lastRow = i + 1;
        }
        if (firstRow >= 0) {
            stampVisible(firstRow, lastRow, now);
        }
        c.pushClip(x, y, w, h);
        if (top != null && top.visible()) {
            Safe.run("grid top", () -> top.draw(c, f));
        }
        if (firstRow >= 0) {
            for (int i = firstRow; i < lastRow; i++) {
                Row r = items.get(i);
                Canvas g = rowCanvas(c, fadeOf(r, now));
                if (r.header()) {
                    float ty = y + r.top - off + theme.px(HEAD_ABOVE);
                    String s = theme.measurer.ellipsize(r.title, headStyle, innerW(w) - theme.px(2));
                    g.text(s, x + theme.px(PAD_LEFT) + theme.px(2), baseline(g, headStyle, ty, theme.px(HEAD_LINE)),
                            headStyle, theme.color(Theme.TEXT_2));
                    continue;
                }
                for (Tile t : r.tiles) {
                    Safe.run("tile draw", () -> t.draw(g, f));
                }
            }
        }
        if (bottom != null && bottom.visible()) {
            Safe.run("grid bottom", () -> bottom.draw(c, f));
        }
        c.popClip();
        drawEmpty(c);
        drawScrollbar(c);
    }

    // ----- input. the scrollbar first (VirtualView), then whatever widget is under the pointer

    @Override
    public Widget mouseMove(float mx, float my) {
        place();
        return super.mouseMove(mx, my);
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        if (super.mouseDown(mx, my, button, mods)) {
            movedIfNeeded();
            return true;
        }
        place();
        for (int i = kids.size() - 1; i >= 0; i--) {
            Widget k = kids.get(i);
            if (k.contains(mx, my) && k.mouseDown(mx, my, button, mods)) {
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        if (super.mouseUp(mx, my, button, mods)) {
            return true;
        }
        place();
        boolean any = false;
        // every child hears the release: a tile pressed and then left still has to let go
        for (int i = kids.size() - 1; i >= 0; i--) {
            any |= kids.get(i).mouseUp(mx, my, button, mods);
        }
        return any;
    }

    @Override
    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        if (super.mouseDrag(mx, my, button, dx, dy)) {
            movedIfNeeded();
            return true;
        }
        for (int i = kids.size() - 1; i >= 0; i--) {
            if (kids.get(i).mouseDrag(mx, my, button, dx, dy)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        boolean used = super.scroll(mx, my, dy);
        movedIfNeeded();
        return used;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        boolean used = super.keyDown(key, mods);
        movedIfNeeded();
        return used;
    }

    // with tiles in it the grid is not a tab stop of its own, Tab goes straight to the first tile (which hands page
    // keys back to us). an empty grid keeps the stop so a long top or bottom widget can still be paged
    @Override
    public boolean focusable() {
        return visible && allTiles.isEmpty();
    }
}
