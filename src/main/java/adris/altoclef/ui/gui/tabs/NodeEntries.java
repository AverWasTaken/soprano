package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.RoutinesAccess;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondPreset;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondSpec;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondTree;
import adris.altoclef.ui.gui.facade.RoutinesAccess.FieldSpec;
import adris.altoclef.ui.gui.facade.RoutinesAccess.NodeSpec;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.NodeCard;
import adris.altoclef.ui.gui.kit.Scroller;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Weight;
import adris.altoclef.ui.gui.model.CondDraft;

import java.util.ArrayList;
import java.util.List;

// what you can add: the palette and the menu on a dropped wire show the same thing, and both read it off the specs so a
// new type shows up in them by itself. a condition is an If that already has that condition in it
final class NodeEntries {

    record Entry(String label, Icon icon, String group, String type, CondTree cond) {
    }

    // title null = no section head for this row, entry null = this row is the head
    record Line(String title, Entry entry) {
    }

    static final int ROW = 27;
    static final int HEAD = 24;

    private NodeEntries() {
    }

    static List<Line> lines(RoutinesAccess a) {
        List<Line> out = new ArrayList<>();
        List<NodeSpec> specs = a.nodeSpecs();
        section(out, "Actions", specs, true);
        section(out, "Flow", specs, false);
        conditions(out, a, specs);
        return out;
    }

    private static void section(List<Line> out, String title, List<NodeSpec> specs, boolean actions) {
        boolean head = false;
        for (NodeSpec s : specs) {
            // the type with no way in is start, there's only ever the one and it can't be added
            if (s.inputs() == null || s.inputs().isEmpty() || "act".equals(s.group()) != actions) {
                continue;
            }
            if (!head) {
                out.add(new Line(title, null));
                head = true;
            }
            out.add(new Line(null, new Entry(s.label(), NodeCard.iconFor(s.type(), s.group()), s.group(), s.type(), null)));
        }
    }

    private static void conditions(List<Line> out, RoutinesAccess a, List<NodeSpec> specs) {
        NodeSpec holder = condHolder(specs);
        if (holder == null) {
            return;
        }
        out.add(new Line("Conditions", null));
        Icon icon = NodeCard.iconFor(holder.type(), holder.group());
        for (CondSpec c : a.condSpecs()) {
            out.add(new Line(null, new Entry(c.label(), icon, holder.group(), holder.type(), CondDraft.leaf(c.kind(), CondDraft.blankRow(c).args))));
        }
        for (CondPreset p : a.condPresets()) {
            out.add(new Line(null, new Entry(p.label(), icon, holder.group(), holder.type(), CondDraft.leaf(p.kind(), p.args()))));
        }
    }

    // the type whose condition is always there, whatever its mode. that's the If
    private static NodeSpec condHolder(List<NodeSpec> specs) {
        for (NodeSpec s : specs) {
            for (FieldSpec f : s.fields()) {
                if ("COND".equals(f.kind()) && f.whenMode() == null) {
                    return s;
                }
            }
        }
        return null;
    }

    static int heightOf(Theme theme, List<Line> lines) {
        int h = 0;
        for (Line l : lines) {
            h += theme.px(l.entry() == null ? HEAD : ROW);
        }
        return h;
    }

    // the entry at a y inside the list, null on a head or past the end
    static Entry at(Theme theme, List<Line> lines, float rel) {
        float cy = 0;
        for (Line l : lines) {
            float lh = theme.px(l.entry() == null ? HEAD : ROW);
            if (rel >= cy && rel < cy + lh) {
                return l.entry();
            }
            cy += lh;
        }
        return null;
    }

    static int ink(Theme theme, String group) {
        return switch (group == null ? "" : group) {
            case "act" -> theme.color(Theme.ACCENT_TEXT);
            case "flow" -> theme.color(Theme.FLOW_INK);
            default -> theme.color(Theme.TEXT_2);
        };
    }

    // the backend writes >= because it also has to survive a chat line, a card can have the real glyphs
    static String pretty(String s) {
        return s == null ? null : s.replace(">=", "\u2265").replace("<=", "\u2264");
    }

    // these lists run off the bottom on a small window with nothing else saying there's more, same thumb the steps have
    static void drawThumb(Canvas c, Theme theme, Scroller scroller, long now, boolean hovered, float right, float top, float height) {
        float vis = scroller.visibility(now, hovered);
        if (vis < 0.01f || !scroller.scrollable()) {
            return;
        }
        float trackY = top + theme.px(4);
        float trackH = height - theme.px(8);
        float th = scroller.thumbHeight(trackH, theme.px(24));
        float tt = scroller.thumbTop(trackH, theme.px(24), now);
        float bw = theme.px(Theme.SCROLLBAR_W);
        c.fillRoundRect(right - bw - theme.px(1), trackY + tt, bw, th, bw / 2f, Theme.fade(theme.color(Theme.TEXT_3), vis));
    }

    static TextStyle rowStyle(Theme theme) {
        return new TextStyle(Weight.REGULAR, theme.px(13));
    }

    // the list, from y top down. hot gets the hover fill. clipping is the caller's
    static void draw(Canvas c, Theme theme, List<Line> lines, float x, float top, float w, Entry hot, TextStyle style) {
        float cy = top;
        for (Line l : lines) {
            if (l.entry() == null) {
                TextStyle s = theme.smallLabel;
                float lh = theme.px(HEAD);
                c.text(l.title(), x + theme.px(6), cy + theme.px(6) + c.ascent(s), s, theme.color(Theme.TEXT_3));
                cy += lh;
                continue;
            }
            Entry e = l.entry();
            float rh = theme.px(ROW);
            if (e == hot) {
                c.fillRoundRect(x, cy, w, rh, theme.px(6), theme.color(Theme.HOVER));
            }
            int is = theme.px(Theme.ICON);
            c.icon(e.icon(), x + theme.px(6), cy + (rh - is) / 2f, is, ink(theme, e.group()));
            float tx = x + theme.px(6) + is + theme.px(8);
            float base = cy + rh / 2f + (c.ascent(style) - c.descent(style)) / 2f;
            c.text(c.ellipsize(e.label(), style, Math.max(0f, x + w - theme.px(6) - tx)), tx, base, style, theme.color(Theme.TEXT));
            cy += rh;
        }
    }
}
