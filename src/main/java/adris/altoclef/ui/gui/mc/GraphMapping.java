package adris.altoclef.ui.gui.mc;

import adris.altoclef.commands.graph.Cond;
import adris.altoclef.commands.graph.GraphSpecs;
import adris.altoclef.commands.graph.RoutineGraph;
import adris.altoclef.ui.gui.facade.RoutinesAccess;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// the facade's plain records <-> the commands side's graph. the facade doesn't import anything from outside the gui, same
// as for the list routines. pure, no game in here
final class GraphMapping {

    private GraphMapping() {
    }

    static RoutinesAccess.Graph toFacade(RoutineGraph g) {
        List<RoutinesAccess.GraphNode> nodes = new ArrayList<>();
        for (RoutineGraph.Node n : g.nodes()) {
            nodes.add(new RoutinesAccess.GraphNode(n.id(), n.type().wire(), n.x(), n.y(), n.params(), n.cond() == null ? null : toFacade(n.cond())));
        }
        List<RoutinesAccess.GraphEdge> edges = new ArrayList<>();
        for (RoutineGraph.Edge e : g.edges()) {
            edges.add(new RoutinesAccess.GraphEdge(e.from(), e.port(), e.to(), e.toPort()));
        }
        return new RoutinesAccess.Graph(g.id(), g.name(), g.description(), nodes, edges);
    }

    private static RoutinesAccess.CondTree toFacade(Cond c) {
        List<RoutinesAccess.CondTree> kids = new ArrayList<>();
        c.children().forEach(k -> kids.add(toFacade(k)));
        return new RoutinesAccess.CondTree(c.op().name().toLowerCase(Locale.ROOT), c.kind(), c.args(), kids);
    }

    static RoutineGraph fromFacade(RoutinesAccess.Graph g) {
        List<RoutineGraph.Node> nodes = new ArrayList<>();
        for (RoutinesAccess.GraphNode n : g.nodes() == null ? List.<RoutinesAccess.GraphNode>of() : g.nodes()) {
            nodes.add(fromFacade(n));
        }
        List<RoutineGraph.Edge> edges = new ArrayList<>();
        for (RoutinesAccess.GraphEdge e : g.edges() == null ? List.<RoutinesAccess.GraphEdge>of() : g.edges()) {
            edges.add(new RoutineGraph.Edge(e.from(), e.port(), e.to(), e.toPort()));
        }
        return new RoutineGraph(g.id(), g.name(), g.description(), nodes, edges);
    }

    static RoutineGraph.Node fromFacade(RoutinesAccess.GraphNode n) {
        RoutineGraph.Type type = RoutineGraph.Type.named(n.type());
        if (type == null) {
            throw new IllegalArgumentException("\"" + n.type() + "\" is not a node type");
        }
        return new RoutineGraph.Node(n.id(), type, n.x(), n.y(), clean(n.params()), n.cond() == null ? null : fromFacade(n.cond()));
    }

    private static Cond fromFacade(RoutinesAccess.CondTree c) {
        List<Cond> kids = new ArrayList<>();
        if (c.children() != null) {
            c.children().forEach(k -> kids.add(fromFacade(k)));
        }
        return new Cond(Cond.Op.valueOf(c.op().toUpperCase(Locale.ROOT)), c.kind(), clean(c.args()), kids);
    }

    // a box nobody typed in may hand over a null, and the model's maps don't take those
    private static Map<String, String> clean(Map<String, String> in) {
        Map<String, String> out = new HashMap<>();
        if (in != null) {
            in.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(k, v);
                }
            });
        }
        return out;
    }

    static List<RoutinesAccess.NodeSpec> nodeSpecs() {
        List<RoutinesAccess.NodeSpec> out = new ArrayList<>();
        for (GraphSpecs.NodeSpec s : GraphSpecs.NODES) {
            out.add(new RoutinesAccess.NodeSpec(s.type().wire(), s.group(), s.label(), fields(s.fields()), s.inputs(), s.outputs()));
        }
        return out;
    }

    static List<RoutinesAccess.CondSpec> condSpecs() {
        List<RoutinesAccess.CondSpec> out = new ArrayList<>();
        for (GraphSpecs.CondSpec s : GraphSpecs.CONDS) {
            out.add(new RoutinesAccess.CondSpec(s.kind(), s.label(), fields(s.fields())));
        }
        return out;
    }

    private static List<RoutinesAccess.FieldSpec> fields(List<GraphSpecs.Field> in) {
        List<RoutinesAccess.FieldSpec> out = new ArrayList<>();
        for (GraphSpecs.Field f : in) {
            out.add(new RoutinesAccess.FieldSpec(f.key(), f.label(), f.kind().name(), f.required(), f.min(), f.max(), f.choices(), f.whenMode()));
        }
        return out;
    }
}
