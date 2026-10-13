package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.ToolPick.Option;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

// item tags are not bound outside a world, so a real pickaxe would read as "wrong for everything" here. the options are
// built by hand with the numbers vanilla gives (tool speed / hardness / 30 when it is the right tool, / 100 when not)
public class ToolPickTest {
    private static final double OBSIDIAN = 50;

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    private static double progress(double hardness, double toolSpeed, boolean correct) {
        return toolSpeed / hardness / (correct ? 30 : 100);
    }

    private static final double HAND = progress(OBSIDIAN, 1, false);

    private static Option tool(double toolSpeed, boolean correct) {
        return new Option(true, correct, false, progress(OBSIDIAN, toolSpeed, correct));
    }

    private static final Option BUCKET = new Option(false, false, false, 0);

    @Test
    public void ironPickBeatsABucketOnObsidian() {
        // nothing below diamond is "correct" for obsidian, and this used to answer nothing at all
        List<Option> bag = List.of(BUCKET, tool(6, false), BUCKET);
        assertEquals(1, ToolPick.best(bag, HAND));
    }

    @Test
    public void noToolsAtAllIsStillNothing() {
        assertEquals(-1, ToolPick.best(List.of(BUCKET, BUCKET, BUCKET), HAND));
        assertEquals(-1, ToolPick.best(List.of(), HAND));
    }

    @Test
    public void aShovelIsNoBetterThanAHandOnObsidianSoThePickWins() {
        // a shovel does nothing to obsidian: speed 1, the same as the hand
        Option shovel = tool(1, false);
        assertEquals(1, ToolPick.best(List.of(shovel, tool(4, false)), HAND));
        // and the shovel alone is not worth a swap
        assertEquals(-1, ToolPick.best(List.of(shovel), HAND));
    }

    @Test
    public void theFastestWrongToolWins() {
        List<Option> bag = List.of(tool(2, false), tool(6, false), tool(4, false));
        assertEquals(1, ToolPick.best(bag, HAND));
    }

    @Test
    public void twoEquallyFastWrongToolsKeepTheFirst() {
        // an iron axe and an iron pick are the same speed on obsidian, the pick must not trade places with the axe
        assertEquals(0, ToolPick.best(List.of(tool(6, false), tool(6, false)), HAND));
    }

    @Test
    public void aCorrectToolStillWinsExactlyLikeBefore() {
        List<Option> bag = List.of(tool(6, true), tool(8, true), BUCKET);
        assertEquals(1, ToolPick.best(bag, HAND));
    }

    @Test
    public void aCorrectToolBeatsAFasterWrongOne() {
        // the right tool keeps the drops, the wrong one is only ever the fallback
        List<Option> bag = List.of(tool(9, false), tool(2, true));
        assertEquals(1, ToolPick.best(bag, HAND));
    }

    @Test
    public void shearsJumpTheQueueOnLeaves() {
        Option shears = new Option(false, false, true, 0);
        assertEquals(1, ToolPick.best(List.of(tool(6, true), shears, tool(8, true)), HAND));
    }

    @Test
    public void shearsOnABlockTheyDoNotFitAreJustAnItem() {
        Option shears = new Option(false, false, false, 0);
        assertEquals(-1, ToolPick.best(List.of(shears), HAND));
    }

    @Test
    public void aBlockThatCannotBeBrokenPicksNothing() {
        // calculateSpeedVsBlock says -1 for everything, the hand included
        Option nothing = new Option(true, false, false, -1);
        assertEquals(-1, ToolPick.best(List.of(nothing), -1));
    }

    @Test
    public void namesForTheLog() {
        assertEquals("iron_pickaxe", ToolPick.nameOf(new ItemStack(Items.IRON_PICKAXE)));
        assertEquals("bare hand", ToolPick.nameOf(ItemStack.EMPTY));
    }
}
