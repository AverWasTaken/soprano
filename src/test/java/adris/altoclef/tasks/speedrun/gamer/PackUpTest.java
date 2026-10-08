package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.container.CollectFromFurnaceTask.Mode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

// leaving the mine with something still cooking at the bottom of it
public class PackUpTest {
    private static RunState.FurnaceJob job(int x, int y, int z, long doneTick) {
        return new RunState.FurnaceJob(new RunState.Pos(x, y, z), "OVERWORLD", "furnace", "raw_iron", 3, "iron_ingot", 0, doneTick);
    }

    @Test
    public void onlyWhatIsReallyDownTheMineIsStranded() {
        assertFalse(PackUp.stranded(0));
        assertFalse(PackUp.stranded(SmeltSurface.GO_UP_DEPTH));
        assertTrue(PackUp.stranded(SmeltSurface.GO_UP_DEPTH + 1));
        // the smoker at y 32 under a surface around 70
        assertTrue(PackUp.stranded(SmeltSurface.depth(70, 32)));
    }

    @Test
    public void aJobAboutToFinishIsWaitedForAndAFarOneIsTakenBack() {
        int depth = 30;
        // done a few seconds from now: stand there and take it
        assertEquals(Mode.WAIT_ALL, PackUp.mode(100, depth));
        assertEquals(Mode.WAIT_ALL, PackUp.mode(-50, depth));
        // two minutes to go: the contents and the station come with us
        assertEquals(Mode.TAKE_ALL, PackUp.mode(2400, depth));
        // the wait is never under 10 s and never over 30 s, whatever the depth
        assertEquals(200, PackUp.waitTicks(1));
        assertEquals(600, PackUp.waitTicks(500));
        assertTrue(PackUp.waitTicks(20) > PackUp.waitTicks(10));
        assertEquals(Mode.WAIT_ALL, PackUp.mode(200, 2));
        assertEquals(Mode.TAKE_ALL, PackUp.mode(201, 2));
    }

    @Test
    public void thePickIsTheDeepJobThatIsReadyFirst() {
        RunState.FurnaceJob up = job(0, 70, 0, 100);
        RunState.FurnaceJob deepLate = job(5, 30, 0, 900);
        RunState.FurnaceJob deepSoon = job(9, 32, 0, 400);
        List<RunState.FurnaceJob> jobs = List.of(up, deepLate, deepSoon);
        RunState.FurnaceJob got = PackUp.pick(jobs, new HashSet<>(), j -> 70 - j.pos.y, j -> 10);
        assertSame(deepSoon, got);
    }

    @Test
    public void aSurfaceJobIsLeftToTheNormalCollect() {
        List<RunState.FurnaceJob> jobs = List.of(job(0, 68, 0, 100));
        assertNull(PackUp.pick(jobs, new HashSet<>(), j -> 2, j -> 3));
    }

    @Test
    public void aTripAlreadyMadeIsNotRepeated() {
        RunState.FurnaceJob deep = job(5, 30, 0, 900);
        Set<RunState.Pos> tried = new HashSet<>();
        assertSame(deep, PackUp.pick(List.of(deep), tried, j -> 40, j -> 10));
        tried.add(deep.pos);
        assertNull(PackUp.pick(List.of(deep), tried, j -> 40, j -> 10));
    }

    @Test
    public void aFurnaceOnTheOtherSideOfTheWorldIsNotWorthTheTripNow() {
        RunState.FurnaceJob far = job(400, 30, 0, 900);
        assertNull(PackUp.pick(List.of(far), new HashSet<>(), j -> 40, j -> PackUp.walk(j, 0.5, 30, 0.5)));
        // 12 blocks down is 48 of walking, inside the stretched budget
        RunState.FurnaceJob near = job(0, 18, 0, 900);
        assertSame(near, PackUp.pick(List.of(near), new HashSet<>(), j -> 52, j -> PackUp.walk(j, 0.5, 30, 0.5)));
    }
}
