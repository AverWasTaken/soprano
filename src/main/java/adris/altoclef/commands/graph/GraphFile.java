package adris.altoclef.commands.graph;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// routines/<id>.json <-> RoutineGraph. the file only has to be a graph, a half built one is fine (that's GraphValidator's
// department). the id is the file's name and isn't in the file, so a copied file can't disagree with itself
public final class GraphFile {

    public static final String FORMAT = "soprano-routine-graph";
    public static final int VERSION = 1;
    public static final int MAX_NODES = 200;
    public static final int MAX_EDGES = 400;
    // not the real limit (that's Conditions.MAX_DEPTH), this only stops a silly file from walking off the stack
    private static final int MAX_COND_NESTING = 16;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final class BadFile extends Exception {
        BadFile(String why) {
            super(why);
        }
    }

    private GraphFile() {
    }

    public static RoutineGraph parse(String id, String text) throws BadFile {
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw new BadFile("it's not valid json: " + e.getMessage());
        }
        if (!root.isJsonObject()) {
            throw new BadFile("it should be one { ... } object");
        }
        JsonObject o = root.getAsJsonObject();
        if (!FORMAT.equals(string(o, "format"))) {
            throw new BadFile("it's not a " + FORMAT + " file");
        }
        checkVersion(o);
        return new RoutineGraph(id, string(o, "name"), string(o, "description"), nodes(o), edges(o));
    }

    private static void checkVersion(JsonObject o) throws BadFile {
        JsonElement v = o.get("version");
        if (!(v instanceof JsonPrimitive p) || !p.isNumber() || p.getAsDouble() != Math.floor(p.getAsDouble())) {
            throw new BadFile("its version isn't a whole number");
        }
        int version = p.getAsInt();
        if (version > VERSION) {
            throw new BadFile("it's from a newer Soprano (version " + version + "), this one only knows " + VERSION);
        }
        if (version < 1) {
            throw new BadFile("it has version " + version + ", which never existed");
        }
    }

    private static List<RoutineGraph.Node> nodes(JsonObject o) throws BadFile {
        JsonArray raw = array(o, "nodes");
        if (raw.size() > MAX_NODES) {
            throw new BadFile("that is more than " + MAX_NODES + " nodes");
        }
        List<RoutineGraph.Node> nodes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement e : raw) {
            RoutineGraph.Node n = node(e);
            if (!ids.add(n.id())) {
                throw new BadFile("there are two nodes called " + n.id());
            }
            nodes.add(n);
        }
        return nodes;
    }

    private static List<RoutineGraph.Edge> edges(JsonObject o) throws BadFile {
        JsonArray raw = array(o, "edges");
        if (raw.size() > MAX_EDGES) {
            throw new BadFile("that is more than " + MAX_EDGES + " wires");
        }
        List<RoutineGraph.Edge> edges = new ArrayList<>();
        for (JsonElement e : raw) {
            edges.add(edge(e));
        }
        return edges;
    }

    private static RoutineGraph.Node node(JsonElement e) throws BadFile {
        if (!e.isJsonObject()) {
            throw new BadFile("a node should be a { ... } object");
        }
        JsonObject o = e.getAsJsonObject();
        String id = string(o, "id");
        if (id == null || id.isEmpty()) {
            throw new BadFile("a node has no id");
        }
        RoutineGraph.Type type = RoutineGraph.Type.named(string(o, "type"));
        if (type == null) {
            throw new BadFile("node " + id + " has type \"" + string(o, "type") + "\", which this Soprano doesn't know");
        }
        Cond cond = o.has("cond") && !o.get("cond").isJsonNull() ? cond(o.get("cond"), 1) : null;
        return new RoutineGraph.Node(id, type, whole(o, "x", id), whole(o, "y", id), strings(o.get("params"), "node " + id + " params"), cond);
    }

    private static RoutineGraph.Edge edge(JsonElement e) throws BadFile {
        if (!e.isJsonObject()) {
            throw new BadFile("a wire should be a { ... } object");
        }
        JsonObject o = e.getAsJsonObject();
        String from = string(o, "from");
        String port = string(o, "port");
        String to = string(o, "to");
        if (from == null || port == null || to == null) {
            throw new BadFile("a wire needs from, port and to");
        }
        return new RoutineGraph.Edge(from, port, to, string(o, "toPort"));
    }

    private static Cond cond(JsonElement e, int nesting) throws BadFile {
        if (nesting > MAX_COND_NESTING) {
            throw new BadFile("a condition is nested too deep");
        }
        if (!e.isJsonObject()) {
            throw new BadFile("a condition should be a { ... } object");
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("leaf")) {
            Map<String, String> args = new LinkedHashMap<>(strings(o, "a condition"));
            String kind = args.remove("leaf");
            return Cond.leaf(kind, args);
        }
        if (o.has("not")) {
            return Cond.not(cond(o.get("not"), nesting + 1));
        }
        Cond.Op op = o.has("all") ? Cond.Op.ALL : o.has("any") ? Cond.Op.ANY : null;
        if (op == null) {
            throw new BadFile("a condition is leaf, all, any or not");
        }
        JsonElement list = o.get(op == Cond.Op.ALL ? "all" : "any");
        if (!list.isJsonArray()) {
            throw new BadFile("all and any take a [ ... ] list");
        }
        List<Cond> kids = new ArrayList<>();
        for (JsonElement k : list.getAsJsonArray()) {
            kids.add(cond(k, nesting + 1));
        }
        return new Cond(op, null, null, kids);
    }

    private static Map<String, String> strings(JsonElement e, String what) throws BadFile {
        Map<String, String> out = new LinkedHashMap<>();
        if (e == null || e.isJsonNull()) {
            return out;
        }
        if (!e.isJsonObject()) {
            throw new BadFile(what + " should be a { ... } object");
        }
        for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
            // a hand edit writes "n": 5 and it means the same as "5"
            if (!(kv.getValue() instanceof JsonPrimitive p)) {
                throw new BadFile(what + ": " + kv.getKey() + " should be plain text or a number");
            }
            out.put(kv.getKey(), p.getAsString());
        }
        return out;
    }

    private static String string(JsonObject o, String key) throws BadFile {
        JsonElement v = o.get(key);
        if (v == null || v.isJsonNull()) {
            return null;
        }
        if (!(v instanceof JsonPrimitive p)) {
            throw new BadFile(key + " should be plain text");
        }
        return p.getAsString();
    }

    private static int whole(JsonObject o, String key, String id) throws BadFile {
        JsonElement v = o.get(key);
        if (v == null || v.isJsonNull()) {
            return 0;
        }
        if (!(v instanceof JsonPrimitive p) || !p.isNumber()) {
            throw new BadFile("node " + id + ": " + key + " should be a number");
        }
        return (int) Math.round(p.getAsDouble());
    }

    private static JsonArray array(JsonObject o, String key) throws BadFile {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonArray()) {
            throw new BadFile("it needs a " + key + " list");
        }
        return v.getAsJsonArray();
    }

    public static String write(RoutineGraph g) {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("version", VERSION);
        o.addProperty("name", g.name());
        o.addProperty("description", g.description());
        JsonArray nodes = new JsonArray();
        for (RoutineGraph.Node n : g.nodes()) {
            nodes.add(toJson(n));
        }
        o.add("nodes", nodes);
        JsonArray edges = new JsonArray();
        for (RoutineGraph.Edge e : g.edges()) {
            JsonObject j = new JsonObject();
            j.addProperty("from", e.from());
            j.addProperty("port", e.port());
            j.addProperty("to", e.to());
            if (!RoutineGraph.IN.equals(e.toPort())) {
                j.addProperty("toPort", e.toPort());
            }
            edges.add(j);
        }
        o.add("edges", edges);
        return GSON.toJson(o) + "\n";
    }

    private static JsonObject toJson(RoutineGraph.Node n) {
        JsonObject j = new JsonObject();
        j.addProperty("id", n.id());
        j.addProperty("type", n.type().wire());
        j.addProperty("x", n.x());
        j.addProperty("y", n.y());
        if (!n.params().isEmpty()) {
            JsonObject p = new JsonObject();
            // Map.copyOf has no order, and a file that reshuffles on every save is a file a diff can't read
            n.params().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(kv -> p.addProperty(kv.getKey(), kv.getValue()));
            j.add("params", p);
        }
        if (n.cond() != null) {
            j.add("cond", toJson(n.cond()));
        }
        return j;
    }

    private static JsonObject toJson(Cond c) {
        JsonObject j = new JsonObject();
        switch (c.op()) {
            case LEAF -> {
                // an unpicked row saves as "", which reads back as the same "pick a condition"
                j.addProperty("leaf", c.kind() == null ? "" : c.kind());
                c.args().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(kv -> j.addProperty(kv.getKey(), kv.getValue()));
            }
            case NOT -> j.add("not", toJson(c.children().isEmpty() ? new Cond(Cond.Op.ALL, null, null, null) : c.children().get(0)));
            default -> {
                JsonArray kids = new JsonArray();
                c.children().forEach(k -> kids.add(toJson(k)));
                j.add(c.op() == Cond.Op.ALL ? "all" : "any", kids);
            }
        }
        return j;
    }
}
