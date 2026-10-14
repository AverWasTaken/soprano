package baritone.pathing.calc;

import adris.altoclef.tasks.construction.GoalMineFromSide;
import adris.altoclef.util.helpers.ItemFixture;
import adris.altoclef.util.helpers.TreeReach;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a log up in the canopy with nothing under it, and whether the planner can get a swing at it. the answer decides what
// MineAndCollectTask does about such logs: with something to place there is a tower, without it there is nothing to find.
// same harness (and the same skip when the shadow BaritoneAPI isn't there) as VineLogPathTest
public class CanopyTowerPathTest {
    private static final int FLOOR = 63;

    private Settings settings;
    private boolean parkour, climb, neos, experimental, sprintJumping, headHitters, potions, chat;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        parkour = settings.allowParkour.value;
        climb = settings.allowClimbJumps.value;
        neos = settings.allowNeos.value;
        experimental = settings.fastMode.value;
        sprintJumping = settings.sprintJumping.value;
        headHitters = settings.headHitters.value;
        potions = settings.considerPotionEffects.value;
        chat = settings.chatDebug.value;
        settings.allowParkour.value = true;
        settings.allowClimbJumps.value = true;
        settings.allowNeos.value = true;
        settings.fastMode.value = true;
        settings.sprintJumping.value = true;
        settings.headHitters.value = true;
        settings.considerPotionEffects.value = false;
        settings.chatDebug.value = false;
    }

    @After
    public void tearDown() {
        if (settings == null) {
            return;
        }
        settings.allowParkour.value = parkour;
        settings.allowClimbJumps.value = climb;
        settings.allowNeos.value = neos;
        settings.fastMode.value = experimental;
        settings.sprintJumping.value = sprintJumping;
        settings.headHitters.value = headHitters;
        settings.considerPotionEffects.value = potions;
        settings.chatDebug.value = chat;
    }

    // flat grass, one log hanging at logY with nothing under it. leaves: a ring of them around the log, a layer under
    // and a layer over, like the middle of a canopy
    private static VineLogPathTest.MapWorld canopy(int logY, boolean leaves) {
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                w.set(x, FLOOR, z, Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        w.set(0, logY, 0, Blocks.OAK_LOG.defaultBlockState());
        if (leaves) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    for (int y = logY - 1; y <= logY + 1; y++) {
                        if (x != 0 || z != 0 || y != logY) {
                            w.set(x, y, z, Blocks.OAK_LEAVES.defaultBlockState());
                        }
                    }
                }
            }
        }
        return w;
    }

    // the goal DestroyBlockTask builds for it: openBelow is the air under the log, capped
    private static GoalMineFromSide goalFor(BlockPos log) {
        return new GoalMineFromSide(log, true, Math.min(GoalMineFromSide.MAX_UNDER, log.getY() - FLOOR - 1));
    }

    private static boolean got(VineLogPathTest.Result r) {
        return r.found && r.inGoal;
    }

    @Test
    public void theReachRuleMatchesTheShaftTheGoalAllows() {
        // the predicate stands in for the goal, so they have to agree on where the line is
        // from gap 2: a log closer than that is a side swing, not the shaft
        for (int logY = FLOOR + 3; logY <= FLOOR + 10; logY++) {
            int gap = TreeReach.gap(logY, FLOOR);
            assertEquals("log at " + logY, gap <= GoalMineFromSide.MAX_UNDER, TreeReach.fromGround(logY, FLOOR, false));
            // feet on the floor are FLOOR + 1, and the shaft goal takes them while the log is at most MAX_UNDER over them
            GoalMineFromSide goal = goalFor(new BlockPos(0, logY, 0));
            assertEquals("log at " + logY, TreeReach.fromGround(logY, FLOOR, false), goal.isInGoal(0, FLOOR + 1, 0));
        }
        assertEquals(GoalMineFromSide.MAX_UNDER, TreeReach.MAX_UNDER);
    }

    @Test
    public void aLogJustInsideTheShaftIsAWalkAndNoTower() {
        BlockPos log = new BlockPos(0, FLOOR + 1 + GoalMineFromSide.MAX_UNDER, 0);
        VineLogPathTest.MapWorld w = canopy(log.getY(), false);
        VineLogPathTest.Result r = VineLogPathTest.path(w, new BetterBlockPos(4, FLOOR + 1, 0), goalFor(log), false);
        assertTrue("moves " + r.moves, got(r));
        assertFalse("moves " + r.moves, r.moves.contains("PILLAR"));
    }

    @Test
    public void aLogOneHigherIsOutOfReachWithNothingToPlace() {
        BlockPos log = new BlockPos(0, FLOOR + 2 + GoalMineFromSide.MAX_UNDER, 0);
        VineLogPathTest.MapWorld w = canopy(log.getY(), false);
        assertFalse(got(VineLogPathTest.path(w, new BetterBlockPos(4, FLOOR + 1, 0), goalFor(log), false)));
        VineLogPathTest.MapWorld leafy = canopy(log.getY(), true);
        assertFalse(got(VineLogPathTest.path(leafy, new BetterBlockPos(4, FLOOR + 1, 0), goalFor(log), false)));
    }

    @Test
    public void withBlocksToPlaceTheSamePlannerGetsToIt() {
        for (int up = 2; up <= 4; up++) {
            BlockPos log = new BlockPos(0, FLOOR + up + GoalMineFromSide.MAX_UNDER, 0);
            VineLogPathTest.MapWorld w = canopy(log.getY(), false);
            VineLogPathTest.Result r = VineLogPathTest.path(w, new BetterBlockPos(4, FLOOR + 1, 0), goalFor(log), true);
            assertTrue("log at " + log.getY() + " moves " + r.moves, got(r));
            // one block to step up on is all the shaft needs when the log is a single cell too high. past that it is a tower
            if (up >= 3) {
                assertTrue("log at " + log.getY() + " moves " + r.moves, r.moves.contains("PILLAR"));
            }
        }
    }

    @Test
    public void aTowerThroughTheLeavesIsStillAWay() {
        BlockPos log = new BlockPos(0, FLOOR + 3 + GoalMineFromSide.MAX_UNDER, 0);
        VineLogPathTest.MapWorld w = canopy(log.getY(), true);
        VineLogPathTest.Result r = VineLogPathTest.path(w, new BetterBlockPos(4, FLOOR + 1, 0), goalFor(log), true);
        assertTrue("moves " + r.moves, got(r));
        assertTrue("moves " + r.moves, r.moves.contains("PILLAR"));
    }
}
