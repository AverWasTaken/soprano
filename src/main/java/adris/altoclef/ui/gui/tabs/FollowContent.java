package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.facade.WorldAccess.EntityEntry;
import adris.altoclef.ui.gui.facade.WorldAccess.PlayerEntry;
import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.EntityGroups;
import adris.altoclef.ui.gui.model.EntityGroups.EntityRow;
import adris.altoclef.ui.gui.model.EntityGroups.Group;
import adris.altoclef.ui.gui.model.EntityGroups.GroupRow;
import adris.altoclef.ui.gui.model.EntityGroups.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

// everything the follow tab shows: the strip, the quick actions and the two lists. the lists are rebuilt from the
// facade every 10 ticks, keyed by name and type so expansion, scroll and hover survive the refresh
final class FollowContent extends Widget {

    private static final int ROW_H = 40;
    private static final int REFRESH_TICKS = 10;

    private final BotFacade facade;
    private final ShellContext ctx;
    private final Button stopBtn;
    private final Button allPlayersBtn;
    private final Button allEntitiesBtn;
    private final VirtualList<PlayerEntry> playerList;
    private final VirtualList<Row> entityList;
    private final Set<String> expanded = new HashSet<>();
    private final Set<String> closed = new HashSet<>();
    private final Set<String> memberOnly = new HashSet<>();
    private final Map<String, Anim> chevrons = new HashMap<>();
    private String pressedType;
    private int laidPlayerRows;

    private List<PlayerEntry> rawPlayers = List.of();
    private List<EntityEntry> rawEntities = List.of();
    private List<PlayerEntry> shownPlayers = List.of();
    private int shownEntityCount;
    private String following;
    private String query = "";
    private int ticks;
    private long lastNow;

    private boolean stripOn;
    private int stripX;
    private int stripY;
    private int stripW;
    private int stripH;
    private int pHdrX;
    private int pHdrY;
    private int pHdrW;
    private int eHdrX;
    private int eHdrY;
    private int eHdrW;

    FollowContent(Theme theme, BotFacade facade, ShellContext ctx) {
        super(theme);
        this.facade = facade;
        this.ctx = ctx;
        stopBtn = new Button(theme, "Stop", Button.Kind.GHOST, null, () -> act(() -> facade.world().stopFollowing()));
        allPlayersBtn = new Button(theme, "Follow all players", Button.Kind.SECONDARY, null, () -> act(() -> facade.world().followAllPlayers()));
        allEntitiesBtn = new Button(theme, "Follow all entities", Button.Kind.SECONDARY, null, () -> act(() -> facade.world().followAllEntities()));

        playerList = new VirtualList<>(theme, theme.px(ROW_H), this::drawPlayer);
        playerList.setKeyFunction(PlayerEntry::name);
        // not selectable: the selection is an index, and both lists re-sort under it every refresh
        playerList.onClick((p, i) -> {
            if (!p.self()) {
                act(() -> facade.world().followPlayer(p.name()));
            }
        });

        entityList = new VirtualList<>(theme, theme.px(ROW_H), this::drawEntityRow);
        entityList.setKeyFunction(Row::key);
        // the "Follow all" press is ours, the release decides (same button, same row, whatever the list did meanwhile)
        entityList.onRowMouseDown((row, i, mx, my, button, mods) -> {
            if (button == 0 && row instanceof GroupRow gr && !gr.group().memberOnly() && inButton(mx, my, i)) {
                pressedType = gr.group().typeId();
                return true;
            }
            return false;
        });
        entityList.onClick((row, i) -> {
            if (row instanceof GroupRow gr) {
                toggle(gr.group().typeId());
            } else if (row instanceof EntityRow er) {
                act(() -> facade.world().followEntity(er.entry().id()));
            }
        });
        refresh();
    }

    VirtualList<PlayerEntry> playerList() {
        return playerList;
    }

    VirtualList<Row> entityList() {
        return entityList;
    }

