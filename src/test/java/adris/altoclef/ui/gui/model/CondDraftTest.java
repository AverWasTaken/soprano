package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.RoutinesAccess.CondPreset;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondTree;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CondDraftTest {

    private static CondTree leaf(String kind) {
        return new CondTree("leaf", kind, Map.of("value", "1"), List.of());
    }

    private static CondTree not(CondTree t) {
        return new CondTree("not", null, Map.of(), List.of(t));
    }

    private static CondTree group(String op, CondTree... kids) {
        return new CondTree(op, null, Map.of(), List.of(kids));
    }

    @Test
    public void nothingIsNoRows() {
        CondDraft d = CondDraft.of(null);
        assertTrue(d.fits);
        assertTrue(d.rows.isEmpty());
        assertNull(d.toTree());
    }

    @Test
    public void aLeafRoundTrips() {
        CondDraft d = CondDraft.of(leaf("health"));
        assertEquals(1, d.rows.size());
        assertEquals(leaf("health").kind(), d.toTree().kind());
        assertEquals("leaf", d.toTree().op());
    }

    @Test
    public void aFlatGroupKeepsItsJoinAndItsNots() {
        CondDraft d = CondDraft.of(group("any", leaf("health"), not(leaf("hunger"))));
        assertTrue(d.fits);
        assertEquals(CondDraft.ANY, d.join);
        assertFalse(d.rows.get(0).negate);
        assertTrue(d.rows.get(1).negate);
        CondTree back = d.toTree();
        assertEquals("any", back.op());
        assertEquals("not", back.children().get(1).op());
        assertEquals("hunger", back.children().get(1).children().get(0).kind());
    }

    @Test
    public void nestingDoesntFitAndNothingGetsDropped() {
        assertFalse(CondDraft.of(group("all", leaf("health"), group("any", leaf("hunger"), leaf("time")))).fits);
        assertFalse(CondDraft.of(not(group("all", leaf("health")))).fits);
        assertFalse(CondDraft.of(not(not(leaf("health")))).fits);
    }

    @Test
    public void aSingleChildGroupStillFits() {
        CondDraft d = CondDraft.of(group("all", leaf("health")));
        assertTrue(d.fits);
        assertEquals(1, d.rows.size());
    }

    @Test
    public void aPresetIsFoundByWhatItsMadeOf() {
        List<CondPreset> presets = List.of(new CondPreset("Inventory full", "free_slots", Map.of("op", "=", "value", "0")));
        CondDraft.Row r = new CondDraft.Row("free_slots");
        r.args.put("op", "=");
        r.args.put("value", "0");
        assertEquals(0, CondDraft.presetOf(r, presets));
        r.args.put("value", "1");
        assertEquals(-1, CondDraft.presetOf(r, presets));
    }
}
