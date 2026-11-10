/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.effortless.EffortlessJob;
import baritone.effortless.EffortlessManager;
import baritone.effortless.EffortlessOptions;
import baritone.effortless.JobQueue;
import baritone.effortless.StorageArea;
import baritone.effortless.ShapeKind;
import baritone.effortless.ShapeSpec;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

// the chat side of Effortless Soprano. everything the radial will do, typed: pick a shape, set its points, place or
// break. the options are key=value words so one line can say the lot
public class EffortlessCommand extends Command {

    public EffortlessCommand(IBaritone baritone) {
        super(baritone, "effortless", "ef");
    }

    private EffortlessManager manager() {
        return ((Baritone) baritone).getEffortlessManager();
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String sub = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "info";
        EffortlessManager m = manager();
        switch (sub) {
            case "p1", "p2", "p3", "pos1", "pos2", "pos3" -> {
                int index = sub.charAt(sub.length() - 1) - '1';
                BlockPos pos;
                if (args.hasAny()) {
                    pos = args.getDatatypePost(RelativeBlockPos.INSTANCE, ctx.viewerPos());
                } else {
                    // no coords: the block you're looking at, or where you stand when you look at nothing
                    pos = m.crosshair().orElse(ctx.playerFeet());
                }
                args.requireMax(0);
                m.point(index, pos);
                logDirect("Point " + (index + 1) + " set to " + pos.toShortString());
            }
            case "shape" -> {
                args.requireMin(1);
                ShapeKind kind = ShapeKind.byId(args.getString());
                if (kind == null) {
                    throw new CommandInvalidStateException("no such shape, try one of " + shapeList());
                }
                EffortlessOptions o = options(m.spec().withKind(kind), rest(args));
                m.spec(o.spec());
                logDirect("Shape: " + describe(o.spec()));
            }
            case "place", "break" -> run(sub.equals("place"), args);
            case "preview" -> {
                args.requireMin(1);
                String what = args.getString().toLowerCase(Locale.ROOT);
                if (what.equals("off")) {
                    m.draft(null, null);
                    logDirect("Preview off");
                    return;
                }
                if (!what.equals("place") && !what.equals("break")) {
                    throw new CommandInvalidStateException("preview place, preview break or preview off");
                }
                EffortlessOptions o = options(m.spec(), rest(args));
                m.spec(o.spec());
                m.draft(what.equals("place") ? EffortlessJob.Mode.PLACE : EffortlessJob.Mode.BREAK, o);
                logDirect("Previewing " + what + ", the shape follows your crosshair until its points are set");
            }
            case "queue", "q" -> queue(args);
            case "storage", "s" -> storage(args);
            case "clear" -> {
                args.requireMax(0);
                m.clearPoints();
                logDirect("Points cleared");
            }
            case "info" -> {
                args.requireMax(0);
                logDirect("Shape: " + describe(m.spec()));
                List<BlockPos> pts = m.points();
                for (int i = 0; i < pts.size(); i++) {
                    logDirect("Point " + (i + 1) + ": " + (pts.get(i) == null ? "not set" : pts.get(i).toShortString()));
                }
                var mask = m.currentMask(false);
                if (mask != null) {
                    logDirect(mask.count() + " blocks, " + mask.sizeX + "×" + mask.sizeY + "×" + mask.sizeZ);
                }
                EffortlessJob job = m.running();
                if (job != null) {
                    logDirect("Building: " + job.name);
                }
            }
            default -> throw new CommandInvalidStateException("unknown subcommand, see #help effortless");
        }
    }

