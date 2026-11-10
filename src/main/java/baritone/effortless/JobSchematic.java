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

import baritone.api.schematic.ISchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

// one job, or a merged group of them, as something BuilderProcess can build. the builder asks in local coords from
// origin(). where two jobs of a group overlap the later one wins, the same order the queue shows them in
public final class JobSchematic implements ISchematic {

    private static final byte UNKNOWN = 0;
    private static final byte IN = 1;
    private static final byte OUT = 2;

    private final List<EffortlessJob> jobs;
    private final AreaMask bounds;
    // a place cell's in or out is decided the first time the builder looks and then kept. replace has to: the builder
    // breaks the old block before it places, and an air cell no longer passes "only dirt", so it would drop out half
    // done (ReplaceSchematic caches for the same reason). byte per cell of the box, the builder asks from its own thread
    // too, and a byte write can't trample a neighbour the way a BitSet word can
    private final byte[] decided;

    public JobSchematic(List<EffortlessJob> jobs) {
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("nothing to build");
        }
        this.jobs = List.copyOf(jobs);
        List<AreaMask> masks = new ArrayList<>(jobs.size());
        for (EffortlessJob j : jobs) {
            masks.add(j.mask);
        }
        this.bounds = jobs.size() == 1 ? jobs.get(0).mask : AreaMask.union(masks);
        this.decided = new byte[Math.max(1, bounds.sizeX * bounds.sizeY * bounds.sizeZ)];
    }

    public JobSchematic(EffortlessJob job) {
        this(List.of(job));
    }

    public BlockPos origin() {
        return new BlockPos(bounds.minX, bounds.minY, bounds.minZ);
    }

    public AreaMask bounds() {
        return bounds;
    }

    public List<EffortlessJob> jobs() {
        return jobs;
    }

    // the job that owns this world cell, the last one in the group that covers it. null when none does
    public EffortlessJob owner(int x, int y, int z) {
        for (int i = jobs.size() - 1; i >= 0; i--) {
            EffortlessJob j = jobs.get(i);
            if (j.mask.contains(x, y, z)) {
                return j;
            }
        }
        return null;
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState current) {
        if (!bounds.containsLocal(x, y, z)) {
            return false;
        }
        int wx = bounds.minX + x;
        int wy = bounds.minY + y;
        int wz = bounds.minZ + z;
        EffortlessJob job = owner(wx, wy, wz);
        if (job == null) {
            return false;
        }
        if (job.mode == EffortlessJob.Mode.BREAK) {
            // not kept: once it's air it's done, and something that grew back into it later is its own business
            return job.filter.matches(current);
        }
        int i = x + bounds.sizeX * (z + bounds.sizeZ * y);
        byte d = decided[i];
        if (d != UNKNOWN) {
            return d == IN;
        }
        EffortlessJob.Outcome o = job.outcome(wx, wy, wz, current);
        // already right counts as in, so the builder sees it as done instead of not its business
        boolean in = o != EffortlessJob.Outcome.UNTOUCHED || current == job.desired(wx, wy, wz);
        decided[i] = in ? IN : OUT;
        return in;
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        int wx = bounds.minX + x;
        int wy = bounds.minY + y;
        int wz = bounds.minZ + z;
        EffortlessJob job = owner(wx, wy, wz);
        return job == null ? current : job.desired(wx, wy, wz);
    }

    // a fresh look at every cell, for a rerun of the same job
    @Override
    public void reset() {
        java.util.Arrays.fill(decided, UNKNOWN);
    }

    @Override
    public int widthX() {
        return bounds.sizeX;
    }

    @Override
    public int heightY() {
        return bounds.sizeY;
    }

    @Override
    public int lengthZ() {
        return bounds.sizeZ;
    }
}
