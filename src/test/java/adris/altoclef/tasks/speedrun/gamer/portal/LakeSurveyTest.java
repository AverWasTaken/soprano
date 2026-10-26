package adris.altoclef.tasks.speedrun.gamer.portal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Mat;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.P;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlanTest.World;
import adris.altoclef.tasks.speedrun.gamer.portal.LakeSurvey.Lake;
import org.junit.Test;

public class LakeSurveyTest {
    private static final P SEED = new P(20, 63, 5);

    private static Lake run(World w) {
        LakeSurvey s = LakeSurvey.of(w, SEED, 0, 0);
        int guard = 0;
        while (!s.step(6, w, l -> true)) {
            assertTrue("never finishes", ++guard < 100000);
        }
        return s.lake();
    }

    @Test
    public void aCleanPoolIsAGoodLakeWithNothingToDo() {
        Lake lake = run(LPortalPlanTest.pool());
        assertTrue(lake.good());
        assertFalse(lake.unknown());
        assertEquals(0, lake.cost());
        assertEquals(0, lake.fills());
        assertTrue(lake.cells().contains(SEED));
    }

    @Test
    public void aPoolUnderWaterIsABadLakeSoNobodyWalksToIt() {
        World w = LPortalPlanTest.pool();
        for (int x = 10; x < 40; x++) {
            for (int z = 0; z <= 10; z++) {
                w.over.put(new P(x, 64, z), Mat.WATER);
            }
        }
        Lake lake = run(w);
        assertNull(lake.site());
        assertFalse("water is a no, not a maybe", lake.unknown());
    }

    @Test
    public void waterAroundThePoolSpoilsEveryRowEvenWithAirOverTheLava() {
        World w = LPortalPlanTest.pool();
        // a ring of water on the shore all the way around: there are rows and layouts, every one reads a wet cell
        for (int x = 6; x < 44; x++) {
            for (int z = -4; z <= 14; z++) {
                boolean inPool = x >= 10 && x < 40 && z >= 0 && z <= 10;
                if (!inPool) {
                    w.over.put(new P(x, 64, z), Mat.WATER);
                }
            }
        }
        Lake lake = run(w);
        assertNull(lake.site());
        assertFalse(lake.unknown());
    }

    @Test
    public void aBoxThatIsntLoadedIsUnknownNotBad() {
        World w = LPortalPlanTest.pool();
        LakeSurvey s = LakeSurvey.of(w, SEED, 0, 0);
        while (!s.step(6, w, l -> false)) {
            // keep going
        }
        Lake lake = s.lake();
        assertNull(lake.site());
        assertTrue(lake.unknown());
    }

    @Test
    public void aSurveyIsDoneAFewLayoutsAtATime() {
        World w = LPortalPlanTest.pool();
        LakeSurvey s = LakeSurvey.of(w, SEED, 0, 0);
        assertFalse(s.step(1, w, l -> true));
        assertNotNull(s.lake());
    }
}
