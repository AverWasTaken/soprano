package adris.altoclef.ui.gui.mc;

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class McStatusLabelTest {

    @Test
    public void javaBitsComeOffTheLongTail() {
        assertEquals("Self defense vs zombie", McStatus.cleanName("Self defense vs minecraft:zombie"));
        assertEquals("Backfill", McStatus.cleanName("Backfill"));
        assertEquals("Waiting on the inventory", McStatus.cleanName("inventory pauser"));
        assertEquals("Thing at", McStatus.cleanName("thing at BlockPos{x=1, y=2, z=3}"));
        assertNull(McStatus.cleanName("{x=1}"));
    }

    @Test
    public void goalsReadLikeWords() {
        assertEquals("Goal 1 2 3", McStatus.goalText(new GoalBlock(1, 2, 3)));
        assertEquals("Goal 5 -7", McStatus.goalText(new GoalXZ(5, -7)));
        assertEquals("Goal y 12", McStatus.goalText(new GoalYLevel(12)));
        assertEquals("Near 1 2 3", McStatus.goalText(new GoalNear(new BlockPos(1, 2, 3), 2)));
        assertEquals("", McStatus.goalText(null));
    }
}
