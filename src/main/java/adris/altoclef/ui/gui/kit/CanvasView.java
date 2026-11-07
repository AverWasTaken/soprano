package adris.altoclef.ui.gui.kit;

// pan and stepped zoom for the node canvas. pan is in design px so a window resize across a scale step keeps the view
// where it was, world is design px at zoom 1. the steps exist because an atlas glyph or a corner slice at 0.93 of
// anything is mush
public final class CanvasView {

    public record Rect(float x, float y, float w, float h) {
    }

    private static final float[] ZOOMS = {0.5f, 0.75f, 1f, 1.25f, 1.5f};
    private static final int HOME = 2;
    private static final int FIT_PAD = 24;
    // the mock's 0.8 rule landed on our steps: under 0.75 beside the inspector isn't worth squinting for
    private static final int READABLE = 1;

    private final float scale;
    private float originX;
    private float originY;
    private float panX;
    private float panY;
    private int index = HOME;

    public CanvasView(float scale) {
        this.scale = scale;
    }

    public static int steps() {
        return ZOOMS.length;
    }

    public static float zoomOf(int index) {
        return ZOOMS[index];
    }

    public void setOrigin(float x, float y) {
        originX = x;
        originY = y;
    }

    public float scale() {
        return scale;
    }

    public float zoom() {
        return ZOOMS[index];
    }

    public int zoomIndex() {
        return index;
    }

    // device px per world unit
    public float k() {
        return scale * ZOOMS[index];
    }

    public float panX() {
        return panX;
    }

    public float panY() {
        return panY;
    }

    // for whoever keeps the view across a gui rebuild
    public void restore(float panX, float panY, int zoomIndex) {
        this.panX = panX;
        this.panY = panY;
        index = clampIndex(zoomIndex);
    }

    public float screenX(float world) {
        return originX + scale * (panX + world * ZOOMS[index]);
    }

    public float screenY(float world) {
        return originY + scale * (panY + world * ZOOMS[index]);
    }

    public float worldX(float screen) {
        return ((screen - originX) / scale - panX) / ZOOMS[index];
    }

    public float worldY(float screen) {
        return ((screen - originY) / scale - panY) / ZOOMS[index];
    }

    public void panBy(float dxDevice, float dyDevice) {
        panX += dxDevice / scale;
        panY += dyDevice / scale;
    }

    // keeps the world point under the cursor where it is. false at the end of the range
    public boolean zoomAt(int steps, float sx, float sy) {
        int next = clampIndex(index + steps);
        if (next == index) {
            return false;
        }
        float wx = worldX(sx);
        float wy = worldY(sy);
        index = next;
        panX = (sx - originX) / scale - wx * ZOOMS[index];
        panY = (sy - originY) / scale - wy * ZOOMS[index];
        return true;
    }

    // bounds is world, free and whole are device px relative to the origin. the biggest step <= 1 that fits, never
    // above 1. if beside the inspector only something under 0.75 fits, use the whole canvas and let it cover the edge
    public void fit(Rect bounds, Rect free, Rect whole) {
        Rect room = free;
        int step = fittingStep(bounds, room);
        if (step < READABLE) {
            room = whole;
            step = fittingStep(bounds, room);
        }
        index = step;
        float z = ZOOMS[step];
        panX = (room.x + (room.w - bounds.w * z * scale) / 2f) / scale - bounds.x * z;
        panY = (room.y + (room.h - bounds.h * z * scale) / 2f) / scale - bounds.y * z;
    }

    private int fittingStep(Rect bounds, Rect room) {
        float pad = FIT_PAD * scale;
        for (int i = HOME; i > 0; i--) {
            float k = scale * ZOOMS[i];
            if (bounds.w * k <= room.w - pad && bounds.h * k <= room.h - pad) {
                return i;
            }
        }
        // doesn't fit at all: the smallest step, centred, and you pan for the rest
        return 0;
    }

    private static int clampIndex(int i) {
        return Math.max(0, Math.min(ZOOMS.length - 1, i));
    }
}
