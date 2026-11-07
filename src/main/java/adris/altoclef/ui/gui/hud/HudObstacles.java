package adris.altoclef.ui.gui.hud;

// things our own hud put on screen this frame that the corner toast has to keep off. the #gamer card says where it
// drew, the toast reads it a moment later in the same frame. begin() wipes it first, so a card that stopped drawing
// (run over, hud off, altoclef not running) is not dodged forever. client thread only
public final class HudObstacles {

    private static float[] card;

    private HudObstacles() {
    }

    public static void begin() {
        card = null;
    }

    // device px
    public static void card(float x, float y, float w, float h) {
        card = new float[]{x, y, w, h};
    }

    public static float[] card() {
        return card;
    }
}
