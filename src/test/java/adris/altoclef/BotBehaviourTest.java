package adris.altoclef;

import baritone.altoclef.AltoClefSettings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class BotBehaviourTest {

    private final AltoClefSettings s = AltoClefSettings.getInstance();

    @After
    public void clean() {
        s.resetAll();
    }

    @Test
    public void syncLeavesAMatchingListAlone() {
        s.getBreakAvoiders().add(pos -> true);
        AltoClefSettings.Snapshot before = s.snapshot();
        // same predicates in the same order is what every applyState after the first one looks like
        BotBehaviour.sync(s.getBreakAvoiders(), new ArrayList<>(s.getBreakAvoiders()));
        assertSame(before, s.snapshot());
    }

    @Test
    public void syncRewritesAListThatDiffers() {
        s.getBreakAvoiders().add(pos -> true);
        AltoClefSettings.Snapshot before = s.snapshot();
        List<Predicate<BlockPos>> other = new ArrayList<>();
        other.add(pos -> false);
        BotBehaviour.sync(s.getBreakAvoiders(), other);
        assertNotSame(before, s.snapshot());
        assertFalse(s.shouldAvoidBreaking(new BlockPos(1, 2, 3)));
    }

    @Test
    public void syncOnASetOnlyCaresAboutMembership() {
        Set<BlockPos> want = new HashSet<>(Arrays.asList(new BlockPos(1, 1, 1), new BlockPos(2, 2, 2)));
        s.getBlocksToAvoidBreaking().addAll(want);
        AltoClefSettings.Snapshot before = s.snapshot();
        BotBehaviour.sync(s.getBlocksToAvoidBreaking(), want);
        assertSame(before, s.snapshot());

        BotBehaviour.sync(s.getBlocksToAvoidBreaking(), new HashSet<>(List.of(new BlockPos(9, 9, 9))));
        assertTrue(s.shouldAvoidBreaking(new BlockPos(9, 9, 9)));
        assertFalse(s.shouldAvoidBreaking(new BlockPos(1, 1, 1)));
    }

    // the walk to the table, mid recipe: one level saves 6 cobble (the craft), the collect task under it saves 6 too
    @Test
    public void reserveLevelsAddUp() {
        Map<String, Integer> craft = Map.of("cobble", 6);
        Map<String, Integer> collect = Map.of("cobble", 6, "dirt", 2);
        Map<String, Integer> out = BotBehaviour.sumReserves(List.of(craft, collect), List.of(Set.of(), Set.of()));
        assertEquals(12, (int) out.get("cobble"));
        assertEquals(2, (int) out.get("dirt"));
    }

    @Test
    public void aLevelThatProtectedWithoutANumberKeepsTheItemWhole() {
        // absent from the map is how the pathing side reads "all of it", whatever the other levels said
        Map<String, Integer> collect = Map.of("cobble", 6);
        Map<String, Integer> out = BotBehaviour.sumReserves(List.of(collect, Map.of()), List.of(Set.of(), Set.of("cobble")));
        assertFalse(out.containsKey("cobble"));
    }

    @Test
    public void aLevelThatPopsTakesItsNumberWithIt() {
        Map<String, Integer> craft = Map.of("cobble", 6);
        Map<String, Integer> collect = Map.of("cobble", 6);
        assertEquals(12, (int) BotBehaviour.sumReserves(List.of(craft, collect), List.of(Set.of(), Set.of())).get("cobble"));
        assertEquals(6, (int) BotBehaviour.sumReserves(List.of(craft), List.of(Set.of())).get("cobble"));
        assertTrue(BotBehaviour.sumReserves(List.of(), List.of()).isEmpty());
    }

    @Test
    public void reserveSumsStopAtAllOfIt() {
        Map<String, Integer> huge = Map.of("cobble", Integer.MAX_VALUE - 1);
        assertEquals(Integer.MAX_VALUE, (int) BotBehaviour.sumReserves(List.of(huge, huge), List.of(Set.of(), Set.of())).get("cobble"));
    }

    @Test
    public void syncOfTwoEmptyCollectionsDoesNotDirty() {
        s.getBreakAvoiders().add(pos -> true);
        AltoClefSettings.Snapshot before = s.snapshot();
        BotBehaviour.sync(s.getPlaceAvoiders(), new ArrayList<>());
        BotBehaviour.sync(s.getBlocksToAvoidBreaking(), new HashSet<>());
        assertSame(before, s.snapshot());
    }
}
