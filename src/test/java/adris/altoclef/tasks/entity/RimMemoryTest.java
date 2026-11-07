package adris.altoclef.tasks.entity;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RimMemoryTest {
    @Test
    public void aFreshTaskOnTheSameMobKeepsWhatTheLastOneLearned() {
        RimMemory.Store store = new RimMemory.Store();
        RimMemory fight = store.get(7, 100);
        fight.failed.add(new BlockPos(1, 2, 3));
        fight.groundTicks = 60;
        // the pickup in between is a few seconds, the loot kill asks for the same mob
        RimMemory loot = store.get(7, 160);
        assertSame(fight, loot);
        assertTrue(loot.failed.contains(new BlockPos(1, 2, 3)));
        assertEquals(60, loot.groundTicks);
    }

    @Test
    public void anotherMobStartsClean() {
        RimMemory.Store store = new RimMemory.Store();
        store.get(7, 100).failed.add(new BlockPos(1, 2, 3));
        assertTrue(store.get(8, 100).failed.isEmpty());
    }

    @Test
    public void aLongGapIsADifferentFight() {
        RimMemory.Store store = new RimMemory.Store();
        store.get(7, 100).groundTicks = 50;
        assertEquals(50, store.get(7, 100 + RimMemory.GAP_TICKS).groundTicks);
        assertEquals(0, store.get(7, 100 + 2 * RimMemory.GAP_TICKS + 1).groundTicks);
    }

    @Test
    public void aClockGoingBackwardsForgets() {
        RimMemory.Store store = new RimMemory.Store();
        store.get(7, 1000).groundTicks = 50;
        // respawn: tickCount starts over and the ids belong to other mobs now
        assertEquals(0, store.get(7, 5).groundTicks);
    }

    @Test
    public void oldFightsDoNotPileUp() {
        RimMemory.Store store = new RimMemory.Store();
        for (int id = 0; id <= RimMemory.MAX_MOBS + 1; id++) {
            store.get(id, 10);
        }
        assertFalse(store.size() > RimMemory.MAX_MOBS + 1);
    }
}
