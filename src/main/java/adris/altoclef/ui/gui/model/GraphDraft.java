package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.RoutinesAccess;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondTree;
import adris.altoclef.ui.gui.facade.RoutinesAccess.FieldSpec;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Graph;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphEdge;
import adris.altoclef.ui.gui.facade.RoutinesAccess.GraphNode;
import adris.altoclef.ui.gui.facade.RoutinesAccess.NodeSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

// the node editor's state: one routine as nodes and wires, the same job RoutineDraft does for the list. every rule still
// comes from the facade (ports from outputsOf, loop bodies from bodyOf), so a node type added tomorrow just works here
public final class GraphDraft {

    private static final String IN = "in";
    private static final String AGAIN = "again";
    private static final String MODE = "mode";
    // where a click in the palette never lands on its own: these are for going wrong, you wire them on purpose
    private static final Set<String> NOT_NEXT = Set.of("failed", "timeout");
    // numbers a person would type first. cosmetic, anything not here starts at its lower limit or empty
    private static final Map<String, String> BLANK = Map.of(
            "mine.n", "16",
            "craft.n", "1",
            "follow.secs", "60",
            "repeat.n", "5",
            "repeat.cap", "20",
            "wait.secs", "5",
            "wait.timeout", "300");

    private final RoutinesAccess a;
    private final Map<String, NodeSpec> specs = new HashMap<>();
    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    // id = what it's saved as. null = never saved
    public final String id;
    // a graph file already. false = a list routine looked at as nodes, or a new one
    public final boolean file;
    // a file that didn't parse: you can look at the message, nothing else
    public final String badFile;
    private final String savedName;
    private String name;
    private String description;
    private Graph snapshot;
    private final Graph loaded;
    private Boolean dirtyCache;

    private GraphDraft(RoutinesAccess a, String id, boolean file, String badFile, String name, String description, Graph loaded) {
        this.a = a;
        this.id = id;
        this.file = file;
        this.badFile = badFile;
        this.savedName = name;
        this.name = name;
        this.description = description;
        this.loaded = loaded;
        for (NodeSpec s : a.nodeSpecs()) {
            specs.put(s.type(), s);
        }
    }

    public static GraphDraft blank(RoutinesAccess a) {
        GraphDraft d = new GraphDraft(a, null, false, null, "", "", null);
        d.nodes.add(new GraphNode("s", startType(a), 0, 0, new LinkedHashMap<>(), null));
        d.snapshot = d.toGraph();
        return d;
    }

    public static GraphDraft badFile(RoutinesAccess a, String id, String name, String error) {
        GraphDraft d = new GraphDraft(a, id, true, error, name == null ? id : name, "", null);
        d.snapshot = d.toGraph();
        return d;
    }

    public static GraphDraft open(RoutinesAccess a, Graph g, boolean file) {
        GraphDraft d = new GraphDraft(a, g.id(), file, null, nz(g.name()), nz(g.description()), g);
        if (g.nodes() != null) {
            d.nodes.addAll(g.nodes());
        }
        if (g.edges() != null) {
            for (GraphEdge e : g.edges()) {
                d.edges.add(new GraphEdge(e.from(), e.port(), e.to(), e.toPort() == null ? IN : e.toPort()));
            }
        }
        // normalized first: a graph that only differs from its file by a wire's "again" isn't an edit anybody made
        d.normalizeRepeatWires();
        d.snapshot = d.toGraph();
        // a wire whose port is gone (a hand edit, a wait that became seconds) can't be clicked. this one is a real change
        if (d.pruneDeadWires()) {
            d.normalizeRepeatWires();
        }
        d.dirtyCache = null;
        return d;
    }

    // the type with no inputs is the one the graph starts at
    private static String startType(RoutinesAccess a) {
        for (NodeSpec s : a.nodeSpecs()) {
            if (s.inputs() == null || s.inputs().isEmpty()) {
                return s.type();
            }
        }
        return "start";
    }

    // ----- reading

    public List<GraphNode> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<GraphEdge> edges() {
        return Collections.unmodifiableList(edges);
    }

    public GraphNode node(String nodeId) {
        for (GraphNode n : nodes) {
            if (n.id().equals(nodeId)) {
                return n;
            }
        }
        return null;
    }

