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

// places where two station readers answer what looks like ONE question differently, and what became of each. FIXED ones now pin the
// agreement (A, B, E, F), BY DESIGN ones pin the reasoning (C, D), and (G) is the one leftover we accepted. when one of these fails,
// someone changed the answer: say so in the commit
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
        boolean usable = true;

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
            return usable;
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

    // a look from `d` blocks along x from the middle of a block at 0 64 0 (a station placed there is d away)
    private static StationVerdict lookAt(RunState state, Bag bag, StationLatches latches, double d) {
        return StationVerdict.of(state, DIM, PX + d, PY, PZ, NOW, bag, latches);
    }

    // (A) FIXED. a table 49 blocks away that the bag could make again. the planner's walk back used to stop flat at WALK_BACK (48) and
    // say "not held", so the kit asked for planks while StationChoice kept walking to the one it was heading for out to WALK_BACK +
    // HOLD (50). now the planner gets the same slack once it has counted the table: acquired at 48, kept to 50, gone past it
    @Test
    public void aTablePastWalkBackStaysHeldOnceItWasHeldAndTheTaskKeepsWalkingToIt() {
        for (int d : new int[]{49, 50}) {
            RunState state = new RunState();
            place(state, Kind.TABLE, pos(0, 64, 0), DIM);
            // walked out from 47 (held by the walk back), now d away
            StationLatches latches = new StationLatches();
            assertTrue(lookAt(state, new Bag(), latches, 47.0).table().heldForPlanner());
            assertTrue("planner at " + d, lookAt(state, new Bag(), latches, d).table().heldForPlanner());
            // standing still does not flip it
            assertTrue(lookAt(state, new Bag(), latches, d).table().heldForPlanner());
            // the task's side: the registry's candidate, the one it headed for last tick, the bag can make a table
            List<StationChoice.Candidate<String>> seen = List.of(new StationChoice.Candidate<>("t", d, StationChoice.Role.OURS));
            StationChoice.Pick<String> kept = StationChoice.decide(seen, "t", false, true, true, 21.0, StationChoice.walkBackReach(Kind.TABLE));
            assertEquals("task at " + d, StationChoice.Use.OURS, kept.use());
            // acquired at the plain 48: the same spot reached from outside is not held, and the task with no previous target agrees
            assertFalse(lookAt(state, new Bag(), new StationLatches(), d).table().heldForPlanner());
            assertEquals(StationChoice.Use.MAKE, StationChoice.decide(seen, null, false, true, true, 21.0, StationChoice.walkBackReach(Kind.TABLE)).use());
        }
        // past 50 both let go, and it takes the plain 48 to get it back
        RunState state = new RunState();
        place(state, Kind.TABLE, pos(0, 64, 0), DIM);
        StationLatches latches = new StationLatches();
        assertTrue(lookAt(state, new Bag(), latches, 48.0).table().heldForPlanner());
        assertTrue(lookAt(state, new Bag(), latches, 50.0).table().heldForPlanner());
        assertFalse(lookAt(state, new Bag(), latches, 50.5).table().heldForPlanner());
        assertEquals(StationChoice.Use.MAKE, StationChoice.decide(List.of(new StationChoice.Candidate<>("t", 51.0, StationChoice.Role.OURS)),
                "t", false, true, true, 21.0, StationChoice.walkBackReach(Kind.TABLE)).use());
        assertFalse(lookAt(state, new Bag(), latches, 49.0).table().heldForPlanner());
        assertTrue(lookAt(state, new Bag(), latches, 48.0).table().heldForPlanner());
    }

    // (G) KNOWN LEFTOVER of (A), accepted. the planner holds a table in the 48 < d <= 50 ring once it has counted it from inside, but
    // it cannot know whether a craft is already walking to it. a craft that STARTS in the ring with no previous target (the bot
    // walked out past 48 for other work, then needs the table) finds none within 48, and with the bag able to make a table it makes
    // one from planks / a log already in the bag. exactly: table of ours at 48 < d <= 50, latched held, bag can make a table, new
    // task. the old table is forgotten after 5 s outside (FORGET_FAR_TABLE), so nothing loops; the cost is 4 planks or a log
    @Test
    public void aFreshCraftInTheRingMakesATableWhileTheLatchedPlannerStillHoldsOne() {
        RunState state = new RunState();
        place(state, Kind.TABLE, pos(0, 64, 0), DIM);
        StationLatches latches = new StationLatches();
        assertTrue(lookAt(state, new Bag(), latches, 47.0).table().heldForPlanner());
        assertTrue(lookAt(state, new Bag(), latches, 49.0).table().heldForPlanner());
        List<StationChoice.Candidate<String>> seen = List.of(new StationChoice.Candidate<>("t", 49.0, StationChoice.Role.OURS));
        assertEquals(StationChoice.Use.MAKE, StationChoice.decide(seen, null, false, true, true, 21.0, StationChoice.walkBackReach(Kind.TABLE)).use());
    }

    // (B) FIXED. a smoker 21 blocks away with a furnace in the bag. the planner's latch used to enter at 20 once the smoker was out and
    // at 21 while it was held or never seen, so the same smoker at the same spot was held or not by which side we came from, and the
    // container task (which only looks at the plain line, 21) used it both times. the furnace and smoker have no latch now, the
    // planner's line is the task's. it must not reach PAST the line either (a band that did was a bug once): the task puts the one from
    // the bag down, or skips a parked one and makes another, and the plan budgeted neither
    @Test
    public void aSmokerOnTheLineIsHeldWhicheverSideWeCameFrom() {
        RunState state = new RunState();
        place(state, Kind.SMOKER, pos(0, 64, 0), DIM);
        Bag bag = new Bag();
        bag.furnaceInBag = true;
        for (double d : new double[]{20.5, 21.0}) {
            StationLatches inside = new StationLatches();
            assertTrue(lookAt(state, bag, inside, 5.0).smoker().heldForPlanner());
            assertTrue("from inside at " + d, lookAt(state, bag, inside, d).smoker().heldForPlanner());
            StationLatches outside = new StationLatches();
            assertFalse(lookAt(state, bag, outside, 40.0).smoker().heldForPlanner());
            assertTrue("from outside at " + d, lookAt(state, bag, outside, d).smoker().heldForPlanner());
            // standing still does not flip it, and the task's side does not care which way either
            assertTrue(lookAt(state, bag, outside, d).smoker().heldForPlanner());
            assertTrue(lookAt(state, bag, outside, d).smoker().standingNear());
        }
        // and past the line it is gone from both sides, the furnace in the bag is a furnace at hand so no smoker walk back
        StationLatches latches = new StationLatches();
        lookAt(state, bag, latches, 5.0);
        assertFalse(lookAt(state, bag, latches, 21.5).smoker().heldForPlanner());
        assertFalse(lookAt(state, bag, latches, 21.5).smoker().heldForPlanner());
    }

    // the same line for a furnace, and what the old latch got wrong from the other side: past the line a furnace in the bag, or one
    // the task would not walk back to, is not held (the task puts the bag one down / skips the parked one and makes another)
    @Test
    public void aFurnacePastTheLineIsOnlyHeldWhereTheTaskWalksBackToIt() {
        RunState state = new RunState();
        place(state, Kind.FURNACE, pos(0, 64, 0), DIM);
        // a usable one the bag could replace is walked back to, so it is held all the way across the line
        Bag usable = new Bag();
        StationLatches latches = new StationLatches();
        lookAt(state, usable, latches, 5.0);
        for (double d : new double[]{20.5, 21.0, 21.5, 22.5, 21.5, 20.5}) {
            assertTrue("usable at " + d, lookAt(state, usable, latches, d).furnace().heldForPlanner());
        }
        // a furnace in the bag: the task places that one past the line
        Bag carrying = new Bag();
        carrying.furnaceInBag = true;
        StationLatches carryingLatches = new StationLatches();
        lookAt(state, carrying, carryingLatches, 5.0);
        assertTrue(lookAt(state, carrying, carryingLatches, 21.0).furnace().heldForPlanner());
        assertFalse(lookAt(state, carrying, carryingLatches, 21.5).furnace().heldForPlanner());
        // a parked or loaded one (walkBackUsable says no): the task skips it past the line
        Bag parked = new Bag();
        parked.usable = false;
        StationLatches parkedLatches = new StationLatches();
        lookAt(state, parked, parkedLatches, 5.0);
        assertTrue(lookAt(state, parked, parkedLatches, 21.0).furnace().heldForPlanner());
        assertFalse(lookAt(state, parked, parkedLatches, 21.5).furnace().heldForPlanner());
    }

    // (C) BY DESIGN. a bench on its way back to the bag. the planner counts it as held (the item is about to be in the bag, so the kit
    // must not ask for table planks the moment it leaves the ground), and standing() / the container tasks do not count it. what stops
    // the task from placing or crafting a second one is the pickup veto (DoStuffInContainerTask.onTick waits while
    // StationHook.pickingUp(kind)), which holds as long as somebody drives the pickup. it cannot hold the plan forever: a failed or
    // timed out try puts the bench back to STANDING (failedTry), a pickup nobody drives goes back too (dropStaleDrive), one that finds
    // our items in it goes to BUSY (abort), and the rest are forgotten (DROP_LOST, the far table). a pickup only starts when nothing
    // needs the station soon, so the planner holding it is the plan agreeing with that
    @Test
    public void aBenchComingDownIsHeldForThePlannerAndTheTaskWaitsForIt() {
        RunState state = new RunState();
        Bench b = place(state, Kind.TABLE, pos(5, 64, 0), DIM);
        WorkbenchRules.beginPickup(b, NOW, 0, "test");
        assertTrue(look(state, new Bag(), new StationLatches()).table().heldForPlanner());
        assertFalse(Workbenches.standing(b, Kind.TABLE, DIM));
        assertNull(Workbenches.nearestStanding(state, Kind.TABLE, DIM, PX, PY, PZ, WorkbenchRules.NEAR));
        // driven this tick: nothing may place or craft another, nor walk to the block coming down
        assertTrue(WorkbenchRules.placeVetoed(state.benches, Kind.TABLE, NOW));
        assertTrue(WorkbenchRules.targetVetoed(state.benches, b.pos, NOW));

        // the try fails: standing again, the veto is gone and the task walks to it like to any other
        WorkbenchRules.failedTry(b, NOW);
        assertEquals(Bench.State.STANDING, b.state);
        assertTrue(look(state, new Bag(), new StationLatches()).table().heldForPlanner());
        assertTrue(Workbenches.standing(b, Kind.TABLE, DIM));
        assertFalse(WorkbenchRules.placeVetoed(state.benches, Kind.TABLE, NOW));

        // nobody drives it any more: it stops vetoing and goes back to standing, so the plan is not left holding a ghost
        WorkbenchRules.beginPickup(b, NOW, 0, "test");
        long later = NOW + WorkbenchRules.DRIVE_GRACE_TICKS + 1;
        assertFalse(WorkbenchRules.placeVetoed(state.benches, Kind.TABLE, later));
        Workbenches.sync(state, later);
        assertEquals(Bench.State.STANDING, b.state);
    }

    // (D) BY DESIGN. a furnace with a job in it. the planner counts it as held, so it budgets no furnace, and DoStuffInContainerTask's
    // `free` test (a split load, skipsBusy) refuses a busy one, finds nothing near and may place a second: that is the split smelt
    // (SmeltSplit), a second furnace next to the one cooking on purpose. the planner asks for no cobble for it (KitPlanner.COBBLE_COST,
    // the mine brings back far more than the 16 spare), and the task cannot eat what the kit still owes: SmeltSplit.loads and
    // DoStuffInContainerTask.bagCanMake both count cobble minus StationHook.cobbleOwed (KitPlanner.toolCobble, the stone tools'
    // cobble, which is also the kit's stone floor while a furnace stands). short on spare cobble the split is fewer loads, never a
    // trip for stone
    @Test
    public void aBusyFurnaceIsHeldForThePlannerAndTheSplitLoadMakesAnotherOnlyFromSpareCobble() {
        RunState state = new RunState();
        Bench b = place(state, Kind.FURNACE, pos(3, 64, 0), DIM);
        FurnaceJobs.record(state.furnaceJobs, new RunState.FurnaceJob(b.pos, DIM, "furnace", "raw_iron", 4, "iron_ingot", NOW - 200, NOW + 400));
        assertTrue(look(state, new Bag(), new StationLatches()).furnace().heldForPlanner());
        FakeFacts facts = new FakeFacts();
        StationHook.Source source = Workbenches.source(state, facts);
        // `busy` in the task is the container tracker or a queued job, a recorded job stands in for it here
        BlockPos any = source.standingWithin(Kind.FURNACE, PX, PY, PZ, WorkbenchRules.NEAR);
        assertTrue(any != null);
        BlockPos free = source.standingWithin(Kind.FURNACE, PX, PY, PZ, WorkbenchRules.NEAR,
                p -> !FurnaceJobs.isBusy(state, pos(p.getX(), p.getY(), p.getZ()), DIM));
        assertNull(free);
        // 37 iron wants 3 furnaces and none is idle (every load but a free one is 8 cobble). 14 cobble go to the tools: 21 in the bag
        // is 7 spare, nothing to make a furnace from, one load
        int owed = 14;
        assertEquals(1, adris.altoclef.tasks.container.SmeltSplit.loads(37, 0, 0, 21 - owed));
        assertFalse(StationChoice.canMakeFrom(Kind.FURNACE, 21 - owed, 0, 0, 0));
        // 8 spare makes a furnace, and it takes 16 for two more
        assertTrue(StationChoice.canMakeFrom(Kind.FURNACE, 22 - owed, 0, 0, 0));
        assertEquals(1, adris.altoclef.tasks.container.SmeltSplit.loads(37, 0, 0, 29 - owed));
        assertEquals(2, adris.altoclef.tasks.container.SmeltSplit.loads(37, 0, 0, 30 - owed));
        assertEquals(3, adris.altoclef.tasks.container.SmeltSplit.loads(37, 0, 0, 38 - owed));
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
