package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.RoutinesAccess.Routine;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunState;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunStatus;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.RowWidgets;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.List;

// one saved routine: the glyph, the name with a line under it, and its own Run button. the one being edited wears the
// soft blue a selected schematic gets; the one running swaps its glyph for the status strip's dot and says which step
final class RoutineListRow extends RowWidgets.Row<Routine> {

    interface Host {
        String selectedId();

        RunStatus status();

        // how many of its steps are red, 0 when it can run
        int problems(String id);

        int count();

        void select(Routine r);

        void run(String id);

        void stop();

        // a graph file and not a list routine. the rest of the row is the same, these only matter with the editor on
        default boolean isGraph(String id) {
            return false;
        }

        // what's wrong with this graph file, null when it's fine
        default String fileError(String id) {
            return null;
        }
    }

    static final int ROW_H = 44;

    private final Host host;
    private final Button go;
    private final List<Widget> kids;
    private Routine item;
    private int index;

    RoutineListRow(Theme theme, Host host) {
        super(theme);
        this.host = host;
        go = new Button(theme, "Run", Button.Kind.SECONDARY, null, () -> {
            if (item == null) {
                return;
            }
            if (running()) {
                host.stop();
            } else {
                host.run(item.id());
            }
        });
        go.setFocusable(false);
        kids = List.of(go);
    }

    @Override
    public void bind(Routine r, int idx) {
        item = r;
        index = idx;
    }

    Button goButton() {
        return go;
    }

    Routine item() {
        return item;
    }

    private boolean mine(RunStatus st) {
        return item != null && item.id() != null && item.id().equalsIgnoreCase(st.id());
    }

    boolean running() {
        RunStatus st = host.status();
        return mine(st) && st.state() == RunState.RUNNING;
    }

    // why the row's Run is grey, for the tooltip. null when it isn't
    String blocked() {
        if (item == null || running()) {
            return null;
        }
        if (host.isGraph(item.id())) {
            return blockedGraph();
        }
        int bad = host.problems(item.id());
        if (bad > 0) {
            return bad + (bad == 1 ? " step needs" : " steps need") + " fixing before it can run";
        }
        if (item.steps() == null || item.steps().isEmpty()) {
            return "It has no steps yet";
        }
        return null;
    }

    private String blockedGraph() {
        if (host.fileError(item.id()) != null) {
            return "This file has an error, so it can't run";
        }
        int bad = host.problems(item.id());
        return bad > 0 ? bad + (bad == 1 ? " node needs" : " nodes need") + " fixing before it can run" : null;
    }

    @Override
    protected void layout() {
        int bw = theme.px(60);
        int bh = theme.px(28);
        go.setBounds(x + w - theme.px(Theme.SPACE_2) - bw, y + (h - bh) / 2, bw, bh);
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        if (super.mouseDown(mx, my, button, mods)) {
            return true;
        }
        if (button == 0 && item != null) {
            host.select(item);
        }
        return true;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        if (item == null) {
            return;
        }
        RunStatus st = host.status();
        boolean running = mine(st) && st.state() == RunState.RUNNING;
        boolean failed = mine(st) && st.state() == RunState.FAILED;
        int bad = host.problems(item.id());
        boolean graph = host.isGraph(item.id());
        boolean brokenFile = graph && host.fileError(item.id()) != null;
        boolean sel = item.id() != null && item.id().equalsIgnoreCase(host.selectedId());
        boolean first = index == 0;
        boolean last = index == host.count() - 1;
        if (sel) {
            ListCard.fill(theme, c, x, y, w, h, first, last, theme.color(Theme.ACCENT_SOFT));
        } else if (hovered) {
            ListCard.fill(theme, c, x, y, w, h, first, last, theme.color(Theme.HOVER));
        }
        ListCard.separator(theme, c, x, y, w, index);

        int pad = theme.px(Theme.PAD);
        int is = theme.px(Theme.ICON);
        if (running) {
            int ds = theme.px(12);
            c.icon(Icon.DOT, x + pad + (is - ds) / 2f, y + (h - ds) / 2f, ds, theme.color(Theme.ACCENT));
        } else if (failed || bad > 0 || brokenFile) {
            c.icon(Icon.WARNING, x + pad, y + (h - is) / 2f, is, theme.color(Theme.DANGER));
        } else {
            c.icon(graph ? Icon.NODES : Icon.ROUTINE, x + pad, y + (h - is) / 2f, is, theme.color(sel ? Theme.ACCENT_TEXT : Theme.TEXT_3));
        }

        int n = item.steps() == null ? 0 : item.steps().size();
        String count = n + (n == 1 ? " step" : " steps");
        String sub;
        int subCol;
        if (running) {
            // a graph can go round, "step 40/5" is no use to anybody
            sub = graph ? "Running" : "Running step " + st.step() + "/" + st.total();
            subCol = Theme.ACCENT_TEXT;
        } else if (failed) {
            sub = graph ? "Failed" : "Failed at step " + st.step() + "/" + st.total();
            subCol = Theme.DANGER;
        } else if (brokenFile) {
            sub = "This file has an error";
            subCol = Theme.DANGER;
        } else if (graph && bad > 0) {
            sub = bad + (bad == 1 ? " node needs" : " nodes need") + " fixing";
            subCol = Theme.DANGER;
        } else if (graph) {
            String d = item.description() == null ? "" : item.description().trim();
            sub = d.isEmpty() ? "Nodes" : "Nodes · " + d;
            subCol = Theme.TEXT_3;
        } else if (bad > 0) {
            sub = count + " · " + bad + (bad == 1 ? " needs" : " need") + " fixing";
            subCol = Theme.DANGER;
        } else {
            String d = item.description() == null ? "" : item.description().trim();
            sub = d.isEmpty() ? count : count + " · " + d;
            subCol = Theme.TEXT_3;
        }

        TextStyle title = theme.label;
        TextStyle small = theme.small.asTabular();
        float lhTitle = c.lineHeight(title);
        float lhSmall = c.lineHeight(small);
        float gap = theme.px(2);
        float top = y + (h - (lhTitle + gap + lhSmall)) / 2f;
        float tx = x + pad + is + theme.px(Theme.SPACE_3);
        float room = Math.max(theme.px(40), go.x() - theme.px(Theme.GAP) - tx);
        String name = item.name() == null || item.name().isBlank() ? item.id() : item.name();
        c.text(c.ellipsize(name, title, room), tx, top + c.ascent(title), title, theme.color(Theme.TEXT));
        c.text(c.ellipsize(sub, small, room), tx, top + lhTitle + gap + c.ascent(small), small, theme.color(subCol));

        go.setText(running ? "Stop" : "Run");
        go.setKind(running ? Button.Kind.DANGER : Button.Kind.SECONDARY);
        go.setEnabled(running || blocked() == null);
        go.draw(c, f);
    }
}
