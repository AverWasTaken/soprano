package adris.altoclef.ui.gui.kit;

import java.util.List;

// what's under the mouse, in world units. a linear scan, and 200 cards at mouse rate is nothing. lists are in draw
// order, so walking them backwards makes the top one win
public final class NodeHits {

    public record Box(String id, float x, float y, float w, float h, boolean hasIn, int outs) {
    }

    // out == -1 is the input
    public record PortHit(int card, int out) {
    }

    private NodeHits() {
    }

    // half is half the hit box. a card sitting on top of another card's port hides it, same as it hides everything else.
    // two ports inside one box: the closer one, at 0.5 zoom the boxes overlap
    public static PortHit port(List<Box> boxes, float wx, float wy, float half) {
        for (int i = boxes.size() - 1; i >= 0; i--) {
            Box b = boxes.get(i);
            int hit = portOf(b, wx, wy, half);
            if (hit != NO_PORT) {
                return new PortHit(i, hit);
            }
            if (inside(b, wx, wy)) {
                return null;
            }
        }
        return null;
    }

    private static final int NO_PORT = -2;

    private static int portOf(Box b, float wx, float wy, float half) {
        int best = NO_PORT;
        float bestD = Float.MAX_VALUE;
        if (Math.abs(wx - (b.x + b.w)) <= half) {
            for (int p = 0; p < b.outs; p++) {
                float d = Math.abs(wy - (b.y + NodeGeometry.outPortY(p)));
                if (d <= half && d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
        }
        if (b.hasIn && Math.abs(wx - b.x) <= half) {
            float d = Math.abs(wy - (b.y + NodeGeometry.inPortY()));
            if (d <= half && d < bestD) {
                best = -1;
            }
        }
        return best;
    }

    public static int card(List<Box> boxes, float wx, float wy) {
        for (int i = boxes.size() - 1; i >= 0; i--) {
            if (inside(boxes.get(i), wx, wy)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean inside(Box b, float wx, float wy) {
        return wx >= b.x && wy >= b.y && wx < b.x + b.w && wy < b.y + b.h;
    }

    // reach is world units. last drawn within reach, not the nearest: that's the one you can see on top
    public static int wire(List<WireRouter.Route> routes, float wx, float wy, float reach) {
        for (int i = routes.size() - 1; i >= 0; i--) {
            WireRouter.Route r = routes.get(i);
            if (wx >= r.minX() - reach && wx <= r.maxX() + reach && wy >= r.minY() - reach && wy <= r.maxY() + reach
                    && distance(r, wx, wy) <= reach) {
                return i;
            }
        }
        return -1;
    }

    // to the polyline, sharp corners and all. the rounding moves a corner by 3 px at most and nobody aims that well
    public static float distance(WireRouter.Route r, float wx, float wy) {
        float best = Float.MAX_VALUE;
        float[] xs = r.xs();
        float[] ys = r.ys();
        for (int i = 0; i < xs.length - 1; i++) {
            float nx = clamp(wx, Math.min(xs[i], xs[i + 1]), Math.max(xs[i], xs[i + 1]));
            float ny = clamp(wy, Math.min(ys[i], ys[i + 1]), Math.max(ys[i], ys[i + 1]));
            float dx = wx - nx;
            float dy = wy - ny;
            best = Math.min(best, dx * dx + dy * dy);
        }
        return (float) Math.sqrt(best);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
