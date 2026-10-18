package adris.altoclef.tasks.construction.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

// the throwaway blocks we put down while building that the file doesn't want where they are. the builder is told to
// leave them be (IBuilderProcess.setKeep) so it stops mining the pillar out from under itself every few seconds, and
// they all come down once at the end instead. memory only: a restart forgets them, same as the rest of the build
public final class BuildScaffold {

    // a pillar next to the box is still ours. further out than this is the walk there, nobody wants that broken
    public static final int MARGIN = 8;

    // what the builder wants at a world cell, null when it wants nothing (outside the file, or a cell the file has
    // cut out, which the builder never touches either)
    @FunctionalInterface
    public interface Want {
        BlockState at(int x, int y, int z);
    }

    private final int x0;
    private final int y0;
    private final int z0;
    private final int x1;
    private final int y1;
    private final int z1;
    private final Want want;
    // (current, desired) -> is it right. BuilderProcess.valid in the game, so a substitute the builder takes isn't ours
    private final BiPredicate<BlockState, BlockState> valid;
    // BlockPos.asLong -> what we put there. concurrent because the path thread asks holds() through the builder's keep
    private final Map<Long, BlockState> blocks = new ConcurrentHashMap<>();

    // the box is the file's, inclusive at both ends
    public BuildScaffold(BlockPos min, BlockPos max, Want want, BiPredicate<BlockState, BlockState> valid) {
        this.x0 = min.getX();
        this.y0 = min.getY();
        this.z0 = min.getZ();
        this.x1 = max.getX();
        this.y1 = max.getY();
        this.z1 = max.getZ();
        this.want = want;
        this.valid = valid;
    }

    // a throwaway block went down at pos. true when it's scaffolding we now own
    public boolean offer(BlockPos pos, BlockState state) {
        if (!counts(pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }
        blocks.put(pos.asLong(), state);
        return true;
    }

    // the builder only ever places what the file wants, so a block that's right for its cell is the builder's (or
    // a pillar that happens to be the build, which is just the build now). wrong or outside, it's ours
    boolean counts(int x, int y, int z, BlockState state) {
        if (state == null || state.isAir() || !near(x, y, z)) {
            return false;
        }
        BlockState wanted = want.at(x, y, z);
        return wanted == null || !valid.test(state, wanted);
    }

    private boolean near(int x, int y, int z) {
        return x >= x0 - MARGIN && x <= x1 + MARGIN && y >= y0 - MARGIN && y <= y1 + MARGIN
            && z >= z0 - MARGIN && z <= z1 + MARGIN;
    }

    // still ours: we put it there and it's still that block. by block, not state: leaves change their distance the
    // tick they land, and a dirt pillar top in the light goes grassy. a pillar somebody mined and built over isn't
    public boolean holds(int x, int y, int z, BlockState current) {
        BlockState ours = blocks.get(BlockPos.asLong(x, y, z));
        return ours != null && same(ours, current);
    }

    static boolean same(BlockState ours, BlockState current) {
        if (current.is(ours.getBlock())) {
            return true;
        }
        return ours.is(Blocks.DIRT) && (current.is(Blocks.GRASS_BLOCK) || current.is(Blocks.MYCELIUM) || current.is(Blocks.PODZOL));
    }

    // any block we put there, whatever stands there now. key only, so the path thread can ask it
    public boolean contains(BlockPos pos) {
        return blocks.containsKey(pos.asLong());
    }

    public boolean holds(BlockPos pos, BlockState current) {
        return holds(pos.getX(), pos.getY(), pos.getZ(), current);
    }

    public void forget(BlockPos pos) {
        blocks.remove(pos.asLong());
    }

    // a copy, the caller may forget some on the way through
    public List<BlockPos> positions() {
        List<BlockPos> out = new ArrayList<>(blocks.size());
        blocks.keySet().forEach(key -> out.add(BlockPos.of(key)));
        return out;
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public int size() {
        return blocks.size();
    }

    // what the cleanup breaks itself: the ones in cells the builder will never touch (outside the file, cut out) and
    // the ones where the file wants air. nearest column first, then top down, so we never mine the block we stand on
    // before the one above it. the rest are where the file wants a real block, release() hands those to the builder
    public List<BlockPos> toBreak(BlockPos from) {
        List<BlockPos> out = new ArrayList<>();
        for (Long key : blocks.keySet()) {
            BlockPos pos = BlockPos.of(key);
            BlockState wanted = want.at(pos.getX(), pos.getY(), pos.getZ());
            if (wanted == null || wanted.isAir()) {
                out.add(pos);
            }
        }
        Comparator<BlockPos> order = Comparator.comparingLong(p -> flat(p, from));
        order = order.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ).thenComparingInt(p -> -p.getY());
        out.sort(order);
        return out;
    }

    // whole columns go by their xz distance, a pillar is one trip and not three
    private static long flat(BlockPos p, BlockPos from) {
        long dx = p.getX() - from.getX();
        long dz = p.getZ() - from.getZ();
        return dx * dx + dz * dz;
    }

    // stop keeping everything inside the file, the builder breaks it and puts the right block in. how many that was
    public int release() {
        int n = 0;
        for (Long key : blocks.keySet()) {
            BlockPos pos = BlockPos.of(key);
            if (want.at(pos.getX(), pos.getY(), pos.getZ()) != null) {
                blocks.remove(key);
                n++;
            }
        }
        return n;
    }
}
