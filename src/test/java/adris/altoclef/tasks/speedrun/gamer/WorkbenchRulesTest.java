package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.WorkbenchRules.Call;
import adris.altoclef.tasks.speedrun.gamer.WorkbenchRules.Look;
import adris.altoclef.tasks.speedrun.gamer.WorkbenchRules.Visit;
import adris.altoclef.util.helpers.StationHook.Kind;
import adris.altoclef.util.helpers.WalkCost;
import java.util.List;
import org.junit.Test;

// every call the registry makes about a table, furnace or smoker of ours, with no game. Workbenches is the world half and only
// compile checked, this is where the rules live
public class WorkbenchRulesTest {
    private static final long NEVER = WorkbenchRules.NEVER;
    private static final String OVERWORLD = "OVERWORLD";

    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    private static Bench bench(Kind kind) {
        return new Bench(kind, pos(10, 64, 0), OVERWORLD, 0);
    }

    private static Bench table() {
        return bench(Kind.TABLE);
    }

    // one tick of what the world half hands decide(). starts as the dull case: next to a table the plan still wants, screen shut,
    // nothing in it, long since placed
    private static final class Seen {
        long now = 1000;
        double distance = 5;
        boolean sameDimension = true;
        boolean blockGone;
        boolean idle = true;
        boolean holdsStuff;
        boolean jobHere;
        boolean neededSoon = true;
        boolean canBreak = true;
        long limit = 600;
        boolean canRecraft;

        Look look() {
            return new Look(now, distance, sameDimension, blockGone, idle, holdsStuff, jobHere, neededSoon, canBreak, limit, canRecraft);
        }
    }

    private static Call decide(Bench b, Seen s) {
        return WorkbenchRules.decide(b, s.look());
    }

    // a bench with a pickup that started at `start`
    private static Bench pickingUp(long start) {
        return pickingUp(Kind.TABLE, start);
    }

    private static Bench pickingUp(Kind kind, long start) {
        Bench b = bench(kind);
        WorkbenchRules.beginPickup(b, start, 2, "test");
        return b;
    }

    // ---- one distance for everything

    @Test
    public void reuseIsAStraightLineInAllThreeAxes() {
        // 15 across and 15 up is 21.2, not "15 and some stairs"
        assertFalse(WalkCost.nearStation(15, 15, 0));
        assertFalse(WalkCost.nearStation(0, -15, 15));
        assertFalse(WalkCost.nearStation(15, 0, -15));
        // 10 / 10 / 10 is 17.3
        assertTrue(WalkCost.nearStation(10, 10, 10));
        assertTrue(WalkCost.nearStation(-10, -10, -10));
        // the table five up a shaft that used to cost more than a new one
        assertTrue(WalkCost.nearStation(0, 5, 0));
        assertTrue(WalkCost.nearStation(0, -20, 0));
        assertFalse(WalkCost.nearStation(0, -22, 0));
        // 12 each way is 20.8, 13 each way is 22.5
        assertTrue(WalkCost.nearStation(12, 12, 12));
        assertFalse(WalkCost.nearStation(13, 13, 13));
    }

    @Test
    public void exactlyTheRadiusIsNearAndAHairMoreIsNot() {
        assertEquals(21.0, WorkbenchRules.NEAR, 0);
        assertTrue(WalkCost.nearStation(21, 0, 0));
        assertTrue(WalkCost.nearStation(0, 0, -21));
        assertFalse(WalkCost.nearStation(21.01, 0, 0));
        assertFalse(WalkCost.nearStation(0, 21.01, 0));
        // 4 / 8 / 19 and 8 / 16 / 11 are both exactly 21 in all three axes
        assertTrue(WalkCost.nearStation(4, 8, 19));
        assertTrue(WalkCost.nearStation(8, 16, 11));
        assertFalse(WalkCost.nearStation(4, 8, 19.01));
    }

    // the planner, the container tasks and the pickup rules all measure from the player to the middle of the block, or they land
    // on different sides of the line
    @Test
    public void theMiddleOfTheBlockIsWhatGetsMeasured() {
        // block 21 east, player in the middle of his own block: dead on the line
        assertTrue(WalkCost.stationDistance(21, 0, 0, 0.5, 0.5, 0.5) <= WalkCost.STATION_NEAR);
        assertEquals(21.0, WalkCost.stationDistance(21, 0, 0, 0.5, 0.5, 0.5), 0);
        // one step further west and it is out
        assertFalse(WalkCost.stationDistance(21, 0, 0, 0.0, 0.5, 0.5) <= WalkCost.STATION_NEAR);
        assertTrue(WalkCost.stationDistance(21, 0, 0, 0.0, 0.5, 0.5) > WorkbenchRules.NEAR);
        // feet at y 64.5 and a block at y 85: 21 up. feet at 64.0 and it is 21.5
        assertTrue(WalkCost.stationDistance(0, 85, 0, 0.5, 64.5, 0.5) <= WalkCost.STATION_NEAR);
        assertFalse(WalkCost.stationDistance(0, 85, 0, 0.5, 64.0, 0.5) <= WalkCost.STATION_NEAR);
        // same block, same player: nothing between them
        assertEquals(0.0, WalkCost.stationDistance(3, 70, -4, 3.5, 70.5, -3.5), 0);
    }

    @Test
    public void forgetAndNearAreTheWalkCostNumbers() {
        assertEquals(WalkCost.STATION_NEAR, WorkbenchRules.NEAR, 0);
        assertEquals(WalkCost.STATION_FORGET, WorkbenchRules.FORGET_DISTANCE, 0);
        assertEquals(128.0, WorkbenchRules.FORGET_DISTANCE, 0);
    }

    @Test
    public void aStationThatWasLostHasToBeWellInsideToCountAgain() {
        assertEquals(21.0, WorkbenchRules.returnRadius(false), 0);
        assertEquals(21.0 * 0.75, WorkbenchRules.returnRadius(true), 1e-9);
        assertTrue(WorkbenchRules.returnRadius(true) < WorkbenchRules.returnRadius(false));
    }

    // a block we did not place can pop up near us too (another player, a chunk update), only reach is evidence
    @Test
    public void placementsOutsideReachAreNotOurs() {
        assertTrue(WorkbenchRules.placedByUs(0.5, 65.6, 0.5, pos(2, 64, 1)));
        assertTrue(WorkbenchRules.placedByUs(0.5, 65.6, 0.5, pos(7, 65, 0)));
        // across the street
        assertFalse(WorkbenchRules.placedByUs(0.5, 65.6, 0.5, pos(20, 64, 0)));
        assertFalse(WorkbenchRules.placedByUs(0.5, 65.6, 0.5, pos(0, 64, -30)));
    }

    @Test
    public void placedByUsMeasuresToTheMiddleOfTheBlockInThreeAxes() {
        // block 8 east of the player's own block middle: right on the reach, 9 is past it
        assertTrue(WorkbenchRules.placedByUs(0.5, 64.5, 0.5, pos(8, 64, 0)));
        assertFalse(WorkbenchRules.placedByUs(0.5, 64.5, 0.5, pos(9, 64, 0)));
        // 7 across and 5 up is 8.6
        assertFalse(WorkbenchRules.placedByUs(0.5, 64.5, 0.5, pos(7, 69, 0)));
        // and straight up or down counts the same as sideways
        assertTrue(WorkbenchRules.placedByUs(0.5, 64.5, 0.5, pos(0, 72, 0)));
        assertFalse(WorkbenchRules.placedByUs(0.5, 64.5, 0.5, pos(0, 73, 0)));
        assertFalse(WorkbenchRules.placedByUs(0.5, 64.5, 0.5, pos(0, 55, 0)));
    }

    // ---- what needs which station

    @Test
    public void aCraftWantsATableAndNothingElse() {
        for (String craft : List.of("stone_pickaxe", "bucket", "shield", "furnace", "iron_pickaxe")) {
            assertTrue(craft, WorkbenchRules.needsStation(Kind.TABLE, craft, true, true));
            assertFalse(craft, WorkbenchRules.needsStation(Kind.FURNACE, craft, true, true));
            assertFalse(craft, WorkbenchRules.needsStation(Kind.SMOKER, craft, true, true));
        }
    }

