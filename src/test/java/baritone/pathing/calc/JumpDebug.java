package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.sim.PlayerSim;
import baritone.pathing.tungsten.*;
import com.google.gson.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

// one jump off the course, flown a few times, every tick printed for the ones that fail. JUMP_DEBUG=x,y,z>x,y,z
public class JumpDebug {
    @Test
    public void debug() throws Exception {
        String json = System.getenv("PARKOURLAB_JSON"), jump = System.getenv("JUMP_DEBUG");
        Assume.assumeTrue(json != null && jump != null);
        ItemFixture.boot();
        BaritoneAPI.getSettings().considerPotionEffects.value = false;
        BaritoneAPI.getSettings().allowTungstenJumps.value = true;
        JsonObject course = JsonParser.parseString(Files.readString(Path.of(json))).getAsJsonObject();
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        for (JsonElement e : course.getAsJsonArray("blocks")) {
            JsonArray b = e.getAsJsonArray();
            w.set(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(),
                    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "minecraft:" + b.get(3).getAsString(), false).blockState());
        }
        String[] ends = jump.split(">");
        BetterBlockPos src = pos(ends[0]), dest = pos(ends[1]);
        CalculationContext ctx = new VineLogPathTest.Ctx(w, false);
        TungstenJumps.Jump j = TungstenJumps.between(ctx, src, dest);
        if (j == null && System.getenv("NEO_SIDE") != null) {
            // a searched neo: what the background search would hand the planner
            String[] sd = System.getenv("NEO_SIDE").split(",");
            Landing l0 = TungstenJumps.landing(ctx, dest);
            baritone.pathing.tungsten.TungstenSearch.Plan p = baritone.pathing.tungsten.NeoSearches.solve(new SafeCollision(w), src, src.y, l0,
                    baritone.pathing.tungsten.NeoSearches.region(src, l0),
                    baritone.pathing.tungsten.NeoSearches.via(src, l0, Integer.parseInt(sd[0]), Integer.parseInt(sd[1])));
            if (p != null) {
                j = new TungstenJumps.Jump(src, dest, 0, JumpPolicy.Variant.EDGE, p.states(), p);
            }
        }
        System.out.println("jump " + src + " -> " + dest + ": " + (j == null ? "none" : j.variant() + " cost " + j.cost() + (j.plan() != null ? " (search plan)" : "")));
        Landing land = TungstenJumps.landing(ctx, dest);
        SafeCollision world = new SafeCollision(w);
        for (long seed = 0; seed < 12; seed++) {
            Random rnd = new Random(seed);
            PlayerSim real = new PlayerSim(PlayerSim.Params.vanilla(), world);
            real.x = src.x + 0.5;
            real.y = src.y;
            real.z = src.z + 0.5;
            real.vy = -0.0784;
            real.onGround = true;
            JumpPilot pilot = new JumpPilot(land, src.x + 0.5, src.z + 0.5, JumpPolicy.Variant.EDGE);
            if (j != null) {
                pilot.fallback(j.plan());
            }
            float yaw = -90F;
            StringBuilder log = new StringBuilder();
            String why = "timeout";
            for (int t = 0; t < 300; t++) {
                JumpPilot.Decision d = pilot.tick(real);
                if (d.status() == JumpPilot.Status.DONE) {
                    why = null;
                    break;
                }
                if (d.status() != JumpPilot.Status.PRESS) {
                    why = d.status() + " " + d.why();
                    break;
                }
                PlayerSim.Controls k = d.keys();
                log.append(t).append(' ').append(SimState.of(real)).append(' ').append(k).append(" following=").append(pilot.following()).append('\n');
                double jitter = rnd.nextDouble() - 0.5;
                if (Math.abs(jitter) < 0.1) {
                    jitter *= 4;
                }
                yaw += Math.round(Mth.wrapDegrees(k.moveYaw() + (float) (jitter * 2.0) - yaw) / 0.15F) * 0.15F;
                if (!Keys.step(real, new PlayerSim.Controls(k.forward(), k.left(), k.jump(), k.sprint(), yaw, yaw))) {
                    why = "sim: " + real.failure();
                    break;
                }
            }
            System.out.println("seed " + seed + ": " + (why == null ? "ok" : why));
            if (why != null && seed < 12) {
                System.out.println(log);
            }
        }
    }

    private static BetterBlockPos pos(String s) {
        String[] p = s.split(",");
        return new BetterBlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
    }
}
