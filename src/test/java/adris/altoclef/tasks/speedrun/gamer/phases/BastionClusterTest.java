package adris.altoclef.tasks.speedrun.gamer.phases;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.Test;

// a handful of bastion blocks is a ruined portal, a crowd of them is a bastion
public class BastionClusterTest {

    private static List<BlockPos> blocks(int n, int baseX) {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new BlockPos(baseX + i % 10, 60 + i / 10, 0));
        }
        return out;
    }

    @Test
    public void nineteenBlocksIsNotABastionAndTwentyIs() {
        assertFalse(NetherPhase.bastionCluster(blocks(19, 0), new BlockPos(0, 60, 0)));
        assertTrue(NetherPhase.bastionCluster(blocks(20, 0), new BlockPos(0, 60, 0)));
    }

    @Test
    public void blocksFarFromTheCandidateDoNotCount() {
        List<BlockPos> known = blocks(19, 0);
        known.addAll(blocks(30, 500));
        assertFalse(NetherPhase.bastionCluster(known, new BlockPos(0, 60, 0)));
        assertTrue(NetherPhase.bastionCluster(known, new BlockPos(500, 60, 0)));
    }
}
