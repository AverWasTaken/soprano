package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.sim.PlayerSim;
import baritone.pathing.tungsten.JumpPilot;
import baritone.pathing.tungsten.JumpPolicy;
import baritone.pathing.tungsten.Keys;
import baritone.pathing.tungsten.Landing;
import baritone.pathing.tungsten.SafeCollision;
import baritone.pathing.tungsten.SimState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// plan a course of any angle jumps with A*, then fly every jump of it one after the other with the pilot, against a player
// sim on the same blocks (through SafeCollision, like the game) with the aim jittering like LookBehavior's. the planner and
// the executor have to agree for this to land
public class TungstenCourseFlightTest {

    private static final int FLOOR = 63;
    private Settings settings;
    private boolean parkour, tungsten, experimental, chat, potions;

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
        chat = settings.chatDebug.value;
        potions = settings.considerPotionEffects.value;
        settings.allowParkour.value = true;
        settings.allowTungstenJumps.value = true;
        settings.fastMode.value = false;
        settings.chatDebug.value = false;
        settings.considerPotionEffects.value = false;
    }

    @After
    public void tearDown() {
        if (settings == null) {
            return;
        }
        settings.allowParkour.value = parkour;
        settings.allowTungstenJumps.value = tungsten;
        settings.fastMode.value = experimental;
        settings.chatDebug.value = chat;
        settings.considerPotionEffects.value = potions;
    }

    @Test
    public void aCourseOfAngledJumpsIsFlownEndToEnd() {
        // single blocks, each an angle the grid moves don't jump, over nothing at all
        int[][] steps = {{2, 0, 2}, {3, 0, 1}, {2, 1, 2}, {-2, 0, 3}, {3, -1, 2}, {1, 0, -3}, {3, 0, 3}};
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        int x = 0, y = FLOOR, z = 0;
        w.set(x, y, z, Blocks.STONE.defaultBlockState());
        for (int[] s : steps) {
            x += s[0];
            y += s[1];
            z += s[2];
            w.set(x, y, z, Blocks.STONE.defaultBlockState());
        }
        BetterBlockPos start = new BetterBlockPos(0, FLOOR + 1, 0), goal = new BetterBlockPos(x, y + 1, z);
        VineLogPathTest.Result r = VineLogPathTest.path(w, start, new GoalBlock(goal), false);
        assertTrue("moves " + r.moves, r.found && r.inGoal);
        // the planner may well find a straight parkour between two of them as well. the pilot flies any jump, so those too
        assertTrue("moves " + r.moves, r.moves.stream().anyMatch(m -> m.startsWith("TUNGSTEN_")));
        for (String m : r.moves) {
            assertTrue("moves " + r.moves, m.startsWith("TUNGSTEN_") || m.startsWith("PARKOUR_"));
        }
        SafeCollision world = new SafeCollision(w);
        for (long seed = 0; seed < 12; seed++) {
            Random rnd = new Random(seed);
            PlayerSim real = new PlayerSim(PlayerSim.Params.vanilla(), world);
            real.x = 0.5;
            real.y = FLOOR + 1;
            real.z = 0.5;
            real.vy = -0.0784;
            real.onGround = true;
            float yaw = 0F;
            int total = 0;
            for (int i = 0; i + 1 < r.positions.size(); i++) {
                BetterBlockPos src = r.positions.get(i), dest = r.positions.get(i + 1);
                JumpPilot pilot = new JumpPilot(new Landing(dest.x, dest.y, dest.z), src.x + 0.5, src.z + 0.5, JumpPolicy.Variant.EDGE);
                boolean done = false;
                for (int t = 0; t < 200 && !done; t++, total++) {
                    JumpPilot.Decision d = pilot.tick(real);
                    if (d.status() == JumpPilot.Status.DONE) {
                        done = true;
                        break;
                    }
                    assertEquals("seed " + seed + " jump " + src + " -> " + dest + ": " + d.why() + " at " + SimState.of(real),
                            JumpPilot.Status.PRESS, d.status());
                    PlayerSim.Controls k = d.keys();
                    double jitter = rnd.nextDouble() - 0.5;
                    if (Math.abs(jitter) < 0.1) {
                        jitter *= 4;
                    }
                    float desired = k.moveYaw() + (float) (jitter * 2.0);
                    yaw += Math.round(Mth.wrapDegrees(desired - yaw) / 0.15F) * 0.15F;
                    assertTrue(real.failure(), Keys.step(real, new PlayerSim.Controls(k.forward(), k.left(), k.jump(), k.sprint(), yaw, yaw)));
                }
                assertTrue("seed " + seed + " never finished " + src + " -> " + dest, done);
            }
            assertTrue(new Landing(goal.x, goal.y, goal.z).holds(real.x, real.y, real.z) && real.onGround);
            if (seed == 0) {
                System.out.println("course of " + r.moves.size() + " jumps flown in " + total + " ticks");
            }
        }
    }
}