    @Test
    public void ironWantsTheFurnaceAndATableOnlyUntilThereIsAStonePick() {
        assertTrue(WorkbenchRules.needsStation(Kind.FURNACE, "iron_ingot", true, true));
        assertTrue(WorkbenchRules.needsStation(Kind.FURNACE, "iron_ingot", false, false));
        assertFalse(WorkbenchRules.needsStation(Kind.SMOKER, "iron_ingot", false, false));
        // the ore needs a stone pick and the mining task makes it at a table
        assertTrue(WorkbenchRules.needsStation(Kind.TABLE, "iron_ingot", false, false));
        assertTrue(WorkbenchRules.needsStation(Kind.TABLE, "iron_ingot", true, false));
        assertFalse(WorkbenchRules.needsStation(Kind.TABLE, "iron_ingot", true, true));
    }

    @Test
    public void cooksWantTheirOwnStation() {
        assertTrue(WorkbenchRules.needsStation(Kind.SMOKER, KitNeed.COOK_SMOKER, true, true));
        assertFalse(WorkbenchRules.needsStation(Kind.FURNACE, KitNeed.COOK_SMOKER, true, true));
        assertFalse(WorkbenchRules.needsStation(Kind.TABLE, KitNeed.COOK_SMOKER, false, false));
        assertTrue(WorkbenchRules.needsStation(Kind.FURNACE, KitNeed.COOK_FURNACE, true, true));
        assertFalse(WorkbenchRules.needsStation(Kind.SMOKER, KitNeed.COOK_FURNACE, true, true));
        assertFalse(WorkbenchRules.needsStation(Kind.TABLE, KitNeed.COOK_FURNACE, false, false));
    }

    // food hunts and cooks what it hunts, and crafts the hoe and the bread inside itself
    @Test
    public void foodWantsATableAndASmoker() {
        assertTrue(WorkbenchRules.needsStation(Kind.TABLE, KitNeed.FOOD, true, true));
        assertTrue(WorkbenchRules.needsStation(Kind.TABLE, KitNeed.FOOD, false, false));
        assertTrue(WorkbenchRules.needsStation(Kind.SMOKER, KitNeed.FOOD, true, true));
        assertFalse(WorkbenchRules.needsStation(Kind.FURNACE, KitNeed.FOOD, true, true));
    }

    @Test
    public void needsThatJustWalkAndMineWantNoStation() {
        for (String need : List.of("wool", "log", "planks", "flint", KitNeed.BUILD_BLOCKS, KitNeed.EQUIP_ARMOR)) {
            for (Kind kind : Kind.values()) {
                assertFalse(need + " " + kind, WorkbenchRules.needsStation(kind, need, true, true));
            }
        }
    }

    // what counts as a craft is KitNeed's call and the table rule is built on it
    @Test
    public void craftAndGatheringNamesAddUp() {
        assertTrue(KitNeed.isCraftName("furnace"));
        assertTrue(KitNeed.isCraftName("shield"));
        assertFalse(KitNeed.isCraftName("iron_ingot"));
        assertFalse(KitNeed.isCraftName("wool"));
        assertFalse(KitNeed.isCraftName(KitNeed.FOOD));
        assertFalse(KitNeed.isCraftName(KitNeed.EQUIP_ARMOR));
        assertFalse(KitNeed.isCraftName(KitNeed.COOK_SMOKER));
        assertFalse(KitNeed.isCraftName(KitNeed.COOK_FURNACE));
        assertFalse(KitNeed.isCraftName(null));
        assertTrue(new KitNeed("bucket", 2).isCraft());
    }

    @Test
    public void noNeedMeansNoStation() {
        for (Kind kind : Kind.values()) {
            assertFalse(WorkbenchRules.needsStation(kind, null, false, false));
            assertFalse(WorkbenchRules.neededSoon(kind, List.of(), false, false));
        }
    }

    @Test
    public void theFoodNeedCraftsInsideItself() {
        assertTrue(WorkbenchRules.needCraftsInside("food", true, true));
        assertTrue(WorkbenchRules.needCraftsInside("food", false, false));
        assertFalse(WorkbenchRules.needCraftsInside("stone_pickaxe", false, false));
        assertFalse(WorkbenchRules.needCraftsInside(null, false, false));
    }

    // cobble with no pick makes its wooden pick at the table, and a plan with no craft in it still wants one
    @Test
    public void miningWithoutThePickCraftsInside() {
        assertTrue(WorkbenchRules.needCraftsInside("cobblestone", false, false));
        assertFalse(WorkbenchRules.needCraftsInside("cobblestone", true, false));
        assertTrue(WorkbenchRules.needCraftsInside("coal", false, false));
        assertFalse(WorkbenchRules.needCraftsInside("coal", true, false));
        // the ore needs the stone tier, a wooden pick does not do
        assertTrue(WorkbenchRules.needCraftsInside("iron_ingot", true, false));
        assertFalse(WorkbenchRules.needCraftsInside("iron_ingot", true, true));
        assertFalse(WorkbenchRules.needCraftsInside("wool", false, false));
        assertFalse(WorkbenchRules.needCraftsInside("log", false, false));
    }

    // a need that crafts inside keeps the table through the lookahead, not just a craft that is literally in the plan
    @Test
    public void aNeedThatCraftsInsideKeepsTheTableSoon() {
        List<String> plan = List.of("wool", "cobblestone");
        assertTrue(WorkbenchRules.neededSoon(Kind.TABLE, plan, false, false));
        assertFalse(WorkbenchRules.neededSoon(Kind.TABLE, plan, true, false));
    }

    @Test
    public void theLookaheadIsTheCurrentNeedPlusThree() {
        assertEquals(3, WorkbenchRules.LOOKAHEAD);
        // wool, log, flint and planks want nothing, so only the pick decides
        String[] filler = {"wool", "log", "flint", "planks"};
        for (int at = 0; at < 6; at++) {
            String[] names = new String[6];
            for (int i = 0; i < names.length; i++) {
                names[i] = i == at ? "stone_pickaxe" : filler[i % filler.length];
            }
            // the first four count, the fifth does not
            assertEquals("craft at " + at, at <= 3, WorkbenchRules.neededSoon(Kind.TABLE, List.of(names), true, true));
        }
    }

    @Test
    public void theLookaheadWorksForEveryKind() {
        List<String> far = List.of("wool", "log", "flint", "planks", KitNeed.COOK_SMOKER, "iron_ingot", KitNeed.COOK_FURNACE);
        assertFalse(WorkbenchRules.neededSoon(Kind.SMOKER, far, true, true));
        assertFalse(WorkbenchRules.neededSoon(Kind.FURNACE, far, true, true));
        List<String> near = List.of("wool", "log", "flint", KitNeed.COOK_SMOKER, "iron_ingot");
        assertTrue(WorkbenchRules.neededSoon(Kind.SMOKER, near, true, true));
        assertFalse(WorkbenchRules.neededSoon(Kind.FURNACE, near, true, true));
        List<String> iron = List.of("wool", "log", "flint", "iron_ingot");
        assertTrue(WorkbenchRules.neededSoon(Kind.FURNACE, iron, true, true));
        assertFalse(WorkbenchRules.neededSoon(Kind.TABLE, iron, true, true));
        assertTrue(WorkbenchRules.neededSoon(Kind.TABLE, iron, true, false));
    }

    @Test
    public void aShortPlanIsJustItsOwnLength() {
        assertTrue(WorkbenchRules.neededSoon(Kind.TABLE, List.of("bucket"), true, true));
        assertFalse(WorkbenchRules.neededSoon(Kind.TABLE, List.of("wool"), true, true));
        assertTrue(WorkbenchRules.neededSoon(Kind.TABLE, List.of("wool", "wool", "bucket"), true, true));
    }

    // ---- rule 2: keep

