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
