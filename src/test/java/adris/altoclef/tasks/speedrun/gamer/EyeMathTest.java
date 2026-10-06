package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EyeMathTest {
    private final GamerConfig cfg = new GamerConfig();

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static FakeFacts bag(int rods, int powder, int pearls, int eyes) {
        FakeFacts f = new FakeFacts();
        f.give(Items.BLAZE_ROD, rods).give(Items.BLAZE_POWDER, powder).give(Items.ENDER_PEARL, pearls).give(Items.ENDER_EYE, eyes);
        return f;
    }

    @Test
    public void rodsAreTwoPowderEach() {
        assertEquals(7, EyeMath.rodsNeeded(14, 0, 0));
        assertEquals(6, EyeMath.rodsNeeded(12, 0, 0));
        // odd numbers round up: 13 powder is 7 rods
        assertEquals(7, EyeMath.rodsNeeded(13, 0, 0));
    }

    @Test
    public void eyesAndPowderCountTowardTheRods() {
        assertEquals(5, EyeMath.rodsNeeded(14, 0, 4));
        assertEquals(3, EyeMath.rodsNeeded(14, 4, 4));
        assertEquals(0, EyeMath.rodsNeeded(14, 8, 6));
        assertEquals(0, EyeMath.rodsNeeded(14, 14, 0));
        assertEquals(0, EyeMath.rodsNeeded(14, 20, 0));
    }

    @Test
    public void pearlsAreOnePerEye() {
        assertEquals(14, EyeMath.pearlsNeeded(14, 0));
        assertEquals(9, EyeMath.pearlsNeeded(14, 5));
        assertEquals(0, EyeMath.pearlsNeeded(14, 14));
        assertEquals(0, EyeMath.pearlsNeeded(14, 99));
    }

    @Test
    public void craftableIsTheShortOfPearlsAndPowder() {
        assertEquals(4, EyeMath.craftable(4, 10, 0));
        assertEquals(6, EyeMath.craftable(9, 2, 2));
        assertEquals(0, EyeMath.craftable(0, 5, 5));
        assertEquals(0, EyeMath.craftable(5, 0, 0));
    }

    @Test
    public void leavesWithTheFullTarget() {
        assertTrue(EyeMath.canLeaveNether(bag(7, 0, 14, 0), cfg, false));
        assertFalse(EyeMath.canLeaveNether(bag(6, 0, 14, 0), cfg, false));
        assertFalse(EyeMath.canLeaveNether(bag(7, 0, 13, 0), cfg, false));
    }

    @Test
    public void powderAndEyesStandInForRods() {
        // 4 eyes, 6 powder: 10 of 14 covered, 2 more rods; 10 pearls finish it
        assertTrue(EyeMath.canLeaveNether(bag(2, 6, 10, 4), cfg, false));
        assertFalse(EyeMath.canLeaveNether(bag(1, 6, 10, 4), cfg, false));
        assertTrue(EyeMath.canLeaveNether(bag(0, 0, 0, 14), cfg, false));
    }

    @Test
    public void budgetOverAcceptsTheFloor() {
        FakeFacts floor = bag(6, 0, 12, 0);
        assertFalse(EyeMath.canLeaveNether(floor, cfg, false));
        assertTrue(EyeMath.canLeaveNether(floor, cfg, true));
        // below the floor the budget does not help
        assertFalse(EyeMath.canLeaveNether(bag(6, 0, 11, 0), cfg, true));
        assertFalse(EyeMath.canLeaveNether(bag(5, 0, 12, 0), cfg, true));
        assertEquals(12, cfg.floorEyes);
    }

    @Test
    public void floorCountsPearlsPlusEyes() {
        // 3 eyes made already, 9 pearls, 5 rods = 10 powder: eyes 3 + 9 pearls = 12, powder 3 + 10 = 13
        assertTrue(EyeMath.canLeaveNether(bag(5, 0, 9, 3), cfg, true));
    }

    @Test
    public void eyesPhaseExit() {
        assertTrue(EyeMath.eyesDone(bag(0, 0, 0, 14), cfg));
        assertFalse(EyeMath.eyesDone(bag(7, 0, 14, 0), cfg));
        // at the floor with something left to craft: keep crafting up to the target
        assertFalse(EyeMath.eyesDone(bag(1, 0, 2, 12), cfg));
        // at the floor with nothing left to craft from: done
        assertTrue(EyeMath.eyesDone(bag(0, 0, 3, 12), cfg));
        assertTrue(EyeMath.eyesDone(bag(2, 0, 0, 13), cfg));
        // under the floor, even with nothing to craft, it is not done
        assertFalse(EyeMath.eyesDone(bag(0, 0, 0, 11), cfg));
    }

    @Test
    public void potentialEyes() {
        assertEquals(10, EyeMath.potentialEyes(bag(2, 1, 8, 5)));
        assertEquals(5, EyeMath.potentialEyes(bag(0, 0, 8, 5)));
    }
}
