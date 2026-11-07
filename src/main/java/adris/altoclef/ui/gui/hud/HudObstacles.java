package adris.altoclef.ui.gui.hud;

// things our own hud put on screen that the others have to keep off. the #gamer card says where it drew, the toast
// and the status panel read it a moment later in the same frame. the toast says where it was for the panel. begin() wipes it first, so a card that stopped drawing
// (run over, hud off, altoclef not running) is not dodged forever. client thread only
public final class HudObstacles {

    private static float[] card;
    // the corner toast draws after everything else, so this is last frame's. not wiped by begin(): the toast sets it
    // or clears it itself every frame it runs
    private static float[] toast;

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

    // device px, null for no toast on screen
    public static void toast(float[] rect) {
        toast = rect;
    }

    public static float[] toast() {
        return toast;
    }
}
