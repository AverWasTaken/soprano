package adris.altoclef.ui.gui.kit;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

// every icon as a handful of strokes on a 24 unit grid, white, drawn at whatever pixel size the atlas asks for. no
// pngs to ship and no blurry upscales when somebody runs a 4k monitor. the art lives in 2..22 of the grid
public final class Icons {

    private static final float GRID_LO = 2f;
    private static final float GRID_SPAN = 20f;

    private Icons() {
    }

    public static void draw(Icon icon, Graphics2D g, int size) {
        // under about 4px the grid maths flips the art inside out, and there is nothing to see anyway
        if (size < 4) {
            return;
        }
        AffineTransform savedT = g.getTransform();
        Stroke savedS = g.getStroke();
        Object savedAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Object savedSc = g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL);
        Color savedC = g.getColor();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.WHITE);

        float sw = Math.max(1.3f, size / 9.5f);
        // the stroke's outer edge sits half a pixel inside the box, so nothing gets sliced by the atlas edge
        float lo = 0.5f + sw * 0.5f;
        float scale = (size - 2f * lo) / GRID_SPAN;
        g.translate(lo, lo);
        g.scale(scale, scale);
        g.translate(-GRID_LO, -GRID_LO);
        float w = sw / scale;
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        art(icon, g, w);

        g.setTransform(savedT);
        g.setStroke(savedS);
        g.setColor(savedC);
        restore(g, RenderingHints.KEY_ANTIALIASING, savedAa);
        restore(g, RenderingHints.KEY_STROKE_CONTROL, savedSc);
    }

    private static void restore(Graphics2D g, RenderingHints.Key key, Object value) {
        if (value != null) {
            g.setRenderingHint(key, value);
        }
    }

    // w is the stroke width in grid units, for the things that are just a dot
    private static void art(Icon icon, Graphics2D g, float w) {
        switch (icon) {
            case GET -> {
                // a box seen from a corner, with the tape stripe on the lid
                poly(g, true, 3, 7.5f, 12, 3, 21, 7.5f, 21, 16.5f, 12, 21, 3, 16.5f);
                poly(g, false, 3, 7.5f, 12, 12, 21, 7.5f);
                line(g, 12, 12, 12, 21);
                line(g, 7.5f, 5.25f, 16.5f, 9.75f);
            }
            case GOTO -> {
                // the l is the corner you are leaving, the arrow is where it is going
                poly(g, false, 4, 8, 4, 20, 16, 20);
                line(g, 9, 15, 20, 4);
                poly(g, false, 12, 4, 20, 4, 20, 12);
            }
            case FOLLOW -> {
                g.draw(new Ellipse2D.Float(5.5f, 4, 7, 7));
                Path2D.Float body = new Path2D.Float();
                body.moveTo(3, 20.5f);
                body.lineTo(3, 19);
                body.quadTo(3, 14.5f, 7.5f, 14.5f);
                body.lineTo(10.5f, 14.5f);
                body.quadTo(15, 14.5f, 15, 19);
                body.lineTo(15, 20.5f);
                g.draw(body);
                line(g, 17.5f, 11.5f, 22, 11.5f);
                poly(g, false, 19.8f, 9.2f, 22, 11.5f, 19.8f, 13.8f);
            }
            case WAYPOINTS -> {
                line(g, 5, 3, 5, 21);
                poly(g, true, 5, 4, 19, 4, 15.5f, 8.5f, 19, 13, 5, 13);
            }
            case HELP -> {
                g.draw(new Ellipse2D.Float(2.5f, 2.5f, 19, 19));
                Path2D.Float q = new Path2D.Float();
                q.append(new Arc2D.Float(9, 6.5f, 6, 6, 195, -225, Arc2D.OPEN), false);
                q.lineTo(12, 13.2f);
                q.lineTo(12, 14.4f);
                g.draw(q);
                dot(g, 12, 17.4f, w);
            }
            case SETTINGS -> {
                line(g, 3, 7, 11.5f, 7);
                line(g, 18.5f, 7, 21, 7);
                g.draw(new Ellipse2D.Float(11.5f, 4, 7, 7));
                line(g, 3, 17, 5.5f, 17);
                line(g, 12.5f, 17, 21, 17);
                g.draw(new Ellipse2D.Float(5.5f, 14, 7, 7));
            }
            case SEARCH -> {
                g.draw(new Ellipse2D.Float(3.5f, 3.5f, 13, 13));
                line(g, 15.5f, 15.5f, 21, 21);
            }
            case CLOSE -> {
                line(g, 6, 6, 18, 18);
                line(g, 18, 6, 6, 18);
            }
            case PLUS -> {
                line(g, 12, 5, 12, 19);
                line(g, 5, 12, 19, 12);
            }
            case MINUS -> line(g, 5, 12, 19, 12);
            case CHEVRON_DOWN -> poly(g, false, 6, 9, 12, 15, 18, 9);
            case CHEVRON_RIGHT -> poly(g, false, 9, 6, 15, 12, 9, 18);
            case CHECK -> poly(g, false, 4.5f, 12.5f, 10, 18, 19.5f, 7);
            case COPY -> {
                g.draw(new RoundRectangle2D.Float(9, 9, 12, 12, 5, 5));
                Path2D.Float back = new Path2D.Float();
                back.moveTo(9, 15);
                back.lineTo(5.5f, 15);
                back.quadTo(3, 15, 3, 12.5f);
                back.lineTo(3, 5.5f);
                back.quadTo(3, 3, 5.5f, 3);
                back.lineTo(12.5f, 3);
                back.quadTo(15, 3, 15, 5.5f);
                back.lineTo(15, 9);
                g.draw(back);
            }
            case RESET -> {
                // open on the upper right, arrow on the loose end, goes round the wrong way on purpose
                float cx = 10.5f;
                float cy = 12f;
                float r = 7.5f;
                g.draw(new Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, 100, 270, Arc2D.OPEN));
                double th = Math.toRadians(10);
                float px = cx + r * (float) Math.cos(th);
                float py = cy - r * (float) Math.sin(th);
                head(g, px, py, (float) -Math.sin(th), (float) -Math.cos(th), 3.2f, 2.6f);
            }
            case STOP -> g.fill(new RoundRectangle2D.Float(5.5f, 5.5f, 13, 13, 4, 4));
            case KEY -> {
                g.draw(new RoundRectangle2D.Float(3, 5, 18, 14, 7, 7));
                line(g, 8, 14.5f, 16, 14.5f);
            }
            case DOT -> g.fill(new Ellipse2D.Float(5, 5, 14, 14));
            case RING -> g.draw(new Ellipse2D.Float(5.5f, 5.5f, 13, 13));
            case TRASH -> {
                line(g, 4, 6.5f, 20, 6.5f);
                poly(g, false, 9, 6.5f, 9, 3.8f, 15, 3.8f, 15, 6.5f);
                Path2D.Float bin = new Path2D.Float();
                bin.moveTo(6, 6.5f);
                bin.lineTo(6.9f, 19.3f);
                bin.quadTo(7, 21, 8.7f, 21);
                bin.lineTo(15.3f, 21);
                bin.quadTo(17, 21, 17.1f, 19.3f);
                bin.lineTo(18, 6.5f);
                g.draw(bin);
                line(g, 10, 10.5f, 10, 17);
                line(g, 14, 10.5f, 14, 17);
            }
            case PIN -> {
                Path2D.Float pin = new Path2D.Float();
                pin.moveTo(20, 10);
                pin.curveTo(20, 16, 12, 22, 12, 22);
                pin.curveTo(12, 22, 4, 16, 4, 10);
                pin.append(new Arc2D.Float(4, 2, 16, 16, 180, -180, Arc2D.OPEN), true);
                g.draw(pin);
                g.draw(new Ellipse2D.Float(9, 7, 6, 6));
            }
            case PLAYER -> {
                g.draw(new Ellipse2D.Float(7.5f, 3.5f, 9, 9));
                Path2D.Float body = new Path2D.Float();
                body.moveTo(5, 21);
                body.lineTo(5, 19);
                body.quadTo(5, 15, 9, 15);
                body.lineTo(15, 15);
                body.quadTo(19, 15, 19, 19);
                body.lineTo(19, 21);
                g.draw(body);
            }
            case ENTITY -> {
                // four toes and a pad, filled, because a paw outline at 16px is just a smudge
                g.fill(new Ellipse2D.Float(3.2f, 8.6f, 4, 5.4f));
                g.fill(new Ellipse2D.Float(7.2f, 3.2f, 4, 5.8f));
                g.fill(new Ellipse2D.Float(12.8f, 3.2f, 4, 5.8f));
                g.fill(new Ellipse2D.Float(16.8f, 8.6f, 4, 5.4f));
                Path2D.Float pad = new Path2D.Float();
                pad.moveTo(12, 11);
                pad.curveTo(9.2f, 11, 6, 14.2f, 6, 17.4f);
                pad.curveTo(6, 19.8f, 7.6f, 21, 9.6f, 21);
                pad.curveTo(10.8f, 21, 11.3f, 20.5f, 12, 20.5f);
                pad.curveTo(12.7f, 20.5f, 13.2f, 21, 14.4f, 21);
                pad.curveTo(16.4f, 21, 18, 19.8f, 18, 17.4f);
                pad.curveTo(18, 14.2f, 14.8f, 11, 12, 11);
                pad.closePath();
                g.fill(pad);
            }
        }
    }

    private static void line(Graphics2D g, float x1, float y1, float x2, float y2) {
        g.draw(new Line2D.Float(x1, y1, x2, y2));
    }

    private static void poly(Graphics2D g, boolean close, float... xy) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) {
            p.lineTo(xy[i], xy[i + 1]);
        }
        if (close) {
            p.closePath();
        }
        g.draw(p);
    }

    // a stroke wide dot, which is what a round capped zero length line would be if awt drew those
    private static void dot(Graphics2D g, float x, float y, float w) {
        g.fill(new Ellipse2D.Float(x - w * 0.5f, y - w * 0.5f, w, w));
    }

    // open v arrowhead whose tip is at (x, y) and which points along the unit vector (tx, ty)
    private static void head(Graphics2D g, float x, float y, float tx, float ty, float len, float spread) {
        float bx = x - tx * len;
        float by = y - ty * len;
        float nx = -ty;
        float ny = tx;
        poly(g, false, bx + nx * spread, by + ny * spread, x, y, bx - nx * spread, by - ny * spread);
    }
}
