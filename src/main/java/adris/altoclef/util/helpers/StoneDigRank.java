package adris.altoclef.util.helpers;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

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

    // lower is better. from is our feet (the exact position), the block is a cell. feet level and the cell above it are
    // free, up is 4 a block from there, down is 4 + BELOW_EXTRA a block
    public static double score(double fromX, double fromY, double fromZ, int bx, int by, int bz, boolean exposed) {
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
        return cost;
    }
}
