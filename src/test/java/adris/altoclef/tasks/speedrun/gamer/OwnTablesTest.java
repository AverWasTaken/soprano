package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// the bot took a crafting table out of a village because the pickup was "nearest table". now only what we placed is a candidate
public class OwnTablesTest {
    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    @Test
    public void aVillageTableIsNeverACandidateBecauseItWasNeverRecorded() {
        List<RunState.Pos> own = new ArrayList<>();
        // the village table sits right next to us, we have placed nothing
        assertNull(OwnTables.nearest(own, p -> true, 247.5, 63, 39.5, 10));
        // and when we did place one further away, that one is the answer even though the village one is closer
        OwnTables.record(own, pos(260, 63, 40));
        assertEquals(pos(260, 63, 40), OwnTables.nearest(own, p -> true, 247.5, 63, 39.5, 20));
    }

    @Test
    public void nearestOwnTableWins() {
        List<RunState.Pos> own = new ArrayList<>(List.of(pos(10, 64, 0), pos(3, 64, 0), pos(-6, 64, 0)));
        assertEquals(pos(3, 64, 0), OwnTables.nearest(own, p -> true, 0.5, 64, 0.5, 10));
    }

    @Test
    public void usableFilterAndRadiusApply() {
        List<RunState.Pos> own = new ArrayList<>(List.of(pos(3, 64, 0), pos(8, 64, 0)));
        // the close one is written off (already tried), the far one is next
        assertEquals(pos(8, 64, 0), OwnTables.nearest(own, p -> p.x != 3, 0.5, 64, 0.5, 10));
        // nothing within the radius
        assertNull(OwnTables.nearest(own, p -> true, 0.5, 64, 0.5, 2));
        // everything filtered
        assertNull(OwnTables.nearest(own, p -> false, 0.5, 64, 0.5, 10));
    }

    @Test
    public void placementsOutsideReachAreNotOurs() {
        assertTrue(OwnTables.placedByUs(0.5, 65.6, 0.5, pos(2, 64, 1)));
        assertTrue(OwnTables.placedByUs(0.5, 65.6, 0.5, pos(7, 65, 0)));
        // a table that appears across the street (another player, a chunk update) is not something we placed
        assertFalse(OwnTables.placedByUs(0.5, 65.6, 0.5, pos(20, 64, 0)));
        assertFalse(OwnTables.placedByUs(0.5, 65.6, 0.5, pos(0, 64, -30)));
    }

    // use debounce 3 s, place guard 1 s, recover cooldown 120 s, the config defaults
    private static OwnTables.Start start(long now, long lastUse, long lastPlace, long lastRecovered, boolean menuOpen, boolean boundary) {
        return OwnTables.startRecovery(now, lastUse, lastPlace, lastRecovered, menuOpen, boundary, 3, OwnTables.PLACE_GUARD_SECONDS, 120);
    }

    // the loop from the log: place table, pickup breaks it, craft needs it, place again, ~every 6 seconds
    @Test
    public void aTableThatWasJustPlacedOrOpenedIsNotTakenBack() {
        long never = OwnTables.NEVER;
        assertEquals(OwnTables.Start.GO, start(1000, never, never, never, false, true));
        // placed half a second ago: the craft is about to happen, even at a boundary
        assertEquals(OwnTables.Start.HOLD, start(1000, never, 990, never, false, true));
        assertEquals(OwnTables.Start.GO, start(1000, never, 980, never, false, true));
        // away from a boundary the old use debounce still holds
        assertEquals(OwnTables.Start.HOLD, start(1000, 960, never, never, false, false));
        assertEquals(OwnTables.Start.GO, start(1000, 940, never, never, false, false));
        // open right now, however long ago anything else happened
        assertEquals(OwnTables.Start.NO, start(1000, never, never, never, true, true));
        assertEquals(OwnTables.Start.NO, start(100000, 0, 0, never, true, true));
    }

    // the log: furnace craft closed its menu at 13:37:15, the food need took over and a 3 s debounce ran out under a pig
    @Test
    public void aBoundaryDoesNotWaitOnTheLastUse() {
        long never = OwnTables.NEVER;
        // the menu closed this very tick, boundary says go
        assertEquals(OwnTables.Start.GO, start(1000, 999, never, never, false, true));
        assertEquals(OwnTables.Start.GO, start(1000, 1000, never, never, false, true));
        // same stamp mid job (no boundary) is still a debounce
        assertEquals(OwnTables.Start.HOLD, start(1000, 999, never, never, false, false));
        // the boundary rule itself: food after a craft is one, the same craft again is not
        assertTrue(OwnTables.atNeedBoundary("furnace", KitNeed.FOOD));
        assertFalse(OwnTables.atNeedBoundary("furnace", "furnace"));
    }

