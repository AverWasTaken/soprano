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

package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.EffortlessAccess;
import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.effortless.AreaMask;
import baritone.effortless.EffortlessJob;
import baritone.effortless.EffortlessManager;
import baritone.effortless.JobQueue;
import baritone.effortless.StorageArea;
import adris.altoclef.ui.gui.facade.Result;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

// the effortless tab's window on the queue. every call is on the game thread, same as the gui
final class McEffortless implements EffortlessAccess {

    private static final int BREAK_RGB = 0xE05555;
    // a row's cells don't change while the entry lives, so the arrays are made once per entry
    private final Map<JobQueue.Entry, int[][]> cellCache = new WeakHashMap<>();

    private static EffortlessManager manager() {
        return ((Baritone) BaritoneAPI.getProvider().getPrimaryBaritone()).getEffortlessManager();
    }

    private static JobQueue queue() {
        return manager().queue();
    }

    @Override
    public List<Row> rows() {
        List<Row> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (JobQueue.Entry e : queue().entries()) {
            AreaMask bounds = e.schematic().bounds();
            int[][] cells = cellCache.computeIfAbsent(e, McEffortless::cells);
            out.add(new Row(e, e.name(), detail(e), ago(now - e.added()), state(e.state()), e.why(), e.merged(), e.shown(),
                    bounds.sizeX, bounds.sizeY, bounds.sizeZ, cells[0], cells[1]));
        }
        return out;
    }

    // every cell of the entry from its corner, and a colour for each: the block's map colour for a place, red for a break
    private static int[][] cells(JobQueue.Entry e) {
        AreaMask bounds = e.schematic().bounds();
        List<Integer> xyz = new ArrayList<>();
        List<Integer> rgb = new ArrayList<>();
        for (EffortlessJob j : e.jobs()) {
            j.mask.forEach((x, y, z) -> {
                xyz.add(x - bounds.minX);
                xyz.add(y - bounds.minY);
                xyz.add(z - bounds.minZ);
                rgb.add(j.mode == EffortlessJob.Mode.BREAK ? BREAK_RGB : color(j.palette.pick(x, y, z)));
            });
        }
        int[] a = new int[xyz.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = xyz.get(i);
        }
        int[] c = new int[rgb.size()];
        for (int i = 0; i < c.length; i++) {
            c[i] = rgb.get(i);
        }
        return new int[][]{a, c};
    }

    private static int color(BlockState s) {
        try {
            int col = s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
            return col == 0 ? 0x9AA3AD : col;
        } catch (Throwable t) {
            return 0x9AA3AD;
        }
    }

    static String detail(JobQueue.Entry e) {
        if (e.merged()) {
            return e.jobs().size() + " shapes as one job · " + e.cells() + " blocks";
        }
        EffortlessJob j = e.jobs().get(0);
        if (j.mode == EffortlessJob.Mode.BREAK) {
            return "Break · " + j.mask.count() + " blocks · " + j.filter;
        }
        String what = j.palette.isSingle() ? blockName(j.palette.entries().get(0).state()) : j.palette.entries().size() + " blocks mixed";
        String how = switch (j.fillMode) {
            case FILL_EMPTY -> "into empty";
            case OVERWRITE -> "over anything";
            case REPLACE -> "replacing " + j.filter;
        };
        return "Place · " + j.mask.count() + " blocks · " + what + " · " + how;
    }

    private static String blockName(BlockState s) {
        return s.getBlock().getName().getString();
    }

    static String ago(long ms) {
        long min = ms / 60_000;
        if (min < 1) {
            return "added just now";
        }
        if (min < 60) {
            return "added " + min + " min ago";
        }
        long h = min / 60;
        return h < 24 ? "added " + h + " h ago" : "added " + (h / 24) + " d ago";
    }

    private static State state(JobQueue.State s) {
        return switch (s) {
            case QUEUED -> State.WAITING;
            case RUNNING -> State.RUNNING;
            case DONE -> State.DONE;
            case FAILED -> State.FAILED;
        };
    }

    @Override
    public boolean running() {
        return queue().running() || queue().runningEntry() != null;
    }

    @Override
    public String status() {
        JobQueue q = queue();
        JobQueue.Entry r = q.runningEntry();
        if (r != null) {
            int[] at = q.progress(r);
            return "Building " + at[0] + " of " + at[1];
        }
        int waiting = 0;
        for (JobQueue.Entry e : q.entries()) {
            if (e.state() == JobQueue.State.QUEUED) {
                waiting++;
            }
        }
        if (q.isEmpty()) {
            return "Empty";
        }
        return waiting == 0 ? "Nothing waiting" : waiting + " waiting";
    }

