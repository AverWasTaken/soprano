package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StationHook.Kind;
import net.minecraft.core.BlockPos;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// places where two station readers answer what looks like ONE question differently. every test here is KNOWN WRONG, pinned as it
// behaves today, waiting on the behaviour round that picks an answer (StationVerdict keeps both answers under their own names and
// does not unify them). when one of these fails because someone fixed the disagreement, change the assert and say so in the commit
public class StationContradictionsTest {
    private static final long NOW = 6000;
    private static final String DIM = Workbenches.OVERWORLD;
    // we stand at the middle of 0 64 0, so a block d away along x is exactly d from us
    private static final double PX = 0.5;
    private static final double PY = 64.5;
    private static final double PZ = 0.5;

    @Rule
    public Timeout timeout = Timeout.seconds(60);

    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    // the game, as a table: every block stands, every walk back is usable, the bag says what the test says
    private static final class Bag implements StationVerdict.World {
        boolean furnaceInBag;
        boolean canMake = true;

        @Override
        public boolean stands(Kind kind, RunState.Pos pos) {
            return true;
        }

        @Override
        public boolean inBag(Kind kind) {
            return kind == Kind.FURNACE && furnaceInBag;
        }

        @Override
        public boolean canMake(Kind kind) {
            return canMake;
        }

        @Override
        public boolean walkUsable(Kind kind, Bench b) {
            return true;
        }
    }