    // a placement within a second still waits, and the wait is the hold task, not a null that lets the next need walk
    @Test
    public void aPlacementWithinASecondHoldsStillAndThenGoes() {
        long never = OwnTables.NEVER;
        long placed = 500;
        assertEquals(OwnTables.Start.HOLD, start(placed, never, placed, never, false, true));
        assertEquals(OwnTables.Start.HOLD, start(placed + 19, never, placed, never, false, true));
        // 20 ticks = 1 s, after that it cannot hold any more, so the hold always ends
        assertEquals(OwnTables.Start.GO, start(placed + 20, never, placed, never, false, true));
    }

    // only the short guards may hold. a menu that never closes or a two minute cooldown would be a hang
    @Test
    public void onlyShortGuardsHold() {
        long never = OwnTables.NEVER;
        assertEquals(OwnTables.Start.NO, start(5000, never, never, 4990, false, true));
        assertEquals(OwnTables.Start.NO, start(5000, never, 4990, 4990, false, true));
        assertEquals(OwnTables.Start.NO, start(5000, never, 4999, never, true, true));
    }

    @Test
    public void oneSuccessfulPickupBuysTwoMinutesOfPeace() {
        long never = OwnTables.NEVER;
        // use guards long over, but we took one back 60 seconds ago
        assertEquals(OwnTables.Start.NO, start(5000, 0, 0, 3800, false, true));
        assertEquals(OwnTables.Start.NO, start(5000, 0, 0, 2601, false, true));
        assertEquals(OwnTables.Start.GO, start(5000, 0, 0, 2600, false, true));
        // a use at tick 0 is a real use, not "never"
        assertEquals(OwnTables.Start.HOLD, start(10, 0, never, never, false, false));
    }

    @Test
    public void recordDedupesAndForgetsTheOldest() {
        List<RunState.Pos> own = new ArrayList<>();
        assertTrue(OwnTables.record(own, pos(1, 1, 1)));
        assertFalse(OwnTables.record(own, pos(1, 1, 1)));
        assertEquals(1, own.size());
        for (int i = 0; i < OwnTables.CAP + 5; i++) {
            OwnTables.record(own, pos(100 + i, 64, 0));
        }
        assertEquals(OwnTables.CAP, own.size());
        assertFalse(own.contains(pos(1, 1, 1)));
        assertTrue(own.contains(pos(100 + OwnTables.CAP + 4, 64, 0)));
    }

    // the bug: craft, walk off to iron, the 30 s cooldown ran out out of range and the table stayed. now the pickup is
    // about WHICH need is running, not how long ago we touched it
    @Test
    public void tableComesBackWhenTheRunMovesToAGatheringNeed() {
        // food placed it (smoker, bread), food is still running: keep
        assertFalse(OwnTables.wantsTableBack("food", "food"));
        // food is done, iron is next: that is the boundary
        assertTrue(OwnTables.wantsTableBack("food", "iron_ingot"));
        assertTrue(OwnTables.wantsTableBack("furnace", "food"));
        assertTrue(OwnTables.wantsTableBack("iron_pickaxe", "wool"));
        // the plan ran dry: nothing will use it again
        assertTrue(OwnTables.wantsTableBack("shears", null));
        // placed outside a prep phase or before a relog, no idea who used it: still fair game at the next gathering need
        assertTrue(OwnTables.wantsTableBack(null, "iron_ingot"));
    }

    @Test
    public void aCraftNeedKeepsTheTable() {
        // wooden pickaxe placed it, stone pickaxe is a craft at that same table
        assertFalse(OwnTables.wantsTableBack("wooden_pickaxe", "stone_pickaxe"));
        assertFalse(OwnTables.wantsTableBack("iron_ingot", "iron_pickaxe"));
        assertFalse(OwnTables.wantsTableBack(null, "bucket"));
        // armor going on and the special needs are not crafts
        assertTrue(OwnTables.wantsTableBack("iron_chestplate", KitNeed.EQUIP_ARMOR));
        assertTrue(OwnTables.wantsTableBack("iron_chestplate", KitNeed.BUILD_BLOCKS));
    }

