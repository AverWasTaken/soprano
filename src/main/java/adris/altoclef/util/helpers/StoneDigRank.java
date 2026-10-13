package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// which stone to chew on next. plain distance is wrong for this and baritone's heuristic is worse (it thinks down is
// nearly free, so the nearest "stone" was always the floor and we dug a shaft, then had to climb out of it with cobble we
// are not allowed to place anymore). so: a walk cost like WalkCost, with everything below our feet priced as a hole,
// the block we stand on priced as a last resort, and buried stone priced above stone with an air face. pure, the caller
// reads the world and says if it is exposed
public final class StoneDigRank {
    // stone you mine for cobble. anything else (ores, logs, dirt) keeps its old pick
    private static final Set<Block> STONEISH = Set.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE,
            Blocks.COBBLED_DEEPSLATE, Blocks.BLACKSTONE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE);

    // on top of the 4 per block of height, for every block of it below the feet: that is the shaft
    public static final double BELOW_EXTRA = 6.0;
    // no air face means the pick has to open the block up first, and often it is the tail of a column we would tunnel down
    public static final double BURIED = 8.0;
    // the block under us (or the column below it): only wins when every other candidate costs more than this, which at
    // 4 vertical per block is roughly "nothing else within 16"
    public static final double STANDING_ON = 20.0;

    private StoneDigRank() {
    }

    // every block we were asked for is the kind we mine for cobble
    public static boolean stoneOnly(Block... blocks) {
        if (blocks.length == 0) {
            return false;
        }
        for (Block block : blocks) {
            if (!STONEISH.contains(block)) {
                return false;
            }
        }
        return true;
    }

    // per block of sand or gravel stacked on the stone, up to FALLING_CAP of them. mining it brings the stack down the hole
    // (mined from the side that is only a mess and a wait for it to land, from below it is the end of us), and any other
    // stone in the neighbourhood doesn't do that
    public static final double FALLING_EXTRA = 6.0;
    public static final int FALLING_CAP = 3;

    public static double score(double fromX, double fromY, double fromZ, int bx, int by, int bz, boolean exposed) {
        return score(fromX, fromY, fromZ, bx, by, bz, exposed, 0);
    }

    // lower is better. from is our feet (the exact position), the block is a cell. feet level and the cell above it are
    // free, up is 4 a block from there, down is 4 + BELOW_EXTRA a block. fallingAbove is how many falling blocks sit on it
    public static double score(double fromX, double fromY, double fromZ, int bx, int by, int bz, boolean exposed, int fallingAbove) {
        double dx = bx + 0.5 - fromX;
        double dz = bz + 0.5 - fromZ;
        // the 0.01 is for standing on a block top at exactly y.0 with float noise under it
        int feet = (int) Math.floor(fromY + 0.01);
        int dy = by - feet;
        double cost = Math.sqrt(dx * dx + dz * dz);
        if (dy < 0) {
            cost += (WalkCost.VERTICAL_WEIGHT + BELOW_EXTRA) * -dy;
            if ((int) Math.floor(fromX) == bx && (int) Math.floor(fromZ) == bz) {
                cost += STANDING_ON;
            }
        } else if (dy > 1) {
            cost += WalkCost.VERTICAL_WEIGHT * (dy - 1);
        }
        if (!exposed) {
            cost += BURIED;
        }
        cost += FALLING_EXTRA * Math.min(Math.max(fallingAbove, 0), FALLING_CAP);
        return cost;
    }

    // a stone and what score() gave it
    public record Scored(BlockPos pos, double score) {
    }

    // how much worse than the best a stone may score and still win by being hittable from right here. 2 is about two
    // blocks of extra walk, way less than BELOW_EXTRA or BURIED, so this never talks us down a shaft or into the floor.
    // it only breaks near-ties, which is the "walk to the dirt-covered one with a good one in reach" case
    public static final double REACH_MARGIN = 2.0;
    // reach raycasts per fresh pick, on top of the one for the best. the rest of the pick is cheap, these aren't
    public static final int REACH_CHECKS = 4;

    // the best is out of reach from where we stand: the first stone (by score) within margin of it that reachable says
    // yes to, checking at most cap of them. null when none of them is. pure, reachable is the caller's raycast
    public static BlockPos preferReachable(List<Scored> candidates, double bestScore, double margin, int cap, Predicate<BlockPos> reachable) {
        List<Scored> close = candidates.stream()
                .filter(c -> c.score() <= bestScore + margin)
                .sorted(Comparator.comparingDouble(Scored::score))
                .limit(cap)
                .toList();
        for (Scored c : close) {
            if (reachable.test(c.pos())) {
                return c.pos();
            }
        }
        return null;
    }
}
