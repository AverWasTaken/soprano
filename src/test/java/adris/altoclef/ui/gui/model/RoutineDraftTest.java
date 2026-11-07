package adris.altoclef.ui.gui.model;

import adris.altoclef.commands.RoutineLines;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.facade.RoutinesAccess;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Routine;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Step;
import adris.altoclef.ui.gui.facade.RoutinesAccess.StepType;
import adris.altoclef.ui.gui.model.RoutineDraft.Kind;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RoutineDraftTest {

    private final RoutinesAccess a = new FakeFacade().routines;

    @Test
    public void idRuleMatchesTheStore() {
        for (String name : new String[]{"Diamond gear", "  wood   run ", "a/b:c", "ünïcode only", "", "x".repeat(60), "Mine-run_2"}) {
            assertEquals(name, RoutineLines.idFor(name), RoutineDraft.idFor(name));
        }
        assertNull(RoutineDraft.idFor(null));
    }

    @Test
    public void oneOfAThingIsJustTheThing() {
        RoutineDraft.Row r = RoutineDraft.blankRow(Kind.GET);
        RoutineDraft.addItem(r, "diamond_sword");
        RoutineDraft.addItem(r, "Bread");
        RoutineDraft.addItem(r, "bread");
        assertEquals("diamond_sword, bread 2", RoutineDraft.fieldsFor(r).get("items"));
        assertEquals("get diamond_sword, bread 2", a.lineFor(StepType.GET, RoutineDraft.fieldsFor(r)));
        // an emptied count box still means one, not "diamond_sword " with a hole in it
        r.items.get(0).count = "";
        assertEquals("diamond_sword, bread 2", RoutineDraft.fieldsFor(r).get("items"));
    }

    @Test
    public void savedLinesOpenInTheRightBoxes() {
        Routine r = new Routine("T", "T", "", List.of(
                new Step(StepType.GET, "get diamond_sword 1, bread 16"),
                new Step(StepType.GOTO, "wp goto home"),
                new Step(StepType.GOTO, "goto crafting_table"),
                new Step(StepType.GOTO, "goto 1 64 -3"),
                new Step(StepType.WAIT, "wait 5"),
                new Step(StepType.RAW, "farm 30")));
        RoutineDraft d = RoutineDraft.open(r, a);
        assertEquals(List.of(Kind.GET, Kind.GOTO_WAYPOINT, Kind.GOTO_BLOCK, Kind.GOTO_COORDS, Kind.WAIT, Kind.RAW),
                d.rows.stream().map(x -> x.kind).toList());
        assertEquals(2, d.rows.get(0).items.size());
        assertEquals("16", d.rows.get(0).items.get(1).count);
        // just opening it isn't an edit, even though the "1" fell off the line the boxes make
        assertFalse(d.dirty(a));
        assertEquals("get diamond_sword, bread 16", d.steps(a).get(0).line());
        assertEquals("wp goto home", d.steps(a).get(1).line());
        assertEquals("goto 1 64 -3", d.steps(a).get(3).line());
    }

    @Test
    public void editsAreDirtyAndNewIsAlwaysDirty() {
        assertTrue(RoutineDraft.blank().dirty(a));
        RoutineDraft d = RoutineDraft.open(new Routine("T", "T", "", List.of(new Step(StepType.WAIT, "wait 5"))), a);
        assertFalse(d.dirty(a));
        d.rows.get(0).f.put("seconds", "6");
        assertTrue(d.dirty(a));
        d.rows.get(0).f.put("seconds", "5");
        assertFalse(d.dirty(a));
        d.move(0, 1);
        assertFalse(d.dirty(a));
        d.add(Kind.RAW);
        d.move(1, -1);
        assertEquals(Kind.RAW, d.rows.get(0).kind);
        assertTrue(d.dirty(a));
    }

    @Test
    public void theSavedIdSticksUntilTheNameChanges() {
        RoutineDraft d = RoutineDraft.open(new Routine("mine_run", "Mine run", "", List.of()), a);
        assertEquals("mine_run", d.shownId());
        d.name = "Mine run 2";
        assertEquals("Mine_run_2", d.shownId());
    }
}