    @Test
    public void furnaceFollowsTheSameBoundaryButCraftsDoNotKeepIt() {
        // cooked food, now crafts: the furnace is dead weight, crafts use the table
        assertTrue(OwnTables.wantsFurnaceBack("food", "iron_pickaxe", false));
        assertTrue(OwnTables.wantsFurnaceBack("iron_ingot", null, false));
        // the need that smelted in it is still running
        assertFalse(OwnTables.wantsFurnaceBack("iron_ingot", "iron_ingot", false));
        assertFalse(OwnTables.wantsFurnaceBack("food", "food", false));
    }

    // the walk budget (horizontal + 4 per block of height, 20), not a sphere
    @Test
    public void aTableAboveOrBelowIsFurtherThanItLooks() {
        // 10 blocks away sideways is fine, 10 straight down is the cave trip
        List<RunState.Pos> own = new ArrayList<>(List.of(pos(10, 64, 0)));
        assertEquals(pos(10, 64, 0), OwnTables.nearest(own, p -> true, 0.5, 64, 0.5, 20));
        List<RunState.Pos> below = new ArrayList<>(List.of(pos(0, 54, 0)));
        assertNull(OwnTables.nearest(below, p -> true, 0.5, 64, 0.5, 20));
        // 4 down is 16 of walking, still worth it
        List<RunState.Pos> close = new ArrayList<>(List.of(pos(0, 60, 0)));
        assertEquals(pos(0, 60, 0), OwnTables.nearest(close, p -> true, 0.5, 64, 0.5, 20));
    }

    @Test
    public void nearestPicksTheCheapestWalkNotTheShortestLine() {
        // 6 straight up is 24 of walking, 15 sideways is 15
        List<RunState.Pos> own = new ArrayList<>(List.of(pos(0, 70, 0), pos(15, 64, 0)));
        assertEquals(pos(15, 64, 0), OwnTables.nearest(own, p -> true, 0.5, 64, 0.5, 30));
    }

    // 22:12:04: the table pickup started between the raw iron and the coal, closed the screen on a furnace holding 37 raw iron
    @Test
    public void noPickupStartsWhileAFurnaceScreenIsOpenOrWasJustWorked() {
        long now = 5000;
        assertTrue(OwnTables.loadInFlight(true, -1, now));
        // screen shut for the tick between two clicks of the same load
        assertTrue(OwnTables.loadInFlight(false, now - 1, now));
        assertTrue(OwnTables.loadInFlight(false, now - OwnTables.LOAD_GRACE_TICKS, now));
        // a second later it is over, and it can never hold for ever
        assertFalse(OwnTables.loadInFlight(false, now - OwnTables.LOAD_GRACE_TICKS - 1, now));
        assertFalse(OwnTables.loadInFlight(false, -1, now));
        // a stamp from a world that ran further than this one is not a load
        assertFalse(OwnTables.loadInFlight(false, now + 500, now));
    }

    // 22:12:06: "forgot 1 furnace(s)" with 37 raw iron in it, 2 blocks up and the budget in the user's saved file was 10
    @Test
    public void aFurnaceWithOurStuffInItIsNotForgottenHoweverFar() {
        RunState.Pos loaded = pos(15, 34, -328);
        RunState.Pos empty = pos(80, 64, 0);
        List<RunState.Pos> own = new ArrayList<>(List.of(loaded, empty));
        // we stand 2 blocks under it and a few across, the old budget of 10 called that far
        assertTrue(OwnTables.walkCost(loaded, 15.5, 30, -325) > 10);
        int gone = OwnTables.forgetFar(own, p -> p.equals(loaded), 15.5, 30, -325, 10);
        assertEquals(1, gone);
        assertEquals(List.of(loaded), own);
        // and nothing picks the loaded one up either: the caller's usable filter says no
        assertNull(OwnTables.nearest(own, p -> !p.equals(loaded), 15.5, 30, -325, 100));
    }

    @Test
    public void aSavedSmallBudgetIsRaisedToTheWalkAFreshStationCosts() {
        assertEquals(20, OwnTables.pickupBudget(10), 0);
        assertEquals(20, OwnTables.pickupBudget(20), 0);
        assertEquals(30, OwnTables.pickupBudget(30), 0);
    }

