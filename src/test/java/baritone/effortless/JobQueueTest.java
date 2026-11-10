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

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

// a house as a queue: floor, stairs, walls, roof. reordered, merged, saved and read back
public class JobQueueTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static EffortlessJob job(ShapeKind kind, int x) {
        return EffortlessJob.place(ShapeSpec.of(kind), List.of(new BlockPos(x, 0, 0), new BlockPos(x + 2, 2, 2)),
                EffortlessJob.FillMode.FILL_EMPTY, null, Palette.of(Blocks.STONE.defaultBlockState()));
    }

    private static JobQueue house() {
        JobQueue q = new JobQueue();
        q.add(job(ShapeKind.FLOOR, 0), 1);
        q.add(job(ShapeKind.SLOPE_FLOOR, 10), 2);
        q.add(job(ShapeKind.WALL, 20), 3);
        q.add(job(ShapeKind.PYRAMID, 30), 4);
        return q;
    }

    private static List<ShapeKind> kinds(JobQueue q) {
        return q.entries().stream().map(e -> e.jobs().get(0).spec.kind()).toList();
    }

    @Test
    public void moveKeepsTheRestInOrder() {
        JobQueue q = house();
        q.move(3, 1);
        assertEquals(List.of(ShapeKind.FLOOR, ShapeKind.PYRAMID, ShapeKind.SLOPE_FLOOR, ShapeKind.WALL), kinds(q));
        q.move(0, 3);
        assertEquals(List.of(ShapeKind.PYRAMID, ShapeKind.SLOPE_FLOOR, ShapeKind.WALL, ShapeKind.FLOOR), kinds(q));
    }

    @Test
    public void mergeMakesOneEntryWhereTheFirstWas() {
        JobQueue q = house();
        JobQueue.Entry g = q.merge(List.of(3, 1));
        assertEquals(3, q.size());
        assertSame(g, q.get(1));
        assertTrue(g.merged());
        assertEquals(ShapeKind.SLOPE_FLOOR, g.jobs().get(0).spec.kind());
        assertEquals(ShapeKind.PYRAMID, g.jobs().get(1).spec.kind());
        assertEquals(2, g.added());
        // one build for both: the schematic covers the two shapes and the space between
        JobSchematic s = g.schematic();
        assertEquals(10, s.origin().getX());
        assertNotNull(s.owner(31, 0, 0));
        assertNull(s.owner(20, 0, 0));
        q.unmerge(1);
        assertEquals(4, q.size());
        assertEquals(ShapeKind.PYRAMID, q.get(2).jobs().get(0).spec.kind());
    }

    @Test(expected = IllegalArgumentException.class)
    public void theRunningOneCantBeMerged() {
        JobQueue q = house();
        q.get(0).state(JobQueue.State.RUNNING, null);
        q.merge(List.of(0, 1));
    }

    @Test
    public void nextSkipsDoneAndFailed() {
        JobQueue q = house();
        q.get(0).state(JobQueue.State.DONE, null);
        q.get(1).state(JobQueue.State.FAILED, "stuck");
        assertSame(q.get(2), q.next());
        assertArrayEquals(new int[]{2, 3}, q.progress(q.get(2)));
        q.clearDone();
        assertEquals(3, q.size());
    }

    @Test
    public void savedAndReadBack() throws Exception {
        JobQueue q = house();
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.HALF, Half.TOP);
        q.add(EffortlessJob.place(ShapeSpec.of(ShapeKind.LINE).withDirection(ShapeSpec.Direction.X),
                List.of(new BlockPos(0, 5, 0), new BlockPos(6, 5, 0)), EffortlessJob.FillMode.OVERWRITE, null,
                new Palette(List.of(new Palette.Entry(stair, 3, false), new Palette.Entry(Blocks.OAK_LOG.defaultBlockState(), 1, true)), 42)), 5);
        q.add(EffortlessJob.breaking(ShapeSpec.of(ShapeKind.DOME).withCentered(true), List.of(new BlockPos(0, 0, 0), new BlockPos(3, 0, 3), new BlockPos(3, 3, 3)),
                BlockFilter.ALL.withOnly(List.of("#minecraft:logs")).withExcept(List.of("minecraft:birch_log")).withSkipContainers(true)), 6);
        q.merge(List.of(0, 1));
        q.get(0).rename("Ground floor");
        q.get(1).state(JobQueue.State.DONE, null);
        q.get(2).state(JobQueue.State.RUNNING, null);

        Path dir = Files.createTempDirectory("effortless-queue");
        Path file = dir.resolve(JobStore.FILE_NAME);
        assertTrue(JobStore.save(file, q));
        JobQueue back = JobStore.load(file);

        assertEquals(q.size(), back.size());
        assertEquals("Ground floor", back.get(0).name());
        assertTrue(back.get(0).merged());
        assertEquals(JobQueue.State.DONE, back.get(1).state());
        // mid build when it was saved: back to waiting
        assertEquals(JobQueue.State.QUEUED, back.get(2).state());
        EffortlessJob line = back.get(3).jobs().get(0);
        assertEquals(ShapeSpec.Direction.X, line.spec.direction());
        assertEquals(EffortlessJob.FillMode.OVERWRITE, line.fillMode);
        assertEquals(42, line.palette.seed());
        assertEquals(stair, line.palette.entries().get(0).state());
        assertTrue(line.palette.entries().get(1).randomRotation());
        // the same cells and the same picks, so the preview of a reloaded job is what it was
        assertEquals(q.get(3).jobs().get(0).mask.count(), line.mask.count());
        assertEquals(q.get(3).jobs().get(0).palette.pick(3, 5, 0), line.palette.pick(3, 5, 0));
        EffortlessJob sphere = back.get(4).jobs().get(0);
        assertEquals(ShapeKind.DOME, sphere.spec.kind());
        assertTrue(sphere.spec.centered());
        assertEquals(List.of("#minecraft:logs"), sphere.filter.only());
        assertTrue(sphere.filter.skipContainers());
    }

    @Test
    public void aBrokenFileStartsEmptyAndIsKept() throws Exception {
        Path dir = Files.createTempDirectory("effortless-queue");
        Path file = dir.resolve(JobStore.FILE_NAME);
        Files.writeString(file, "{ not json");
        assertTrue(JobStore.load(file).isEmpty());
        assertTrue(Files.exists(dir.resolve(JobStore.FILE_NAME + ".bad")));
    }

    @Test
    public void storageAndMaterialsComeBack() throws Exception {
        JobQueue q = house();
        q.get(0).materials(baritone.api.utils.EffortlessMaterials.STORAGE);
        q.get(1).materials(baritone.api.utils.EffortlessMaterials.GATHER);
        q.storage().add(new StorageArea(new BlockPos(0, 60, 0), new BlockPos(3, 62, 1), StorageArea.Role.INPUT));
        q.storage().add(new StorageArea(new BlockPos(10, 60, 0), new BlockPos(12, 61, 0), StorageArea.Role.OUTPUT));
        assertEquals(new BlockPos(0, 60, 0), q.input().a());
        assertEquals(new BlockPos(10, 60, 0), q.output().a());
        // merging keeps the first one's source
        q.merge(List.of(0, 2));
        assertEquals(baritone.api.utils.EffortlessMaterials.STORAGE, q.get(0).materials());

        JobQueue back = JobStore.fromJson(JobStore.toJson(q));
        assertEquals(2, back.storage().size());
        assertEquals(StorageArea.Role.OUTPUT, back.storage().get(1).role());
        assertEquals(new BlockPos(12, 61, 0), back.storage().get(1).b());
        assertEquals(baritone.api.utils.EffortlessMaterials.STORAGE, back.get(0).materials());
        assertEquals(baritone.api.utils.EffortlessMaterials.GATHER, back.get(1).materials());
    }
}