    Button stopButton() {
        return stopBtn;
    }

    Button allPlayersButton() {
        return allPlayersBtn;
    }

    Button allEntitiesButton() {
        return allEntitiesBtn;
    }

    boolean stripVisible() {
        return stripOn;
    }

    boolean expanded(String typeId) {
        return open(typeId, memberOnly.contains(typeId));
    }

    // a group is open when the player opened it, or a search found a mob inside it by name and they have not closed it
    private boolean open(String typeId, boolean viaSearch) {
        return expanded.contains(typeId) || (viaSearch && !closed.contains(typeId));
    }

    void setQuery(String q) {
        query = q == null ? "" : q;
        // a group closed mid search stays closed while they keep typing
        if (query.isEmpty()) {
            closed.clear();
        }
        rebuild();
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        String type = button == 0 ? pressedType : null;
        if (button == 0) {
            pressedType = null;
        }
        // always forward: a press that never gets its release must not leave a button held
        boolean kids = super.mouseUp(mx, my, button, mods);
        if (type != null) {
            int i = entityList.indexAt(mx, my);
            if (i >= 0 && entityList.items().get(i) instanceof GroupRow gr && gr.group().typeId().equals(type) && inButton(mx, my, i)) {
                act(() -> facade.world().followType(type));
            }
            return true;
        }
        return kids;
    }

    void shown() {
        ticks = 0;
        pressedType = null;
        refresh();
    }

    // ----- data

    void refresh() {
        WorldAccess world = facade.world();
        String f = null;
        try {
            f = world.following();
            rawPlayers = new ArrayList<>(world.players());
            rawEntities = new ArrayList<>(world.entities());
        } catch (Throwable t) {
            Safe.report("follow refresh", t);
        }
        boolean stripChanged = (f != null) != (following != null);
        following = f;
        rebuild();
        if (stripChanged) {
            setBounds(x, y, w(), h());
        }
    }

    private void rebuild() {
        shownPlayers = EntityGroups.players(rawPlayers, query);
        if (!shownPlayers.equals(playerList.items())) {
            playerList.setItems(shownPlayers);
        }
        List<Group> groups = EntityGroups.group(rawEntities, query);
        int n = 0;
        for (Group g : groups) {
            n += g.count();
        }
        shownEntityCount = n;
        memberOnly.clear();
        Set<String> opened = new HashSet<>();
        for (Group g : groups) {
            if (g.memberOnly()) {
                memberOnly.add(g.typeId());
            }
            if (open(g.typeId(), g.memberOnly())) {
                opened.add(g.typeId());
            }
        }
        List<Row> rows = EntityGroups.rows(groups, opened);
        if (!rows.equals(entityList.items())) {
            entityList.setItems(rows);
        }
        // the stacked layout sizes the players list by how many players there are
        if (Math.min(4, shownPlayers.size()) != laidPlayerRows) {
            setBounds(x, y, w, h);
        }
    }

    private void toggle(String typeId) {
        if (expanded(typeId)) {
            expanded.remove(typeId);
            closed.add(typeId);
        } else {
            expanded.add(typeId);
            closed.remove(typeId);
        }
        rebuild();
    }

    // the chevron follows whatever open means right now, so a search opening a group animates too
    private float chevronValue(Group g, long now) {
        boolean open = open(g.typeId(), g.memberOnly());
        Anim a = chevrons.computeIfAbsent(g.typeId(), k -> new Anim(open ? 1f : 0f));
        a.set(open ? 1f : 0f, now, Theme.ANIM_FAST);
        return a.value(now);
    }

    private void act(Supplier<Result> action) {
        Result r;
        try {
            r = action.get();
        } catch (Throwable t) {
            Safe.report("follow action", t);
            r = Result.fail("That did not work. Try the chat command instead.");
        }
        if (r != null) {
            Result shown = r;
            Safe.run("follow toast", () -> ctx.toast(shown));
        }
        refresh();
    }

