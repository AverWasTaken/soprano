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

import java.util.List;

// the numbers the game has been quietly keeping: distance walked, blocks mined, mobs killed, that sort of thing.
// the facade formats them, this just lays them out in two columns and refreshes on a tick so a live session moves
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
        });
    }

    private final class View extends Widget {

        View() {
            super(StatsTab.this.theme);
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
            int pad = theme.px(Theme.PAD);
            TextStyle value = theme.body.asTabular();
            float lh = c.lineHeight(theme.body);
            float gap = theme.px(2);
            float top = y + pad + theme.px(4);

            c.text("Your numbers", x + pad, top + c.ascent(theme.label), theme.label, theme.color(Theme.TEXT));
            top += theme.px(28);
            String sub = "What the game has seen you do. Distances count every kind of travel.";
            c.text(c.ellipsize(sub, theme.small, w - 2 * pad), x + pad, top + c.ascent(theme.small), theme.small, theme.color(Theme.TEXT_3));
            top += theme.px(24);

            if (stats.isEmpty()) {
                c.text("No numbers yet. Join a world and move around a bit.", x + pad,
                        top + c.ascent(theme.small), theme.small, theme.color(Theme.TEXT_2));
                return;
            }

            float valueRoom = w * 0.38f;
            float labelRoom = Math.max(theme.px(120), w - 2 * pad - valueRoom - theme.px(24));
            float rowH = lh + gap + theme.px(10);
            for (StatsAccess.Stat s : stats) {
                if (top + rowH > y + h - pad) {
                    break;
                }
                c.fillRect(x + pad, top + rowH - theme.px(1), w - 2 * pad, 1, Theme.fade(theme.color(Theme.HAIRLINE), 0.7f));
                String label = c.ellipsize(s.label(), theme.body, labelRoom);
                c.text(label, x + pad, top + theme.px(4) + c.ascent(theme.body), theme.body, theme.color(Theme.TEXT_2));
                String v = s.value();
                float vw = c.measure(v, value);
                c.text(v, x + w - pad - vw, top + theme.px(4) + c.ascent(theme.body), value, theme.color(Theme.TEXT));
                top += rowH;
            }
        }
    }
}
