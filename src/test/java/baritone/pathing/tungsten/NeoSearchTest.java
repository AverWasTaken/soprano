package baritone.pathing.tungsten;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.sim.ArrayWorld;
import baritone.pathing.sim.BoxList;
import baritone.pathing.sim.PlayerSim;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

// the neos only the full search does (NeoSearches): found, and then flown with the aim wobbling, every time
public class NeoSearchTest {

    @BeforeClass
    public static void boot() {
        adris.altoclef.util.helpers.ItemFixture.boot(); // (run on its own, nothing else has started the game's registries)
    }

    private static final int SX = 8, SY = 63, SZ = 8;

    // a wall three thick (x SX + 1 to SX + 3, z SZ - 3 to SZ) with its end on our row, the open row beside it at SZ + 1, a
    // one block runway and the landing one down past the wall: a triple neo
    static ArrayWorld tripleNeo(Landing[] land) {
        ArrayWorld w = new ArrayWorld(40, 80, 40);
        w.set(SX, SY, SZ, ArrayWorld.STONE);
        w.fill(SX + 1, SY - 2, SZ - 3, SX + 3, SY + 3, SZ, ArrayWorld.STONE);
        w.set(SX + 4, SY - 1, SZ, ArrayWorld.STONE);
        land[0] = new Landing(SX + 4, SY, SZ);
        return w;
    }

    // a one block pad with the wall we just came round right behind it (so no run up at all), a one thick wall in front, and
    // the landing past it: one of a chain of neos
    static ArrayWorld padNeo(Landing[] land) {
        ArrayWorld w = new ArrayWorld(40, 80, 40);
        w.fill(SX - 1, SY - 2, SZ - 3, SX - 1, SY + 3, SZ, ArrayWorld.STONE);
        w.set(SX, SY, SZ, ArrayWorld.STONE);
        w.fill(SX + 1, SY - 2, SZ - 3, SX + 1, SY + 3, SZ, ArrayWorld.STONE);
        w.set(SX + 2, SY, SZ, ArrayWorld.STONE);
        land[0] = new Landing(SX + 2, SY + 1, SZ);
        return w;
    }

    // the real triple neo: level, off the side block (the row beside the wall's, at its start) after a run up along that
    // row, round a wall three thick onto the block right behind it
    static ArrayWorld flatTripleNeo(Landing[] land) {
        ArrayWorld w = new ArrayWorld(40, 80, 40);
        w.fill(SX - 6, SY, SZ + 1, SX, SY, SZ + 1, ArrayWorld.STONE);
        w.fill(SX + 1, SY - 2, SZ - 3, SX + 3, SY + 3, SZ, ArrayWorld.STONE);
        w.set(SX + 4, SY, SZ, ArrayWorld.STONE);
        land[0] = new Landing(SX + 4, SY + 1, SZ);
        return w;
    }

    private static void searchedAndFlown(ArrayWorld w, Landing land, String what) {
        searchedAndFlown(w, land, what, new BetterBlockPos(SX, SY + 1, SZ), NeoSearches.via(new BetterBlockPos(SX, SY + 1, SZ), land, 0, 1));
    }

    private static void searchedAndFlown(ArrayWorld w, Landing land, String what, BetterBlockPos src, double[] via) {
        // the rule has nothing here, which is the whole point
        assertNull(what, JumpPolicy.forgiving(NeoSearches.standing(w, src, SY + 1), land));
        TungstenSearch.Plan plan = NeoSearches.solve(w, src, SY + 1, land, NeoSearches.region(src, land), via);
        assertNotNull(what, plan);
        StringBuilder fails = new StringBuilder();
        for (long seed = 100; seed < 130; seed++) { // other seeds than the ones solve already checked
            String why = NeoSearches.fly(NeoSearches.standing(w, src, SY + 1), land, plan, src.x + 0.5, src.z + 0.5, seed, NeoSearches.JITTER);
            if (why != null) {
                fails.append("seed ").append(seed).append(": ").append(why).append('\n');
            }
        }
        assertEquals(what + "\n" + fails, 0, fails.length());
    }

    @Test
    public void roundAWallThreeThickOneDown() {
        Landing[] land = new Landing[1];
        searchedAndFlown(tripleNeo(land), land[0], "triple neo");
    }

    @Test
    public void aLevelTripleNeoOffTheSideBlock() {
        Landing[] land = new Landing[1];
        ArrayWorld w = flatTripleNeo(land);
        BetterBlockPos src = new BetterBlockPos(SX, SY + 1, SZ + 1);
        searchedAndFlown(w, land[0], "level triple neo", src, new double[]{(SX + 0.5 + land[0].centerX()) / 2, SZ + 1.5});
    }

    @Test
    public void offAPadWithTheLastWallRightBehind() {
        Landing[] land = new Landing[1];
        searchedAndFlown(padNeo(land), land[0], "neo off a pad");
    }

    @Test
    public void theFattenedWorldFattensWallsAndThinsFloors() {
        ArrayWorld w = new ArrayWorld(20, 80, 20);
        w.set(5, 63, 5, ArrayWorld.STONE); // a floor under the feet at y 64
        w.set(7, 64, 5, ArrayWorld.STONE); // a wall at feet height
        w.set(7, 65, 5, ArrayWorld.STONE);
        InflatedCollision fat = new InflatedCollision(w, 0.05, 64, 64);
        BoxList b = new BoxList();
        fat.boxes(5, 63, 5, b);
        assertEquals(5.05, b.get(0, 0), 1e-9);
        assertEquals(5.95, b.get(0, 3), 1e-9);
        b.clear();
        fat.boxes(7, 64, 5, b);
        assertEquals(6.95, b.get(0, 0), 1e-9);
        assertEquals(8.05, b.get(0, 3), 1e-9);
    }

    @Test
    public void aSnapshotsHashChangesWithItsBlocks() {
        // the snapshot a background search works on hashes the same for the same blocks, and differently for different ones
        Landing[] land = new Landing[1];
        ArrayWorld a = padNeo(land), b = padNeo(land);
        assertEquals(new SnapshotCollision(a, 0, 55, 0, 20, 70, 20).hash, new SnapshotCollision(b, 0, 55, 0, 20, 70, 20).hash);
        b.set(SX + 3, SY, SZ, ArrayWorld.STONE);
        assertNotEquals(new SnapshotCollision(a, 0, 55, 0, 20, 70, 20).hash, new SnapshotCollision(b, 0, 55, 0, 20, 70, 20).hash);
    }
}