    @Override
    public void tick() {
        if (++ticks % REFRESH_TICKS == 0) {
            refresh();
        }
        super.tick();
    }

    // ----- layout

    @Override
    protected void layout() {
        int pad = theme.px(Theme.PAD);
        int gap = theme.px(Theme.GAP);
        int cx = x + pad;
        int cw = Math.max(0, w - 2 * pad);
        int cy = y + pad;

        stripOn = following != null;
        stopBtn.setVisible(stripOn);
        if (stripOn) {
            stripX = cx;
            stripY = cy;
            stripW = cw;
            stripH = theme.px(44);
            int bh = theme.px(28);
            int bw = stopBtn.preferredWidth();
            stopBtn.setBounds(cx + cw - theme.px(8) - bw, cy + (stripH - bh) / 2, bw, bh);
            cy += stripH + gap;
        }

        laidPlayerRows = Math.min(4, shownPlayers.size());

        int ch = theme.px(Theme.CONTROL_H);
        int b1 = allPlayersBtn.preferredWidth();
        int b2 = allEntitiesBtn.preferredWidth();
        if (b1 + gap + b2 > cw) {
            // a tiny panel: the labels ellipsize before the buttons fall off the edge
            b1 = Math.max(0, (cw - gap) / 2);
            b2 = Math.max(0, cw - gap - b1);
        }
        allPlayersBtn.setBounds(cx, cy, b1, ch);
        allEntitiesBtn.setBounds(cx + b1 + gap, cy, b2, ch);
        cy += ch + theme.px(20);

        int bottom = y + h - pad;
        int hdr = theme.px(28);
        int sectionGap = theme.px(Theme.PAD);
        if (w >= theme.px(900)) {
            int colGap = theme.px(24);
            int pw = Math.round((cw - colGap) * 0.42f);
            int ew = cw - colGap - pw;
            place(cx, cy, pw, bottom - cy, hdr, true);
            place(cx + pw + colGap, cy, ew, bottom - cy, hdr, false);
        } else {
            int avail = Math.max(0, bottom - cy - sectionGap);
            // whole rows only, a sliver of a third row looks like a bug
            int rowH = theme.px(ROW_H);
            int fit = (int) ((avail * 0.5f - hdr) / rowH);
            int rows = Math.max(2, Math.min(Math.min(4, shownPlayers.size()), fit));
            int ph = hdr + rows * rowH;
            place(cx, cy, cw, ph, hdr, true);
            place(cx, cy + ph + sectionGap, cw, bottom - (cy + ph + sectionGap), hdr, false);
        }
    }

    private void place(int sx, int sy, int sw, int sh, int hdr, boolean players) {
        VirtualList<?> list = players ? playerList : entityList;
        list.setBounds(sx, sy + hdr, sw, Math.max(0, sh - hdr));
        if (players) {
            pHdrX = sx;
            pHdrY = sy;
            pHdrW = sw;
        } else {
            eHdrX = sx;
            eHdrY = sy;
            eHdrW = sw;
        }
    }

    @Override
    public List<Widget> children() {
        return List.of(stopBtn, allPlayersBtn, allEntitiesBtn, playerList, entityList);
    }

    // ----- drawing

    private static float base(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        lastNow = f.nowNanos();
        if (stripOn) {
            drawStrip(c);
        }
        allPlayersBtn.draw(c, f);
        allEntitiesBtn.draw(c, f);
        drawHeader(c, "Players", EntityGroups.nearbyPlayers(shownPlayers), pHdrX, pHdrY, pHdrW);
        drawHeader(c, "Entities", shownEntityCount, eHdrX, eHdrY, eHdrW);
        playerList.draw(c, f);
        entityList.draw(c, f);
        if (playerList.items().isEmpty()) {
            drawEmpty(c, playerList, query.isEmpty() ? "No players nearby" : "No players match",
                    query.isEmpty() ? "Anyone on the server shows up here." : "Try a different name.");
        }
        if (entityList.items().isEmpty()) {
            drawEmpty(c, entityList, query.isEmpty() ? "Nothing nearby" : "No entities match",
                    query.isEmpty() ? "Walk somewhere livelier and it fills in." : "Try a mob name, like zombie.");
        }
        if (stripOn) {
            stopBtn.draw(c, f);
        }
    }

