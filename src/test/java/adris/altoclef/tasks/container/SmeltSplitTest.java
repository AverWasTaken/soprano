package adris.altoclef.tasks.container;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.After;
import org.junit.Test;

public class SmeltSplitTest {
    @After
    public void forget() {
        SmeltSplit.clear();
    }

    @Test
    public void smallBatchesStayInOneFurnace() {
        assertEquals(1, SmeltSplit.wanted(0));
        assertEquals(1, SmeltSplit.wanted(3));
        assertEquals(1, SmeltSplit.wanted(5));
        assertEquals(1, SmeltSplit.wanted(15));
        assertEquals(1, SmeltSplit.loads(15, 3, 3, 64));
    }

    @Test
    public void sixteenIsTwoAndThirtySevenIsThree() {
        assertEquals(2, SmeltSplit.wanted(16));
        assertEquals(2, SmeltSplit.wanted(26));
        assertEquals(3, SmeltSplit.wanted(27));
        assertEquals(3, SmeltSplit.wanted(37));
        // never a fourth
        assertEquals(3, SmeltSplit.wanted(200));
        assertEquals(2, SmeltSplit.loads(16, 0, 0, 64));
        assertEquals(3, SmeltSplit.loads(37, 0, 0, 64));
        assertArrayEquals(new int[]{13, 12, 12}, SmeltSplit.sizes(37, 3));
        assertArrayEquals(new int[]{8, 8}, SmeltSplit.sizes(16, 2));
        assertArrayEquals(new int[]{14, 13}, SmeltSplit.sizes(27, 2));
        assertEquals("13/12/12", SmeltSplit.words(SmeltSplit.sizes(37, 3)));
    }

    // every furnace we have to make is 8 cobble the tools are not owed, short cobble is fewer loads, never a cobble trip
    @Test
    public void cobbleLimitsTheLoads() {
        assertEquals(1, SmeltSplit.loads(37, 0, 0, 7));
        assertEquals(1, SmeltSplit.loads(37, 0, 0, 8));
        assertEquals(2, SmeltSplit.loads(37, 0, 0, 16));
        assertEquals(2, SmeltSplit.loads(37, 0, 0, 23));
        assertEquals(3, SmeltSplit.loads(37, 0, 0, 24));
        // owed cobble comes off before it gets here, a negative spare is no furnace
        assertEquals(1, SmeltSplit.loads(37, 0, 0, -5));
    }

    // idle ones of ours and the bag's are free, the cobble only pays for the rest
    @Test
    public void idleFurnacesAndTheBagGoFirst() {
        assertEquals(3, SmeltSplit.loads(37, 3, 0, 0));
        assertEquals(3, SmeltSplit.loads(37, 1, 2, 0));
        assertEquals(2, SmeltSplit.loads(37, 2, 0, 0));
        assertEquals(3, SmeltSplit.loads(37, 2, 0, 8));
        assertEquals(3, SmeltSplit.loads(37, 1, 0, 16));
        assertEquals(2, SmeltSplit.loads(37, 1, 0, 15));
        // more than we want is still three
        assertEquals(3, SmeltSplit.loads(37, 5, 4, 64));
        assertEquals(2, SmeltSplit.loads(20, 5, 4, 64));
    }

    // the planner's half: the first furnace is the kit's own, only the ones past it are 8 cobble each
    @Test
    public void extraToCraftLeavesTheFirstToTheKit() {
        assertEquals(2, SmeltSplit.extraToCraft(3, 0));
        assertEquals(2, SmeltSplit.extraToCraft(3, 1));
        assertEquals(1, SmeltSplit.extraToCraft(3, 2));
        assertEquals(0, SmeltSplit.extraToCraft(3, 3));
        assertEquals(0, SmeltSplit.extraToCraft(3, 7));
        assertEquals(0, SmeltSplit.extraToCraft(1, 0));
        assertEquals(1, SmeltSplit.extraToCraft(2, 0));
    }

    @Test
    public void theLastLoadTakesWhatIsStillOwed() {
        int[] sizes = SmeltSplit.sizes(37, 3);
        assertEquals(13, SmeltSplit.nextLoad(sizes, 0, 37, 37, false));
        assertEquals(12, SmeltSplit.nextLoad(sizes, 1, 24, 24, false));
        assertEquals(12, SmeltSplit.nextLoad(sizes, 2, 12, 12, false));
        // a visit took output early and the count drifted: the last one sweeps it up, no fourth load
        assertEquals(14, SmeltSplit.nextLoad(sizes, 2, 14, 20, false));
        // a middle one never takes more than its share, nor more than is owed
        assertEquals(12, SmeltSplit.nextLoad(sizes, 1, 30, 30, false));
        assertEquals(5, SmeltSplit.nextLoad(sizes, 1, 5, 30, false));
        // nothing owed, or every load handed out
        assertEquals(0, SmeltSplit.nextLoad(sizes, 1, 0, 30, false));
        assertEquals(0, SmeltSplit.nextLoad(sizes, 3, 9, 30, false));
    }

    // lava ate some ore: the load takes what the bag has, an empty bag is the end of the batch
    @Test
    public void aShortBagShrinksTheLoad() {
        int[] sizes = SmeltSplit.sizes(37, 3);
        assertEquals(9, SmeltSplit.nextLoad(sizes, 1, 24, 9, false));
        assertEquals(0, SmeltSplit.nextLoad(sizes, 1, 24, 0, false));
        // a load we come back to finish has half its ore in the furnace already, the bag is not the whole of it
        assertEquals(12, SmeltSplit.nextLoad(sizes, 1, 24, 4, true));
    }

