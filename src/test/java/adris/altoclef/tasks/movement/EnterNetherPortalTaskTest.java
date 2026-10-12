package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import org.junit.Test;

// the entry goal is a GoalBlock, so only a cell the feet can be in is a real target
public class EnterNetherPortalTaskTest {

    // 2 wide, 3 tall interior at x 0..1, y 64..66
    private static Set<BlockPos> interior() {
        Set<BlockPos> cells = new HashSet<>();
        for (int x = 0; x <= 1; x++) {
            for (int y = 64; y <= 66; y++) {
                cells.add(new BlockPos(x, y, 0));
            }
        }
        return cells;
    }

    @Test
    public void bottomRowIsAFeetCell() {
        Predicate<BlockPos> portal = interior()::contains;
        assertTrue(EnterNetherPortalTask.isFeetCell(new BlockPos(0, 64, 0), portal));
        assertTrue(EnterNetherPortalTask.isFeetCell(new BlockPos(1, 64, 0), portal));
    }

    @Test
    public void everythingAboveTheBottomRowIsNot() {
        Predicate<BlockPos> portal = interior()::contains;
        for (int x = 0; x <= 1; x++) {
            assertFalse(EnterNetherPortalTask.isFeetCell(new BlockPos(x, 65, 0), portal));
            assertFalse(EnterNetherPortalTask.isFeetCell(new BlockPos(x, 66, 0), portal));
        }
    }

    @Test
    public void aLonePortalBlockOnTheGroundCountsAsFeet() {
        // a one cell tall bit of portal (half broken frame) still has to be walkable into
        Predicate<BlockPos> portal = new BlockPos(5, 70, 5)::equals;
        assertTrue(EnterNetherPortalTask.isFeetCell(new BlockPos(5, 70, 5), portal));
    }
}
