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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

// the Parkour Lab course (tools/parkourlab) put through the planner, section by section, with and without Tungsten jumps,
// and where the plan is all jumps, flown by the pilot against the sim with the aim jittering. a report, not a pass/fail:
// it prints a table and writes it to PARKOURLAB_SCORECARD if that's set. skipped unless PARKOURLAB_JSON points at course.json
public class ParkourLabScorecard {

    @Test
    public void scorecard() throws Exception {
        String json = System.getenv("PARKOURLAB_JSON");
        Assume.assumeTrue("set PARKOURLAB_JSON to course.json", json != null && Files.exists(Path.of(json)));
        ItemFixture.boot();
        Settings settings = BaritoneAPI.getSettings();
        Map<String, Object> saved = new LinkedHashMap<>();
        String[] bools = {"allowParkour", "allowParkourAscend", "allowNeos", "allowMomentumJumps", "allowClimbJumps", "allowTungstenJumps",
                "fastMode", "allowBreak", "allowPlace", "considerPotionEffects", "chatDebug", "allowVines"};
        for (String b : bools) {
            saved.put(b, settings.byLowerName.get(b.toLowerCase()).value);
        }
        try {
            settings.allowParkour.value = true;
            settings.allowParkourAscend.value = true;
            settings.allowNeos.value = true;
            settings.allowMomentumJumps.value = true;
            settings.allowClimbJumps.value = true;
            settings.allowVines.value = true;
            settings.fastMode.value = false;
            settings.allowBreak.value = false; // a parkour server: no breaking, no placing
            settings.allowPlace.value = false;
            settings.considerPotionEffects.value = false;
            settings.chatDebug.value = false;
            run(json, settings);
        } finally {
            for (Map.Entry<String, Object> e : saved.entrySet()) {
                @SuppressWarnings("unchecked")
                baritone.api.Settings.Setting<Object> s = (baritone.api.Settings.Setting<Object>) settings.byLowerName.get(e.getKey().toLowerCase());
                s.value = e.getValue();
            }
        }
    }

