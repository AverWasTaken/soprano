package adris.altoclef.tasks.container;

import adris.altoclef.tasks.container.CollectFromFurnaceTask.Mode;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// when a visit to a cold station puts fuel in instead of taking the meat back out
public class CollectFromFurnaceFeedTest {
    @Test
    public void aColdStationGetsFuelOnceInTheStayingModes() {
        assertTrue(CollectFromFurnaceTask.mayFeed(true, Mode.NORMAL, false));
        assertTrue(CollectFromFurnaceTask.mayFeed(true, Mode.WAIT_ALL, false));
        assertFalse("already tried this visit", CollectFromFurnaceTask.mayFeed(true, Mode.NORMAL, true));
    }

    @Test
    public void takeAllNeverLightsWhatItIsAboutToEmpty() {
        assertFalse(CollectFromFurnaceTask.mayFeed(true, Mode.TAKE_ALL, false));
    }

    @Test
    public void aStationThatIsLitOrFueledIsNotFed() {
        assertFalse(CollectFromFurnaceTask.mayFeed(false, Mode.NORMAL, false));
        assertFalse(CollectFromFurnaceTask.mayFeed(false, Mode.WAIT_ALL, false));
    }
}
