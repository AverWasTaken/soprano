package adris.altoclef.ui.gui.kit;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.HashMap;
import java.util.Map;

// the boot screen's and title screen's pictures, baked with java2d because the canvas has no gradients, no tracking
// and no curves that aren't an icon. every one comes out at the device size it gets drawn at, so they're as sharp as
// the text next to them. the numbers are the soprano site's favicon and gui-mock/title, keep the three in step
public final class BrandArt {

    private static final int ICON_BLUE = 0xFF0A73E8;
    private static final int CURVE = 0xFFFFFFFF;
    private static final int START = 0xFF34C759;
    private static final int GOAL = 0xFFFF453A;
    private static final int WORDMARK = 0xFFF5F5F7;

    private BrandArt() {
    }

    // the favicon: blue rounded square, white path from the green dot to the red one
    public static PixelImage appIcon(int px) {
        return mark(px, true);
    }

    // the same path without the square, for the black boot screen
    public static PixelImage bootMark(int px) {
        return mark(px, false);
    }

    private static PixelImage mark(int px, boolean square) {
        int size = Math.max(1, px);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            smooth(g);
            // the art is on a 64 grid
            g.scale(size / 64.0, size / 64.0);
            if (square) {
                g.setColor(new Color(ICON_BLUE, true));
                g.fill(new RoundRectangle2D.Float(0, 0, 64, 64, 28, 28));
            }
            Path2D.Float path = new Path2D.Float();
            path.moveTo(16, 44);
            path.curveTo(30, 44, 26, 20, 48, 20);
            g.setColor(new Color(CURVE, true));
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(path);
            g.setColor(new Color(START, true));
            g.fill(new Ellipse2D.Float(16 - 5.5f, 44 - 5.5f, 11, 11));
            g.setColor(new Color(GOAL, true));
            g.fill(new Ellipse2D.Float(48 - 5.5f, 20 - 5.5f, 11, 11));
        } finally {
            g.dispose();
        }
        return toImage(img, 0, 0, size, size);
    }

    // "soprano" in semibold at -0.04em, cropped to the ink. the crop is what the gaps around it are measured from,
    // otherwise the 20 px under the icon turns into 57 because soprano has no ascenders
    public static PixelImage wordmark(AwtFonts fonts, int px) {
        int size = Math.max(4, px);
        Map<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.TRACKING, -0.04f);
        Font font = fonts.font(Weight.SEMIBOLD, size).deriveFont(attrs);
        int w = (int) Math.ceil(size * 5.0);
        int h = (int) Math.ceil(size * 2.0);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            smooth(g);
            g.setFont(font);
            g.setColor(new Color(WORDMARK, true));
            g.drawString("soprano", Math.round(size * 0.5f), Math.round(size * 1.2f));
        } finally {
            g.dispose();
        }
        int[] argb = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int x0 = w;
        int y0 = h;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((argb[y * w + x] >>> 24) != 0) {
                    x0 = Math.min(x0, x);
                    x1 = Math.max(x1, x);
                    y0 = Math.min(y0, y);
                    y1 = Math.max(y1, y);
                }
            }
        }
        if (x1 < 0) {
            return new PixelImage(1, 1).smooth();
        }
        return toImage(img, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    // a dock tile: top to bottom gradient inside a rounded square. the see-through pixels outside the corners keep
    // their row's colour, so linear sampling at the edge blends into the tile and not into black
    public static PixelImage tile(int top, int bottom, int px) {
        int size = Math.max(1, px);
        int r = Math.round(size * 0.234f);
        BufferedImage mask = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = mask.createGraphics();
        try {
            smooth(g);
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Float(0, 0, size, size, 2f * r, 2f * r));
        } finally {
            g.dispose();
        }
        int[] m = ((DataBufferInt) mask.getRaster().getDataBuffer()).getData();
        PixelImage out = new PixelImage(size, size).smooth();
        int[] dst = out.pixels();
        for (int y = 0; y < size; y++) {
            // css linear-gradient samples at the pixel centre
            int rgb = Theme.lerp(top, bottom, (y + 0.5f) / size) & 0x00FFFFFF;
            for (int x = 0; x < size; x++) {
                int i = y * size + x;
                dst[i] = (m[i] & 0xFF000000) | rgb;
            }
        }
        out.bump();
        return out;
    }

    // a disabled tile's stop: its own rec. 709 luma, so a light stop stays light and a dark one dark
    public static int grey(int argb) {
        int r = argb >> 16 & 255;
        int gr = argb >> 8 & 255;
        int b = argb & 255;
        int l = Math.round(0.2126f * r + 0.7152f * gr + 0.0722f * b);
        return (argb & 0xFF000000) | (l << 16) | (l << 8) | l;
    }

    // the title's backdrop over the blurred panorama: black 0.10 at the top to 0.45 at the bottom, and over that an
    // elliptical vignette (the corners are 1) from nothing in the middle to 0.25 in the corners. it is all smooth,
    // so it bakes small once and gets stretched over the window, the shape is in 0..1 of the box either way
    public static PixelImage shade(int w, int h) {
        PixelImage out = new PixelImage(w, h).smooth();
        int[] dst = out.pixels();
        float cx = w / 2f;
        float cy = h / 2f;
        for (int y = 0; y < h; y++) {
            float t = (y + 0.5f) / h;
            float lin = 0.10f + (0.45f - 0.10f) * t;
            float ny = (y + 0.5f - cy) / cy;
            for (int x = 0; x < w; x++) {
                float nx = (x + 0.5f - cx) / cx;
                float d = (float) Math.min(1.0, Math.sqrt((nx * nx + ny * ny) / 2.0));
                float vig = 0.25f * d;
                // the vignette is drawn over the gradient, both black: plain source over
                float a = vig + lin * (1f - vig);
                dst[y * w + x] = Math.round(a * 255f) << 24;
            }
        }
        out.bump();
        return out;
    }

    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    // TYPE_INT_ARGB is straight alpha, which is what PixelImage holds
    private static PixelImage toImage(BufferedImage img, int x0, int y0, int w, int h) {
        int[] src = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int sw = img.getWidth();
        PixelImage out = new PixelImage(w, h).smooth();
        int[] dst = out.pixels();
        for (int y = 0; y < h; y++) {
            System.arraycopy(src, (y0 + y) * sw + x0, dst, y * w, w);
        }
        out.bump();
        return out;
    }
}
