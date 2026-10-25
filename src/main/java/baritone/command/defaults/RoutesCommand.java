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

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.behavior.RouteMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class RoutesCommand extends Command {

    public RoutesCommand(IBaritone baritone) {
        super(baritone, "routes");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase() : "status";
        if (action.equals("learn")) {
            learn(args);
            return;
        }
        args.requireMax(0);
        switch (action) {
            case "forget":
                RouteMemory.forget();
                RouteMemory.save();
                logDirect("Forgot every way through this world (and how the jumps went)");
                break;
            case "save":
                RouteMemory.save();
                logDirect("Saved");
                break;
            default:
                logDirect(RouteMemory.trailCount() + " trails remembered in this world. #routes forget to start over");
        }
    }

    // #routes learn <recording> [dx dy dz]: the way you went in a #record recording, as trails here (moved, for a map rebuilt
    // somewhere else: Parkour Lab's levels)
    private void learn(IArgConsumer args) throws CommandException {
        String name = args.getString();
        int dx = 0, dy = 0, dz = 0;
        if (args.hasAny()) {
            dx = args.getAs(Integer.class);
            dy = args.getAs(Integer.class);
            dz = args.getAs(Integer.class);
        }
        args.requireMax(0);
        Path dir = ((baritone.Baritone) baritone).getDirectory().resolve("recordings");
        String trace;
        try {
            trace = readTrace(dir, name);
        } catch (IOException e) {
            logDirect("Couldn't read " + name + ": " + e.getMessage());
            return;
        }
        if (trace == null) {
            logDirect("No recording called " + name + " in " + dir + " (a folder or a .zip, from #record)");
            return;
        }
        List<double[]> ticks = parse(trace);
        try {
            String blocks = readEntry(dir, name, "blocks.json");
            if (blocks != null && !ticks.isEmpty()) {
                floating(ticks, blocks);
            }
        } catch (IOException | RuntimeException e) {
            logDirect("(couldn't read the recording's blocks, so swims and climbs aren't on the trail: " + e.getMessage() + ")");
        }
        int cells = RouteMemory.learn(ticks, dx, dy, dz);
        RouteMemory.save();
        logDirect("Learned the way from " + name + ": " + ticks.size() + " ticks, " + cells + " cells of trail. "
                + RouteMemory.trailCount() + " trails remembered in this world");
    }

    private static String readTrace(Path dir, String name) throws IOException {
        return readEntry(dir, name, "trace.csv");
    }

    private static String readEntry(Path dir, String name, String entry) throws IOException {
        Path folder = dir.resolve(name);
        if (Files.isRegularFile(folder.resolve(entry))) {
            return Files.readString(folder.resolve(entry));
        }
        Path zip = dir.resolve(name.endsWith(".zip") ? name : name + ".zip");
        if (!Files.isRegularFile(zip)) {
            return null;
        }
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.getName().endsWith("/" + entry) || e.getName().equals(entry)) {
                    return new String(z.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    // the ticks in water or on a ladder (by the recording's own blocks, for the world the ticks are in): 2 for those, so the
    // swim up a hanging water block or the climb up a ladder is on the trail too, not a gap
    static void floating(List<double[]> ticks, String blocksJson) {
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(blocksJson).getAsJsonObject();
        com.google.gson.JsonArray palette = o.getAsJsonArray("palette");
        boolean[] floaty = new boolean[palette.size()];
        for (int i = 0; i < floaty.length; i++) {
            String st = palette.get(i).getAsString();
            floaty[i] = st.startsWith("minecraft:water") || st.contains("waterlogged=true") || st.startsWith("minecraft:bubble_column")
                    || st.startsWith("minecraft:kelp") || st.startsWith("minecraft:seagrass") || st.startsWith("minecraft:tall_seagrass")
                    || st.startsWith("minecraft:ladder") || st.startsWith("minecraft:vine") || st.startsWith("minecraft:scaffolding")
                    || st.contains("_vines") || st.startsWith("minecraft:cave_vines");
        }
        int world = (int) ticks.get(0)[4];
        it.unimi.dsi.fastutil.longs.LongOpenHashSet cells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        com.google.gson.JsonArray b = o.getAsJsonArray("blocks");
        for (com.google.gson.JsonElement e : b) {
            com.google.gson.JsonArray q = e.getAsJsonArray(); // x, y, z, palette index, world
            if (q.get(4).getAsInt() == world && floaty[q.get(3).getAsInt()]) {
                cells.add(net.minecraft.core.BlockPos.asLong(q.get(0).getAsInt(), q.get(1).getAsInt(), q.get(2).getAsInt()));
            }
        }
        if (cells.isEmpty()) {
            return;
        }
        for (double[] t : ticks) {
            if (t[3] != 0) {
                continue;
            }
            int x = (int) Math.floor(t[0]), z = (int) Math.floor(t[2]);
            if (cells.contains(net.minecraft.core.BlockPos.asLong(x, (int) Math.floor(t[1] + 0.1), z))
                    || cells.contains(net.minecraft.core.BlockPos.asLong(x, (int) Math.floor(t[1] + 0.9), z))) {
                t[3] = 2;
            }
        }
    }

    // x y z onGround world a tick, for the world spent longest in (a server sends you through a few: lobby, game, lobby)
    public static List<double[]> parse(String trace) {
        String[] lines = trace.split("\\R");
        List<String> cols = Arrays.asList(lines[0].split(","));
        int cw = cols.indexOf("world"), cx = cols.indexOf("x"), cy = cols.indexOf("y"), cz = cols.indexOf("z"), cg = cols.indexOf("onGround");
        java.util.Map<String, List<double[]>> byWorld = new java.util.LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String[] v = lines[i].split(",", -1);
            if (v.length != cols.size()) {
                continue;
            }
            try {
                byWorld.computeIfAbsent(cw < 0 ? "" : v[cw], k -> new ArrayList<>()).add(new double[]{
                        Double.parseDouble(v[cx]), Double.parseDouble(v[cy]), Double.parseDouble(v[cz]), Double.parseDouble(v[cg]),
                        cw < 0 ? 0 : Double.parseDouble(v[cw])});
            } catch (NumberFormatException ignored) {}
        }
        List<double[]> best = new ArrayList<>();
        for (List<double[]> l : byWorld.values()) {
            if (l.size() > best.size()) {
                best = l;
            }
        }
        return best;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String typed = args.peekString().toLowerCase();
            return Stream.of("forget", "learn", "save", "status").filter(s -> s.startsWith(typed));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "The ways through courses the bot remembers";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Everywhere you stand goes onto a trail (the bot's or yours), and a #goto towards somewhere a trail goes past",
                "follows it (see the useRouteMemory setting). The trails are kept with the world's other Soprano data.",
                "",
                "Usage:",
                "> routes - how many there are",
                "> routes save - write them out now",
                "> routes learn <recording> [dx dy dz] - the way you went in a #record recording, as trails here (moved by dx dy dz:",
                ">     a map rebuilt somewhere else). Playing a level once with #record teaches the bot the way",
                "> routes forget - forget them all, and how the jumps went"
        );
    }
}
