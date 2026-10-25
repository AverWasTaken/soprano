/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.tungsten.TungstenJumps;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// every jump on a remembered trail (routes.json, #routes learn): can the planner make it? the ones it can't are where a
// #goto along that trail stops. TRAIL_COURSE=level.json TRAIL_ROUTES=routes.json (the trail that starts at the level's start)
public class TrailCheck {

    static VineLogPathTest.Result guided(VineLogPathTest.MapWorld w, BetterBlockPos a, BetterBlockPos b) {
        baritone.pathing.movement.CalculationContext ctx = new VineLogPathTest.Ctx(w, false);
        ctx.routeGuide = baritone.behavior.RouteMemory.guide(a, b);
        ctx.trailFloors = ctx.routeGuide == null ? baritone.behavior.RouteMemory.floorsNear(a.x, a.z) : baritone.behavior.RouteMemory.floors(ctx.routeGuide, ctx);
        baritone.api.pathing.goals.Goal goal = new baritone.api.pathing.goals.GoalBlock(b);
        AStarPathFinder pf = new AStarPathFinder(a, a.x, a.y, a.z, goal, new baritone.utils.pathing.Favoring(null, ctx), ctx);
        java.util.Optional<baritone.api.pathing.calc.IPath> p;
        try {
            ctx.claimSearchCaches();
            p = pf.calculate0(3000, 3000);
        } finally {
            ctx.releaseSearchCaches();
        }
        VineLogPathTest.Result out = new VineLogPathTest.Result();
        if (p.isPresent()) {
            out.found = true;
            out.positions.addAll(p.get().positions());
            out.inGoal = goal.isInGoal(p.get().getDest().x, p.get().getDest().y, p.get().getDest().z);
        }
        return out;
    }

