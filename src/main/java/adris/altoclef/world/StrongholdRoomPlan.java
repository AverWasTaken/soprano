package adris.altoclef.world;

import java.util.List;
import java.util.Optional;
import java.util.Set;

// STUB of the contract (gamer-design.md 5.6/5.7). the estimator worker replaces the bodies
public final class StrongholdRoomPlan {
    public record Chunk(int cx, int cz) {
        public String key() {
            return cx + "," + cz;
        }
    }

    // every chunk within radius of start (square), nearest first, start itself first
    public static List<Chunk> spiralOrder(Chunk start, int radiusChunks) {
        return List.of(start);
    }

    // nearest not yet visited chunk of the spiral to where the player is, empty when everything is visited
    public static Optional<Chunk> next(Chunk start, int radiusChunks, Set<String> visitedKeys, Chunk player) {
        return Optional.empty();
    }

    private StrongholdRoomPlan() {
    }
}
