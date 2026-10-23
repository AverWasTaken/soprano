package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementTungsten;
import baritone.pathing.tungsten.TungstenJumps;
import net.minecraft.world.level.block.Blocks;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the planner with Tungsten jumps: courses of single blocks at angles the grid moves can't do, over nothing. same harness (and
// the same skip when the shadow BaritoneAPI isn't there) as VineLogPathTest
public class TungstenPathTest {

    private static final int FLOOR = 63;
    private Settings settings;
    private boolean parkour, tungsten, experimental, neos, momentum, climb, chat, potions;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        parkour = settings.allowParkour.value;
        tungsten = settings.allowTungstenJumps.value;
        experimental = settings.fastMode.value;
        neos = settings.allowNeos.value;
        momentum = settings.allowMomentumJumps.value;
        climb = settings.allowClimbJumps.value;
        chat = settings.chatDebug.value;
        potions = settings.considerPotionEffects.value;
        settings.considerPotionEffects.value = false;
        settings.allowParkour.value = true;
        settings.fastMode.value = false;
        settings.allowNeos.value = true;
        settings.allowMomentumJumps.value = true;
        settings.allowClimbJumps.value = true;
        settings.chatDebug.value = false;
    }

    @After
    public void tearDown() {
        if (settings == null) {
            return;
        }
        settings.allowParkour.value = parkour;
        settings.allowTungstenJumps.value = tungsten;
        settings.fastMode.value = experimental;
        settings.allowNeos.value = neos;
        settings.allowMomentumJumps.value = momentum;
        settings.allowClimbJumps.value = climb;
        settings.chatDebug.value = chat;
        settings.considerPotionEffects.value = potions;
    }

    // a 3x3 start pad round 0,0 and then single blocks at the given (x, dy, z) steps from the last one
    private static VineLogPathTest.MapWorld course(int[][] steps, BetterBlockPos[] end) {
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                w.set(x, FLOOR, z, Blocks.STONE.defaultBlockState());
            }
        }
        int x = 1, y = FLOOR, z = 1;
        for (int[] s : steps) {
            x += s[0];
            y += s[1];
            z += s[2];
            w.set(x, y, z, Blocks.STONE.defaultBlockState());
        }
        end[0] = new BetterBlockPos(x, y + 1, z);
        return w;
    }

    private static final BetterBlockPos START = new BetterBlockPos(0, FLOOR + 1, 0);

    // diagonal and knight's move jumps, one up and one down, nothing a straight parkour lands
    private static final int[][] DIAGONALS = {{2, 0, 2}, {3, 0, 2}, {2, 1, 2}, {-2, 0, 3}, {3, -1, 3}, {2, 0, -3}};

    @Test
    public void withoutTungstenThereIsNoWayAcross() {
        settings.allowTungstenJumps.value = false;
        BetterBlockPos[] end = new BetterBlockPos[1];
        VineLogPathTest.MapWorld w = course(DIAGONALS, end);
        VineLogPathTest.Result r = VineLogPathTest.path(w, START, new GoalBlock(end[0]), false);
        assertFalse("moves " + r.moves, r.found && r.inGoal);
    }

    @Test
    public void withTungstenTheCourseIsAPath() {
        settings.allowTungstenJumps.value = true;
        BetterBlockPos[] end = new BetterBlockPos[1];
        VineLogPathTest.MapWorld w = course(DIAGONALS, end);
        long t0 = System.nanoTime();
        VineLogPathTest.Result r = VineLogPathTest.path(w, START, new GoalBlock(end[0]), false);
        System.out.println("tungsten course: " + (System.nanoTime() - t0) / 1_000_000 + " ms, moves " + r.moves + " positions " + r.positions + " goal " + end[0]);
        assertTrue("moves " + r.moves, r.found && r.inGoal);
        assertTrue("moves " + r.moves, r.moves.stream().anyMatch(m -> m.startsWith("TUNGSTEN_")));
    }

    @Test
    public void straightJumpsStayParkour() {
        // a straight three gap is MovementParkour's. Tungsten only offers what the grid can't do
        settings.allowTungstenJumps.value = true;
        BetterBlockPos[] end = new BetterBlockPos[1];
        VineLogPathTest.MapWorld w = course(new int[][]{{3, 0, -1}, {3, 0, 0}}, end);
        VineLogPathTest.Result r = VineLogPathTest.path(w, START, new GoalBlock(end[0]), false);
        assertTrue("moves " + r.moves, r.found && r.inGoal);
        assertEquals("moves " + r.moves, "PARKOUR_EAST", r.moves.get(r.moves.size() - 1));
    }

    @Test
    public void theMovementAgreesWithThePlanner() {
        // calculateCost (what the executor rechecks every tick) prices the jump A* planned the same way
        settings.allowTungstenJumps.value = true;
        BetterBlockPos[] end = new BetterBlockPos[1];
        VineLogPathTest.MapWorld w = course(new int[][]{{2, 0, 2}}, end);
        CalculationContext ctx = new VineLogPathTest.Ctx(w);
        BetterBlockPos src = new BetterBlockPos(1, FLOOR + 1, 1);
        TungstenJumps.Jump j = TungstenJumps.get(ctx, src.x, src.y, src.z, TungstenJumps.bucket(2, 2));
        assertTrue(j != null && j.dest().equals(end[0]));
        CalculationContext fresh = new VineLogPathTest.Ctx(w);
        MovementTungsten m = MovementTungsten.cost(fresh, src, TungstenJumps.bucket(2, 2));
        assertEquals(end[0], m.getDest());
        assertEquals(j.cost(), m.calculateCost(new VineLogPathTest.Ctx(w)), 1e-9);
    }

    @Test
    public void flatGroundCostsNoSim() {
        // no drops anywhere, so no node is a takeoff and the sim never runs
        settings.allowTungstenJumps.value = true;
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                w.set(x, FLOOR, z, Blocks.STONE.defaultBlockState());
            }
        }
        long before = baritone.pathing.sim.PlayerSim.ticks(); // this thread's: a search left running in the background by another test counts in ticksRun
        VineLogPathTest.Result r = VineLogPathTest.path(w, START, new GoalBlock(new BetterBlockPos(15, FLOOR + 1, 12)), false);
        assertTrue(r.found && r.inGoal);
        assertEquals(before, baritone.pathing.sim.PlayerSim.ticks());
    }
}
