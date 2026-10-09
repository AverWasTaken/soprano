package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.Bans;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
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

    // two pillagers standing at the origin long enough to be an outpost, then nobody for longer than it lives
    private static PillagerWatch outpostThatWentQuiet() {
        PillagerWatch watch = new PillagerWatch(10, 100);
        Map<Integer, double[]> two = Map.of(1, new double[]{0, 0}, 2, new double[]{1, 1});
        watch.update(0, two);
        watch.update(10, two);
        assertEquals(1, watch.outposts());
        watch.update(200, Map.of());
        assertEquals(0, watch.outposts());
        return watch;
    }

    @Test
    public void aQuietOutpostLiftsOnlyTheOutpostBansAroundIt() {
        Bans bans = new Bans(line -> {
        });
        BanPolicy.outpost(bans, Dimension.OVERWORLD, 5, 70, 5);
        BanPolicy.outpost(bans, Dimension.OVERWORLD, 300, 70, 5);
        BanPolicy.blockStrike(bans, Dimension.OVERWORLD, 6, 70, 5, 0, 4, "couldn't reach it");
        BanPolicy.outpost(bans, Dimension.OVERWORLD, 6, 70, 5);
        assertEquals(2, DangerFilter.liftExpiredBans(bans, outpostThatWentQuiet()));
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 5, 70, 5));
        assertTrue("another outpost's", bans.blockBanned(Dimension.OVERWORLD, 300, 70, 5));
        assertTrue("unreachable anyway, not ours to lift", bans.blockBanned(Dimension.OVERWORLD, 6, 70, 5));
    }

    @Test
    public void nothingExpiredLiftsNothing() {
        Bans bans = new Bans(line -> {
        });
        BanPolicy.outpost(bans, Dimension.OVERWORLD, 5, 70, 5);
        assertEquals(0, DangerFilter.liftExpiredBans(bans, new PillagerWatch(10, 100)));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 5, 70, 5));
    }
}
