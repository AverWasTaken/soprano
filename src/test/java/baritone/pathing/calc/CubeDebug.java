package baritone.pathing.calc;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.tungsten.*;
import baritone.pathing.sim.PlayerSim;
import baritone.pathing.tungsten.JumpPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

// a jump on a course from a recording, searched on its own: CUBE_JSON=course.json CUBE_JUMP="sx sy sz lx ly lz floorY"
public class CubeDebug {
    @Test
    public void search() throws Exception {
        String json = System.getenv("CUBE_JSON"), jump = System.getenv("CUBE_JUMP");
        Assume.assumeTrue(json != null && (jump != null || System.getenv("CUBE_PAIRS") != null));
        adris.altoclef.util.helpers.ItemFixture.boot();
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(json))).getAsJsonObject().getAsJsonArray("blocks")) {
            JsonArray b = e.getAsJsonArray();
            w.set(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(),
                    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "minecraft:" + b.get(3).getAsString(), false).blockState());
        }
        if (System.getenv("CUBE_SOLVE_AT") != null) {
            // the full search from exactly here: CUBE_SOLVE_AT="x y z tx ty tz" (a start where the player stood, to a landing)
            baritone.api.BaritoneAPI.getSettings().allowTungstenJumps.value = true;
            baritone.api.BaritoneAPI.getSettings().considerPotionEffects.value = false;
            String[] q = System.getenv("CUBE_SOLVE_AT").trim().split("\\s+");
            SafeCollision sw = new SafeCollision(w);
            VineLogPathTest.Ctx ctx = new VineLogPathTest.Ctx(w);
            baritone.pathing.sim.PlayerSim sim = new baritone.pathing.sim.PlayerSim(baritone.pathing.sim.PlayerSim.Params.vanilla(), sw);
            sim.x = Double.parseDouble(q[0]);
            sim.y = Double.parseDouble(q[1]);
            sim.z = Double.parseDouble(q[2]);
            sim.vy = -0.0784;
            sim.onGround = true;
            BetterBlockPos t = new BetterBlockPos(Integer.parseInt(q[3]), Integer.parseInt(q[4]), Integer.parseInt(q[5]));
            Landing l = TungstenJumps.landing(ctx, t);
            BetterBlockPos src = new BetterBlockPos((int) Math.floor(sim.x), (int) Math.floor(sim.y), (int) Math.floor(sim.z));
            for (long budget : new long[]{1_000_000, 3_000_000, 10_000_000}) {
                long t0 = System.currentTimeMillis();
                baritone.pathing.tungsten.TungstenSearch.Plan plan = baritone.pathing.tungsten.TungstenSearch.search(sim.copy(), l,
                        baritone.pathing.tungsten.NeoSearches.region(src, l), budget, null);
                System.out.println("CUBE solve at " + sim.x + " " + sim.z + " budget " + budget + ": " + (plan == null ? "none" : plan.ticks() + " ticks") + " in " + (System.currentTimeMillis() - t0) + " ms");
                if (plan != null && plan.ticks() > 0) {
                    break;
                }
            }
            return;
        }
        if (System.getenv("CUBE_PAIRS") != null) {
            // several pairs, ';' between: what the planner makes of each (CUBE_PAIR for one)
            baritone.api.BaritoneAPI.getSettings().allowTungstenJumps.value = true;
            baritone.api.BaritoneAPI.getSettings().maxFallHeightNoWater.value = Integer.parseInt(System.getenv().getOrDefault("CUBE_MAX_FALL", "8"));
            baritone.api.BaritoneAPI.getSettings().allowParkour.value = true;
            baritone.api.BaritoneAPI.getSettings().considerPotionEffects.value = false;
            baritone.api.BaritoneAPI.getSettings().allowBreak.value = false;
            baritone.api.BaritoneAPI.getSettings().allowPlace.value = false;
            baritone.api.BaritoneAPI.getSettings().allowParkourAscend.value = true;
            VineLogPathTest.Ctx ctx = new VineLogPathTest.Ctx(w);
            SafeCollision sw = new SafeCollision(w);
            for (String pair : System.getenv("CUBE_PAIRS").split(";")) {
                String[] q = pair.trim().split("\\s+");
                BetterBlockPos a = new BetterBlockPos(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]));
                BetterBlockPos b = new BetterBlockPos(Integer.parseInt(q[3]), Integer.parseInt(q[4]), Integer.parseInt(q[5]));
                double[] st = TungstenJumps.standAt(sw, a.x, a.y, a.z);
                Landing l = TungstenJumps.landing(ctx, b);
                String head = "CUBE " + a + " -> " + b + ":";
                System.out.println(head + " stand " + java.util.Arrays.toString(st) + " landing " + l
                        + " plain " + TungstenJumps.plainCandidate(ctx, a.x, a.y, a.z, b.x - a.x, b.y - a.y, b.z - a.z, true)
                        + " candidate " + TungstenJumps.candidate(ctx, a.x, a.y, a.z, b.x - a.x, b.y - a.y, b.z - a.z, true)
                        + " takeoff " + TungstenJumps.takeoff(ctx, a.x, a.y, a.z) + " destOk " + TungstenJumps.destOk(ctx, b.x, b.y, b.z));
                TungstenJumps.Jump j = TungstenJumps.between(ctx, a, b);
                System.out.println(head + " between " + (j == null ? "none" : j.cost() + " " + j.variant() + (j.plan() != null ? " (plan)" : "")));
                if (System.getenv("CUBE_SOLVE") != null && !Double.isNaN(st[2])) {
                    // the full search, the way the background does it (standing in src)
                    long t0 = System.currentTimeMillis();
                    baritone.pathing.tungsten.TungstenSearch.Plan plan = baritone.pathing.tungsten.NeoSearches.solve(sw, a, st[2], l,
                            baritone.pathing.tungsten.NeoSearches.region(a, l));
                    System.out.println(head + " full search " + (plan == null ? "none" : plan.ticks() + " ticks") + " in " + (System.currentTimeMillis() - t0) + " ms");
                }
                if (q.length >= 8) {
                    // coming in from fx fz: the jump carrying that one's speed on
                    int sector = TungstenJumps.carrySector(Integer.parseInt(q[6]), Integer.parseInt(q[7]), a.x, a.z);
                    TungstenJumps.Jump cj = TungstenJumps.between(ctx, a, b, sector);
                    System.out.println(head + " carried (sector " + sector + ") " + (cj == null ? "none" : cj.cost() + " " + cj.variant()));
                    if (cj == null) {
                        for (JumpPolicy.Variant v : new JumpPolicy.Variant[]{new JumpPolicy.Variant(0, 0F), new JumpPolicy.Variant(1, 0F), new JumpPolicy.Variant(2, 0F), JumpPolicy.Variant.EDGE}) {
                            PlayerSim cs = new PlayerSim(PlayerSim.Params.vanilla(), sw);
                            cs.x = a.x + st[0];
                            cs.y = st[2];
                            cs.z = a.z + st[1];
                            double ang = sector * 2 * Math.PI / 16;
                            cs.vx = Math.cos(ang) * 0.24;
                            cs.vz = Math.sin(ang) * 0.24;
                            cs.vy = -0.0784;
                            cs.onGround = true;
                            cs.sprinting = true;
                            JumpPolicy.Rollout rr = JumpPolicy.rollout(cs, l, v);
                            System.out.println(head + "   " + v + ": ok " + rr.ok() + " " + rr.why() + " ends " + rr.states().get(rr.states().size() - 1));
                        }
                    }
                }
                if (j == null && !Double.isNaN(st[2]) && (System.getenv("CUBE_FORCE") != null || TungstenJumps.candidate(ctx, a.x, a.y, a.z, b.x - a.x, b.y - a.y, b.z - a.z, true))) {
                    // what the rule makes of it from standing, and the best it can do
                    PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), sw);
                    sim.x = a.x + st[0];
                    sim.y = st[2];
                    sim.z = a.z + st[1];
                    sim.vy = -0.0784;
                    sim.onGround = true;
                    JumpPolicy.Rollout r = JumpPolicy.best(sim, l);
                    System.out.println(head + " best " + (r == null ? "none" : r.variant() + " ok " + r.ok() + " ends " + r.states().get(r.states().size() - 1)));
                }
            }
            return;
        }
        String[] p = jump.trim().split("\\s+");
        BetterBlockPos src = new BetterBlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        Landing land = new Landing(Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]));
        double floorY = Double.parseDouble(p[6]);
        SafeCollision world = new SafeCollision(w);
        if (System.getenv("CUBE_BOXES") != null) {
            String[] q = System.getenv("CUBE_BOXES").trim().split("\\s+");
            int bx = Integer.parseInt(q[0]), by = Integer.parseInt(q[1]), bz = Integer.parseInt(q[2]);
            for (int x = bx - 1; x <= bx + 1; x++) {
                for (int y = by - 1; y <= by + 1; y++) {
                    for (int z = bz - 1; z <= bz + 1; z++) {
                        baritone.pathing.sim.BoxList b = new baritone.pathing.sim.BoxList();
                        world.boxes(x, y, z, b);
                        for (int i = 0; i < b.size(); i++) {
                            System.out.printf("CUBE box %d %d %d: %.3f %.3f %.3f .. %.3f %.3f %.3f%n", x, y, z, b.get(i, 0), b.get(i, 1), b.get(i, 2), b.get(i, 3), b.get(i, 4), b.get(i, 5));
                        }
                    }
                }
            }
        }
        if (System.getenv("CUBE_RUN") != null) {
            // x z yaw: sprint from rest at floorY, jump after N ticks
            String[] q = System.getenv("CUBE_RUN").trim().split("\\s+");
            baritone.pathing.sim.PlayerSim sim = new baritone.pathing.sim.PlayerSim(baritone.pathing.sim.PlayerSim.Params.vanilla(), world);
            sim.x = Double.parseDouble(q[0]);
            sim.z = Double.parseDouble(q[1]);
            sim.y = Double.parseDouble(p[6]);
            sim.vy = -0.0784;
            sim.onGround = true;
            float yaw = Float.parseFloat(q[2]);
            int jumpAt = Integer.parseInt(q[3]);
            for (int t = 0; t < 14; t++) {
                Keys.step(sim, new baritone.pathing.sim.PlayerSim.Controls(1, 0, t == jumpAt, true, yaw));
                System.out.printf("CUBE run %d %.3f %.3f %.3f v %.3f %.3f %.3f g%s h%s%n", t, sim.x, sim.y, sim.z, sim.vx, sim.vy, sim.vz, sim.onGround, sim.horizontalCollision);
            }
        }
        if (System.getenv("CUBE_PAIR") != null) {
            baritone.api.BaritoneAPI.getSettings().allowTungstenJumps.value = true;
            // the way the client has it for the levels
            baritone.api.BaritoneAPI.getSettings().allowNeos.value = true;
            baritone.api.BaritoneAPI.getSettings().allowMomentumJumps.value = true;
            baritone.api.BaritoneAPI.getSettings().allowClimbJumps.value = true;
            if (System.getenv("CUBE_MAX_FALL") != null) {
                baritone.api.BaritoneAPI.getSettings().maxFallHeightNoWater.value = Integer.parseInt(System.getenv("CUBE_MAX_FALL"));
            }
            baritone.api.BaritoneAPI.getSettings().allowParkour.value = true;
            baritone.api.BaritoneAPI.getSettings().considerPotionEffects.value = false;
            baritone.api.BaritoneAPI.getSettings().allowBreak.value = false;
            baritone.api.BaritoneAPI.getSettings().allowPlace.value = false;
            baritone.api.BaritoneAPI.getSettings().allowParkourAscend.value = true;
            VineLogPathTest.Ctx ctx = new VineLogPathTest.Ctx(w);
            BetterBlockPos dst = new BetterBlockPos(land.x(), land.y(), land.z());
            System.out.println("CUBE plain " + TungstenJumps.plainCandidate(ctx, src.x, src.y, src.z, dst.x - src.x, dst.y - src.y, dst.z - src.z, true)
                    + " candidate " + TungstenJumps.candidate(ctx, src.x, src.y, src.z, dst.x - src.x, dst.y - src.y, dst.z - src.z, true)
                    + " takeoff " + TungstenJumps.takeoff(ctx, src.x, src.y, src.z));
            TungstenJumps.Jump j = TungstenJumps.between(ctx, src, dst);
            System.out.println("CUBE between " + (j == null ? "none" : j.cost() + ""));
            if (System.getenv("CUBE_BUCKETS") != null) {
                ctx.tungsten().newSearch(10_000_000);
                long tb = System.nanoTime();
                for (int b = 0; b < TungstenJumps.BUCKETS; b++) {
                    TungstenJumps.Jump bj = TungstenJumps.get(ctx, src.x, src.y, src.z, b);
                    System.out.println("CUBE bucket " + b + ": " + (bj == null ? "-" : bj.dest() + " " + bj.cost()));
                }
                System.out.println("CUBE buckets took " + (System.nanoTime() - tb) / 1_000_000 + " ms, sim ticks " + (10_000_000 - ctx.tungsten().budgetLeft()));
            }
            if (System.getenv("CUBE_MOVES") != null) {
                baritone.utils.pathing.MutableMoveResult mr = new baritone.utils.pathing.MutableMoveResult();
                System.out.println("CUBE rim " + TungstenJumps.rimNode(ctx, src.x, src.y, src.z) + " water " + TungstenJumps.waterCell(ctx, src.x, src.y, src.z));
                for (baritone.pathing.movement.Moves m : baritone.pathing.movement.Moves.values()) {
                    if (m.tungsten) {
                        continue;
                    }
                    mr.reset();
                    m.apply(ctx, src.x, src.y, src.z, mr);
                    System.out.println("CUBE move " + m + " -> " + mr.x + " " + mr.y + " " + mr.z + " cost " + mr.cost);
                }
            }
            System.out.println("CUBE ground under start " + AbstractNodeCostSearch.groundUnder(ctx, src.x, src.y, src.z));
            long simBefore = baritone.pathing.sim.PlayerSim.ticks(), tPath = System.nanoTime();
            long missesBefore = baritone.pathing.movement.movements.PendingTables.misses();
            VineLogPathTest.Result r = VineLogPathTest.path(w, src, new baritone.api.pathing.goals.GoalBlock(dst), false);
            System.out.println("CUBE pending misses " + (baritone.pathing.movement.movements.PendingTables.misses() - missesBefore));
            for (int retry = 0; retry < Integer.parseInt(System.getenv().getOrDefault("CUBE_RETRIES", "0")) && !r.inGoal; retry++) {
                Thread.sleep(5000);
                r = VineLogPathTest.path(w, src, new baritone.api.pathing.goals.GoalBlock(dst), false);
                System.out.println("CUBE retry " + retry + " found " + r.found + " inGoal " + r.inGoal + " " + r.moves);
            }
            System.out.println("CUBE path sim ticks " + (baritone.pathing.sim.PlayerSim.ticks() - simBefore) + " in " + (System.nanoTime() - tPath) / 1_000_000 + " ms");
            if (TungstenJumps.PROFILE) {
                for (int k = 0; k < 2; k++) {
                    StringBuilder sb = new StringBuilder("CUBE profile " + (k == 0 ? "fail" : "ok  ") + ":");
                    for (int d = 0; d < 12; d++) {
                        sb.append(" dy").append(d - 8).append("=").append(TungstenJumps.PROFILE_CALLS[k][d]).append("/").append(TungstenJumps.PROFILE_TICKS[k][d]);
                    }
                    System.out.println(sb);
                }
                String[] names = {"rimwalk", "rule ok", "rule fail", "neo ok", "neo fail"};
                StringBuilder sb = new StringBuilder("CUBE parts:");
                for (int k = 0; k < 5; k++) {
                    sb.append(" ").append(names[k]).append("=").append(TungstenJumps.PROFILE_PARTS_N[k]).append("/").append(TungstenJumps.PROFILE_PARTS[k]);
                }
                System.out.println(sb);
                System.out.println("CUBE rimwalk walks " + baritone.pathing.tungsten.RimWalk.PROFILE[0] + " flights " + baritone.pathing.tungsten.RimWalk.PROFILE[1]
                        + " drives jump " + baritone.pathing.tungsten.RimWalk.PROFILE[2] + " walk " + baritone.pathing.tungsten.RimWalk.PROFILE[3]);
            }
            System.out.println("CUBE path found " + r.found + " inGoal " + r.inGoal + " " + r.moves + " " + r.positions);
            // what the executor sees each tick (a fresh context, no idea which way the path came in)
            VineLogPathTest.Ctx fresh = new VineLogPathTest.Ctx(w);
            for (int i = 0; i + 1 < r.positions.size(); i++) {
                if (r.moves.get(i).startsWith("TUNGSTEN")) {
                    TungstenJumps.Jump bj = TungstenJumps.between(fresh, r.positions.get(i), r.positions.get(i + 1));
                    System.out.println("CUBE recheck " + r.moves.get(i) + " " + r.positions.get(i) + " -> " + r.positions.get(i + 1) + ": " + (bj == null ? "NONE" : bj.cost()));
                }
            }
            return;
        }
        long t0 = System.currentTimeMillis();
        TungstenSearch.Plan plan = System.getenv("CUBE_NO_SEARCH") != null ? null : NeoSearches.solve(world, src, floorY, land, NeoSearches.region(src, land),
                p.length > 8 ? new double[]{Double.parseDouble(p[7]), Double.parseDouble(p[8])} : null);
        System.out.println("CUBE plan " + (plan == null ? "none" : plan.ticks() + " ticks") + " in " + (System.currentTimeMillis() - t0) + " ms");
        TungstenSearch.Plan walk = RimWalk.plan(world, src, floorY, land);
        System.out.println("CUBE rim walk " + (walk == null ? "none" : walk.ticks() + " ticks"));
        for (int rep = 0; rep < Integer.parseInt(System.getenv().getOrDefault("CUBE_REPS", "0")); rep++) {
            long tr = System.currentTimeMillis();
            Ledges.plan(world, src, floorY, land);
            System.out.println("CUBE warm-up ledges in " + (System.currentTimeMillis() - tr) + " ms");
        }
        long t1 = System.currentTimeMillis();
        TungstenSearch.Plan ledges = Ledges.plan(world, src, floorY, land);
        System.out.println("CUBE ledges " + (ledges == null ? "none" : ledges.ticks() + " ticks") + " in " + (System.currentTimeMillis() - t1) + " ms");
        if (plan == null) {
            plan = ledges;
        }
        if (plan != null) {
            for (int i = 0; i < plan.states().size(); i++) {
                System.out.println("CUBE  " + i + " " + plan.states().get(i) + (i < plan.keys().size() ? " " + plan.keys().get(i) : ""));
            }
        }
    }
}
