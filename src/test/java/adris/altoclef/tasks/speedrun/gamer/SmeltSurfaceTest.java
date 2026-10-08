package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.container.CollectFromFurnaceTask;
import org.junit.Test;

// when to carry the raw iron up out of the mine before the furnace goes down, and how long to stand by it
public class SmeltSurfaceTest {
    @Test
    public void theOreIsDoneWhenTheBagCoversTheIronNeed() {
        assertTrue(SmeltSurface.oreDone("iron_ingot", 39, 39, 0, 0));
        // some already cooking or already smelted counts
        assertTrue(SmeltSurface.oreDone("iron_ingot", 39, 20, 10, 9));
        assertFalse(SmeltSurface.oreDone("iron_ingot", 39, 30, 0, 0));
    }

    @Test
    public void nothingToSmeltOrAnotherNeedIsNotOurBusiness() {
        assertFalse(SmeltSurface.oreDone("iron_ingot", 0, 0, 5, 0));
        assertFalse(SmeltSurface.oreDone("wool", 3, 20, 0, 0));
        assertFalse(SmeltSurface.oreDone(null, 3, 20, 0, 0));
    }

    @Test
    public void aDitchIsNotAMineButAShaftIs() {
        assertFalse(SmeltSurface.wantsUp(false, 3));
        assertFalse(SmeltSurface.wantsUp(false, SmeltSurface.GO_UP_DEPTH));
        assertTrue(SmeltSurface.wantsUp(false, SmeltSurface.GO_UP_DEPTH + 1));
        // the run this came from: furnace at y 38 under a surface around 70
        assertTrue(SmeltSurface.wantsUp(false, SmeltSurface.depth(70, 38)));
    }

    @Test
    public void onceClimbingItKeepsGoingUntilNearlyOut() {
        assertTrue(SmeltSurface.wantsUp(true, 5));
        assertTrue(SmeltSurface.wantsUp(true, SmeltSurface.ARRIVED_DEPTH + 1));
        assertFalse(SmeltSurface.wantsUp(true, SmeltSurface.ARRIVED_DEPTH));
        // and standing on top is depth 0, never negative trouble
        assertFalse(SmeltSurface.wantsUp(false, SmeltSurface.depth(64, 64)));
        assertFalse(SmeltSurface.wantsUp(false, SmeltSurface.depth(64, 70)));
    }

    private static KitNeed cook() {
        return new KitNeed(KitNeed.COOK_SMOKER, CookGate.MIN_RAW);
    }

    private static KitNeed iron(int n) {
        return new KitNeed("iron_ingot", n);
    }

    // 22:08: smoker at y 32, hunting on the surface, 40 s of walking back down. cooking with nothing underground to do next
    // goes up first, same as the smelt always did
    @Test
    public void aCookWithNothingToMineAfterItRidesUpFirst() {
        // the cook is the last thing in the plan
        assertEquals(SmeltSurface.Why.COOK, SmeltSurface.why(cook(), null, 0, 0, 0, 7));
        // and so is one followed by a craft or a filler, none of that is down here
        assertEquals(SmeltSurface.Why.COOK, SmeltSurface.why(cook(), new KitNeed("iron_pickaxe", 1), 0, 0, 0, 7));
        assertEquals(SmeltSurface.Why.COOK, SmeltSurface.why(cook(), new KitNeed(KitNeed.FOOD, 70), 0, 0, 0, 7));
    }

    @Test
    public void aCookInFrontOfMoreMiningStaysDownWhereTheWorkIs() {
        // 14 of 39 ore in the bag: the vein is next, the furnace would be right there when we come back for it
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.why(cook(), iron(39), 14, 0, 0, 7));
        assertTrue(SmeltSurface.nextWorkDown(iron(39), 14, 0, 0));
        // all the ore already in the bag: the next work is a smelt, and that goes up
        assertEquals(SmeltSurface.Why.COOK, SmeltSurface.why(cook(), iron(39), 39, 0, 0, 7));
        assertFalse(SmeltSurface.nextWorkDown(iron(39), 39, 0, 0));
        assertFalse(SmeltSurface.nextWorkDown(null, 0, 0, 0));
    }

    @Test
    public void noMeatNoCookClimb() {
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.why(cook(), null, 0, 0, 0, 0));
        assertFalse(SmeltSurface.cookDone(iron(39), 7));
        assertFalse(SmeltSurface.cookDone(null, 7));
        assertTrue(SmeltSurface.cookDone(new KitNeed(KitNeed.COOK_FURNACE, 3), 3));
    }

    @Test
    public void theIronSmeltStillWinsOverACookBehindIt() {
        assertEquals(SmeltSurface.Why.IRON, SmeltSurface.why(iron(39), cook(), 39, 0, 0, 7));
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.why(iron(39), cook(), 12, 0, 0, 7));
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.why(null, null, 0, 0, 0, 0));
    }

    @Test
    public void waitingByTheFurnaceReopensForTheNextOutputOrTheTimer() {
        // an item due in 3 s: look then (plus a hair)
        assertEquals(70, CollectFromFurnaceTask.idleTicks(60));
        // nothing due for a minute: the 10 s timer
        assertEquals(200, CollectFromFurnaceTask.idleTicks(1200));
        // due right now: not a flicker, a second at least
        assertEquals(20, CollectFromFurnaceTask.idleTicks(0));
        assertEquals(20, CollectFromFurnaceTask.idleTicks(-50));
    }
}
