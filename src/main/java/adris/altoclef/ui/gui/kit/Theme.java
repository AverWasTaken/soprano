package adris.altoclef.ui.gui.kit;

// the whole look in one place. colours are argb, sizes are design pixels at 1080p and go through px() so the panel is
// the same thing at every window size and minecraft gui scale. cyan marks a selection, red means danger
public final class Theme {

    // these are also the default dark palette. resolve a token through color() before blending or fading it
    public static final int SCRIM = 0x66070C14;
    public static final int SIDEBAR = 0xFF1C2027;
    public static final int PANEL = 0xFF222730;
    public static final int RAISED = 0xFF2C333E;
    public static final int HOVER = 0xFF343E4B;
    public static final int PRESSED = 0xFF414D5D;
    public static final int SELECTED = 0xFF465160;
    public static final int HAIRLINE = 0xFF343C48;
    public static final int HAIRLINE_STRONG = 0xFF667281;
    public static final int TEXT = 0xFFF0F3F7;
    public static final int TEXT_2 = 0xFFBEC7D2;
    public static final int TEXT_3 = 0xFF98A5B5;
    public static final int ACCENT = 0xFF62C6FA;
    public static final int ACCENT_HOVER = 0xFF8AD6FF;
    public static final int ACCENT_PRESSED = 0xFF43ADE5;
    public static final int ACCENT_SOFT = 0xFF213E50;
    public static final int ON_ACCENT = 0xFF092536;
    public static final int DANGER = 0xFFFF9696;
    public static final int DANGER_FILL = 0xFF4B2E35;
    public static final int DANGER_PRESSED = 0xFF613740;
    public static final int SHADOW = 0x66000000;
    public static final int PATH_START = 0xFF72C688;
    public static final int PATH_END = 0xFFEB817B;

    public static final int SURFACE = 0xFF2B3039;
    public static final int SOFT_SHADE = 0x800A0D13;
    public static final int SOFT_LIGHT = 0x40546175;
    public static final int INSET = 0xFF1C2129;
    public static final int EDGE = 0x167D8A9C;
    public static final int GLEAM = 0x22FFFFFF;

    // design sizes at scale 1
    public static final int SIDEBAR_W = 176;
    public static final int HEADER_H = 58;
    public static final int STATUS_H = 36;
    public static final int PANEL_RADIUS = 20;
    public static final int CONTROL_RADIUS = 9;
    public static final int ROW_RADIUS = 10;
    public static final int ROW_H = 34;
    public static final int CONTROL_H = 30;
    public static final int PAD = 16;
    public static final int GAP = 8;
    public static final int ICON = 16;
    public static final int TAB_BAR_W = 2;
    public static final int SCROLLBAR_W = 4;

    // animation lengths in ms. nothing bounces, nothing spins
    public static final int ANIM_FAST = 150;
    public static final int ANIM = 240;
    public static final int ANIM_SLOW = 320;

    public final float scale;
    public final TextMeasurer measurer;

    public final TextStyle title;
    public final TextStyle label;
    public final TextStyle body;
    public final TextStyle small;
    public final TextStyle smallLabel;

    private final Anim appearance = new Anim(0f);
    private boolean dark = true;
    private float lightMix;

    public Theme(float scale, TextMeasurer measurer) {
        this.scale = scale;
        this.measurer = measurer;
        this.title = new TextStyle(Weight.MEDIUM, px(22));
        this.label = new TextStyle(Weight.MEDIUM, px(14));
        this.body = new TextStyle(Weight.REGULAR, px(15));
        this.small = new TextStyle(Weight.REGULAR, px(12));
        this.smallLabel = new TextStyle(Weight.REGULAR, px(12));
    }

    public boolean dark() {
        return dark;
    }

    // loading a saved preference should not animate from the opposite appearance
    public void setDark(boolean value) {
        dark = value;
        lightMix = value ? 0f : 1f;
        appearance.snap(lightMix);
    }

    public void setDark(boolean value, long nowNanos) {
        dark = value;
        appearance.set(value ? 0f : 1f, nowNanos, ANIM_SLOW);
        update(nowNanos);
    }

