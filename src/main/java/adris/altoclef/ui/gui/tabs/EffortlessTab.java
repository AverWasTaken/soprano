/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.EffortlessAccess;
import adris.altoclef.ui.gui.facade.EffortlessAccess.Row;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.PixelImage;
import adris.altoclef.ui.gui.kit.ShapeThumb;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Effortless Soprano's queue: every shape waiting to be built or broken, in the order it'll run. the details on the
// left, a little isometric picture on the right, and per row: up, down, show it in the world, merge with the one below
// (so the builder does both at once), split a merged one back up, and remove. start runs the list top to bottom
public final class EffortlessTab implements Tab {

    static final int ROW_H = 96;
    static final int THUMB_W = 128;
    static final int THUMB_H = 80;
    static final int ACT = 26;
    static final int ACT_ICON = 16;
    private static final int REFRESH_TICKS = 10;

    // what a click on a row hits
    static final int NONE = -1;
    static final int UP = 0;
    static final int DOWN = 1;
    static final int SHOW = 2;
    static final int MERGE = 3;
    static final int SPLIT = 4;
    static final int REMOVE = 5;
    private static final Icon[] ACTIONS = {Icon.CHEVRON_UP, Icon.CHEVRON_DOWN, Icon.EYE, Icon.MERGE, Icon.SPLIT, Icon.TRASH};

    private final Theme theme;
    private final EffortlessAccess access;
    private final ShellContext ctx;
    private final Button startBtn;
    private final Button clearBtn;
    private final VirtualList<Row> list;
    private final View view;
    private final Map<Object, PixelImage> thumbs = new HashMap<>();
    private String status = "";
    private int ticks;

    public EffortlessTab(Theme theme, BotFacade facade, ShellContext ctx) {
        this.theme = theme;
        this.access = facade.effortless();
        this.ctx = ctx;
        startBtn = new Button(theme, "Start", Button.Kind.PRIMARY, Icon.PLAY, this::startStop);
        clearBtn = new Button(theme, "Clear done", Button.Kind.SECONDARY, null, () -> {
            access.clearDone();
            refresh();
        });
        list = new VirtualList<>(theme, theme.px(ROW_H), this::drawRow);
        list.setEmptyText("Nothing queued. Pick a shape with the Effortless key (left alt), click its corners, and it lands here");
        list.onRowMouseDown(this::rowMouse);
        view = new View();
        refresh();
    }

    @Override
    public String id() {
        return "effortless";
    }

    @Override
    public String title() {
        return "Effortless";
    }

    @Override
    public Icon icon() {
        return Icon.SHAPE_CUBOID;
    }

    @Override
    public Widget content() {
        return view;
    }

    @Override
    public void onShown() {
        refresh();
    }

    private void startStop() {
        if (access.running()) {
            access.stop();
        } else {
            access.start();
        }
        refresh();
    }

    void refresh() {
        List<Row> rows = access.rows();
        list.setItems(rows);
        status = access.status();
        startBtn.setText(access.running() ? "Stop" : "Start");
        startBtn.setIcon(access.running() ? Icon.STOP : Icon.PLAY);
        // pictures of rows that are gone are dropped, the rest stay cached by their key
        thumbs.keySet().retainAll(rows.stream().map(Row::key).toList());
    }

    // ----- rows

    // where each action sits in a row, left to right along the bottom of the text column
    float[] zone(int x, int y, int w, int h, int which) {
        float pad = theme.pxf(Theme.PAD);
        float size = theme.pxf(ACT);
        float ax = x + pad + which * (size + theme.pxf(4));
        float ay = y + h - pad / 2f - size;
        return new float[]{ax, ay, size, size};
    }

    private boolean rowMouse(Row r, int index, float mx, float my, int button, int mods) {
        if (button != 0) {
            return true;
        }
        int top = Math.round(list.y() - list.scroller().offset(System.nanoTime())) + index * list.rowHeight();
        int hit = NONE;
        for (int i = 0; i < ACTIONS.length; i++) {
            float[] z = zone(list.x(), top, list.w() - theme.px(10), list.rowHeight(), i);
            if (mx >= z[0] && my >= z[1] && mx < z[0] + z[2] && my < z[1] + z[3]) {
                hit = i;
            }
        }
        switch (hit) {
            case UP -> {
                if (index > 0) {
                    access.move(index, index - 1);
                }
            }
            case DOWN -> {
                if (index < list.items().size() - 1) {
                    access.move(index, index + 1);
                }
            }
            case SHOW -> access.toggleShown(index);
            case MERGE -> {
                if (index < list.items().size() - 1) {
                    access.mergeWithNext(index);
                    ctx.toast("Merged with the one below, they'll build as one job", null);
                }
            }
            case SPLIT -> {
                if (r.merged()) {
                    access.unmerge(index);
                }
            }
            case REMOVE -> access.remove(index);
            default -> {
                return true;
            }
        }
        refresh();
        return true;
    }

