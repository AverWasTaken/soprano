package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.StatsAccess;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.kit.VirtualGrid;

import java.util.List;

// the numbers the game has been quietly keeping: distance walked, blocks mined, mobs killed, that sort of thing.
// the facade formats them, this lays out compact cards and refreshes on a tick so a live session moves
public final class StatsTab implements Tab {

    private static final int REFRESH_TICKS = 20;

    private final Theme theme;
    private final BotFacade facade;
    private final View view;
    private List<StatsAccess.Stat> stats = List.of();
    private int ticks;

    public StatsTab(Theme theme, BotFacade facade, ShellContext ctx) {
        this.theme = theme;
        this.facade = facade;
        this.view = new View();
    }

    @Override
    public String id() {
        return "stats";
    }

    @Override
    public String title() {
        return "Stats";
    }

    @Override
    public Icon icon() {
        return Icon.STATS;
    }

    @Override
    public Widget content() {
        return view;
    }

    @Override
    public void onShown() {
        ticks = 0;
        refresh();
    }

    private void refresh() {
        Safe.run("stats refresh", () -> {
            List<StatsAccess.Stat> got = facade.stats().snapshot();
            stats = got == null ? List.of() : got;
            view.cards.setItems(stats);
        });
    }

    private final class View extends Widget {

        private final VirtualGrid<StatsAccess.Stat> cards;

        View() {
            super(StatsTab.this.theme);
            cards = new VirtualGrid<>(theme, theme.px(220), theme.px(96), this::drawCard);
        }

        @Override
        protected void layout() {
            int pad = theme.px(Theme.PAD);
            int top = y + pad + theme.px(56);
            cards.setBounds(x + pad - theme.px(6), top, w - 2 * pad + theme.px(12), Math.max(0, y + h - pad - top));
        }

        @Override
        public List<Widget> children() {
            return List.of(cards);
        }

        @Override
        public void tick() {
            super.tick();
            if (++ticks % REFRESH_TICKS == 0) {
                refresh();
            }
        }

        private void drawCard(Canvas c, Frame f, StatsAccess.Stat stat, int index, int cx, int cy, int cw, int ch, boolean hot) {
            int in = theme.px(6);
            theme.card(c, cx + in, cy + in, cw - 2 * in, ch - 2 * in, theme.px(12), true);
            float tx = cx + in + theme.px(14);
            float room = cw - 2 * in - theme.px(28);
            TextStyle value = theme.title.asTabular();
            c.text(c.ellipsize(stat.label(), theme.small, room), tx, cy + in + theme.px(14) + c.ascent(theme.small),
                    theme.small, theme.color(Theme.TEXT_2));
            c.text(c.ellipsize(stat.value(), value, room), tx, cy + ch - in - theme.px(16), value, theme.color(Theme.TEXT));
        }

        @Override
        public void draw(Canvas c, Frame f) {
            int pad = theme.px(Theme.PAD);
            float top = y + pad + theme.px(4);
            c.text("Your numbers", x + pad, top + c.ascent(theme.label), theme.label, theme.color(Theme.TEXT));
            top += theme.px(28);
            String sub = "What the game has seen you do. Distances count every kind of travel.";
            c.text(c.ellipsize(sub, theme.small, w - 2 * pad), x + pad, top + c.ascent(theme.small), theme.small, theme.color(Theme.TEXT_3));
            cards.draw(c, f);
            if (stats.isEmpty()) {
                c.text(c.ellipsize("No numbers yet. Join a world and move around a bit.", theme.small, w - 2 * pad), x + pad,
                        top + theme.px(24) + c.ascent(theme.small), theme.small, theme.color(Theme.TEXT_2));
            }
        }
    }
}
