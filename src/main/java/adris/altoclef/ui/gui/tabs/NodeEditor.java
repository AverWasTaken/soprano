package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.RoutinesAccess;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondTree;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Graph;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphCheck;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphEdge;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphNode;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphRunStatus;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphSaveResult;
import adris.altoclef.ui.gui.facade.RoutinesAccess.NodeCheck;
import adris.altoclef.ui.gui.facade.RoutinesAccess.NodeSpec;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Routine;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunState;
import adris.altoclef.ui.gui.facade.RoutinesAccess.RunStatus;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Step;
import adris.altoclef.ui.gui.facade.RoutinesAccess.StepType;
import adris.altoclef.ui.gui.kit.AutoArrange;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.CanvasView;
import adris.altoclef.ui.gui.kit.Dropdown;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.NodeCanvas;
import adris.altoclef.ui.gui.kit.NodeCard;
import adris.altoclef.ui.gui.kit.NodeGeometry;
import adris.altoclef.ui.gui.kit.NodeWire;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Segmented;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.GraphDraft;
import adris.altoclef.ui.gui.model.RoutineDraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// the commands tab's nodes view: the head row, the canvas, and the palette, inspector and zoom floating on it. this owns
// the draft and answers the canvas's Model. the cards are worked out when something changes, not per frame, because the
// list's identity is how the canvas knows to redo its own work
final class NodeEditor extends Widget implements NodeCanvas.Model, NodeCanvas.Listener, NodeInspector.Host, NodePalette.Host {

    interface Host {
        RoutinesAccess routines();

        ShellContext ctx();

        ItemEntry item(String id);

        List<String> waypoints();

        // every routine and graph, for name clashes and the Run routine node's picker
        List<Routine> all();

        // the same, through the search box
        List<Routine> shown();

        boolean isFile(String id);

        // what's wrong with this file, null when it's fine
        String fileError(String id);

        int problems(String id);

        RunStatus status();

        boolean locked();

        void savedGraph(String id);

        void run(String id);

        void stop();

        // the picker asked for a routine, null = a new one. the unsaved edits question was already asked
        void pick(String id);

        // List on the switch, the id is the routine to show there
        void showList(String id);

        void deletedGraph(String id);
    }

    // design sizes, the mock's head row and the room the floats leave
    private static final int PAD = 16;
    private static final int PICK_W = 52;
    private static final int NAME_W = 176;
    private static final int BTN_W = 64;
    private static final int CANVAS_TOP = 58;
    private static final int FLOAT = 10;
    private static final int BAR_H = 36;
    private static final int NEXT_X = 48;
    private static final int STALE_TICKS = 20;
    private static final String NEW = "New command";
    private static final Set<String> SMALL = Set.of("start", "stop");

    private final Host host;
    private final NodeSession session;
    private final Dropdown picker;
    private final StepField nameField;
    private final StepField descField;
    private final Segmented viewSwitch;
    private final Button saveBtn;
    private final Button runBtn;
    private final NodeCanvas canvas;
    private final NodePalette palette;
    private final NodeInspector inspector;
    private final Bar bar;
    private final List<NodeEntries.Line> entries;
    private final List<Widget> kidsSel;
    private final List<Widget> kidsNoSel;
    private final List<Widget> kidsLocked;
    private final List<Widget> kidsLockedNoSel;
    private List<String> pickerIds = List.of();

    private GraphDraft draft;
    private GraphCheck check = new GraphCheck(true, List.of(), Map.of());
    private List<NodeCard> cards = List.of();
    private List<NodeWire> wires = List.of();
    private String selCard;
    private String selWire;
    private String pendingClear;
    private boolean pendingFit;
    private boolean floatFocus;
    private String msgNote;
    private RunStatus lastStatus = RoutinesAccess.IDLE_STATUS;
    private GraphRunStatus lastGraphStatus = RoutinesAccess.IDLE_GRAPH_STATUS;
    private int staleTicks;
    private boolean lastReadOnly;
    private float msgX;
    private float msgY;
    private float msgW;
    private float msgH;

    NodeEditor(Theme theme, Host host, NodeSession session) {
        super(theme);
        this.host = host;
        this.session = session;
        ShellContext ctx = host.ctx();
        picker = new PickerDropdown(theme, ctx);
        picker.setMinMenuWidth(theme.px(240));
        nameField = new StepField(theme, "Name, like Wood loop", StepField.Lead.NONE, null, null);
        nameField.edit().setMaxLength(64);
        nameField.setOnChange(t -> {
            draft.setName(t);
            soft();
        });
        descField = new StepField(theme, "Description, optional", StepField.Lead.NONE, null, null);
        descField.edit().setMaxLength(120);
        descField.setOnChange(t -> {
            draft.setDescription(t);
            soft();
        });
        viewSwitch = new Segmented(theme, List.of("List", "Nodes"), 1, this::viewPicked);
        saveBtn = new Button(theme, "Save", Button.Kind.SECONDARY, null, this::save);
        runBtn = new Button(theme, "Run", Button.Kind.PRIMARY, null, this::runClicked);
        canvas = new NodeCanvas(theme, this, this);
        entries = NodeEntries.lines(host.routines());
        palette = new NodePalette(theme, entries, this);
        inspector = new NodeInspector(theme, this);
        bar = new Bar();
        kidsNoSel = List.of(picker, nameField, descField, viewSwitch, saveBtn, runBtn, canvas, palette, bar);
        kidsSel = List.of(picker, nameField, descField, viewSwitch, saveBtn, runBtn, canvas, palette, inspector, bar);
        kidsLocked = List.of(picker, nameField, descField, viewSwitch, saveBtn, runBtn, canvas, inspector, bar);
        kidsLockedNoSel = List.of(picker, nameField, descField, viewSwitch, saveBtn, runBtn, canvas, bar);
    }

    // ----- opening things

