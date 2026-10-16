package adris.altoclef.tasks.construction.schematic;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.schematic.IStaticSchematic;
import baritone.utils.schematic.StaticSchematic;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SkipItemsTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static StaticSchematic row(BlockState... states) {
        BlockState[][][] s = new BlockState[states.length][1][1];
        for (int x = 0; x < states.length; x++) {
            s[x][0][0] = states[x];
        }
        return new StaticSchematic(s);
    }

    @Test
    public void cutsTheCellsWhoseItemIsOnTheList() {
        // the coloured blocks aren't plain fields on Blocks any more
        BlockState concrete = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getValue(net.minecraft.resources.Identifier.parse("minecraft:white_concrete")).defaultBlockState();
        IStaticSchematic s = SkipItems.of(row(Blocks.STONE.defaultBlockState(), concrete, AIR), Set.of("minecraft:white_concrete"));
        assertTrue(s.inSchematic(0, 0, 0, AIR));
        assertFalse(s.inSchematic(1, 0, 0, AIR));
        // air costs nothing, so it's never on anybody's list and stays in (the builder clears it)
        assertTrue(s.inSchematic(2, 0, 0, AIR));
        // the raw file is untouched for whoever reads it directly
        assertEquals(concrete, s.getDirect(1, 0, 0));
        assertEquals(concrete, s.desiredState(1, 0, 0, AIR, List.of()));
        assertEquals(3, s.widthX());
    }

    @Test
    public void aSecondHalfGoesWithItsFirstHalf() {
        // the top of a door costs nothing by itself, it's cut when the bottom is
        BlockState upper = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        BlockState lower = upper.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        SkipItems s = new SkipItems(row(lower, upper), Set.of("minecraft:oak_door"));
        assertTrue(s.skipped(upper));
        assertTrue(s.skipped(lower));
        assertFalse(s.inSchematic(1, 0, 0, AIR));
        assertFalse(new SkipItems(row(upper), Set.of("minecraft:spruce_door")).skipped(upper));
        // same for a bed's foot
        BlockState foot = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getValue(net.minecraft.resources.Identifier.parse("minecraft:red_bed")).defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.FOOT);
        assertTrue(new SkipItems(row(foot), Set.of("minecraft:red_bed")).skipped(foot));
        assertFalse(new SkipItems(row(foot), Set.of("minecraft:blue_bed")).skipped(foot));
    }

    @Test
    public void aPistonHeadGoesWithItsPiston() {
        BlockState head = Blocks.PISTON_HEAD.defaultBlockState();
        BlockState stickyHead = head.setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.TYPE,
            net.minecraft.world.level.block.state.properties.PistonType.STICKY);
        assertTrue(new SkipItems(row(head), Set.of("minecraft:piston")).skipped(head));
        assertFalse(new SkipItems(row(head), Set.of("minecraft:sticky_piston")).skipped(head));
        assertTrue(new SkipItems(row(stickyHead), Set.of("minecraft:sticky_piston")).skipped(stickyHead));
    }

    @Test
    public void aPotIsOnlyCutWhenBothItemsAre() {
        BlockState pot = Blocks.POTTED_POPPY.defaultBlockState();
        assertFalse(new SkipItems(row(pot), Set.of("minecraft:poppy")).skipped(pot));
        assertTrue(new SkipItems(row(pot), Set.of("minecraft:poppy", "minecraft:flower_pot")).skipped(pot));
    }

    @Test
    public void nothingToCutIsThePlainFile() {
        StaticSchematic plain = row(Blocks.STONE.defaultBlockState());
        assertSame(plain, SkipItems.of(plain, Set.of()));
    }
}
