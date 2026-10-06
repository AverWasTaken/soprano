package adris.altoclef;

import baritone.altoclef.AltoClefSettings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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

    @Test
    public void syncOfTwoEmptyCollectionsDoesNotDirty() {
        s.getBreakAvoiders().add(pos -> true);
        AltoClefSettings.Snapshot before = s.snapshot();
        BotBehaviour.sync(s.getPlaceAvoiders(), new ArrayList<>());
        BotBehaviour.sync(s.getBlocksToAvoidBreaking(), new HashSet<>());
        assertSame(before, s.snapshot());
    }
}
