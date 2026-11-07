package adris.altoclef.ui.gui.hud;

// where the status panel may go: top left, MARGIN in, and out of the way of what vanilla puts up there or below it. the
// boss bars (top centre, the dragon's in the end), chat (bottom left, grows upwards), the #gamer card (right edge) and
// the trip toast (bottom right, only reachable on small windows or a big hud scale).
// everything in device px except the vanilla bits, which come in gui units. no minecraft, so it can be tested
public final class PanelPlacement {

    // BossHealthOverlay: the first bar at y 12, 19 apart, 182 wide and centred, 5 tall. it stops adding bars once the
    // next one would start below a third of the screen
    static final int BOSS_TOP = 12;
    static final int BOSS_STEP = 19;
    static final int BOSS_HALF = 91;
    static final int BOSS_TALL = 5;
    // chat's bottom line sits 40 gui units up (the hotbar and hearts are under it)
    static final int CHAT_BOTTOM = 40;

    // x, y and the room it gets, device px
    public record Room(float x, float y, float w, float h) {
    }

    private PanelPlacement() {
    }

    // bosses is how many bars are up, chatTall how tall chat may get in gui units (its height times its scale), card the
    // #gamer card and toast the trip toast, each null when not up. narrowAt is how thin the panel is allowed to get
    // beside the boss bars before it gives up and goes under them instead
    public static Room room(float winW, float winH, int guiScale, float margin, int bosses, float chatTall, float[] card,
                            float[] toast, float narrowAt) {
        float gs = Math.max(1, guiScale);
        float x = margin;
        float y = margin;
        float right = winW - margin;
        int bars = bossBars(winH / gs, bosses);
        if (bars > 0) {
            float bossLeft = (winW / gs / 2f - BOSS_HALF) * gs - margin / 2f;
            if (bossLeft - x >= narrowAt) {
                right = Math.min(right, bossLeft);
            } else {
                // too small a window to sit beside them, so under them: the last bar's bottom plus a gap
                y = Math.max(y, (BOSS_TOP + BOSS_STEP * (bars - 1) + BOSS_TALL) * gs + margin / 2f);
            }
        }
        float bottom = (winH / gs - CHAT_BOTTOM - chatTall) * gs - margin / 2f;
        if (card != null && card[1] < bottom && card[1] + card[3] > y) {
            // the card hangs on the right edge, so only its left side matters
            right = Math.min(right, card[0] - margin / 2f);
        }
        if (toast != null && toast[0] < right && toast[1] < bottom && toast[1] + toast[3] > y) {
            // stop above it or stop left of it, whichever leaves the bigger room. above nearly always wins, the toast
            // hugs the bottom
            float above = toast[1] - margin / 2f;
            float leftOf = toast[0] - margin / 2f;
            if (Math.max(0f, above - y) * (right - x) >= Math.max(0f, leftOf - x) * (bottom - y)) {
                bottom = above;
            } else {
                right = leftOf;
            }
        }
        return new Room(x, y, Math.max(0f, right - x), Math.max(0f, bottom - y));
    }

    // how many boss bars vanilla really draws for this many events
    static int bossBars(float guiH, int events) {
        int n = 0;
        for (int y = BOSS_TOP; n < events; y += BOSS_STEP) {
            n++;
            if (y + BOSS_STEP >= (int) guiH / 3) {
                break;
            }
        }
        return n;
    }
}
