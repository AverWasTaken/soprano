package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StationHook.Kind;
import baritone.api.utils.Dimension;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.Test;

// the static half of Workbenches: RunState in, RunState out, no game. the pickup driving is the world half and only compile checked
public class WorkbenchesRegistryTest {
    private static final String OVERWORLD = Workbenches.OVERWORLD;
    private static final String NETHER = "NETHER";

    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    // the log is how the DUPLICATE case is seen, so a test that wants it reads what went to the console
    private static String logOf(Runnable action) {
        PrintStream real = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(real);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    // a record that did not cry duplicate. the placement line has to be there too, or a capture that caught nothing would pass
    private static void assertNoDuplicate(String log) {
        assertTrue(log, log.contains("placed"));
        assertFalse(log, log.contains("DUPLICATE"));
    }

    // ---- sync

    @Test
    public void syncMakesABenchForEveryListedPosition() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        state.placedFurnaces.add(pos(5, 64, 5));
        state.placedSmokers.add(pos(9, 64, 9));
        Workbenches.sync(state, 500);
        assertEquals(3, state.benches.size());
        for (Kind kind : Kind.values()) {
            Bench b = state.benches.stream().filter(x -> x.kind == kind).findFirst().orElseThrow();
            assertEquals(Bench.State.STANDING, b.state);
            assertEquals(OVERWORLD, b.dimension);
            assertEquals(500, b.placedTick);
        }
        assertNotNull(Workbenches.find(state, Kind.TABLE, pos(1, 64, 1)));
        assertNotNull(Workbenches.find(state, Kind.FURNACE, pos(5, 64, 5)));
        assertNotNull(Workbenches.find(state, Kind.SMOKER, pos(9, 64, 9)));
        // the kind matters: a furnace is not at the table's spot
        assertNull(Workbenches.find(state, Kind.FURNACE, pos(1, 64, 1)));
    }

    @Test
    public void anOldSaveWithOnlyTheListsLoadsAsOverworld() {
        // no placedDimension anywhere in it, the way saves were written before the registry
        RunState old = RunStateStore.parse("{\"phase\":\"IRON\",\"placedTables\":[{\"x\":1,\"y\":64,\"z\":2}],"
                + "\"placedFurnaces\":[{\"x\":7,\"y\":30,\"z\":-4}]}");
        assertNotNull(old);
        assertNotNull(old.benches);
        assertTrue(old.benches.isEmpty());
        Workbenches.sync(old, 10);
        assertEquals(2, old.benches.size());
        for (Bench b : old.benches) {
            assertEquals(OVERWORLD, b.dimension);
        }
    }

