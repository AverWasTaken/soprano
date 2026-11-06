package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.List;

// elbow wires. two stages: the polyline in world units (cached, only changes when a card moves), then trace() turns
// the projected polyline into whole pixels: straight rects and corner slices. the two come out separately so the canvas
// can draw every fill and then every blit, a fill/blit switch per elbow would be thousands of pipeline switches a frame
public final class WireRouter {

    public static final int RADIUS = 10;
    private static final int FAN = 4;
    private static final int BACK_RISE = 26;
    private static final int LANE_GAP = 8;
    private static final int BACK_OUT = 18;
    private static final int BACK_IN = 24;
    private static final int BACK_WHEN = 30;
    // wires stop short of the input so the arrow tip sits on the line and the port stays visible
    public static final int END_SHORT = 6;

    // a is the out port, b the input port's centre, tops are the two cards' top edges
    public record In(float ax, float ay, float bx, float by, int port, float fromTop, float toTop) {
    }

    public record Route(float[] xs, float[] ys, boolean back, float midX, float midY,
                        float minX, float minY, float maxX, float maxY) {
    }

    public record Seg(int x, int y, int w, int h) {
    }

    public record Corner(int left, int top, int r, int quadrant) {
    }

    public record Elbows(List<Seg> segs, List<Corner> corners) {
    }

    public interface Sink {
        void segment(int x, int y, int w, int h);

        void corner(int left, int top, int r, int quadrant);
    }

    private WireRouter() {
    }

    public static boolean isBack(float ax, float bx) {
        return bx < ax + BACK_WHEN;
    }

    // k = which lane over this wire's cards, 0 is the closest
    public static Route route(In w, int k) {
        return isBack(w.ax, w.bx) ? back(w, k) : forward(w);
    }

    private static Route forward(In w) {
        float end = w.bx - END_SHORT;
        if (w.ay == w.by) {
            return build(new float[]{w.ax, end}, new float[]{w.ay, w.by}, false, (w.ax + end) / 2f, w.ay);
        }
        // offset per port or a fan out would run its risers down one column
        float mid = (w.ax + end) / 2f + FAN * w.port;
        return build(new float[]{w.ax, mid, mid, end}, new float[]{w.ay, w.ay, w.by, w.by}, false, mid, (w.ay + w.by) / 2f);
    }

    private static Route back(In w, int k) {
        float lane = laneBase(w) - LANE_GAP * k;
        float out = w.ax + BACK_OUT;
        float in = w.bx - BACK_IN;
        return build(new float[]{w.ax, out, out, in, in, w.bx - END_SHORT},
                new float[]{w.ay, w.ay, lane, lane, w.by, w.by}, true, (out + in) / 2f, lane);
    }

    private static float laneBase(In w) {
        return Math.min(w.fromTop, w.toTop) - BACK_RISE;
    }

    // routes for a whole graph, in wire order. a repeat's loop back is the common case, so the lane bookkeeping lives here
    public static List<Route> routeAll(List<In> wires) {
        List<Route> out = new ArrayList<>(wires.size());
        List<In> backs = new ArrayList<>();
        List<Float> lanes = new ArrayList<>();
        for (In w : wires) {
            int k = 0;
            if (isBack(w.ax, w.bx)) {
                k = freeLane(w, backs, lanes);
                backs.add(w);
                lanes.add(laneBase(w) - LANE_GAP * k);
            }
            out.add(route(w, k));
        }
        return out;
    }

    // the first lane going up that nothing over this span is already using. counting the overlaps isn't enough: a->b and
    // c->d can both overlap b->c without overlapping each other, then the count hands two neighbours the same lane
    private static int freeLane(In w, List<In> backs, List<Float> lanes) {
        List<Float> taken = new ArrayList<>();
        for (int i = 0; i < backs.size(); i++) {
            if (spansTouch(w, backs.get(i))) {
                taken.add(lanes.get(i));
            }
        }
        // top down, so one walk settles it however many there are
        taken.sort((a, b) -> Float.compare(b, a));
        float base = laneBase(w);
        int k = 0;
        for (float lane : taken) {
            // a while: cards loaded from a file aren't always on the 8 grid, so one step might land 1 px off this lane
            while (lane > base - LANE_GAP * (k + 1) && lane < base - LANE_GAP * (k - 1)) {
                k++;
            }
        }
        return k;
    }

    private static boolean spansTouch(In a, In b) {
        float a0 = Math.min(a.ax + BACK_OUT, a.bx - BACK_IN);
        float a1 = Math.max(a.ax + BACK_OUT, a.bx - BACK_IN);
        float b0 = Math.min(b.ax + BACK_OUT, b.bx - BACK_IN);
        float b1 = Math.max(b.ax + BACK_OUT, b.bx - BACK_IN);
        return a0 <= b1 && b0 <= a1;
    }

    // the wire being dragged: an elbow to the mouse. it goes wherever the mouse goes, no lanes
    public static Route ghost(float ax, float ay, float bx, float by) {
        float mid = (ax + bx) / 2f;
        return build(new float[]{ax, mid, mid, bx}, new float[]{ay, ay, by, by}, false, mid, (ay + by) / 2f);
    }