    @Test
    public void theDecisionIsHeldUntilEveryLoadIsIn() {
        assertNull(SmeltSplit.held(37));
        SmeltSplit.Batch b = SmeltSplit.start(37, SmeltSplit.sizes(37, 3));
        assertSame(b, SmeltSplit.held(37));
        assertSame(b, SmeltSplit.active());
        // a different need count is a different batch
        assertNull(SmeltSplit.held(40));
        assertTrue(b.splitting());
        assertEquals(3, b.loadsLeft());
        // half loaded and cut off: the fresh load task finishes it at its size, whatever the bag still holds
        b.loadingAt(new BlockPos(1, 64, 1));
        assertEquals(13, b.nextLoad(37, 3));
        b.loaded(0);
        assertNull(b.loadingAt());
        assertEquals(2, b.loadsLeft());
        // a second word about the same load, or a late one about an older load, moves nothing
        b.loaded(0);
        assertEquals(1, b.issued());
        assertEquals(12, b.nextLoad(24, 24));
        b.loaded(1);
        b.loaded(0);
        assertEquals(2, b.issued());
        // the last load closes the batch by itself, nobody has to tick the parent after it (the planner drops the need that tick)
        b.loaded(2);
        assertTrue(b.over());
        assertEquals(0, b.loadsLeft());
        assertEquals(0, b.nextLoad(5, 5));
        assertNull(SmeltSplit.held(37));
        assertNull(SmeltSplit.active());
    }

    @Test
    public void anEndedBatchLetsGo() {
        SmeltSplit.Batch b = SmeltSplit.start(37, SmeltSplit.sizes(37, 3));
        b.loaded(0);
        b.end();
        assertTrue(b.over());
        assertEquals(0, b.loadsLeft());
        assertNull(SmeltSplit.held(37));
    }

    @Test
    public void aNewRunForgetsTheBatch() {
        SmeltSplit.start(37, SmeltSplit.sizes(37, 3));
        AsyncSmelting.clear();
        assertNull(SmeltSplit.active());
    }

    // ---- the loads as tasks

    // 13/12/12: loads 2 and 3 have the same target and the same cap. if they were equal the task system would keep ticking the
    // finished load 2 and load 3 never ran
    @Test
    public void twoLoadsWithTheSameCapAreNotTheSameTask() {
        // (a task can't be built without a game, the equality is this rule)
        SmeltSplit.Batch b = SmeltSplit.start(37, SmeltSplit.sizes(37, 3));
        assertFalse(SmeltInFurnaceTask.DoSmeltInFurnaceTask.sameLoad(b, 1, 12, b, 2, 12));
        assertTrue(SmeltInFurnaceTask.DoSmeltInFurnaceTask.sameLoad(b, 1, 12, b, 1, 12));
        // and a split load is never the plain smelt of the same target
        assertFalse(SmeltInFurnaceTask.DoSmeltInFurnaceTask.sameLoad(b, 1, 12, null, -1, Integer.MAX_VALUE));
        assertTrue(SmeltInFurnaceTask.DoSmeltInFurnaceTask.sameLoad(null, -1, Integer.MAX_VALUE, null, -1, Integer.MAX_VALUE));
    }

    // what goes in: the target less the bag, the output slot and every OTHER furnace's job, at most one load
    @Test
    public void owedCountsEveryFurnaceOnce() {
        // 37 cooking in the one we are topping up (its slots are read, its job is left out), 40 owed: 3 more
        assertEquals(3, SmeltInFurnaceTask.DoSmeltInFurnaceTask.owed(40, 0, 0, 0, Integer.MAX_VALUE) - 37);
        // three furnaces 13/12/12, topping up the 13 one: the other two are 24 elsewhere, 3 more
        assertEquals(3, SmeltInFurnaceTask.DoSmeltInFurnaceTask.owed(40, 24, 0, 0, Integer.MAX_VALUE) - 13);
        // load 2 of 37 with 13 cooking in load 1's: 24 owed, capped at its 12
        assertEquals(12, SmeltInFurnaceTask.DoSmeltInFurnaceTask.owed(37, 13, 0, 0, 12));
        // the last load sees what is left, ingots in the bag and the output slot included
        assertEquals(9, SmeltInFurnaceTask.DoSmeltInFurnaceTask.owed(37, 25, 2, 1, 12));
        // plain alto: no jobs, no cap, the old sum
        assertEquals(30, SmeltInFurnaceTask.DoSmeltInFurnaceTask.owed(37, 0, 5, 2, Integer.MAX_VALUE));
    }

    // ---- the pin

    // load 2 is never pinned to the nearest loaded furnace (that is load 1, cooking), only to its own or the one it half loaded
    @Test
    public void aSplitLoadIsNotPinnedToTheLoadedOne() {
        String loadOne = "load 1's furnace";
        assertNull(SmeltInFurnaceTask.DoSmeltInFurnaceTask.pin(null, false, true, null, false, () -> loadOne));
        // the plain smelt still finishes a cut off load wherever it is (StationMemory.ourLoaded)
        assertEquals(loadOne, SmeltInFurnaceTask.DoSmeltInFurnaceTask.pin(null, false, false, null, false, () -> loadOne));
        // its own furnace, once it put things in, stays its own
        assertEquals("mine", SmeltInFurnaceTask.DoSmeltInFurnaceTask.pin("mine", true, true, null, false, () -> loadOne));
        // the half load an interrupted load task left is finished there
        assertEquals("half", SmeltInFurnaceTask.DoSmeltInFurnaceTask.pin(null, false, true, "half", false, () -> loadOne));
        // unless it went in after all and is cooking: busy
        assertNull(SmeltInFurnaceTask.DoSmeltInFurnaceTask.pin(null, false, true, "half", true, () -> loadOne));
    }
}
