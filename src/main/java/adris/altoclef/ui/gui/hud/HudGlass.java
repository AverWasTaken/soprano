package adris.altoclef.ui.gui.hud;

import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Theme;

// hud.css .glass, shared by the corner toast and the status panel so the two read as one thing: the panel colour at 80%
// (84% light), the kit hairline at half, a one px light line along the top edge and the soft shadow. no blur, the world
// behind is moving and a blur pass every frame is not worth it
public final class HudGlass {

    private HudGlass() {
    }

    public static void draw(Canvas g, Theme theme, float x, float y, float w, float h, float r) {
        boolean dark = theme.dark();
        g.shadow(x, y, w, h, r, Theme.fade(theme.color(Theme.SHADOW), 0.5f));
        g.fillRoundRect(x, y, w, h, r, Theme.fade(theme.color(Theme.PANEL), dark ? 0.80f : 0.84f));
        g.strokeRoundRect(x, y, w, h, r, Theme.fade(theme.color(Theme.HAIRLINE_STRONG), 0.5f));
        int line = dark ? 0x1AFFFFFF : 0xA6FFFFFF;
        g.fillRect(x + r, y, w - 2 * r, 1, line);
    }
}