    @Test
    public void tablesOutOfBudgetAreForgottenButJobsKeepTheirs() {
        List<RunState.Pos> own = new ArrayList<>(List.of(pos(2, 64, 0), pos(0, 30, 0), pos(60, 64, 0)));
        // the one at 60 belongs to a smelting job
        int gone = OwnTables.forgetFar(own, p -> p.x == 60, 0.5, 64, 0.5, 20);
        assertEquals(1, gone);
        assertEquals(List.of(pos(2, 64, 0), pos(60, 64, 0)), own);
        assertEquals(0, OwnTables.forgetFar(own, p -> false, 0.5, 64, 0.5, 100));
    }

    // the progress gate: no craft since it went down, no new-rule pickup
    @Test
    public void usedSincePlacedNeedsAMenuAfterThePlacement() {
        long never = OwnTables.NEVER;
        assertFalse(OwnTables.usedSincePlaced(never, never));
        assertFalse(OwnTables.usedSincePlaced(never, 500));
        // an old use from a table we already took back, then a fresh placement
        assertFalse(OwnTables.usedSincePlaced(300, 500));
        assertTrue(OwnTables.usedSincePlaced(510, 500));
        // placed before the stamps started (relog), opened since
        assertTrue(OwnTables.usedSincePlaced(10, never));
        // a use at tick 0 is a real use
        assertTrue(OwnTables.usedSincePlaced(0, never));
    }

    private static boolean finished(long now, long lastUse, long lastPlace, boolean open, boolean running, boolean nextCrafts) {
        return OwnTables.finishedCrafting(now, lastUse, lastPlace, open, running, nextCrafts);
    }

    @Test
    public void craftingIsFinishedOnceTheMenuStaysShutAndNothingCrafts() {
        // placed at 100, opened until 140, now 200 (3 s later): done
        assertTrue(finished(200, 140, 100, false, false, false));
        // a second after the menu closed is the minimum, 19 ticks is not enough (the gap between two crafts of one chain)
        assertFalse(finished(159, 140, 100, false, false, false));
        assertTrue(finished(160, 140, 100, false, false, false));
        // the menu is open, or a CraftInTableTask is anywhere in the tree, or the next need crafts: not done
        assertFalse(finished(200, 140, 100, true, false, false));
        assertFalse(finished(200, 140, 100, false, true, false));
        assertFalse(finished(200, 140, 100, false, false, true));
    }

    // 16:50 log: hoe crafted under the food need, table picked up 2 s later, the bread 15 s after that placed another one
    @Test
    public void aCraftStillAheadHoldsTheTableForAWhile() {
        // placed at 100, last open at 140. the plain rule says done from tick 160
        assertTrue(OwnTables.finishedCrafting(200, 140, 100, false, false, false, false));
        // with a craft ahead it is not, not at 2 s and not at 15 s
        assertFalse(OwnTables.finishedCrafting(180, 140, 100, false, false, false, true));
        assertFalse(OwnTables.finishedCrafting(140 + 300, 140, 100, false, false, false, true));
        // but not forever either, the table is a log and the walk back gets longer
        long holdTicks = (long) (OwnTables.AHEAD_HOLD_SECONDS * 20);
        assertFalse(OwnTables.finishedCrafting(140 + holdTicks - 1, 140, 100, false, false, false, true));
        assertTrue(OwnTables.finishedCrafting(140 + holdTicks, 140, 100, false, false, false, true));
        // every old guard still applies on top (menu open, craft running, a table nobody used)
        assertFalse(OwnTables.finishedCrafting(5000, 140, 100, true, false, false, false));
        assertFalse(OwnTables.finishedCrafting(5000, 140, 100, false, true, false, false));
        assertFalse(OwnTables.finishedCrafting(5000, OwnTables.NEVER, 100, false, false, false, false));
    }

    @Test
    public void theFoodNeedCraftsInsideItself() {
        assertTrue(OwnTables.needCraftsInside("food"));
        assertFalse(OwnTables.needCraftsInside("iron_ingot"));
        assertFalse(OwnTables.needCraftsInside("stone_pickaxe"));
        assertFalse(OwnTables.needCraftsInside(null));
    }