    @Override
    public void start() {
        manager().startQueue();
    }

    @Override
    public void stop() {
        manager().stopQueue();
    }

    @Override
    public void move(int from, int to) {
        queue().move(from, to);
        manager().save();
    }

    @Override
    public void mergeWithNext(int index) {
        try {
            queue().merge(List.of(index, index + 1));
            manager().save();
        } catch (IllegalArgumentException ignored) {
            // the running one can't be merged, the row's button just does nothing then
        }
    }

    @Override
    public void unmerge(int index) {
        try {
            queue().unmerge(index);
            manager().save();
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void remove(int index) {
        if (queue().get(index).state() == JobQueue.State.RUNNING) {
            manager().stopQueue();
        }
        queue().remove(index);
        manager().save();
    }

    @Override
    public void toggleShown(int index) {
        JobQueue.Entry e = queue().get(index);
        e.shown(!e.shown());
        manager().save();
    }

    @Override public List<Storage> storages() {
        return queue().storage().stream().map(a -> new Storage(a.id(),a.name(),a.role().ordinal(),a.match().ordinal(),
                String.join(", ",a.items()), a.boxes().stream().map(b -> new Region(b.a().toShortString(),b.b().toShortString())).toList())).toList();
    }

    private int storageIndex(String id) {
        for (int i = 0; i < queue().storage().size(); i++) if (queue().storage().get(i).id().equals(id)) return i;
        throw new IllegalArgumentException("That storage was removed; select another entry");
    }

    private static BlockPos storagePos(String text) {
        List<String> values = adris.altoclef.ui.gui.model.Coords.split(text);
        if (values.size() != 3) throw new IllegalArgumentException("Enter X Y Z for each corner");
        try {
            int x = Integer.parseInt(values.get(0)), y = Integer.parseInt(values.get(1)), z = Integer.parseInt(values.get(2));
            if (Math.abs((long)x) > 30_000_000 || Math.abs((long)z) > 30_000_000 || y < -2032 || y > 2031)
                throw new IllegalArgumentException("Coordinates are outside the supported world bounds");
            return new BlockPos(x,y,z);
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Use whole numbers for X Y Z"); }
    }

    @Override public Result updateStorage(Storage value) {
        try {
            int i = storageIndex(value.id());
            if (value.regions().isEmpty()) return Result.fail("A storage needs at least one area");
            List<StorageArea.Box> parts = value.regions().stream().map(v -> new StorageArea.Box(storagePos(v.a()),storagePos(v.b()))).toList();
            StorageArea old = queue().storage().get(i);
            StorageArea edited = old.edited(value.name(),StorageArea.Role.values()[value.role()],StorageArea.Match.values()[value.match()],
                    value.items(),parts.get(0).a(),parts.get(0).b());
            edited = new StorageArea(edited.a(),edited.b(),edited.role(),edited.id(),edited.name(),edited.match(),edited.items(),parts.subList(1,parts.size()));
            queue().storage().set(i,edited); manager().save();
            return Result.ok("Storage saved. Changes apply to the next trip");
        } catch (IllegalArgumentException e) { return Result.fail(e.getMessage()); }
    }

    @Override public Result removeStorage(String id) {
        try { queue().storage().remove(storageIndex(id)); manager().save(); return Result.ok("Storage removed"); }
        catch (IllegalArgumentException e) { return Result.fail(e.getMessage()); }
    }

    @Override public Result moveStorage(String id, int delta) {
        try {
            int from = storageIndex(id), to = Math.max(0,Math.min(queue().storage().size()-1,from+delta));
            StorageArea area = queue().storage().remove(from); queue().storage().add(to,area); manager().save();
            return Result.ok("Storage priority updated");
        } catch (IllegalArgumentException e) { return Result.fail(e.getMessage()); }
    }

    @Override public Result mergeStorage(String id) {
        try {
            int i = storageIndex(id);
            if (i+1 >= queue().storage().size()) return Result.fail("There is no storage below this one");
            StorageArea merged = queue().storage().get(i).merge(queue().storage().get(i+1));
            queue().storage().set(i,merged); queue().storage().remove(i+1); manager().save();
            return Result.ok("Storage areas merged; the space between them is excluded");
        } catch (IllegalArgumentException e) { return Result.fail(e.getMessage()); }
    }

    @Override
    public void clearDone() {
        queue().clearDone();
        manager().save();
    }
}
