package adris.altoclef.ui.gui.hud;

// where the corner toast goes so it never sits on something the player is reading. everything in device px except the
// vanilla bits, which come in gui units and get multiplied by the gui scale here. no minecraft, so it can be tested
public final class ToastPlacement {

    // SubtitleOverlay: rows stack up from 35 gui units above the bottom, 10 apart, each box 5 tall either side of its
    // middle. so n rows reach 30 + 10n up, and two more keeps the toast's shadow off the top one
    static final int SUBTITLE_BASE = 32;
    static final int SUBTITLE_ROW = 10;
    // the hotbar is 182 wide and centred, the offhand slot or the attack indicator hang up to 29 off either end, and
    // hearts, food, armour and air stack to about 50 up. 60 leaves the absorption row some room too
    static final int HOTBAR_HALF = 91 + 29;
    static final int HOTBAR_TALL = 60;
    // ChatScreen's input box runs the whole width, 14 gui units off the bottom
    static final int CHAT_TALL = 14;

    private ToastPlacement() {
    }

    // right and bottom insets in device px. card is the #gamer card in device px, null when it isn't up
    public static float[] place(float winW, float winH, int guiScale, float toastW, float toastH, float margin,
                                int subtitleRows, boolean chatOpen, float[] card) {
        float right = margin;
        float bottom = margin;
        if (chatOpen) {
            bottom = Math.max(bottom, CHAT_TALL * guiScale + margin / 2f);
        }
        if (subtitleRows > 0) {
            bottom = Math.max(bottom, (SUBTITLE_BASE + SUBTITLE_ROW * subtitleRows) * guiScale + margin / 2f);
        }
        // the #gamer card is centred on the right edge. a tall card on a short window comes down to the toast, so the
        // toast steps left of it rather than up into it
        if (card != null && overlaps(winW - right - toastW, winH - bottom - toastH, toastW, toastH, card)) {
            right = Math.max(right, winW - card[0] + margin / 2f);
        }
        float left = winW - right - toastW;
        float hotbarRight = winW / 2f + HOTBAR_HALF * guiScale;
        if (left < hotbarRight) {
            bottom = Math.max(bottom, HOTBAR_TALL * guiScale + margin / 2f);
        }
        return new float[]{right, bottom};
    }

    static boolean overlaps(float x, float y, float w, float h, float[] r) {
        return x < r[0] + r[2] && r[0] < x + w && y < r[1] + r[3] && r[1] < y + h;
    }

    // a ChatScreen click comes in gui units, the toast is laid out in framebuffer px
    public static float toDevice(double gui, int guiScale) {
        return (float) (gui * guiScale);
    }

    // the kit's scale for the toast: KitSurface.scaleFor's gui scale rule times the hud size setting, in quarter steps so
    // the glyph atlas keeps its sizes cached
    public static float scale(float guiScaleRule, float hudScale) {
        float raw = guiScaleRule * hudScale;
        float stepped = Math.round(raw * 4f) / 4f;
        return Math.max(0.5f, Math.min(2.5f, stepped));
    }
}
