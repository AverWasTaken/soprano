package adris.altoclef.ui.gui.kit;

// a node card's shape in design px, at zoom 1. every port sits on a 22 row so wires between cards on the same y come
// out dead straight, which is most of why the editor doesn't look like a plate of spaghetti
public final class NodeGeometry {

    public static final int WIDTH = 176;
    public static final int SMALL_WIDTH = 112;
    public static final int HEAD = 30;
    public static final int ROW = 22;
    public static final int FIRST_PORT = 16;
    public static final int RADIUS = 10;
    public static final int PORT = 10;
    public static final int LINE = 15;
    public static final int MAX_LINES = 2;
    public static final int MIN_BODY = 32;
    public static final int WHY = 21;
    public static final int TEXT_LEFT = 10;
    public static final int TEXT_RIGHT = 4;
    public static final int TEXT_PAD = 8;
    public static final int ITEM = 16;
    public static final int ITEM_TOP = 7;
    public static final int ITEM_BELOW = 4;
    public static final int ITEM_GAP = 6;
    public static final int PORT_COLUMN_RIGHT = 12;
    private static final int PORT_COLUMN_PAD = 10;

    private NodeGeometry() {
    }

    public static int width(boolean small) {
        return small ? SMALL_WIDTH : WIDTH;
    }

    public static int height(int outputCount, int summaryLines, boolean hasWhy, boolean itemRow) {
        return bodyBottom(outputCount, summaryLines, itemRow) + (hasWhy ? WHY : 0);
    }

    // where the body stops and the why line starts
    public static int bodyBottom(int outputCount, int summaryLines, boolean itemRow) {
        int text = (itemRow ? ITEM_TOP + ITEM + ITEM_BELOW : TEXT_PAD) + LINE * summaryLines + TEXT_PAD;
        int ports = outputCount > 0 ? PORT_COLUMN_PAD + ROW * outputCount : 0;
        return HEAD + Math.max(MIN_BODY, Math.max(text, ports));
    }

    public static int inPortY() {
        return HEAD + FIRST_PORT;
    }

    public static int outPortY(int index) {
        return HEAD + FIRST_PORT + ROW * Math.max(0, index);
    }

    public static int outPortX(boolean small) {
        return width(small);
    }

    // snapping on drop keeps rows lined up, so wires stay straight after a drag
    public static int snap(float world) {
        return Math.round(world / 8f) * 8;
    }
}