    @Test
    public void aStationTheNextNeedsWantIsKeptWhileWeAreNearIt() {
        Bench b = table();
        Seen s = new Seen();
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(Bench.State.STANDING, b.state);
        assertEquals(NEVER, b.outsideSince);
        // a screen that is open is a reason to wait for a pickup, but nothing wants one while the plan needs the table
        s.idle = false;
        assertEquals(Call.KEEP, decide(b, s));
    }

    @Test
    public void aStationNothingWantsComesDown() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        assertEquals(Call.PICK_UP, decide(b, s));
        // deciding is not starting: the state only moves when the world half says so
        assertEquals(Bench.State.STANDING, b.state);
    }

    // ---- the gates only delay, none of them is a failed try

    @Test
    public void aScreenInUseOrATaskAtTheStationWaits() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        s.idle = false;
        assertEquals(Call.WAIT, decide(b, s));
        assertEquals(0, b.tries);
        assertEquals(NEVER, b.retryAt);
        s.idle = true;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void aBlockThatWentDownAMomentAgoIsLeftAlone() {
        Seen s = new Seen();
        s.neededSoon = false;
        Bench fresh = new Bench(Kind.TABLE, pos(10, 64, 0), OVERWORLD, s.now - (WorkbenchRules.PLACE_GUARD_TICKS - 1));
        assertEquals(Call.WAIT, decide(fresh, s));
        assertEquals(0, fresh.tries);
        Bench settled = new Bench(Kind.TABLE, pos(10, 64, 0), OVERWORLD, s.now - WorkbenchRules.PLACE_GUARD_TICKS);
        assertEquals(Call.PICK_UP, decide(settled, s));
        // placed this very tick
        assertEquals(Call.WAIT, decide(new Bench(Kind.TABLE, pos(10, 64, 0), OVERWORLD, s.now), s));
    }

    // CraftInTableTask shuts the screen and opens it again between the steps of one chain
    @Test
    public void aScreenThatWasShutAMomentAgoStillCounts() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        b.lastUsedTick = s.now - (WorkbenchRules.SETTLE_TICKS - 1);
        assertEquals(Call.WAIT, decide(b, s));
        assertEquals(0, b.tries);
        b.lastUsedTick = s.now - WorkbenchRules.SETTLE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void aScreenUseAtTickZeroIsARealUse() {
        Bench early = new Bench(Kind.TABLE, pos(10, 64, 0), OVERWORLD, -100);
        early.lastUsedTick = 0;
        Seen s = new Seen();
        s.now = 5;
        s.neededSoon = false;
        assertEquals(Call.WAIT, decide(early, s));
        s.now = WorkbenchRules.SETTLE_TICKS;
        assertEquals(Call.PICK_UP, decide(early, s));
    }

    @Test
    public void theRetryGapHoldsAndThenReleases() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        b.retryAt = s.now + 50;
        assertEquals(Call.WAIT, decide(b, s));
        s.now += 49;
        assertEquals(Call.WAIT, decide(b, s));
        s.now += 1;
        assertEquals(Call.PICK_UP, decide(b, s));
        assertEquals(0, b.tries);
    }

    // a gate is checked before the can-it-be-broken question, or a table behind a screen would burn its tries
    @Test
    public void aGateWinsOverUnreachable() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        s.canBreak = false;
        s.idle = false;
        assertEquals(Call.WAIT, decide(b, s));
        s.idle = true;
        assertEquals(Call.UNREACHABLE, decide(b, s));
        assertEquals(0, b.tries);
    }

    // ---- outside the circle

    @Test
    public void outsideFiveSecondsTakesBackAStationThePlanStillWants() {
        Bench b = table();
        Seen s = new Seen();
        s.distance = 30;
        s.now = 2000;
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(2000, b.outsideSince);
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS - 1;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
        assertEquals(100, WorkbenchRules.OUTSIDE_TICKS);
    }

    @Test
    public void aStepBackInsideStartsTheClockOver() {
        Bench b = table();
        Seen s = new Seen();
        s.distance = 30;
        s.now = 2000;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 2090;
        assertEquals(Call.KEEP, decide(b, s));
        // back inside for a tick
        s.distance = 5;
        s.now = 2095;
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(NEVER, b.outsideSince);
        // out again: without the reset 2100 would be the fifth second
        s.distance = 30;
        s.now = 2100;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 2100 + WorkbenchRules.OUTSIDE_TICKS - 1;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 2100 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void exactlyTheRadiusIsStillInsideAndAHairMoreStartsTheClock() {
        Bench b = table();
        Seen s = new Seen();
        s.distance = WorkbenchRules.NEAR;
        for (long t = 0; t < 1000; t += 50) {
            s.now = 2000 + t;
            assertEquals(Call.KEEP, decide(b, s));
        }
        assertEquals(NEVER, b.outsideSince);
        s.distance = WorkbenchRules.NEAR + 0.01;
        s.now = 5000;
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(5000, b.outsideSince);
        s.now = 5000 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void theOutsidePickupStillWaitsForTheGates() {
        Bench b = table();
        Seen s = new Seen();
        s.distance = 30;
        s.now = 2000;
        decide(b, s);
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS;
        s.idle = false;
        assertEquals(Call.WAIT, decide(b, s));
        s.idle = true;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void outsideLongEnoughIsAboutTheClockAlone() {
        Bench b = table();
        assertFalse(WorkbenchRules.outsideLongEnough(b, 5000));
        b.outsideSince = 4900;
        assertFalse(WorkbenchRules.outsideLongEnough(b, 4999));
        assertTrue(WorkbenchRules.outsideLongEnough(b, 5000));
        // a clock that started at tick 0 is a clock
        b.outsideSince = 0;
        assertTrue(WorkbenchRules.outsideLongEnough(b, WorkbenchRules.OUTSIDE_TICKS));
        assertFalse(WorkbenchRules.outsideLongEnough(b, WorkbenchRules.OUTSIDE_TICKS - 1));
    }

    @Test
    public void thePickupReasonIsInPlainWords() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        String unwanted = WorkbenchRules.reason(b, s.look());
        assertTrue(unwanted, unwanted.contains("table"));
        assertTrue(WorkbenchRules.reason(bench(Kind.SMOKER), s.look()).contains("smoker"));
        s.neededSoon = true;
        // wanted but we wandered off: the reason is the distance and the five seconds, not the plan
        String wandered = WorkbenchRules.reason(b, s.look());
        assertTrue(wandered, wandered.contains("21") && wandered.contains("5 s"));
        assertFalse(unwanted.equals(wandered));
    }

    // ---- busy

    @Test
    public void aStationWithOurStuffInItIsNeverTakenAndTheStateSaysSo() {
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.holdsStuff = true;
        s.neededSoon = false;
        // however far, however unwanted, whatever the gates would say
        for (double d : new double[]{0, 5, 21, 22, 60, 127, 128, 500}) {
            s.distance = d;
            s.now += 500;
            assertEquals("at " + d, Call.BUSY, decide(b, s));
            assertEquals(Bench.State.BUSY, b.state);
        }
    }

    @Test
    public void busyGoesBackToStandingOnceItStopsHolding() {
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.holdsStuff = true;
        s.neededSoon = false;
        assertEquals(Call.BUSY, decide(b, s));
        assertEquals(Bench.State.BUSY, b.state);
        s.holdsStuff = false;
        s.now += 20;
        assertEquals(Call.PICK_UP, decide(b, s));
        assertEquals(Bench.State.STANDING, b.state);
        // and wanted again it is just kept
        s.neededSoon = true;
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(Bench.State.STANDING, b.state);
    }

    // the clock keeps counting while the job runs, so a job that finishes while we were away does not buy another five seconds
    @Test
    public void theOutsideClockRunsWhileBusyToo() {
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.distance = 50;
        s.holdsStuff = true;
        s.now = 1000;
        assertEquals(Call.BUSY, decide(b, s));
        assertEquals(1000, b.outsideSince);
        s.holdsStuff = false;
        s.now = 1500;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void busyIsNotForgottenByDistanceButIdleIs() {
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.holdsStuff = true;
        s.distance = 500;
        // it was busy on the tick before, which is what the exemption reads
        b.state = Bench.State.BUSY;
        assertEquals(Call.BUSY, decide(b, s));
        // the job is collected a long way off, now it is just a station nobody will walk back to
        s.holdsStuff = false;
        decide(b, s);
        assertEquals(Bench.State.STANDING, b.state);
        assertEquals(Call.FORGET_TOO_FAR, decide(b, s));
    }

    // a relog rebuilds every bench as STANDING, so the exemption can't only read last tick's state: the look says it holds our items
    @Test
    public void aStationThatHoldsOurStuffRightNowIsNotForgottenEvenIfItWasStanding() {
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.holdsStuff = true;
        s.distance = 500;
        assertEquals(Bench.State.STANDING, b.state);
        assertEquals(Call.BUSY, decide(b, s));
        assertEquals(Bench.State.BUSY, b.state);
    }

    // ---- forget

    @Test
    public void aBlockThatIsGoneIsForgotten() {
        Seen s = new Seen();
        s.blockGone = true;
        assertEquals(Call.FORGET_GONE, decide(table(), s));
        // a furnace with something in it too: the block is what matters
        Bench furnace = bench(Kind.FURNACE);
        furnace.state = Bench.State.BUSY;
        s.holdsStuff = true;
        assertEquals(Call.FORGET_GONE, decide(furnace, s));
    }

    @Test
    public void furtherThan128IsForgottenAndExactly128IsNot() {
        Seen s = new Seen();
        s.neededSoon = false;
        s.distance = WorkbenchRules.FORGET_DISTANCE;
        assertEquals(Call.PICK_UP, decide(table(), s));
        s.distance = WorkbenchRules.FORGET_DISTANCE + 0.01;
        assertEquals(Call.FORGET_TOO_FAR, decide(table(), s));
        // wanted or not
        s.neededSoon = true;
        assertEquals(Call.FORGET_TOO_FAR, decide(table(), s));
    }

    @Test
    public void anotherDimensionHasToLastBeforeItCounts() {
        Bench b = table();
        Seen s = new Seen();
        s.sameDimension = false;
        s.now = 3000;
        // the first tick of a loading screen
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 3000 + WorkbenchRules.DIMENSION_TICKS - 1;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 3000 + WorkbenchRules.DIMENSION_TICKS;
        assertEquals(Call.FORGET_LEFT_DIMENSION, decide(b, s));
    }

    @Test
    public void oneTickInAnotherDimensionIsJustKept() {
        Bench b = table();
        Seen s = new Seen();
        s.sameDimension = false;
        s.now = 3000;
        assertEquals(Call.KEEP, decide(b, s));
        // back
        s.sameDimension = true;
        s.now = 3001;
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(NEVER, b.otherDimensionSince);
        // a long time later another blip does not inherit the first
        s.sameDimension = false;
        s.now = 9000;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 9000 + WorkbenchRules.DIMENSION_TICKS - 1;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 9000 + WorkbenchRules.DIMENSION_TICKS;
        assertEquals(Call.FORGET_LEFT_DIMENSION, decide(b, s));
    }

    // our own break is the block going away, not a station that disappeared
    @Test
    public void aPickupWhoseBlockVanishedKeepsGoing() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.now = 1030;
        s.blockGone = true;
        assertEquals(Call.CONTINUE, decide(b, s));
    }

    @Test
    public void aPickupFarFromEverythingIsForgottenLikeAnythingElse() {
        Seen s = new Seen();
        s.now = 1030;
        s.distance = 300;
        assertEquals(Call.FORGET_TOO_FAR, decide(pickingUp(1000), s));
    }

    // ---- the pickup in flight

    @Test
    public void aPickupKeepsGoingWhileItHasTime() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.now = 1001;
        assertEquals(Call.CONTINUE, decide(b, s));
        // it never runs the gates, the keep rule or the outside clock
        s.neededSoon = true;
        s.idle = false;
        s.distance = 60;
        s.now = 1100;
        assertEquals(Call.CONTINUE, decide(b, s));
        assertEquals(Bench.State.PICKING_UP, b.state);
        assertEquals(NEVER, b.outsideSince);
    }

    @Test
    public void aJobInItCallsThePickupOffButOnlyBeforeTheBlockIsDown() {
        Seen s = new Seen();
        s.now = 1010;
        s.jobHere = true;
        s.holdsStuff = true;
        assertEquals(Call.ABORT_BUSY, decide(pickingUp(Kind.FURNACE, 1000), s));
        // the container tracker's last look is not evidence enough to call a pickup off
        Seen tracker = new Seen();
        tracker.now = 1010;
        tracker.holdsStuff = true;
        assertEquals(Call.CONTINUE, decide(pickingUp(Kind.FURNACE, 1000), tracker));
        // the block is already going down, that is our break landing
        s.blockGone = true;
        assertEquals(Call.CONTINUE, decide(pickingUp(Kind.FURNACE, 1000), s));
        // and the drop phase does not care about jobs at all
        Bench broken = pickingUp(Kind.FURNACE, 1000);
        WorkbenchRules.blockBroken(broken, 1005);
        s.blockGone = false;
        assertEquals(Call.CONTINUE, decide(broken, s));
    }

    @Test
    public void aStandingBenchWithAJobIsBusyNotAborted() {
        Bench b = bench(Kind.SMOKER);
        Seen s = new Seen();
        s.jobHere = true;
        s.holdsStuff = true;
        assertEquals(Call.BUSY, decide(b, s));
    }

    @Test
    public void aPickupThatRunsOutOfTimeIsTimedOut() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.limit = 600;
        s.now = 1600;
        assertEquals(Call.CONTINUE, decide(b, s));
        s.now = 1601;
        assertEquals(Call.TIMED_OUT, decide(b, s));
        // the block already down is not a timeout, the world half is about to notice it
        s.blockGone = true;
        assertEquals(Call.CONTINUE, decide(b, s));
    }

    @Test
    public void theTimeLimitComesFromTheLook() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.limit = 40;
        s.now = 1040;
        assertEquals(Call.CONTINUE, decide(b, s));
        s.now = 1041;
        assertEquals(Call.TIMED_OUT, decide(b, s));
    }

    @Test
    public void aDropThatNeverArrivesIsGivenUpAfterItsOwnWindow() {
        Bench b = pickingUp(1000);
        WorkbenchRules.blockBroken(b, 2000);
        Seen s = new Seen();
        s.blockGone = true;
        s.limit = 600;
        // the break phase limit means nothing any more
        s.now = 2000 + 601;
        assertEquals(Call.CONTINUE, decide(b, s));
        s.now = 2000 + WorkbenchRules.DROP_GIVE_UP_TICKS;
        assertEquals(Call.CONTINUE, decide(b, s));
        s.now = 2000 + WorkbenchRules.DROP_GIVE_UP_TICKS + 1;
        assertEquals(Call.DROP_LOST, decide(b, s));
    }

    @Test
    public void theDropClockStartsWhenTheBlockCameDownNotWhenThePickupBegan() {
        Bench b = pickingUp(1000);
        // the break took forever
        long brokeAt = 1000 + 500;
        WorkbenchRules.blockBroken(b, brokeAt);
        assertTrue(b.broken);
        assertEquals(brokeAt, b.pickupStart);
        Seen s = new Seen();
        s.blockGone = true;
        s.now = 1000 + WorkbenchRules.DROP_GIVE_UP_TICKS + 1;
        assertEquals(Call.CONTINUE, decide(b, s));
    }

    @Test
    public void pickupBookkeepingStartsClean() {
        Bench b = table();
        b.broken = true;
        WorkbenchRules.beginPickup(b, 777, 4, "because");
        assertEquals(Bench.State.PICKING_UP, b.state);
        assertEquals(777, b.pickupStart);
        assertEquals(777, b.drivenTick);
        assertFalse(b.broken);
        assertEquals(4, b.bagBefore);
        assertEquals("because", b.why);
    }

    @Test
    public void theItemIsBackOnceThereIsOneMoreThanBefore() {
        assertFalse(WorkbenchRules.pickupDone(false, 5, 1));
        assertFalse(WorkbenchRules.pickupDone(true, 2, 2));
        assertFalse(WorkbenchRules.pickupDone(true, 1, 2));
        assertTrue(WorkbenchRules.pickupDone(true, 3, 2));
        assertTrue(WorkbenchRules.pickupDone(true, 1, 0));
    }

    // ---- tries

    @Test
    public void aFailedTryCountsGoesBackToStandingAndWaitsItsGap() {
        Bench b = pickingUp(1000);
        WorkbenchRules.blockBroken(b, 1100);
        assertFalse(WorkbenchRules.failedTry(b, 5000));
        assertEquals(1, b.tries);
        assertEquals(Bench.State.STANDING, b.state);
        assertEquals(NEVER, b.pickupStart);
        assertFalse(b.broken);
        assertEquals(5000 + WorkbenchRules.RETRY_GAP_TICKS, b.retryAt);
    }

    @Test
    public void onlyTheThirdFailedTryIsTheLastOne() {
        assertEquals(3, WorkbenchRules.MAX_TRIES);
        Bench b = table();
        assertFalse(WorkbenchRules.failedTry(b, 1000));
        assertFalse(WorkbenchRules.failedTry(b, 2000));
        assertTrue(WorkbenchRules.failedTry(b, 3000));
        assertEquals(3, b.tries);
        // the gap is from the latest try
        assertEquals(3000 + WorkbenchRules.RETRY_GAP_TICKS, b.retryAt);
    }

    @Test
    public void afterAFailedTryTheGapWaitsAndThenItPicksUpAgain() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.neededSoon = false;
        s.now = 5000;
        WorkbenchRules.failedTry(b, s.now);
        s.now = 5000 + WorkbenchRules.RETRY_GAP_TICKS - 1;
        assertEquals(Call.WAIT, decide(b, s));
        s.now = 5000 + WorkbenchRules.RETRY_GAP_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
        assertEquals(1, b.tries);
    }

    @Test
    public void aStationThatCannotBeBrokenIsUnreachableUntilTheTriesRunOut() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        s.canBreak = false;
        s.now = 5000;
        assertEquals(Call.UNREACHABLE, decide(b, s));
        assertFalse(WorkbenchRules.failedTry(b, s.now));
        s.now += WorkbenchRules.RETRY_GAP_TICKS - 1;
        assertEquals(Call.WAIT, decide(b, s));
        s.now += 1;
        assertEquals(Call.UNREACHABLE, decide(b, s));
        assertFalse(WorkbenchRules.failedTry(b, s.now));
        s.now += WorkbenchRules.RETRY_GAP_TICKS;
        assertEquals(Call.UNREACHABLE, decide(b, s));
        assertTrue(WorkbenchRules.failedTry(b, s.now));
    }

    @Test
    public void aTimedOutPickupAndAnUnreachableOneUseTheSameCounter() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.limit = 100;
        s.now = 1101;
        assertEquals(Call.TIMED_OUT, decide(b, s));
        assertFalse(WorkbenchRules.failedTry(b, s.now));
        s.neededSoon = false;
        s.canBreak = false;
        s.now += WorkbenchRules.RETRY_GAP_TICKS;
        assertEquals(Call.UNREACHABLE, decide(b, s));
        assertFalse(WorkbenchRules.failedTry(b, s.now));
        WorkbenchRules.beginPickup(b, s.now + WorkbenchRules.RETRY_GAP_TICKS, 0, "again");
        assertTrue(WorkbenchRules.failedTry(b, s.now + 2 * WorkbenchRules.RETRY_GAP_TICKS));
    }

    // ---- rule 3: the visit that empties a station

    @Test
    public void somethingStillCookingMeansLeaveIt() {
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(3, true, false, false));
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(1, true, false, true));
    }

    @Test
    public void emptyAndOursComesDownAfterTheCookIfThereIsOne() {
        assertEquals(Visit.PICK_UP, WorkbenchRules.afterVisit(0, true, false, false));
        assertEquals(Visit.COOK_THEN_PICK_UP, WorkbenchRules.afterVisit(0, true, false, true));
    }

    // the village's blast furnace is never ours to take, and neither is a spot another job is using
    @Test
    public void notOursOrAnotherJobHereMeansLeaveIt() {
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(0, false, false, false));
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(0, false, false, true));
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(0, true, true, false));
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(0, true, true, true));
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(0, false, true, true));
    }

    // ---- cooking: the smoker wins

    @Test
    public void meatGoesInASmokerOfOursNearOrInTheBag() {
        assertTrue(WorkbenchRules.cookInSmoker(true, false));
        assertTrue(WorkbenchRules.cookInSmoker(false, true));
        assertTrue(WorkbenchRules.cookInSmoker(true, true));
        assertFalse(WorkbenchRules.cookInSmoker(false, false));
    }

    @Test
    public void theEmptiedFurnaceNeverGetsMeatWhileASmokerOfOursIsAround() {
        assertFalse(WorkbenchRules.emptiedStationMayCook(false, true, false));
        assertFalse(WorkbenchRules.emptiedStationMayCook(false, false, true));
        assertFalse(WorkbenchRules.emptiedStationMayCook(false, true, true));
        assertTrue(WorkbenchRules.emptiedStationMayCook(false, false, false));
    }

    // a smoker that was just emptied is the smoker
    @Test
    public void anEmptiedSmokerAlwaysMayCook() {
        for (boolean near : new boolean[]{false, true}) {
            for (boolean bag : new boolean[]{false, true}) {
                assertTrue(WorkbenchRules.emptiedStationMayCook(true, near, bag));
            }
        }
    }

    // ---- rule 5: a pickup and a placement of the same kind never overlap

    private static Bench driven(Kind kind, RunState.Pos at, long drivenAt) {
        Bench b = new Bench(kind, at, OVERWORLD, 0);
        WorkbenchRules.beginPickup(b, drivenAt, 0, "test");
        return b;
    }

    @Test
    public void aPickupIsInFlightOnlyWhileSomebodyDrivesIt() {
        Bench b = driven(Kind.TABLE, pos(1, 64, 1), 1000);
        assertTrue(WorkbenchRules.inFlight(b, 1000));
        assertTrue(WorkbenchRules.inFlight(b, 1000 + WorkbenchRules.DRIVE_GRACE_TICKS));
        assertFalse(WorkbenchRules.inFlight(b, 1000 + WorkbenchRules.DRIVE_GRACE_TICKS + 1));
        // driven again, the grace starts over
        b.drivenTick = 2000;
        assertTrue(WorkbenchRules.inFlight(b, 2050));
    }

    @Test
    public void onlyAPickupCanBeInFlight() {
        Bench standing = table();
        standing.drivenTick = 1000;
        assertFalse(WorkbenchRules.inFlight(standing, 1000));
        Bench busy = table();
        busy.state = Bench.State.BUSY;
        busy.drivenTick = 1000;
        assertFalse(WorkbenchRules.inFlight(busy, 1000));
        Bench neverDriven = table();
        neverDriven.state = Bench.State.PICKING_UP;
        assertFalse(WorkbenchRules.inFlight(neverDriven, 1000));
    }

    @Test
    public void aPickupVetoesPlacingTheSameKindOnly() {
        List<Bench> benches = List.of(driven(Kind.TABLE, pos(1, 64, 1), 1000), bench(Kind.FURNACE));
        assertTrue(WorkbenchRules.placeVetoed(benches, Kind.TABLE, 1050));
        assertFalse(WorkbenchRules.placeVetoed(benches, Kind.FURNACE, 1050));
        assertFalse(WorkbenchRules.placeVetoed(benches, Kind.SMOKER, 1050));
        assertFalse(WorkbenchRules.placeVetoed(List.of(), Kind.TABLE, 1050));
    }

    @Test
    public void aPickupNobodyDrivesVetoesNothing() {
        List<Bench> benches = List.of(driven(Kind.TABLE, pos(1, 64, 1), 1000));
        assertTrue(WorkbenchRules.placeVetoed(benches, Kind.TABLE, 1100));
        assertFalse(WorkbenchRules.placeVetoed(benches, Kind.TABLE, 1101));
        assertTrue(WorkbenchRules.targetVetoed(benches, pos(1, 64, 1), 1100));
        assertFalse(WorkbenchRules.targetVetoed(benches, pos(1, 64, 1), 1101));
    }

    @Test
    public void aPickupVetoesWalkingToThatExactBlock() {
        List<Bench> benches = List.of(driven(Kind.TABLE, pos(1, 64, 1), 1000), new Bench(Kind.TABLE, pos(5, 64, 5), OVERWORLD, 0));
        assertTrue(WorkbenchRules.targetVetoed(benches, pos(1, 64, 1), 1010));
        // the other table, and the block next to the one coming down, are fair game
        assertFalse(WorkbenchRules.targetVetoed(benches, pos(5, 64, 5), 1010));
        assertFalse(WorkbenchRules.targetVetoed(benches, pos(1, 65, 1), 1010));
        assertFalse(WorkbenchRules.targetVetoed(List.of(), pos(1, 64, 1), 1010));
    }

    @Test
    public void aStandingBenchVetoesNothing() {
        List<Bench> benches = List.of(table());
        assertFalse(WorkbenchRules.placeVetoed(benches, Kind.TABLE, 1000));
        assertFalse(WorkbenchRules.targetVetoed(benches, pos(10, 64, 0), 1000));
    }

    @Test
    public void aStalePickupWithTheBlockStillUpGoesBackToStanding() {
        Bench b = driven(Kind.TABLE, pos(1, 64, 1), 1000);
        assertFalse(WorkbenchRules.dropStaleDrive(b, 1000 + WorkbenchRules.DRIVE_GRACE_TICKS));
        assertEquals(Bench.State.PICKING_UP, b.state);
        assertTrue(WorkbenchRules.dropStaleDrive(b, 1000 + WorkbenchRules.DRIVE_GRACE_TICKS + 1));
        assertEquals(Bench.State.STANDING, b.state);
        assertEquals(NEVER, b.pickupStart);
        // and now it vetoes nothing, and says nothing a second time
        assertFalse(WorkbenchRules.placeVetoed(List.of(b), Kind.TABLE, 1000 + WorkbenchRules.DRIVE_GRACE_TICKS + 1));
        assertFalse(WorkbenchRules.dropStaleDrive(b, 9999));
    }

    // the block is already down, only the drop is left, and that one has its own long window (DROP_GIVE_UP_TICKS)
    @Test
    public void aBrokenPickupIsNotResetEvenIfNobodyDrivesIt() {
        Bench b = driven(Kind.TABLE, pos(1, 64, 1), 1000);
        WorkbenchRules.blockBroken(b, 1010);
        assertFalse(WorkbenchRules.dropStaleDrive(b, 1000 + 10 * WorkbenchRules.DRIVE_GRACE_TICKS));
        assertEquals(Bench.State.PICKING_UP, b.state);
        assertTrue(b.broken);
    }

    @Test
    public void staleResetLeavesOtherStatesAlone() {
        Bench standing = table();
        assertFalse(WorkbenchRules.dropStaleDrive(standing, 99999));
        Bench busy = table();
        busy.state = Bench.State.BUSY;
        busy.drivenTick = 0;
        assertFalse(WorkbenchRules.dropStaleDrive(busy, 99999));
        assertEquals(Bench.State.BUSY, busy.state);
    }

    // ---- rule 6: a phase may end

    private static Bench in(Kind kind, String dimension, Bench.State state) {
        Bench b = new Bench(kind, pos(1, 64, 1), dimension, 0);
        b.state = state;
        return b;
    }

    private static boolean mayEnd(List<Bench> benches, String dimension) {
        // every busy one has its job
        return WorkbenchRules.phaseMayEnd(benches, dimension, b -> true);
    }

    @Test
    public void aPhaseCannotEndWithAStationStandingOrComingDown() {
        assertFalse(mayEnd(List.of(in(Kind.TABLE, OVERWORLD, Bench.State.STANDING)), OVERWORLD));
        assertFalse(mayEnd(List.of(in(Kind.FURNACE, OVERWORLD, Bench.State.PICKING_UP)), OVERWORLD));
        assertFalse(mayEnd(List.of(in(Kind.FURNACE, OVERWORLD, Bench.State.BUSY),
                in(Kind.TABLE, OVERWORLD, Bench.State.STANDING)), OVERWORLD));
    }

    @Test
    public void aPhaseMayEndWithNothingOrOnlyBusyOrFarAwayOnes() {
        assertTrue(mayEnd(List.of(), OVERWORLD));
        // busy with a job is the job's business, the phase waits for those itself
        assertTrue(mayEnd(List.of(in(Kind.FURNACE, OVERWORLD, Bench.State.BUSY),
                in(Kind.SMOKER, OVERWORLD, Bench.State.BUSY)), OVERWORLD));
        // already forgotten, as far as this world goes
        assertTrue(mayEnd(List.of(in(Kind.TABLE, "NETHER", Bench.State.STANDING),
                in(Kind.TABLE, "END", Bench.State.PICKING_UP)), OVERWORLD));
        assertFalse(mayEnd(List.of(in(Kind.TABLE, "NETHER", Bench.State.STANDING)), "NETHER"));
        assertTrue(mayEnd(List.of(in(Kind.TABLE, OVERWORLD, Bench.State.IN_BAG)), OVERWORLD));
    }

    // an interrupted load that was not adopted yet: our items in it, no job, so nobody would ever visit it. the phase waits for the
    // adoption (and the visit it brings) instead of ending around it
    @Test
    public void aBusyStationWithNoJobHoldsThePhaseUntilItIsAdopted() {
        Bench loaded = in(Kind.FURNACE, OVERWORLD, Bench.State.BUSY);
        assertFalse(WorkbenchRules.phaseMayEnd(List.of(loaded), OVERWORLD, b -> false));
        assertFalse(WorkbenchRules.phaseMayEnd(List.of(in(Kind.SMOKER, OVERWORLD, Bench.State.BUSY)), OVERWORLD, b -> false));
        // adopted: the job is the phase's business again
        assertTrue(WorkbenchRules.phaseMayEnd(List.of(loaded), OVERWORLD, b -> b == loaded));
        // a busy one in another dimension never holds this one, job or not
        assertTrue(WorkbenchRules.phaseMayEnd(List.of(in(Kind.FURNACE, "NETHER", Bench.State.BUSY)), OVERWORLD, b -> false));
    }

    // ---- loads

    @Test
    public void noPickupStartsWhileAScreenIsOpenOrWasJustWorked() {
        long now = 5000;
        assertTrue(WorkbenchRules.loadInFlight(true, -1, now));
        assertTrue(WorkbenchRules.loadInFlight(true, now - 5000, now));
        // the screen shut for the tick between two clicks of the same load
        assertTrue(WorkbenchRules.loadInFlight(false, now - 1, now));
        assertTrue(WorkbenchRules.loadInFlight(false, now - WorkbenchRules.LOAD_GRACE_TICKS, now));
        // a second later it is over, and it can never hold for ever
        assertFalse(WorkbenchRules.loadInFlight(false, now - WorkbenchRules.LOAD_GRACE_TICKS - 1, now));
        assertFalse(WorkbenchRules.loadInFlight(false, -1, now));
        // a stamp from a world that ran further than this one is not a load
        assertFalse(WorkbenchRules.loadInFlight(false, now + 500, now));
    }

    @Test
    public void aSmeltStampAtTickZeroIsARealStamp() {
        assertTrue(WorkbenchRules.loadInFlight(false, 0, 10));
        assertFalse(WorkbenchRules.loadInFlight(false, 0, 21));
    }

    // ---- round 2: a table too far to walk back to

    // out past 48 for the usual five seconds with planks in the bag: not worth the trip, forget it (the log says why)
    @Test
    public void aTablePast48BlocksIsForgottenWhenTheBagCanCraftAnother() {
        Bench b = table();
        Seen s = new Seen();
        s.distance = 60;
        s.canRecraft = true;
        s.now = 2000;
        // the outside clock has to run first, a blip past the line is not a trip
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS - 1;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.FORGET_FAR_TABLE, decide(b, s));
        assertEquals(48.0, WorkbenchRules.FAR_TABLE_DISTANCE, 0);
    }

    // the plan wanting the table changes nothing out there: the planner stopped counting it as held at NEAR, so it asks for the planks
    @Test
    public void aFarTableIsForgottenWhetherOrNotThePlanWantsIt() {
        for (boolean wanted : new boolean[]{true, false}) {
            Bench b = table();
            Seen s = new Seen();
            s.distance = 90;
            s.canRecraft = true;
            s.neededSoon = wanted;
            s.now = 2000;
            decide(b, s);
            s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS;
            assertEquals(Call.FORGET_FAR_TABLE, decide(b, s));
        }
    }

    // nothing to craft one from: the old trip back stays, up to the 128 that forgets everything
    @Test
    public void aFarTableWeCannotRemakeIsStillWalkedBackTo() {
        Bench b = table();
        Seen s = new Seen();
        s.distance = 60;
        s.canRecraft = false;
        s.neededSoon = false;
        s.now = 2000;
        decide(b, s);
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void exactly48IsStillAWalkAndOnlyTablesGoThisWay() {
        Seen s = new Seen();
        s.canRecraft = true;
        s.neededSoon = false;
        s.distance = WorkbenchRules.FAR_TABLE_DISTANCE;
        Bench t = table();
        s.now = 2000;
        decide(t, s);
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(t, s));
        // a furnace or a smoker keeps the long trip, they cost stone and a table to make
        for (Kind kind : new Kind[]{Kind.FURNACE, Kind.SMOKER}) {
            Bench b = bench(kind);
            s.distance = 100;
            s.now = 3000;
            decide(b, s);
            s.now = 3000 + WorkbenchRules.OUTSIDE_TICKS;
            assertEquals(Call.PICK_UP, decide(b, s));
        }
    }

    @Test
    public void aTableWithinTheNearLineNeverCountsAsFar() {
        Bench b = table();
        Seen s = new Seen();
        s.canRecraft = true;
        s.distance = WorkbenchRules.NEAR;
        s.now = 2000;
        decide(b, s);
        s.now = 90000;
        assertEquals(Call.KEEP, decide(b, s));
    }

    @Test
    public void whatTheBagCanRemake() {
        assertTrue(WorkbenchRules.canRecraftTable(4, 0));
        assertTrue(WorkbenchRules.canRecraftTable(0, 1));
        assertTrue(WorkbenchRules.canRecraftTable(3, 1));
        assertFalse(WorkbenchRules.canRecraftTable(3, 0));
        assertFalse(WorkbenchRules.canRecraftTable(0, 0));
    }

    // ---- round 2: busy in a dimension we left

    @Test
    public void aBusyStationInAnotherDimensionIsKeptNotForgotten() {
        for (Kind kind : new Kind[]{Kind.FURNACE, Kind.SMOKER}) {
            Bench b = bench(kind);
            b.state = Bench.State.BUSY;
            Seen s = new Seen();
            s.sameDimension = false;
            s.now = 3000;
            assertEquals(Call.ELSEWHERE, decide(b, s));
            // a minute, an hour: nothing in the rules forgets it, the run ending does
            s.now = 3000 + WorkbenchRules.DIMENSION_TICKS;
            assertEquals(Call.ELSEWHERE, decide(b, s));
            s.now = 3000 + 20 * 3600;
            assertEquals(Call.ELSEWHERE, decide(b, s));
            assertEquals(Bench.State.BUSY, b.state);
        }
    }

    // a relog rebuilds every bench as standing, the job recorded in that dimension is what says it still holds our items
    @Test
    public void aRecordedJobThereKeepsAStationThatWasRebuiltAsStanding() {
        Bench b = bench(Kind.FURNACE);
        assertEquals(Bench.State.STANDING, b.state);
        Seen s = new Seen();
        s.sameDimension = false;
        s.jobHere = true;
        assertEquals(Call.ELSEWHERE, decide(b, s));
        assertEquals(Bench.State.BUSY, b.state);
    }

    // an idle one is still forgotten a second after we left, like before. only the busy ones wait
    @Test
    public void anIdleStationInAnotherDimensionStillGoes() {
        for (Kind kind : Kind.values()) {
            Bench b = bench(kind);
            Seen s = new Seen();
            s.sameDimension = false;
            s.now = 3000;
            assertEquals(Call.KEEP, decide(b, s));
            s.now = 3000 + WorkbenchRules.DIMENSION_TICKS;
            assertEquals(Call.FORGET_LEFT_DIMENSION, decide(b, s));
        }
    }

    // back in the dimension it is a normal busy station again: never forgotten by distance, picked up by the visit that empties it
    @Test
    public void backInTheDimensionABusyStationIsBusyAsBefore() {
        Bench b = bench(Kind.FURNACE);
        b.state = Bench.State.BUSY;
        Seen s = new Seen();
        s.sameDimension = false;
        s.now = 3000;
        assertEquals(Call.ELSEWHERE, decide(b, s));
        s.sameDimension = true;
        s.holdsStuff = true;
        s.jobHere = true;
        s.distance = 90;
        s.now = 9000;
        assertEquals(Call.BUSY, decide(b, s));
        assertEquals(NEVER, b.otherDimensionSince);
    }

    // ---- round 2: an interrupted load is adopted

    private static Look idleHolding() {
        Seen s = new Seen();
        s.holdsStuff = true;
        return s.look();
    }

    @Test
    public void aFurnaceOrSmokerHoldingOurItemsWithNoJobIsAdopted() {
        assertTrue(WorkbenchRules.adoptable(bench(Kind.FURNACE), idleHolding()));
        assertTrue(WorkbenchRules.adoptable(bench(Kind.SMOKER), idleHolding()));
    }

    @Test
    public void aTableHoldsNothingAndIsNeverAdopted() {
        assertFalse(WorkbenchRules.adoptable(table(), idleHolding()));
    }

    @Test
    public void aStationWithAJobIsNotAdoptedAgain() {
        Seen s = new Seen();
        s.holdsStuff = true;
        s.jobHere = true;
        assertFalse(WorkbenchRules.adoptable(bench(Kind.FURNACE), s.look()));
    }

    @Test
    public void anEmptyStationIsNotAdopted() {
        assertFalse(WorkbenchRules.adoptable(bench(Kind.FURNACE), new Seen().look()));
    }

    // a load in flight records its own job a tick later, adopting under it would be a second job for the same spot
    @Test
    public void nothingIsAdoptedWhileAScreenOrALoadIsUsingIt() {
        Seen s = new Seen();
        s.holdsStuff = true;
        s.idle = false;
        assertFalse(WorkbenchRules.adoptable(bench(Kind.SMOKER), s.look()));
    }

    @Test
    public void justPlacedOrJustUsedIsGivenAMoment() {
        Seen s = new Seen();
        s.holdsStuff = true;
        s.now = 100;
        Bench fresh = new Bench(Kind.FURNACE, pos(1, 64, 1), OVERWORLD, 100 - WorkbenchRules.PLACE_GUARD_TICKS + 1);
        assertFalse(WorkbenchRules.adoptable(fresh, s.look()));
        Bench used = bench(Kind.FURNACE);
        used.lastUsedTick = s.now - WorkbenchRules.SETTLE_TICKS + 1;
        assertFalse(WorkbenchRules.adoptable(used, s.look()));
        used.lastUsedTick = s.now - WorkbenchRules.SETTLE_TICKS;
        assertTrue(WorkbenchRules.adoptable(used, s.look()));
    }

    @Test
    public void nothingComingDownOrGoneOrInAnotherDimensionIsAdopted() {
        Seen s = new Seen();
        s.holdsStuff = true;
        assertFalse(WorkbenchRules.adoptable(pickingUp(Kind.FURNACE, 900), s.look()));
        s.blockGone = true;
        assertFalse(WorkbenchRules.adoptable(bench(Kind.FURNACE), s.look()));
        s.blockGone = false;
        s.sameDimension = false;
        assertFalse(WorkbenchRules.adoptable(bench(Kind.FURNACE), s.look()));
    }

    // ---- round 2: the furnace and smoker band

    // inward only: the planner may say "not held" where the container task would still walk to ours, never "held" where the task
    // would make a second one (the plan did not budget the stone or the logs for it)
    @Test
    public void theBandOnlyReachesInsideTheLine() {
        // before the first look the plain line is the only fair test
        assertEquals(21.0, WorkbenchRules.bandRadius(null), 0);
        assertEquals(21.0, WorkbenchRules.bandRadius(true), 0);
        assertEquals(20.0, WorkbenchRules.bandRadius(false), 0);
        assertTrue(WorkbenchRules.bandRadius(true) <= WorkbenchRules.NEAR);
        assertTrue(WorkbenchRules.bandRadius(false) <= WorkbenchRules.NEAR);
    }

    // walk a path across the line and ask what the planner would say at each step, the way MinecraftFacts feeds it back
    private static boolean[] walk(double[] distances, Boolean start) {
        boolean[] out = new boolean[distances.length];
        Boolean held = start;
        for (int i = 0; i < distances.length; i++) {
            out[i] = distances[i] <= WorkbenchRules.bandRadius(held);
            held = out[i];
        }
        return out;
    }

    @Test
    public void aSmokerTwentyBlocksAwayIsASmokerWhicheverWayWeGotThere() {
        // never left: held the whole way in
        assertTrue(walk(new double[]{20}, true)[0]);
        // came in from outside: 20 is the edge of the band and counts
        assertTrue(walk(new double[]{30, 25, 22, 21, 20}, null)[4]);
        // first look right at 20.5 uses the plain line
        assertFalse(walk(new double[]{21.5}, null)[0]);
        assertTrue(walk(new double[]{20.5}, null)[0]);
    }

    // a bot mining at the edge: held while inside, one flip when it crosses the line, then no flip back until it is a block in
    @Test
    public void crossingTheLineBackAndForthFlipsOnceNotEveryStep() {
        double[] dither = {20.6, 21.4, 20.8, 21.2, 20.9, 21.5, 20.7};
        boolean[] latched = walk(dither, true);
        int flips = 0;
        for (int i = 1; i < latched.length; i++) {
            if (latched[i] != latched[i - 1]) {
                flips++;
            }
        }
        assertEquals(1, flips);
        assertTrue(latched[0]);
        assertFalse(latched[1]);
        assertFalse(latched[6]);
        // the plain line flipped on every crossing
        int plain = 0;
        for (int i = 1; i < dither.length; i++) {
            if ((dither[i] <= WorkbenchRules.NEAR) != (dither[i - 1] <= WorkbenchRules.NEAR)) {
                plain++;
            }
        }
        assertEquals(6, plain);
        // and a block inside it is back
        assertTrue(walk(new double[]{21.5, 20.0}, true)[1]);
        assertFalse(walk(new double[]{21.5, 20.01}, true)[1]);
    }

    // the planner flag must never be true where the container task would make a second one: StationChoice takes ours within NEAR,
    // so held <= NEAR for every walk, in and out, whatever the start
    @Test
    public void theFlagIsNeverTrueWhereTheTaskWouldNotWalkToOurs() {
        // a triangle wave between 10 and 30 blocks, a quarter block a step, a few laps
        double[] path = new double[640];
        for (int i = 0; i < path.length; i++) {
            int phase = i % 160;
            path[i] = 10 + (phase < 80 ? phase : 160 - phase) * 0.25;
        }
        for (Boolean start : new Boolean[]{null, true, false}) {
            boolean[] held = walk(path, start);
            for (int i = 0; i < path.length; i++) {
                if (held[i]) {
                    assertTrue("held at " + path[i], path[i] <= WorkbenchRules.NEAR);
                }
            }
        }
    }

    @Test
    public void theBandIsNarrowerThanTheTablesLatch() {
        assertTrue(WorkbenchRules.bandRadius(false) > WorkbenchRules.returnRadius(true));
        assertEquals(1.0, WorkbenchRules.LATCH_BAND, 0);
    }

    // ---- round 2: review fixes

    // a table pickup that is under way (nothing broken yet) and out past the far line, the bag now holding wood: forget it
    @Test
    public void aTablePickupUnderWayPastTheFarLineIsCalledOffWhenTheBagCanRemake() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.now = 1030;
        s.distance = 70;
        s.canRecraft = true;
        assertEquals(Call.FORGET_FAR_TABLE, decide(b, s));
        // nothing to remake it from: it keeps walking
        s.canRecraft = false;
        assertEquals(Call.CONTINUE, decide(b, s));
        // the block already down: only the drop is left, which is right there
        s.canRecraft = true;
        WorkbenchRules.blockBroken(b, 1030);
        assertEquals(Call.CONTINUE, decide(b, s));
        // and a furnace coming down keeps going from as far as it was
        Bench furnace = pickingUp(Kind.FURNACE, 1000);
        assertEquals(Call.CONTINUE, decide(furnace, s));
    }

    // dropped as stale: what the container tracker still remembers in it is not adopted back into a new job
    @Test
    public void aLoadGivenUpOnIsNotAdoptedOrHeldForByThePhase() {
        Bench b = bench(Kind.FURNACE);
        b.state = Bench.State.BUSY;
        b.givenUp = true;
        assertFalse(WorkbenchRules.adoptable(b, idleHolding()));
        assertTrue(WorkbenchRules.phaseMayEnd(List.of(b), OVERWORLD, x -> false));
        // seen empty: over, and a later interrupted load is a new story
        Seen s = new Seen();
        decide(b, s);
        assertFalse(b.givenUp);
        assertTrue(WorkbenchRules.adoptable(b, idleHolding()));
    }

    @Test
    public void aRealJobAtTheStationClearsTheGiveUp() {
        Bench b = bench(Kind.SMOKER);
        b.givenUp = true;
        Seen s = new Seen();
        s.holdsStuff = true;
        s.jobHere = true;
        assertEquals(Call.BUSY, decide(b, s));
        assertFalse(b.givenUp);
        // holding something with no job keeps it
        Bench c = bench(Kind.SMOKER);
        c.givenUp = true;
        Seen t = new Seen();
        t.holdsStuff = true;
        assertEquals(Call.BUSY, decide(c, t));
        assertTrue(c.givenUp);
    }

    // the phase lets these go on purpose, and the world half names each one in the log: busy, given up, no job, this dimension
    @Test
    public void leftGivenUpIsExactlyWhatThePhaseLetsGo() {
        Bench left = bench(Kind.FURNACE);
        left.state = Bench.State.BUSY;
        left.givenUp = true;
        Bench interrupted = bench(Kind.SMOKER);
        interrupted.state = Bench.State.BUSY;
        Bench withJob = bench(Kind.FURNACE);
        withJob.state = Bench.State.BUSY;
        withJob.givenUp = true;
        Bench nether = in(Kind.FURNACE, "NETHER", Bench.State.BUSY);
        nether.givenUp = true;
        List<Bench> all = List.of(left, interrupted, withJob, nether);
        assertEquals(List.of(left), WorkbenchRules.leftGivenUp(all, OVERWORLD, b -> b == withJob));
        assertEquals(List.of(), WorkbenchRules.leftGivenUp(List.of(bench(Kind.FURNACE)), OVERWORLD, b -> false));
    }

    // the log line is once per give up: clearing it lets the next give up say so again
    @Test
    public void clearingTheGiveUpClearsItsLogLine() {
        Bench b = bench(Kind.FURNACE);
        b.givenUp = true;
        b.givenUpLogged = true;
        decide(b, new Seen());
        assertFalse(b.givenUp);
        assertFalse(b.givenUpLogged);
    }
}