    public NodeSpec spec(String type) {
        return specs.get(type);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public boolean isStart(GraphNode n) {
        NodeSpec s = specs.get(n.type());
        return s != null && (s.inputs() == null || s.inputs().isEmpty());
    }

    public GraphNode start() {
        for (GraphNode n : nodes) {
            if (isStart(n)) {
                return n;
            }
        }
        return null;
    }

    public Graph toGraph() {
        return new Graph(id, name, description, List.copyOf(nodes), List.copyOf(edges));
    }

    public boolean dirty() {
        if (dirtyCache == null) {
            dirtyCache = !toGraph().equals(snapshot);
        }
        return dirtyCache;
    }

    // the same rule as RoutineDraft.shownId, and the store makes the id out of the name the same way
    public String shownId() {
        return id != null && name.equals(savedName) ? id : RoutineDraft.idFor(name);
    }

    // the file changed under us: what we opened isn't what's stored any more
    public boolean staleAgainst(Graph fresh) {
        return loaded != null && fresh != null && !loaded.equals(fresh);
    }

    public List<String> outputsOf(GraphNode n) {
        List<String> outs = a.outputsOf(n);
        return outs == null ? List.of() : outs;
    }

    public boolean wired(String nodeId, String port) {
        for (GraphEdge e : edges) {
            if (e.from().equals(nodeId) && e.port().equals(port)) {
                return true;
            }
        }
        return false;
    }

    public boolean inWired(String nodeId) {
        for (GraphEdge e : edges) {
            if (e.to().equals(nodeId)) {
                return true;
            }
        }
        return false;
    }

    // what a click in the palette hangs the new node off. null = this node has no room left
    public String firstFreePort(String nodeId) {
        GraphNode n = node(nodeId);
        if (n == null) {
            return null;
        }
        for (String p : outputsOf(n)) {
            if (!NOT_NEXT.contains(p) && !wired(nodeId, p)) {
                return p;
            }
        }
        return null;
    }

    // start, then along the wires that carry on. a list routine's nth step is the nth node on this. RunStatus only ever
    // says a step number, so it's what the highlight has for a run that isn't a graph run
    public List<String> chain() {
        List<String> out = new ArrayList<>();
        GraphNode at = start();
        Set<String> seen = new HashSet<>();
        while (at != null && seen.add(at.id())) {
            String next = null;
            for (GraphEdge e : edges) {
                if (e.from().equals(at.id()) && !NOT_NEXT.contains(e.port())) {
                    next = e.to();
                    break;
                }
            }
            GraphNode to = next == null ? null : node(next);
            if (to != null && !outputsOf(to).isEmpty()) {
                out.add(to.id());
            }
            at = to;
        }
        return out;
    }

    // ----- changing it. the name and description are the only things the fields write straight

    public void setName(String s) {
        name = nz(s);
        dirtyCache = null;
    }

    public void setDescription(String s) {
        description = nz(s);
        dirtyCache = null;
    }

    private String freeId() {
        for (int k = 1; ; k++) {
            String candidate = "n" + k;
            if (node(candidate) == null) {
                return candidate;
            }
        }
    }

    public String add(String type, int x, int y) {
        String nid = freeId();
        GraphNode n = new GraphNode(nid, type, x, y, blankParams(type), null);
        nodes.add(seedCond(n));
        changed();
        return nid;
    }

    private Map<String, String> blankParams(String type) {
        Map<String, String> out = new LinkedHashMap<>();
        NodeSpec s = specs.get(type);
        if (s == null || s.fields() == null) {
            return out;
        }
        for (FieldSpec f : s.fields()) {
            String v = BLANK.get(type + "." + f.key());
            if (v == null && "CHOICE".equals(f.kind()) && f.choices() != null && !f.choices().isEmpty()) {
                v = f.choices().get(0);
            }
            if (v == null && "INT".equals(f.kind()) && f.required() && f.min() > 0) {
                v = String.valueOf(f.min());
            }
            if (v != null) {
                out.put(f.key(), v);
            }
        }
        return out;
    }

    // a node that's asking for a condition right now gets a row to start from, like the mock's blankParams. one that
    // isn't (a wait in seconds) gets nothing, a hidden condition in the file would just be something to explain
    private GraphNode seedCond(GraphNode n) {
        return n.cond() == null && asksForCond(n) ? new GraphNode(n.id(), n.type(), n.x(), n.y(), n.params(), CondDraft.blankTree(a.condSpecs())) : n;
    }

    private boolean asksForCond(GraphNode n) {
        NodeSpec s = specs.get(n.type());
        if (s == null || s.fields() == null) {
            return false;
        }
        for (FieldSpec f : s.fields()) {
            if ("COND".equals(f.kind()) && asked(f, n)) {
                return true;
            }
        }
        return false;
    }

    public static boolean asked(FieldSpec f, GraphNode n) {
        return f.whenMode() == null || f.whenMode().equals(n.params().get(MODE));
    }

    // start can't go, it's what a run begins at
    public boolean remove(String nodeId) {
        GraphNode n = node(nodeId);
        if (n == null || isStart(n)) {
            return false;
        }
        nodes.remove(n);
        edges.removeIf(e -> e.from().equals(nodeId) || e.to().equals(nodeId));
        normalizeRepeatWires();
        changed();
        return true;
    }

    // one wire per output, a new one replaces it. inputs take as many as you like. never into start, never into itself
    public boolean connect(String from, String port, String to) {
        GraphNode target = node(to);
        if (node(from) == null || target == null || from.equals(to) || isStart(target)) {
            return false;
        }
        edges.removeIf(e -> e.from().equals(from) && e.port().equals(port));
        edges.add(new GraphEdge(from, port, to, IN));
        normalizeRepeatWires();
        changed();
        return true;
    }

    public boolean disconnect(String key) {
        boolean gone = edges.removeIf(e -> keyOf(e).equals(key));
        if (gone) {
            normalizeRepeatWires();
            changed();
        }
        return gone;
    }

    public static String keyOf(GraphEdge e) {
        return e.from() + ":" + e.port() + ":" + e.to() + ":" + e.toPort();
    }

    public void move(String nodeId, int x, int y) {
        int i = indexOf(nodeId);
        if (i >= 0) {
            GraphNode n = nodes.get(i);
            nodes.set(i, new GraphNode(n.id(), n.type(), x, y, n.params(), n.cond()));
            changed();
        }
    }

    public void setParam(String nodeId, String key, String value) {
        int i = indexOf(nodeId);
        if (i < 0) {
            return;
        }
        GraphNode n = nodes.get(i);
        if (Objects.equals(n.params().get(key), value)) {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>(n.params());
        params.put(key, value == null ? "" : value);
        nodes.set(i, seedCond(new GraphNode(n.id(), n.type(), n.x(), n.y(), params, n.cond())));
        // a wait going from until to seconds loses its timeout port, and a wire nobody can click stays behind otherwise
        if (pruneDeadWires()) {
            normalizeRepeatWires();
        }
        changed();
    }

    public void setCond(String nodeId, CondTree cond) {
        int i = indexOf(nodeId);
        if (i >= 0) {
            GraphNode n = nodes.get(i);
            nodes.set(i, new GraphNode(n.id(), n.type(), n.x(), n.y(), n.params(), cond));
            changed();
        }
    }

    private int indexOf(String nodeId) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id().equals(nodeId)) {
                return i;
            }
        }
        return -1;
    }

    private void changed() {
        dirtyCache = null;
    }

    private boolean pruneDeadWires() {
        return edges.removeIf(e -> {
            GraphNode from = node(e.from());
            return from != null && !outputsOf(from).contains(e.port());
        });
    }

    // says which wires into a repeat are its own body coming back ("again") and which are the way in. it depends on
    // everything downstream, so it runs after every structural change and not only when a wire lands on the repeat:
    // wire end -> repeat first and body -> chain after, and that first wire has to flip to again.
    // outer repeats first, because an inner repeat's own again wires are what stop the outer body from looking like it
    // never ends. each repeat is asked with all its incoming wires forced to again, since again wires aren't walked and
    // that's the only way bodyOf sees just the body
    public void normalizeRepeatWires() {
        Set<String> repeats = new HashSet<>();
        for (GraphNode n : nodes) {
            NodeSpec s = specs.get(n.type());
            if (s != null && s.inputs() != null && s.inputs().contains(AGAIN)) {
                repeats.add(n.id());
            }
        }
        for (int i = 0; i < edges.size(); i++) {
            GraphEdge e = edges.get(i);
            if (repeats.contains(e.to()) && !IN.equals(e.toPort())) {
                edges.set(i, new GraphEdge(e.from(), e.port(), e.to(), IN));
            }
        }
        for (String repeat : repeatOrder(repeats)) {
            classify(repeat);
        }
        dirtyCache = null;
    }

    private void classify(String repeat) {
        List<GraphEdge> forced = new ArrayList<>(edges.size());
        for (GraphEdge e : edges) {
            forced.add(e.to().equals(repeat) ? new GraphEdge(e.from(), e.port(), e.to(), AGAIN) : e);
        }
        Set<String> body = a.bodyOf(new Graph(id, name, description, List.copyOf(nodes), forced), repeat);
        for (int i = 0; i < edges.size(); i++) {
            GraphEdge e = edges.get(i);
            if (e.to().equals(repeat)) {
                edges.set(i, new GraphEdge(e.from(), e.port(), e.to(), body != null && body.contains(e.from()) ? AGAIN : IN));
            }
        }
    }

    // preorder from start over every wire, so an outer repeat comes before what's inside it. what start doesn't reach goes
    // last, by id, so the answer doesn't depend on the order things got made in
    private List<String> repeatOrder(Set<String> repeats) {
        Map<String, List<String>> outs = new HashMap<>();
        for (GraphEdge e : edges) {
            outs.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
        }
        List<String> order = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        GraphNode s = start();
        if (s != null) {
            walk(s.id(), outs, repeats, seen, order);
        }
        for (String r : new TreeSet<>(repeats)) {
            if (!order.contains(r)) {
                order.add(r);
            }
        }
        return order;
    }

    // deep graphs exist (a 200 node chain), so this keeps its own stack
    private static void walk(String from, Map<String, List<String>> outs, Set<String> repeats, Set<String> seen, List<String> order) {
        List<String> stack = new ArrayList<>(List.of(from));
        while (!stack.isEmpty()) {
            String at = stack.remove(stack.size() - 1);
            if (!seen.add(at)) {
                continue;
            }
            if (repeats.contains(at)) {
                order.add(at);
            }
            List<String> next = outs.getOrDefault(at, List.of());
            for (int i = next.size() - 1; i >= 0; i--) {
                stack.add(next.get(i));
            }
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