    // a routine or a graph file, null = a blank one
    void open(String id) {
        GraphDraft next;
        String err = id == null ? null : host.fileError(id);
        if (err != null) {
            next = GraphDraft.badFile(host.routines(), id, nameOf(id), err);
        } else {
            Graph g = id == null ? null : host.routines().loadGraph(id);
            next = g == null ? GraphDraft.blank(host.routines()) : GraphDraft.open(host.routines(), g, host.isFile(id));
        }
        selCard = null;
        selWire = null;
        msgNote = null;
        setDraft(next);
        // the palette folds away on a routine with nodes (the canvas needs the room), opens on an empty one
        palette.setOpen(draft.nodes().size() < 2 && !readOnly());
        session.paletteOpen = palette.open();
        pendingFit = true;
        structure();
        placeFloats();
    }

    // the same one again after a save or a change on disk: selection and view stay where they are
    private void reload(String id) {
        String err = id == null ? null : host.fileError(id);
        if (err != null) {
            open(id);
            return;
        }
        Graph g = id == null ? null : host.routines().loadGraph(id);
        if (g == null) {
            return;
        }
        setDraft(GraphDraft.open(host.routines(), g, host.isFile(id)));
        if (selCard != null && draft.node(selCard) == null) {
            selCard = null;
        }
        selWire = null;
        structure();
    }

    // a gui rebuild made this editor new, the draft and the view carry on from the session
    void restore() {
        selCard = session.selectedCard;
        selWire = session.selectedWire;
        setDraft(session.draft);
        palette.setOpen(session.paletteOpen);
        if (session.viewSet) {
            canvas.view().restore(session.panX, session.panY, session.zoomIndex);
        } else {
            pendingFit = true;
        }
        structure();
        placeFloats();
    }

    private void setDraft(GraphDraft d) {
        draft = d;
        session.draft = d;
        nameField.setText(d.name());
        descField.setText(d.description());
        lastReadOnly = !readOnly();
    }

    private String nameOf(String id) {
        for (Routine r : host.all()) {
            if (id.equalsIgnoreCase(r.id())) {
                return r.name();
            }
        }
        return id;
    }

    GraphDraft currentDraft() {
        return draft;
    }

    boolean dirty() {
        return draft != null && draft.badFile == null && draft.dirty();
    }

    void onHidden() {
        canvas.cancelPress();
        palette.cancel();
    }

    // ----- what changed

    // a value: words, cards, wires
    @Override
    public void soft() {
        msgNote = null;
        validate();
        rebuildCards();
        inspector.soft();
    }

    // the shape: the inspector's boxes too
    @Override
    public void hard() {
        structure();
    }

    private void structure() {
        msgNote = null;
        validate();
        rebuildCards();
        syncSession();
        inspector.show(selCard);
        placeFloats();
        syncPicker();
    }

    private void validate() {
        if (draft.badFile != null) {
            check = new GraphCheck(false, List.of(draft.badFile), Map.of());
            return;
        }
        GraphCheck c = null;
        try {
            c = host.routines().validateGraph(draft.toGraph());
        } catch (Throwable t) {
            Safe.report("graphs validate", t);
        }
        check = c == null ? new GraphCheck(false, List.of("checking broke, the log has the details"), Map.of()) : c;
    }

    private void syncSession() {
        session.selectedCard = selCard;
        session.selectedWire = selWire;
    }

    @Override
    public NodeCheck checkOf(String id) {
        return check.nodes() == null ? null : check.nodes().get(id);
    }

    private int badNodes() {
        int bad = 0;
        if (check.nodes() != null) {
            for (NodeCheck c : check.nodes().values()) {
                if (c.error() != null) {
                    bad++;
                }
            }
        }
        return bad;
    }

    // ----- the run, as the cards see it

    private enum RunKind { NONE, GRAPH, LIST }

    // only a clean draft gets the highlight: edit one thing and the nodes aren't the ones that ran any more
    private RunKind runKind() {
        if (draft == null || draft.id == null || draft.dirty() || draft.badFile != null) {
            return RunKind.NONE;
        }
        if (draft.file) {
            return lastGraphStatus.state() != RunState.IDLE && draft.id.equalsIgnoreCase(lastGraphStatus.id()) ? RunKind.GRAPH : RunKind.NONE;
        }
        return lastStatus.state() != RunState.IDLE && draft.id.equalsIgnoreCase(lastStatus.id()) ? RunKind.LIST : RunKind.NONE;
    }

    // not the highlight's rule: edits or no edits it's still running, and Stop is what the button has to say then
    private boolean runningMine() {
        return draft != null && draft.id != null && lastStatus.state() == RunState.RUNNING && draft.id.equalsIgnoreCase(lastStatus.id());
    }