    private static Bench place(RunState state, Kind kind, RunState.Pos at, String dimension) {
        PrintStream real = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            Workbenches.record(state, kind, at, dimension, 100);
        } finally {
            System.setOut(real);
        }
        return Workbenches.find(state, kind, at);
    }

    private static StationVerdict look(RunState state, Bag bag, StationLatches latches) {
        return StationVerdict.of(state, DIM, PX, PY, PZ, NOW, bag, latches);
    }

    // (A) a table 49 blocks away that the bag could make again. the planner's walk back stops at WALK_BACK (48) and says "not held", so
    // the kit asks for the planks; StationChoice keeps walking to the one it was heading for out to WALK_BACK + HOLD (50) and never
    // makes another. the planner budgets planks the task then never spends
    @Test
    public void aTablePastWalkBackIsNotHeldButTheTaskKeepsWalkingToIt() {
        for (int d : new int[]{49, 50}) {
            RunState state = new RunState();
            place(state, Kind.TABLE, pos(d, 64, 0), DIM);
            StationVerdict v = look(state, new Bag(), new StationLatches());
            assertFalse("planner at " + d, v.table().heldForPlanner());
            assertFalse(v.table().walksBack());
            // the task's side: the registry's candidate, the one it headed for last tick, the bag can make a table
            List<StationChoice.Candidate<String>> seen = List.of(new StationChoice.Candidate<>("t", d, StationChoice.Role.OURS));
            StationChoice.Pick<String> kept = StationChoice.decide(seen, "t", false, true, true, 21.0, StationChoice.walkBackReach(Kind.TABLE));
            assertEquals("task at " + d, StationChoice.Use.OURS, kept.use());
            // with no previous target the task agrees with the planner
            assertEquals(StationChoice.Use.MAKE, StationChoice.decide(seen, null, false, true, true, 21.0, StationChoice.walkBackReach(Kind.TABLE)).use());
        }
        // and at 51 they agree again
        RunState state = new RunState();
        place(state, Kind.TABLE, pos(51, 64, 0), DIM);
        assertFalse(look(state, new Bag(), new StationLatches()).table().heldForPlanner());
        assertEquals(StationChoice.Use.MAKE, StationChoice.decide(List.of(new StationChoice.Candidate<>("t", 51.0, StationChoice.Role.OURS)),
                "t", false, true, true, 21.0, StationChoice.walkBackReach(Kind.TABLE)).use());
    }

    // (B) a smoker 21 blocks away with a furnace in the bag. the planner's band is NEAR (21) while it was held or never seen, NEAR - 1
    // (20) once it was out, so the same smoker at the same spot is held or not by which side we came from. WorkbenchRules says a smoker
    // at 20 blocks is a smoker whichever way we got there; the container task only ever looks at the plain line and finds it both times
    @Test
    public void aSmokerOnTheLineIsHeldOrNotByWhichSideWeCameFrom() {
        RunState state = new RunState();
        place(state, Kind.SMOKER, pos(21, 64, 0), DIM);
        Bag bag = new Bag();
        bag.furnaceInBag = true;
        StationLatches cameFromInside = new StationLatches();
        cameFromInside.smokerHeld = Boolean.TRUE;
        StationLatches cameFromOutside = new StationLatches();
        cameFromOutside.smokerHeld = Boolean.FALSE;
        StationVerdict in = look(state, bag, cameFromInside);
        StationVerdict out = look(state, bag, cameFromOutside);
        assertTrue(in.smoker().heldForPlanner());
        assertFalse(out.smoker().heldForPlanner());
        // the task's side does not care
        assertTrue(in.smoker().standingNear());
        assertTrue(out.smoker().standingNear());
    }

    // (C) a bench on its way back to the bag. the planner counts it as held (the kit must not ask for table planks the moment it leaves
    // the bag), standing() does not, and neither do the container tasks (StationHook.standingWithin): they would place a second one,
    // which is what the pickup veto is for
    @Test
    public void aBenchComingDownIsHeldForThePlannerAndNotStanding() {
        RunState state = new RunState();
        Bench b = place(state, Kind.TABLE, pos(5, 64, 0), DIM);
        b.state = Bench.State.PICKING_UP;
        StationVerdict v = look(state, new Bag(), new StationLatches());
        assertTrue(v.table().heldForPlanner());
        assertFalse(Workbenches.standing(b, Kind.TABLE, DIM));
        assertFalse(v.table().standingNear());
        // (the veto itself only holds while somebody drives the pickup, nobody does in this room)
        assertFalse(v.table().pickingUp());
        assertNull(Workbenches.nearestStanding(state, Kind.TABLE, DIM, PX, PY, PZ, WorkbenchRules.NEAR));
    }

    // (D) a furnace with a job in it. the planner counts it as held, so it budgets no furnace; DoStuffInContainerTask's `free` test
    // (a split load, skipsBusy) refuses a busy one, finds nothing near and places a second
    @Test
    public void aBusyFurnaceIsHeldForThePlannerAndSkippedByTheSplitLoad() {
        RunState state = new RunState();
        Bench b = place(state, Kind.FURNACE, pos(3, 64, 0), DIM);
        FurnaceJobs.record(state.furnaceJobs, new RunState.FurnaceJob(b.pos, DIM, "furnace", "raw_iron", 4, "iron_ingot", NOW - 200, NOW + 400));
        StationVerdict v = look(state, new Bag(), new StationLatches());
        assertTrue(v.furnace().heldForPlanner());
        FakeFacts facts = new FakeFacts();
        StationHook.Source source = Workbenches.source(state, facts);
        // `busy` in the task is the container tracker or a queued job, a recorded job stands in for it here
        BlockPos any = source.standingWithin(Kind.FURNACE, PX, PY, PZ, WorkbenchRules.NEAR);
        assertTrue(any != null);
        BlockPos free = source.standingWithin(Kind.FURNACE, PX, PY, PZ, WorkbenchRules.NEAR,
                p -> !FurnaceJobs.isBusy(state, pos(p.getX(), p.getY(), p.getZ())));
        assertNull(free);
    }

    // (E) WorkbenchRules.decide forgets a station by distance even when something is using it. 130 blocks away a standing furnace with
    // its screen open (idle = false) is dropped, and a table 50 away with the wood to recraft is dropped once it has been outside for 10 s (the rule wants 5).
    // only a station holding our items is spared; "in use" alone is not a reason
    @Test
    public void decideForgetsAStationByDistanceEvenWhileItIsInUse() {
        Bench far = new Bench(Kind.FURNACE, pos(130, 64, 0), DIM, 100);
        WorkbenchRules.Look inUse = new WorkbenchRules.Look(NOW, 130, true, false, false, false, false, true, true, 600);
        assertEquals(WorkbenchRules.Call.FORGET_TOO_FAR, WorkbenchRules.decide(far, inUse));

        Bench table = new Bench(Kind.TABLE, pos(50, 64, 0), DIM, 100);
        table.outsideSince = NOW - 200;
        WorkbenchRules.Look busyTable = new WorkbenchRules.Look(NOW, 50, true, false, false, false, false, true, true, 600, true);
        assertEquals(WorkbenchRules.Call.FORGET_FAR_TABLE, WorkbenchRules.decide(table, busyTable));
    }

    // (F) FurnaceWatch asks two dimension blind questions where Workbenches asks them with the dimension: a job (or a placed furnace) at
    // the same x y z in the nether is "busy" and "ours" in the overworld for the visit that decides whether to take a station down
    @Test
    public void theVisitsBusyAndOursIgnoreTheDimension() {
        RunState state = new RunState();
        RunState.Pos at = pos(7, 64, 7);
        place(state, Kind.FURNACE, at, "NETHER");
        FurnaceJobs.record(state.furnaceJobs, new RunState.FurnaceJob(at, "NETHER", "furnace", "raw_iron", 4, "iron_ingot", NOW - 200, NOW + 400));
        // the visit's two questions, 2 arg and blind
        assertTrue(FurnaceJobs.isBusy(state, at));
        assertTrue(FurnaceWatch.ours(state, "furnace", at));
        // the registry's, with the dimension we stand in (the overworld)
        assertFalse(FurnaceJobs.isBusy(state, at, DIM));
        FakeFacts facts = new FakeFacts();
        assertFalse(Workbenches.source(state, facts).ours(new BlockPos(7, 64, 7)));
    }
}
