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
        System.out.println("neoSide " + java.util.Arrays.toString(TungstenJumps.neoSide(ctx, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z))
                + " cornerVia " + java.util.Arrays.toString(TungstenJumps.cornerVia(ctx, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z)));
        System.out.println("takeoff " + TungstenJumps.takeoff(ctx, src.x, src.y, src.z) + " candidate " + TungstenJumps.candidate(ctx, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z, false));
        if (System.getenv("WATER_ROLL") != null) {
            // out of a water block: every variant, every tick
            baritone.pathing.tungsten.Landing wl = TungstenJumps.landing(ctx, dest);
            System.out.println("landing " + wl);
            {
                SafeCollision bw = new SafeCollision(w);
                double[] st1 = TungstenJumps.standAt(bw, src.x, src.y, src.z);
                System.out.println("blocked " + JumpPolicy.blocked(bw, src.x + st1[0], Double.isNaN(st1[2]) ? src.y : st1[2], src.z + st1[1], wl.centerX(), wl.y(), wl.centerZ()));
                for (int by = src.y; by <= wl.y() + 2; by++) {
                    for (int bx = Math.min(src.x, wl.x()) - 1; bx <= Math.max(src.x, wl.x()) + 1; bx++) {
                        for (int bz = Math.min(src.z, wl.z()) - 1; bz <= Math.max(src.z, wl.z()) + 1; bz++) {
                            baritone.pathing.sim.BoxList bl = new baritone.pathing.sim.BoxList();
                            bw.boxes(bx, by, bz, bl);
                            if (bl.size() > 0) System.out.println("  box at " + bx + " " + by + " " + bz + ": " + bl.get(0, 1) + ".." + bl.get(0, 4));
                        }
                    }
                }
            }
            java.util.function.Supplier<PlayerSim> mk = () -> {
                PlayerSim ws = new PlayerSim(PlayerSim.Params.vanilla(), new SafeCollision(w));
                ws.x = src.x + 0.5; ws.y = src.y + 0.2; ws.z = src.z + 0.5;
                if (System.getenv("STAND") != null) {
                    double[] st0 = TungstenJumps.standAt(new SafeCollision(w), src.x, src.y, src.z);
                    ws.x = src.x + st0[0]; ws.y = st0[2]; ws.z = src.z + st0[1]; ws.vy = -0.0784; ws.onGround = true;
                }
                return ws;
            };
            for (JumpPolicy.Variant v : JumpPolicy.variants(mk.get(), wl)) {
                PlayerSim ws = mk.get();
                JumpPolicy.Rollout wr = JumpPolicy.rollout(ws, wl, v);
                System.out.println("variant " + v + ": ok " + wr.ok() + " why " + wr.why() + " ticks " + wr.states().size());
                if (System.getenv("WATER_TICKS") != null) {
                    for (int i = 0; i < wr.states().size(); i++) {
                        System.out.println("  " + i + " " + wr.states().get(i) + " keys " + (i < wr.keys().size() ? wr.keys().get(i) : null));
                    }
                }
            }
        }
        if (System.getenv("ROLL") != null) {
            PlayerSim rs = new PlayerSim(PlayerSim.Params.vanilla(), new SafeCollision(w));
            rs.x = src.x + 0.5; rs.y = src.y; rs.z = src.z + 0.5; rs.vy = -0.0784; rs.onGround = true;
            JumpPolicy.Rollout rr = JumpPolicy.best(rs, TungstenJumps.landing(ctx, dest));
            System.out.println("best: " + (rr == null ? "none" : rr.variant() + " ok " + rr.ok()));
            if (rr != null) {
                for (int i = 0; i < rr.states().size(); i++) {
                    SimState q = rr.states().get(i);
                    if (q.horizontalCollision) System.out.println("  bump at " + i + ": " + q + " minor " + q.minorHorizontalCollision);
                }
            }
        }
        if (System.getenv("BUCKETS") != null) {
            ctx.tungsten().newSearch(10_000_000);
            for (int pass = 0; pass < 3; pass++) {
                for (int b = 0; b < TungstenJumps.BUCKETS; b++) {
                    TungstenJumps.Jump bj = TungstenJumps.get(ctx, src.x, src.y, src.z, b);
                    if (pass == 2) System.out.println("bucket " + b + ": " + (bj == null ? "-" : bj.dest() + " " + bj.cost()));
                }
                Thread.sleep(15000);
                ctx.tungsten().newSearch(10_000_000);
            }
        }
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
            double[] st = TungstenJumps.standAt(world, src.x, src.y, src.z);
            real.x = src.x + st[0] + Double.parseDouble(System.getenv().getOrDefault("START_DX", "0"));
            real.y = Double.isNaN(st[2]) ? src.y : st[2];
            real.z = src.z + st[1];
            real.vy = -0.0784;
            real.onGround = true;
            JumpPilot pilot = new JumpPilot(land, src.x + st[0], src.z + st[1], JumpPolicy.Variant.EDGE);
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
