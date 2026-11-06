package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.RoutinesAccess;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondPreset;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondSpec;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondTree;
import adris.altoclef.ui.gui.facade.RoutinesAccess.FieldSpec;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Graph;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphCheck;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphEdge;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphNode;
import adris.altoclef.ui.gui.facade.RoutinesAccess.NodeCheck;
import adris.altoclef.ui.gui.facade.RoutinesAccess.NodeSpec;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Routine;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Step;
import adris.altoclef.ui.gui.facade.RoutinesAccess.StepType;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Dropdown;
import adris.altoclef.ui.gui.kit.FadeCanvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.NodeCard;
import adris.altoclef.ui.gui.kit.Scroller;
import adris.altoclef.ui.gui.kit.Segmented;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.SuggestList;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.TextWrap;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.CondDraft;
import adris.altoclef.ui.gui.model.GraphDraft;
import adris.altoclef.ui.gui.model.RoutineDraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

// floats on the right while a node is selected. the boxes come out of the FieldSpecs, so a new type or a new kind of field
// edits itself and every string in the tables below is only there to read better than the spec's own label. it gets rebuilt
// when what's on it changes shape, never while you type, or the box under your caret would be gone
final class NodeInspector extends Widget {

    interface Host {
        GraphDraft draft();

        RoutinesAccess routines();

        ShellContext ctx();

        ItemEntry item(String id);

        default IdLists ids() {
            return IdLists.NONE;
        }

        List<String> waypoints();

        List<Routine> all();

        boolean readOnly();

        NodeCheck checkOf(String id);

        // a value changed: card, wires, the head row, the red box
        void soft();

        // what's on the inspector changes too
        void hard();

        void close();

        void deleteNode();

        // the canvas doesn't get to think this press was its own
        void pressed();

        void confirm(String title, String body, List<NodeConfirm.Choice> choices);
    }

