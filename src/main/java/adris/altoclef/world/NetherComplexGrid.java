package adris.altoclef.world;

import java.util.List;
import java.util.Optional;
import java.util.Set;

// STUB of the contract (gamer-design.md 5.4). the estimator worker replaces the bodies.
// fortress and bastion share one structure set: 27x27 chunk cells aligned to the world grid (nether coordinates),
// one candidate chunk per cell at offset 0..22 chunks per axis
public final class NetherComplexGrid {
    public static final int CELL_CHUNKS = 27;

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

    // nether block coordinates -> cell
    public static Cell cellOf(int blockX, int blockZ) {
        return new Cell(Math.floorDiv(blockX >> 4, CELL_CHUNKS), Math.floorDiv(blockZ >> 4, CELL_CHUNKS));
    }

    // waypoints (nether block coords) covering the cell's candidate square, in walking order
    public static List<Point> sweepWaypoints(Cell cell, int spacingChunks) {
        return List.of();
    }

    // nearest cell not in visitedKeys (Cell.key()), spiral tie breaks around the cell the player is in
    public static Optional<Cell> nextCell(int blockX, int blockZ, Set<String> visitedKeys) {
        return Optional.empty();
    }

    private NetherComplexGrid() {
    }
}