    private static Route build(float[] xs, float[] ys, boolean back, float mx, float my) {
        float x0 = xs[0];
        float x1 = xs[0];
        float y0 = ys[0];
        float y1 = ys[0];
        for (int i = 1; i < xs.length; i++) {
            x0 = Math.min(x0, xs[i]);
            x1 = Math.max(x1, xs[i]);
            y0 = Math.min(y0, ys[i]);
            y1 = Math.max(y1, ys[i]);
        }
        return new Route(xs, ys, back, mx, my, x0, y0, x1, y1);
    }

    public static Elbows elbows(int[] sx, int[] sy, int n, int radius, int thick) {
        List<Seg> segs = new ArrayList<>();
        List<Corner> corners = new ArrayList<>();
        trace(sx, sy, n, radius, thick, new Sink() {
            @Override
            public void segment(int x, int y, int w, int h) {
                segs.add(new Seg(x, y, w, h));
            }

            @Override
            public void corner(int left, int top, int r, int quadrant) {
                corners.add(new Corner(left, top, r, quadrant));
            }
        });
        return new Elbows(segs, corners);
    }

    // sx, sy are projected whole pixels and get compacted in place: rounding can collapse a leg or leave a collinear
    // point behind. a line t thick covers p - t/2 .. p - t/2 + t - 1, same centring as an even port dot. a radius clamped
    // under 2 is a square join, a one pixel ring slice is a smudge. a thick corner is t rings: r, r-1, r-2... nested
    public static void trace(int[] sx, int[] sy, int n, int radius, int thick, Sink out) {
        n = compact(sx, sy, n);
        if (n < 2) {
            return;
        }
        int half = thick / 2;
        for (int i = 0; i < n - 1; i++) {
            segment(sx, sy, n, i, radius, thick, half, out);
        }
        for (int i = 1; i < n - 1; i++) {
            corner(sx, sy, i, radius, thick, half, out);
        }
    }

    private static int compact(int[] sx, int[] sy, int n) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (m > 0 && sx[m - 1] == sx[i] && sy[m - 1] == sy[i]) {
                continue;
            }
            // a -> b -> c on one line: b has no business being a corner
            if (m > 1 && (sx[m - 2] == sx[m - 1] && sx[m - 1] == sx[i] || sy[m - 2] == sy[m - 1] && sy[m - 1] == sy[i])) {
                m--;
            }
            sx[m] = sx[i];
            sy[m] = sy[i];
            m++;
        }
        return m;
    }

    private static int cornerRadius(int[] sx, int[] sy, int i, int radius) {
        int a = Math.abs(sx[i] - sx[i - 1]) + Math.abs(sy[i] - sy[i - 1]);
        int b = Math.abs(sx[i + 1] - sx[i]) + Math.abs(sy[i + 1] - sy[i]);
        int r = Math.min(radius, Math.min(a, b) / 2);
        return r < 2 ? 0 : r;
    }

    private static void segment(int[] sx, int[] sy, int n, int i, int radius, int thick, int half, Sink out) {
        boolean horizontal = sy[i] == sy[i + 1];
        int lowEnd = sx[i] + sy[i] <= sx[i + 1] + sy[i + 1] ? i : i + 1;
        int highEnd = lowEnd == i ? i + 1 : i;
        int[] along = horizontal ? sx : sy;
        // each end of the leg stops where the corner on that end starts
        int lo = isCorner(lowEnd, n) ? trimLow(along[lowEnd] - half, thick, cornerRadius(sx, sy, lowEnd, radius)) : along[lowEnd];
        int hi = isCorner(highEnd, n) ? trimHigh(along[highEnd] - half, thick, cornerRadius(sx, sy, highEnd, radius)) : along[highEnd];
        if (hi <= lo) {
            return;
        }
        if (horizontal) {
            out.segment(lo, sy[i] - half, hi - lo, thick);
        } else {
            out.segment(sx[i] - half, lo, thick, hi - lo);
        }
    }

    private static boolean isCorner(int i, int n) {
        return i > 0 && i < n - 1;
    }

    // first free pixel past a corner on the leg's low side. r 0 is the square join's t by t block
    private static int trimLow(int edge, int thick, int r) {
        return r == 0 ? edge + thick : edge + r;
    }

    // first pixel the corner owns on the leg's high side, so the leg's exclusive end
    private static int trimHigh(int edge, int thick, int r) {
        return r == 0 ? edge : edge + thick - r;
    }

    private static void corner(int[] sx, int[] sy, int i, int radius, int thick, int half, Sink out) {
        int r = cornerRadius(sx, sy, i, radius);
        int left = sx[i] - half;
        int top = sy[i] - half;
        if (r == 0) {
            out.segment(left, top, thick, thick);
            return;
        }
        // which way the two legs leave this corner. the leg that shares our y is the horizontal one
        boolean prevHorizontal = sy[i - 1] == sy[i];
        boolean legRight = (prevHorizontal ? sx[i - 1] : sx[i + 1]) > sx[i];
        boolean legDown = (prevHorizontal ? sy[i + 1] : sy[i - 1]) > sy[i];
        int quadrant = legDown ? (legRight ? ShapeAtlas.TL : ShapeAtlas.TR) : (legRight ? ShapeAtlas.BL : ShapeAtlas.BR);
        // ring(0) quietly hands back ring(1), hence the stop at 1
        for (int t = 0; t < thick && r - t >= 1; t++) {
            int x = legRight ? left + t : left + thick - r;
            int y = legDown ? top + t : top + thick - r;
            out.corner(x, y, r - t, quadrant);
        }
    }
}