    // what the cards show during a run. a graph run says it all itself, a list run only has a step number and the chain
    private record Shown(Set<String> active, Set<String> done, Set<String> warned, Set<String> failed, Set<String> taken, Map<String, Integer> laps, boolean live) {
        static final Shown NOTHING = new Shown(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), false);
    }

    private Shown shown() {
        return switch (runKind()) {
            case NONE -> Shown.NOTHING;
            case GRAPH -> shownGraph();
            case LIST -> shownList();
        };
    }

    private Shown shownGraph() {
        GraphRunStatus g = lastGraphStatus;
        Set<String> active = g.activeNode() == null ? Set.of() : Set.of(g.activeNode());
        Set<String> failed = g.failedNode() == null ? Set.of() : Set.of(g.failedNode());
        Set<String> done = new HashSet<>();
        Set<String> warned = new HashSet<>();
        Map<String, String> outcomes = g.outcomes() == null ? Map.of() : g.outcomes();
        outcomes.forEach((node, how) -> {
            done.add(node);
            if ("FAILED".equals(how) || "TIMEOUT".equals(how)) {
                warned.add(node);
            }
        });
        if (g.recent() != null) {
            done.addAll(g.recent());
        }
        return new Shown(active, done, warned, failed, g.taken() == null ? Set.of() : g.taken(),
                g.repeats() == null ? Map.of() : g.repeats(), g.state() == RunState.RUNNING);
    }

    // RunStatus.step is 1 based, the nth node along the chain
    private Shown shownList() {
        RunStatus st = lastStatus;
        List<String> chain = draft.chain();
        int step = st.step();
        Set<String> active = new HashSet<>();
        Set<String> done = new HashSet<>();
        Set<String> failed = new HashSet<>();
        for (int i = 0; i < chain.size(); i++) {
            int k = i + 1;
            if (st.state() == RunState.RUNNING && k == step) {
                active.add(chain.get(i));
            } else if (st.state() == RunState.FAILED && k == step) {
                failed.add(chain.get(i));
            } else if (k < step || st.state() == RunState.DONE) {
                done.add(chain.get(i));
            }
        }
        GraphNode s = draft.start();
        if (s != null) {
            done.add(s.id());
        }
        Set<String> taken = new HashSet<>();
        for (GraphEdge e : draft.edges()) {
            if (done.contains(e.from()) && (done.contains(e.to()) || active.contains(e.to()) || failed.contains(e.to()))) {
                taken.add(GraphDraft.keyOf(e));
            }
        }
        return new Shown(active, done, Set.of(), failed, taken, Map.of(), st.state() == RunState.RUNNING);
    }

    private void rebuildCards() {
        Shown run = shown();
        List<NodeCard> nextCards = new ArrayList<>(draft.nodes().size());
        for (GraphNode n : draft.nodes()) {
            nextCards.add(cardOf(n, run));
        }
        List<NodeWire> nextWires = new ArrayList<>(draft.edges().size());
        for (GraphEdge e : draft.edges()) {
            boolean ran = run.taken().contains(GraphDraft.keyOf(e));
            nextWires.add(new NodeWire(e.from(), e.port(), e.to(), e.toPort(), ran, run.live() && !ran));
        }
        cards = nextCards;
        wires = nextWires;
    }

    private NodeCard cardOf(GraphNode n, Shown run) {
        NodeSpec spec = draft.spec(n.type());
        String group = spec == null ? "end" : spec.group();
        List<NodeCard.Port> outs = new ArrayList<>();
        for (String p : draft.outputsOf(n)) {
            outs.add(new NodeCard.Port(p, draft.wired(n.id(), p)));
        }
        boolean hasIn = spec == null || spec.inputs() == null || !spec.inputs().isEmpty();
        NodeCheck nc = checkOf(n.id());
        String err = nc == null ? null : nc.error();
        String warning = nc == null ? null : nc.warning();
        boolean inRun = run != Shown.NOTHING;
        String why = !inRun && err != null ? sentence(err) : warning;
        boolean warn = why != null && why.equals(warning) && (inRun || err == null);
        NodeCard.State state = stateOf(n, run, !inRun && err != null);
        Integer laps = run.laps().get(n.id());
        String summary = host.routines().summaryOf(n);
        return new NodeCard(n.id(), n.x(), n.y(), isSmall(n), titleOf(n, spec), NodeCard.iconFor(n.type(), group), group,
                n.cond() != null ? NodeEntries.pretty(summary) : summary, itemIds(n), outs, hasIn, draft.inWired(n.id()), state,
                laps != null && laps > 0 ? lapText(n, laps) : null, badgeOf(state, run.warned().contains(n.id())), why, warn);
    }

    private static NodeCard.State stateOf(GraphNode n, Shown run, boolean bad) {
        if (run.active().contains(n.id())) {
            return NodeCard.State.ACTIVE;
        }
        if (run.failed().contains(n.id())) {
            return NodeCard.State.FAILED;
        }
        if (run.done().contains(n.id())) {
            return NodeCard.State.DONE;
        }
        return bad ? NodeCard.State.BAD : NodeCard.State.IDLE;
    }

    private static Icon badgeOf(NodeCard.State state, boolean warned) {
        return switch (state) {
            case ACTIVE -> Icon.DOT;
            case FAILED, BAD -> Icon.WARNING;
            case DONE -> warned ? Icon.WARNING : Icon.CHECK;
            case IDLE -> null;
        };
    }

    // "3/20" while it has a limit, a forever loop just counts
    private static String lapText(GraphNode n, int laps) {
        String mode = n.params().getOrDefault("mode", "");
        String cap = switch (mode) {
            case "times" -> n.params().getOrDefault("n", "");
            case "until" -> n.params().getOrDefault("cap", "");
            default -> "";
        };
        return cap.isBlank() ? String.valueOf(laps) : laps + "/" + cap;
    }

    private static boolean isSmall(GraphNode n) {
        return SMALL.contains(n.type());
    }

    private int widthOf(GraphNode n) {
        return NodeGeometry.width(isSmall(n));
    }

    private String titleOf(GraphNode n, NodeSpec spec) {
        String label = spec == null ? n.type() : spec.label();
        if (NodeInspector.stepTypeOf(n.type()) != StepType.GOTO) {
            return label;
        }
        // a goto's card says which of the three it is, the mock's "Go to block"
        String line = n.params().getOrDefault("line", "");
        if (line.isBlank()) {
            return label;
        }
        Map<String, String> f = safeFields(StepType.GOTO, line);
        return f.containsKey("waypoint") ? label + " waypoint" : f.containsKey("block") ? label + " block" : label + " coords";
    }

    private List<String> itemIds(GraphNode n) {
        if (NodeInspector.stepTypeOf(n.type()) != StepType.GET) {
            return List.of();
        }
        String line = n.params().getOrDefault("line", "");
        if (line.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (RoutineDraft.Item it : RoutineDraft.parseItems(safeFields(StepType.GET, line).get("items"))) {
            ItemEntry e = host.item(it.id);
            if (e != null) {
                out.add(e.iconItemId());
            }
            if (out.size() == 7) {
                break;
            }
        }
        return out;
    }

    private Map<String, String> safeFields(StepType type, String line) {
        Map<String, String> f = host.routines().fieldsOf(new Step(type, line));
        return f == null ? Map.of() : f;
    }

    // ----- NodeCanvas.Model

    @Override
    public List<NodeCard> cards() {
        return cards;
    }

    @Override
    public List<NodeWire> wires() {
        return wires;
    }

    @Override
    public String selectedCard() {
        return selCard;
    }

    @Override
    public String selectedWire() {
        return selWire;
    }

    @Override
    public boolean readOnly() {
        return host.locked() || draft == null || draft.badFile != null;
    }

    // ----- NodeCanvas.Listener

    @Override
    public void select(String cardId) {
        selCard = cardId;
        selWire = null;
        // not yet: this is the press, sliding the view now would yank a card that's about to get dragged
        pendingClear = cardId;
        syncSession();
        inspector.show(selCard);
        placeFloats();
    }

    @Override
    public void move(String cardId, int x, int y) {
        pendingClear = null;
        draft.move(cardId, x, y);
        soft();
    }

    @Override
    public void connect(String from, String port, String to) {
        if (draft.connect(from, port, to)) {
            selCard = null;
            selWire = null;
            structure();
        }
    }

    @Override
    public void dropAt(String from, String port, float worldX, float worldY) {
        AddNodeMenu menu = new AddNodeMenu(theme, entries, canvas.view().screenX(worldX), canvas.view().screenY(worldY),
                canvas.x(), canvas.y(), canvas.w(), canvas.h(), e -> {
            host.ctx().closeOverlay();
            String id = addEntry(e, NodeGeometry.snap(worldX), NodeGeometry.snap(worldY - NodeGeometry.inPortY()));
            draft.connect(from, port, id);
            selectNew(id);
        });
        host.ctx().openOverlay(menu);
    }

    @Override
    public void wireClicked(String wireKey) {
        selWire = wireKey;
        selCard = null;
        pendingClear = null;
        syncSession();
        inspector.show(null);
        placeFloats();
    }

    @Override
    public void deleteSelected() {
        if (selWire != null) {
            draft.disconnect(selWire);
            selWire = null;
        } else if (selCard != null) {
            // start says no, and then nothing's changed
            if (!draft.remove(selCard)) {
                return;
            }
            selCard = null;
        }
        structure();
    }

    @Override
    public void viewChanged() {
        session.viewSet = true;
        session.panX = canvas.view().panX();
        session.panY = canvas.view().panY();
        session.zoomIndex = canvas.view().zoomIndex();
    }

    // ----- NodeInspector.Host

    @Override
    public GraphDraft draft() {
        return draft;
    }

    @Override
    public RoutinesAccess routines() {
        return host.routines();
    }

    @Override
    public ShellContext ctx() {
        return host.ctx();
    }

    @Override
    public ItemEntry item(String id) {
        return host.item(id);
    }

    @Override
    public List<String> waypoints() {
        return host.waypoints();
    }

    @Override
    public List<Routine> all() {
        return host.all();
    }

    @Override
    public void close() {
        select(null);
        pendingClear = null;
    }

    @Override
    public void deleteNode() {
        selWire = null;
        deleteSelected();
    }

    @Override
    public void pressed() {
        canvas.pressedElsewhere();
    }

    @Override
    public void confirm(String title, String body, List<NodeConfirm.Choice> choices) {
        host.ctx().openOverlay(new NodeConfirm(theme, host.ctx(), title, body, choices, x + w / 2, y + h / 2));
    }

    // ----- adding, NodePalette.Host

    private String addEntry(NodeEntries.Entry e, int wx, int wy) {
        String id = draft.add(e.type(), wx, wy);
        CondTree cond = e.cond();
        if (cond != null) {
            draft.setCond(id, cond);
        }
        return id;
    }

    private void selectNew(String id) {
        selCard = id;
        selWire = null;
        structure();
        ensureVisible(id);
    }

    // after the selected node on its first free port, like n8n's plus. no selection, or no room on it: the middle of the view
    @Override
    public void clicked(NodeEntries.Entry e) {
        if (readOnly()) {
            return;
        }
        GraphNode s = selCard == null ? null : draft.node(selCard);
        String port = s == null ? null : draft.firstFreePort(s.id());
        if (s != null && port != null) {
            int wx = s.x() + widthOf(s) + NEXT_X;
            int wy = s.y() + NodeGeometry.ROW * Math.max(0, draft.outputsOf(s).indexOf(port));
            while (overlaps(s, wx, wy)) {
                wy += 104;
            }
            String id = addEntry(e, wx, wy);
            draft.connect(s.id(), port, id);
            selectNew(id);
            return;
        }
        float[] mid = freeMiddle();
        selectNew(addEntry(e, NodeGeometry.snap(mid[0] - 88), NodeGeometry.snap(mid[1] - 40)));
    }

    private boolean overlaps(GraphNode besides, int wx, int wy) {
        for (GraphNode n : draft.nodes()) {
            if (n != besides && Math.abs(n.x() - wx) < 160 && Math.abs(n.y() - wy) < 80) {
                return true;
            }
        }
        return false;
    }

    private float[] freeMiddle() {
        CanvasView.Rect fr = freeRect(false);
        return canvas.screenToWorld(canvas.x() + fr.x() + fr.w() / 2f, canvas.y() + fr.y() + fr.h() / 2f);
    }

    @Override
    public void dropped(NodeEntries.Entry e, float mx, float my) {
        if (readOnly() || !canvas.contains(mx, my) || overFloat(mx, my)) {
            return;
        }
        float[] world = canvas.screenToWorld(mx, my);
        selectNew(addEntry(e, NodeGeometry.snap(world[0] - 20), NodeGeometry.snap(world[1] - 15)));
    }

    @Override
    public void foldChanged() {
        session.paletteOpen = palette.open();
        placeFloats();
    }

    // ----- moving the view

    private CanvasView.Rect freeRect(boolean noInspector) {
        float s = theme.scale;
        float left = palette.open() && !readOnly() ? FLOAT + NodePalette.W + 16 : 64;
        float right = !noInspector && selCard != null ? NodeInspector.W + 26 : 16;
        float cw = canvas.w();
        float ch = canvas.h();
        return new CanvasView.Rect(left * s, 16 * s, Math.max(100 * s, cw - (left + right) * s), ch - (16 + 56) * s);
    }

    private void fit() {
        canvas.fit(freeRect(false), freeRect(true));
        GraphNode n = selCard == null ? null : draft.node(selCard);
        if (n != null) {
            clearInspector(n);
        }
    }

    // a selected node under the inspector slides left until it's clear, nothing else moves
    private void clearInspector(GraphNode n) {
        if (selCard == null) {
            return;
        }
        CanvasView view = canvas.view();
        float edge = canvas.x() + canvas.w() - (NodeInspector.W + 26) * theme.scale;
        float right = view.screenX(n.x() + widthOf(n));
        if (right > edge) {
            view.panBy(edge - right, 0);
            viewChanged();
        }
    }

    private void focusNode(String id) {
        GraphNode n = draft.node(id);
        if (n == null) {
            return;
        }
        selCard = id;
        selWire = null;
        syncSession();
        inspector.show(id);
        placeFloats();
        CanvasView view = canvas.view();
        CanvasView.Rect fr = freeRect(false);
        float s = theme.scale;
        float panX = (fr.x() + fr.w() / 2f) / s - (n.x() + widthOf(n) / 2f) * view.zoom();
        float panY = (fr.y() + fr.h() / 2f) / s - (n.y() + 40) * view.zoom();
        view.restore(panX, panY, view.zoomIndex());
        viewChanged();
    }

    private void ensureVisible(String id) {
        GraphNode n = draft.node(id);
        if (n == null) {
            return;
        }
        CanvasView view = canvas.view();
        CanvasView.Rect fr = freeRect(false);
        float sx = view.screenX(n.x()) - canvas.x();
        float sy = view.screenY(n.y()) - canvas.y();
        float k = view.k();
        if (sx < fr.x() || sx + widthOf(n) * k > fr.x() + fr.w() || sy < fr.y() || sy + 90 * k > fr.y() + fr.h()) {
            focusNode(id);
        }
    }

    private void arrange() {
        GraphNode s = draft.start();
        if (s == null || readOnly()) {
            return;
        }
        Map<String, NodeCard> byId = new HashMap<>();
        for (NodeCard c : cards) {
            byId.put(c.id(), c);
        }
        List<AutoArrange.Node> nodes = new ArrayList<>();
        for (GraphNode n : draft.nodes()) {
            nodes.add(new AutoArrange.Node(n.id(), canvas.cardHeight(byId.get(n.id()))));
        }
        List<AutoArrange.Edge> edges = new ArrayList<>();
        for (GraphEdge e : draft.edges()) {
            edges.add(new AutoArrange.Edge(e.from(), e.to(), "again".equals(e.toPort())));
        }
        for (AutoArrange.Placed p : AutoArrange.arrange(s.id(), nodes, edges)) {
            draft.move(p.id(), p.x(), p.y());
        }
        soft();
        fit();
    }

    // ----- the head row's actions

    private String shownId() {
        return draft.shownId();
    }

    private boolean clash() {
        String id = shownId();
        if (id == null) {
            return false;
        }
        for (Routine r : host.all()) {
            if (r.id() != null && r.id().equalsIgnoreCase(id) && (draft.id == null || !r.id().equalsIgnoreCase(draft.id))) {
                return true;
            }
        }
        return false;
    }

    private boolean canSave() {
        return !readOnly() && draft.dirty() && shownId() != null && !clash();
    }

    // why Run is grey, or null when it isn't. the tab's tip says it
    String runBlocked() {
        if (runningMine()) {
            return null;
        }
        if (draft.badFile != null) {
            return "This file has an error, so it can't run";
        }
        int bad = badNodes();
        if (bad > 0) {
            return bad + (bad == 1 ? " node needs" : " nodes need") + " fixing before it can run";
        }
        if (!check.ok() && check.errors() != null && !check.errors().isEmpty()) {
            return sentence(check.errors().get(0));
        }
        if (draft.nodes().size() < 2) {
            return "Add a node first";
        }
        if (shownId() == null) {
            return "Give it a name first";
        }
        if (clash()) {
            return "That name is taken, pick another one";
        }
        if (host.locked() && draft.dirty()) {
            return "CustomTasks.json has an error, so this can't be saved";
        }
        return null;
    }

    // the upgrade happens right here for a list routine: saveGraph with its id as previousId takes it out of the list
    private boolean save() {
        if (!canSave()) {
            return false;
        }
        GraphSaveResult r;
        try {
            r = host.routines().saveGraph(draft.toGraph(), draft.id);
        } catch (Throwable t) {
            Safe.report("graphs save", t);
            r = new GraphSaveResult(false, "saving broke, the log has the details", null);
        }
        if (r == null || !r.ok() || r.id() == null) {
            msgNote = sentence(r == null || r.error() == null ? "couldn't save" : r.error());
            return false;
        }
        host.savedGraph(r.id());
        reload(r.id());
        // saved, but something after it didn't go through. the file's in, so this is just saying so
        msgNote = r.error() == null ? null : sentence(r.error());
        return true;
    }

    private void runClicked() {
        if (runningMine()) {
            host.stop();
            return;
        }
        if (runBlocked() != null) {
            return;
        }
        if (draft.dirty() && !save()) {
            return;
        }
        // a clean list routine stays a list routine: run() is enough, no file gets written just to look at it
        host.run(draft.id);
    }

    private void viewPicked(int i) {
        if (i == 1) {
            return;
        }
        // stays here until something actually leaves, the question can still end in a no
        viewSwitch.setSelected(1);
        if (draft.file) {
            return;
        }
        if (!draft.dirty()) {
            host.showList(draft.id);
            return;
        }
        // a Save nothing can do would just close the dialog and leave you wondering
        if (readOnly()) {
            guard(() -> host.showList(draft.id));
            return;
        }
        confirm("Keep your changes?", "Save makes this a node routine and stays here, the list view can't edit those. Don't save goes back to the list.", List.of(
                new NodeConfirm.Choice("Cancel", Button.Kind.GHOST, () -> {
                }),
                new NodeConfirm.Choice("Don't save", Button.Kind.SECONDARY, () -> host.showList(draft.id)),
                new NodeConfirm.Choice("Save", Button.Kind.PRIMARY, this::save)));
    }

    // anything that would drop the draft asks first. Save that doesn't go through stays right here
    void guard(Runnable next) {
        if (!dirty()) {
            next.run();
            return;
        }
        if (readOnly()) {
            confirm("Throw away your changes?", "Editing is off, so they can't be saved right now.", List.of(
                    new NodeConfirm.Choice("Cancel", Button.Kind.GHOST, () -> {
                    }),
                    new NodeConfirm.Choice("Don't save", Button.Kind.SECONDARY, next)));
            return;
        }
        confirm("Save your changes?", draft.id != null && !draft.file ? "Saving turns this into a node routine." : null, List.of(
                new NodeConfirm.Choice("Cancel", Button.Kind.GHOST, () -> {
                }),
                new NodeConfirm.Choice("Don't save", Button.Kind.SECONDARY, next),
                new NodeConfirm.Choice("Save", Button.Kind.PRIMARY, () -> {
                    if (save()) {
                        next.run();
                    }
                })));
    }

    private void picked(int i) {
        if (i < 0 || i >= pickerIds.size()) {
            return;
        }
        String id = pickerIds.get(i);
        if (DELETE_ROW.equals(id)) {
            askDelete();
            return;
        }
        if (id != null && id.equalsIgnoreCase(draft.id)) {
            return;
        }
        guard(() -> host.pick(id));
    }

    private void askDelete() {
        String id = draft.id;
        String shownName = draft.name().isBlank() ? id : draft.name();
        confirm("Delete " + shownName + "?", "This removes its file from the routines folder.", List.of(
                new NodeConfirm.Choice("Cancel", Button.Kind.GHOST, () -> {
                }),
                new NodeConfirm.Choice("Delete", Button.Kind.DANGER, () -> {
                    boolean ok = false;
                    try {
                        ok = host.routines().deleteGraph(id);
                    } catch (Throwable t) {
                        Safe.report("graphs delete", t);
                    }
                    if (ok) {
                        host.deletedGraph(id);
                    } else {
                        msgNote = "Couldn't delete it, the file is still there";
                    }
                })));
    }

    private static final String DELETE_ROW = "\u0000delete";

    private void syncPicker() {
        List<Routine> shown = host.shown();
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<Icon> icons = new ArrayList<>();
        int at = -1;
        for (Routine r : shown) {
            ids.add(r.id());
            labels.add(r.name() == null || r.name().isBlank() ? r.id() : r.name());
            boolean file = host.isFile(r.id());
            icons.add(host.fileError(r.id()) != null || host.problems(r.id()) > 0 ? Icon.WARNING : file ? Icon.NODES : Icon.ROUTINE);
            if (r.id() != null && r.id().equalsIgnoreCase(draft.id)) {
                at = ids.size() - 1;
            }
        }
        ids.add(null);
        labels.add(NEW);
        icons.add(Icon.PLUS);
        int danger = -1;
        if (draft.file && draft.id != null) {
            ids.add(DELETE_ROW);
            labels.add("Delete " + (draft.name().isBlank() ? draft.id : draft.name()));
            icons.add(Icon.TRASH);
            danger = ids.size() - 1;
        }
        pickerIds = ids;
        picker.setOptions(labels, at);
        picker.setIcons(icons, danger);
    }

    private void syncHead() {
        boolean live = runningMine();
        runBtn.setText(live ? "Stop" : "Run");
        runBtn.setKind(live ? Button.Kind.DANGER : Button.Kind.PRIMARY);
        runBtn.setEnabled(live || runBlocked() == null);
        saveBtn.setEnabled(canSave());
        boolean ro = readOnly();
        if (ro != lastReadOnly) {
            lastReadOnly = ro;
            nameField.setEditable(!ro);
            descField.setEditable(!ro);
            inspector.show(selCard);
            placeFloats();
        }
        viewSwitch.setOff(draft.file ? 0 : -1);
        String id = shownId();
        nameField.setInvalid((id == null && (draft.id != null || !draft.name().isEmpty())) || clash());
    }

    private void syncRun() {
        RunStatus st = host.status();
        GraphRunStatus g = host.routines().graphStatus();
        st = st == null ? RoutinesAccess.IDLE_STATUS : st;
        g = g == null ? RoutinesAccess.IDLE_GRAPH_STATUS : g;
        if (!st.equals(lastStatus) || !g.equals(lastGraphStatus)) {
            lastStatus = st;
            lastGraphStatus = g;
            rebuildCards();
        }
    }

    // a hand edit or an #altoreload under a clean draft shows the new version. reading it is a directory listing, so not
    // every tick
    private void syncDisk() {
        // a file that stopped parsing, or got fixed. loadGraph only answers null for the first and the draft is what it was
        // for the second, so neither shows up as stale. edits in the air stay put, the save is what gets told
        if (draft.id != null && !dirty() && !Objects.equals(host.fileError(draft.id), draft.badFile)) {
            open(draft.id);
            return;
        }
        if (++staleTicks < STALE_TICKS || draft.id == null || draft.badFile != null || draft.dirty()) {
            return;
        }
        staleTicks = 0;
        Graph fresh = host.routines().loadGraph(draft.id);
        if (draft.staleAgainst(fresh)) {
            reload(draft.id);
        }
    }

    // esc out of a box blurs it, and the esc after that would close the whole panel with a node still selected. the canvas
    // gets the keyboard back so that esc deselects first. only when nobody else took it
    private void syncFocus() {
        boolean now = inspector.focusWithin() || nameField.isFocused() || descField.isFocused() || picker.isFocused();
        boolean selected = selCard != null || selWire != null;
        if (floatFocus && !now && selected && host.ctx().focusedWidget() == null) {
            host.ctx().focus(canvas);
        }
        floatFocus = now;
    }

    // ----- layout

    @Override
    protected void layout() {
        int pad = theme.px(PAD);
        int gap = theme.px(Theme.GAP);
        int ch = theme.px(Theme.CONTROL_H);
        int hy = y + pad;
        int cx = x + pad;
        picker.setBounds(cx, hy, theme.px(PICK_W), ch);
        cx += theme.px(PICK_W) + gap;
        nameField.setBounds(cx, hy, theme.px(NAME_W), ch);
        cx += theme.px(NAME_W) + gap;
        int right = x + w - pad;
        int bw = theme.px(BTN_W);
        runBtn.setBounds(right - bw, hy, bw, ch);
        saveBtn.setBounds(runBtn.x() - gap - bw, hy, bw, ch);
        int sw = viewSwitch.preferredWidth();
        viewSwitch.setBounds(saveBtn.x() - gap - theme.px(4) - sw, hy, sw, ch);
        descField.setBounds(cx, hy, Math.max(theme.px(80), viewSwitch.x() - theme.px(4) - gap - cx), ch);
        canvas.setBounds(x + pad, y + theme.px(CANVAS_TOP), Math.max(0, w - 2 * pad), Math.max(0, h - theme.px(CANVAS_TOP) - pad));
        placeFloats();
    }

    private void placeFloats() {
        int f = theme.px(FLOAT);
        palette.setMaxHeight(canvas.h() - 2 * f);
        palette.setBounds(canvas.x() + f, canvas.y() + f, palette.wantedW(), palette.wantedH());
        int iw = theme.px(NodeInspector.W);
        inspector.setBounds(canvas.x() + canvas.w() - f - iw, canvas.y() + f, iw, Math.max(0, canvas.h() - 2 * f));
        int bx = canvas.x() + (palette.open() && !readOnly() ? theme.px(FLOAT + NodePalette.W + FLOAT) : f);
        bar.setBounds(bx, canvas.y() + canvas.h() - f - theme.px(BAR_H), bar.wantedW(), theme.px(BAR_H));
    }

    @Override
    public List<Widget> children() {
        if (draft == null) {
            return kidsLockedNoSel;
        }
        if (readOnly()) {
            return selCard != null ? kidsLocked : kidsLockedNoSel;
        }
        return selCard != null ? kidsSel : kidsNoSel;
    }

    private boolean overFloat(float mx, float my) {
        return palette.contains(mx, my) && !readOnly()
                || selCard != null && inspector.contains(mx, my)
                || bar.contains(mx, my)
                || msgW > 0 && mx >= msgX && mx < msgX + msgW && my >= msgY && my < msgY + msgH;
    }

    // ----- input

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        // the name box and friends don't reach the canvas at all, and syncFocus can hand it the keyboard after them. Delete
        // only means a node when the last press was on the canvas
        if (!canvas.contains(mx, my)) {
            canvas.pressedElsewhere();
        }
        if (msgW > 0 && mx >= msgX && mx < msgX + msgW && my >= msgY && my < msgY + msgH) {
            canvas.pressedElsewhere();
            String first = firstBad();
            if (first != null && button == 0) {
                focusNode(first);
            }
            return true;
        }
        return super.mouseDown(mx, my, button, mods);
    }

    private String firstBad() {
        for (GraphNode n : draft.nodes()) {
            NodeCheck c = checkOf(n.id());
            if (c != null && c.error() != null) {
                return n.id();
            }
        }
        return null;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        boolean took = super.mouseUp(mx, my, button, mods);
        // a click on a card without a drag is the moment the inspector gets to push the view aside
        String clear = pendingClear;
        pendingClear = null;
        GraphNode n = clear == null ? null : draft.node(clear);
        if (n != null) {
            clearInspector(n);
        }
        return took;
    }

    // the canvas under a float isn't hovered, or a port behind the inspector lights up
    @Override
    public Widget mouseMove(float mx, float my) {
        hovered = contains(mx, my);
        boolean over = overFloat(mx, my);
        Widget deepest = hovered ? this : null;
        List<Widget> kids = children();
        for (int i = kids.size() - 1; i >= 0; i--) {
            Widget k = kids.get(i);
            Widget d = k == canvas && over ? k.mouseMove(-1e6f, -1e6f) : k.mouseMove(mx, my);
            if (d != null && deepest == this) {
                deepest = d;
            }
        }
        return deepest;
    }

    @Override
    public void tick() {
        if (draft == null) {
            return;
        }
        syncRun();
        syncDisk();
        syncHead();
        syncPicker();
        super.tick();
        syncFocus();
    }

    // ----- drawing

    @Override
    public void draw(Canvas c, Frame f) {
        if (draft == null) {
            return;
        }
        syncHead();
        syncFocus();
        if (pendingFit && canvas.w() > 0) {
            pendingFit = false;
            fit();
        }
        picker.draw(c, f);
        nameField.draw(c, f);
        descField.draw(c, f);
        viewSwitch.draw(c, f);
        saveBtn.draw(c, f);
        runBtn.draw(c, f);
        canvas.draw(c, f);
        if (!readOnly()) {
            palette.draw(c, f);
        }
        if (selCard != null) {
            inspector.draw(c, f);
        }
        bar.draw(c, f);
        drawMessage(c);
        palette.drawCarried(c);
    }

    // first thing that's true wins, in the order you'd want to hear it
    private String message() {
        if (msgNote != null) {
            return msgNote;
        }
        if (draft.badFile != null) {
            return sentence(draft.badFile);
        }
        if (host.locked()) {
            return "Editing is off";
        }
        int bad = badNodes();
        if (bad > 0) {
            return bad + (bad == 1 ? " node needs" : " nodes need") + " fixing before it can run";
        }
        if (!check.ok() && check.errors() != null && !check.errors().isEmpty()) {
            return sentence(check.errors().get(0));
        }
        String id = shownId();
        if (clash()) {
            return "There is already a command called " + id;
        }
        if (id == null) {
            return "Give it a name to save it";
        }
        if (runKind() == RunKind.GRAPH && lastGraphStatus.state() == RunState.FAILED) {
            String m = lastGraphStatus.message();
            return m == null || m.isBlank() ? "Failed" : "Failed: " + m;
        }
        if (runningMine() && !draft.dirty()) {
            return "";
        }
        if (draft.dirty()) {
            if (draft.id != null && !draft.file) {
                return "Saving turns this into a node routine";
            }
            return draft.id != null ? "Unsaved changes. Run saves first" : "Not saved yet. Run saves first";
        }
        return "Runs as #custom " + id;
    }

    private boolean messageBad() {
        return msgNote != null || draft.badFile != null || !host.locked() && (badNodes() > 0 || !check.ok() || clash())
                || runKind() == RunKind.GRAPH && lastGraphStatus.state() == RunState.FAILED;
    }

    private void drawMessage(Canvas c) {
        String m = message();
        msgW = 0;
        if (m == null || m.isEmpty()) {
            return;
        }
        TextStyle s = theme.small;
        float left = bar.x() + bar.w() + theme.px(Theme.SPACE_3);
        float right = canvas.x() + canvas.w() - (selCard != null ? theme.px(NodeInspector.W + 22) : theme.px(FLOAT));
        float pad = theme.px(8);
        float room = Math.max(0f, right - left - 2 * pad);
        String shownText = c.ellipsize(m, s, room);
        float tw = c.measure(shownText, s);
        float mh = c.lineHeight(s) + 2 * theme.px(3);
        msgX = left;
        msgW = tw + 2 * pad;
        msgH = mh;
        msgY = bar.y() + (bar.h() - mh) / 2f;
        c.fillRoundRect(msgX, msgY, msgW, msgH, theme.px(6), Theme.fade(theme.color(Theme.FIELD), 0.85f));
        c.text(shownText, msgX + pad, msgY + theme.px(3) + c.ascent(s), s, theme.color(messageBad() ? Theme.DANGER : Theme.TEXT_3));
    }

    private static String sentence(String s) {
        return s == null || s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    // why the thing under the mouse is grey, or what an icon button is
    String tipAt(float mx, float my) {
        if (runBtn.contains(mx, my) && !runBtn.enabled()) {
            return runBlocked();
        }
        if (viewSwitch.contains(mx, my) && viewSwitch.off() == 0 && viewSwitch.segmentAtX(mx) == 0) {
            return "Saved as a node routine, the list view can't edit it";
        }
        return bar.tipAt(mx, my);
    }

    // ----- the picker: the routine glyph and a chevron, the list column folded into a menu

    private final class PickerDropdown extends Dropdown {

        PickerDropdown(Theme theme, ShellContext ctx) {
            super(theme, List.of(NEW), 0, NodeEditor.this::picked, ctx);
        }

        @Override
        public void draw(Canvas c, Frame f) {
            stamp(f);
            float r = theme.pxf(Theme.CONTROL_RADIUS);
            boolean open = isOpen();
            c.fillRoundRect(x, y, w, h, r, theme.color(hovered ? Theme.HOVER : Theme.RAISED));
            c.strokeRoundRect(x, y, w, h, r, focused || open ? theme.color(Theme.ACCENT) : Theme.fade(theme.color(Theme.HAIRLINE_STRONG), 0.6f));
            int is = theme.px(Theme.ICON);
            c.icon(Icon.ROUTINE, x + theme.px(8), y + (h - is) / 2f, is, theme.color(Theme.TEXT_2));
            c.icon(Icon.CHEVRON_DOWN, x + w - theme.px(8) - is + theme.px(2), y + (h - is) / 2f, is, theme.color(Theme.TEXT_2));
            noteView(c);
        }
    }

    // ----- bottom left: fit, zoom, tidy

    private final class Bar extends Widget {
        private final IconButton fitBtn;
        private final IconButton outBtn;
        private final IconButton inBtn;
        private final IconButton tidyBtn;
        private final List<Widget> barKids;

        Bar() {
            super(NodeEditor.this.theme);
            fitBtn = button(Icon.FIT, "Fit to view", NodeEditor.this::fit);
            outBtn = button(Icon.MINUS, "Zoom out", () -> canvas.zoomStep(-1));
            inBtn = button(Icon.PLUS, "Zoom in", () -> canvas.zoomStep(1));
            tidyBtn = button(Icon.NODES, "Tidy up", NodeEditor.this::arrange);
            barKids = List.of(fitBtn, outBtn, inBtn, tidyBtn);
        }

        private IconButton button(Icon icon, String tip, Runnable run) {
            IconButton b = new IconButton(theme, icon, tip, run);
            b.setFocusable(false);
            return b;
        }

        int wantedW() {
            return theme.px(3 + 30 + 30 + 40 + 30 + 30 + 3);
        }

        @Override
        protected void layout() {
            int b = theme.px(30);
            int by = y + (h - b) / 2;
            int cx = x + theme.px(3);
            fitBtn.setBounds(cx, by, b, b);
            outBtn.setBounds(cx + b, by, b, b);
            inBtn.setBounds(cx + 2 * b + theme.px(40), by, b, b);
            tidyBtn.setBounds(cx + 3 * b + theme.px(40), by, b, b);
        }

        @Override
        public List<Widget> children() {
            return barKids;
        }

        @Override
        public boolean mouseDown(float mx, float my, int button, int mods) {
            if (!contains(mx, my)) {
                return false;
            }
            canvas.pressedElsewhere();
            super.mouseDown(mx, my, button, mods);
            return true;
        }

        @Override
        public boolean scroll(float mx, float my, float dy) {
            return contains(mx, my);
        }

        String tipAt(float mx, float my) {
            for (Widget k : barKids) {
                if (k.contains(mx, my) && k instanceof IconButton b) {
                    return b.tooltip();
                }
            }
            return null;
        }

        @Override
        public void draw(Canvas c, Frame f) {
            theme.card(c, x, y, w, h, theme.pxf(Theme.CARD_RADIUS), true);
            tidyBtn.setEnabled(!readOnly() && draft.start() != null);
            for (Widget k : barKids) {
                k.draw(c, f);
            }
            TextStyle s = theme.small.asTabular();
            String pct = Math.round(canvas.view().zoom() * 100) + "%";
            float tw = c.measure(pct, s);
            float mid = outBtn.x() + outBtn.w() + theme.px(20);
            c.text(pct, mid - tw / 2f, y + h / 2f + (c.ascent(s) - c.descent(s)) / 2f, s, theme.color(Theme.TEXT_2));
        }
    }

    // test hooks
    NodeCanvas canvas() {
        return canvas;
    }

    NodePalette palette() {
        return palette;
    }

    NodeInspector inspector() {
        return inspector;
    }

    Dropdown picker() {
        return picker;
    }

    List<String> pickerIds() {
        return pickerIds;
    }

    StepField nameField() {
        return nameField;
    }

    StepField descField() {
        return descField;
    }

    Segmented viewSwitch() {
        return viewSwitch;
    }

    Button saveButton() {
        return saveBtn;
    }

    Button runButton() {
        return runBtn;
    }

    String messageText() {
        return message();
    }

    float[] messageRect() {
        return new float[]{msgX, msgY, msgW, msgH};
    }

    IconButton tidyButton() {
        return bar.tidyBtn;
    }

    IconButton fitButton() {
        return bar.fitBtn;
    }

    void pickForTest(int i) {
        picked(i);
    }
}