    private void drawStrip(Canvas c) {
        c.fillRoundRect(stripX, stripY, stripW, stripH, theme.pxf(Theme.CONTROL_RADIUS), Theme.RAISED);
        float is = theme.px(14);
        c.icon(Icon.DOT, stripX + theme.px(12), stripY + (stripH - is) / 2f, is, Theme.ACCENT);
        float tx = stripX + theme.px(12) + is + theme.px(8);
        float by = base(c, theme.label, stripY, stripH);
        tx += c.text("Following ", tx, by, theme.label, Theme.TEXT_2);
        float room = stopBtn.x() - theme.px(12) - tx;
        c.text(c.ellipsize(following, theme.label, Math.max(0, room)), tx, by, theme.label, Theme.TEXT);
    }

    private void drawHeader(Canvas c, String title, int count, int hx, int hy, int hw) {
        int hdr = theme.px(28);
        float by = base(c, theme.label, hy, hdr - theme.px(4));
        float adv = c.text(title, hx, by, theme.label, Theme.TEXT);
        c.text(" · " + count + " nearby", hx + adv, by, theme.label, Theme.TEXT_2);
        c.fillRect(hx, hy + hdr - 1, hw, 1, Theme.HAIRLINE);
    }

    private void drawEmpty(Canvas c, VirtualList<?> list, String line, String hint) {
        if (list.h() < theme.px(40)) {
            return;
        }
        float cy = list.y() + Math.min(list.h() / 2f, theme.px(60));
        float w1 = c.measure(line, theme.body);
        c.text(line, list.x() + (list.w() - w1) / 2f, cy - theme.px(2), theme.body, Theme.TEXT_2);
        float w2 = c.measure(hint, theme.small);
        c.text(hint, list.x() + (list.w() - w2) / 2f, cy + theme.px(18), theme.small, Theme.TEXT_3);
    }

    // the hover button lives in a fixed slot at the right edge so the distance next to it never jumps
    private int buttonW() {
        // sized for the longer label so "Follow" and "Follow all" share one slot
        return (int) Math.ceil(theme.measurer.measure("Follow all", theme.label)) + theme.px(20);
    }

    private int buttonH() {
        return theme.px(26);
    }

    private int rowW(VirtualList<?> list) {
        return list.w() - theme.px(10);
    }

    // same rectangle drawGhost paints, for the row at this index of the entity list
    private boolean inButton(float mx, float my, int index) {
        float right = entityList.x() + rowW(entityList) - theme.px(8);
        float rowTop = entityList.y() + index * (float) entityList.rowHeight() - entityList.scroller().offset(lastNow);
        float top = rowTop + (entityList.rowHeight() - buttonH()) / 2f;
        return mx >= right - buttonW() && mx < right && my >= top && my < top + buttonH();
    }

    private void drawGhost(Canvas c, Frame f, String label, int rx, int ry, int rw, int rh) {
        float bx = rx + rw - theme.px(8) - buttonW();
        float by = ry + (rh - buttonH()) / 2f;
        boolean over = f.mouseX() >= bx && f.mouseX() < bx + buttonW() && f.mouseY() >= by && f.mouseY() < by + buttonH();
        if (over) {
            c.fillRoundRect(bx, by, buttonW(), buttonH(), theme.pxf(Theme.CONTROL_RADIUS), Theme.PRESSED);
        }
        float tw = c.measure(label, theme.label);
        c.text(label, bx + (buttonW() - tw) / 2f, base(c, theme.label, by, buttonH()), theme.label, over ? Theme.TEXT : Theme.TEXT_2);
    }

    private float distanceRight(int rx, int rw) {
        return rx + rw - theme.px(8) - buttonW() - theme.px(8);
    }

