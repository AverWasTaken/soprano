package adris.altoclef.tasks.resources;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// ten blocks for a path is ten dirt, not a tree, a pickaxe and a hole in a hill
public class TripGatherPhaseTest {

    @Test
    public void noPickaxeAndDirtAroundMeansDirtByHand() {
        assertFalse(TripGatherTask.wide(false, true, false));
    }

    @Test
    public void noPickaxeAndNoDirtLeftMeansTheTreeAndTheStone() {
        assertTrue(TripGatherTask.wide(false, true, true));
        // netherrack only, nothing comes out by hand
        assertTrue(TripGatherTask.wide(false, false, false));
    }

    @Test
    public void aPickaxeStillDigsTheGrassFirst() {
        // holding a pickaxe doesn't make far off cobble quicker than the dirt we're standing on.
        // wide() doesn't even ask about the pickaxe anymore, same answer as the no pickaxe case
        assertFalse(TripGatherTask.wide(false, true, false));
    }

    @Test
    public void onceWideItStaysWide() {
        // the pickaxe broke and dirt showed up again, still not going back
        assertTrue(TripGatherTask.wide(true, true, false));
    }
}