    static final int W = 288;
    private static final int HEAD_H = 50;
    private static final int FOOT_H = 48;
    private static final int LINE = 15;
    private static final int GAP = 6;
    private static final int BODY_PAD = 12;
    private static final int NUM_W = 72;
    private static final int COND_NUM_W = 64;
    private static final int OP_W = 52;
    private static final int BASKET_LINE = 40;
    private static final List<String> OPS = List.of(">=", "<=", "=", ">", "<");

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("repeat.mode", "Repeat"),
            Map.entry("repeat.n", "How many times"),
            Map.entry("repeat.cond", "Until"),
            Map.entry("wait.mode", "Wait"),
            Map.entry("wait.secs", "How long"),
            Map.entry("wait.cond", "Until"),
            Map.entry("stop.ok", "Ends the run as"),
            Map.entry("follow.secs", "For"));
    private static final Map<String, String> UNITS = Map.of(
            "mine.n", "blocks",
            "craft.n", "items",
            "follow.secs", "seconds",
            "repeat.n", "times",
            "repeat.cap", "loops",
            "wait.secs", "seconds",
            "wait.timeout", "seconds");
    private static final Map<String, String> HINTS = Map.of(
            "mine.block", "Like oak_log",
            "mine.n", "16",
            "craft.item", "Like iron_pickaxe",
            "craft.n", "1",
            "follow.player", "A player name",
            "follow.secs", "60",
            "eat.hunger", "20");
    private static final Map<String, String> NOTES = Map.ofEntries(
            Map.entry("start", "Every run begins here. Wire it to the first thing the bot should do. It can't be deleted."),
            Map.entry("eat", "Eats the best food in the inventory. Fails if there is nothing to eat."),
            Map.entry("craft", "Gets the ingredients first if it has to"),
            Map.entry("sub", "Runs it to the end. Its Stop decides ok or failed"),
            Map.entry("if", "Goes to no otherwise"),
            Map.entry("repeat.until", "Giving up fails the run"),
            Map.entry("repeat.forever", "Runs body over and over until a Stop node ends the run, or you press Stop. Body needs an action or a Wait in it."),
            Map.entry("repeat", "Wire the end of body back into this node's input"),
            Map.entry("wait.until", "Goes to timeout if it runs out"));
    private static final Map<String, String> READ = Map.of(
            "if", "Goes to yes when",
            "repeat", "Goes to done when",
            "wait", "Goes to ok when");
    private static final Map<String, String> CHOICES = Map.of(
            "secs", "Seconds",
            "stop.ok.true", "Done",
            "stop.ok.false", "Failed");
    private static final Map<String, String> PORT_NOTES = Map.of(
            "failed", "Nothing on failed, so a failure stops the whole run",
            "timeout", "Nothing on timeout, so running out stops the whole run");

    private final Host host;
    private final IconButton closeBtn;
    private final Button delBtn;
    private final Body body;
    private final List<Widget> kids;
    private final FadeCanvas fade = new FadeCanvas();
    private final List<Part> parts = new ArrayList<>();
    // a goto's waypoint | block | coords isn't in its line until there's something to put there
    private final Map<String, RoutineDraft.Row> lineRows = new HashMap<>();
    private String nodeId;
    private String sub = "";
    private CondView cond;
    private BasketPart basket;
    private long now;

    NodeInspector(Theme theme, Host host) {
        super(theme);
        this.host = host;
        closeBtn = new IconButton(theme, Icon.CLOSE, "Close", host::close);
        closeBtn.setFocusable(false);
        delBtn = new Button(theme, "Delete node", Button.Kind.GHOST, Icon.TRASH, host::deleteNode);
        delBtn.setFocusable(false);
        body = new Body();
        kids = List.of(body, closeBtn, delBtn);
    }

    void show(String id) {
        boolean same = id != null && id.equals(nodeId);
        nodeId = id;
        if (!same) {
            body.scroller.snapTo(0);
        }
        rebuild();
    }

    String nodeId() {
        return nodeId;
    }

    private GraphNode node() {
        return nodeId == null ? null : host.draft().node(nodeId);
    }

    private GraphDraft draft() {
        return host.draft();
    }

    private ShellContext ctx() {
        return host.ctx();
    }

    // ----- building it

    void rebuild() {
        // the old boxes are about to go, and the shell would keep typing into one
        if (focusIn(allWidgets())) {
            ctx().blur();
        }
        parts.clear();
        cond = null;
        basket = null;
        GraphNode n = node();
        if (n == null) {
            return;
        }
        NodeSpec spec = draft().spec(n.type());
        if (spec != null && spec.fields() != null) {
            for (FieldSpec f : spec.fields()) {
                if (GraphDraft.asked(f, n)) {
                    addField(n, f);
                }
            }
        }
        addNotes(n);
        parts.add(new TextPart(this::warning, Theme.TEXT_3, GAP));
        parts.add(new WhyPart(this::problem));
        soft();
        layout();
    }

    // the cheap one: the words that follow what you type, no widget changes
    void soft() {
        GraphNode n = node();
        if (n == null) {
            return;
        }
        String said = host.routines().summaryOf(n);
        sub = n.cond() != null ? NodeEntries.pretty(said) : said;
        if (cond != null) {
            cond.refresh();
        }
        if (basket != null) {
            basket.refresh();
        }
    }

    private String problem() {
        NodeCheck c = nodeId == null ? null : host.checkOf(nodeId);
        return c == null || c.error() == null ? "" : c.error();
    }

    private String warning() {
        NodeCheck c = nodeId == null ? null : host.checkOf(nodeId);
        return c == null || c.warning() == null ? "" : c.warning();
    }

    private void addNotes(GraphNode n) {
        String mode = n.params().get("mode");
        if (mode != null) {
            note(NOTES.get(n.type() + "." + mode));
        }
        note(NOTES.get(n.type()));
        for (String port : draft().outputsOf(n)) {
            if (PORT_NOTES.containsKey(port) && !draft().wired(n.id(), port)) {
                note(PORT_NOTES.get(port));
            }
        }
    }

    private void note(String text) {
        if (text != null) {
            parts.add(new TextPart(() -> text, Theme.TEXT_3, GAP));
        }
    }

    private void label(String text) {
        parts.add(new LabelPart(text));
    }

    private static String labelOf(GraphNode n, FieldSpec f) {
        String l = LABELS.get(n.type() + "." + f.key());
        return l != null ? l : f.label();
    }

    private void addField(GraphNode n, FieldSpec f) {
        String key = n.type() + "." + f.key();
        String value = n.params().getOrDefault(f.key(), "");
        String id = n.id();
        switch (f.kind() == null ? "" : f.kind()) {
            case "INT", "SECONDS" -> {
                label(labelOf(n, f));
                StepField box = number(value, HINTS.getOrDefault(key, ""), "SECONDS".equals(f.kind()), v -> set(id, f.key(), v));
                String unit = UNITS.get(key);
                parts.add(unit == null ? new RowPart(fixed(box, NUM_W), spacer()) : new RowPart(fixed(box, NUM_W), unit(unit)));
            }
            case "CHOICE" -> {
                label(labelOf(n, f));
                parts.add(new RowPart(flex(choice(n, f, value))));
            }
            case "OP" -> {
                label(labelOf(n, f));
                parts.add(new RowPart(fixed(op(f, value, v -> set(id, f.key(), v)), OP_W), spacer()));
            }
            case "ITEM", "BLOCK" -> {
                label(labelOf(n, f));
                parts.add(new RowPart(flex(itemBox(f.kind(), value, HINTS.getOrDefault(key, ""), v -> set(id, f.key(), v)))));
            }
            case "ROUTINE" -> {
                label(labelOf(n, f));
                parts.add(new RowPart(flex(routinePicker(id, f.key(), value))));
            }
            case "COND" -> addCond(n, labelOf(n, f));
            case "LINE" -> addLine(n);
            // COORD, MOB, PLAYER and anything nobody's heard of yet: a text box still edits it
            default -> {
                label(labelOf(n, f));
                parts.add(new RowPart(flex(suggest(text(value, HINTS.getOrDefault(key, ""), v -> set(id, f.key(), v)), f.kind()))));
            }
        }
    }

    private void set(String id, String key, String value) {
        draft().setParam(id, key, value);
        host.soft();
    }

    private void setHard(String id, String key, String value) {
        draft().setParam(id, key, value);
        host.hard();
    }

    private StepField text(String value, String hint, Consumer<String> onChange) {
        return box(value, hint, StepField.Lead.NONE, onChange);
    }

    private StepField itemBox(String kind, String value, String hint, Consumer<String> onChange) {
        return suggest(box(value, hint, StepField.Lead.ITEM, v -> onChange.accept(v.trim().toLowerCase(Locale.ROOT))), kind);
    }

    // hangs the type-ahead off a box whose kind has a list (items, blocks, mobs, players). a read only box has
    // nothing to type into, so it gets none
    private StepField suggest(StepField f, String kind) {
        SuggestList.Source s = host.ids().forKind(kind);
        if (s != null && !host.readOnly()) {
            IdLists.attach(theme, f, ctx(), s, null);
        }
        return f;
    }

    private StepField box(String value, String hint, StepField.Lead lead, Consumer<String> onChange) {
        StepField f = new StepField(theme, hint, lead, id -> host.item(id) != null, null);
        f.setText(value);
        f.setOnChange(onChange);
        f.setEditable(!host.readOnly());
        return f;
    }

    // SECONDS keeps its point, 0.5 has to stay 0.5. a half typed number is the validator's business, not the box's
    private StepField number(String value, String hint, boolean decimal, Consumer<String> onChange) {
        StepField f = box(value, hint, StepField.Lead.NONE, v -> onChange.accept(v.trim()));
        f.edit().setCharFilter(decimal ? ch -> (ch >= '0' && ch <= '9') || ch == '.' : ch -> ch >= '0' && ch <= '9');
        f.edit().setMaxLength(8);
        f.setRightAligned(true);
        return f;
    }

    private Widget choice(GraphNode n, FieldSpec f, String value) {
        List<String> values = f.choices() == null ? List.of() : f.choices();
        List<String> labels = new ArrayList<>();
        for (String v : values) {
            labels.add(choiceLabel(n.type(), f.key(), v));
        }
        int at = Math.max(0, values.indexOf(value));
        String id = n.id();
        if (values.size() <= 3) {
            return new Segmented(theme, labels, at, i -> setHard(id, f.key(), values.get(i)));
        }
        return new Dropdown(theme, labels, at, i -> setHard(id, f.key(), values.get(i)), ctx());
    }

    private static String choiceLabel(String type, String key, String value) {
        String l = CHOICES.get(type + "." + key + "." + value);
        if (l == null) {
            l = CHOICES.get(value);
        }
        return l != null ? l : title(value);
    }

    private static String title(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).replace('_', ' ');
    }

    private Dropdown op(FieldSpec f, String value, Consumer<String> onChange) {
        // the spec has no list of its own for these yet
        List<String> values = f.choices() == null || f.choices().isEmpty() ? OPS : f.choices();
        List<String> shown = new ArrayList<>();
        for (String v : values) {
            shown.add(switch (v) {
                case ">=" -> "≥";
                case "<=" -> "≤";
                default -> v;
            });
        }
        return new Dropdown(theme, shown, Math.max(0, values.indexOf(value)), i -> onChange.accept(values.get(i)), ctx());
    }

    // everything else #custom could run, not the routine you're editing. one that's gone stays listed, same idea as
    // StepRow's waypoint, so opening a node never quietly swaps it
    private Dropdown routinePicker(String nodeId, String key, String value) {
        String self = draft().id;
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (value.isEmpty()) {
            ids.add("");
            labels.add("Pick a routine");
        }
        int at = -1;
        for (Routine r : host.all()) {
            if (r.id() == null || r.id().equalsIgnoreCase(self)) {
                continue;
            }
            ids.add(r.id());
            labels.add(r.name() == null || r.name().isBlank() ? r.id() : r.name());
            if (r.id().equalsIgnoreCase(value)) {
                at = ids.size() - 1;
            }
        }
        if (!value.isEmpty() && at < 0) {
            ids.add(0, value);
            labels.add(0, value);
            at = 0;
        }
        return new Dropdown(theme, labels, Math.max(0, at), i -> set(nodeId, key, ids.get(i)), ctx());
    }

    // ----- a command line, in the list view's boxes

    static StepType stepTypeOf(String nodeType) {
        try {
            return nodeType == null ? null : StepType.valueOf(nodeType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String lineOf(RoutineDraft.Row row) {
        String l = host.routines().lineFor(row.kind.type, RoutineDraft.fieldsFor(row));
        return l == null ? "" : l;
    }

    private RoutineDraft.Row rowFor(GraphNode n, StepType type) {
        String line = n.params().getOrDefault("line", "");
        RoutineDraft.Row kept = lineRows.get(n.id());
        if (kept != null && kept.kind.type == type && lineOf(kept).equals(line)) {
            return kept;
        }
        RoutineDraft.Row row;
        if (line.isBlank()) {
            row = RoutineDraft.blankRow(switch (type) {
                case GET -> RoutineDraft.Kind.GET;
                case EQUIP -> RoutineDraft.Kind.EQUIP;
                case GOTO -> RoutineDraft.Kind.GOTO_WAYPOINT;
                case WAIT -> RoutineDraft.Kind.WAIT;
                case RAW -> RoutineDraft.Kind.RAW;
            });
        } else {
            row = RoutineDraft.rowOf(new Step(type, line), host.routines());
        }
        lineRows.put(n.id(), row);
        return row;
    }

    private void commitLine(String id, RoutineDraft.Row row) {
        draft().setParam(id, "line", lineOf(row));
        host.soft();
    }

    private void addLine(GraphNode n) {
        StepType type = stepTypeOf(n.type());
        String id = n.id();
        if (type == null) {
            label("Command");
            parts.add(new RowPart(flex(text(n.params().getOrDefault("line", ""), "", v -> set(id, "line", v)))));
            return;
        }
        RoutineDraft.Row row = rowFor(n, type);
        switch (type) {
            case GET -> {
                label("Items");
                basket = new BasketPart(id, row);
                parts.add(basket);
            }
            case EQUIP -> {
                label("Item to hold");
                parts.add(new RowPart(flex(itemBox("ITEM", row.get("item"), "Like diamond_sword", v -> {
                    row.f.put("item", v);
                    commitLine(id, row);
                }))));
            }
            case GOTO -> addGoto(id, row);
            case RAW -> {
                label("Command");
                StepField f = box(row.get("line"), "Any command, like farm 30", StepField.Lead.HASH, v -> {
                    row.f.put("line", v);
                    commitLine(id, row);
                });
                parts.add(new RowPart(flex(f)));
                note("Done when the command finishes, failed if it errors");
            }
            case WAIT -> {
                label("How long");
                parts.add(new RowPart(fixed(number(row.get("seconds"), "5", true, v -> {
                    row.f.put("seconds", v);
                    commitLine(id, row);
                }), NUM_W), unit("seconds")));
            }
        }
    }

    private void addGoto(String id, RoutineDraft.Row row) {
        List<RoutineDraft.Kind> kinds = List.of(RoutineDraft.Kind.GOTO_WAYPOINT, RoutineDraft.Kind.GOTO_BLOCK, RoutineDraft.Kind.GOTO_COORDS);
        label("Where");
        parts.add(new RowPart(flex(new Segmented(theme, List.of("Waypoint", "Block", "Coords"), Math.max(0, kinds.indexOf(row.kind)), i -> {
            lineRows.put(id, RoutineDraft.blankRow(kinds.get(i)));
            commitLine(id, lineRows.get(id));
            host.hard();
        }))));
        switch (row.kind) {
            case GOTO_BLOCK -> {
                label("Block");
                parts.add(new RowPart(flex(itemBox("BLOCK", row.get("block"), "Like crafting_table", v -> {
                    row.f.put("block", v);
                    commitLine(id, row);
                }))));
                note("Goes to the nearest one it knows about");
            }
            case GOTO_COORDS -> {
                label("Coordinates");
                List<Elem> elems = new ArrayList<>();
                for (String key : List.of("x", "y", "z")) {
                    StepField f = text(row.get(key), key.toUpperCase(Locale.ROOT), v -> {
                        row.f.put(key, v.trim());
                        commitLine(id, row);
                    });
                    f.edit().setCharFilter(ch -> (ch >= '0' && ch <= '9') || ch == '-' || ch == '+' || ch == '~');
                    f.edit().setMaxLength(10);
                    elems.add(flex(f));
                }
                parts.add(new RowPart(elems.toArray(new Elem[0])));
                note("A number, ~ for where you stand, or ~10 for an offset. Y can stay empty");
            }
            default -> {
                label("Waypoint");
                List<String> opts = StepRow.waypointOptions(row.get("waypoint"), host.waypoints());
                String cur = row.get("waypoint");
                parts.add(new RowPart(flex(new Dropdown(theme, opts, cur.isEmpty() ? 0 : Math.max(0, opts.indexOf(cur)), i -> {
                    String v = opts.get(i);
                    row.f.put("waypoint", host.waypoints().contains(v) || v.equals(cur) ? v : "");
                    commitLine(id, row);
                }, ctx()))));
            }
        }
    }

    // ----- conditions

    private void addCond(GraphNode n, String labelText) {
        label(labelText);
        CondDraft d = CondDraft.of(n.cond());
        String prefix = READ.getOrDefault(n.type(), "True when");
        if (!d.fits) {
            addUnfitCond(n, prefix);
            return;
        }
        cond = new CondView(n.id(), d, prefix);
        cond.build();
    }

    // nested past what rows can show. never flattened on the quiet: you can read it, or start over on purpose
    private void addUnfitCond(GraphNode n, String prefix) {
        String id = n.id();
        cond = new CondView(id, CondDraft.of(null), prefix);
        parts.add(cond.read);
        note("This condition is nested too deep for the rows here");
        Button replace = new Button(theme, "Replace", Button.Kind.SECONDARY, null, () -> host.confirm("Replace this condition?",
                "It starts over with one empty row. The old one is gone once you save.", List.of(
                        new NodeConfirm.Choice("Cancel", Button.Kind.GHOST, () -> {
                        }),
                        new NodeConfirm.Choice("Replace", Button.Kind.DANGER, () -> {
                            draft().setCond(id, CondDraft.blankTree(host.routines().condSpecs()));
                            host.hard();
                        }))));
        replace.setFocusable(false);
        parts.add(new RowPart(GAP, fixedPx(replace, replace.preferredWidth()), spacer()));
    }

    private final class CondView {
        final String id;
        final CondDraft d;
        final String prefix;
        final List<BoxPart> boxes = new ArrayList<>();
        final List<Runnable> pickerSyncs = new ArrayList<>();
        final ReadPart read;

        CondView(String id, CondDraft d, String prefix) {
            this.id = id;
            this.d = d;
            this.prefix = prefix;
            read = new ReadPart(prefix);
        }

        void build() {
            List<CondSpec> specs = host.routines().condSpecs();
            List<CondPreset> presets = host.routines().condPresets();
            for (int i = 0; i < d.rows.size(); i++) {
                if (i > 0) {
                    parts.add(new JoinPart(new Segmented(theme, List.of("And", "Or"), CondDraft.ANY.equals(d.join) ? 1 : 0, k -> {
                        d.join = k == 1 ? CondDraft.ANY : CondDraft.ALL;
                        commit();
                        host.hard();
                    })));
                }
                BoxPart box = rowBox(i, specs, presets);
                boxes.add(box);
                parts.add(box);
            }
            Button add = new Button(theme, "Add condition", Button.Kind.GHOST, Icon.PLUS, () -> {
                if (!specs.isEmpty()) {
                    d.rows.add(CondDraft.blankRow(specs.get(0)));
                    commit();
                    host.hard();
                }
            });
            add.setFocusable(false);
            parts.add(new RowPart(8, fixedPx(add, add.preferredWidth()), spacer()));
            parts.add(read);
        }

        void commit() {
            draft().setCond(id, d.toTree());
        }

        // a box's value changed: nothing appears or goes, just the words and the red
        void edited() {
            commit();
            host.soft();
        }

        private BoxPart rowBox(int i, List<CondSpec> specs, List<CondPreset> presets) {
            CondDraft.Row row = d.rows.get(i);
            List<Part> inner = new ArrayList<>();
            Button not = notButton(row);
            inner.add(new RowPart(flex(kindPicker(i, row, specs, presets)), fixedPx(not, not.preferredWidth()), fixed(rowBin(i), 28)));
            CondSpec spec = null;
            for (CondSpec s : specs) {
                if (s.kind().equals(row.kind)) {
                    spec = s;
                }
            }
            if (spec != null) {
                condArgs(spec, row, inner);
            }
            return new BoxPart(inner, () -> rowProblem(row) != null);
        }

        private Dropdown kindPicker(int i, CondDraft.Row row, List<CondSpec> specs, List<CondPreset> presets) {
            List<String> labels = new ArrayList<>();
            int at = -1;
            for (int k = 0; k < specs.size(); k++) {
                labels.add(specs.get(k).label());
                if (specs.get(k).kind().equals(row.kind)) {
                    at = k;
                }
            }
            for (CondPreset p : presets) {
                labels.add(p.label());
            }
            int preset = CondDraft.presetOf(row, presets);
            if (preset >= 0) {
                at = specs.size() + preset;
            }
            // a kind this build doesn't know, from a hand edit: says so instead of pretending it's the first one
            int shift = at < 0 ? 1 : 0;
            if (at < 0) {
                labels.add(0, "Pick a condition");
                at = 0;
            }
            Dropdown[] holder = new Dropdown[1];
            // "Inventory full" stops being true the moment you change its 0, and rebuilding here would lose the caret
            if (shift == 0) {
                pickerSyncs.add(() -> holder[0].setSelected(kindIndex(row, specs, presets)));
            }
            holder[0] = new Dropdown(theme, labels, at, picked -> {
                int k = picked - shift;
                if (k < 0) {
                    return;
                }
                CondDraft.Row next;
                if (k < specs.size()) {
                    next = CondDraft.blankRow(specs.get(k));
                } else {
                    CondPreset p = presets.get(k - specs.size());
                    next = new CondDraft.Row(p.kind());
                    next.args.putAll(p.args());
                }
                next.negate = row.negate;
                d.rows.set(i, next);
                commit();
                host.hard();
            }, ctx());
            return holder[0];
        }

        private int kindIndex(CondDraft.Row row, List<CondSpec> specs, List<CondPreset> presets) {
            int preset = CondDraft.presetOf(row, presets);
            if (preset >= 0) {
                return specs.size() + preset;
            }
            for (int k = 0; k < specs.size(); k++) {
                if (specs.get(k).kind().equals(row.kind)) {
                    return k;
                }
            }
            return 0;
        }

        private Button notButton(CondDraft.Row row) {
            Button[] holder = new Button[1];
            holder[0] = new Button(theme, "Not", row.negate ? Button.Kind.SECONDARY : Button.Kind.GHOST, null, () -> {
                row.negate = !row.negate;
                holder[0].setKind(row.negate ? Button.Kind.SECONDARY : Button.Kind.GHOST);
                edited();
            });
            holder[0].setFocusable(false);
            return holder[0];
        }

        private IconButton rowBin(int i) {
            IconButton bin = new StepRow.Bin(theme, "Remove condition", () -> {
                d.rows.remove(i);
                commit();
                host.hard();
            });
            bin.setFocusable(false);
            return bin;
        }

        private void condArgs(CondSpec spec, CondDraft.Row row, List<Part> inner) {
            List<Elem> cur = new ArrayList<>();
            boolean wide = false;
            for (FieldSpec f : spec.fields()) {
                String key = f.key();
                Consumer<String> put = v -> {
                    row.args.put(key, v);
                    edited();
                };
                String value = row.arg(key);
                switch (f.kind() == null ? "" : f.kind()) {
                    case "ITEM", "BLOCK" -> {
                        String what = "BLOCK".equals(f.kind()) ? "Block" : "Item";
                        cur.add(flex(itemBox(f.kind(), value, what + ", like oak_log", put)));
                        wide = true;
                    }
                    case "OP" -> cur.add(fixed(op(f, value, put), OP_W));
                    case "CHOICE" -> {
                        cur.add(flex(condChoice(f, value, put)));
                        wide = true;
                    }
                    case "INT", "SECONDS" -> {
                        StepField box = number(value, "0", "SECONDS".equals(f.kind()), put);
                        if ("r".equals(key)) {
                            // the mock squeezes this beside an id box, which leaves it room for four letters of oak_log
                            if (wide) {
                                flush(cur, inner);
                                wide = false;
                            }
                            cur.add(unit("within"));
                            cur.add(fixed(box, COND_NUM_W));
                            cur.add(unit("blocks"));
                        } else {
                            if (spec.fields().size() == 2) {
                                cur.add(spacer());
                            }
                            cur.add(fixed(box, COND_NUM_W));
                        }
                    }
                    case "COORD" -> {
                        StepField box = text(value, f.label(), v -> put.accept(v.trim()));
                        // "at position" asks where you are, not where you are relative to you, so no ~ here
                        box.edit().setCharFilter(ch -> (ch >= '0' && ch <= '9') || ch == '-');
                        box.edit().setMaxLength(10);
                        cur.add(flex(box));
                        wide = true;
                    }
                    default -> {
                        cur.add(flex(suggest(text(value, title(f.label().toLowerCase(Locale.ROOT)), put), f.kind())));
                        wide = true;
                    }
                }
            }
            flush(cur, inner);
        }

        private Dropdown condChoice(FieldSpec f, String value, Consumer<String> put) {
            List<String> values = f.choices() == null ? List.of() : f.choices();
            List<String> labels = new ArrayList<>();
            for (String v : values) {
                labels.add(title(v));
            }
            return new Dropdown(theme, labels, Math.max(0, values.indexOf(value)), i -> put.accept(values.get(i)), ctx());
        }

        private void flush(List<Elem> cur, List<Part> inner) {
            if (!cur.isEmpty()) {
                inner.add(new RowPart(cur.toArray(new Elem[0])));
                cur.clear();
            }
        }

        void refresh() {
            GraphNode n = node();
            CondTree tree = n == null ? null : n.cond();
            // the card's own words for it, an If's summary is its condition
            String words = tree == null ? "no condition yet" : host.routines().summaryOf(new GraphNode("x", "if", 0, 0, Map.of(), tree));
            read.set(NodeEntries.pretty(words));
            for (BoxPart b : boxes) {
                b.refresh();
            }
            for (Runnable sync : pickerSyncs) {
                sync.run();
            }
        }
    }

    // what the validator says about one row by itself, so the red box goes on the row that's wrong
    private String rowProblem(CondDraft.Row row) {
        GraphNode start = draft().start();
        if (start == null) {
            return null;
        }
        CondDraft one = CondDraft.of(null);
        one.rows.add(row);
        GraphNode probe = new GraphNode("x", "if", 0, 0, Map.of(), one.toTree());
        Graph g = new Graph(null, "x", "", List.of(start, probe), List.of(new GraphEdge(start.id(), "out", "x", "in")));
        GraphCheck c = host.routines().validateGraph(g);
        NodeCheck nc = c == null || c.nodes() == null ? null : c.nodes().get("x");
        return nc == null ? null : nc.error();
    }

    // ----- parts: a column of things that know their height, stacked every frame. a handful per node, so nobody has to
    // remember to relayout when a line wraps

    private abstract class Part {
        int py;

        abstract int height(int pw);

        void place(int px, int ptop, int pw) {
        }

        void draw(Canvas c, Frame f) {
        }

        List<Widget> widgets() {
            return List.of();
        }

        void tick() {
        }
    }

    private final class LabelPart extends Part {
        private final String text;
        private int lx;

        LabelPart(String text) {
            this.text = text;
        }

        @Override
        int height(int pw) {
            return theme.px(12) + theme.px(LINE) + theme.px(GAP);
        }

        @Override
        void place(int px, int ptop, int pw) {
            lx = px;
            py = ptop;
        }

        @Override
        void draw(Canvas c, Frame f) {
            TextStyle s = theme.smallLabel;
            c.text(text, lx, py + theme.px(12) + c.ascent(s), s, theme.color(Theme.TEXT_2));
        }
    }

    private List<String> wrapped(String text, TextStyle s, int width) {
        return text.isEmpty() ? List.of() : TextWrap.wrap(theme.measurer, text, s, Math.max(1, width));
    }

    private final class TextPart extends Part {
        private final Supplier<String> text;
        private final int token;
        private final int margin;
        private int lx;
        private int lw;

        TextPart(Supplier<String> text, int token, int margin) {
            this.text = text;
            this.token = token;
            this.margin = margin;
        }

        @Override
        int height(int pw) {
            int n = wrapped(text.get(), theme.small, pw).size();
            return n == 0 ? 0 : theme.px(margin) + n * theme.px(LINE);
        }

        @Override
        void place(int px, int ptop, int pw) {
            lx = px;
            lw = pw;
            py = ptop;
        }

        @Override
        void draw(Canvas c, Frame f) {
            TextStyle s = theme.small;
            float ly = py + theme.px(margin);
            for (String l : wrapped(text.get(), s, lw)) {
                c.text(l, lx, ly + c.ascent(s), s, theme.color(token));
                ly += theme.px(LINE);
            }
        }
    }

    // the card only has room for one ellipsized line, this is where the whole reason goes
    private final class WhyPart extends Part {
        private final Supplier<String> text;
        private int lx;
        private int lw;

        WhyPart(Supplier<String> text) {
            this.text = text;
        }

        private int innerW(int pw) {
            return pw - 2 * theme.px(10);
        }

        @Override
        int height(int pw) {
            int n = wrapped(text.get(), theme.small, innerW(pw)).size();
            return n == 0 ? 0 : theme.px(12) + 2 * theme.px(8) + n * theme.px(LINE);
        }

        @Override
        void place(int px, int ptop, int pw) {
            lx = px;
            lw = pw;
            py = ptop;
        }

        @Override
        void draw(Canvas c, Frame f) {
            List<String> lines = wrapped(text.get(), theme.small, innerW(lw));
            if (lines.isEmpty()) {
                return;
            }
            int top = py + theme.px(12);
            int bh = 2 * theme.px(8) + lines.size() * theme.px(LINE);
            c.fillRoundRect(lx, top, lw, bh, theme.pxf(Theme.ROW_RADIUS), Theme.fade(theme.color(Theme.DANGER_FILL), 0.7f));
            float ly = top + theme.px(8);
            TextStyle s = theme.small;
            for (String l : lines) {
                c.text(l, lx + theme.px(10), ly + c.ascent(s), s, theme.color(Theme.DANGER));
                ly += theme.px(LINE);
            }
        }
    }

    // "Goes to yes when has oak_log >= 64": the condition itself reads a shade stronger, like the mock's <b>
    private final class ReadPart extends Part {
        private final String prefix;
        private String words = "";
        private int lx;
        private int lw;

        ReadPart(String prefix) {
            this.prefix = prefix + " ";
        }

        void set(String w) {
            words = w == null ? "" : w;
        }

        private List<String> lines(int pw) {
            return wrapped(prefix + words, theme.smallLabel, pw);
        }

        @Override
        int height(int pw) {
            return theme.px(8) + lines(pw).size() * theme.px(LINE);
        }

        @Override
        void place(int px, int ptop, int pw) {
            lx = px;
            lw = pw;
            py = ptop;
        }

        @Override
        void draw(Canvas c, Frame f) {
            TextStyle small = theme.small;
            TextStyle strong = theme.smallLabel;
            float ly = py + theme.px(8);
            boolean first = true;
            for (String l : lines(lw)) {
                float base = ly + c.ascent(small);
                if (first && l.startsWith(prefix)) {
                    c.text(prefix, lx, base, small, theme.color(Theme.TEXT_3));
                    c.text(l.substring(prefix.length()), lx + c.measure(prefix, small), base, strong, theme.color(Theme.TEXT_2));
                } else {
                    c.text(l, lx, base, strong, theme.color(Theme.TEXT_2));
                }
                first = false;
                ly += theme.px(LINE);
            }
        }
    }

    // a widget at a fixed design width, or a flexible one, or some words
    private record Elem(Widget widget, int width, String unit) {
    }

    private Elem fixed(Widget w, int design) {
        return new Elem(w, theme.px(design), null);
    }

    private Elem fixedPx(Widget w, int px) {
        return new Elem(w, px, null);
    }

    private Elem flex(Widget w) {
        return new Elem(w, 0, null);
    }

    private Elem unit(String s) {
        return new Elem(null, Math.round(theme.measurer.measure(s, theme.body)) + 1, s);
    }

    private Elem spacer() {
        return new Elem(null, 0, null);
    }

    private final class RowPart extends Part {
        private final int margin;
        private final Elem[] elems;
        private final List<Widget> widgets = new ArrayList<>();
        private final int[] ux;

        RowPart(Elem... elems) {
            this(0, elems);
        }

        RowPart(int margin, Elem... elems) {
            this.margin = margin;
            this.elems = elems;
            ux = new int[elems.length];
            for (Elem e : elems) {
                if (e.widget() != null) {
                    widgets.add(e.widget());
                }
            }
        }

        @Override
        int height(int pw) {
            return theme.px(margin) + theme.px(Theme.CONTROL_H);
        }

        @Override
        void place(int px, int ptop, int pw) {
            py = ptop;
            int ch = theme.px(Theme.CONTROL_H);
            int gap = theme.px(GAP);
            int top = ptop + theme.px(margin);
            int used = gap * (elems.length - 1);
            int flexes = 0;
            for (Elem e : elems) {
                used += e.width();
                if (e.width() == 0) {
                    flexes++;
                }
            }
            int each = flexes == 0 ? 0 : Math.max(0, (pw - used) / flexes);
            int cx = px;
            for (int i = 0; i < elems.length; i++) {
                Elem e = elems[i];
                int ew = e.width() == 0 ? each : e.width();
                if (e.widget() != null) {
                    // a 28 button sits centred against the 30 boxes next to it
                    int eh = e.widget() instanceof IconButton ? theme.px(28) : ch;
                    if (e.widget().x() != cx || e.widget().y() != top + (ch - eh) / 2 || e.widget().w() != ew || e.widget().h() != eh) {
                        e.widget().setBounds(cx, top + (ch - eh) / 2, ew, eh);
                    }
                }
                ux[i] = cx;
                cx += ew + gap;
            }
        }

        @Override
        void draw(Canvas c, Frame f) {
            int ch = theme.px(Theme.CONTROL_H);
            int top = py + theme.px(margin);
            TextStyle s = theme.body;
            for (int i = 0; i < elems.length; i++) {
                Elem e = elems[i];
                if (e.widget() != null) {
                    e.widget().draw(c, f);
                } else if (e.unit() != null) {
                    c.text(e.unit(), ux[i], top + ch / 2f + (c.ascent(s) - c.descent(s)) / 2f, s, theme.color(Theme.TEXT_2));
                }
            }
        }

        @Override
        List<Widget> widgets() {
            return widgets;
        }
    }

    // one condition row: a hairline box, red when it's the row that's wrong
    private final class BoxPart extends Part {
        private final List<Part> inner;
        private final BooleanSupplier red;
        private final List<Widget> widgets = new ArrayList<>();
        private boolean redNow;
        private int bx;
        private int bw;
        private int bh;

        BoxPart(List<Part> inner, BooleanSupplier red) {
            this.inner = inner;
            this.red = red;
            for (Part p : inner) {
                widgets.addAll(p.widgets());
            }
        }

        private int pad() {
            return theme.px(8);
        }

        @Override
        int height(int pw) {
            int hh = 2 * pad();
            int innerW = pw - 2 * pad();
            for (int i = 0; i < inner.size(); i++) {
                hh += inner.get(i).height(innerW) + (i > 0 ? theme.px(GAP) : 0);
            }
            return hh;
        }

        @Override
        void place(int px, int ptop, int pw) {
            py = ptop;
            bx = px;
            bw = pw;
            bh = height(pw);
            int cy = ptop + pad();
            int innerW = pw - 2 * pad();
            for (Part p : inner) {
                p.place(px + pad(), cy, innerW);
                cy += p.height(innerW) + theme.px(GAP);
            }
        }

        @Override
        void draw(Canvas c, Frame f) {
            c.strokeRoundRect(bx, py, bw, bh, theme.pxf(Theme.ROW_RADIUS), theme.color(redNow ? Theme.DANGER : Theme.HAIRLINE));
            for (Part p : inner) {
                p.draw(c, f);
            }
        }

        @Override
        List<Widget> widgets() {
            return widgets;
        }

        void refresh() {
            redNow = red.getAsBoolean();
        }
    }

    // the and / or between two rows, a short rule on either side
    private final class JoinPart extends Part {
        private final Segmented seg;
        private int lx;
        private int lw;

        JoinPart(Segmented seg) {
            this.seg = seg;
        }

        @Override
        int height(int pw) {
            return theme.px(36);
        }

        @Override
        void place(int px, int ptop, int pw) {
            py = ptop;
            lx = px;
            lw = pw;
            int sw = seg.preferredWidth();
            int sh = theme.px(24);
            seg.setBounds(px + (pw - sw) / 2, ptop + (theme.px(36) - sh) / 2, sw, sh);
        }

        @Override
        void draw(Canvas c, Frame f) {
            int mid = py + theme.px(36) / 2;
            int colour = theme.color(Theme.HAIRLINE);
            int gap = theme.px(8);
            c.fillRect(lx, mid, Math.max(0, seg.x() - gap - lx), 1, colour);
            int after = seg.x() + seg.w() + gap;
            c.fillRect(after, mid, Math.max(0, lx + lw - after), 1, colour);
            seg.draw(c, f);
        }

        @Override
        List<Widget> widgets() {
            return List.of(seg);
        }
    }

    // a get node: the list view's basket lines without the steppers, enter in the box adds one
    private final class BasketPart extends Part implements BasketLine.Owner {
        private final String id;
        private final RoutineDraft.Row row;
        private final List<BasketLine> lines = new ArrayList<>();
        private final StepField add;
        private final List<Widget> widgets = new ArrayList<>();
        private int bx;
        private int bw;

        BasketPart(String id, RoutineDraft.Row row) {
            this.id = id;
            this.row = row;
            add = new StepField(theme, "Add an item, like bread", StepField.Lead.SEARCH, null, t -> {
                if (t.isBlank()) {
                    return;
                }
                RoutineDraft.addItem(row, t);
                addField().setText("");
                structure();
            });
            add.setEditable(!host.readOnly());
            if (!host.readOnly()) {
                IdLists.attach(theme, add, ctx(), host.ids().items(), picked -> addField().commit());
            }
            sync();
        }

        private StepField addField() {
            return add;
        }

        private void sync() {
            while (lines.size() < row.items.size()) {
                lines.add(new BasketLine(theme, this, false));
            }
            while (lines.size() > row.items.size()) {
                lines.remove(lines.size() - 1).unfocus();
            }
            for (int i = 0; i < lines.size(); i++) {
                lines.get(i).bind(row.items.get(i), i);
            }
            widgets.clear();
            widgets.addAll(lines);
            if (!host.readOnly()) {
                widgets.add(add);
            }
        }

        void refresh() {
            boolean bad = red();
            for (BasketLine l : lines) {
                l.setRed(bad);
            }
        }

        private int boxH() {
            return lines.size() * theme.px(BASKET_LINE);
        }

        @Override
        int height(int pw) {
            int hh = boxH();
            return host.readOnly() ? hh : hh + (hh > 0 ? theme.px(GAP) : 0) + theme.px(Theme.CONTROL_H);
        }

        @Override
        void place(int px, int ptop, int pw) {
            py = ptop;
            bx = px;
            bw = pw;
            int lh = theme.px(BASKET_LINE);
            for (int i = 0; i < lines.size(); i++) {
                BasketLine l = lines.get(i);
                int ly = ptop + i * lh;
                if (l.x() != px || l.y() != ly || l.w() != pw) {
                    l.setBounds(px, ly, pw, lh);
                }
            }
            int ay = ptop + boxH() + (boxH() > 0 ? theme.px(GAP) : 0);
            if (add.x() != px || add.y() != ay || add.w() != pw) {
                add.setBounds(px, ay, pw, theme.px(Theme.CONTROL_H));
            }
        }

        @Override
        void draw(Canvas c, Frame f) {
            if (!lines.isEmpty()) {
                c.strokeRoundRect(bx, py, bw, boxH(), theme.pxf(Theme.ROW_RADIUS), theme.color(Theme.HAIRLINE));
            }
            boolean locked = host.readOnly();
            for (BasketLine l : lines) {
                l.drawLine(c, f, locked);
            }
            if (!locked) {
                add.draw(c, f);
            }
        }

        @Override
        List<Widget> widgets() {
            return widgets;
        }

        @Override
        void tick() {
            for (BasketLine l : lines) {
                l.settle();
            }
        }

        @Override
        public RoutineDraft.Row row() {
            return row;
        }

        @Override
        public boolean red() {
            return !problem().isEmpty();
        }

        @Override
        public boolean locked() {
            return host.readOnly();
        }

        @Override
        public ItemEntry item(String itemId) {
            return host.item(itemId);
        }

        @Override
        public void edited() {
            commitLine(id, row);
        }

        // a line came or went. the part keeps itself, so a caret in the add box stays where it is for the next item
        @Override
        public void structure() {
            sync();
            commitLine(id, row);
        }

        @Override
        public void blur() {
            ctx().blur();
        }
    }

    // ----- the scrolling middle

    private final class Body extends Widget {
        final Scroller scroller = new Scroller();
        private final List<Widget> live = new ArrayList<>();

        Body() {
            super(NodeInspector.this.theme);
        }

        void place() {
            int pad = theme.px(BODY_PAD);
            int pw = w - 2 * pad;
            int content = 0;
            for (Part p : parts) {
                content += p.height(pw);
            }
            scroller.setSizes(content + theme.px(4) + pad, h);
            int cy = Math.round(y + theme.px(4) - scroller.offset(now));
            live.clear();
            for (Part p : parts) {
                int ph = p.height(pw);
                p.place(x + pad, cy, pw);
                if (cy + ph > y && cy < y + h) {
                    // whole ones only. the shell focuses whatever's under a click by its bounds without asking the body,
                    // and a box half under the head would take your typing out of sight
                    for (Widget k : p.widgets()) {
                        if (k.y() >= y && k.y() + k.h() <= y + h) {
                            live.add(k);
                        } else {
                            // it's still drawn, and it won't hear about the mouse leaving now it isn't live
                            k.mouseMove(-1e6f, -1e6f);
                        }
                    }
                }
                cy += ph;
            }
        }

        @Override
        protected void layout() {
            place();
        }

        @Override
        public List<Widget> children() {
            return host.readOnly() ? List.of() : live;
        }

        @Override
        public boolean mouseDown(float mx, float my, int button, int mods) {
            // a box half scrolled out must not take a click that landed outside the body
            return contains(mx, my) && super.mouseDown(mx, my, button, mods);
        }

        @Override
        public Widget mouseMove(float mx, float my) {
            if (!contains(mx, my)) {
                for (Widget k : live) {
                    k.mouseMove(-1e6f, -1e6f);
                }
                hovered = false;
                return null;
            }
            return super.mouseMove(mx, my);
        }

        @Override
        public void draw(Canvas c, Frame f) {
            place();
            c.pushClip(x, y, w, h);
            try {
                for (Part p : parts) {
                    p.draw(c, f);
                }
            } finally {
                c.popClip();
            }
            NodeEntries.drawThumb(c, theme, scroller, now, hovered, x + w, y, h);
        }
    }

    // ----- the card around it

    @Override
    protected void layout() {
        int hh = theme.px(HEAD_H);
        int foot = footShown() ? theme.px(FOOT_H) : 0;
        int b = theme.px(28);
        closeBtn.setBounds(x + w - theme.px(8) - b, y + (hh - b) / 2, b, b);
        int dw = delBtn.preferredWidth();
        delBtn.setBounds(x + theme.px(8), y + h - foot + (foot - theme.px(Theme.CONTROL_H)) / 2, dw, theme.px(Theme.CONTROL_H));
        body.setBounds(x + 1, y + hh, w - 2, Math.max(0, h - hh - foot));
    }

    private boolean footShown() {
        GraphNode n = node();
        return n != null && !draft().isStart(n) && !host.readOnly();
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    private List<Widget> allWidgets() {
        List<Widget> out = new ArrayList<>();
        for (Part p : parts) {
            out.addAll(p.widgets());
        }
        return out;
    }

    private static boolean focusIn(List<Widget> widgets) {
        for (Widget k : widgets) {
            if (k.isFocused() || focusIn(k.children())) {
                return true;
            }
        }
        return false;
    }

    boolean focusWithin() {
        return focusIn(allWidgets());
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        host.pressed();
        super.mouseDown(mx, my, button, mods);
        return true;
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        if (!contains(mx, my)) {
            return false;
        }
        body.scroller.scrollBy(-dy * theme.px(40), now);
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        for (Part p : new ArrayList<>(parts)) {
            p.tick();
        }
    }

    @Override
    public void draw(Canvas c, Frame f) {
        now = f.nowNanos();
        GraphNode n = node();
        if (n == null) {
            return;
        }
        float r = theme.pxf(Theme.CARD_RADIUS);
        theme.card(c, x, y, w, h, r, true);
        drawHead(c, f, n);
        boolean locked = host.readOnly();
        body.draw(locked ? fade.reset(c, 0.6f, 0f) : c, f);
        if (footShown()) {
            int fy = y + h - theme.px(FOOT_H);
            c.fillRect(x + 1, fy, w - 2, 1, theme.color(Theme.HAIRLINE));
            delBtn.draw(c, f);
            TextStyle s = theme.small;
            String del = "Del";
            c.text(del, x + w - theme.px(12) - c.measure(del, s), fy + theme.px(FOOT_H) / 2f + (c.ascent(s) - c.descent(s)) / 2f, s, theme.color(Theme.TEXT_3));
        }
    }

    private void drawHead(Canvas c, Frame f, GraphNode n) {
        NodeSpec spec = draft().spec(n.type());
        String group = spec == null ? "end" : spec.group();
        int hh = theme.px(HEAD_H);
        int chip = theme.px(30);
        int cx = x + theme.px(12);
        int cy = y + (hh - chip) / 2;
        c.fillRoundRect(cx, cy, chip, chip, theme.px(8), chipColour(group));
        int is = theme.px(Theme.ICON);
        c.icon(NodeCard.iconFor(n.type(), group), cx + (chip - is) / 2f, cy + (chip - is) / 2f, is, NodeEntries.ink(theme, group));
        float tx = cx + chip + theme.px(10);
        float room = Math.max(0f, closeBtn.x() - theme.px(8) - tx);
        TextStyle l = theme.label;
        TextStyle s = theme.small;
        float lh = c.lineHeight(l);
        float sh = c.lineHeight(s);
        float top = y + (hh - lh - sh) / 2f;
        c.text(c.ellipsize(spec == null ? n.type() : spec.label(), l, room), tx, top + c.ascent(l), l, theme.color(Theme.TEXT));
        c.text(c.ellipsize(sub, s, room), tx, top + lh + c.ascent(s), s, theme.color(Theme.TEXT_3));
        closeBtn.draw(c, f);
        c.fillRect(x + 1, y + hh - 1, w - 2, 1, theme.color(Theme.HAIRLINE));
    }

    private int chipColour(String group) {
        float mix = theme.dark() ? 0.16f : 0.10f;
        int surface = theme.color(Theme.SURFACE);
        return switch (group == null ? "" : group) {
            case "act" -> Theme.lerp(surface, theme.color(Theme.ACCENT), mix);
            case "flow" -> Theme.lerp(surface, theme.color(Theme.FLOW), mix);
            default -> theme.color(Theme.RAISED);
        };
    }

    // test hooks
    Button deleteButton() {
        return delBtn;
    }

    IconButton closeButton() {
        return closeBtn;
    }

    List<Widget> bodyWidgets() {
        return allWidgets();
    }

    Widget bodyBox() {
        return body;
    }

    String summaryLine() {
        return sub;
    }

    boolean hasNote(String text) {
        for (Part part : parts) {
            if (part instanceof TextPart t && text.equals(t.text.get())) {
                return true;
            }
        }
        return false;
    }
}