    private void dot(Canvas c, int rx, int ry, int rh) {
        float s = theme.px(14);
        c.icon(Icon.DOT, rx + theme.px(2), ry + (rh - s) / 2f, s, Theme.ACCENT);
    }

    private void rowBg(Canvas c, int rx, int ry, int rw, int rh) {
        c.fillRoundRect(rx, ry + theme.px(2), rw, rh - theme.px(4), theme.pxf(Theme.ROW_RADIUS), Theme.HOVER);
    }

    private void pill(Canvas c, String text, float px, int ry, int rh, int fill, int col) {
        float tw = c.measure(text, theme.smallLabel.asTabular());
        float ph = theme.px(18);
        c.fillRoundRect(px, ry + (rh - ph) / 2f, tw + theme.px(12), ph, ph / 2f, fill);
        c.text(text, px + theme.px(6), base(c, theme.smallLabel, ry, rh), theme.smallLabel.asTabular(), col);
    }

    private float pillW(Canvas c, String text) {
        return c.measure(text, theme.smallLabel.asTabular()) + theme.px(12);
    }

    private void drawPlayer(Canvas c, Frame f, PlayerEntry p, int idx, int rx, int ry, int rw, int rh, boolean hot) {
        boolean near = p.distance() >= 0;
        if (hot && !p.self()) {
            rowBg(c, rx, ry, rw, rh);
        }
        if (EntityGroups.followsPlayer(following, p.name())) {
            dot(c, rx, ry, rh);
        }
        float face = theme.px(24);
        float fx = rx + theme.px(20);
        float fy = ry + (rh - face) / 2f;
        c.playerFace(p.name(), fx, fy, face);
        if (!near && !p.self()) {
            c.fillRoundRect(fx, fy, face, face, face / 6f, Theme.alpha(Theme.PANEL, 0.6f));
        }
        float tx = fx + face + theme.px(10);
        float right = distanceRight(rx, rw);
        float limit = right;
        if (!p.self()) {
            String d = near ? EntityGroups.meters(p.distance()) : "not nearby";
            TextStyle ds = near ? theme.body.asTabular() : theme.small;
            float dw = c.measure(d, ds);
            c.text(d, right - dw, base(c, ds, ry, rh), ds, near ? Theme.TEXT_2 : Theme.TEXT_3);
            limit = right - dw - theme.px(12);
        }
        int nameCol = near || p.self() ? Theme.TEXT : Theme.TEXT_3;
        float pillRoom = p.self() ? pillW(c, "you") + theme.px(8) : 0f;
        String name = c.ellipsize(p.name(), theme.body, Math.max(0, limit - tx - pillRoom));
        c.text(name, tx, base(c, theme.body, ry, rh), theme.body, nameCol);
        if (p.self()) {
            pill(c, "you", tx + c.measure(name, theme.body) + theme.px(8), ry, rh, Theme.RAISED, Theme.TEXT_2);
        }
        if (over(f, rx, ry, rw, rh) && !p.self() && near) {
            drawGhost(c, f, "Follow", rx, ry, rw, rh);
        }
    }

    private static boolean over(Frame f, int rx, int ry, int rw, int rh) {
        return f.mouseX() >= rx && f.mouseX() < rx + rw && f.mouseY() >= ry && f.mouseY() < ry + rh;
    }

    private void drawEntityRow(Canvas c, Frame f, Row row, int idx, int rx, int ry, int rw, int rh, boolean hot) {
        if (hot) {
            rowBg(c, rx, ry, rw, rh);
        }
        if (row instanceof GroupRow gr) {
            drawGroup(c, f, gr.group(), rx, ry, rw, rh, hot);
        } else if (row instanceof EntityRow er) {
            drawMember(c, f, er.entry(), rx, ry, rw, rh, hot);
        }
    }