    public void update(long nowNanos) {
        lightMix = appearance.value(nowNanos);
    }

    // every widget shares the same frame's blend; switching themes never rebuilds controls or loses an edit
    public int color(int token) {
        if (lightMix <= 0f) {
            return token;
        }
        int light = switch (token) {
            case SURFACE -> 0xFFF0F2F5;
            case SOFT_SHADE -> 0x557C8798;
            case SOFT_LIGHT -> 0xDFFFFFFF;
            case INSET -> 0xFFE8ECF1;
            case EDGE -> 0x14758294;
            case GLEAM -> 0xCCFFFFFF;
            case SCRIM -> 0x350D1420;
            case SIDEBAR -> 0xFFE7EBF0;
            case PANEL -> 0xFFECEFF3;
            case RAISED -> 0xFFECEFF3;
            case HOVER -> 0xFFE2E8EE;
            case PRESSED -> 0xFFD4DEE8;
            case SELECTED -> 0xFFFFFFFF;
            case HAIRLINE -> 0xFFDDE3E9;
            case HAIRLINE_STRONG -> 0xFF9DAAB8;
            case TEXT -> 0xFF242B33;
            case TEXT_2 -> 0xFF515E6B;
            case TEXT_3 -> 0xFF667582;
            case ACCENT -> 0xFF007EAF;
            case ACCENT_HOVER -> 0xFF006F9F;
            case ACCENT_PRESSED -> 0xFF005E89;
            case ACCENT_SOFT -> 0xFFDCEFF9;
            case ON_ACCENT -> 0xFFFFFFFF;
            case DANGER -> 0xFFB43B42;
            case DANGER_FILL -> 0xFFF8E3E5;
            case DANGER_PRESSED -> 0xFFF0CBCF;
            case SHADOW -> 0x38000000;
            case PATH_START -> 0xFF388449;
            case PATH_END -> 0xFFBD4E47;
            default -> token;
        };
        return lightMix >= 1f ? light : lerp(token, light, lightMix);
    }

    // light comes from the upper left throughout the shell, including controls pressed into a card
    public void card(Canvas c, float x, float y, float w, float h, float radius, boolean raised) {
        if (raised) {
            lift(c, x, y, w, h, radius, 1f);
        }
        c.fillRoundRect(x, y, w, h, radius, color(SURFACE));
        c.strokeRoundRect(x, y, w, h, radius, color(EDGE));
    }

    public void lift(Canvas c, float x, float y, float w, float h, float radius, float amount) {
        float offset = px(3);
        // shadow() has its own downward offset; cancel that before positioning the paired lights
        c.shadow(x + offset, y + offset - radius / 2f, w, h, radius, fade(color(SOFT_SHADE), amount));
        c.shadow(x - offset, y - offset - radius / 2f, w, h, radius, fade(color(SOFT_LIGHT), amount));
    }

    public void recess(Canvas c, float x, float y, float w, float h, float radius) {
        c.fillRoundRect(x, y, w, h, radius, color(INSET));
        well(c, x, y, w, h, radius, 1f);
    }

    public void well(Canvas c, float x, float y, float w, float h, float radius, float amount) {
        c.insetShadow(x, y, w, h, radius, fade(color(SOFT_SHADE), amount), false);
        c.insetShadow(x, y, w, h, radius, fade(color(SOFT_LIGHT), amount), true);
    }

    // a minecraft item icon is 16 px of texture: draw it at a whole multiple so the pixels stay crisp, and lay
    // out with this size in the preview too so the real icons do not shift anything
    public int itemIconPx() {
        return 16 * Math.max(1, Math.round(scale));
    }

    public int px(int design) {
        return Math.max(1, Math.round(design * scale));
    }

    public float pxf(float design) {
        return design * scale;
    }

    // quarter steps so the glyph atlases stay cached while the window is dragged around, clamped so a tiny window
    // still gets readable text and a 4k one does not get a billboard. the 1234 (was 1080) is the "a bit smaller"
    // ask: a 1080p window lands on 0.875 instead of a full 1.0, and the panel shrinks with everything else
    public static float scaleFor(int windowHeightPx) {
        float raw = windowHeightPx / 1234f;
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
