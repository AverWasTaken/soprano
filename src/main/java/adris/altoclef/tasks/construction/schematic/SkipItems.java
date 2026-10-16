package adris.altoclef.tasks.construction.schematic;

import baritone.api.schematic.IStaticSchematic;
import baritone.utils.schematic.library.MaterialList;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.PistonType;

import java.util.List;
import java.util.Map;
import java.util.Set;

// the file with the cells we can't get anything for cut out, so the builder never walks over to stare at them. same
// idea as the buildSkipBlocks MaskSchematic in BuilderProcess.start, but by item: a cell goes when every item it costs
// is on the list. a cut cell is outside the schematic, so whatever stands there is left alone too
public final class SkipItems implements IStaticSchematic {

    private final IStaticSchematic inner;
    private final Set<String> skip;

    public SkipItems(IStaticSchematic inner, Set<String> skip) {
        this.inner = inner;
        this.skip = Set.copyOf(skip);
    }

    // nothing to cut is the plain file, not a wrapper that asks every cell a question with one answer
    public static IStaticSchematic of(IStaticSchematic inner, Set<String> skip) {
        return skip.isEmpty() ? inner : new SkipItems(inner, skip);
    }

    public boolean skipped(BlockState state) {
        if (state == null) {
            return false;
        }
        Map<String, Integer> cost = MaterialList.itemsFor(paired(state));
        return !cost.isEmpty() && skip.containsAll(cost.keySet());
    }

    // a second half costs nothing by itself, so it goes by the half that pays for it. a door top whose bottom is cut
    // would otherwise stay in, and the builder would stand there trying to place half a door
    static BlockState paired(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        }
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return state.setValue(BedBlock.PART, BedPart.HEAD);
        }
        // an extended piston's head comes out of the piston, sticky or not
        if (state.getBlock() instanceof PistonHeadBlock) {
            return (state.getValue(PistonHeadBlock.TYPE) == PistonType.STICKY ? Blocks.STICKY_PISTON : Blocks.PISTON)
                .defaultBlockState();
        }
        return state;
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState current) {
        return inner.inSchematic(x, y, z, current) && !skipped(inner.desiredState(x, y, z, current, List.of()));
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        return inner.desiredState(x, y, z, current, approxPlaceable);
    }

    // map art reads the raw columns for its height map, so a cut cell still counts as the top of its column there.
    // the builder asks inSchematic before it places, so the cut holds anyway
    @Override
    public BlockState getDirect(int x, int y, int z) {
        return inner.getDirect(x, y, z);
    }

    @Override
    public BlockState[] getColumn(int x, int z) {
        return inner.getColumn(x, z);
    }

    @Override
    public void reset() {
        inner.reset();
    }

    @Override
    public int widthX() {
        return inner.widthX();
    }

    @Override
    public int heightY() {
        return inner.heightY();
    }

    @Override
    public int lengthZ() {
        return inner.lengthZ();
    }
}
