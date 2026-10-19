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
import baritone.utils.ExperimentalMovement;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    private BuildSkips skips = new BuildSkips();
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
    // bumped by every start, so whoever kicked off a build can tell later if it's still theirs or a #build took over
    private long buildSerial;
    // what the logMissing pass found with no item for it, kept for whoever wants to go and get it (SchematicBuildTask).
    // no lock: the builder, the gui and altoclef all run on the game thread
    private Map<BlockState, Integer> missingAtPause = Map.of();
    // whoever started the build gets to say which wrong blocks are fine anyway (SchematicBuildTask's scaffolding, so the
    // builder stops mining the pillar it is standing on). volatile because the path thread reads it through the bcc
    private volatile Keep keep;
    // the corner of the build we're doing, see BuildArea
    private BuildArea area = new BuildArea();
    private BetterBlockPos loggedAnchor;
    private long lastAreaLog = -1000;
    private long lastAnchorLog = -1000;
    private long lastSwing = -1000;
    // keepLastPlacement for breaking: last tick's target while it still needs it, so a half broken block gets finished
    private BetterBlockPos lastBreak;
    private HotbarHold hotbarHold = new HotbarHold();
    // the clicks still down are ours. onLostControl lets go of exactly those, once: it runs for every loser every tick
    // and clearAllKeys there would wipe whatever the winner just pressed
    private boolean forcedClicks;
    // the hotbar slots the last inventory pass called useful, so the clutch's ladder doesn't move into them
    private int usefulSlotMask;
    // last pos the trace heard about a break of, one line per block and not one per tick of digging
    private BetterBlockPos tracedBreak;
    // cells noStandingRoom already said so about, it's asked every tick
    private LongOpenHashSet noRoomLogged;
    // ticks stood still inside our own goal with neither click branch going. the pather won't move us (we're already
    // there) and nothing else will either, so after IDLE_LIMIT of them the cells that put us here get let go of
    private int idleInGoal;
    private BetterBlockPos idleFeet;
    private static final int IDLE_LIMIT = 20;
    // last break the chat line went out for, see logBreakStart. apart from tracedBreak, a place doesn't reset this one
    private BetterBlockPos loggedBreak;
    private long lastNoGoalLog = -1000;
    // what the assemble pass that made this tick's goal thought we can place, so ownGoal asks the same question
    private List<BlockState> goalPlaceable;

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        start(name, schematic, origin, Baritone.settings().buildSchematicRotation.value,
                Baritone.settings().buildSchematicMirror.value, true);
    }

    @Override
    public void build(String name, IStaticSchematic schematic, Vec3i origin, net.minecraft.world.level.block.Rotation rotation,
                      net.minecraft.world.level.block.Mirror mirror) {
        // map art reads the raw columns, so it goes under the transform. the selection is in world space, and origin is
        // already the corner of the turned footprint, so it goes over it (the global setting path does it the other way
        // round, which only lines up when nothing is rotated)
        ISchematic placed = placed(schematic, rotation, mirror);
        start(name, onlySelection(placed, origin), origin, net.minecraft.world.level.block.Rotation.NONE,
                net.minecraft.world.level.block.Mirror.NONE, false);
    }

    // the old entry point with the orientation pulled out. useOrientation = add the schematicOrientation offsets, which
    // only the global setting path wants: they use the unturned size and would push a placed origin off its corner
    private void start(String name, ISchematic schematic, Vec3i origin, net.minecraft.world.level.block.Rotation rotation,
                       net.minecraft.world.level.block.Mirror mirror, boolean useOrientation) {
        this.buildSerial++;
        this.missingAtPause = Map.of();
        // a #build from chat doesn't get to inherit somebody else's pillar
        this.keep = null;
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
        }
        this.schematic = SchematicTransforms.apply(this.schematic, rotation, mirror);
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
        if (useOrientation && Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (useOrientation && Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (useOrientation && Baritone.settings().schematicOrientationZ.value) {
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
        // a fresh one instead of clear(), BuilderPlacedBuildTest makes the builder with Unsafe and skips the initializers
        this.skips = new BuildSkips();
        this.lastPlacement = null;
        // same reason, and start doesn't go through forgetSkips here
        this.area = new BuildArea();
        this.hotbarHold = new HotbarHold();
        this.usefulSlotMask = 0;
        this.lastBreak = null;
        this.loggedAnchor = null;
        skipRetries = 0;
    }

    public void resume() {
        paused = false;
        missingAtPause = Map.of();
    }

    public void pause() {
        paused = true;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public Map<BlockState, Integer> missingAtPause() {
        return missingAtPause;
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
        return onlySelection(mapArt(parsed), origin);
    }

    // the file laid out the way the build above lays it out, before the selection, substitutes and skip blocks. public so
    // SchematicBuildTask prices the rest of a build off the same blocks the builder will ask for
    public static ISchematic placed(IStaticSchematic schematic, net.minecraft.world.level.block.Rotation rotation,
                                    net.minecraft.world.level.block.Mirror mirror) {
        return SchematicTransforms.apply(mapArt(schematic), rotation, mirror);
    }

    private static ISchematic mapArt(IStaticSchematic parsed) {
        return Baritone.settings().mapArtMode.value ? new MapArtSchematic(parsed) : parsed;
    }

    private ISchematic onlySelection(ISchematic schematic, Vec3i origin) {
        if (Baritone.settings().buildOnlySelection.value) {
            return new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
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
    public void setKeep(Keep keep) {
        this.keep = keep;
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    @Override
    public long buildSerial() {
        // onLostControl nulls the schematic, so a finished or cancelled build reads as none
        return schematic != null ? buildSerial : 0;
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
        return !(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !bcc.fine(x, y, z, curr, desired);
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
        forcedClicks = false;
        if (paused) {
            idleInGoal = 0;
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
            dropPathBeforeClicking();
            idleInGoal = 0;
            Rotation rot = toBreak.get().second();
            BetterBlockPos pos = toBreak.get().first();
            lastBreak = pos;
            lastSwing = builderTicks;
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
                forcedClicks = true;
                traceBreak(bcc, pos);
                logBreakStart(bcc, pos);
            }
            // cancelling the path for a block that never breaks (protected, bedrock, can't aim at it) used to be forever
            stuckOn(bcc, pos, breakLimit(pos), true);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        // a set, the box is 847 cells and most of them want the same three blocks
        Set<BlockState> desirableOnHotbar = new LinkedHashSet<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        if (!interactionPaused && toPlace.isPresent() && isSafeToCancel && ctx.player().onGround() && ticks <= 0) {
            dropPathBeforeClicking();
            idleInGoal = 0;
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
                forcedClicks = true;
                tracePlace(bcc);
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
                    if (canSupply(approxPlaceable.get(i), desired)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }
            int mask = 0;
            for (int i : usefulSlots) {
                mask |= 1 << i;
            }
            usefulSlotMask = mask;

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (canSupply(approxPlaceable.get(i), desired)) {
                        // a throttled fetch used to pause until it went through. one move a tick means that was a pause
                        // every other tick, every pause lets go of the keys and the dig with them, and a dig down never
                        // finished before its movement timed out. so walk on, the move is remembered and goes next tick
                        OptionalInt into = baritone.getInventoryBehavior().fetchToHotbar(i, hotbarHold.tiers(usefulSlots::contains, this::clutchSlot, builderTicks), "builder");
                        if (into.isPresent()) {
                            hotbarHold.fetched(into.getAsInt(), builderTicks);
                        }
                        break outer;
                    }
                }
            }
        }

        goalPlaceable = approxPlaceable.subList(0, 9);
        Goal goal = assemble(bcc, goalPlaceable);
        if (goal == null) {
            goalPlaceable = approxPlaceable;
            goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
            if (goal == null) {
                if (!skips.anySkipped(builderTicks) && skips.anySoftSkipped(builderTicks)) {
                    // the only ones left got let go of for where we stood, not for being impossible. that's not worth a
                    // retry off the count or wiping the real skips' bookkeeping, just look again (the history stays, see
                    // BuildSkips.softSkip, so a cell that strands us twice still gets the real skip)
                    logDebug("only blocks we couldn't do from where we stood are left, trying them again");
                    skips.clearSoftSkips();
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
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
                    // not a pause, so nothing is missing as far as missingAtPause is concerned
                    missingAtPause = Map.of();
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
        // both click branches returned already, so getting here means nothing got clicked this tick
        goal = walkOutOfFallback(goal);
        idleCheck(bcc, goal, interactionPaused);
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }

    // the pather can't leave a cell its goal already covers. standing in the break half of the janky goal with no break
    // going is just that: the place half is somewhere else and the walk there never starts. not while a path is going,
    // a revalidate against a goal its dest isn't in would throw the path away
    private Goal walkOutOfFallback(Goal goal) {
        if (!(goal instanceof JankyGoalComposite janky) || baritone.getPathingBehavior().getCurrent() != null) {
            return goal;
        }
        BetterBlockPos feet = ctx.playerFeet();
        if (!janky.primary().isInGoal(feet) && janky.fallback().isInGoal(feet)) {
            return janky.primary();
        }
        return goal;
    }

    // the stare: in our own goal, no path, no click. see idleInGoal
    private void idleCheck(BuilderCalculationContext bcc, Goal goal, boolean interactionPaused) {
        BetterBlockPos feet = ctx.playerFeet();
        if (!feet.equals(idleFeet)) {
            idleFeet = feet;
            idleInGoal = 0;
        }
        if (interactionPaused || !ctx.player().onGround() || baritone.getPathingBehavior().getCurrent() != null || !goal.isInGoal(feet)) {
            idleInGoal = 0; // walking, falling, or told not to click. none of that is a stare, and it has to be in a row
            return;
        }
        if (++idleInGoal < IDLE_LIMIT) {
            return;
        }
        idleInGoal = 0;
        letGoFromHere(bcc, feet);
    }

    // soft skips every wrong cell whose own goal has us standing in it, so the next goal is somewhere we can work from.
    // the cell's primary goal first: a break cell's from-above spot covers ground it doesn't need us on
    private void letGoFromHere(BuilderCalculationContext bcc, BetterBlockPos feet) {
        List<BetterBlockPos> owners = new ArrayList<>();
        List<BetterBlockPos> loose = new ArrayList<>();
        // every goal a cell gets is within a block or two of it, three is the from-above spot plus change
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BetterBlockPos pos = new BetterBlockPos(feet.x + dx, feet.y + dy, feet.z + dz);
                    if (!incorrectPositions.contains(pos) || skipped(pos.x, pos.y, pos.z)) {
                        continue;
                    }
                    Goal own = ownGoal(bcc, pos);
                    if (own == null) {
                        continue;
                    }
                    Goal primary = own instanceof JankyGoalComposite janky ? janky.primary() : own;
                    if (primary.isInGoal(feet)) {
                        owners.add(pos);
                    } else if (own.isInGoal(feet)) {
                        loose.add(pos);
                    }
                }
            }
        }
        if (owners.isEmpty()) {
            owners = loose;
        }
        StringBuilder line = new StringBuilder("stood in the goal at " + feet.x + " " + feet.y + " " + feet.z + " for " + IDLE_LIMIT + " ticks with nothing to click");
        line.append(owners.isEmpty() ? ", and no cell puts us here" : ", letting go of:");
        for (BetterBlockPos pos : owners) {
            BlockState curr = bcc.bsi.get0(pos.x, pos.y, pos.z);
            BlockState want = bcc.getSchematic(pos.x, pos.y, pos.z, curr);
            line.append(String.format(" %s at %s %s %s, wanted %s (%s)", curr.getBlock(), pos.x, pos.y, pos.z, want, whyNotFromHere(bcc, pos, feet)));
            if (skips.softSkip(serialize(pos), builderTicks)) {
                line.append(" again, skipping it for ").append(BuildSkips.SKIP_TICKS / 20).append("s");
            }
            line.append(";");
        }
        Trace.record("builder: " + line);
        logDebug(line.toString());
        // through the monitors' cooldown, a build that strands us every few seconds doesn't get to print 300 lines each time
        Trace.dump("monitor:build-stuck");
    }

    // the same goal assemble would give it, null if it gets none. same items as the pass that made the goal
    private Goal ownGoal(BuilderCalculationContext bcc, BetterBlockPos pos) {
        BlockState state = bcc.bsi.get0(pos.x, pos.y, pos.z);
        if (state.getBlock() instanceof AirBlock) {
            if (!placeableCell(bcc, pos)) {
                return null;
            }
            // assemble leaves a cell out while the one or two under it still need a block, it can't be what stranded us
            if (placeableCell(bcc, pos.below()) || placeableCell(bcc, pos.below(2))) {
                return null;
            }
            return placementGoal(pos, bcc);
        }
        if (state.getBlock() instanceof LiquidBlock) {
            return MovementHelper.possiblyFlowing(state) ? null : new GoalBlock(pos.above());
        }
        return breakGoal(pos, bcc);
    }

    // assemble's "placeable": wrong, air, and we have something that places what it wants
    private boolean placeableCell(BuilderCalculationContext bcc, BetterBlockPos pos) {
        if (!incorrectPositions.contains(pos)) {
            return false;
        }
        BlockState state = bcc.bsi.get0(pos.x, pos.y, pos.z);
        if (!(state.getBlock() instanceof AirBlock)) {
            return false;
        }
        BlockState want = bcc.getSchematic(pos.x, pos.y, pos.z, state);
        return want != null && containsBlockState(goalPlaceable, want);
    }

    // a best guess at which click branch said no and why. cheap answers only, it's one line in the log
    private String whyNotFromHere(BuilderCalculationContext bcc, BetterBlockPos pos, BetterBlockPos feet) {
        BlockState curr = bcc.bsi.get0(pos.x, pos.y, pos.z);
        if (curr.getBlock() instanceof AirBlock) {
            BlockState want = bcc.getSchematic(pos.x, pos.y, pos.z, curr);
            if (want != null && !containsBlockState(approxPlaceable.subList(0, 9), want)) {
                return "place: it's in the backpack and never made the hotbar, no free slot?";
            }
            return "place: no face to click from here";
        }
        if (curr.getBlock() instanceof LiquidBlock) {
            return "can't do it from here";
        }
        if (!wantsBreak(bcc, pos.x, pos.y, pos.z, feet, baritone.getPathingBehavior().pathStart())) {
            return "break: the scan won't take it from here, it's under us or holding us up";
        }
        if (RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance()).isEmpty()) {
            return "break: no ray to it from here";
        }
        return "can't do it from here";
    }

    // PathingControlManager cancels the segment after the process tick, and that cancel clears every key, so the sneak
    // and the click of the first tick after a walk never went out. cancel it ourselves first and keep what we press
    private void dropPathBeforeClicking() {
        if (baritone.getPathingBehavior().getCurrent() != null) {
            baritone.getPathingBehavior().cancelSegmentIfSafe();
        }
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
                        if (bcc.fine(x, y, z, bcc.bsi.get0(x, y, z), desired)) {
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
                        // kept counts before the incorrectSize bail, a site full of pillars would hit the cap otherwise
                        if (bcc.fine(blockX, blockY, blockZ, current, schematic.desiredState(x, y, z, current, this.approxPlaceable))) {
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
        Map<BlockState, Integer> wrongWayRound = new HashMap<>();
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
                    // holding the item but not in the state the schematic wants isn't "missing materials"
                    (containsItem(approxPlaceable, desired) ? wrongWayRound : missing).merge(desired, 1, Integer::sum);
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
            if (logMissing) {
                // the same "at least" the chat line below prints, a copy so the next tick's pass can't change it
                missingAtPause = Map.copyOf(missing);
            }
            if (logMissing && !missing.isEmpty()) {
                logDirect("Missing materials for at least:");
                logDirect(missing.entrySet().stream()
                        .map(e -> String.format("%sx %s", e.getValue(), e.getKey()))
                        .collect(Collectors.joining("\n")));
            }
            if (logMissing && !wrongWayRound.isEmpty()) {
                logDirect("Have the items but can't place them the way the schematic wants for at least:");
                logDirect(wrongWayRound.entrySet().stream()
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
        if (!logMissing && (!area.hasWork(open) || area.pendingGiveUp())) {
            // the hotbar pass can't see what's left around the anchor, it may just need a block from the inventory.
            // the whole inventory pass decides if the area is done (or this walked off the same tick the fetch went out)
            // and if a given up area really is the last one left
            return null;
        }
        List<BetterBlockPos> giveUp = area.choose(open, skippedCells, ctx.playerFeet(), builderTicks);
        if (!giveUp.isEmpty()) {
            giveUpOn(bcc, giveUp);
        }
        noteAnchor();
        Goal goal = goalFrom(bcc, placeCells, liquidCells, breakCells, true);
        if (goal == null) {
            if (!logMissing) {
                return null; // maybe it's just in the backpack, same as the hasWork bail above
            }
            noGoalAround();
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
                Goal goal = placementGoal(pos, bcc);
                if (goal != null) {
                    toPlace.add(goal);
                }
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
            area.giveUp(builderTicks);
        }
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return; // no clicking allowed, so no fixes either. not the area's fault
        }
        // mid dig the block gets its honest break time plus the confirm on top, same numbers BuildSkips uses
        int limit = lastBreak != null && builderTicks - lastSwing <= 1 ? breakLimit(lastBreak) + BuildSkips.CONFIRM_TICKS : 0;
        area.left(wrongInArea());
        if (area.clock(ctx.playerFeet(), builderTicks, skips.fixes(), limit)) {
            areaLog("nothing got done around " + anchor.x + " " + anchor.y + " " + anchor.z + " in " + Math.max(BuildArea.STALE_TICKS, limit) / 20 + "s, trying somewhere else");
            Trace.dump("monitor:build-stuck");
        }
    }

    // off incorrectPositions, both assemble passes see different slices of it and a hotbar swap would look like progress
    private int wrongInArea() {
        if (incorrectPositions == null || area.anchor() == null) {
            return 0;
        }
        int n = 0;
        for (BetterBlockPos p : incorrectPositions) {
            if (area.inArea(p.x, p.y, p.z)) {
                n++;
            }
        }
        return n;
    }

    private void noteAnchor() {
        BetterBlockPos anchor = area.anchor();
        if (anchor == null || anchor.equals(loggedAnchor)) {
            return;
        }
        loggedAnchor = anchor;
        String line = "working around " + anchor.x + " " + anchor.y + " " + anchor.z;
        Trace.record("builder: " + line);
        // its own limit, a release and the new anchor land on the same tick and the new anchor is the useful half
        if (builderTicks - lastAnchorLog >= 20) {
            lastAnchorLog = builderTicks;
            logDebug(line);
        }
    }

    // the anchor's cells are all there but none of them gets a goal (nowhere to stand, say). keeping the anchor kept
    // whatever goal we already stood in, so let it go like a timeout would and let choose find a better one
    private void noGoalAround() {
        BetterBlockPos anchor = area.anchor();
        if (anchor == null) {
            return;
        }
        area.giveUp(builderTicks);
        // choose falls back to banned areas, so this can come round every tick
        if (builderTicks - lastNoGoalLog >= 20) {
            lastNoGoalLog = builderTicks;
            String line = "nothing around " + anchor.x + " " + anchor.y + " " + anchor.z + " has anywhere to stand, trying somewhere else";
            Trace.record("builder: " + line);
            logDebug(line);
        }
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

        public Goal primary() {
            return primary;
        }

        public Goal fallback() {
            return fallback;
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

    // null when there's nowhere to stand for it right now, see noStandingRoom
    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        BlockState current = ctx.world().getBlockState(pos);
        BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
        // on top of an empty cell is only ever reached by pillaring into it, and a pillar places the real block (the
        // cost won't let it put anything else there). so a block the pillar can't place (aimed, see costOfPlacingAt)
        // or can't fit (a fence post is half a block into us at the top of the jump) doesn't get that spot
        boolean pillarable = desired == null || (PlaceRules.fitsUnderFeet(desired) && PlaceRules.aimProof(desired));
        if (!(current.getBlock() instanceof AirBlock) && pillarable) {  // TODO can this even happen?
            return new GoalPlace(pos); // the rest go through the faces below like they would into air
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        Direction blocked = null;
        for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            if (!MovementHelper.canPlaceAgainst(ctx, pos.relative(facing))) {
                continue;
            }
            //noinspection ConstantConditions
            if (placementPlausible(pos, desired)) {
                return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel, pillarable);
            }
            if (blocked == null) {
                blocked = facing;
            }
        }
        if (pillarable) {
            return new GoalPlace(pos);
        }
        if (blocked != null) {
            // there's a face to click, something's just standing in the cell (usually us). any side but on top gets us
            // out of it, GoalPlace would have us pillar up onto it
            return new GoalAdjacent(pos, pos.relative(blocked), allowSameLevel, false);
        }
        if (MovementHelper.canPlaceAgainst(ctx, pos.above())) {
            // possibleToPlace clicks undersides too, a fence post under an overhang is fine from the side
            return new GoalAdjacent(pos, pos.above(), true, false);
        }
        return noStandingRoom(pos, desired);
    }

    // nothing to click it against and on top is out, so no goal is reachable. a goal over thin air had the planner pillar
    // up and click a fence into its own feet (or a cobble into a stair cell) until the skips gave up. no skip either:
    // the goal is asked again every tick and the cell gets one as soon as a neighbour goes in, and an instant skip
    // burned the three retries in a single tick
    private Goal noStandingRoom(BlockPos pos, BlockState desired) {
        if (noRoomLogged == null) {
            noRoomLogged = new LongOpenHashSet();
        }
        if (noRoomLogged.add(serialize(pos))) {
            String line = "nowhere to stand to place " + desired + " at " + pos.toShortString() + " but on top of it, leaving it till something's next to it";
            Trace.record("builder: " + line);
            logDebug(line);
        }
        return null;
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
        // standing right on top of the cell (or right under it), see placementGoal
        private boolean allowAbove;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            this(pos, no, allowSameLevel, true);
        }

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel, boolean allowAbove) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
            this.allowAbove = allowAbove;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowAbove && x == this.x && (y == this.y + 1 || y == this.y - 1) && z == this.z) {
                // on top is a pillar we can't do. right under it has our head in the cell, and for a fence or a stair
                // that means the click never goes and nothing counts towards a skip either
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
                    && allowAbove == goal.allowAbove
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            hash = hash * 1067354897 + (allowAbove ? 0 : 1);
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
        noRoomLogged = null;
        lastPlacement = null;
        lastBreak = null;
        area.reset();
        loggedAnchor = null;
        idleInGoal = 0;
        idleFeet = null;
        loggedBreak = null;
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
            gateLog(plan, null, "crosshair isn't on a block");
            return OptionalInt.empty();
        }
        if (!ctx.player().isShiftKeyDown()) {
            // the sneak we just asked for lands on the player tick. until then the eye is standing height (aim is off
            // by a third of a block, wrong slab half) and the click would open a chest instead of placing on it
            gateLog(plan, hit, "not sneaking yet");
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
        gateLog(plan, hit, why);
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

    private void gateLog(Placement plan, BlockHitResult hit, String why) {
        // once a second is plenty, it gets asked every tick
        if (builderTicks - lastGateLog < 20) {
            return;
        }
        lastGateLog = builderTicks;
        String aim = hit == null ? "nothing" : hit.getBlockPos().toShortString() + " " + hit.getDirection();
        logDebug("not clicking yet: " + why + " (for " + plan.target().toShortString() + " off the " + plan.side + " face of "
                + plan.placeAgainst.toShortString() + ", crosshair on " + aim + ")");
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
        // see BuildSkips.work for why the right ones get asked about before the skip and not after
        for (long p : skips.work(serialize(pos), builderTicks, limit, breaking, c -> looksRight(bcc, c))) {
            BetterBlockPos s = BetterBlockPos.deserializeFromLong(p);
            BlockState curr = bcc.bsi.get0(s.x, s.y, s.z);
            // the window can skip a few at once, so ask each one what it was instead of trusting this tick
            BlockState want = bcc.getSchematic(s.x, s.y, s.z, curr);
            String line = String.format("couldn't %s at %s %s %s (it's %s now, wanted %s), skipping it for %ss", stuckWhat(s, curr, want, bcc), s.x, s.y, s.z, curr, want, BuildSkips.SKIP_TICKS / 20);
            Trace.record("builder: " + line);
            logDebug(line);
            if (p == lastTarget) {
                lastPlacement = null;
            }
        }
    }

    private boolean looksRight(BuilderCalculationContext bcc, long p) {
        BetterBlockPos s = BetterBlockPos.deserializeFromLong(p);
        BlockState curr = bcc.bsi.get0(s.x, s.y, s.z);
        BlockState want = bcc.getSchematic(s.x, s.y, s.z, curr);
        return want == null || valid(curr, want, false) || bcc.fine(s.x, s.y, s.z, curr, want);
    }

    // a replaceable (grass, snow, a puddle) where the file wants air is a stuck break, "place air" was a lie
    private String stuckWhat(BetterBlockPos s, BlockState curr, BlockState want, BuilderCalculationContext bcc) {
        if (!MovementHelper.isReplaceable(s.x, s.y, s.z, curr, bcc.bsi)) {
            return "break " + curr.getBlock();
        }
        if (want == null || want.getBlock() instanceof AirBlock) {
            return "clear " + curr.getBlock();
        }
        return "place " + want;
    }

    // what we clicked with and what we wanted, the actual block comes from BlockPlaceHelper's own line after the confirm
    private void tracePlace(BuilderCalculationContext bcc) {
        BlockPos cell = gateCell;
        if (cell == null) {
            return;
        }
        tracedBreak = null; // a place and break loop on one cell should show every round of it
        BlockState want = bcc.getSchematic(cell.getX(), cell.getY(), cell.getZ(), bcc.bsi.get0(cell));
        Trace.record("builder: place at " + cell.toShortString() + " want " + want + " held " + ctx.player().getMainHandItem().getItem());
    }

    private void traceBreak(BuilderCalculationContext bcc, BetterBlockPos pos) {
        if (pos.equals(tracedBreak)) {
            return;
        }
        tracedBreak = pos;
        BlockState curr = bcc.bsi.get0(pos.x, pos.y, pos.z);
        Trace.record("builder: break " + curr + " at " + pos.x + " " + pos.y + " " + pos.z + " want " + bcc.getSchematic(pos.x, pos.y, pos.z, curr)
                + " held " + ctx.player().getMainHandItem().getItem() + ", " + breakEta(pos));
    }

    // a bare hand on stone is seven seconds of what looks exactly like staring, so say so once per block
    private void logBreakStart(BuilderCalculationContext bcc, BetterBlockPos pos) {
        if (pos.equals(loggedBreak)) {
            return;
        }
        loggedBreak = pos;
        logDebug("breaking " + bcc.bsi.get0(pos.x, pos.y, pos.z).getBlock() + " at " + pos.x + " " + pos.y + " " + pos.z
                + " with " + ctx.player().getMainHandItem().getItem() + ", " + breakEta(pos));
    }

    // same perTick breakLimit goes by, with whatever switchToBestToolFor just put in hand
    private String breakEta(BlockPos pos) {
        float perTick = ctx.world().getBlockState(pos).getDestroyProgress(ctx.player(), ctx.world(), pos);
        return perTick <= 0 ? "never breaks" : "about " + (int) Math.ceil(1 / perTick) + " ticks";
    }

    // whether the builder pressed the click that's down right now. BlockPlaceHelper and BlockBreakHelper ask it for the
    // trace, so a click that outlived the builder's control still shows up as the builder's
    public boolean clickedByBuilder() {
        return forcedClicks;
    }

    // the ladder keepClutchItemOnHotbar keeps there, only while the clutch is on
    private boolean clutchSlot(int slot) {
        if (!ExperimentalMovement.allowLadderClutch()) {
            return false;
        }
        ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(slot);
        return stack.is(Items.LADDER) || stack.is(Items.VINE);
    }

    // a slot the builder is using or just filled, for anyone else about to swap something into the hotbar
    public boolean hotbarSlotBusy(int slot) {
        return HotbarHold.busy(slot, usefulSlotMask, hotbarHold, builderTicks);
    }

    private static long serialize(BlockPos pos) {
        return BetterBlockPos.serializeToLong(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public void onLostControl() {
        if (forcedClicks) {
            // whoever has the wheel now may never touch the clicks, and a left click left down mines whatever's under
            // the crosshair until somebody does
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            forcedClicks = false;
        }
        usefulSlotMask = 0;
        tracedBreak = null;
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        missingAtPause = Map.of();
        observedCompleted = null;
        keep = null;
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
        return sameBlockstate(first, second, Baritone.settings().buildIgnoreDirection.value, Baritone.settings().buildIgnoreProperties.value);
    }

    // the neighbour derived ones (PlaceRules.derived) never count: a straight stair the file wants next to a sideways
    // one comes out a corner, the click gate refused that forever and every refusal dropped the path
    static boolean sameBlockstate(BlockState first, BlockState second, boolean ignoreDirection, List<String> ignoredProps) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        if (first.equals(second)) {
            return true;
        }
        Block block = first.getBlock();
        for (Property<?> prop : first.getProperties()) {
            if (!Objects.equals(first.getValue(prop), second.getValue(prop))
                    && !PlaceRules.derived(block, prop)
                    && !(ignoreDirection && ORIENTATION_PROPS.contains(prop))
                    && !ignoredProps.contains(prop.getName())) {
                return false;
            }
        }
        return true;
    }

    // the states in approxPlaceable are what each stack would place *right now*, with the player facing wherever they
    // face. stairs, doors, logs and friends come out rotated, so an exact state match called a stack of stairs missing
    // whenever the schematic wanted them the other way round. the real placement (hasAnyItemThatWouldPlace) turns to
    // face the block it places against and still checks the exact state, so for "do i own this" the item is what counts.
    // this only feeds the hotbar swap and the log, NOT the placeable check: a placement goal for a state we can't
    // actually aim into is satisfied from any neighbouring tile and the builder would stand there forever, silently
    static boolean sameItem(BlockState have, BlockState desired) {
        Item item = have.getBlock().asItem();
        return item != Items.AIR && item == desired.getBlock().asItem();
    }

    // valid() was the old answer for the slot picking
    static boolean canSupply(BlockState have, BlockState desired) {
        return valid(have, desired, true) || sameItem(have, desired);
    }

    static boolean containsItem(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameItem(testee, state)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameBlockstate(testee, state)) {
                return true;
            }
        }
        return false;
    }

    // public so the schematic ghost asks the exact same question, a ghost over a block #build calls done is a liar
    public static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
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
        // one read per context, so a keep swapped mid search can't give half a path each answer
        private final Keep keep;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();
            this.keep = BuilderProcess.this.keep;

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

        // valid() plus whatever the starter said to keep. every per cell "is this done" asks this one
        private boolean fine(int x, int y, int z, BlockState current, BlockState desired) {
            return valid(current, desired, false) || kept(x, y, z, current);
        }

        private boolean kept(int x, int y, int z, BlockState current) {
            return keep != null && !(current.getBlock() instanceof AirBlock) && keep.keep(x, y, z, current);
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
                if (!PlaceRules.aimProof(sch)) {
                    // a movement clicks whatever face is handy, so a stair or a log from it is a coin flip and a fence
                    // under our own feet is no flip at all. the builder places those itself
                    return COST_INF;
                }
                // an aim-proof block has nothing a click could get wrong, so the same match assemble uses: snowy
                // grass and buildIgnoreProperties leaves were placeable to it and unreachable here
                if (containsBlockState(placeable, sch)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                // the old answer was a throwaway here at 1.5x. it went into a cell the build wants, got kept as
                // scaffolding, and came back out at cleanup, so selectThrowawayForLocation won't do it anymore either
                return COST_INF;
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
            if (kept(x, y, z, current)) {
                // our own pillar, inside the box or out. x10 was still cheaper than a detour now and then, and a path
                // that mines the scaffold it just put up is a loop that places it again. not INF though, standing on
                // top of a tall pillar with no bucket the only way down is through it
                return 1000;
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
