package baritone.pathing.calc;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.tungsten.*;
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
        Assume.assumeTrue(json != null && jump != null);
        adris.altoclef.util.helpers.ItemFixture.boot();
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(json))).getAsJsonObject().getAsJsonArray("blocks")) {
            JsonArray b = e.getAsJsonArray();
            w.set(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(),
                    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "minecraft:" + b.get(3).getAsString(), false).blockState());
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
            VineLogPathTest.Result r = VineLogPathTest.path(w, src, new baritone.api.pathing.goals.GoalBlock(dst), false);
            System.out.println("CUBE path found " + r.found + " inGoal " + r.inGoal + " " + r.moves + " " + r.positions);
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
