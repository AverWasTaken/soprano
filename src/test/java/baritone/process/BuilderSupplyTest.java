package baritone.process;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the "do i have this" check the builder does before it walks over to place something. approxPlaceable hands it the
// state each stack would place with the player facing wherever they face, which is rarely the way the schematic wants
public class BuilderSupplyTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static BlockState stairs(Direction facing) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
    }

    @Test
    public void stairsFacingTheOtherWayStillCount() {
        List<BlockState> inventory = List.of(Blocks.AIR.defaultBlockState(), stairs(Direction.NORTH));
        // owning them is not the same as being able to place them that way, so the placeable check stays exact
        assertFalse(BuilderProcess.containsBlockState(inventory, stairs(Direction.SOUTH)));
        assertTrue(BuilderProcess.containsBlockState(inventory, stairs(Direction.NORTH)));
        assertTrue(BuilderProcess.containsItem(inventory, stairs(Direction.SOUTH)));
        assertTrue(BuilderProcess.canSupply(stairs(Direction.NORTH), stairs(Direction.WEST)));
    }

    @Test
    public void exactMatchStillCounts() {
        List<BlockState> inventory = List.of(Blocks.STONE.defaultBlockState());
        assertTrue(BuilderProcess.containsBlockState(inventory, Blocks.STONE.defaultBlockState()));
    }

    @Test
    public void halfAndShapeDontMatterEither() {
        BlockState top = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.TOP);
        assertTrue(BuilderProcess.sameItem(Blocks.OAK_STAIRS.defaultBlockState(), top));
    }

    @Test
    public void aDifferentItemDoesNotCount() {
        List<BlockState> inventory = List.of(stairs(Direction.NORTH), Blocks.STONE.defaultBlockState());
        assertFalse(BuilderProcess.containsItem(inventory, Blocks.SPRUCE_STAIRS.defaultBlockState()));
        assertFalse(BuilderProcess.canSupply(Blocks.STONE.defaultBlockState(), stairs(Direction.NORTH)));
    }

    @Test
    public void emptySlotsNeverSupplyAnything() {
        // an empty slot shows up as air and so does a schematic gap, those must not "match" each other
        BlockState air = Blocks.AIR.defaultBlockState();
        assertFalse(BuilderProcess.sameItem(air, air));
        assertFalse(BuilderProcess.sameItem(air, Blocks.STONE.defaultBlockState()));
        assertFalse(BuilderProcess.sameItem(Blocks.STONE.defaultBlockState(), air));
    }

    @Test
    public void wallVariantsRideOnTheirStandingItem() {
        // a wall torch is placed with a plain torch item
        BlockState wall = Blocks.WALL_TORCH.defaultBlockState();
        assertTrue(BuilderProcess.sameItem(Blocks.TORCH.defaultBlockState(), wall));
    }
}