    @Test
    public void aSavedDimensionComesBackAndTheBenchesThemselvesAreNotSaved() {
        RunState state = new RunState();
        assertTrue(Workbenches.record(state, Kind.FURNACE, pos(3, 40, 3), NETHER, 100));
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 100));
        String json = RunStateStore.toJson(state);
        assertTrue(json, json.contains("placedDimension"));
        // the registry rebuilds from the lists after a relog, what the bot was doing with each one is not worth a save
        assertFalse(json, json.contains("benches"));
        RunState back = RunStateStore.parse(json);
        assertNotNull(back);
        assertTrue(back.benches.isEmpty());
        Workbenches.sync(back, 5000);
        assertEquals(NETHER, Workbenches.find(back, Kind.FURNACE, pos(3, 40, 3)).dimension);
        assertEquals(OVERWORLD, Workbenches.find(back, Kind.TABLE, pos(1, 64, 1)).dimension);
    }

    @Test
    public void syncKeepsTheEntriesItAlreadyHas() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        Workbenches.sync(state, 100);
        Bench first = state.benches.get(0);
        first.outsideSince = 777;
        first.tries = 2;
        Workbenches.sync(state, 200);
        Workbenches.sync(state, 300);
        assertEquals(1, state.benches.size());
        assertSame(first, state.benches.get(0));
        // the clocks and the tries are the point of keeping it
        assertEquals(777, first.outsideSince);
        assertEquals(2, first.tries);
        assertEquals(100, first.placedTick);
    }

    @Test
    public void syncPicksUpAPositionThatAppearedInTheListLater() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        Workbenches.sync(state, 100);
        state.placedTables.add(pos(40, 64, 1));
        Workbenches.sync(state, 250);
        assertEquals(2, state.benches.size());
        assertEquals(250, Workbenches.find(state, Kind.TABLE, pos(40, 64, 1)).placedTick);
    }

    // the lists are the truth, they are what is saved
    @Test
    public void syncDropsAnEntryWhosePositionLeftTheList() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        state.placedFurnaces.add(pos(5, 64, 5));
        Workbenches.sync(state, 100);
        state.placedTables.remove(pos(1, 64, 1));
        Workbenches.sync(state, 120);
        assertEquals(1, state.benches.size());
        assertNull(Workbenches.find(state, Kind.TABLE, pos(1, 64, 1)));
        assertNotNull(Workbenches.find(state, Kind.FURNACE, pos(5, 64, 5)));
        // busy or not, the list decides
        Workbenches.find(state, Kind.FURNACE, pos(5, 64, 5)).state = Bench.State.BUSY;
        state.placedFurnaces.clear();
        Workbenches.sync(state, 140);
        assertTrue(state.benches.isEmpty());
    }

    // its block is already down, the entry has to live until the drop is in the bag
    @Test
    public void syncKeepsAPickupInFlightWhateverTheListSays() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        Workbenches.sync(state, 100);
        Bench b = Workbenches.find(state, Kind.TABLE, pos(1, 64, 1));
        WorkbenchRules.beginPickup(b, 100, 0, "test");
        state.placedTables.clear();
        Workbenches.sync(state, 120);
        assertEquals(1, state.benches.size());
        assertSame(b, state.benches.get(0));
        assertEquals(Bench.State.PICKING_UP, b.state);
    }

    // a pickup no phase has run for a while must not veto placing a new table for ever
    @Test
    public void syncLetsAPickupNobodyDrivesGoBackToStanding() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        Workbenches.sync(state, 100);
        Bench b = Workbenches.find(state, Kind.TABLE, pos(1, 64, 1));
        WorkbenchRules.beginPickup(b, 100, 0, "test");
        Workbenches.sync(state, 100 + WorkbenchRules.DRIVE_GRACE_TICKS);
        assertEquals(Bench.State.PICKING_UP, b.state);
        Workbenches.sync(state, 100 + WorkbenchRules.DRIVE_GRACE_TICKS + 1);
        assertEquals(Bench.State.STANDING, b.state);
    }

    // ---- record

    @Test
    public void recordIsTrueTheFirstTimeAndFalseOnADuplicate() {
        RunState state = new RunState();
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 300));
        assertFalse(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 310));
        assertEquals(List.of(pos(1, 64, 1)), state.placedTables);
        assertEquals(1, state.benches.size());
        Bench b = state.benches.get(0);
        assertEquals(Kind.TABLE, b.kind);
        assertEquals(pos(1, 64, 1), b.pos);
        assertEquals(OVERWORLD, b.dimension);
        assertEquals(Bench.State.STANDING, b.state);
        // the first sighting's tick, not the second's
        assertEquals(300, b.placedTick);
    }

    // the same x y z in two worlds is two blocks: the old one is behind us, the new one must not be taken for it
    @Test
    public void aTableOnTheSameCoordinatesInAnotherDimensionReplacesTheOldEntry() {
        RunState state = new RunState();
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(10, 70, 10), OVERWORLD, 100));
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(10, 70, 10), NETHER, 900));
        assertEquals(1, state.benches.size());
        assertEquals(NETHER, state.benches.get(0).dimension);
        assertEquals(900, state.benches.get(0).placedTick);
        assertEquals(List.of(pos(10, 70, 10)), state.placedTables);
        assertEquals(Map.of("10,70,10", NETHER), state.placedDimension);
        // and the other way round, back home: the nether entry goes and the map has nothing left to say about the spot
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(10, 70, 10), OVERWORLD, 2000));
        assertEquals(OVERWORLD, state.benches.get(0).dimension);
        assertTrue(state.placedDimension.isEmpty());
    }

    @Test
    public void recordFillsTheListOfItsKind() {
        RunState state = new RunState();
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 1));
        assertTrue(Workbenches.record(state, Kind.FURNACE, pos(50, 64, 1), OVERWORLD, 2));
        assertTrue(Workbenches.record(state, Kind.SMOKER, pos(90, 64, 1), OVERWORLD, 3));
        assertEquals(List.of(pos(1, 64, 1)), state.placedTables);
        assertEquals(List.of(pos(50, 64, 1)), state.placedFurnaces);
        assertEquals(List.of(pos(90, 64, 1)), state.placedSmokers);
        assertEquals(3, state.benches.size());
    }

    @Test
    public void onlyAnOtherDimensionIsWrittenToThePlacedDimensionMap() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 1);
        assertTrue(state.placedDimension.isEmpty());
        Workbenches.record(state, Kind.FURNACE, pos(-5, 40, 7), NETHER, 2);
        // x,y,z is the key the saves use, so it does not change
        assertEquals(Map.of("-5,40,7", NETHER), state.placedDimension);
        assertEquals(NETHER, Workbenches.find(state, Kind.FURNACE, pos(-5, 40, 7)).dimension);
    }

    // a table placed, picked up and placed on the same spot is one entry at a time
    @Test
    public void aSpotThatWasForgottenCanBeRecordedAgain() {
        RunState state = new RunState();
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 1));
        Workbenches.forget(state, Workbenches.find(state, Kind.TABLE, pos(1, 64, 1)), "test");
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 50));
        assertEquals(1, state.benches.size());
        assertEquals(50, state.benches.get(0).placedTick);
    }

    // we only log DUPLICATE, the second one is a station like any other (the log line is what the bug hunt greps for)
    @Test
    public void aSecondTableWithinNearIsStillRecorded() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(0, 64, 0), OVERWORLD, 1);
        String log = logOf(() -> assertTrue(Workbenches.record(state, Kind.TABLE, pos(5, 64, 0), OVERWORLD, 2)));
        assertTrue(log, log.contains("DUPLICATE"));
        assertEquals(2, state.placedTables.size());
        assertEquals(2, state.benches.size());
        assertNotNull(Workbenches.find(state, Kind.TABLE, pos(0, 64, 0)));
        assertNotNull(Workbenches.find(state, Kind.TABLE, pos(5, 64, 0)));
    }

    @Test
    public void duplicateIsMeasuredWithTheSameNearAsEverythingElse() {
        // 15 across and 15 up is 21.2, not a duplicate; 10 / 10 / 10 is 17.3, one
        RunState far = new RunState();
        Workbenches.record(far, Kind.TABLE, pos(0, 64, 0), OVERWORLD, 1);
        String quiet = logOf(() -> Workbenches.record(far, Kind.TABLE, pos(15, 79, 0), OVERWORLD, 2));
        assertNoDuplicate(quiet);
        assertEquals(2, far.benches.size());

        RunState close = new RunState();
        Workbenches.record(close, Kind.TABLE, pos(0, 64, 0), OVERWORLD, 1);
        String loud = logOf(() -> Workbenches.record(close, Kind.TABLE, pos(10, 74, 10), OVERWORLD, 2));
        assertTrue(loud, loud.contains("DUPLICATE"));
    }

    @Test
    public void onlyTheSameKindInTheSameDimensionStandingIsADuplicate() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(0, 64, 0), OVERWORLD, 1);
        // a furnace next to a table is how a base looks
        String otherKind = logOf(() -> Workbenches.record(state, Kind.FURNACE, pos(1, 64, 0), OVERWORLD, 2));
        assertNoDuplicate(otherKind);
        // the nether is another world
        String otherWorld = logOf(() -> Workbenches.record(state, Kind.TABLE, pos(2, 64, 0), NETHER, 3));
        assertNoDuplicate(otherWorld);
        // and one already on its way back into the bag is not standing there
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.TABLE, pos(0, 64, 0)), 4, 0, "test");
        String coming = logOf(() -> Workbenches.record(state, Kind.TABLE, pos(3, 64, 0), OVERWORLD, 5));
        assertNoDuplicate(coming);
        assertEquals(4, state.benches.size());
    }

    // ---- forget

    @Test
    public void forgetRemovesTheListEntryTheMapEntryAndTheBench() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(-5, 40, 7), NETHER, 1);
        Workbenches.record(state, Kind.FURNACE, pos(8, 64, 8), OVERWORLD, 2);
        Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 3);
        Workbenches.forget(state, Workbenches.find(state, Kind.FURNACE, pos(-5, 40, 7)), "test");
        assertEquals(List.of(pos(8, 64, 8)), state.placedFurnaces);
        assertTrue(state.placedDimension.isEmpty());
        assertNull(Workbenches.find(state, Kind.FURNACE, pos(-5, 40, 7)));
        // the others are untouched
        assertEquals(2, state.benches.size());
        assertEquals(List.of(pos(1, 64, 1)), state.placedTables);
        // and a sync does not bring it back
        Workbenches.sync(state, 100);
        assertEquals(2, state.benches.size());
    }

    // ---- lookups

    @Test
    public void findWantsTheKindAndFindAtOnlyThePlace() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.SMOKER, pos(4, 64, 4), OVERWORLD, 1);
        assertNotNull(Workbenches.find(state, Kind.SMOKER, pos(4, 64, 4)));
        assertNull(Workbenches.find(state, Kind.FURNACE, pos(4, 64, 4)));
        assertEquals(Kind.SMOKER, Workbenches.findAt(state, pos(4, 64, 4)).kind);
        assertNull(Workbenches.findAt(state, pos(4, 65, 4)));
    }

    @Test
    public void addOnceNeverDoublesAPosition() {
        List<RunState.Pos> list = new java.util.ArrayList<>();
        Workbenches.addOnce(list, pos(1, 2, 3));
        Workbenches.addOnce(list, pos(1, 2, 3));
        Workbenches.addOnce(list, pos(1, 2, 4));
        assertEquals(List.of(pos(1, 2, 3), pos(1, 2, 4)), list);
    }

    // ---- heldNear

    private static boolean held(RunState state, Kind kind, String dimension, double radius, boolean stands) {
        // the player's feet in the middle of block (0, 64, 0), so a block at x = 10 is 10 away
        return Workbenches.heldNear(state, kind, dimension, 0.5, 64.5, 0.5, radius, p -> stands);
    }

    @Test
    public void aStandingStationWithinRadiusIsHeld() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(10, 64, 0), OVERWORLD, 1);
        assertTrue(held(state, Kind.TABLE, OVERWORLD, 21, true));
        assertTrue(held(state, Kind.TABLE, OVERWORLD, 10, true));
        assertFalse(held(state, Kind.TABLE, OVERWORLD, 9.99, true));
    }

    @Test
    public void heldNearAsksThePredicateWhetherTheBlockStillStands() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(10, 64, 0), OVERWORLD, 1);
        assertFalse(held(state, Kind.TABLE, OVERWORLD, 21, false));
        // and is given the bench's own position
        assertTrue(Workbenches.heldNear(state, Kind.TABLE, OVERWORLD, 0.5, 64.5, 0.5, 21, p -> p.equals(pos(10, 64, 0))));
        assertFalse(Workbenches.heldNear(state, Kind.TABLE, OVERWORLD, 0.5, 64.5, 0.5, 21, p -> p.equals(pos(11, 64, 0))));
    }

    // it is on its way back to the bag, and the plan flipping to "make a table" mid pickup is the old bug
    @Test
    public void aPickupInFlightStillCountsAsHeldWhateverTheBlockSays() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(10, 64, 0), OVERWORLD, 1);
        WorkbenchRules.beginPickup(state.benches.get(0), 5, 0, "test");
        assertTrue(held(state, Kind.TABLE, OVERWORLD, 21, false));
        assertFalse(held(state, Kind.TABLE, OVERWORLD, 5, false));
    }

    @Test
    public void heldNearRespectsKindAndDimension() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(10, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.FURNACE, pos(-20, 64, 0), NETHER, 1);
        assertFalse(held(state, Kind.FURNACE, OVERWORLD, 100, true));
        assertFalse(held(state, Kind.SMOKER, OVERWORLD, 100, true));
        assertFalse(held(state, Kind.TABLE, NETHER, 100, true));
        assertTrue(held(state, Kind.FURNACE, NETHER, 100, true));
        // even a pickup in flight in the other dimension is not held here
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.FURNACE, pos(-20, 64, 0)), 5, 0, "test");
        assertFalse(held(state, Kind.FURNACE, OVERWORLD, 100, true));
    }

    @Test
    public void heldNearMeasuresInThreeAxesFromTheBlockMiddle() {
        RunState state = new RunState();
        // 15 across and 15 up
        Workbenches.record(state, Kind.TABLE, pos(15, 79, 0), OVERWORLD, 1);
        assertFalse(held(state, Kind.TABLE, OVERWORLD, WorkbenchRules.NEAR, true));
        RunState close = new RunState();
        Workbenches.record(close, Kind.TABLE, pos(10, 74, 10), OVERWORLD, 1);
        assertTrue(held(close, Kind.TABLE, OVERWORLD, WorkbenchRules.NEAR, true));
        // the block middle against the player: 21 east of the player's block is exactly the radius
        RunState edge = new RunState();
        Workbenches.record(edge, Kind.TABLE, pos(21, 64, 0), OVERWORLD, 1);
        assertTrue(held(edge, Kind.TABLE, OVERWORLD, WorkbenchRules.NEAR, true));
        assertFalse(Workbenches.heldNear(edge, Kind.TABLE, OVERWORLD, 0.0, 64.5, 0.5, WorkbenchRules.NEAR, p -> true));
    }

    @Test
    public void anyIsAboutTheDimensionNotTheDistance() {
        RunState state = new RunState();
        assertFalse(Workbenches.any(state, Kind.TABLE, OVERWORLD));
        Workbenches.record(state, Kind.TABLE, pos(500, 64, 0), OVERWORLD, 1);
        assertTrue(Workbenches.any(state, Kind.TABLE, OVERWORLD));
        assertFalse(Workbenches.any(state, Kind.TABLE, NETHER));
        assertFalse(Workbenches.any(state, Kind.FURNACE, OVERWORLD));
        // busy or on its way down, it is still one
        state.benches.get(0).state = Bench.State.BUSY;
        assertTrue(Workbenches.any(state, Kind.TABLE, OVERWORLD));
        WorkbenchRules.beginPickup(state.benches.get(0), 5, 0, "test");
        assertTrue(Workbenches.any(state, Kind.TABLE, OVERWORLD));
    }

    // ---- phaseMayEnd

    @Test
    public void aPhaseMayEndOnlyOnceNothingIsStandingOrComingDown() {
        RunState state = new RunState();
        assertTrue(Workbenches.phaseMayEnd(state, OVERWORLD, 100));
        Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 100);
        assertFalse(Workbenches.phaseMayEnd(state, OVERWORLD, 101));
        Bench b = state.benches.get(0);
        WorkbenchRules.beginPickup(b, 102, 0, "test");
        assertFalse(Workbenches.phaseMayEnd(state, OVERWORLD, 103));
        // taken back
        Workbenches.forget(state, b, "test");
        assertTrue(Workbenches.phaseMayEnd(state, OVERWORLD, 104));
    }

    @Test
    public void busyOnesAndOtherDimensionsDoNotHoldAPhase() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(1, 64, 1), OVERWORLD, 100);
        Workbenches.record(state, Kind.TABLE, pos(9, 64, 1), NETHER, 100);
        Workbenches.find(state, Kind.FURNACE, pos(1, 64, 1)).state = Bench.State.BUSY;
        assertTrue(Workbenches.phaseMayEnd(state, OVERWORLD, 110));
        // but the nether table holds a nether phase
        assertFalse(Workbenches.phaseMayEnd(state, NETHER, 110));
    }

    // it builds the entries from the lists first, a state that was just loaded has none yet
    @Test
    public void phaseMayEndSyncsBeforeItLooks() {
        RunState state = new RunState();
        state.placedSmokers.add(pos(2, 64, 2));
        assertTrue(state.benches.isEmpty());
        assertFalse(Workbenches.phaseMayEnd(state, OVERWORLD, 100));
        assertEquals(1, state.benches.size());
    }

    // ---- source (what the container tasks see)

    private static FakeFacts facts(Dimension dimension, long now) {
        FakeFacts f = new FakeFacts();
        f.dimension = dimension;
        f.gameTime = now;
        return f;
    }

    @Test
    public void standingNearAnswersTheClosestStandingOneWithinNear() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(15, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.TABLE, pos(4, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.TABLE, pos(-9, 64, 0), OVERWORLD, 1);
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        assertEquals(new BlockPos(4, 64, 0), source.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
    }

    @Test
    public void standingNearStopsAtNearInThreeAxes() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(21, 64, 0), OVERWORLD, 1);
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        // dead on the line is in
        assertEquals(new BlockPos(21, 64, 0), source.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
        assertNull(source.standingNear(Kind.TABLE, 0.4, 64.5, 0.5));

        RunState tall = new RunState();
        Workbenches.record(tall, Kind.FURNACE, pos(15, 79, 0), OVERWORLD, 1);
        StationHook.Source tallSource = Workbenches.source(tall, facts(Dimension.OVERWORLD, 100));
        assertNull(tallSource.standingNear(Kind.FURNACE, 0.5, 64.5, 0.5));
        assertNotNull(tallSource.standingNear(Kind.FURNACE, 0.5, 70.5, 0.5));
    }

    @Test
    public void standingNearLeavesOutWhatIsComingDown() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(4, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.TABLE, pos(9, 64, 0), OVERWORLD, 1);
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.TABLE, pos(4, 64, 0)), 5, 0, "test");
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        // the closer one is on its way into the bag, the next one is the answer
        assertEquals(new BlockPos(9, 64, 0), source.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.TABLE, pos(9, 64, 0)), 5, 0, "test");
        assertNull(source.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
    }

    @Test
    public void standingNearWantsTheRightKindAndDimension() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(4, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.SMOKER, pos(6, 64, 0), NETHER, 1);
        StationHook.Source overworld = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        assertNull(overworld.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
        assertNull(overworld.standingNear(Kind.SMOKER, 0.5, 64.5, 0.5));
        assertNotNull(overworld.standingNear(Kind.FURNACE, 0.5, 64.5, 0.5));
        StationHook.Source nether = Workbenches.source(state, facts(Dimension.NETHER, 100));
        assertNull(nether.standingNear(Kind.FURNACE, 0.5, 64.5, 0.5));
        assertEquals(new BlockPos(6, 64, 0), nether.standingNear(Kind.SMOKER, 0.5, 64.5, 0.5));
    }

    // the source reads the registry as it is now, a phase can change it between two questions
    @Test
    public void theSourceSeesWhatIsRecordedAfterItWasMade() {
        RunState state = new RunState();
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        assertNull(source.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
        Workbenches.record(state, Kind.TABLE, pos(3, 64, 3), OVERWORLD, 1);
        assertNotNull(source.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
    }

    @Test
    public void pickingUpAKindFollowsTheDriveGrace() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(4, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.FURNACE, pos(8, 64, 0), OVERWORLD, 1);
        FakeFacts f = facts(Dimension.OVERWORLD, 1000);
        StationHook.Source source = Workbenches.source(state, f);
        assertFalse(source.pickingUp(Kind.TABLE));
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.TABLE, pos(4, 64, 0)), 1000, 0, "test");
        assertTrue(source.pickingUp(Kind.TABLE));
        // the same kind only
        assertFalse(source.pickingUp(Kind.FURNACE));
        assertFalse(source.pickingUp(Kind.SMOKER));
        f.gameTime = 1000 + WorkbenchRules.DRIVE_GRACE_TICKS;
        assertTrue(source.pickingUp(Kind.TABLE));
        f.gameTime = 1000 + WorkbenchRules.DRIVE_GRACE_TICKS + 1;
        assertFalse(source.pickingUp(Kind.TABLE));
    }

    @Test
    public void pickingUpABlockFollowsTheDriveGraceToo() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(4, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.TABLE, pos(9, 64, 0), OVERWORLD, 1);
        FakeFacts f = facts(Dimension.OVERWORLD, 1000);
        StationHook.Source source = Workbenches.source(state, f);
        assertFalse(source.pickingUp(new BlockPos(4, 64, 0)));
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.TABLE, pos(4, 64, 0)), 1000, 0, "test");
        assertTrue(source.pickingUp(new BlockPos(4, 64, 0)));
        // that exact block, not its neighbours and not the other table
        assertFalse(source.pickingUp(new BlockPos(9, 64, 0)));
        assertFalse(source.pickingUp(new BlockPos(4, 65, 0)));
        f.gameTime = 1000 + WorkbenchRules.DRIVE_GRACE_TICKS + 1;
        assertFalse(source.pickingUp(new BlockPos(4, 64, 0)));
    }

    // a pickup that was driven again is in flight again
    @Test
    public void drivingThePickupAgainRenewsTheVeto() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(4, 64, 0), OVERWORLD, 1);
        FakeFacts f = facts(Dimension.OVERWORLD, 1000);
        StationHook.Source source = Workbenches.source(state, f);
        Bench b = state.benches.get(0);
        WorkbenchRules.beginPickup(b, 1000, 0, "test");
        f.gameTime = 1150;
        assertFalse(source.pickingUp(Kind.TABLE));
        // a phase ran it a moment ago
        b.drivenTick = 1140;
        assertTrue(source.pickingUp(Kind.TABLE));
        assertTrue(source.pickingUp(new BlockPos(4, 64, 0)));
    }
}