    // the loop from the log: placed, picked up, the craft placed it again. with no craft in between this rule never fires
    @Test
    public void aTableNobodyUsedIsNotFinishedWith() {
        assertFalse(finished(5000, OwnTables.NEVER, 100, false, false, false));
        assertFalse(finished(5000, 50, 100, false, false, false));
    }

    @Test
    public void bothReasonsTakeTheTableBack() {
        // food is still the running need (no boundary) but the bread is done
        assertFalse(OwnTables.wantsTableNow("food", "food", false, true, false));
        assertTrue(OwnTables.wantsTableNow("food", "food", true, true, false));
        // the old boundary rule is still the fallback for a table that was never opened
        assertTrue(OwnTables.wantsTableNow("food", "iron_ingot", false, false, false));
        // a craft need next keeps it either way, finished or not is the caller's call (nextNeedCrafts above)
        assertFalse(OwnTables.wantsTableNow("wooden_pickaxe", "stone_pickaxe", false, true, true));
    }

    // the log: table placed for the wooden axe, the planner wanted logs before the menu ever opened (the table had left the
    // bag), the boundary fallback read that as "moved on" and the table came back up and went down again
    @Test
    public void anUnusedTableStaysWhileACraftIsStillPlanned() {
        // used-by is the axe, the head need flipped to log, the axe craft is still in the plan: stays
        assertFalse(OwnTables.wantsTableNow("wooden_axe", "log", false, false, true));
        // nothing crafts any more and it never got opened: the old fallback applies, take it
        assertTrue(OwnTables.wantsTableNow("wooden_axe", "log", false, false, false));
        // it was used, a craft later in the plan does not keep it (the next craft places it again, same as before)
        assertTrue(OwnTables.wantsTableNow("wooden_axe", "log", false, true, true));
        // finished crafting still wins, whatever the plan says
        assertTrue(OwnTables.wantsTableNow("wooden_axe", "log", true, true, true));
    }

    // the floor is a few seconds now (config 5), two crafts landing close together no longer leave a table behind
    @Test
    public void theRecoverFloorIsOnlyAFewSeconds() {
        long never = OwnTables.NEVER;
        assertEquals(OwnTables.Start.NO, OwnTables.startRecovery(5000, never, never, 4950, false, true, 3, 1, 5));
        assertEquals(OwnTables.Start.GO, OwnTables.startRecovery(5000, never, never, 4900, false, true, 3, 1, 5));
    }

    @Test
    public void aFurnaceAboutToSmeltIsKept() {
        assertTrue(OwnTables.smeltsSoon("iron_ingot", 3));
        // iron need but nothing raw yet: we are going mining, carry it
        assertFalse(OwnTables.smeltsSoon("iron_ingot", 0));
        assertFalse(OwnTables.smeltsSoon("food", 3));
        assertFalse(OwnTables.smeltsSoon(null, 3));
        assertFalse(OwnTables.wantsFurnaceBack("food", "iron_ingot", OwnTables.smeltsSoon("iron_ingot", 5)));
        assertTrue(OwnTables.wantsFurnaceBack("food", "iron_ingot", OwnTables.smeltsSoon("iron_ingot", 0)));
    }

    // the debounce is a few seconds now, not the 30 that let the bot wander out of range
    @Test
    public void aFewSecondsOfDebounceAfterUse() {
        long never = OwnTables.NEVER;
        assertEquals(OwnTables.Start.HOLD, start(1000, 960, never, never, false, false));
        assertEquals(OwnTables.Start.GO, start(1000, 940, never, never, false, false));
        // but the loop backstop is untouched
        assertEquals(OwnTables.Start.NO, start(5000, 0, never, 2601, false, false));
        assertEquals(OwnTables.Start.GO, start(5000, 0, never, 2600, false, false));
    }

    @Test
    public void craftAndGatheringNamesAddUp() {
        assertTrue(KitNeed.isCraftName("furnace"));
        assertTrue(KitNeed.isCraftName("shield"));
        assertFalse(KitNeed.isCraftName("iron_ingot"));
        assertFalse(KitNeed.isCraftName("wool"));
        assertFalse(KitNeed.isCraftName(KitNeed.FOOD));
        assertFalse(KitNeed.isCraftName(KitNeed.EQUIP_ARMOR));
        assertFalse(KitNeed.isCraftName(null));
        assertTrue(new KitNeed("bucket", 2).isCraft());
    }
}
