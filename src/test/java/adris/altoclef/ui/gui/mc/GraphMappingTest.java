package adris.altoclef.ui.gui.mc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.commands.graph.GraphSpecs;
import adris.altoclef.commands.graph.RoutineGraph;
import adris.altoclef.ui.gui.facade.RoutinesAccess;
import java.util.List;
import java.util.Map;
import org.junit.Test;

// the editor builds itself from these, a node that doesn't make it through here doesn't exist as far as it knows
public class GraphMappingTest {

    private static RoutinesAccess.NodeSpec node(String type) {
        return GraphMapping.nodeSpecs().stream().filter(s -> s.type().equals(type)).findFirst().orElseThrow();
    }

    @Test
    public void everyNodeTypeReachesTheEditor() {
        assertEquals(RoutineGraph.Type.values().length, GraphMapping.nodeSpecs().size());
    }

    @Test
    public void theNewNodesKeepTheirFields() {
        assertEquals(List.of("block", "n"), node("mine").fields().stream().map(RoutinesAccess.FieldSpec::key).toList());
        assertEquals(List.of("item", "n"), node("craft").fields().stream().map(RoutinesAccess.FieldSpec::key).toList());
        assertEquals(List.of("player", "secs"), node("follow").fields().stream().map(RoutinesAccess.FieldSpec::key).toList());
        assertEquals("ROUTINE", node("sub").fields().get(0).kind());
        assertEquals("PLAYER", node("follow").fields().get(0).kind());
        assertEquals(false, node("eat").fields().get(0).required());
        assertEquals(20, node("eat").fields().get(0).max());
        assertEquals(List.of("ok", "failed"), node("sub").outputs());
    }

    @Test
    public void theNewConditionsAndThePresetReachTheEditor() {
        List<String> kinds = GraphMapping.condSpecs().stream().map(RoutinesAccess.CondSpec::kind).toList();
        assertTrue(kinds.toString(), kinds.containsAll(List.of("last_action_failed", "loop_counter")));
        RoutinesAccess.CondPreset full = GraphMapping.condPresets().get(0);
        assertEquals("Inventory full", full.label());
        assertEquals("free_slots", full.kind());
        assertEquals(Map.of("op", "=", "value", "0"), full.args());
    }

    @Test
    public void theNewNodesRoundTripThroughTheFacade() {
        RoutineGraph g = new RoutineGraph("x", "x", "", List.of(new RoutineGraph.Node("m", RoutineGraph.Type.MINE, 1, 2, Map.of("block", "dirt", "n", "3"), null)), List.of());
        RoutinesAccess.Graph out = GraphMapping.toFacade(g);
        assertEquals("mine", out.nodes().get(0).type());
        assertEquals(g.nodes(), GraphMapping.fromFacade(out).nodes());
    }

    @Test
    public void theRadiusLimitsReachTheEditor() {
        RoutinesAccess.CondSpec mob = GraphMapping.condSpecs().stream().filter(c -> c.kind().equals("near_mob")).findFirst().orElseThrow();
        RoutinesAccess.CondSpec block = GraphMapping.condSpecs().stream().filter(c -> c.kind().equals("near_block")).findFirst().orElseThrow();
        assertEquals(GraphSpecs.MAX_MOB_RADIUS, mob.fields().get(1).max());
        assertEquals(GraphSpecs.MAX_BLOCK_RADIUS, block.fields().get(1).max());
    }
}