    @Test
    public void check() throws Exception {
        String course = System.getenv("TRAIL_COURSE"), routes = System.getenv("TRAIL_ROUTES");
        Assume.assumeTrue(course != null && routes != null);
        ItemFixture.boot();
        var s = BaritoneAPI.getSettings();
        s.allowTungstenJumps.value = true;
        s.allowParkour.value = true;
        s.allowParkourAscend.value = true;
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.considerPotionEffects.value = false;
        s.maxFallHeightNoWater.value = 8;
        s.useJumpNet.value = false;
        s.learnJumpNet.value = false;
        JsonObject c = JsonParser.parseString(Files.readString(Path.of(course))).getAsJsonObject();
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        for (JsonElement e : c.getAsJsonArray("blocks")) {
            JsonArray b = e.getAsJsonArray();
            w.set(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(),
                    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "minecraft:" + b.get(3).getAsString(), false).blockState());
        }
        JsonObject start = c.getAsJsonArray("checkpoints").get(0).getAsJsonObject();
        int sx = start.get("x").getAsInt(), sy = start.get("y").getAsInt(), sz = start.get("z").getAsInt();
        List<BetterBlockPos> trail = null;
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(routes))).getAsJsonObject().getAsJsonArray("trails")) {
            JsonArray a = e.getAsJsonArray();
            if (Math.abs(a.get(0).getAsInt() - sx) <= 1 && Math.abs(a.get(1).getAsInt() - sy) <= 1 && Math.abs(a.get(2).getAsInt() - sz) <= 1) {
                trail = new ArrayList<>();
                for (int i = 0; i + 2 < a.size(); i += 3) {
                    trail.add(new BetterBlockPos(a.get(i).getAsInt(), a.get(i + 1).getAsInt(), a.get(i + 2).getAsInt()));
                }
                break;
            }
        }
        Assume.assumeTrue(trail != null);
        // (and remembered, the way the game has it: the planner tries harder at the jumps along a trail)
        baritone.behavior.RouteMemory.remember(trail);
        VineLogPathTest.Ctx ctx = new VineLogPathTest.Ctx(w);
        // the cells you can stand in (a trail cell over an edge isn't one: the one before it on the trail is where the jump goes from)
        List<BetterBlockPos> stands = new ArrayList<>();
        for (BetterBlockPos p : trail) {
            if (TungstenJumps.takeoff(ctx, p.x, p.y, p.z) || TungstenJumps.destOk(ctx, p.x, p.y, p.z)
                    || !ctx.get(p.x, p.y, p.z).getFluidState().isEmpty()
                    || baritone.pathing.movement.MovementHelper.isClimbable(ctx.get(p.x, p.y, p.z).getBlock())) {
                stands.add(p);
            }
        }
        {
            // (what the trail stands on, the way the game has it from the searches before: RouteMemory.floorsNear)
            baritone.behavior.RouteMemory.Guide g0 = baritone.behavior.RouteMemory.guide(trail.get(0), trail.get(trail.size() - 1));
            if (g0 != null) {
                System.out.println("TRAIL floors " + baritone.behavior.RouteMemory.floors(g0, new VineLogPathTest.Ctx(w, false)));
            }
        }
        if (System.getenv("TRAIL_PAIR") != null) {
            // just this one, guided (TRAIL_PAIR=x y z x y z)
            String[] q = System.getenv("TRAIL_PAIR").trim().split("\\s+");
            BetterBlockPos a = new BetterBlockPos(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]));
            BetterBlockPos b = new BetterBlockPos(Integer.parseInt(q[3]), Integer.parseInt(q[4]), Integer.parseInt(q[5]));
            for (int attempt = 0; attempt < 5; attempt++) {
                VineLogPathTest.Result r = guided(w, a, b);
                baritone.behavior.RouteMemory.Guide gg = baritone.behavior.RouteMemory.guide(a, b);
                System.out.println("TRAIL guide " + gg + (gg == null ? "" : " floors " + baritone.behavior.RouteMemory.floors(gg, new VineLogPathTest.Ctx(w, false))));
                System.out.println("TRAIL pair " + a + " -> " + b + " attempt " + attempt + ": " + (r.inGoal ? "made it: " + r.positions : "no" + (r.found ? " (part: " + r.positions + ")" : "")));
                if (r.inGoal) {
                    break;
                }
                Thread.sleep(3000);
            }
            return;
        }
        int jumps = 0, missing = 0;
        for (int i = 0; i + 1 < stands.size(); i++) {
            BetterBlockPos a = stands.get(i), b = stands.get(i + 1);
            int dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy == 0) {
                continue; // (a step)
            }
            jumps++;
            long t0 = System.nanoTime();
            TungstenJumps.Jump j = TungstenJumps.between(ctx, a, b);
            // and what a search from a to b makes of it (any move: a parkour jump, an ascend, a ladder, the jumps)
            VineLogPathTest.Result r = VineLogPathTest.path(w, a, new baritone.api.pathing.goals.GoalBlock(b), false);
            if (!r.inGoal && System.getenv("TRAIL_GUIDED") != null) {
                // and with the trail as a guide, the way the game has it (trail leaps), the background given its time
                for (int attempt = 0; attempt < 4 && !r.inGoal; attempt++) {
                    r = guided(w, a, b);
                    if (!r.inGoal && baritone.pathing.movement.movements.PendingTables.waiting()) {
                        Thread.sleep(4000);
                    }
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (!r.inGoal) {
                missing++;
            }
            System.out.println("TRAIL " + i + " " + a.x + " " + a.y + " " + a.z + " -> " + b.x + " " + b.y + " " + b.z + " (" + dx + " " + dy + " " + dz + ") "
                    + (r.inGoal ? "SEARCH " + (r.positions.size() - 1) + " moves" : "NOSEARCH") + " jump " + (j == null ? "none" : j.variant()) + " " + ms + "ms"
                    + " candidate " + TungstenJumps.candidate(ctx, a.x, a.y, a.z, dx, dy, dz, true)
                    + " under " + ctx.get(b.x, b.y - 1, b.z).getBlock().getDescriptionId().replace("block.minecraft.", ""));
        }
        System.out.println("TRAIL " + course + ": " + jumps + " jumps, the planner can't make " + missing);
    }
}
