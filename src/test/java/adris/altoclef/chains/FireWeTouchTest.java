package adris.altoclef.chains;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.Test;

// the fire we put out is the one we touch, picked straight off the scanned blocks
public class FireWeTouchTest {

    @Test
    public void picksTheFirstFireAndCopiesIt() {
        BlockPos.MutableBlockPos reused = new BlockPos.MutableBlockPos(1, 64, 1);
        BlockPos hit = WorldSurvivalChain.firstMatch(List.of(new BlockPos(0, 64, 0), reused), p -> p.getX() == 1);
        reused.set(9, 9, 9);
        assertEquals(new BlockPos(1, 64, 1), hit);
        assertNotSame(reused, hit);
    }

    @Test
    public void noFireMeansNull() {
        assertNull(WorldSurvivalChain.firstMatch(List.of(new BlockPos(0, 64, 0)), p -> false));
    }
}