    private void drawRow(Canvas c, Frame f, Row r, int index, int x, int y, int w, int h, boolean hovered) {
        int cw = w - ListCard.gutter(theme);
        ListCard.separator(theme, c, x, y, cw, index);
        if (hovered) {
            ListCard.fill(theme, c, x, y, cw, h, index == 0, index == list.items().size() - 1, theme.color(Theme.HOVER));
        }
        float pad = theme.pxf(Theme.PAD);
        float tw = theme.pxf(THUMB_W);
        float th = theme.pxf(THUMB_H);
        float tx = x + cw - pad - tw;
        float ty = y + (h - th) / 2f;
        c.fillRoundRect(tx, ty, tw, th, theme.pxf(Theme.ROW_RADIUS), theme.color(Theme.FIELD));
        PixelImage img = thumbs.computeIfAbsent(r.key(), k -> ShapeThumb.render(r.cells(), r.colors(), r.sizeX(), r.sizeY(),
                r.sizeZ(), Math.max(8, Math.round(tw) - 8), Math.max(8, Math.round(th) - 8)));
        c.image(img, tx + 4, ty + 4, img.width(), img.height(), 0xFFFFFFFF);

        float textW = tx - (x + pad) - pad;
        float line = y + pad + c.ascent(theme.label);
        c.text(c.ellipsize((index + 1) + ".  " + r.name(), theme.label, textW), x + pad, line, theme.label, theme.color(Theme.TEXT));
        line += c.lineHeight(theme.small) + theme.pxf(4);
        c.text(c.ellipsize(r.detail(), theme.small, textW), x + pad, line, theme.small, theme.color(Theme.TEXT_2));
        line += c.lineHeight(theme.small);
        String state = switch (r.state()) {
            case RUNNING -> "Building now";
            case DONE -> "Done";
            case FAILED -> "Failed" + (r.why() != null ? ": " + r.why() : "");
            case WAITING -> "Waiting";
        };
        int stateColor = switch (r.state()) {
            case RUNNING -> theme.color(Theme.ACCENT_TEXT);
            case DONE -> theme.color(Theme.PATH_START);
            case FAILED -> theme.color(Theme.DANGER);
            case WAITING -> theme.color(Theme.TEXT_3);
        };
        float adv = c.text(c.ellipsize(state, theme.small, textW), x + pad, line, theme.small, stateColor);
        if (r.when() != null && textW - adv > 0) {
            c.text(c.ellipsize("  ·  " + r.when(), theme.small, textW - adv), x + pad + adv, line, theme.small, theme.color(Theme.TEXT_3));
        }

        for (int i = 0; i < ACTIONS.length; i++) {
            boolean usable = switch (i) {
                case UP -> index > 0;
                case DOWN, MERGE -> index < list.items().size() - 1;
                case SPLIT -> r.merged();
                default -> true;
            };
            float[] z = zone(x, y, cw, h, i);
            boolean over = hovered && f.mouseX() >= z[0] && f.mouseY() >= z[1] && f.mouseX() < z[0] + z[2] && f.mouseY() < z[1] + z[3];
            if (over && usable) {
                c.fillRoundRect(z[0], z[1], z[2], z[3], theme.pxf(Theme.CONTROL_RADIUS), theme.color(Theme.PRESSED));
            }
            boolean on = i == SHOW && r.shown();
            int col = !usable ? theme.color(Theme.HAIRLINE_STRONG) : on ? theme.color(Theme.ACCENT_TEXT)
                    : i == REMOVE && over ? theme.color(Theme.DANGER) : theme.color(Theme.TEXT_2);
            float is = theme.pxf(ACT_ICON);
            c.icon(ACTIONS[i], z[0] + (z[2] - is) / 2f, z[1] + (z[3] - is) / 2f, is, col);
        }
    }

    // ----- the view

    private final class View extends Widget {

        private final List<Widget> kids;
        private int infoTop;

        View() {
            super(EffortlessTab.this.theme);
            kids = List.of(startBtn, clearBtn, list);
        }

        @Override
        protected void layout() {
            int pad = theme.px(Theme.PAD);
            int gap = theme.px(Theme.GAP);
            int ch = theme.px(Theme.CONTROL_H);
            int top = y + pad;
            int right = x + w - pad;
            int sw = startBtn.preferredWidth();
            startBtn.setBounds(right - sw, top, sw, ch);
            int cw = clearBtn.preferredWidth();
            clearBtn.setBounds(startBtn.x() - gap - cw, top, cw, ch);
            infoTop = top;
            int lt = top + ch + theme.px(Theme.SECTION);
            list.setBounds(x + pad, lt, w - 2 * pad + ListCard.gutter(theme), Math.max(0, y + h - lt - pad));
        }

        @Override
        public List<Widget> children() {
            return kids;
        }

        @Override
        public void tick() {
            super.tick();
            if (++ticks % REFRESH_TICKS == 0) {
                refresh();
            }
        }

        @Override
        public void draw(Canvas c, Frame f) {
            ListCard.card(theme, c, list, f.nowNanos(), list.items().size() * list.rowHeight());
            for (Widget k : kids) {
                k.draw(c, f);
            }
            int pad = theme.px(Theme.PAD);
            TextStyle s = theme.label;
            float hy = infoTop + theme.px(Theme.CONTROL_H) / 2f + (c.ascent(s) - c.descent(s)) / 2f;
            float room = Math.max(0, clearBtn.x() - theme.px(Theme.GAP) - (x + pad));
            c.text(c.ellipsize(status, s, room), x + pad, hy, s, theme.color(Theme.TEXT));
        }
    }

    // test hooks
    VirtualList<Row> list() {
        return list;
    }

    Button startButton() {
        return startBtn;
    }
}