    private void run(String json, Settings settings) throws Exception {
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
        // neos, momentum jumps and ladder grabs come out of tables that get worked out in the background the first time
        // something asks (in game that's the first path of the session). ask once for everything and give them time
        settings.allowTungstenJumps.value = true;
        // and again, as long as each pass turns up shapes nobody had asked about (a landing only reachable through a jump that
        // was itself still being worked out last time), and until the background work is done or the time is up
        long warmUntil = System.currentTimeMillis() + Long.parseLong(System.getenv().getOrDefault("PARKOURLAB_WARM_MS", "45000"));
        for (int pass = 0; pass < 6 && System.currentTimeMillis() < warmUntil; pass++) {
            long missesBefore = baritone.pathing.movement.movements.PendingTables.misses();
            String only0 = System.getenv("PARKOURLAB_SECTIONS");
            for (JsonElement e : course.getAsJsonArray("sections")) {
                JsonObject sec = e.getAsJsonObject();
                if (only0 != null && !("," + only0.replace(" ", "") + ",").contains("," + sec.get("id").getAsInt() + ",")) {
                    continue;
                }
                for (JsonElement je : sec.getAsJsonArray("jumps")) {
                    JsonArray a = je.getAsJsonObject().getAsJsonArray("from"), b = je.getAsJsonObject().getAsJsonArray("to");
                    VineLogPathTest.path(w, new BetterBlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()),
                            new GoalBlock(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt()), false);
                }
            }

            while (baritone.pathing.movement.movements.PendingTables.waiting() && System.currentTimeMillis() < warmUntil) {
                Thread.sleep(500);
            }
            if (baritone.pathing.movement.movements.PendingTables.misses() == missesBefore) {
                break;
            }
        }
        String only = System.getenv("PARKOURLAB_SECTIONS");
        java.util.Set<Integer> wanted = new java.util.HashSet<>();
        if (only != null) {
            for (String t : only.split(",")) {
                wanted.add(Integer.parseInt(t.trim()));
            }
        }
        StringBuilder jumps = new StringBuilder();
        StringBuilder out = new StringBuilder("| # | Section | Without Tungsten | With Tungsten | Flown in the sim (12 runs, jittered aim) |\n|---|---|---|---|---|\n");
        for (JsonElement e : course.getAsJsonArray("sections")) {
            JsonObject sec = e.getAsJsonObject();
            if (!wanted.isEmpty() && !wanted.contains(sec.get("id").getAsInt())) {
                continue;
            }
            BetterBlockPos from = cps.get(sec.get("from").getAsInt()), to = cps.get(sec.get("to").getAsInt());
            settings.allowTungstenJumps.value = false;
            VineLogPathTest.Result off = VineLogPathTest.path(w, from, new GoalBlock(to), false);
            settings.allowTungstenJumps.value = true;
            long t0 = System.nanoTime();
            VineLogPathTest.Result on = VineLogPathTest.path(w, from, new GoalBlock(to), false);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String flown = fly(w, on);
            out.append("| ").append(sec.get("id").getAsInt()).append(" | ").append(sec.get("name").getAsString())
                    .append(" | ").append(describe(off, -1)).append(" | ").append(describe(on, ms)).append(" | ").append(flown).append(" |\n");
            // and each jump on its own, so a section that fails says which jump
            for (JsonElement je : sec.getAsJsonArray("jumps")) {
                JsonObject jo = je.getAsJsonObject();
                JsonArray a = jo.getAsJsonArray("from"), b = jo.getAsJsonArray("to");
                BetterBlockPos ja = new BetterBlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
                BetterBlockPos jb = new BetterBlockPos(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt());
                settings.allowTungstenJumps.value = false;
                boolean before = ok(VineLogPathTest.path(w, ja, new GoalBlock(jb), false));
                settings.allowTungstenJumps.value = true;
                VineLogPathTest.Result jr = VineLogPathTest.path(w, ja, new GoalBlock(jb), false);
                boolean after = ok(jr);
                jumps.append(sec.get("id").getAsInt()).append(' ').append(jo.get("kind").getAsString()).append(' ')
                        .append(jb.x - ja.x).append(',').append(jb.y - ja.y).append(',').append(jb.z - ja.z).append(" onto ")
                        .append(jo.get("block").getAsString()).append(": ").append(before ? "ok" : "NO").append(" / ")
                        .append(after ? "ok " + jr.moves : "NO" + (jr.found ? " (ends at " + jr.positions.get(jr.positions.size() - 1) + " via " + jr.moves + ")" : "")).append('\n');
            }
        }
        out.append("\nJumps one at a time (without / with Tungsten):\n```\n").append(jumps).append("```\n");
        // and the whole thing in one go, start to checkmate
        settings.allowTungstenJumps.value = true;
        if (!wanted.isEmpty()) {
            System.out.println(out);
            return;
        }
        long t0 = System.nanoTime();
        VineLogPathTest.Result all = VineLogPathTest.path(w, cps.get(0), new GoalBlock(cps.get(cps.size() - 1)), false);
        out.append("\nWhole course in one search (start to checkmate, Tungsten on): ").append(describe(all, (System.nanoTime() - t0) / 1_000_000)).append('\n');
        System.out.println(out);
        String file = System.getenv("PARKOURLAB_SCORECARD");
        if (file != null) {
            Files.writeString(Path.of(file), out.toString());
        }
    }

    private static boolean ok(VineLogPathTest.Result r) {
        return r.found && r.inGoal;
    }

    private static String describe(VineLogPathTest.Result r, long ms) {
        if (!r.found || !r.inGoal) {
            return "no path" + (r.found ? " (gets partway)" : "") + (ms >= 0 ? ", " + ms + " ms" : "");
        }
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (String m : r.moves) {
            String k = m.replaceAll("(_(NEAR|FAR|PAD|GRAB|LEAP))?_(NORTHEAST|NORTHWEST|SOUTHEAST|SOUTHWEST|NORTH|SOUTH|EAST|WEST)$", "$1");
            kinds.merge(k.toLowerCase(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("**path**: ");
        kinds.forEach((k, n) -> sb.append(n).append("× ").append(k).append(", "));
        sb.setLength(sb.length() - 2);
        if (ms >= 0) {
            sb.append(" (").append(ms).append(" ms)");
        }
        return sb.toString();
    }

    // fly every jump the pilot can (Tungsten jumps and straight parkour) one after another. anything else (walking, neos,
    // momentum jumps, ladders: other movements with their own executors) is skipped by standing the player on its dest
    private static String fly(VineLogPathTest.MapWorld w, VineLogPathTest.Result r) {
        if (!r.found || !r.inGoal) {
            return "-";
        }
        SafeCollision world = new SafeCollision(w);
        baritone.pathing.movement.CalculationContext ctx = new VineLogPathTest.Ctx(w, false);
        int ok = 0, ticks = 0, flownJumps = 0, searches = 0;
        Map<String, Integer> skipped = new LinkedHashMap<>();
        String why = null;
        for (long seed = 0; seed < 12; seed++) {
            Random rnd = new Random(seed);
            PlayerSim real = new PlayerSim(PlayerSim.Params.vanilla(), world);
            stand(real, w, r.positions.get(0));
            float yaw = -90F;
            int total = 0, jumps = 0;
            boolean all = true;
            for (int i = 0; i + 1 < r.positions.size() && all; i++) {
                BetterBlockPos src = r.positions.get(i), dest = r.positions.get(i + 1);
                String move = r.moves.get(i);
                if (!move.startsWith("TUNGSTEN_") && !move.startsWith("PARKOUR_")) {
                    if (seed == 0) {
                        skipped.merge(move.toLowerCase().replaceAll("_(north|south|east|west|northeast|northwest|southeast|southwest)$", ""), 1, Integer::sum);
                    }
                    stand(real, w, dest);
                    continue;
                }
                jumps++;
                // what MovementTungsten flies: a grab if dest is a ladder, and the planner's full search plan if it needed one
                Landing landing = baritone.pathing.tungsten.TungstenJumps.landing(ctx, dest);
                JumpPilot pilot = new JumpPilot(landing, src.x + 0.5, src.z + 0.5, JumpPolicy.Variant.EDGE);
                if (move.startsWith("TUNGSTEN_")) {
                    baritone.pathing.tungsten.TungstenJumps.Jump j = baritone.pathing.tungsten.TungstenJumps.between(ctx, src, dest);
                    if (j != null) {
                        pilot.fallback(j.plan());
                    }
                }
                boolean done = false, searched = false;
                for (int t = 0; t < 300 && !done; t++, total++) {
                    JumpPilot.Decision d = pilot.tick(real);
                    if (d.status() == JumpPilot.Status.DONE) {
                        done = true;
                    } else if (d.status() == JumpPilot.Status.STUCK && !searched) {
                        // what MovementTungsten does: the full search, from right here
                        searched = true;
                        Landing land = landing;
                        baritone.pathing.tungsten.TungstenSearch.Plan plan = baritone.pathing.tungsten.TungstenSearch.search(real, land,
                                JumpPilot.region(real, land), 3_000_000, null);
                        if (plan == null) {
                            why = src + " -> " + dest + ": stuck and the search found nothing, seed " + seed;
                            break;
                        }
                        searches++;
                        pilot.follow(plan);
                    } else if (d.status() != JumpPilot.Status.PRESS) {
                        why = src + " -> " + dest + ": " + d.why() + " at " + baritone.pathing.tungsten.SimState.of(real) + " seed " + seed;
                        break;
                    } else {
                        PlayerSim.Controls k = d.keys();
                        double jitter = rnd.nextDouble() - 0.5;
                        if (Math.abs(jitter) < 0.1) {
                            jitter *= 4;
                        }
                        yaw += Math.round(Mth.wrapDegrees(k.moveYaw() + (float) (jitter * 2.0) - yaw) / 0.15F) * 0.15F;
                        if (!Keys.step(real, new PlayerSim.Controls(k.forward(), k.left(), k.jump(), k.sprint(), yaw, yaw))) {
                            why = src + " -> " + dest + ": sim stopped (" + real.failure() + ")";
                            break;
                        }
                    }
                }
                if (!done && why == null) {
                    why = src + " -> " + dest + ": timed out at " + baritone.pathing.tungsten.SimState.of(real) + " following " + pilot.following()
                            + " seed " + seed;
                }
                all = done;
            }
            if (all) {
                ok++;
                ticks = total;
                flownJumps = jumps;
            }
        }
        StringBuilder sb = new StringBuilder(ok + "/12 runs landed all " + flownJumps + " jumps");
        if (ok > 0) {
            sb.append(String.format(" (%.1f s in the air and braking)", ticks / 20.0));
        }
        if (searches > 0) {
            sb.append(", fell back on the full search ").append(searches).append(" times");
        }
        if (!skipped.isEmpty()) {
            sb.append("; not flown here: ");
            skipped.forEach((k, n) -> sb.append(n).append("× ").append(k).append(", "));
            sb.setLength(sb.length() - 2);
        }
        if (ok < 12 && why != null) {
            sb.append(" — failed at ").append(why);
        }
        return sb.toString();
    }

    // standing still in the middle of a cell, on whatever is under it
    private static void stand(PlayerSim sim, VineLogPathTest.MapWorld w, BetterBlockPos at) {
        baritone.pathing.sim.BoxList boxes = new baritone.pathing.sim.BoxList();
        SafeCollision c = new SafeCollision(w);
        c.boxes(at.x, at.y - 1, at.z, boxes);
        c.boxes(at.x, at.y, at.z, boxes);
        double top = at.y;
        for (int i = 0; i < boxes.size(); i++) {
            double t = boxes.get(i, 4);
            if (t <= at.y + 0.5 && t > at.y - 0.5) {
                top = Math.max(i == 0 ? t : top, t);
            }
        }
        sim.x = at.x + 0.5;
        sim.y = top;
        sim.z = at.z + 0.5;
        sim.vx = sim.vz = 0;
        sim.vy = -0.0784;
        sim.onGround = true;
        sim.sprinting = false;
    }
}
