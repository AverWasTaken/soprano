package adris.altoclef.ui.gui.kit;

// everything the gui draws goes through this. units are device pixels, origin top left of the window. two backends:
// the game (GuiGraphics, pose scaled by 1/guiScale) and a BufferedImage for previews and tests. the rounded rect,
// hairline, shadow, text and icon calls are implemented once in TexturedCanvas on top of fillRect + blitTinted, so a
// backend only has to copy tinted pixels and clip. no minecraft imports in here, ever
public interface Canvas extends TextMeasurer {

    int width();

    int height();

    void fillRect(float x, float y, float w, float h, int argb);

    void fillRoundRect(float x, float y, float w, float h, float radius, int argb);

    // one pixel hairline drawn inside the box
    void strokeRoundRect(float x, float y, float w, float h, float radius, int argb);

    // soft shadow under a rounded box: blur about 1.5x the radius, pushed down by half the radius
    void shadow(float x, float y, float w, float h, float radius, int argb);

    // draws at the baseline, returns the advance
    float text(String s, float x, float baseline, TextStyle style, int argb);

    void icon(Icon icon, float x, float y, float size, int argb);

    // registry id like "minecraft:oak_log". the preview draws a neutral tile, the game draws the real item
    void itemIcon(String itemId, float x, float y, float size);

    void pushClip(float x, float y, float w, float h);

    void popClip();
}