    private void drawGroup(Canvas c, Frame f, Group g, int rx, int ry, int rw, int rh, boolean hot) {
        if (EntityGroups.followsGroup(following, g) || EntityGroups.followsOneOf(following, g)) {
            dot(c, rx, ry, rh);
        }
        float cs = theme.px(16);
        float cx = rx + theme.px(18);
        float cy = ry + (rh - cs) / 2f;
        float open = chevronValue(g, f.nowNanos());
        c.icon(Icon.CHEVRON_RIGHT, cx, cy, cs, Theme.fade(Theme.TEXT_2, 1f - open));
        c.icon(Icon.CHEVRON_DOWN, cx, cy, cs, Theme.fade(Theme.TEXT_2, open));

        float tile = theme.px(24);
        float tx = rx + theme.px(42);
        float ty = ry + (rh - tile) / 2f;
        c.fillRoundRect(tx, ty, tile, tile, theme.pxf(Theme.ROW_RADIUS), Theme.RAISED);
        if (g.iconItemId() != null) {
            float is = theme.itemIconPx();
            c.itemIcon(g.iconItemId(), tx + (tile - is) / 2f, ty + (tile - is) / 2f, is);
        } else {
            float is = theme.px(16);
            c.icon(Icon.ENTITY, tx + (tile - is) / 2f, ty + (tile - is) / 2f, is, Theme.TEXT_2);
        }

        float nx = tx + tile + theme.px(10);
        String d = EntityGroups.meters(g.nearest());
        float dw = c.measure(d, theme.body.asTabular());
        float right = distanceRight(rx, rw);
        c.text(d, right - dw, base(c, theme.body, ry, rh), theme.body.asTabular(), Theme.TEXT_2);
        String count = "x" + g.count();
        float pw = pillW(c, count);
        float room = right - dw - theme.px(12) - nx - pw - theme.px(8);
        String name = c.ellipsize(g.typeName(), theme.label, Math.max(0, room));
        c.text(name, nx, base(c, theme.label, ry, rh), theme.label, Theme.TEXT);
        pill(c, count, nx + c.measure(name, theme.label) + theme.px(8), ry, rh, hot ? Theme.PANEL : Theme.RAISED, Theme.TEXT_2);
        // a group that is only here for one named member would follow the whole type, which is not what the search said
        if (over(f, rx, ry, rw, rh) && !g.memberOnly()) {
            drawGhost(c, f, "Follow all", rx, ry, rw, rh);
        }
    }

    private void drawMember(Canvas c, Frame f, EntityEntry e, int rx, int ry, int rw, int rh, boolean hot) {
        c.fillRect(rx + theme.px(26), ry, 1, rh, Theme.HAIRLINE);
        if (EntityGroups.followsEntity(following, e)) {
            dot(c, rx, ry, rh);
        }
        // one step in from the group's name so the tree reads without squinting at the guide line
        float nx = rx + theme.px(42) + theme.px(24) + theme.px(10) + theme.px(16);
        float right = distanceRight(rx, rw);
        String d = EntityGroups.meters(e.distance());
        float dw = c.measure(d, theme.body.asTabular());
        c.text(d, right - dw, base(c, theme.body, ry, rh), theme.body.asTabular(), Theme.TEXT_2);
        float room = right - dw - theme.px(12) - nx;
        String label = EntityGroups.label(e);
        boolean named = EntityGroups.named(e);
        float typeW = named ? c.measure(e.typeName(), theme.small) + theme.px(8) : 0f;
        String shown = c.ellipsize(label, theme.body, Math.max(0, room - typeW));
        c.text(shown, nx, base(c, theme.body, ry, rh), theme.body, Theme.TEXT);
        if (named) {
            float tx = nx + c.measure(shown, theme.body) + theme.px(8);
            c.text(e.typeName(), tx, base(c, theme.small, ry, rh), theme.small, Theme.TEXT_2);
        }
        if (over(f, rx, ry, rw, rh)) {
            drawGhost(c, f, "Follow", rx, ry, rw, rh);
        }
    }
}
