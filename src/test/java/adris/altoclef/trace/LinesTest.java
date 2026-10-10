package adris.altoclef.trace;

import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.chains.UserTaskChain;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LinesTest {

    @Test
    public void theChainSwitchLine() {
        assertEquals("switch chain from=MobDefenseChain:9.0 to=UserTaskChain:12.5 next=FoodChain:8.0",
                Lines.chainSwitch("MobDefenseChain", 9.0f, "UserTaskChain", 12.5f, "FoodChain", 8.0f));
    }

    @Test
    public void nobodyIsNoneAndAnInactiveLoserIsOff() {
        assertEquals("switch chain from=none to=UserTaskChain:50.0 next=none",
                Lines.chainSwitch(null, Float.NaN, "UserTaskChain", 50f, null, Float.NEGATIVE_INFINITY));
        assertEquals("switch chain from=FoodChain:off to=none next=none",
                Lines.chainSwitch("FoodChain", Float.NaN, null, Float.NEGATIVE_INFINITY, null, 0f));
    }

    @Test
    public void realChainClassNamesHaveNoSpacesInThem() {
        // getName() on these says "User Tasks" and "Mob Defense", which would split the key=value
        assertFalse(UserTaskChain.class.getSimpleName().contains(" "));
        assertFalse(MobDefenseChain.class.getSimpleName().contains(" "));
        assertEquals("UserTaskChain", UserTaskChain.class.getSimpleName());
    }

    @Test
    public void theRepeatLine() {
        assertEquals("repeat: CollectIronTask \"mining\" x5 in 60 s", Lines.repeat("CollectIronTask", "mining", 5));
    }

    @Test
    public void theStormLine() {
        assertEquals("MONITOR: start storm ClickSlotTask x21 in 60 s", Lines.startStorm("ClickSlotTask"));
    }

    @Test
    public void theStormLineNamesWhoKeptCuttingItOff() {
        assertEquals("MONITOR: start storm DestroyBlockTask x21 in 60 s, mostly cut off by PickupDroppedItemTask",
                Lines.startStorm("DestroyBlockTask", "PickupDroppedItemTask", false));
        assertEquals("MONITOR: start storm GetToEntityTask x21 in 60 s, mostly dropped by parent KillEntityTask",
                Lines.startStorm("GetToEntityTask", "KillEntityTask", true));
        // nobody to blame is the plain line
        assertEquals(Lines.startStorm("ClickSlotTask"), Lines.startStorm("ClickSlotTask", null, false));
    }

    @Test
    public void theCursorLine() {
        assertEquals("MONITOR: cursor stuck cobblestone x12 for 5 s, no container screen open", Lines.cursorStuck("cobblestone", 12, 5));
    }
}
