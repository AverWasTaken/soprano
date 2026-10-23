package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// no path to the goal yet, so the bot sets off on the best part of one: that part mustn't be a drop onto ground below the
// course with no way back up (a parkour map's floor, CubeCraft's candy level). only once it's been stuck right there with
// nothing left to work out does it go lower
public class HighGroundTest {

    private final java.util.Map<String, Object> saved = new java.util.HashMap<>();
    private static final String[] TOUCHED = {"allowParkour", "allowTungstenJumps", "allowBreak", "allowPlace", "considerPotionEffects", "chatDebug", "partialPathMaxDrop"};

    @Before
    public void setUp() {
        ItemFixture.boot();
        Settings s = BaritoneAPI.getSettings();
        for (String name : TOUCHED) {
            saved.put(name, s.byLowerName.get(name.toLowerCase()).value);
        }
        s.allowParkour.value = true;
        s.allowTungstenJumps.value = false;
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.considerPotionEffects.value = false;
        s.chatDebug.value = false;
        s.partialPathMaxDrop.value = 2;
    }

    @org.junit.After
    public void tearDown() {
        Settings s = BaritoneAPI.getSettings();
        for (java.util.Map.Entry<String, Object> e : saved.entrySet()) {
            @SuppressWarnings("unchecked")
            Settings.Setting<Object> setting = (Settings.Setting<Object>) s.byLowerName.get(e.getKey().toLowerCase());
            setting.value = e.getValue();
        }
    }

    // a ground floor, a start platform 3 up with a goal platform far across, out of reach: only the ground goes that way
    private static VineLogPathTest.MapWorld world(int ox) {
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        for (int x = ox - 4; x <= ox + 44; x++) {
            for (int z = -4; z <= 8; z++) {
                w.set(x, 60, z, Blocks.STONE.defaultBlockState());
            }
        }
        for (int x = ox; x <= ox + 2; x++) {
            for (int z = 1; z <= 3; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
                w.set(x + 36, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        return w;
    }

    @Test
    public void staysUpWhenTheOnlyWayOnIsDown() {
        int ox = 1000;
        VineLogPathTest.MapWorld w = world(ox);
        BetterBlockPos start = new BetterBlockPos(ox + 1, 64, 2);
        GoalBlock goal = new GoalBlock(ox + 37, 64, 2);
        VineLogPathTest.Result first = VineLogPathTest.path(w, start, goal, false);
        assertTrue("no drop to the ground the first time: " + first.positions, !first.found || lowest(first) >= 62);
        // stuck here again, and nothing's being worked out (other tests' background searches finished): now going lower is
        // all there is
        long until = System.currentTimeMillis() + 60_000;
        while (baritone.pathing.movement.movements.PendingTables.waiting() && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        VineLogPathTest.Result again = VineLogPathTest.path(w, start, goal, false);
        assertTrue("the second time it goes down: " + again.positions, again.found && lowest(again) < 62);
    }

    @Test
    public void offTurnsItOff() {
        int ox = 3000;
        BaritoneAPI.getSettings().partialPathMaxDrop.value = -1;
        VineLogPathTest.Result r = VineLogPathTest.path(world(ox), new BetterBlockPos(ox + 1, 64, 2), new GoalBlock(ox + 37, 64, 2), false);
        assertTrue(r.found && lowest(r) < 62);
    }

    @Test
    public void aGoalBelowIsFine() {
        // the goal's on the ground: down is the way, straight away
        int ox = 5000;
        VineLogPathTest.Result r = VineLogPathTest.path(world(ox), new BetterBlockPos(ox + 1, 64, 2), new GoalBlock(ox + 40, 61, 6), false);
        assertTrue(r.found && r.inGoal);
    }

    private static int lowest(VineLogPathTest.Result r) {
        int low = Integer.MAX_VALUE;
        for (BetterBlockPos p : r.positions) {
            low = Math.min(low, p.y);
        }
        return low;
    }
}
