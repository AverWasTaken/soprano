package adris.altoclef.tasks.speedrun.gamer;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DangerFilterTest {
    @Test
    public void aTableWePlacedIsNeverAWitchTable() {
        List<RunState.Pos> placed = List.of(new RunState.Pos(10, 64, -5));
        assertTrue(DangerFilter.ownStation(placed, new BlockPos(10, 64, -5)));
        assertFalse(DangerFilter.ownStation(placed, new BlockPos(10, 65, -5)));
        assertFalse(DangerFilter.ownStation(List.of(), new BlockPos(10, 64, -5)));
        // no state yet (before the run begins) means nothing is ours
        assertFalse(DangerFilter.ownStation(null, new BlockPos(10, 64, -5)));
    }
}
