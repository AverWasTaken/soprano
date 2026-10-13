package adris.altoclef.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// pure math for sweeping nether fortresses / bastions (gamer-design.md 5.4).
// fortress and bastion share one structure set: 27x27 chunk cells aligned to the world grid (nether coordinates),
// one candidate chunk per cell at offset 0..22 chunks per axis
public final class NetherComplexGrid {
    public static final int CELL_CHUNKS = 27;
    // nextInt(27 - 4) so the candidate chunk sits at 0..22 inside its cell
    public static final int CANDIDATE_CHUNKS = 23;
    // the structure is not one chunk big, so the sweep covers the candidate square plus this much on top
    public static final int STRUCTURE_MARGIN_CHUNKS = 4;
    public static final int DEFAULT_SPACING_CHUNKS = 9;

    // middle of the candidate square: chunk 11, block centre of it
    private static final int CANDIDATE_MID_CHUNK = (CANDIDATE_CHUNKS - 1) / 2;

    public record Cell(int cx, int cz) {
        public String key() {
            return cx + "," + cz;
        }

        public static Cell parse(String key) {
            String[] parts = key.split(",");
            return new Cell(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        }
    }

    public record Point(int x, int z) {
    }

    // nether block coordinates -> cell. shift is floor division by 16 and floorDiv floors too, so negatives are fine
    public static Cell cellOf(int blockX, int blockZ) {
        return new Cell(Math.floorDiv(blockX >> 4, CELL_CHUNKS), Math.floorDiv(blockZ >> 4, CELL_CHUNKS));
    }

    // middle of the candidate square (not of the whole cell: the offset is 0..22 so the middle is chunk 11, 184 blocks
    // in, not 216). that is where the structure is on average, so it is what "how far is this cell" measures to
    public static Point cellCentre(Cell cell) {
        return new Point(centreBlock(cell.cx(), CANDIDATE_MID_CHUNK), centreBlock(cell.cz(), CANDIDATE_MID_CHUNK));
    }

    // waypoints (nether block coords, chunk centres) covering the cell's candidate square, serpentine so
    // consecutive ones are one spacing apart
    public static List<Point> sweepWaypoints(Cell cell, int spacingChunks) {
        return sweepWaypoints(cell, spacingChunks, false, false);
    }

    // same grid, but flipped so the first waypoint is the corner of the grid nearest the player. a serpentine only
    // has four sensible starts and walking the far one first is a pointless hike across the cell
    public static List<Point> sweepWaypoints(Cell cell, int spacingChunks, int playerX, int playerZ) {
        int[] offs = offsets(spacingChunks);
        int last = offs.length - 1;
        long fromLow = dist2(playerX, playerZ, centreBlock(cell.cx(), offs[0]), centreBlock(cell.cz(), offs[0]));
        long fromHighX = dist2(playerX, playerZ, centreBlock(cell.cx(), offs[last]), centreBlock(cell.cz(), offs[0]));
        long fromHighZ = dist2(playerX, playerZ, centreBlock(cell.cx(), offs[0]), centreBlock(cell.cz(), offs[last]));
        long fromHighBoth = dist2(playerX, playerZ, centreBlock(cell.cx(), offs[last]), centreBlock(cell.cz(), offs[last]));
        // strict less so the unflipped order wins ties
        long best = fromLow;
        boolean flipX = false;
        boolean flipZ = false;
        if (fromHighX < best) {
            best = fromHighX;
            flipX = true;
            flipZ = false;
        }
        if (fromHighZ < best) {
            best = fromHighZ;
            flipX = false;
            flipZ = true;
        }
        if (fromHighBoth < best) {
            flipX = true;
            flipZ = true;
        }
        return sweepWaypoints(cell, spacingChunks, flipX, flipZ);
    }