    // #ef queue [list|start|stop|move n m|merge n m..|unmerge n|remove n|show n|rename n name|clear]. numbers are 1 based,
    // as the list prints them
    private void queue(IArgConsumer args) throws CommandException {
        EffortlessManager m = manager();
        JobQueue q = m.queue();
        String what = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "list";
        switch (what) {
            case "list" -> {
                if (q.isEmpty()) {
                    logDirect("The queue is empty. Finish a shape in build mode, or #ef place / break, to add one");
                    return;
                }
                for (int i = 0; i < q.size(); i++) {
                    JobQueue.Entry e = q.get(i);
                    logDirect((i + 1) + ". " + e.name() + " - " + e.cells() + " blocks, " + describeEntry(e)
                            + ", " + e.state().name().toLowerCase(Locale.ROOT) + (e.why() != null ? " (" + e.why() + ")" : "")
                            + (e.shown() ? ", shown" : ""));
                }
                logDirect(q.running() ? "Running" : "Stopped, #ef queue start to run it");
            }
            case "start" -> {
                String why = m.startQueue();
                logDirect(why != null ? why : "Queue started");
            }
            case "stop", "pause" -> {
                m.stopQueue();
                logDirect("Queue stopped");
            }
            case "move" -> {
                int from = index(args, q);
                int to = index(args, q);
                q.move(from, to);
                m.save();
                logDirect("Moved to " + (to + 1));
            }
            case "merge" -> {
                List<Integer> picks = new java.util.ArrayList<>();
                while (args.hasAny()) {
                    picks.add(index(args, q));
                }
                try {
                    JobQueue.Entry g = q.merge(picks);
                    m.save();
                    logDirect("Merged into one job: " + g.name() + ", " + g.cells() + " blocks");
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
            }
            case "unmerge", "split" -> {
                try {
                    q.unmerge(index(args, q));
                    m.save();
                    logDirect("Split back into its shapes");
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
            }
            case "remove", "rm" -> {
                int i = index(args, q);
                if (q.get(i).state() == JobQueue.State.RUNNING) {
                    m.stopQueue();
                }
                q.remove(i);
                m.save();
                logDirect("Removed");
            }
            case "show", "hide" -> {
                int i = index(args, q);
                q.get(i).shown(what.equals("show"));
                m.save();
                logDirect(what.equals("show") ? "Showing it in the world" : "Hidden");
            }
            case "rename" -> {
                int i = index(args, q);
                String name = rest(args);
                q.get(i).rename(name);
                m.save();
                logDirect("Renamed to " + q.get(i).name());
            }
            case "clear" -> {
                q.clearDone();
                m.save();
                logDirect("Cleared the finished ones");
            }
            default -> throw new CommandInvalidStateException("queue list, start, stop, move, merge, unmerge, remove, show, hide, rename or clear");
        }
    }

    // #ef storage [list|add x y z x y z [role]|role n input|output|both|remove n|clear]
    private void storage(IArgConsumer args) throws CommandException {
        EffortlessManager m = manager();
        List<StorageArea> areas = m.queue().storage();
        String what = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "list";
        switch (what) {
            case "list" -> {
                if (areas.isEmpty()) {
                    logDirect("No storage marked. In build mode, right click two corners around the chests with an empty hand");
                    return;
                }
                for (int i = 0; i < areas.size(); i++) {
                    logDirect((i + 1) + ". " + areas.get(i).describe());
                }
            }
            case "add" -> {
                BlockPos a = args.getDatatypePost(RelativeBlockPos.INSTANCE, ctx.viewerPos());
                BlockPos b = args.getDatatypePost(RelativeBlockPos.INSTANCE, ctx.viewerPos());
                StorageArea.Role role = args.hasAny() ? StorageArea.Role.byId(args.getString()) : StorageArea.Role.BOTH;
                if (role == null) {
                    throw new CommandInvalidStateException("the role is input, output or both");
                }
                m.addStorage(new StorageArea(a, b, role));
                logDirect("Storage " + areas.size() + " marked: " + areas.get(areas.size() - 1).describe());
            }
            case "role" -> {
                int i = storageIndex(args, areas);
                StorageArea.Role role = StorageArea.Role.byId(args.getString());
                if (role == null) {
                    throw new CommandInvalidStateException("the role is input, output or both");
                }
                areas.set(i, areas.get(i).withRole(role));
                m.save();
                logDirect("Storage " + (i + 1) + " is " + areas.get(i).describe());
            }
            case "remove", "rm" -> {
                areas.remove(storageIndex(args, areas));
                m.save();
                logDirect("Removed");
            }
            case "clear" -> {
                areas.clear();
                m.save();
                logDirect("No storage marked now");
            }
            default -> throw new CommandInvalidStateException("storage list, add, role, remove or clear");
        }
    }

    private static int storageIndex(IArgConsumer args, List<StorageArea> areas) throws CommandException {
        int n = args.getAs(Integer.class);
        if (n < 1 || n > areas.size()) {
            throw new CommandInvalidStateException("there's no storage " + n + " (1 to " + areas.size() + ")");
        }
        return n - 1;
    }

    private static int index(IArgConsumer args, JobQueue q) throws CommandException {
        int n = args.getAs(Integer.class);
        if (n < 1 || n > q.size()) {
            throw new CommandInvalidStateException("there's no " + n + " in the queue (1 to " + q.size() + ")");
        }
        return n - 1;
    }

    static String describeEntry(JobQueue.Entry e) {
        if (e.merged()) {
            return e.jobs().size() + " shapes merged";
        }
        EffortlessJob j = e.jobs().get(0);
        return j.mode == EffortlessJob.Mode.BREAK ? "break (" + j.filter + ")" : "place";
    }

    private static EffortlessOptions options(ShapeSpec base, String raw) throws CommandException {
        try {
            return EffortlessOptions.parse(base, raw);
        } catch (IllegalArgumentException e) {
            throw new CommandInvalidStateException(e.getMessage());
        }
    }

    private static String rest(IArgConsumer args) throws CommandException {
        String raw = args.hasAny() ? args.rawRest() : "";
        while (args.hasAny()) {
            args.get();
        }
        return raw;
    }

    private void run(boolean place, IArgConsumer args) throws CommandException {
        EffortlessManager m = manager();
        EffortlessOptions o = options(m.spec(), rest(args));
        m.spec(o.spec());
        List<BlockPos> pts;
        try {
            pts = m.jobPoints();
        } catch (EffortlessManager.NotReady e) {
            throw new CommandInvalidStateException(e.getMessage());
        }
        Optional<net.minecraft.world.level.block.state.BlockState> hand = m.heldBlock(false);
        Optional<net.minecraft.world.level.block.state.BlockState> off = m.heldBlock(true);
        EffortlessJob job;
        try {
            job = place ? o.placeJob(pts, hand, off) : o.breakJob(pts, hand, off);
            m.checkSize(job);
        } catch (EffortlessOptions.Bad | EffortlessManager.NotReady e) {
            throw new CommandInvalidStateException(e.getMessage());
        }
        m.draft(place ? EffortlessJob.Mode.PLACE : EffortlessJob.Mode.BREAK, o);
        m.start(job);
        // the points are spent, the preview moves on to the running job
        m.clearPoints();
        logDirect((place ? "Placing " : "Breaking ") + job.name + ", " + job.mask.count() + " blocks"
                + (place ? "" : " (" + job.filter + ")"));
    }

    private static String describe(ShapeSpec s) {
        StringBuilder b = new StringBuilder(s.kind().title);
        b.append(", axis ").append(s.axis().name().toLowerCase(Locale.ROOT));
        b.append(", ").append(s.fill().name().toLowerCase(Locale.ROOT));
        if (s.thickness() > 1) {
            b.append(", ").append(s.thickness()).append(" thick");
        }
        if (s.half()) {
            b.append(", half");
        }
        return b.toString();
    }

    private static String shapeList() {
        return String.join(", ", Arrays.stream(ShapeKind.values()).map(ShapeKind::id).toList());
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("p1", "p2", "p3", "shape", "place", "break", "preview", "queue", "storage", "info", "clear")
                    .filterPrefix(args.getString()).sortAlphabetically().stream();
        }
        String sub = args.getString().toLowerCase(Locale.ROOT);
        if (sub.equals("shape") && args.hasExactlyOne()) {
            return new TabCompleteHelper().append(Arrays.stream(ShapeKind.values()).map(ShapeKind::id))
                    .filterPrefix(args.getString()).stream();
        }
        if ((sub.equals("queue") || sub.equals("q")) && args.hasExactlyOne()) {
            return new TabCompleteHelper().append("list", "start", "stop", "move", "merge", "unmerge", "remove", "show",
                    "hide", "rename", "clear").filterPrefix(args.getString()).stream();
        }
        if (sub.startsWith("p") && sub.length() == 2) {
            return args.tabCompleteDatatype(RelativeBlockPos.INSTANCE);
        }
        if (sub.equals("shape") || sub.equals("place") || sub.equals("break")) {
            if (sub.equals("shape")) {
                args.getString();
            }
            while (args.has(2)) {
                args.getString();
            }
            if (args.hasExactlyOne()) {
                return new TabCompleteHelper().append(EffortlessOptions.KEYWORDS.stream()).filterPrefix(args.getString()).stream();
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Build or break a shape, Effortless Soprano style";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Set up a shape with two or three points and Soprano places or breaks every block in it.",
                "Points with no coordinates are the block you are looking at.",
                "",
                "Usage:",
                "> ef shape <shape> [options] - Pick a shape: " + shapeList(),
                "> ef p1 / p2 / p3 [x y z] - Set a point",
                "> ef place [options] - Fill the shape. Uses the block in your hand unless you say block=",
                "> ef break [options] - Break the blocks in the shape",
                "> ef preview place|break [options] - Show what place or break would do, without starting. preview off hides it",
                "> ef queue - The queue: list, start, stop, move <n> <to>, merge <n> <n>.., unmerge <n>, remove <n>,",
                "  show/hide <n> (in the world), rename <n> <name>, clear (the finished ones)",
                "> ef storage - Marked storage: list, add <x y z> <x y z> [input|output|both], role <n> <role>, remove <n>, clear",
                "  Break jobs empty a full inventory into output storage, place jobs set to Storage fetch from input storage",
                "> ef info - What's set up",
                "> ef clear - Forget the points",
                "",
                "Shape options: x y z free (axis), filled hollow walls frame, t=<thickness>, half (a dome)",
                "Place options: block=<blocks> (hand, offhand, or ids like stone*3,cobblestone with @r for random rotation),",
                "  mode=fill (only empty cells, the default) / overwrite / replace, only=<blocks to replace>, seed=<n>",
                "Break options: only=<blocks or #tags>, except=<blocks>, hand (only the hand block), offhand,",
                "  nothand (all but the hand block), skipcontainers, fluids (break fluids too)",
                "Replace with no only= swaps the hand block for the offhand block.",
                "",
                "Examples:",
                "> ef shape wall t=2, ef p1, ef p2, ef place",
                "> ef shape cuboid, ef p1 ~ ~ ~, ef p2 ~10 ~5 ~10, ef break only=stone,#minecraft:logs",
                "> ef place block=stone_bricks*6,cracked_stone_bricks*3,mossy_stone_bricks"
        );
    }
}
