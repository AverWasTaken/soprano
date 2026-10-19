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

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.MaskSchematic;
import baritone.api.schematic.SubstituteSchematic;
import baritone.api.schematic.RotatedSchematic;
import baritone.api.schematic.MirroredSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.PathingCommandContext;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.function.UnaryOperator;
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
    // bumped by every start, so whoever kicked off a build can tell later if it's still theirs or a #build took over
    private long buildSerial;
    // what the logMissing pass found with no item for it, kept for whoever wants to go and get it (SchematicBuildTask).
    // no lock: the builder, the gui and altoclef all run on the game thread
    private Map<BlockState, Integer> missingAtPause = Map.of();
    // whoever started the build gets to say which wrong blocks are fine anyway (SchematicBuildTask's scaffolding, so the
    // builder stops mining the pillar it is standing on). volatile because the path thread reads it through the bcc
    private volatile Keep keep;
    // the clicks still down are ours. onLostControl lets go of exactly those, once: it runs for every loser every tick
    // and clearAllKeys there would wipe whatever the winner just pressed
    private boolean forcedClicks;
    // the builder's own clock, recursions don't count
    private long builderTicks;
    // ticks in a row at the same feet with nothing clicked, see stallCheck
    private int stallTicks;
    private BetterBlockPos stallFeet;
    private static final int STALL_TICKS = 40;
    private BetterBlockPos lastBreak;
    private boolean digging;
    private GivenUp givenUp;
    // where to stand for the cells a movement can't place right (stairs, logs, anything aimed), see PlacementPlanner
    private PlacementPlanner planner;
    private Long2ObjectOpenHashMap<CellPlan> plans;
    // cells planned per tick, nearest first. the rest use upstream's goal till their turn
    private static final int PLANS_PER_TICK = 8;
    private int planBudget;
    // cells left out of the goal because the block can't stay there yet (a wall torch with no wall), logged once each
    private LongOpenHashSet droppedLogged;
    private List<BetterBlockPos> dropped = List.of();
    // which cell asked for which part of the last goal, so the stall check knows who parked us
    private List<Pair<BetterBlockPos, Goal>> goalOwners = List.of();
    // what recalcNearby saw last time per cell, so it can tell a change from a cell that just came into range
    private Long2ObjectOpenHashMap<BlockState> seen;
    private Object seenWorld;
    // wrong blocks nothing short of creative breaks (bedrock where the file wants air), out of the goal and the scan
    private List<BetterBlockPos> unbreakableCells = List.of();

    public BuilderProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onBlockChange(BlockChangeEvent event) {
                if (schematic == null || ctx.player() == null) {
                    return;
                }
                // a farm or a waterfall across the map sends these all day and each one walks every cell we care about,
                // so only the ones near us. the nearby scan in recalcNearby has the close ones covered anyway
                BetterBlockPos feet = ctx.playerFeet();
                for (Pair<BlockPos, BlockState> change : event.getBlocks()) {
                    BlockPos p = change.first();
                    if (Math.abs(p.getX() - feet.x) <= 32 && Math.abs(p.getY() - feet.y) <= 32 && Math.abs(p.getZ() - feet.z) <= 32) {
                        blockChanged(p.getX(), p.getY(), p.getZ());
                    }
                }
            }
        });
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
    // only the global setting path wants: they use the unturned size and would push a placed origin off its corner.
    // no ctx in here, BuilderPlacedBuildTest makes the builder without a baritone
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
        forgetStalls();
    }

    // a fresh build, a resume or a repeat: whatever we gave up on before was about a different situation
    private void forgetStalls() {
        givenUp = null;
        stallTicks = 0;
        stallFeet = null;
        lastBreak = null;
        goalOwners = List.of();
        seen = null;
        // and the plans with them, they're cheap to make again and a stale one costs a walk
        plans = null;
        droppedLogged = null;
        dropped = List.of();
    }

    public void resume() {
        paused = false;
        missingAtPause = Map.of();
        // everything left got given up on for good if we paused over it, so the resume is the next go
        forgetStalls();
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
            Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().getA();
                BlockPos origin = schematic.get().getB();
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
                Tuple<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.getB();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.getA());
                build(schematic.getA().toString(), schematic2, correctedOrigin);
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

    private Optional<Tuple<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = Baritone.settings().breakFromAbove.value ? -1 : 0; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && x == pathStart.x && z == pathStart.z) {
                        continue; // dont mine what we're supported by, but not directly standing on
                    }
                    if (givenUp(x, y, z)) {
                        continue;
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    // fine, not valid: a kept block is the caller's scaffolding and the builder never gets to mine it
                    if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !bcc.fine(x, y, z, curr, desired) && !unbreakable(x, y, z, curr)) {
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
                        if (rot.isPresent()) {
                            return Optional.of(new Pair<>(pos, rot.get()));
                        }
                    }
                }
            }
        }
        return Optional.empty();
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

    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        BetterBlockPos center = ctx.playerFeet();
        // standing where the planner said to stand for some cell: that one first, off the face it planned
        Optional<Placement> planned = plannedFromHere(bcc, center, desirableOnHotbar);
        if (planned.isPresent()) {
            return planned;
        }
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = wantsPlace(bcc, center, x, y, z);
                    if (desired == null) {
                        continue;
                    }
                    desirableOnHotbar.add(desired);
                    Optional<Placement> opt = possibleToPlace(bcc, desired, x, y, z, null);
                    if (opt.isPresent()) {
                        return opt;
                    }
                }
            }
        }
        return Optional.empty();
    }

    // what the scan would want placed at this cell from center, null if it wouldn't place anything there
    private BlockState wantsPlace(BuilderCalculationContext bcc, BetterBlockPos center, int x, int y, int z) {
        int dy = y - center.y;
        if (Math.abs(x - center.x) > 5 || Math.abs(z - center.z) > 5 || dy < -5 || dy > 1) {
            return null;
        }
        BlockState curr = bcc.bsi.get0(x, y, z);
        BlockState desired = bcc.getSchematic(x, y, z, curr);
        if (desired == null || givenUp(x, y, z)) {
            return null; // irrelevant
        }
        if (!MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) || bcc.fine(x, y, z, curr, desired)) {
            return null;
        }
        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock) {
            return null;
        }
        if (desired.getBlock() instanceof AirBlock) {
            // grass where the file wants air. no item places air, and an empty slot "supplies" it, so this kept every
            // empty slot off limits for the hotbar swap below
            return null;
        }
        return desired;
    }

    private Optional<Placement> plannedFromHere(BuilderCalculationContext bcc, BetterBlockPos feet, List<BlockState> desirableOnHotbar) {
        if (plans == null || plans.isEmpty()) {
            return Optional.empty();
        }
        for (Long2ObjectMap.Entry<CellPlan> e : plans.long2ObjectEntrySet()) {
            for (PlacementPlanner.Feet f : e.getValue().feet) {
                if (!f.feet().equals(feet)) {
                    continue;
                }
                BetterBlockPos cell = BetterBlockPos.deserializeFromLong(e.getLongKey());
                BlockState desired = wantsPlace(bcc, feet, cell.x, cell.y, cell.z);
                if (desired == null) {
                    break;
                }
                desirableOnHotbar.add(desired);
                Optional<Placement> opt = possibleToPlace(bcc, desired, cell.x, cell.y, cell.z, f.recipe().side().getOpposite());
                if (opt.isPresent()) {
                    return opt;
                }
                break;
            }
        }
        return Optional.empty();
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    // upstream's possibleToPlace, with the per face work moved into PlacementPlanner.plan so the planner's guesses and
    // this real click ask the exact same question. the real sneaking eye and the aim processor's noise, like upstream
    private Optional<Placement> possibleToPlace(BuilderCalculationContext bcc, BlockState toPlace, int x, int y, int z, Direction preferAgainst) {
        BetterBlockPos pos = new BetterBlockPos(x, y, z);
        if (!placementPlausible(pos, toPlace)) {
            return Optional.empty();
        }
        List<ItemStack> hotbar = ctx.player().getInventory().getNonEquipmentItems().subList(0, 9);
        Vec3 eye = RayTraceUtils.inferSneakingEyePosition(ctx.player());
        double reach = ctx.playerController().getBlockReachDistance();
        PlacementPlanner planner = planner();
        UnaryOperator<Rotation> noise = PlacementPlanner.noise(baritone.getLookBehavior().getAimProcessor());
        // room to the next quadrant if there's a way to get it, a stair aimed right at the boundary only works on the
        // ticks the noise feels like it. the click gate catches the rest either way
        Optional<PlacementPlanner.Recipe> recipe = PlaceRules.aimProof(toPlace)
                ? Optional.empty()
                : planner.plan(bcc.bsi, eye, pos, toPlace, hotbar, noise, true, reach, preferAgainst);
        if (recipe.isEmpty()) {
            recipe = planner.plan(bcc.bsi, eye, pos, toPlace, hotbar, noise, false, reach, preferAgainst);
        }
        return recipe.map(r -> new Placement(r.slot(), r.placeAgainst(), r.side(), r.rot()));
    }

    private PlacementPlanner planner() {
        if (planner == null) {
            planner = new PlacementPlanner(ctx);
        }
        return planner;
    }

    // the click goes out later this same tick (InputOverrideHandler ticks after the processes) off the rotation the
    // server already has, which is what ctx.playerRotations and objectMouseOver are with freeLook. so run the placement
    // again on exactly that ray and rotation and only click if the right state comes out. upstream also clicked when
    // the rotation was merely close, which is how stairs went in backwards
    private boolean placeGateOpen(BuilderCalculationContext bcc, Placement p) {
        if (!ctx.player().isCrouching()) {
            // the plan used the sneaking eye, and a standing click on a chest opens it
            return false;
        }
        HitResult over = ctx.objectMouseOver();
        if (!(over instanceof BlockHitResult hit) || over.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(p.placeAgainst) || hit.getDirection() != p.side) {
            return false;
        }
        BlockPos cell = p.target();
        BlockState desired = bcc.getSchematic(cell.getX(), cell.getY(), cell.getZ(), bcc.bsi.get0(cell));
        if (desired == null) {
            return false;
        }
        ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(p.hotbarSelection);
        return planner().placesRight(desired, hit, ctx.playerRotations(), stack);
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        builderTicks++;
        planBudget = PLANS_PER_TICK;
        // a dig the break branch started last tick and the block is still there: obsidian by hand is ~250 ticks of
        // this and none of them are a stare
        digging = baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT) && lastBreak != null
                && !ctx.world().getBlockState(lastBreak).isAir();
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
            stallTicks = 0;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
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
            forgetStalls(); // keyed by world position, and the origin is about to move
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
        Optional<Tuple<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
        if (!interactionPaused && toBreak.isPresent() && isSafeToCancel && ctx.player().onGround()) {
            // we'd like to pause to break this block
            // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
            dropPathBeforeClicking();
            Rotation rot = toBreak.get().second();
            BetterBlockPos pos = toBreak.get().first();
            lastBreak = pos;
            baritone.getLookBehavior().updateTarget(rot, true);
            MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
            if (ctx.player().isCrouching()) {
                // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                // and is unable since it's unsneaked in the intermediary tick
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                forcedClicks = true;
                stallTicks = 0;
            } else if (stalled()) {
                // turned towards it for two seconds and never got to look at it
                giveUpOn(bcc, List.of(pos), "Couldn't aim at a block to break");
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        if (!interactionPaused && toPlace.isPresent() && isSafeToCancel && ctx.player().onGround() && ticks <= 0) {
            dropPathBeforeClicking();
            Rotation rot = toPlace.get().rot;
            baritone.getLookBehavior().updateTarget(rot, true);
            ctx.player().getInventory().setSelectedSlot(toPlace.get().hotbarSelection);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            if (placeGateOpen(bcc, toPlace.get())) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                forcedClicks = true;
                stallTicks = 0;
            } else if (stalled()) {
                giveUpOn(bcc, List.of(BetterBlockPos.from(toPlace.get().target())), "Couldn't get a place click in");
            }
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

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (canSupply(approxPlaceable.get(i), desired)) {
                        if (!baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains)) {
                            // awaiting inventory move, so pause
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
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
                if (retryGivenUp()) {
                    // all that's left is stuff we let go of for a bit. that's not "unable to do it" and not a reason to
                    // skip a layer, so give it another go now. each go is an attempt, so this runs out
                    logDirect("Only blocks I gave up on for a bit are left, trying them again");
                    // the logMissing pass just wrote it, but this isn't a pause
                    missingAtPause = Map.of();
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
                    logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                    // not a pause, so nothing is missing as far as missingAtPause is concerned
                    missingAtPause = Map.of();
                    forgetStalls();
                    layer++;
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
                logWhyUnable(bcc);
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        if (stallCheck(bcc, goal, interactionPaused, isSafeToCancel)) {
            // the cells that parked us here are out of the goal now, so build it again without them
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }

    // upstream's goal composites are happy if any member is. a member that's happy with where we stand while nothing
    // around here is clickable means the pather never moves us and nothing else does either: the stare. true when this
    // tick gave some cells up and the goal needs building again
    private boolean stallCheck(BuilderCalculationContext bcc, Goal goal, boolean interactionPaused, boolean isSafeToCancel) {
        BetterBlockPos feet = ctx.playerFeet();
        if (interactionPaused || !isSafeToCancel || !ctx.player().onGround() || busyElsewhere() || !goal.isInGoal(feet)) {
            // walking, swimming, mid movement, told not to click, or something is happening that just isn't a click.
            // the click branches need safe to cancel and on the ground too, so none of that is a stare
            stallTicks = 0;
            return false;
        }
        if (!stalled()) {
            return false;
        }
        List<BetterBlockPos> owners = new ArrayList<>();
        for (Pair<BetterBlockPos, Goal> owner : goalOwners) {
            if (owner.second().isInGoal(feet)) {
                owners.add(owner.first());
            }
        }
        if (owners.isEmpty()) {
            return false;
        }
        giveUpOn(bcc, owners, "Stood in the goal with nothing to click");
        return true;
    }

    // one more tick at the same feet with nothing to show for it. true on the tick that makes it STALL_TICKS
    private boolean stalled() {
        BetterBlockPos feet = ctx.playerFeet();
        if (!feet.equals(stallFeet)) {
            stallFeet = feet;
            stallTicks = 0;
        }
        if (busyElsewhere() || ++stallTicks < STALL_TICKS) {
            return false;
        }
        stallTicks = 0;
        return true;
    }

    // not a click, but not nothing either
    private boolean busyElsewhere() {
        return digging || baritone.getInventoryBehavior().movePending();
    }

    private void giveUpOn(BuilderCalculationContext bcc, List<BetterBlockPos> cells, String why) {
        StringBuilder line = new StringBuilder();
        int named = 0;
        for (BetterBlockPos pos : cells) {
            int attempts = givenUp().giveUp(pos, builderTicks);
            if (named++ >= 5) {
                continue; // still given up, just not all in one chat line
            }
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(describe(bcc, pos));
            if (attempts >= GivenUp.MAX_ATTEMPTS) {
                line.append(" (for good, till something next to it changes)");
            }
        }
        if (cells.size() > 5) {
            line.append(" and ").append(cells.size() - 5).append(" more");
        }
        logDirect(String.format("%s for %ss, leaving it alone for %ss: %s", why, STALL_TICKS / 20, GivenUp.EXPIRY_TICKS / 20, line));
    }

    // "stone" for a place, "dirt -> air" for a break, "dirt -> stone" for a swap
    private String describe(BuilderCalculationContext bcc, BetterBlockPos pos) {
        BlockState curr = bcc.bsi.get0(pos.x, pos.y, pos.z);
        BlockState want = bcc.getSchematic(pos.x, pos.y, pos.z, curr);
        String what;
        if (want == null) {
            what = name(curr);
        } else if (curr.getBlock() instanceof AirBlock) {
            what = name(want);
        } else {
            what = name(curr) + " -> " + name(want);
        }
        return what + " at " + pos.x + " " + pos.y + " " + pos.z;
    }

    private static String name(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private boolean givenUp(int x, int y, int z) {
        return givenUp != null && givenUp.isGivenUp(x, y, z, builderTicks);
    }

    private GivenUp givenUp() {
        if (givenUp == null) {
            givenUp = new GivenUp();
        }
        return givenUp;
    }

    private boolean retryGivenUp() {
        return givenUp != null && givenUp.retryNow(builderTicks);
    }

    // upstream held left click on bedrock until somebody cancelled
    private boolean unbreakable(int x, int y, int z, BlockState state) {
        return !ctx.player().hasInfiniteMaterials() && state.getDestroySpeed(ctx.world(), new BlockPos(x, y, z)) < 0;
    }

    // the pause line used to be just "unable to do it". what's left and why, the missing materials already got theirs
    private void logWhyUnable(BuilderCalculationContext bcc) {
        if (!unbreakableCells.isEmpty()) {
            logDirect(String.format("Can't break %s blocks, they don't break:", unbreakableCells.size()));
            logDirect(unbreakableCells.stream().limit(10).map(p -> describe(bcc, p)).collect(Collectors.joining("\n")) + (unbreakableCells.size() > 10 ? "\n..." : ""));
        }
        if (!dropped.isEmpty()) {
            logDirect(String.format("Can't place %s blocks there yet, nothing to hold them up:", dropped.size()));
            logDirect(dropped.stream().limit(10).map(p -> describe(bcc, p)).collect(Collectors.joining("\n")) + (dropped.size() > 10 ? "\n..." : ""));
        }
        if (givenUp == null || incorrectPositions == null) {
            return;
        }
        List<String> stuck = new ArrayList<>();
        for (BetterBlockPos pos : incorrectPositions) {
            if (givenUp(pos.x, pos.y, pos.z)) {
                stuck.add(describe(bcc, pos));
            }
        }
        if (!stuck.isEmpty()) {
            logDirect(String.format("Gave up on %s blocks after %s tries each, nothing next to them changed since:", stuck.size(), GivenUp.MAX_ATTEMPTS));
            logDirect(String.join("\n", stuck.subList(0, Math.min(stuck.size(), 10))) + (stuck.size() > 10 ? "\n..." : ""));
        }
    }

    // a block near one we gave up on for good changed, so it gets another chance. both the multi block packet event and
    // the nearby scan in recalcNearby land here, the event alone misses single block updates
    private void blockChanged(int x, int y, int z) {
        if (givenUp != null) {
            givenUp.nearbyChanged(x, y, z);
        }
        if (plans != null && !plans.isEmpty()) {
            // stale, not gone: the old feet stay the goal till the new plan is in, or the goal would flip to upstream's
            // and back and drag the path with it
            for (Long2ObjectMap.Entry<CellPlan> e : plans.long2ObjectEntrySet()) {
                BetterBlockPos cell = BetterBlockPos.deserializeFromLong(e.getLongKey());
                if (Math.abs(cell.x - x) <= GivenUp.NEARBY && Math.abs(cell.y - y) <= GivenUp.NEARBY && Math.abs(cell.z - z) <= GivenUp.NEARBY) {
                    e.getValue().stale = true;
                }
            }
        }
        if (droppedLogged != null && !droppedLogged.isEmpty()) {
            // a neighbour going in is usually exactly what a dropped one was waiting for, it can say so again next time
            droppedLogged.removeIf((long p) -> {
                BetterBlockPos cell = BetterBlockPos.deserializeFromLong(p);
                return Math.abs(cell.x - x) <= 1 && Math.abs(cell.y - y) <= 1 && Math.abs(cell.z - z) <= 1;
            });
        }
    }

    // one line for the cells that just got left out of the goal, not one per cell per tick
    private void logDropped(BuilderCalculationContext bcc, List<BetterBlockPos> cells) {
        if (cells.isEmpty()) {
            return;
        }
        if (droppedLogged == null) {
            droppedLogged = new LongOpenHashSet();
        }
        List<String> fresh = new ArrayList<>();
        for (BetterBlockPos pos : cells) {
            if (droppedLogged.add(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z))) {
                fresh.add(describe(bcc, pos));
            }
        }
        if (!fresh.isEmpty()) {
            logDirect("Can't place these yet, nothing to hold them up, leaving them out till they would stay: "
                    + String.join(", ", fresh.subList(0, Math.min(fresh.size(), 5))) + (fresh.size() > 5 ? " and " + (fresh.size() - 5) + " more" : ""));
        }
    }

    // what the planner said about a cell. stale = something near it changed and it's due a new look
    private static final class CellPlan {
        final BlockState desired;
        final List<PlacementPlanner.Feet> feet;
        boolean stale;

        CellPlan(BlockState desired, List<PlacementPlanner.Feet> feet) {
            this.desired = desired;
            this.feet = feet;
        }
    }

    // plans the nearest cells that don't have one (or have a stale one) yet, up to this tick's budget
    private void planCells(BuilderCalculationContext bcc, List<BetterBlockPos> cells) {
        if (planBudget <= 0 || cells.isEmpty()) {
            return;
        }
        if (plans == null) {
            plans = new Long2ObjectOpenHashMap<>();
        }
        List<BetterBlockPos> todo = new ArrayList<>();
        for (BetterBlockPos pos : cells) {
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z));
            CellPlan plan = plans.get(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z));
            if (plan == null || plan.stale || plan.desired != desired) {
                todo.add(pos);
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        BetterBlockPos feet = ctx.playerFeet();
        todo.sort(Comparator.comparingDouble(p -> p.distSqr(feet)));
        for (BetterBlockPos pos : todo) {
            if (planBudget-- <= 0) {
                return;
            }
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z));
            List<PlacementPlanner.Feet> found = planner().planFeet(pos, desired, bcc.bsi, bcc::getSchematic);
            plans.put(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z), new CellPlan(desired, found));
        }
    }

    // the old plans of cells that got placed (or stopped being wanted) only cost memory, so clean up now and then
    private void prunePlans() {
        if (plans != null && plans.size() > 2048 && incorrectPositions != null) {
            plans.long2ObjectEntrySet().removeIf(e -> !incorrectPositions.contains(BetterBlockPos.deserializeFromLong(e.getLongKey())));
        }
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
        if (ctx.world() != seenWorld) {
            // another dimension or a reconnect. the same coordinates are somewhere else now, so none of it means anything
            forgetStalls();
            seenWorld = ctx.world();
        }
        if (seen == null || seen.size() > 100_000) {
            seen = new Long2ObjectOpenHashMap<>(); // a long walk across a big build, forgetting is cheaper than trimming
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    // states are interned, so != is a change. a cell we never saw before isn't one, and an unloaded
                    // chunk reads as air, which isn't one either
                    if (bcc.bsi.worldContainsLoadedChunk(x, z)) {
                        BlockState now = bcc.bsi.get0(x, y, z);
                        BlockState before = seen.put(BetterBlockPos.serializeToLong(x, y, z), now);
                        if (before != null && before != now) {
                            blockChanged(x, y, z);
                        }
                    }
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
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        incorrectPositions.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (state.getBlock() instanceof AirBlock) {
                BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
                if (desired == null) {
                    outOfBounds.add(pos);
                } else if (containsBlockState(approxPlaceable, desired) || (!PlaceRules.aimProof(desired) && containsItem(approxPlaceable, desired))) {
                    // the states in approxPlaceable are what each stack places with us facing wherever we face right
                    // now, so a stack of stairs only counted for the one facing. an aimed block gets planned (see
                    // planCells) and the click gate checks the real state, so for those owning the item is enough
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
        List<Pair<BetterBlockPos, Goal>> owners = new ArrayList<>();
        List<Goal> toBreak = new ArrayList<>();
        List<BetterBlockPos> cantBreak = new ArrayList<>();
        breakable.forEach(pos -> {
            if (unbreakable(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z))) {
                cantBreak.add(pos);
            } else if (!givenUp(pos.x, pos.y, pos.z)) {
                Goal goal = breakGoal(pos, bcc);
                toBreak.add(goal);
                owners.add(new Pair<>(pos, goal));
            }
        });
        unbreakableCells = cantBreak;
        List<Goal> toPlace = new ArrayList<>();
        // a set, List.contains for every position was quadratic and placeable is in the thousands on a big build
        Set<BetterBlockPos> placeableSet = new HashSet<>(placeable);
        List<BetterBlockPos> placeCells = new ArrayList<>();
        List<BetterBlockPos> droppedNow = new ArrayList<>();
        placeable.forEach(pos -> {
            // given up ones stay in placeableSet, or the block above a hole we gave up on becomes a goal over thin air
            if (!placeableSet.contains(pos.below()) && !placeableSet.contains(pos.below(2)) && !givenUp(pos.x, pos.y, pos.z)) {
                BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z));
                // loaded only, an unloaded chunk reads as air and nothing survives on air
                if (bcc.bsi.worldContainsLoadedChunk(pos.x, pos.z) && !desired.canSurvive(ctx.world(), pos)) {
                    // nothing could go in there right now from any face (a wall torch whose wall isn't up yet), so a
                    // goal for it is just somewhere to stand and stare
                    droppedNow.add(pos);
                } else {
                    placeCells.add(pos);
                }
            }
        });
        dropped = droppedNow;
        logDropped(bcc, droppedNow);
        List<BetterBlockPos> aimed = new ArrayList<>();
        for (BetterBlockPos pos : placeCells) {
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z));
            if (!PlaceRules.aimProof(desired)) {
                aimed.add(pos);
            }
        }
        planCells(bcc, aimed);
        prunePlans();
        for (BetterBlockPos pos : placeCells) {
            Goal goal = placementGoal(pos, bcc);
            toPlace.add(goal);
            owners.add(new Pair<>(pos, goal));
        }
        sourceLiquids.forEach(pos -> {
            if (!givenUp(pos.x, pos.y, pos.z)) {
                Goal goal = new GoalBlock(pos.above());
                toPlace.add(goal);
                owners.add(new Pair<>(pos, goal));
            }
        });
        goalOwners = owners;

        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (toBreak.isEmpty()) {
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
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return null;
        }
        return new GoalComposite(toBreak.toArray(new Goal[0]));
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
        CellPlan plan = plans == null ? null : plans.get(BetterBlockPos.serializeToLong(pos.getX(), pos.getY(), pos.getZ()));
        if (plan != null && !plan.feet.isEmpty() && plan.desired == bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), bcc.bsi.get0(pos))) {
            // somewhere a click comes out right from, not just anywhere next to it. GoalAdjacent was happy one block
            // over with the stair facing the wrong way and we stood there till the stall check let it go
            Goal[] feet = new Goal[plan.feet.size()];
            for (int i = 0; i < feet.length; i++) {
                feet[i] = new GoalPlanned(plan.feet.get(i).feet(), pos.getY());
            }
            return feet.length == 1 ? feet[0] : new GoalComposite(feet);
        }
        // not planned yet, not an aimed block, or the planner found nowhere: upstream's, and the stall check covers a bad
        // arrival
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

    // a feet cell the planner picked. lower cells first, same as GoalAdjacent and GoalPlace, or a composite of these
    // next to theirs would always look closer and the build would go up in whatever order the stairs are
    public static class GoalPlanned extends GoalBlock {

        private final int cellY;

        public GoalPlanned(BlockPos feet, int cellY) {
            super(feet);
            this.cellY = cellY;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return this.cellY * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o) && ((GoalPlanned) o).cellY == cellY;
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 31 + cellY;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlanned{x=%s,y=%s,z=%s}",
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

    // whether the builder pressed the click that's down right now. InputOverrideHandler asks it for the trace, so a click
    // that outlived the builder's control still shows up as the builder's
    public boolean clickedByBuilder() {
        return forcedClicks;
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
        missingAtPause = Map.of();
        keep = null;
        forgetStalls();
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
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
            ItemStack stack = ctx.player().getInventory().items.get(i);
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
    // one comes out a corner, and asking for the straight one anyway is a place and break loop.
    // Objects.equals and not != because not every property value is an interned enum (upstream #5122)
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

    // same item, any state. for "do i own this" on aimed blocks, whose approxPlaceable state is just whichever way we
    // happened to face. AIR on both sides is no item, not a match. same block too: a torch item shares its item with
    // the wall torch, but the place branch simulates the item's own block and only ever gets a standing one out of it
    static boolean sameItem(BlockState have, BlockState desired) {
        Item item = have.getBlock().asItem();
        return item != Items.AIR && have.getBlock() == desired.getBlock();
    }

    static boolean containsItem(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameItem(testee, state)) {
                return true;
            }
        }
        return false;
    }

    // the hotbar swap's question. a stair stack facing the wrong way is still the stack the stair comes out of
    static boolean canSupply(BlockState have, BlockState desired) {
        return valid(have, desired, true) || (!PlaceRules.aimProof(desired) && sameItem(have, desired));
    }

    static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameBlockstate(testee, state)) {
                return true;
            }
        }
        return false;
    }

    // public so the schematic ghost and the diff ask the exact same question, a ghost over a block #build calls done is a liar
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

        // this has to agree with what a movement will actually put there (InventoryBehavior.selectThrowawayForLocation),
        // or A* plans through a cell the movement then refuses to fill and we recalc forever
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
                // containsBlockState and not contains: snowy grass and buildIgnoreProperties leaves were placeable to
                // assemble and unreachable here
                if (containsBlockState(placeable, sch)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                // upstream put a throwaway here at 1.5x. it goes into a cell the build wants filled, the builder breaks
                // it back out, the next path puts it back. that's the scaffold loop (upstream #2173)
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
                // the caller's pillar, inside the box or out. x10 was still cheaper than a detour now and then, and a
                // path that mines the scaffold it just put up is a loop that places it again. not INF though, standing
                // on top of a tall pillar with no bucket the only way down is through it
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
