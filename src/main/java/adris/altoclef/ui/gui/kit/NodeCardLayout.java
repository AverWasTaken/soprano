package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// a card's text, worked out once. line breaks and the card's height are fixed at zoom 1, otherwise a card would grow a
// line when you zoom out because 9 px text happens to wrap differently. each zoom only ellipsizes those same lines
final class NodeCardLayout {

    // one zoom's worth of already measured strings, no measuring per frame
    static final class AtZoom {
        TextStyle title;
        TextStyle small;
        TextStyle smallLabel;
        String titleText;
        String[] lines;
        String why;
        String[] portNames;
        float[] portWidths;
        float badgeWidth;
    }

    final NodeCard card;
    final List<String> lines;
    final int height;
    final int bodyBottom;
    final boolean itemRow;
    private final float summaryWidth;
    private final AtZoom[] zooms = new AtZoom[CanvasView.steps()];

    NodeCardLayout(Theme theme, NodeCard card) {
        this.card = card;
        itemRow = card.itemIds().size() > 1;
        summaryWidth = summaryWidth(theme, card);
        lines = wrap(theme, card.summary(), summaryWidth);
        boolean why = card.why() != null && !card.why().isEmpty();
        bodyBottom = NodeGeometry.bodyBottom(card.outs().size(), lines.size(), itemRow);
        height = bodyBottom + (why ? NodeGeometry.WHY : 0);
    }

    // anything text decides is in here. x, y and state change all the time and cost nothing
    boolean sameText(NodeCard other) {
        NodeCard a = card;
        return a == other || a.small() == other.small()
                && Objects.equals(a.title(), other.title())
                && Objects.equals(a.summary(), other.summary())
                && a.itemIds().size() == other.itemIds().size()
                && a.outs().size() == other.outs().size()
                && sameNames(a, other)
                && a.icon() == other.icon()
                && a.badgeIcon() == other.badgeIcon()
                && Objects.equals(a.badgeText(), other.badgeText())
                && Objects.equals(a.why(), other.why());
    }

    private static boolean sameNames(NodeCard a, NodeCard b) {
        for (int i = 0; i < a.outs().size(); i++) {
            if (!a.outs().get(i).name().equals(b.outs().get(i).name())) {
                return false;
            }
        }
        return true;
    }

    // device px at zoom 1, what's left beside the port names
    private static float summaryWidth(Theme theme, NodeCard card) {
        float s = theme.scale;
        float room = (NodeGeometry.width(card.small()) - NodeGeometry.TEXT_LEFT - NodeGeometry.TEXT_RIGHT) * s;
        if (!card.outs().isEmpty()) {
            float names = 0f;
            if (card.outs().size() > 1) {
                for (NodeCard.Port p : card.outs()) {
                    names = Math.max(names, theme.measurer.measure(p.name(), theme.small));
                }
            }
            room -= names + NodeGeometry.PORT_COLUMN_RIGHT * s;
        }
        if (card.itemIds().size() == 1) {
            // one item: texture and line side by side, a row of one icon reads as a typo
            room -= (NodeGeometry.ITEM + NodeGeometry.ITEM_GAP) * s;
        }
        return room;
    }

    private static List<String> wrap(Theme theme, String summary, float width) {
        List<String> wrapped = TextWrap.wrap(theme.measurer, summary, theme.small, Math.max(1f, width));
        if (wrapped.size() <= NodeGeometry.MAX_LINES) {
            return wrapped;
        }
        List<String> out = new ArrayList<>(NodeGeometry.MAX_LINES);
        out.add(wrapped.get(0));
        String rest = String.join(" ", wrapped.subList(1, wrapped.size()));
        out.add(theme.measurer.ellipsize(rest, theme.small, width));
        return out;
    }

    // true when this zoom shows any text at all. 9 is where inter stops being letters and starts being texture
    static boolean showsText(float scale, float zoom) {
        return zoom >= 0.75f && Math.round(12 * scale * zoom) >= 9;
    }

    // whole multiples of 16 only, a scaled item texture is a blurry one. 0 means no icons at this zoom
    static int itemSize(float scale, float zoom) {
        if (zoom < 0.75f) {
            return 0;
        }
        return (int) Math.floor(22 * scale * zoom / 16f) * 16;
    }

    AtZoom at(Theme theme, int zoomIndex) {
        AtZoom a = zooms[zoomIndex];
        if (a == null) {
            a = build(theme, CanvasView.zoomOf(zoomIndex));
            zooms[zoomIndex] = a;
        }
        return a;
    }

    private AtZoom build(Theme theme, float zoom) {
        float k = theme.scale * zoom;
        TextMeasurer m = theme.measurer;
        AtZoom a = new AtZoom();
        a.title = new TextStyle(Weight.MEDIUM, Math.max(1, Math.round(13 * k)));
        a.small = new TextStyle(Weight.REGULAR, Math.max(1, Math.round(12 * k)));
        a.smallLabel = new TextStyle(Weight.MEDIUM, Math.max(1, Math.round(12 * k)));
        a.badgeWidth = badgeWidth(a, m, k);
        float titleRoom = (NodeGeometry.width(card.small()) - 34 - 8) * k - (a.badgeWidth > 0 ? a.badgeWidth + 8 * k : 0);
        a.titleText = card.title() == null ? "" : m.ellipsize(card.title(), a.title, titleRoom);
        // the size rounds, so a zoom's text can come out wider than the zoom's box. that's the only reason to cut here
        a.lines = new String[lines.size()];
        for (int i = 0; i < a.lines.length; i++) {
            a.lines[i] = m.ellipsize(lines.get(i), a.small, summaryWidth * zoom);
        }
        a.why = card.why() == null || card.why().isEmpty() ? null
                : m.ellipsize(card.why(), a.small, (NodeGeometry.width(card.small()) - 2 * NodeGeometry.TEXT_LEFT) * k);
        if (card.outs().size() > 1) {
            a.portNames = new String[card.outs().size()];
            a.portWidths = new float[a.portNames.length];
            for (int i = 0; i < a.portNames.length; i++) {
                a.portNames[i] = card.outs().get(i).name();
                a.portWidths[i] = m.measure(a.portNames[i], a.small);
            }
        }
        return a;
    }

    private float badgeWidth(AtZoom a, TextMeasurer m, float k) {
        if (card.badgeText() != null && !card.badgeText().isEmpty()) {
            return m.measure(card.badgeText(), a.smallLabel) + 12 * k;
        }
        return card.badgeIcon() != null ? 16 * k : 0f;
    }

    float summaryWidthAt(float zoom) {
        return summaryWidth * zoom;
    }
}
