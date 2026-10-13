package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.container.AsyncSmelting;
import org.junit.Test;

// the cook task's smelt is "finished" two ways, and only one of them is a load
public class CookRawFoodTaskTest {
    @Test
    public void aSmeltThatLoadedAndLeftIsAHandoffAndTheBagMeetingItsTargetIsNot() {
        AsyncSmelting.Handoff loaded = () -> true;
        AsyncSmelting.Handoff notYet = () -> false;
        assertTrue(CookRawFoodTask.isHandoff(loaded));
        // 23:19:39: three cooked beef collected from the smoker met "have three cooked beef" with the raw beef still in the bag
        assertFalse(CookRawFoodTask.isHandoff(notYet));
        assertFalse(CookRawFoodTask.isHandoff(new Object()));
        assertFalse(CookRawFoodTask.isHandoff(null));
    }
}
