package adris.altoclef.util;

import adris.altoclef.util.helpers.CraftMath;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

// "how many can i craft from this inventory", the number that stops "craft all" from reaching for items we ran out of.
// strings stand in for items, the math is generic
public class CraftMathTest {

    private static final int ALL = 99999999;

    private static Map<String, Integer> inv(Object... pairs) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], (Integer) pairs[i + 1]);
        return result;
    }

    private static int crafts(List<List<String>> slots, Map<String, Integer> have, int cap) {
        return CraftMath.maxCrafts(slots, item -> have.getOrDefault(item, 0), cap);
    }

    @Test
    public void craftsForRoundsUp() {
        assertEquals(3, CraftMath.craftsFor(9, 4));
        assertEquals(1, CraftMath.craftsFor(1, 4));
        assertEquals(2, CraftMath.craftsFor(8, 4));
        assertEquals(11111111, CraftMath.craftsFor(ALL, 9));
        assertEquals(0, CraftMath.craftsFor(0, 4));
        assertEquals(5, CraftMath.craftsFor(5, 0));
    }

    @Test
    public void hayToWheatIsOneSlot() {
        List<List<String>> hayToWheat = Collections.singletonList(Collections.singletonList("hay"));
        assertEquals(0, crafts(hayToWheat, inv(), ALL));
        assertEquals(5, crafts(hayToWheat, inv("hay", 5), ALL));
        // the target caps it, we do not want to burn the stack
        assertEquals(2, crafts(hayToWheat, inv("hay", 5), 2));
    }

    @Test
    public void breadNeedsThreeWheatPerCraftFromTheSamePile() {
        List<String> wheat = Collections.singletonList("wheat");
        List<List<String>> bread = Arrays.asList(wheat, wheat, wheat);
        assertEquals(0, crafts(bread, inv("wheat", 2), ALL));
        assertEquals(1, crafts(bread, inv("wheat", 3), ALL));
        assertEquals(1, crafts(bread, inv("wheat", 5), ALL));
        assertEquals(21, crafts(bread, inv("wheat", 64), ALL));
    }

    @Test
    public void anyOfSeveralItemsFillsASlotButOneTypePerSlot() {
        // planks recipe from any log, one slot
        List<List<String>> planks = Collections.singletonList(Arrays.asList("oak_log", "birch_log"));
        assertEquals(7, crafts(planks, inv("oak_log", 3, "birch_log", 7), ALL));
        // a 2x2 of any planks: 4 slots, each one stacks a single type, so a crafting table is 4 planks per craft, not a pile of mixed ones doubling up
        List<String> anyPlank = Arrays.asList("oak_planks", "birch_planks");
        List<List<String>> table = Arrays.asList(anyPlank, anyPlank, anyPlank, anyPlank);
        assertEquals(1, crafts(table, inv("oak_planks", 4), ALL));
        assertEquals(1, crafts(table, inv("oak_planks", 3, "birch_planks", 1), ALL));
        assertEquals(0, crafts(table, inv("oak_planks", 3), ALL));
    }

    @Test
    public void everySlotHasToBeFillable() {
        // sticks style: planks over planks
        List<String> planks = Collections.singletonList("planks");
        List<List<String>> sticks = Arrays.asList(planks, planks);
        assertEquals(0, crafts(sticks, inv("planks", 1), ALL));
        assertEquals(2, crafts(sticks, inv("planks", 4), ALL));
        // a slot nothing can fill kills the recipe
        assertEquals(0, CraftMath.maxCrafts(Arrays.asList(planks, Collections.<String>emptyList()), item -> 64, ALL));
    }

    @Test
    public void mixedIngredientsAreLimitedByTheScarcest() {
        // bed-ish: 3 wool + 3 planks
        List<String> wool = Collections.singletonList("wool");
        List<String> planks = Collections.singletonList("planks");
        List<List<String>> bed = Arrays.asList(wool, wool, wool, planks, planks, planks);
        assertEquals(2, crafts(bed, inv("wool", 6, "planks", 30), ALL));
        assertEquals(0, crafts(bed, inv("wool", 6), ALL));
    }

    @Test
    public void noSlotsOrNoCapIsZero() {
        assertEquals(0, crafts(Collections.<List<String>>emptyList(), inv("x", 5), ALL));
        assertEquals(0, crafts(Collections.singletonList(Collections.singletonList("x")), inv("x", 5), 0));
    }

    @Test
    public void hugeCapDoesNotLoopForever() {
        List<List<String>> one = Collections.singletonList(Collections.singletonList("x"));
        assertEquals(Integer.MAX_VALUE, crafts(one, inv("x", Integer.MAX_VALUE), Integer.MAX_VALUE));
    }
}
