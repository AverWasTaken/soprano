package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.TextStyle;

// the glyph atlases the hud's kit bits share (the corner toast, the status panel). built once, on a scheduled task and
// never inside the hud extract (rasterising inter is the slow part), and a failure is kept: McAtlases.get() would try
// again on every call. client thread only, which is where mc.schedule runs things
final class HudAtlases {

    private static boolean built;
    private static McAtlases atlases;

    private HudAtlases() {
    }

    // only from a scheduled task. null or not ok means draw with vanilla's font and plain fills
    static McAtlases get() {
        if (!built) {
            built = true;
            McAtlases at = null;
            try {
                at = McAtlases.get();
            } catch (Throwable t) {
                Safe.report("hud atlases", t);
            }
            atlases = at;
        }
        return atlases;
    }

    // a card's own sizes are not the theme's, put their glyphs in the atlas now and not on the first frame
    static void prewarm(TextStyle... styles) {
        McAtlases at = get();
        if (at == null || !at.ok) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (char ch = 32; ch < 127; ch++) {
            sb.append(ch);
        }
        sb.append('…');
        String ascii = sb.toString();
        for (TextStyle st : styles) {
            at.glyphs.shape(ascii, st);
        }
    }
}
