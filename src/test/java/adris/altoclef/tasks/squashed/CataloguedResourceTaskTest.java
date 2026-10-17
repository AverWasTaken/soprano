package adris.altoclef.tasks.squashed;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemFixture;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

// which sibling the catalogued gather works on. a "task" here is just its targets, the counts are handed in, no game
public class CataloguedResourceTaskTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void planksLentToTheGridStillCount() {
        // birch first and short by the four sitting in the 2x2 for the stairs' crafting table. counting the grid it is met,
        // so the stairs keep the wheel instead of birch yanking its planks back out
        ItemTarget[] birch = {new ItemTarget(Items.BIRCH_PLANKS, 112)};
        ItemTarget[] stairs = {new ItemTarget(Items.SPRUCE_STAIRS, 57)};
        Map<Item, Integer> inventory = Map.of(Items.BIRCH_PLANKS, 108);
        Map<Item, Integer> grid = Map.of(Items.BIRCH_PLANKS, 4);
        List<ItemTarget[]> tasks = List.of(birch, stairs);

        assertSame(birch, CataloguedResourceTask.firstUnmet(tasks, t -> t, t -> count(t, inventory)));
        assertSame(stairs, CataloguedResourceTask.firstUnmet(tasks, t -> t, t -> count(t, inventory) + count(t, grid)));
    }

    @Test
    public void allMetIsNull() {
        ItemTarget[] birch = {new ItemTarget(Items.BIRCH_PLANKS, 4)};
        Map<Item, Integer> held = Map.of(Items.BIRCH_PLANKS, 4);
        assertNull(CataloguedResourceTask.firstUnmet(List.<ItemTarget[]>of(birch), t -> t, t -> count(t, held)));
    }

    private static int count(ItemTarget target, Map<Item, Integer> where) {
        int n = 0;
        for (Item item : target.getMatches()) {
            n += where.getOrDefault(item, 0);
        }
        return n;
    }
}
