package adris.altoclef.commands.graph;

import java.util.List;
import java.util.Map;

// a condition: a leaf question, or all / any / not over more of them. one record and not a little class family so the
// file, the facade and the inspector all hold the same simple thing
public record Cond(Op op, String kind, Map<String, String> args, List<Cond> children) {

    public enum Op {
        ALL,
        ANY,
        NOT,
        LEAF
    }

    public Cond {
        args = args == null ? Map.of() : Map.copyOf(args);
        children = children == null ? List.of() : List.copyOf(children);
    }

    public static Cond leaf(String kind, Map<String, String> args) {
        return new Cond(Op.LEAF, kind, args, null);
    }

    public static Cond all(Cond... of) {
        return new Cond(Op.ALL, null, null, List.of(of));
    }

    public static Cond any(Cond... of) {
        return new Cond(Op.ANY, null, null, List.of(of));
    }

    public static Cond not(Cond of) {
        return new Cond(Op.NOT, null, null, List.of(of));
    }

    public String arg(String key) {
        String v = args.get(key);
        // the validator trims too, or a hand edited " <=" would pass there and read as the default here
        return v == null ? "" : v.trim();
    }
}
