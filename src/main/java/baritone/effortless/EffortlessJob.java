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

package baritone.effortless;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

// one thing to do in one area: the shape and where it was clicked, place or break, and what may be touched. the cells
// come from the shape once, here, and everything else (preview, bill, builder) asks the job
public final class EffortlessJob {

    public enum Mode {
        PLACE, BREAK
    }

    // how a place job treats what's already there
    public enum FillMode {
        // only air and things you place straight over (grass, water, snow). filling a hole
        FILL_EMPTY,
        // every cell becomes the palette, whatever's in the way goes
        OVERWRITE,
        // only cells whose block passes the filter (the hand block by default) become the palette
        REPLACE
    }

    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis());

    public final long id;
    public final String name;
    public final ShapeSpec spec;
    public final List<BlockPos> points;
    public final Mode mode;
    public final FillMode fillMode;
    // break: what gets broken. replace: what gets replaced. ignored otherwise
    public final BlockFilter filter;
    // place only, null for a break job
    public final Palette palette;
    public final AreaMask mask;

    public EffortlessJob(long id, String name, ShapeSpec spec, List<BlockPos> points, Mode mode, FillMode fillMode,
                         BlockFilter filter, Palette palette, AreaMask mask) {
        if (mode == Mode.PLACE && palette == null) {
            throw new IllegalArgumentException("a place job needs a block");
        }
        this.id = id;
        this.spec = spec;
        this.points = List.copyOf(points);
        this.mode = mode;
        this.fillMode = fillMode == null ? FillMode.FILL_EMPTY : fillMode;
        this.filter = filter == null ? BlockFilter.ALL : filter;
        this.palette = palette;
        this.mask = mask != null ? mask : ShapeGeometry.build(spec, this.points);
        this.name = name != null ? name : defaultName(spec, this.mask);
    }

    public static EffortlessJob place(ShapeSpec spec, List<BlockPos> points, FillMode fill, BlockFilter replaceFilter, Palette palette) {
        return new EffortlessJob(IDS.incrementAndGet(), null, spec, points, Mode.PLACE, fill, replaceFilter, palette, null);
    }

    public static EffortlessJob breaking(ShapeSpec spec, List<BlockPos> points, BlockFilter filter) {
        return new EffortlessJob(IDS.incrementAndGet(), null, spec, points, Mode.BREAK, null, filter, null, null);
    }

    public EffortlessJob withName(String newName) {
        return new EffortlessJob(id, newName, spec, points, mode, fillMode, filter, palette, mask);
    }

    public EffortlessJob withPalette(Palette p) {
        return new EffortlessJob(id, name, spec, points, mode, fillMode, filter, p, mask);
    }

    public BlockPos origin() {
        return new BlockPos(mask.minX, mask.minY, mask.minZ);
    }

    // what this job does to a cell holding current, in world coords. the one rule the preview and the schematic share
    public enum Outcome {
        // nothing: the cell's not in the shape, the filter says no, or it's already right
        UNTOUCHED, PLACE, BREAK, REPLACE
    }

    public Outcome outcome(int x, int y, int z, BlockState current) {
        if (!mask.contains(x, y, z)) {
            return Outcome.UNTOUCHED;
        }
        if (mode == Mode.BREAK) {
            return filter.matches(current) ? Outcome.BREAK : Outcome.UNTOUCHED;
        }
        BlockState want = palette.pick(x, y, z);
        if (current == want) {
            return Outcome.UNTOUCHED;
        }
        boolean empty = current.isAir() || current.canBeReplaced();
        return switch (fillMode) {
            case FILL_EMPTY -> empty ? Outcome.PLACE : Outcome.UNTOUCHED;
            // the filter can keep blocks out of an overwrite: everything but the logs in your offhand
            case OVERWRITE -> empty ? Outcome.PLACE : filter.matches(current) ? Outcome.REPLACE : Outcome.UNTOUCHED;
            // only blocks that are there: air stays air, a door's hole stays open
            case REPLACE -> !empty && filter.matches(current) ? Outcome.REPLACE : Outcome.UNTOUCHED;
        };
    }

    public BlockState desired(int x, int y, int z) {
        return mode == Mode.BREAK ? Blocks.AIR.defaultBlockState() : palette.pick(x, y, z);
    }

    static String defaultName(ShapeSpec spec, AreaMask mask) {
        return spec.kind().title + " " + mask.sizeX + "×" + mask.sizeY + "×" + mask.sizeZ;
    }

    @Override
    public String toString() {
        return name + " (" + mode.name().toLowerCase() + ", " + mask.count() + " cells)";
    }
}
