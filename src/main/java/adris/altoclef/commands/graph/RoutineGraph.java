package adris.altoclef.commands.graph;

import adris.altoclef.commands.RoutineStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// a routine as a graph: nodes and the wires between them. nothing in here checks anything, a half built graph has to
// be able to exist and get saved (GraphValidator says what's wrong with it, GraphFile says if it's a graph at all)
public record RoutineGraph(String id, String name, String description, List<Node> nodes, List<Edge> edges) {

    public static final String IN = "in";
    public static final String AGAIN = "again";
    public static final String OUT = "out";
    public static final String OK = "ok";
    public static final String FAILED = "failed";
    public static final String TIMEOUT = "timeout";
    public static final String YES = "yes";
    public static final String NO = "no";
    public static final String BODY = "body";
    public static final String DONE = "done";

    public static final String MODE_TIMES = "times";
    public static final String MODE_UNTIL = "until";
    public static final String MODE_FOREVER = "forever";
    public static final String MODE_SECS = "secs";

    public static final int CHAIN_X = 160;
    public static final int CHAIN_STEP = 224;

    public RoutineGraph {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public enum Type {
        START("Start", null),
        GET("Get items", RoutineStep.Kind.GET),
        EQUIP("Equip", RoutineStep.Kind.EQUIP),
        GOTO("Go to", RoutineStep.Kind.GOTO),
        RAW("Raw command", RoutineStep.Kind.RAW),
        IF("If", null),
        REPEAT("Repeat", null),
        WAIT("Wait", null),
        STOP("Stop", null);

        public final String label;
        // the step kind an action node runs, null for the flow nodes
        public final RoutineStep.Kind action;

        Type(String label, RoutineStep.Kind action) {
            this.label = label;
            this.action = action;
        }

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Type named(String name) {
            for (Type t : values()) {
                if (t.wire().equals(name)) {
                    return t;
                }
            }
            return null;
        }
    }

    // params are strings on purpose: the inspector holds half typed numbers, and those have to survive a save
    public record Node(String id, Type type, int x, int y, Map<String, String> params, Cond cond) {
        public Node {
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        public String param(String key) {
            String v = params.get(key);
            return v == null ? "" : v;
        }
    }

    public record Edge(String from, String port, String to, String toPort) {
        public Edge {
            toPort = toPort == null ? IN : toPort;
        }

        public boolean again() {
            return AGAIN.equals(toPort);
        }

        // what the status keeps for a wire that ran
        public String key() {
            return from + ":" + port + ":" + to + ":" + toPort;
        }
    }

    public Map<String, Node> byId() {
        Map<String, Node> out = new HashMap<>();
        for (Node n : nodes) {
            out.putIfAbsent(n.id(), n);
        }
        return out;
    }

    public Node start() {
        for (Node n : nodes) {
            if (n.type() == Type.START) {
                return n;
            }
        }
        return null;
    }

    // a wait only has a timeout port while it can time out
    public static List<String> outputs(Node n) {
        return switch (n.type()) {
            case START -> List.of(OUT);
            case GET, EQUIP, GOTO, RAW -> List.of(OK, FAILED);
            case IF -> List.of(YES, NO);
            case REPEAT -> List.of(BODY, DONE);
            case WAIT -> MODE_UNTIL.equals(n.param("mode")) ? List.of(OK, TIMEOUT) : List.of(OK);
            case STOP -> List.of();
        };
    }

    public static List<String> inputs(Type type) {
        return switch (type) {
            case START -> List.of();
            case REPEAT -> List.of(IN, AGAIN);
            default -> List.of(IN);
        };
    }

    // a list routine as a graph: start, then a row. the same steps, so it runs the same
    public static RoutineGraph ofSteps(String id, String name, String description, List<RoutineStep> steps) {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        nodes.add(new Node("s", Type.START, 0, 0, null, null));
        String last = "s";
        String port = OUT;
        for (int i = 0; i < steps.size(); i++) {
            String nid = "n" + (i + 1);
            nodes.add(nodeOf(nid, CHAIN_X + i * CHAIN_STEP, steps.get(i)));
            edges.add(new Edge(last, port, nid, null));
            last = nid;
            port = OK;
        }
        return new RoutineGraph(id, name, description, nodes, edges);
    }

    private static Node nodeOf(String id, int x, RoutineStep s) {
        if (s.kind() == RoutineStep.Kind.WAIT) {
            String line = s.line().trim();
            int space = line.indexOf(' ');
            return new Node(id, Type.WAIT, x, 0, Map.of("mode", MODE_SECS, "secs", space < 0 ? "" : line.substring(space + 1).trim()), null);
        }
        Type type = switch (s.kind()) {
            case GET -> Type.GET;
            case EQUIP -> Type.EQUIP;
            case GOTO -> Type.GOTO;
            default -> Type.RAW;
        };
        return new Node(id, type, x, 0, Map.of("line", s.line()), null);
    }

    // the reverse: the steps of a straight graph (start, actions on ok wires, maybe a plain stop), null for anything
    // the list view can't show
    public List<RoutineStep> toSteps() {
        Map<String, Node> by = byId();
        Node at = start();
        if (at == null) {
            return null;
        }
        List<RoutineStep> steps = new ArrayList<>();
        int seen = 1;
        while (true) {
            Edge wire = onlyWireOutOf(at);
            if (wire == null) {
                // null is also "more than one wire" or "a wire on failed", and those aren't straight
                if (hasWireOut(at)) {
                    return null;
                }
                break;
            }
            Node next = by.get(wire.to());
            if (next == null || wire.again() || seen >= nodes.size()) {
                return null;
            }
            seen++;
            if (next.type() == Type.STOP) {
                if ("false".equals(next.param("ok"))) {
                    return null;
                }
                break;
            }
            RoutineStep step = stepOf(next);
            if (step == null) {
                return null;
            }
            steps.add(step);
            at = next;
        }
        // something hanging off the side isn't straight either
        return seen == nodes.size() ? steps : null;
    }

    private Edge onlyWireOutOf(Node n) {
        Edge only = null;
        for (Edge e : edges) {
            if (e.from().equals(n.id())) {
                if (only != null || !(e.port().equals(OK) || e.port().equals(OUT))) {
                    return null;
                }
                only = e;
            }
        }
        return only;
    }

    private boolean hasWireOut(Node n) {
        for (Edge e : edges) {
            if (e.from().equals(n.id())) {
                return true;
            }
        }
        return false;
    }

    private static RoutineStep stepOf(Node n) {
        if (n.type().action != null) {
            return new RoutineStep(n.type().action, n.param("line"));
        }
        return n.type() == Type.WAIT && !MODE_UNTIL.equals(n.param("mode")) ? new RoutineStep(RoutineStep.Kind.WAIT, "wait " + n.param("secs")) : null;
    }
}
