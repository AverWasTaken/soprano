package adris.altoclef.ui.gui.kit;

import adris.altoclef.ui.gui.preview.Preview;

import java.util.ArrayList;
import java.util.List;

// a real TexturedCanvas that throws the pixels away and counts what a backend would be asked for. a pipeline switch is
// the game going from fills to blits or back, a page switch is the game rebinding a texture between two blits
public final class CountingCanvas extends TexturedCanvas {

    public int fills;
    public int blits;
    public int switches;
    public int pageSwitches;
    public int clips;
    public int texts;
    public int icons;
    public int items;
    public int shadows;
    public int depth;
    public final List<Integer> cornerColours = new ArrayList<>();
    private int last = -1;
    private AtlasPage lastPage;
    private final int width;
    private final int height;

    public CountingCanvas(Preview p) {
        this(p.glyphs, p.shapes, p.winW, p.winH);
        setTheme(p.theme);
    }

    public CountingCanvas(GlyphAtlas glyphs, ShapeAtlas shapes, int width, int height) {
        super(glyphs, shapes);
        this.width = width;
        this.height = height;
    }

    public int calls() {
        return fills + blits;
    }

    public void reset() {
        fills = blits = switches = pageSwitches = clips = texts = icons = items = shadows = 0;
        last = -1;
        lastPage = null;
        cornerColours.clear();
    }

    private void kind(int k) {
        if (last != -1 && last != k) {
            switches++;
        }
        last = k;
    }

    @Override
    protected void blitTinted(AtlasPage page, int sx, int sy, int sw, int sh, float dx, float dy, float dw, float dh, int argb) {
        blits++;
        kind(1);
        if (lastPage != null && lastPage != page) {
            pageSwitches++;
        }
        lastPage = page;
    }

    @Override
    public void fillRect(float x, float y, float w, float h, int argb) {
        fills++;
        kind(0);
    }

    @Override
    public void cornerFill(int left, int top, int r, int quadrant, int argb) {
        cornerColours.add(argb);
        super.cornerFill(left, top, r, quadrant, argb);
    }

    @Override
    public float text(String s, float x, float baseline, TextStyle style, int argb) {
        texts++;
        return super.text(s, x, baseline, style, argb);
    }

    @Override
    public void icon(Icon icon, float x, float y, float size, int argb) {
        icons++;
        super.icon(icon, x, y, size, argb);
    }

    @Override
    public void shadow(float x, float y, float w, float h, float radius, int argb) {
        shadows++;
        super.shadow(x, y, w, h, radius, argb);
    }

    @Override
    public void itemIcon(String itemId, float x, float y, float size) {
        items++;
    }

    @Override
    public void image(PixelImage image, float x, float y, float w, float h, int tint) {
    }

    @Override
    public void pushClip(float x, float y, float w, float h) {
        clips++;
        depth++;
    }

    @Override
    public void popClip() {
        depth--;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }
}
