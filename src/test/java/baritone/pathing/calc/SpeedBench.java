package baritone.pathing.calc;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.movements.PendingTables;
import baritone.pathing.sim.PlayerSim;
import baritone.pathing.tungsten.Keys;
import baritone.pathing.tungsten.SafeCollision;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.Assume;
import org.junit.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// how fast the planner is and how much garbage it makes, on the Parkour Lab course: SPEED_BENCH=course.json. a cold run
// (every section asked for the first time, background searches and all, till they're done), then each section searched
// again warm, then the sim ticked on a fence the way the pilot's staging does. prints numbers, asserts nothing
public class SpeedBench {

    private static long allocated() {
        com.sun.management.ThreadMXBean t = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long sum = 0;
        for (long id : t.getAllThreadIds()) {
            long a = t.getThreadAllocatedBytes(id);
            if (a > 0) {
                sum += a;
            }
        }
        return sum;
    }

    private static long[] gc() {
        long n = 0, ms = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += g.getCollectionCount();
            ms += g.getCollectionTime();
        }
        return new long[]{n, ms};
    }

    @Test
    public void bench() throws Exception {
        String json = System.getenv("SPEED_BENCH");
        Assume.assumeTrue(json != null && Files.exists(Path.of(json)));
        ItemFixture.boot();
        Settings s = BaritoneAPI.getSettings();
        s.allowParkour.value = true;
        s.allowParkourAscend.value = true;
        s.allowNeos.value = true;
        s.allowMomentumJumps.value = true;
        s.allowClimbJumps.value = true;
        s.allowTungstenJumps.value = true;
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.chatDebug.value = false;
        s.considerPotionEffects.value = false;
        s.fastMode.value = false;
        JsonObject course = JsonParser.parseString(Files.readString(Path.of(json))).getAsJsonObject();
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        for (JsonElement e : course.getAsJsonArray("blocks")) {
            JsonArray b = e.getAsJsonArray();
            w.set(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(),
                    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "minecraft:" + b.get(3).getAsString(), false).blockState());
        }
        List<BetterBlockPos> cps = new ArrayList<>();
        for (JsonElement e : course.getAsJsonArray("checkpoints")) {
            JsonObject cp = e.getAsJsonObject();
            cps.add(new BetterBlockPos(cp.get("x").getAsInt(), cp.get("y").getAsInt(), cp.get("z").getAsInt()));
        }
        int[] sections = Arrays.stream(System.getenv().getOrDefault("SPEED_SECTIONS", "8,9,16,20,21").split(",")).mapToInt(Integer::parseInt).toArray();

        boolean pilotOnly = System.getenv("SPEED_PILOT_ONLY") != null;
        // cold: a section, asked again and again until nothing's left to work out (or a minute), the way the bot meets it
        jdk.jfr.Recording coldRec = null;
        if (System.getenv("SPEED_JFR_COLD") != null) {
            coldRec = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            coldRec.setDestination(Path.of(System.getenv("SPEED_JFR_COLD")));
            coldRec.start();
        }
        long a0 = allocated(), t0 = System.nanoTime();
        long[] g0 = gc();
        for (int sec : pilotOnly ? new int[0] : sections) {
            ParkourLabScorecard.warm(w, cps, course.getAsJsonArray("sections").get(sec - 1).getAsJsonObject(), 120_000, true);
        }
        long coldMs = (System.nanoTime() - t0) / 1_000_000, coldMb = (allocated() - a0) >> 20;
        if (coldRec != null) {
            coldRec.stop();
            coldRec.close();
        }
        long[] g1 = gc();
        System.out.println("BENCH cold: " + coldMs + " ms, " + coldMb + " MB allocated, " + (g1[0] - g0[0]) + " GCs (" + (g1[1] - g0[1]) + " ms)"
                );

        // warm: each section searched 5 times
        jdk.jfr.Recording rec = null;
        if (System.getenv("SPEED_JFR") != null) {
            rec = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            rec.setDestination(Path.of(System.getenv("SPEED_JFR")));
            rec.start();
        }
        StringBuilder warm = new StringBuilder();
        long wa = allocated(), wt = System.nanoTime();
        for (int sec : pilotOnly ? new int[0] : sections) {
            JsonObject so = course.getAsJsonArray("sections").get(sec - 1).getAsJsonObject();
            BetterBlockPos from = cps.get(so.get("from").getAsInt()), to = cps.get(so.get("to").getAsInt());
            long[] ms = new long[5];
            boolean ok = true;
            for (int i = 0; i < ms.length; i++) {
                long t = System.nanoTime();
                VineLogPathTest.Result r = VineLogPathTest.path(w, from, new GoalBlock(to), false);
                ms[i] = (System.nanoTime() - t) / 1_000_000;
                ok &= r.found && r.inGoal;
            }
            Arrays.sort(ms);
            warm.append(" ").append(sec).append(":").append(ms[2]).append("ms").append(ok ? "" : "(no path)");
        }
        if (rec != null) {
            rec.stop();
            rec.close();
        }
        System.out.println("BENCH warm (median of 5):" + warm + "; " + ((System.nanoTime() - wt) / 1_000_000) + " ms, " + ((allocated() - wa) >> 20) + " MB in all");

        // the pilot's staging: from a spot beside a fence post, 17 x 9 two-tick key presses each coasted out, 20 times over
        VineLogPathTest.Ctx ctx = new VineLogPathTest.Ctx(w);
        BetterBlockPos at = new BetterBlockPos(1241, 147, 1015); // on the fence run's first fence post
        long pa = allocated(), pt = System.nanoTime();
        long simTicks = 0;
        SafeCollision pilotWorld = null;
        int reps = pilotOnly ? 400 : 20;
        for (int rep = 0; rep < reps; rep++) {
            pilotWorld = SafeCollision.forTick(pilotWorld, ctx.bsi); // one table reused tick after tick, as in game
            SafeCollision world = pilotWorld;
            PlayerSim live = new PlayerSim(PlayerSim.Params.vanilla(), world);
            live.x = at.x + 0.5;
            live.y = at.y + 0.5;
            live.z = at.z + 0.5;
            live.onGround = true;
            long before = PlayerSim.ticks();
            for (int i = 0; i < 17; i++) {
                PlayerSim one = live.copy();
                Keys.step(one, Keys.push(i * 360F / 16, false));
                for (int j = 0; j < 9; j++) {
                    PlayerSim two = one.copy();
                    Keys.step(two, Keys.push(j * 40F, false));
                    for (int c = 0; c < 10; c++) {
                        Keys.step(two, Keys.release(0F));
                    }
                }
            }
            simTicks += PlayerSim.ticks() - before;
        }
        long pms = (System.nanoTime() - pt) / 1_000_000;
        System.out.println("BENCH pilot staging: " + pms / (double) reps + " ms a tick (" + simTicks / reps + " sim ticks), " + ((allocated() - pa) >> 10) / 1024.0 / reps + " MB a tick");
    }
}
