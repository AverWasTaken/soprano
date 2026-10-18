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

package baritone.process;

import adris.altoclef.trace.Trace;
import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.*;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.PathingCommandContext;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

    private static final Set<Property<?>> ORIENTATION_PROPS =
            ImmutableSet.of(
                    RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
                    StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP,
                    TrapDoorBlock.OPEN, TrapDoorBlock.HALF
            );

    private HashSet<BetterBlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    private String name;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    private int layer;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    public int stopAtHeight = 0;
    // stuck in reach bookkeeping, see BuildSkips. builderTicks is its clock, recursions don't count
    private final BuildSkips skips = new BuildSkips();
    private long builderTicks;
    private long lastGateLog = -1000;
    // "only skipped blocks left, trying again" rounds in a row with nothing fixed in between. a protected cell or
    // bedrock where the file wants air would go round forever otherwise, quietly, which is worse than the old pause
    private int skipRetries;
    private int fixesAtRetry;
    private static final int MAX_SKIP_RETRIES = 3;
    // last tick's pick, kept while it still works so the aim doesn't hop to whatever the scan finds first this tick
    private Placement lastPlacement;
    // where the last wouldPlaceWrong would have put it
    private BlockPos gateCell;
    // the corner of the build we're doing, see BuildArea
    private final BuildArea area = new BuildArea();
    private BetterBlockPos loggedAnchor;
    private long lastAreaLog = -1000;
    // keepLastPlacement for breaking: last tick's target while it still needs it, so a half broken block gets finished
    private BetterBlockPos lastBreak;
    private final HotbarHold hotbarHold = new HotbarHold();

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
        }
        if (Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            this.schematic = new MirroredSchematic(this.schematic, Baritone.settings().buildSchematicMirror.value);
        }
        if (Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            this.schematic = new RotatedSchematic(this.schematic, Baritone.settings().buildSchematicRotation.value);
        }
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        this.schematic = new MaskSchematic(this.schematic) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.layer = Baritone.settings().startAtLayer.value;
        this.stopAtHeight = schematic.heightY();
        if (Baritone.settings().buildOnlySelection.value && buildingSelectionSchematic) {  // currently redundant but safer maybe
            if (baritone.getSelectionManager().getSelections().length == 0) {
                logDirect("Poor little kitten forgot to set a selection while BuildOnlySelection is true");
                this.stopAtHeight = 0;
            } else if (Baritone.settings().buildInLayers.value) {
                OptionalInt minim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
                OptionalInt maxim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
                if (minim.isPresent() && maxim.isPresent()) {
                    int startAtHeight = Baritone.settings().layerOrder.value ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
                    this.stopAtHeight = (Baritone.settings().layerOrder.value ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
                    this.layer = Math.max(this.layer, startAtHeight / Baritone.settings().layerHeight.value);  // startAtLayer or startAtHeight, whichever is highest
                    logDebug(String.format("Schematic starts at y=%s with height %s", y, schematic.heightY()));
                    logDebug(String.format("Selection starts at y=%s and ends at y=%s", minim.getAsInt(), maxim.getAsInt()));
                    logDebug(String.format("Considering relevant height %s - %s", startAtHeight, this.stopAtHeight));
                }
            }
        }

        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.incorrectPositions = null;
        forgetSkips();
        hotbarHold.clear();
        skipRetries = 0;
    }

    public void resume() {
        paused = false;
    }

    public void pause() {
        paused = true;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        ISchematic schem = applyMapArtAndSelection(origin, parsed);
        build(name, schem, origin);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        ISchematic schematic = parsed;
        if (Baritone.settings().mapArtMode.value) {
            schematic = new MapArtSchematic(parsed);
        }
        if (Baritone.settings().buildOnlySelection.value) {
            schematic = new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Pair<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().first();
                BlockPos origin = schematic.get().second();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Pair<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.second();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.first());
                build(schematic.first().toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    private Optional<Pair<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        double reach = ctx.playerController().getBlockReachDistance();
        BetterBlockPos last = lastBreak;
        if (last != null && wantsBreak(bcc, last.x, last.y, last.z, center, pathStart)) {
            Optional<Rotation> rot = RotationUtils.reachable(ctx, last, reach);
            if (rot.isPresent()) {
                return Optional.of(new Pair<>(last, rot.get()));
            }
        }
        // the work area's blocks beat the rest, a reachable one outside it is only the fallback
        Pair<BetterBlockPos, Rotation> outside = null;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = Baritone.settings().breakFromAbove.value ? -1 : 0; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (!wantsBreak(bcc, x, y, z, center, pathStart)) {
                        continue;
                    }
                    boolean inArea = area.contains(x, y, z);
                    if (!inArea && outside != null) {
                        continue; // a raytrace that can't win
                    }
                    BetterBlockPos pos = new BetterBlockPos(x, y, z);
                    Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, reach);
                    if (rot.isPresent()) {
                        if (inArea) {
                            return Optional.of(new Pair<>(pos, rot.get()));
                        }
                        outside = new Pair<>(pos, rot.get());
                    }
                }
            }
        }
        return Optional.ofNullable(outside);
    }

    // the same box and rules for the scan and for lastBreak, the two shouldn't disagree
    private boolean wantsBreak(BuilderCalculationContext bcc, int x, int y, int z, BetterBlockPos center, BetterBlockPos pathStart) {
        int dy = y - center.y;
        if (Math.abs(x - center.x) > 5 || Math.abs(z - center.z) > 5 || dy < (Baritone.settings().breakFromAbove.value ? -1 : 0) || dy > 5) {
            return false;
        }
        if (dy == -1 && x == pathStart.x && z == pathStart.z) {
            return false; // dont mine what we're supported by, but not directly standing on
        }
        BlockState curr = bcc.bsi.get0(x, y, z);
        BlockState desired = bcc.getSchematic(x, y, z, curr);
        if (desired == null || skipped(x, y, z)) {
            return false;
        }
        return !(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !valid(curr, desired, false);
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;

        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot) {
            this.hotbarSelection = hotbarSelection;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
        }

        // side is the face of placeAgainst we click, so the block lands one step out of it
        BlockPos target() {
            return placeAgainst.relative(side);
        }
    }

    // desirableOnHotbar gets everything in range, not just what came before the first hit. the slots for the cells
    // scanned after it weren't protected and two blocks kept evicting each other from the same temp slot
    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, Set<BlockState> desirableOnHotbar) {
        BetterBlockPos center = ctx.playerFeet();
        Optional<Placement> found = Optional.empty();
        boolean foundInArea = false;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    if (skipped(x, y, z)) {
                        continue;
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) && !valid(curr, desired, false)) {
                        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock) {
                            continue;
                        }
                        if (desired.getBlock() instanceof AirBlock) {
                            // no item places air, and an empty slot "supplies" it, so this kept empty slots off limits
                            // for the fetch below
                            continue;
                        }
                        desirableOnHotbar.add(desired);
                        boolean inArea = area.contains(x, y, z);
                        if (foundInArea || (found.isPresent() && !inArea)) {
                            continue; // possibleToPlace is all raytraces, only ask it when the answer could change the pick
                        }
                        Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc.bsi);
                        if (opt.isPresent()) {
                            found = opt;
                            foundInArea = inArea;
                        }
                    }
                }
            }
        }
        return found;
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi) {
        return possibleToPlace(toPlace, x, y, z, bsi, null);
    }

    // preferAgainst goes first, so a cell that could be placed from two faces doesn't flip between them every tick
    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi, Direction preferAgainst) {
        // these two don't care which face we're placing against, but they were asked for every face
        // placementPlausible is an entity collision query, so that's up to six of those per position per tick
        // still only asked once we've found something solid to place against, like before
        boolean checkedPlaceable = false;
        for (Direction against : facesFirst(preferAgainst)) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                continue;
            }
            if (!checkedPlaceable) {
                BetterBlockPos pos = new BetterBlockPos(x, y, z);
                if (!toPlace.canSurvive(ctx.world(), pos) || !placementPlausible(pos, toPlace)) {
                    return Optional.empty();
                }
                checkedPlaceable = true;
            }
            VoxelShape shape = placeAgainstState.getShape(ctx.world(), placeAgainstPos);
            if (shape.isEmpty()) {
                continue;
            }
            AABB aabb = shape.bounds();
            for (Vec3 placementMultiplier : aabbSideMultipliers(against)) {
                double placeX = placeAgainstPos.x + aabb.minX * placementMultiplier.x + aabb.maxX * (1 - placementMultiplier.x);
                double placeY = placeAgainstPos.y + aabb.minY * placementMultiplier.y + aabb.maxY * (1 - placementMultiplier.y);
                double placeZ = placeAgainstPos.z + aabb.minZ * placementMultiplier.z + aabb.maxZ * (1 - placementMultiplier.z);
                Rotation rot = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), new Vec3(placeX, placeY, placeZ), ctx.playerRotations());
                Rotation actualRot = baritone.getLookBehavior().getAimProcessor().peekRotation(rot);
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRot, ctx.playerController().getBlockReachDistance(), true);
                if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(placeAgainstPos) && ((BlockHitResult) result).getDirection() == against.getOpposite()) {
                    OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, actualRot);
                    if (hotbar.isPresent()) {
                        return Optional.of(new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), rot));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            float originalYaw = ctx.player().getYRot();
            float originalPitch = ctx.player().getXRot();
            // the state depends on the facing of the player sometimes
            ctx.player().setYRot(rot.getYaw());
            ctx.player().setXRot(rot.getPitch());
            BlockPlaceContext meme = new BlockPlaceContext(new UseOnContext(
                    ctx.world(),
                    ctx.player(),
                    InteractionHand.MAIN_HAND,
                    stack,
                    (BlockHitResult) result
            ) {}); // that {} gives us access to a protected constructor lmfao
            BlockState wouldBePlaced = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(meme);
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
            if (wouldBePlaced == null) {
                continue;
            }
            if (!meme.canPlace()) {
                continue;
            }
            if (valid(wouldBePlaced, desired, true)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    private static Direction[] facesFirst(Direction first) {
        Direction[] all = Direction.values();
        if (first == null) {
            return all;
        }
        Direction[] ordered = new Direction[all.length];
        ordered[0] = first;
        int i = 1;
        for (Direction d : all) {
            if (d != first) {
                ordered[i++] = d;
            }
        }
        return ordered;
    }

    private static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        builderTicks++;
        return onTick(calcFailed, isSafeToCancel, 0);
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
        if (recursions > 100) { // onTick calls itself, don't crash
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
        }
        approxPlaceable = approxPlaceable(36);
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        if (paused) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (recursions == 0) {
            tickArea(calcFailed); // the recursions see the same calcFailed, one release per failure is plenty
        }
        if (Baritone.settings().buildInLayers.value) {
            if (realSchematic == null) {
                realSchematic = schematic;
            }
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            int minYInclusive;
            int maxYInclusive;
            // layer = 0 should be nothing
            // layer = realSchematic.heightY() should be everything
            if (Baritone.settings().layerOrder.value) { // top to bottom
                maxYInclusive = realSchematic.heightY() - 1;
                minYInclusive = realSchematic.heightY() - layer * Baritone.settings().layerHeight.value;
            } else {
                maxYInclusive = layer * Baritone.settings().layerHeight.value - 1;
                minYInclusive = 0;
            }
            schematic = new ISchematic() {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
                }

                @Override
                public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                    return ISchematic.super.inSchematic(x, y, z, currentState) && y >= minYInclusive && y <= maxYInclusive && realSchematic.inSchematic(x, y, z, currentState);
                }

                @Override
                public void reset() {
                    realSchematic.reset();
                }

                @Override
                public int widthX() {
                    return realSchematic.widthX();
                }

                @Override
                public int heightY() {
                    return realSchematic.heightY();
                }

                @Override
                public int lengthZ() {
                    return realSchematic.lengthZ();
                }
            };
        }
        BuilderCalculationContext bcc = new BuilderCalculationContext();
        if (!recalc(bcc)) {
            if (Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < stopAtHeight) {
                logDirect("Starting layer " + layer);
                forgetSkips();
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            Vec3i repeat = Baritone.settings().buildRepeat.value;
            int max = Baritone.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                logDirect("Done building");
                if (Baritone.settings().notificationOnBuildFinished.value) {
                    logNotification("Done building", false);
                }
                onLostControl();
                return null;
            }
            // build repeat time
            layer = 0;
            forgetSkips(); // keyed by world position, and the origin is about to move
            origin = new BlockPos(origin.getX(), origin.getY(), origin.getZ()).offset(repeat);
            if (!Baritone.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        if (Baritone.settings().distanceTrim.value) {
            trim();
        }

        // altoclef pausing interactions means no breaking, no placing and no hotbar shuffling, we just keep walking
        boolean interactionPaused = AltoClefSettings.getInstance().isInteractionPaused();
        Optional<Pair<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
        if (!interactionPaused && toBreak.isPresent() && isSafeToCancel && ctx.player().onGround()) {
            // we'd like to pause to break this block
            // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
            Rotation rot = toBreak.get().second();
            BetterBlockPos pos = toBreak.get().first();
            lastBreak = pos;
            baritone.getLookBehavior().updateTarget(rot, true);
            if (ctx.player().isCrouching()) {
                // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                // and is unable since it's unsneaked in the intermediary tick
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot)) {
                // the pick comes out on the swing, not while we're still turning. picking it every tick fought the
                // place branch over the hotbar on the ticks the two took turns
                MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            // cancelling the path for a block that never breaks (protected, bedrock, can't aim at it) used to be forever
            stuckOn(bcc, pos, breakLimit(pos), true);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        // a set, the box is 847 cells and most of them want the same three blocks
        Set<BlockState> desirableOnHotbar = new LinkedHashSet<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        if (!interactionPaused && toPlace.isPresent() && isSafeToCancel && ctx.player().onGround() && ticks <= 0) {
            Placement plan = keepLastPlacement(bcc).orElse(toPlace.get());
            lastPlacement = plan;
            baritone.getLookBehavior().updateTarget(plan.rot, true);
            boolean sneaking = ctx.player().isShiftKeyDown();
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            // the old gate compared the crosshair (aimed at last tick's pick, plus aim noise) to this tick's pick and
            // basically never clicked. the plan is a guess from a sneaking eye, the click is a raytrace from the real one,
            // so ask the real one: would a click right now put the right state into a cell that wants it
            OptionalInt slot = crosshairPlaceSlot(bcc, plan);
            // one slot write, and only on a tick it matters. writing the plan's slot and then the crosshair's every tick
            // took turns whenever the two disagreed. useItemOn sends the carried item first, so picking on the click
            // tick is fine
            if (slot.isPresent()) {
                ctx.player().getInventory().setSelectedSlot(slot.getAsInt());
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            } else if (!sneaking) {
                // the sneak lands next tick and the click can go out with it
                ctx.player().getInventory().setSelectedSlot(plan.hotbarSelection);
            }
            stuckOn(bcc, plan.target(), BuildSkips.STUCK_TICKS, false);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (!interactionPaused && Baritone.settings().allowInventory.value) {
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            outer:
            for (BlockState desired : desirableOnHotbar) {
                for (int i = 0; i < 9; i++) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        // a throttled fetch used to pause until it went through. one move a tick means that was a pause
                        // every other tick, every pause lets go of the keys and the dig with them, and a dig down never
                        // finished before its movement timed out. so walk on, the move is remembered and goes next tick
                        OptionalInt into = baritone.getInventoryBehavior().fetchToHotbar(i, hotbarHold.tiers(usefulSlots::contains, builderTicks), "builder");
                        if (into.isPresent()) {
                            hotbarHold.fetched(into.getAsInt(), builderTicks);
                        }
                        break outer;
                    }
                }
            }
        }

        Goal goal = assemble(bcc, approxPlaceable.subList(0, 9));
        if (goal == null) {
            goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
            if (goal == null) {
                if (skips.anySkipped(builderTicks)) {
                    if (skips.fixes() != fixesAtRetry) {
                        skipRetries = 0;
                        fixesAtRetry = skips.fixes();
                    }
                    if (skipRetries++ < MAX_SKIP_RETRIES) {
                        // all that's left is stuff we gave up on for a bit. that's not "unable to do it" and not a reason to
                        // skip a layer, so give it all another go
                        logDebug("only skipped blocks left, trying them again");
                        forgetSkips();
                        return onTick(calcFailed, isSafeToCancel, recursions + 1);
                    }
                    // same blocks given up on round after round with nothing fixed in between, they aren't happening.
                    // the old rules from here on
                    skipRetries = 0;
                }
                if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
                    logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                    forgetSkips();
                    layer++;
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
                forgetSkips(); // a resume gets a clean slate, not straight back here
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }

    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                return false;
            }
        }
        recalcNearby(bcc);
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
    }

    private void trim() {
        HashSet<BetterBlockPos> copy = new HashSet<>(incorrectPositions);
        copy.removeIf(pos -> pos.distSqr(ctx.player().blockPosition()) > 200);
        if (!copy.isEmpty()) {
            incorrectPositions = copy;
        }
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Baritone.settings().builderTickScanRadius.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (valid(bcc.bsi.get0(x, y, z), desired, false)) {
                            incorrectPositions.remove(pos);
                            observedCompleted.add(BetterBlockPos.longHash(pos));
                        } else {
                            incorrectPositions.add(pos);
                            observedCompleted.remove(BetterBlockPos.longHash(pos));
                        }
                    }
                }
            }
        }
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        incorrectPositions = new HashSet<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (valid(bcc.bsi.get0(blockX, blockY, blockZ), schematic.desiredState(x, y, z, current, this.approxPlaceable), false)) {
                            observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                        } else {
                            incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                            observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                                return;
                            }
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                        if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
        return assemble(bcc, approxPlaceable, false);
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Map<BlockState, Integer> missing = new HashMap<>();
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        incorrectPositions.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (state.getBlock() instanceof AirBlock) {
                BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
                if (desired == null) {
                    outOfBounds.add(pos);
                } else if (containsBlockState(approxPlaceable, desired)) {
                    placeable.add(pos);
                } else {
                    missing.put(desired, 1 + missing.getOrDefault(desired, 0));
                }
            } else {
                if (state.getBlock() instanceof LiquidBlock) {
                    // if the block itself is JUST a liquid (i.e. not just a waterlogged block), we CANNOT break it
                    // TODO for 1.13 make sure that this only matches pure water, not waterlogged blocks
                    if (!MovementHelper.possiblyFlowing(state)) {
                        // if it's a source block then we want to replace it with a throwaway
                        sourceLiquids.add(pos);
                    } else {
                        flowingLiquids.add(pos);
                    }
                } else {
                    breakable.add(pos);
                }
            }
        });
        incorrectPositions.removeAll(outOfBounds);
        List<BetterBlockPos> breakCells = new ArrayList<>();
        List<BetterBlockPos> placeCells = new ArrayList<>();
        List<BetterBlockPos> liquidCells = new ArrayList<>();
        List<BetterBlockPos> skippedCells = new ArrayList<>();
        breakable.forEach(pos -> (skipped(pos.x, pos.y, pos.z) ? skippedCells : breakCells).add(pos));
        // List.contains for every position was quadratic, and placeable is in the thousands on a big build
        Set<BetterBlockPos> placeableSet = new HashSet<>(placeable);
        placeable.forEach(pos -> {
            // skipped ones stay in placeableSet, or the block above a skipped hole would become a goal over thin air
            if (skipped(pos.x, pos.y, pos.z)) {
                skippedCells.add(pos);
            } else if (!placeableSet.contains(pos.below()) && !placeableSet.contains(pos.below(2))) {
                placeCells.add(pos);
            }
        });
        sourceLiquids.forEach(pos -> (skipped(pos.x, pos.y, pos.z) ? skippedCells : liquidCells).add(pos));

        // nothing to do is decided on the whole build, the work area only gets a say in where we walk
        if (placeCells.isEmpty() && liquidCells.isEmpty() && breakCells.isEmpty()) {
            if (logMissing && !missing.isEmpty()) {
                logDirect("Missing materials for at least:");
                logDirect(missing.entrySet().stream()
                        .map(e -> String.format("%sx %s", e.getValue(), e.getKey()))
                        .collect(Collectors.joining("\n")));
            }
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return null;
        }
        List<BetterBlockPos> open = new ArrayList<>(placeCells);
        open.addAll(liquidCells);
        open.addAll(breakCells);
        List<BetterBlockPos> giveUp = area.choose(open, skippedCells, ctx.playerFeet(), builderTicks);
        if (!giveUp.isEmpty()) {
            giveUpOn(bcc, giveUp);
        }
        noteAnchor();
        Goal goal = goalFrom(bcc, placeCells, liquidCells, breakCells, true);
        if (goal == null) {
            goal = goalFrom(bcc, placeCells, liquidCells, breakCells, false); // nothing near the anchor this tick
        }
        return goal;
    }

    // null if none of the cells make it in
    private Goal goalFrom(BuilderCalculationContext bcc, List<BetterBlockPos> placeCells, List<BetterBlockPos> liquidCells, List<BetterBlockPos> breakCells, boolean inAreaOnly) {
        List<Goal> toPlace = new ArrayList<>();
        List<Goal> toBreak = new ArrayList<>();
        for (BetterBlockPos pos : placeCells) {
            if (goalWorthy(pos, inAreaOnly)) {
                toPlace.add(placementGoal(pos, bcc));
            }
        }
        for (BetterBlockPos pos : liquidCells) {
            if (goalWorthy(pos, inAreaOnly)) {
                toPlace.add(new GoalBlock(pos.above()));
            }
        }
        for (BetterBlockPos pos : breakCells) {
            if (goalWorthy(pos, inAreaOnly)) {
                toBreak.add(breakGoal(pos, bcc));
            }
        }
        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (toBreak.isEmpty()) {
            return null;
        }
        return new GoalComposite(toBreak.toArray(new Goal[0]));
    }

    // asked again because giveUpOn may have just skipped it
    private boolean goalWorthy(BetterBlockPos pos, boolean inAreaOnly) {
        return !skipped(pos.x, pos.y, pos.z) && (!inAreaOnly || area.contains(pos.x, pos.y, pos.z));
    }

    // the area ran out of time with nowhere else to go, see BuildArea.choose. a limit of one makes this tick the one
    // that goes over, the same skip a block gets for being stood on too long
    private void giveUpOn(BuilderCalculationContext bcc, List<BetterBlockPos> cells) {
        logDebug(String.format("the last area left isn't getting anywhere, skipping its %s blocks for %ss", cells.size(), BuildSkips.SKIP_TICKS / 20));
        for (BetterBlockPos p : cells) {
            boolean breaking = !MovementHelper.isReplaceable(p.x, p.y, p.z, bcc.bsi.get0(p.x, p.y, p.z), bcc.bsi);
            skips.work(serialize(p), builderTicks, 1, breaking);
        }
        lastPlacement = null;
        lastBreak = null;
    }

    private void tickArea(boolean calcFailed) {
        BetterBlockPos anchor = area.anchor();
        if (calcFailed && anchor != null) {
            areaLog("can't get to the area around " + anchor.x + " " + anchor.y + " " + anchor.z + ", trying somewhere else");
            area.release(builderTicks, true);
        }
        if (area.clock(ctx.playerFeet(), builderTicks, skips.fixes())) {
            areaLog("nothing got done around " + anchor.x + " " + anchor.y + " " + anchor.z + " in " + BuildArea.STALE_TICKS / 20 + "s, trying somewhere else");
        }
    }

    private void noteAnchor() {
        BetterBlockPos anchor = area.anchor();
        if (anchor == null || anchor.equals(loggedAnchor)) {
            return;
        }
        loggedAnchor = anchor;
        areaLog("working around " + anchor.x + " " + anchor.y + " " + anchor.z);
    }

    // into the trace every time, into chat (chatDebug) at most once a second
    private void areaLog(String line) {
        Trace.record("builder: " + line);
        if (builderTicks - lastAreaLog >= 20) {
            lastAreaLog = builderTicks;
            logDebug(line);
        }
    }

    public static class JankyGoalComposite implements Goal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            JankyGoalComposite goal = (JankyGoalComposite) o;
            return Objects.equals(primary, goal.primary)
                    && Objects.equals(fallback, goal.fallback);
        }

        @Override
        public int hashCode() {
            int hash = -1701079641;
            hash = hash * 1196141026 + primary.hashCode();
            hash = hash * -80327868 + fallback.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBreak{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1636324008;
        }
    }

    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {  // TODO can this even happen?
            return new GoalPlace(pos);
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        BlockState current = ctx.world().getBlockState(pos);
        for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            //noinspection ConstantConditions
            if (MovementHelper.canPlaceAgainst(ctx, pos.relative(facing)) && placementPlausible(pos, bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current))) {
                return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
            }
        }
        return new GoalPlace(pos);
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        return new GoalBreak(pos);
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            GoalAdjacent goal = (GoalAdjacent) o;
            return allowSameLevel == goal.allowSameLevel
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            return hash;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalAdjacent{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1910811835;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlace{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    // the area resets with the skips: every caller is a fresh start (build, lost control, a layer, a repeat, the
    // retry, the pause) and an anchor or a ban from before it means nothing after
    private void forgetSkips() {
        skips.clear();
        lastPlacement = null;
        lastBreak = null;
        area.reset();
        loggedAnchor = null;
    }

    private boolean skipped(int x, int y, int z) {
        return skips.isSkipped(BetterBlockPos.serializeToLong(x, y, z), builderTicks);
    }

    // last tick's placement again, if its cell still wants a block and the same face still works from here
    private Optional<Placement> keepLastPlacement(BuilderCalculationContext bcc) {
        Placement last = lastPlacement;
        if (last == null) {
            return Optional.empty();
        }
        BlockPos cell = last.target();
        int x = cell.getX();
        int y = cell.getY();
        int z = cell.getZ();
        if (skipped(x, y, z)) {
            return Optional.empty();
        }
        BlockState curr = bcc.bsi.get0(x, y, z);
        BlockState desired = bcc.getSchematic(x, y, z, curr);
        if (desired == null || !MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) || valid(curr, desired, false)) {
            return Optional.empty();
        }
        // same box and rules searchForPlacables has, the two shouldn't disagree
        BetterBlockPos feet = ctx.playerFeet();
        int dy = y - feet.y;
        if (Math.abs(x - feet.x) > 5 || Math.abs(z - feet.z) > 5 || dy < -5 || dy > 1) {
            return Optional.empty();
        }
        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock) {
            return Optional.empty();
        }
        return possibleToPlace(desired, x, y, z, bcc.bsi, last.side.getOpposite());
    }

    // which hotbar slot, if any, makes a right click on the current crosshair do the right thing. the plan's slot first
    private OptionalInt crosshairPlaceSlot(BuilderCalculationContext bcc, Placement plan) {
        // objectMouseOver is the exact raytrace BlockPlaceHelper clicks with later this tick, server rotation and all
        HitResult mouseOver = ctx.objectMouseOver();
        if (!(mouseOver instanceof BlockHitResult hit) || mouseOver.getType() != HitResult.Type.BLOCK) {
            gateLog("crosshair isn't on a block");
            return OptionalInt.empty();
        }
        if (!ctx.player().isShiftKeyDown()) {
            // the sneak we just asked for lands on the player tick. until then the eye is standing height (aim is off
            // by a third of a block, wrong slab half) and the click would open a chest instead of placing on it
            gateLog("not sneaking yet");
            return OptionalInt.empty();
        }
        String why = null;
        for (int i = -1; i < 9; i++) {
            int slot = i < 0 ? plan.hotbarSelection : i;
            if (i == plan.hotbarSelection) {
                continue;
            }
            String reason = wouldPlaceWrong(bcc, hit, slot);
            if (reason == null) {
                // it can land somewhere other than the plan's cell, watch that one too or a good click looks like no progress
                skips.watch(serialize(gateCell), builderTicks);
                return OptionalInt.of(slot);
            }
            if (why == null) {
                why = reason; // the plan's slot is the one worth hearing about
            }
        }
        gateLog(why);
        return OptionalInt.empty();
    }

    // null if right clicking hit with this slot puts what the schematic wants into a cell that wants it, else why not
    private String wouldPlaceWrong(BuilderCalculationContext bcc, BlockHitResult hit, int slot) {
        ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            return "slot " + slot + " has no block in it";
        }
        // stairs and logs read the rotation off the player, and the server only knows the one we last sent it
        Rotation look = ctx.playerRotations();
        float yaw = ctx.player().getYRot();
        float pitch = ctx.player().getXRot();
        ctx.player().setYRot(look.getYaw());
        ctx.player().setXRot(look.getPitch());
        BlockPlaceContext place;
        BlockState wouldBe;
        try {
            place = new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, hit) {});
            wouldBe = place.canPlace() ? item.getBlock().getStateForPlacement(place) : null;
        } finally {
            ctx.player().setYRot(yaw);
            ctx.player().setXRot(pitch);
        }
        // vanilla's own answer for where it lands, snow and half slabs get replaced in place instead of next to
        BlockPos cell = place.getClickedPos();
        gateCell = cell;
        BlockState curr = bcc.bsi.get0(cell);
        BlockState desired = bcc.getSchematic(cell.getX(), cell.getY(), cell.getZ(), curr);
        if (desired == null) {
            return "it would land at " + cell.toShortString() + ", outside the schematic";
        }
        if (valid(curr, desired, false)) {
            return "it would land at " + cell.toShortString() + ", which is already right";
        }
        if (wouldBe == null) {
            return "can't place into " + cell.toShortString();
        }
        if (!valid(wouldBe, desired, true)) {
            return "it would place " + wouldBe + " at " + cell.toShortString() + ", the schematic wants " + desired;
        }
        if (!wouldBe.canSurvive(ctx.world(), cell) || !placementPlausible(cell, wouldBe)) {
            return "something's in the way at " + cell.toShortString();
        }
        return null;
    }

    private void gateLog(String why) {
        // once a second is plenty, it gets asked every tick
        if (builderTicks - lastGateLog < 20) {
            return;
        }
        lastGateLog = builderTicks;
        logDebug("not clicking yet: " + why);
    }

    // twice the honest break time on top of the usual, a stone pick on obsidian isn't stuck, it's just sad
    private int breakLimit(BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        float perTick = state.getDestroyProgress(ctx.player(), ctx.world(), pos);
        if (perTick <= 0) {
            return BuildSkips.STUCK_TICKS; // bedrock and friends, clicking harder won't help
        }
        return (int) Math.min(BuildSkips.STUCK_TICKS + 2 * Math.ceil(1 / perTick), 20 * 120);
    }

    // one more tick stood still in reach of pos. first see if anything we were stuck on got done meanwhile
    private void stuckOn(BuilderCalculationContext bcc, BlockPos pos, int limit, boolean breaking) {
        for (long p : skips.watched()) {
            BetterBlockPos w = BetterBlockPos.deserializeFromLong(p);
            BlockState curr = bcc.bsi.get0(w.x, w.y, w.z);
            BlockState desired = bcc.getSchematic(w.x, w.y, w.z, curr);
            if (desired == null || valid(curr, desired, false)) {
                skips.looksFixed(p, builderTicks);
            } else {
                skips.looksWrong(p);
            }
        }
        long lastTarget = lastPlacement == null ? Long.MIN_VALUE : serialize(lastPlacement.target());
        for (long p : skips.work(serialize(pos), builderTicks, limit, breaking)) {
            BetterBlockPos s = BetterBlockPos.deserializeFromLong(p);
            BlockState curr = bcc.bsi.get0(s.x, s.y, s.z);
            // the window can skip a few at once, so ask each one what it was instead of trusting this tick
            String what = MovementHelper.isReplaceable(s.x, s.y, s.z, curr, bcc.bsi)
                    ? "place " + bcc.getSchematic(s.x, s.y, s.z, curr)
                    : "break " + curr.getBlock();
            logDebug(String.format("couldn't %s at %s %s %s, skipping it for %ss", what, s.x, s.y, s.z, BuildSkips.SKIP_TICKS / 20));
            if (p == lastTarget) {
                lastPlacement = null;
            }
        }
    }

    private static long serialize(BlockPos pos) {
        return BetterBlockPos.serializeToLong(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public void onLostControl() {
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
        forgetSkips();
        hotbarHold.clear();
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    @Override
    public Optional<Integer> getMinLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.layer);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.stopAtHeight);
        }
        return Optional.empty();
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            // <toxic cloud>
            BlockState itemState = ((BlockItem) stack.getItem())
                .getBlock()
                .getStateForPlacement(
                    new BlockPlaceContext(
                        new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}
                    )
                );
            if (itemState != null) {
                result.add(itemState);
            } else {
                result.add(Blocks.AIR.defaultBlockState());
            }
            // </toxic cloud>
        }
        return result;
    }

    private static boolean sameBlockstate(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        boolean ignoreDirection = Baritone.settings().buildIgnoreDirection.value;
        List<String> ignoredProps = Baritone.settings().buildIgnoreProperties.value;
        if (!ignoreDirection && ignoredProps.isEmpty()) {
            return first.equals(second); // early return if no properties are being ignored
        }
        for (Property<?> prop : first.getProperties()) {
            if (!Objects.equals(first.getValue(prop), second.getValue(prop))
                    && !(ignoreDirection && ORIENTATION_PROPS.contains(prop))
                    && !ignoredProps.contains(prop.getName())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameBlockstate(testee, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockstate(current, desired);
    }

    public class BuilderCalculationContext extends CalculationContext {

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        private final int originX;
        private final int originY;
        private final int originZ;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPlaceProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return placeBlockCost * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value; // we're going to have to break it eventually
                }
                if (placeable.contains(sch)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                if (!hasThrowaway) {
                    return COST_INF;
                }
                // we want it to be something that we don't have
                // even more of a pain to place something wrong
                return placeBlockCost * 1.5 * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value;
            } else {
                if (hasThrowaway) {
                    return placeBlockCost;
                } else {
                    return COST_INF;
                }
            }
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if ((!allowBreak && !allowBreakAnyway.contains(current.getBlock())) || isBreakProtected(x, y, z)) {
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false)) {
                    return Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return 1; // why not lol
            }
        }
    }
}
