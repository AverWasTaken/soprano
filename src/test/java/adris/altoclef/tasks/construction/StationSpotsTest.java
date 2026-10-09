package adris.altoclef.tasks.construction;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class StationSpotsTest {

    private static final double STAND_EYE = 1.62;
    private static final double SNEAK_EYE = 1.27;

    enum Kind {
        AIR, FULL, FENCE, SLAB_BOTTOM
    }

    // everything at or under floorY is stone, the rest is air, and tests carve whatever they need out of that
    static final class FakeWorld implements StationSpots.Cells {
        final int floorY;
        final Map<String, Kind> overrides = new HashMap<>();
        // when set, only these cells accept a block (a way to say "everything else has a mob in it")
        Set<String> onlyPlaceable;
        final Set<String> hidden = new HashSet<>();
        // stone we may not mine (bedrock, a chest, the ore we want)
        final Set<String> unminable = new HashSet<>();
        // cells with water, lava, gravel in them: opening a neighbour lets them in
        final Set<String> wet = new HashSet<>();
        // a station of ours or any workbench standing there (StationHook.keepStanding)
        final Set<String> stations = new HashSet<>();

        FakeWorld(int floorY) {
            this.floorY = floorY;
        }

        static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        FakeWorld put(int x, int y, int z, Kind kind) {
            overrides.put(key(x, y, z), kind);
            return this;
        }

        Kind at(int x, int y, int z) {
            Kind k = overrides.get(key(x, y, z));
            if (k != null) {
                return k;
            }
            return y <= floorY ? Kind.FULL : Kind.AIR;
        }

        @Override
        public boolean placeable(int x, int y, int z) {
            if (onlyPlaceable != null && !onlyPlaceable.contains(key(x, y, z))) {
                return false;
            }
            return at(x, y, z) == Kind.AIR;
        }

        @Override
        public boolean faceSturdy(int x, int y, int z, int fx, int fy, int fz) {
            return switch (at(x, y, z)) {
                case FULL -> true;
                // the top of a bottom slab is half a block down, its bottom is the sturdy side
                case SLAB_BOTTOM -> fy == -1;
                default -> false;
            };
        }

        @Override
        public boolean passable(int x, int y, int z) {
            return at(x, y, z) == Kind.AIR;
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return at(x, y - 1, z) == Kind.FULL && passable(x, y, z) && passable(x, y + 1, z);
        }

        @Override
        public boolean carvable(int x, int y, int z) {
            return at(x, y, z) == Kind.FULL && !unminable.contains(key(x, y, z));
        }

        @Override
        public boolean floods(int x, int y, int z) {
            return wet.contains(key(x, y, z));
        }

        @Override
        public boolean keep(int x, int y, int z) {
            return stations.contains(key(x, y, z));
        }

        @Override
        public boolean visible(double ex, double ey, double ez, int sx, int sy, int sz, int fx, int fy, int fz, int cx, int cy, int cz) {
            return !hidden.contains(key(sx, sy, sz));
        }
    }

    // feet at (0,65,0), standing on the floor (top at y=65)
    private static StationSpots.Stance flat() {
        return new StationSpots.Stance(0, 65, 0, 0.5, 65 + SNEAK_EYE, 0.5);
    }

    private static Optional<StationSpots.Spot> best(FakeWorld w, StationSpots.Stance me) {
        return StationSpots.best(w, me, new StationSpots.Bans());
    }

    @Test
    public void flatGroundPutsItOnOurLevelNextToUs() {
        StationSpots.Spot s = best(new FakeWorld(64), flat()).orElseThrow();
        assertEquals(65, s.y());
        assertTrue(s.isTopFace());
        // the next cell over, not some corner of the room
        assertEquals(1, Math.abs(s.x()) + Math.abs(s.z()));
    }

    @Test
    public void feetLevelBeatsLowerAtSimilarDistance() {
        // a step down right next to us is a worse answer than the floor a block or two further on our own level
        assertTrue(StationSpots.score(4, 0) < StationSpots.score(1, 1));
        assertTrue(StationSpots.score(1, 0) < StationSpots.score(1, 1));
        // a climb is a climb whichever way it goes
        assertEquals(StationSpots.score(2, 1), StationSpots.score(2, -1), 0);
    }

    @Test
    public void aHoleNextToUsLosesToTheFloorOnOurLevel() {
        FakeWorld w = new FakeWorld(64);
        // a pit one deep on every side at distance 1: those cells are placeable and supported, but a level lower
        w.put(1, 64, 0, Kind.AIR).put(-1, 64, 0, Kind.AIR).put(0, 64, 1, Kind.AIR).put(0, 64, -1, Kind.AIR);
        StationSpots.Spot s = best(w, flat()).orElseThrow();
        assertEquals(65, s.y());
    }

    @Test
    public void aSturdyTopIsRequiredNotJustAnythingSolid() {
        // standing on a fence line: every neighbouring floor cell is a fence, whose top is not a face to build on
        FakeWorld w = new FakeWorld(63);
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                w.put(x, 64, z, Kind.FENCE);
            }
        }
        StationSpots.Stance onFence = new StationSpots.Stance(0, 65, 0, 0.5, 65 + SNEAK_EYE, 0.5);
        assertTrue(best(w, onFence).isEmpty());
        // a bottom slab is not a floor either
        FakeWorld slabs = new FakeWorld(63);
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                slabs.put(x, 64, z, Kind.SLAB_BOTTOM);
            }
        }
        assertTrue(best(slabs, onFence).isEmpty());
    }

    // the log: standing on a fence, it just sat there. one gap in the fence line and the ground under it is the answer
    @Test
    public void aGapInTheFenceLineLetsUsPlaceOnTheGroundBelow() {
        FakeWorld w = new FakeWorld(63);
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                w.put(x, 64, z, Kind.FENCE);
            }
        }
        w.put(2, 64, 0, Kind.AIR);
        StationSpots.Stance onFence = new StationSpots.Stance(0, 65, 0, 0.5, 65 + SNEAK_EYE, 0.5);
        StationSpots.Spot s = best(w, onFence).orElseThrow();
        assertEquals(2, s.x());
        assertEquals(64, s.y());
        assertEquals(0, s.z());
        assertTrue(s.isTopFace());
        assertEquals(63, s.sy());
    }

    @Test
    public void ourOwnTwoCellsAreNeverTheAnswer() {
        FakeWorld w = new FakeWorld(64);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(0, 65, 0), FakeWorld.key(0, 66, 0)));
        assertTrue(best(w, flat()).isEmpty());
        // and the cell next to us is fine once it is allowed
        w.onlyPlaceable.add(FakeWorld.key(1, 65, 0));
        assertEquals(new StationSpots.Spot(1, 65, 0, 1, 64, 0), best(w, flat()).orElseThrow());
    }

    @Test
    public void ownCellPredicateCoversFeetAndHeadOnly() {
        StationSpots.Stance me = flat();
        assertTrue(me.isOwnCell(0, 65, 0));
        assertTrue(me.isOwnCell(0, 66, 0));
        assertFalse(me.isOwnCell(0, 64, 0));
        assertFalse(me.isOwnCell(0, 67, 0));
        assertFalse(me.isOwnCell(1, 65, 0));
    }

    @Test
    public void aBanTakesTheColumnTwoEitherWay() {
        StationSpots.Bans bans = new StationSpots.Bans();
        bans.ban(3, 65, 3);
        for (int y = 63; y <= 67; y++) {
            assertTrue("y " + y, bans.isBanned(3, y, 3));
        }
        assertFalse(bans.isBanned(3, 62, 3));
        assertFalse(bans.isBanned(3, 68, 3));
        // the next column over is a different spot
        assertFalse(bans.isBanned(4, 65, 3));
        assertFalse(bans.isBanned(3, 65, 4));
        bans.clear();
        assertFalse(bans.isBanned(3, 65, 3));
    }

    @Test
    public void aBannedSpotIsNotPickedAgain() {
        FakeWorld w = new FakeWorld(64);
        StationSpots.Bans bans = new StationSpots.Bans();
        StationSpots.Spot first = StationSpots.best(w, flat(), bans).orElseThrow();
        bans.ban(first.x(), first.y(), first.z());
        StationSpots.Spot second = StationSpots.best(w, flat(), bans).orElseThrow();
        assertNotEquals(first, second);
        assertFalse(bans.isBanned(second.x(), second.y(), second.z()));
    }

    @Test
    public void everySpotBannedMeansNoSpot() {
        FakeWorld w = new FakeWorld(64);
        StationSpots.Bans bans = new StationSpots.Bans();
        for (int x = -StationSpots.RADIUS; x <= StationSpots.RADIUS; x++) {
            for (int z = -StationSpots.RADIUS; z <= StationSpots.RADIUS; z++) {
                bans.ban(x, 65, z);
            }
        }
        assertTrue(StationSpots.best(w, flat(), bans).isEmpty());
    }

    @Test
    public void aWallSideIsTheFallbackWhenNoFloorWorks() {
        FakeWorld w = new FakeWorld(63);
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                w.put(x, 64, z, Kind.FENCE);
            }
        }
        // a stone pillar three blocks east, up through our level
        w.put(3, 64, 0, Kind.FULL).put(3, 65, 0, Kind.FULL).put(3, 66, 0, Kind.FULL);
        StationSpots.Stance onFence = new StationSpots.Stance(0, 65, 0, 0.5, 65 + SNEAK_EYE, 0.5);
        StationSpots.Spot s = best(w, onFence).orElseThrow();
        assertFalse(s.isTopFace());
        // the cell west of the pillar, clicking the pillar's west face
        assertEquals(new StationSpots.Spot(2, 65, 0, 3, 65, 0), s);
        assertEquals(-1, s.faceX());
        assertEquals(0, s.faceY());
    }

    @Test
    public void aFloorBeatsAWallWheneverThereIsOne() {
        FakeWorld w = new FakeWorld(64);
        w.put(1, 65, 0, Kind.FULL).put(1, 66, 0, Kind.FULL);
        StationSpots.Spot s = best(w, flat()).orElseThrow();
        assertTrue(s.isTopFace());
    }

    @Test
    public void aHiddenFaceIsNotClickable() {
        FakeWorld w = new FakeWorld(64);
        // the floor next to us is out of sight (behind something), the rest is fine
        w.hidden.add(FakeWorld.key(1, 64, 0));
        w.hidden.add(FakeWorld.key(-1, 64, 0));
        w.hidden.add(FakeWorld.key(0, 64, 1));
        w.hidden.add(FakeWorld.key(0, 64, -1));
        StationSpots.Spot s = best(w, flat()).orElseThrow();
        assertFalse(w.hidden.contains(FakeWorld.key(s.sx(), s.sy(), s.sz())));
    }

    @Test
    public void thingsOutOfReachAreNotClickable() {
        FakeWorld w = new FakeWorld(64);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(4, 65, 4)));
        // 5.7 blocks from the eye to the middle of that floor
        assertTrue(best(w, flat()).isEmpty());
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(2, 65, 1)));
        assertTrue(best(w, flat()).isPresent());
    }

    @Test
    public void weDoNotPlaceTheLastDoorOutOfACorridor() {
        // stone on three sides at feet and head height, the only way out is south
        FakeWorld w = new FakeWorld(64);
        for (int y = 65; y <= 66; y++) {
            w.put(1, y, 0, Kind.FULL).put(-1, y, 0, Kind.FULL).put(0, y, -1, Kind.FULL);
        }
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(0, 65, 1)));
        assertFalse(StationSpots.leavesAWayOut(w, flat(), 0, 65, 1));
        assertTrue(best(w, flat()).isEmpty());
        // a second way out and it is fine
        w.put(1, 65, 0, Kind.AIR).put(1, 66, 0, Kind.AIR);
        assertTrue(StationSpots.leavesAWayOut(w, flat(), 0, 65, 1));
        assertTrue(best(w, flat()).isPresent());
    }

    @Test
    public void aBlockOnTheFloorBelowUsNeverClosesAnExit() {
        // stone around us except east, and the cell east of us but a level down is not the doorway (feet and head are)
        FakeWorld w = new FakeWorld(63);
        for (int y = 65; y <= 66; y++) {
            w.put(-1, y, 0, Kind.FULL).put(0, y, 1, Kind.FULL).put(0, y, -1, Kind.FULL);
        }
        assertTrue(StationSpots.leavesAWayOut(w, flat(), 1, 64, 0));
        assertFalse(StationSpots.leavesAWayOut(w, flat(), 1, 65, 0));
        assertFalse(StationSpots.leavesAWayOut(w, flat(), 1, 66, 0));
    }

    // ---- standing points ----

    @Test
    public void aStandpointComesFromTheNearestPatchWithASpot() {
        FakeWorld w = new FakeWorld(64);
        // only the strip around x=10 takes a block, six blocks is as far as we walk and the first place the strip is in reach
        w.onlyPlaceable = new HashSet<>();
        for (int x = 9; x <= 11; x++) {
            for (int z = -1; z <= 1; z++) {
                w.onlyPlaceable.add(FakeWorld.key(x, 65, z));
            }
        }
        StationSpots.Stand stand = StationSpots.standpoint(w, flat(), new StationSpots.Bans(), SNEAK_EYE).orElseThrow();
        assertEquals(6, stand.x());
        assertEquals(0, stand.z());
        assertEquals(65, stand.y());
    }

    @Test
    public void aStandpointIsAtLeastTwoAwayAndNoMoreThanSix() {
        FakeWorld w = new FakeWorld(64);
        // spots exist right where we stand, so the nearest candidate counts, but never closer than MIN_MOVE
        StationSpots.Stand stand = StationSpots.standpoint(w, flat(), new StationSpots.Bans(), SNEAK_EYE).orElseThrow();
        int far = Math.max(Math.abs(stand.x()), Math.abs(stand.z()));
        assertTrue(far >= StationSpots.MIN_MOVE && far <= StationSpots.MAX_MOVE);
    }

    @Test
    public void noStandpointWhenNothingAnywhereTakesABlock() {
        FakeWorld w = new FakeWorld(64);
        w.onlyPlaceable = new HashSet<>();
        assertTrue(StationSpots.standpoint(w, flat(), new StationSpots.Bans(), SNEAK_EYE).isEmpty());
    }

    @Test
    public void standpointsSkipBannedColumns() {
        FakeWorld w = new FakeWorld(64);
        StationSpots.Bans bans = new StationSpots.Bans();
        StationSpots.Stand first = StationSpots.standpoint(w, flat(), bans, SNEAK_EYE).orElseThrow();
        bans.ban(first.x(), first.y(), first.z());
        StationSpots.Stand second = StationSpots.standpoint(w, flat(), bans, SNEAK_EYE).orElseThrow();
        assertNotEquals(first, second);
    }

    @Test
    public void aStandpointNeedsAFullBlockUnderIt() {
        // nothing but air under y=64 means no full block to stand on
        FakeWorld w = new FakeWorld(0);
        assertFalse(w.standable(5, 65, 0));
        assertTrue(new FakeWorld(64).standable(5, 65, 0));
    }

    // ---- carving ----

    // the log: a 1x1 shaft in solid rock. our two cells, and open air straight up and down. nothing around takes a block
    private static FakeWorld shaft() {
        FakeWorld w = new FakeWorld(200);
        for (int y = 60; y <= 80; y++) {
            w.put(0, y, 0, Kind.AIR);
        }
        return w;
    }

    private static StationSpots.Stance inShaft() {
        return new StationSpots.Stance(0, 65, 0, 0.5, 65 + SNEAK_EYE, 0.5);
    }

    private static List<StationSpots.Cell> carves(FakeWorld w) {
        return StationSpots.carveCandidates(w, inShaft(), new StationSpots.Bans());
    }

    @Test
    public void aShaftHasNothingClickableButPlentyToCarve() {
        FakeWorld w = shaft();
        assertTrue(best(w, inShaft()).isEmpty());
        assertFalse(carves(w).isEmpty());
    }

    @Test
    public void carveOrderIsFeetThenHeadAndTwoOutNeedsAnOpenCellBetween() {
        List<StationSpots.Cell> list = carves(shaft());
        // four at feet level, four at head level, nothing two out: the cell between is stone
        assertEquals(8, list.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(65, list.get(i).y());
            assertEquals(1, Math.abs(list.get(i).x()) + Math.abs(list.get(i).z()));
        }
        for (int i = 4; i < 8; i++) {
            assertEquals(66, list.get(i).y());
        }
        // a tunnel east: now the cell two out that way is a candidate, after the near ones
        FakeWorld tunnel = shaft();
        tunnel.put(1, 65, 0, Kind.AIR).put(1, 66, 0, Kind.AIR);
        List<StationSpots.Cell> withTunnel = carves(tunnel);
        assertEquals(new StationSpots.Cell(2, 65, 0), withTunnel.get(withTunnel.size() - 1));
        assertFalse(withTunnel.contains(new StationSpots.Cell(1, 65, 0)));
    }

    @Test
    public void carvingTheHoleMakesItTheSpot() {
        FakeWorld w = shaft();
        StationSpots.Cell hole = carves(w).get(0);
        w.put(hole.x(), hole.y(), hole.z(), Kind.AIR);
        // dug by us: it needn't leave a way out, the shaft is the way out
        StationSpots.Spot s = StationSpots.best(w, inShaft(), new StationSpots.Bans(), hole).orElseThrow();
        assertEquals(new StationSpots.Spot(hole.x(), hole.y(), hole.z(), hole.x(), hole.y() - 1, hole.z()), s);
        assertTrue(s.isTopFace());
        // an ordinary hole doesn't get the pass: a table there would close the last horizontal door
        assertTrue(StationSpots.best(w, inShaft(), new StationSpots.Bans()).isEmpty());
    }

    @Test
    public void theCarveExemptionIsOnlyForTheCellWeDug() {
        FakeWorld w = shaft();
        w.put(1, 65, 0, Kind.AIR);
        // (1,65,0) is open but it isn't the one we dug (that was somewhere else), so it still has to leave a way out
        assertTrue(StationSpots.best(w, inShaft(), new StationSpots.Bans(), new StationSpots.Cell(0, 65, 1)).isEmpty());
        assertTrue(StationSpots.best(w, inShaft(), new StationSpots.Bans(), new StationSpots.Cell(1, 65, 0)).isPresent());
    }

    @Test
    public void bedrockContainersAndOreAreNeverCarved() {
        FakeWorld w = shaft();
        // all four feet-level neighbours are off limits, so the head level is what is left
        w.unminable.add(FakeWorld.key(1, 65, 0));
        w.unminable.add(FakeWorld.key(-1, 65, 0));
        w.unminable.add(FakeWorld.key(0, 65, 1));
        w.unminable.add(FakeWorld.key(0, 65, -1));
        List<StationSpots.Cell> list = carves(w);
        assertEquals(4, list.size());
        for (StationSpots.Cell c : list) {
            assertEquals(66, c.y());
        }
        // and with the head level off limits too there is nothing
        w.unminable.add(FakeWorld.key(1, 66, 0));
        w.unminable.add(FakeWorld.key(-1, 66, 0));
        w.unminable.add(FakeWorld.key(0, 66, 1));
        w.unminable.add(FakeWorld.key(0, 66, -1));
        assertTrue(carves(w).isEmpty());
    }

    @Test
    public void aCarveNeedsASturdyFloorUnderIt() {
        FakeWorld w = shaft();
        // a hole under the east neighbour: opening it gives a pit, not a floor
        w.put(1, 64, 0, Kind.AIR);
        assertFalse(carves(w).contains(new StationSpots.Cell(1, 65, 0)));
        // a fence top is not a floor either
        w.put(-1, 64, 0, Kind.FENCE);
        assertFalse(carves(w).contains(new StationSpots.Cell(-1, 65, 0)));
        assertTrue(carves(w).contains(new StationSpots.Cell(0, 65, 1)));
    }

    @Test
    public void aCarveNextToWaterOrFallingBlocksIsOut() {
        FakeWorld w = shaft();
        // water behind the east wall: opening (1,65,0) lets it pour in
        w.wet.add(FakeWorld.key(2, 65, 0));
        assertFalse(carves(w).contains(new StationSpots.Cell(1, 65, 0)));
        // gravel above the north one falls into the hole
        w.wet.add(FakeWorld.key(0, 66, -1));
        assertFalse(carves(w).contains(new StationSpots.Cell(0, 65, -1)));
        // lava over the west one
        w.wet.add(FakeWorld.key(-1, 66, 0));
        assertFalse(carves(w).contains(new StationSpots.Cell(-1, 65, 0)));
        // south is fine
        assertTrue(carves(w).contains(new StationSpots.Cell(0, 65, 1)));
    }

    @Test
    public void bannedCarvesAreSkipped() {
        FakeWorld w = shaft();
        StationSpots.Bans bans = new StationSpots.Bans();
        StationSpots.Cell first = StationSpots.carveCandidates(w, inShaft(), bans).get(0);
        bans.ban(first.x(), first.y(), first.z());
        assertFalse(StationSpots.carveCandidates(w, inShaft(), bans).contains(first));
    }

    // the table we just crafted the furnace at stands beside our feet in the shaft. it is a full block, breakable, no block entity,
    // so carvable alone says yes. it is never the hole the furnace goes in
    @Test
    public void ourTableIsNeverCarvedForRoom() {
        FakeWorld w = shaft();
        StationSpots.Cell table = new StationSpots.Cell(1, 65, 0);
        assertTrue(carves(w).contains(table));
        assertEquals(table, carves(w).get(0));
        w.stations.add(FakeWorld.key(1, 65, 0));
        assertFalse(carves(w).contains(table));
        // the other walls still are
        assertTrue(carves(w).contains(new StationSpots.Cell(-1, 65, 0)));
    }

    @Test
    public void openAirAroundMeNeedsNoCarving() {
        // nothing solid to mine on the floor level of an open room: the neighbours are air, not candidates
        FakeWorld w = new FakeWorld(64);
        assertTrue(StationSpots.carveCandidates(w, flat(), new StationSpots.Bans()).isEmpty());
    }

    @Test
    public void faceDistanceIsToTheMiddleOfTheFace() {
        StationSpots.Stance me = new StationSpots.Stance(0, 65, 0, 0.5, 66.5, 0.5);
        // the top face of (0,64,0) sits at y=65, 1.5 under the eye
        double d = StationSpots.faceDistSq(me, new StationSpots.Spot(0, 65, 0, 0, 64, 0));
        assertEquals(1.5 * 1.5, d, 1e-9);
    }
}
