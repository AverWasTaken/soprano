package adris.altoclef.ui.gui.kit;

// the whole look in one place. colours are argb, sizes are design pixels at 1080p and go through px() so the panel is
// the same thing at every window size and minecraft gui scale. white is the only accent, red only means danger
public final class Theme {

    public static final int SCRIM = 0x73000000;
    public static final int SIDEBAR = 0xFF0E0E10;
    public static final int PANEL = 0xFF131315;
    public static final int RAISED = 0xFF1B1B1E;
    public static final int HOVER = 0xFF222226;
    public static final int PRESSED = 0xFF2A2A2F;
    public static final int HAIRLINE = 0xFF27272B;
    public static final int HAIRLINE_STRONG = 0xFF3A3A40;
    public static final int TEXT = 0xFFF2F2F3;
    public static final int TEXT_2 = 0xFFA2A2A9;
    public static final int TEXT_3 = 0xFF6C6C74;
    public static final int ACCENT = 0xFFFFFFFF;
    public static final int ON_ACCENT = 0xFF0E0E10;
    public static final int DANGER = 0xFFC96B6B;
    public static final int DANGER_FILL = 0xFF3A1E1E;
    public static final int SHADOW = 0x66000000;

    // design sizes at scale 1
    public static final int SIDEBAR_W = 176;
    public static final int HEADER_H = 52;
    public static final int STATUS_H = 40;
    public static final int PANEL_RADIUS = 10;
    public static final int CONTROL_RADIUS = 6;
    public static final int ROW_RADIUS = 4;
    public static final int ROW_H = 36;
    public static final int CONTROL_H = 30;
    public static final int PAD = 16;
    public static final int GAP = 8;
    public static final int ICON = 16;
    public static final int TAB_BAR_W = 2;
    public static final int SCROLLBAR_W = 4;

    // animation lengths in ms. nothing bounces, nothing spins
    public static final int ANIM_FAST = 120;
    public static final int ANIM = 160;
    public static final int ANIM_SLOW = 200;

    public final float scale;
    public final TextMeasurer measurer;

    public final TextStyle title;
    public final TextStyle label;
    public final TextStyle body;
    public final TextStyle small;
    public final TextStyle smallLabel;

    public Theme(float scale, TextMeasurer measurer) {
        this.scale = scale;
        this.measurer = measurer;
        this.title = new TextStyle(Weight.SEMIBOLD, px(18));
        this.label = new TextStyle(Weight.MEDIUM, px(13));
        this.body = new TextStyle(Weight.REGULAR, px(13));
        this.small = new TextStyle(Weight.REGULAR, px(11));
        this.smallLabel = new TextStyle(Weight.MEDIUM, px(11));
    }

    public int px(int design) {
        return Math.max(1, Math.round(design * scale));
    }

    public float pxf(float design) {
        return design * scale;
    }

    // quarter steps so the glyph atlases stay cached while the window is dragged around, clamped so a tiny window
    // still gets readable text and a 4k one does not get a billboard
    public static float scaleFor(int windowHeightPx) {
        float raw = windowHeightPx / 1080f;
        float stepped = Math.round(raw * 4f) / 4f;
        return Math.max(0.75f, Math.min(2.0f, stepped));
    }

    // argb with a new alpha, 0..1
    public static int alpha(int argb, float a) {
        int al = Math.round(Math.max(0f, Math.min(1f, a)) * 255f);
        return (al << 24) | (argb & 0x00FFFFFF);
    }

    // scale the alpha that is already there
    public static int fade(int argb, float factor) {
        int al = (argb >>> 24);
        return alpha(argb, (al / 255f) * factor);
    }

    public static int lerp(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = Math.round(((from >>> 24) & 255) + (((to >>> 24) & 255) - ((from >>> 24) & 255)) * t);
        int r = Math.round(((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * t);
        int g = Math.round(((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * t);
        int b = Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