    // nearest cell not in visitedKeys (Cell.key()) by distance from the player to the cell's centre. rings of cells
    // around the player's own cell are scanned outward, ties go to the cell met first in that spiral
    public static Optional<Cell> nextCell(int blockX, int blockZ, Set<String> visitedKeys) {
        Cell home = cellOf(blockX, blockZ);
        // ring k holds 8k cells so by ring visited.size() something is free, that is the hard stop
        int limit = visitedKeys.size() + 1;
        Cell best = null;
        long bestD = Long.MAX_VALUE;
        for (int k = 0; k <= limit; k++) {
            int[] cur = {0, 0};
            for (int i = 0; ; i++) {
                if (!ringCell(k, i, cur)) {
                    break;
                }
                Cell c = new Cell(home.cx() + cur[0], home.cz() + cur[1]);
                if (visitedKeys.contains(c.key())) {
                    continue;
                }
                Point mid = cellCentre(c);
                long d = dist2(blockX, blockZ, mid.x(), mid.z());
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
            if (best != null) {
                // the player can stand anywhere in the home cell so a farther ring can still win for a few rings,
                // 1.42 is sqrt 2 for the diagonal and the +1.3 is the half cell slop on each end
                limit = Math.min(limit, (int) Math.ceil(1.42 * k + 1.3));
            }
        }
        return Optional.ofNullable(best);
    }

    // i-th cell offset of the square ring k around (0,0), starting at the top left going clockwise-ish
    // (x increases along the top edge, then z down the right edge, then back along the bottom, then up the left).
    // returns false when i runs past the ring
    private static boolean ringCell(int k, int i, int[] out) {
        if (k == 0) {
            out[0] = 0;
            out[1] = 0;
            return i == 0;
        }
        int side = 2 * k;
        if (i >= 4 * side) {
            return false;
        }
        int edge = i / side;
        int t = i % side;
        switch (edge) {
            case 0 -> {
                out[0] = -k + t;
                out[1] = -k;
            }
            case 1 -> {
                out[0] = k;
                out[1] = -k + t;
            }
            case 2 -> {
                out[0] = k - t;
                out[1] = k;
            }
            default -> {
                out[0] = -k;
                out[1] = k - t;
            }
        }
        return true;
    }

    private static List<Point> sweepWaypoints(Cell cell, int spacingChunks, boolean flipX, boolean flipZ) {
        int[] offs = offsets(spacingChunks);
        int n = offs.length;
        List<Point> out = new ArrayList<>(n * n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                // odd columns walk back so the end of one column is next to the start of the next
                int jj = (i % 2 == 0) ? j : n - 1 - j;
                int ii = flipX ? n - 1 - i : i;
                int zz = flipZ ? n - 1 - jj : jj;
                out.add(new Point(centreBlock(cell.cx(), offs[ii]), centreBlock(cell.cz(), offs[zz])));
            }
        }
        return out;
    }

    // chunk offsets (inside the cell) of the grid lines, centred on the candidate square, strictly increasing
    private static int[] offsets(int spacingChunks) {
        int spacing = Math.max(1, spacingChunks);
        int cover = CANDIDATE_CHUNKS + STRUCTURE_MARGIN_CHUNKS;
        int n = Math.max(1, (cover + spacing - 1) / spacing);
        int[] raw = new int[n];
        int count = 0;
        for (int k = 0; k < n; k++) {
            int off = (int) Math.floor(CANDIDATE_MID_CHUNK + (k - (n - 1) / 2.0) * spacing + 0.5);
            // candidate chunks are 0..22, a waypoint outside that is walking for nothing (and into the next cell)
            off = Math.max(0, Math.min(CANDIDATE_CHUNKS - 1, off));
            if (count == 0 || raw[count - 1] != off) {
                raw[count++] = off;
            }
        }
        int[] out = new int[count];
        System.arraycopy(raw, 0, out, 0, count);
        return out;
    }

    private static int centreBlock(int cell, int chunkOffset) {
        return (cell * CELL_CHUNKS + chunkOffset) * 16 + 8;
    }

    private static long dist2(int ax, int az, int bx, int bz) {
        long dx = (long) ax - bx;
        long dz = (long) az - bz;
        return dx * dx + dz * dz;
    }

    private NetherComplexGrid() {
    }
}
