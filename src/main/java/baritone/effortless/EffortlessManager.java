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

import baritone.Baritone;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.process.IBuilderProcess;
import baritone.api.utils.IPlayerContext;
import baritone.utils.IRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// the state of the build mode for one baritone: the shape picked, the clicks so far, and the jobs it starts. the
// command drives it for now, the radial and the clicks will later. it also draws the outline of what you're setting up
public final class EffortlessManager implements AbstractGameEventListener {

    private static final double GROW = 0.002;

    private final Baritone baritone;
    private ShapeSpec spec = ShapeSpec.of(ShapeKind.CUBOID);
    private final List<BlockPos> points = new ArrayList<>();
    // the last shape drawn, so the outline only rebuilds the mask when the clicks or the crosshair move
    private List<BlockPos> previewKey;
    private ShapeSpec previewSpec;
    private AreaMask preview;
    private EffortlessJob running;
    private JobSchematic runningSchematic;
    // what the next job will be, for the preview: place or break and the options it was set up with. null mode is
    // "no idea yet", the outline still shows but no cells do
    private EffortlessJob.Mode draftMode;
    private EffortlessOptions draftOptions;
    // the preview job, rebuilt only when what it depends on changes
    private Object previewJobKey;
    private JobSchematic previewSchematic;

    public EffortlessManager(Baritone baritone) {
        this.baritone = baritone;
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public ShapeSpec spec() {
        return spec;
    }

    public void spec(ShapeSpec newSpec) {
        this.spec = newSpec;
    }

    public List<BlockPos> points() {
        return List.copyOf(points);
    }

    // index 0 based. setting point 2 with no point 1 is fine, the gaps get the crosshair until they're set
    public void point(int index, BlockPos pos) {
        while (points.size() <= index) {
            points.add(null);
        }
        points.set(index, pos.immutable());
    }

    public void clearPoints() {
        points.clear();
    }

    // the points the shape gets: the set ones in order, and when the shape wants more, the block under the crosshair
    // for the next one, so the outline follows your look. empty when there's nothing to go on
    public List<BlockPos> effectivePoints(boolean fillWithCrosshair) {
        List<BlockPos> out = new ArrayList<>();
        Optional<BlockPos> look = fillWithCrosshair ? crosshair() : Optional.empty();
        for (BlockPos p : points) {
            if (p != null) {
                out.add(p);
            } else if (look.isPresent()) {
                out.add(look.get());
            } else {
                return out;
            }
        }
        if (!out.isEmpty() && out.size() < spec.kind().clicks(Baritone.settings().effortlessThreeClickHeight.value) && look.isPresent()) {
            out.add(look.get());
        }
        return out;
    }

    public Optional<BlockPos> crosshair() {
        IPlayerContext ctx = baritone.getPlayerContext();
        if (ctx.player() == null) {
            return Optional.empty();
        }
        // in build mode the next corner is where a click would land: in front of the face for a place, in the air
        // when there's nothing in reach
        if (BuildMode.active()) {
            return BuildMode.crosshair();
        }
        return ctx.getSelectedBlock();
    }

    // the cells of the shape as set up right now, null when there aren't enough points
    public AreaMask currentMask(boolean fillWithCrosshair) {
        List<BlockPos> pts = effectivePoints(fillWithCrosshair);
        if (pts.isEmpty()) {
            return null;
        }
        if (pts.equals(previewKey) && spec.equals(previewSpec)) {
            return preview;
        }
        previewKey = pts;
        previewSpec = spec;
        try {
            preview = ShapeGeometry.build(spec, pts);
        } catch (ShapeGeometry.TooBig e) {
            preview = null;
        }
        return preview;
    }

    // ----- making jobs

    public static final class NotReady extends Exception {
        public NotReady(String message) {
            super(message);
        }
    }

    // the set points only, no crosshair: a job is what you clicked, not where you happen to be looking
    public List<BlockPos> jobPoints() throws NotReady {
        List<BlockPos> pts = new ArrayList<>();
        for (BlockPos p : points) {
            if (p == null) {
                break;
            }
            pts.add(p);
        }
        if (pts.isEmpty()) {
            throw new NotReady("set the first point first (#ef p1)");
        }
        if (pts.size() < spec.kind().clicks) {
            throw new NotReady("a " + spec.kind().title.toLowerCase() + " needs " + spec.kind().clicks + " points, you've set " + pts.size());
        }
        return pts;
    }

    public void checkSize(EffortlessJob job) throws NotReady {
        int max = Baritone.settings().effortlessMaxBlocks.value;
        if (job.mask.count() > max) {
            throw new NotReady("that's " + job.mask.count() + " blocks, more than effortlessMaxBlocks (" + max + ")");
        }
        if (job.mask.isEmpty()) {
            throw new NotReady("that shape has no blocks in it");
        }
    }

    // the block in the main hand (or offhand), as the state it places by default. empty when it isn't a block
    public Optional<BlockState> heldBlock(boolean offhand) {
        IPlayerContext ctx = baritone.getPlayerContext();
        if (ctx.player() == null) {
            return Optional.empty();
        }
        ItemStack stack = offhand ? ctx.player().getOffhandItem() : ctx.player().getMainHandItem();
        if (stack.getItem() instanceof BlockItem bi) {
            return Optional.of(bi.getBlock().defaultBlockState());
        }
        return Optional.empty();
    }

    // hands the job to the builder right away. the queue comes later, this is "start now"
    public void start(EffortlessJob job) {
        running = job;
        JobSchematic schematic = new JobSchematic(job);
        runningSchematic = schematic;
        baritone.getBuilderProcess().buildInPlace(job.name, schematic, schematic.origin(), Baritone.settings().effortlessJumpOff.value);
        // what the job leaves alone inside its area stays standing: a path that digs through the dirt under "break only
        // stone" is the builder breaking the one thing you told it not to. kept blocks price like finished ones
        baritone.getBuilderProcess().setKeep(keepUntouched(schematic));
    }

    // what the preview should show for the next job, or null when there's nothing set up to show
    public void draft(EffortlessJob.Mode mode, EffortlessOptions options) {
        this.draftMode = mode;
        this.draftOptions = options;
    }

    public EffortlessJob.Mode draftMode() {
        return draftMode;
    }

    // the job the current points (plus the crosshair for the missing ones) and the draft would make. null when there
    // are no points, no draft, or the draft can't make a job here (no block in hand, too big)
    public JobSchematic previewSchematic() {
        if (points.isEmpty() || draftMode == null || draftOptions == null) {
            return null;
        }
        AreaMask mask = currentMask(true);
        if (mask == null || mask.isEmpty() || mask.count() > Baritone.settings().effortlessMaxBlocks.value) {
            return null;
        }
        List<BlockPos> pts = effectivePoints(true);
        Optional<BlockState> hand = heldBlock(false);
        Optional<BlockState> off = heldBlock(true);
        List<Object> key = List.of(pts, spec, draftMode, draftOptions, hand, off);
        if (key.equals(previewJobKey)) {
            return previewSchematic;
        }
        previewJobKey = key;
        try {
            EffortlessJob job = draftMode == EffortlessJob.Mode.PLACE
                    ? draftOptions.withSpec(spec).placeJob(pts, hand, off)
                    : draftOptions.withSpec(spec).breakJob(pts, hand, off);
            previewSchematic = new JobSchematic(job);
        } catch (EffortlessOptions.Bad | RuntimeException e) {
            previewSchematic = null;
        }
        return previewSchematic;
    }

    // the job the builder is on, while it is
    public JobSchematic runningSchematic() {
        return running() != null ? runningSchematic : null;
    }

    // a block in the area the job won't change. pure: the builder asks from its path thread
    static IBuilderProcess.Keep keepUntouched(JobSchematic schematic) {
        return (x, y, z, current) -> {
            if (current.isAir()) {
                return false;
            }
            EffortlessJob job = schematic.owner(x, y, z);
            return job != null && job.outcome(x, y, z, current) == EffortlessJob.Outcome.UNTOUCHED;
        };
    }

    // a finished shape from build mode. the queue comes later, for now every job starts right away
    public void submit(EffortlessJob job) {
        start(job);
    }

    public EffortlessJob running() {
        return running != null && baritone.getBuilderProcess().isActive() ? running : null;
    }

    // ----- the outline

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN && event.getState() == EventState.PRE) {
            BuildMode.tick();
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (points.isEmpty()) {
            return;
        }
        AreaMask mask = currentMask(true);
        PoseStack stack = event.getModelViewStack();
        float width = Baritone.settings().selectionLineWidth.value;
        BufferBuilder buf = IRenderer.startLines(Baritone.settings().colorEffortlessOutline.value, 0.9f);
        if (mask != null && !mask.isEmpty()) {
            IRenderer.emitAABB(buf, stack, new AABB(mask.minX, mask.minY, mask.minZ, mask.maxX() + 1, mask.maxY() + 1, mask.maxZ() + 1), GROW, width);
        }
        for (BlockPos p : points) {
            if (p != null) {
                IRenderer.emitAABB(buf, stack, new AABB(p), 0.01, width);
            }
        }
        IRenderer.endLines(buf, false);
    }
}
