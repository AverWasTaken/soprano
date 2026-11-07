package adris.altoclef.ui.gui.hud;

import adris.altoclef.ui.gui.kit.Icon;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TaskIconsTest {

    @Test
    public void theMockChainGetsTheMockIcons() {
        assertEquals(Icon.GOTO, TaskIcons.forName("Getting blocks for the path"));
        assertEquals(Icon.GOTO, TaskIcons.forName("Getting a stone pickaxe for the path"));
        assertEquals(Icon.GET, TaskIcons.forName("Mine and collect"));
        assertEquals(Icon.PICKAXE, TaskIcons.forName("Mining dirt"));
        assertEquals(Icon.PICKAXE, TaskIcons.forName("Breaking dirt"));
    }

    @Test
    public void phrasesBeatTheVerb() {
        assertEquals(Icon.EAT, TaskIcons.forName("Getting food"));
        assertEquals(Icon.WATER, TaskIcons.forName("Breaking a fall"));
        assertEquals(Icon.WATER, TaskIcons.forName("Filling 3 Buckets with water"));
        assertEquals(Icon.EYE, TaskIcons.forName("Following an Eye of Ender"));
        assertEquals(Icon.RUN, TaskIcons.forName("Getting out of the lava"));
    }

    @Test
    public void verbs() {
        assertEquals(Icon.CRAFT, TaskIcons.forName("Crafting 4 Oak Planks"));
        assertEquals(Icon.BLOCK, TaskIcons.forName("Building a Nether portal"));
        assertEquals(Icon.EQUIP, TaskIcons.forName("Hunting Blazes for 7 Blaze Rods"));
        assertEquals(Icon.SEARCH, TaskIcons.forName("Finding the stronghold"));
        assertEquals(Icon.GOTO, TaskIcons.forName("Going through a Nether portal"));
        assertEquals(Icon.BACKPACK, TaskIcons.forName("Storing items in a chest"));
        assertEquals(Icon.CLOCK, TaskIcons.forName("Waiting for the furnace"));
        assertEquals(Icon.EAT, TaskIcons.forName("Eating"));
        assertEquals(Icon.GET, TaskIcons.forName("Collecting 64 cobblestone"));
        assertEquals(Icon.RUN, TaskIcons.forName("Escaping lava"));
        assertEquals(Icon.FOLLOW, TaskIcons.forName("Following Steve"));
    }

    @Test
    public void unknownIsADot() {
        assertEquals(Icon.DOT, TaskIcons.forName("Acting like SCP 173"));
        assertEquals(Icon.DOT, TaskIcons.forName(""));
        assertEquals(Icon.DOT, TaskIcons.forName(null));
        assertEquals(Icon.DOT, TaskIcons.forName("12 apples"));
    }
}
