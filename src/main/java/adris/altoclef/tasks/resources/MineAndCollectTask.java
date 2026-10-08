package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import baritone.Baritone;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.DropPatience;
import adris.altoclef.util.helpers.DropWatch;
import adris.altoclef.util.helpers.MineAnchor;
import adris.altoclef.util.helpers.MineStick;
import adris.altoclef.util.helpers.PlacedByUs;
import adris.altoclef.util.helpers.StoneDigRank;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.CursorSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.ui.HudText;
import java.util.*;
import net.minecraft.client.Minecraft;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

public class MineAndCollectTask extends ResourceTask {

    private final Block[] _blocksToMine;

    private final MiningRequirement _requirement;

    private final TimerGame _cursorStackTimer = new TimerGame(3);

    private final MineOrCollectTask _subtask;

    public MineAndCollectTask(ItemTarget[] itemTargets, Block[] blocksToMine, MiningRequirement requirement) {
        super(itemTargets);
        _requirement = requirement;
        _blocksToMine = blocksToMine;
        _subtask = new MineOrCollectTask(_blocksToMine, _itemTargets);
    }

    public MineAndCollectTask(ItemTarget[] blocksToMine, MiningRequirement requirement) {
        this(blocksToMine, itemTargetToBlockList(blocksToMine), requirement);
    }

    public MineAndCollectTask(ItemTarget target, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget[]{target}, blocksToMine, requirement);
    }

    public MineAndCollectTask(Item item, int count, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget(item, count), blocksToMine, requirement);
    }

    public static Block[] itemTargetToBlockList(ItemTarget[] targets) {
        List<Block> result = new ArrayList<>(targets.length);
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                Block block = Block.byItem(item);
                if (block != null && !WorldHelper.isAir(block)) {
                    result.add(block);
                }
            }
        }
        return result.toArray(Block[]::new);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBlockTracker().trackBlock(_blocksToMine);

        // We're mining, so don't throw away pickaxes.
        mod.getBehaviour().addProtectedItems(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

        _subtask.resetSearch();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // Picking up is controlled by a separate task here.
        return true;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (!StorageHelper.miningRequirementMet(mod, _requirement)) {
            return new SatisfyMiningRequirementTask(_requirement);
        }

        if (_subtask.isMining()) {
            makeSureToolIsEquipped(mod);
        }

        // Wrong dimension check.
        if (_subtask.wasWandering() && isInWrongDimension(mod) && !mod.getBlockTracker().anyFound(_blocksToMine)) {
            return getToCorrectDimensionTask(mod);
        }

        return _subtask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_blocksToMine);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof MineAndCollectTask task) {
            return Arrays.equals(task._blocksToMine, _blocksToMine);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Mine And Collect";
    }

    @Override
    protected String toHudString() {
        return "Mining " + HudText.some(_itemTargets);
    }

    private void makeSureToolIsEquipped(AltoClef mod) {
        if (_cursorStackTimer.elapsed() && !mod.getFoodChain().needsToEat()) {
            assert Minecraft.getInstance().player != null;
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (cursorStack != null && !cursorStack.isEmpty()) {
                // We have something in our cursor stack
                if (cursorStack.isCorrectToolForDrops(mod.getWorld().getBlockState(_subtask.miningPos()))) {
                    // Our cursor stack would help us mine our current block
                    Item currentlyEquipped = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
                    if (cursorStack.getItem() instanceof DiggerItem) {
                        if (currentlyEquipped instanceof DiggerItem currentPick) {
                            DiggerItem swapPick = (DiggerItem) cursorStack.getItem();
                            if (ItemHelper.getMiningSpeed(swapPick) > ItemHelper.getMiningSpeed(currentPick)) {
                                // We can equip a better pickaxe.
                                mod.getSlotHandler().forceEquipSlot(CursorSlot.SLOT);
                            }
                        } else {
                            // We're not equipped with a pickaxe...
                            mod.getSlotHandler().forceEquipSlot(CursorSlot.SLOT);
                        }
                    }
                }
            }
            _cursorStackTimer.reset();
        }
    }

    private static class MineOrCollectTask extends AbstractDoToClosestObjectTask<Object> {

        private final Block[] _blocks;
        private final boolean _stoneOnly;
        private final ItemTarget[] _targets;
        private final Set<BlockPos> _blacklist = new HashSet<>();
        private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
        private final Task _pickupTask;
        private final MineStick _stick = new MineStick();
        private final DropPatience _patience = new DropPatience();
        private final boolean _logsOnly;
        private BlockPos _miningPos;
        // the last block we broke, while there is more around it (see MineAnchor)
        private BlockPos _anchor;
        private boolean _anchorAnnounced;
        private ItemEntity _lockedDrop;

        public MineOrCollectTask(Block[] blocks, ItemTarget[] targets) {
            _blocks = blocks;
            _stoneOnly = StoneDigRank.stoneOnly(blocks);
            _logsOnly = MineAnchor.logsOnly(blocks);
            _targets = targets;
            _pickupTask = new PickupDroppedItemTask(_targets, true);
        }

        @Override
        protected Vec3 getPos(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                return WorldHelper.toVec3d(b);
            }
            if (obj instanceof ItemEntity item) {
                return item.position();
            }
            throw new UnsupportedOperationException("Shouldn't try to get the position of object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        // exposure is six world reads, so only the stone near enough to matter pays for it. past this the walk is the
        // whole story and a buried block 40 away is as far as an exposed one
        private static final double EXPOSURE_RANGE = 24;

        private double stoneScore(AltoClef mod, double fx, double fy, double fz, double tx, double ty, double tz) {
            int bx = (int) Math.floor(tx);
            int by = (int) Math.floor(ty);
            int bz = (int) Math.floor(tz);
            boolean exposed = true;
            int falling = 0;
            if (Math.abs(tx - fx) <= EXPOSURE_RANGE && Math.abs(tz - fz) <= EXPOSURE_RANGE && Math.abs(ty - fy) <= EXPOSURE_RANGE) {
                exposed = hasAirFace(mod, bx, by, bz);
                // gravel on top of the stone doesn't show up in hasAirFace, it's a block. it comes down on whoever digs it
                falling = WorldHelper.fallingAbove(mod.getWorld(), new BlockPos(bx, by, bz));
            }
            return StoneDigRank.score(fx, fy, fz, bx, by, bz, exposed, falling);
        }

        private static boolean hasAirFace(AltoClef mod, int x, int y, int z) {
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            for (Direction d : Direction.values()) {
                at.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
                if (mod.getWorld().getBlockState(at).isAir()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected Optional<Object> getClosestTo(AltoClef mod, Vec3 pos) {
            noteBreak(mod);
            Optional<Object> pick = pick(mod, pos);
            // the drop we hand out is the one we stay on, see DropPatience
            if (pick.isPresent() && pick.get() instanceof ItemEntity drop) {
                _lockedDrop = drop;
                _patience.lock(drop.getId());
            }
            return pick;
        }

        private Optional<Object> pick(AltoClef mod, Vec3 pos) {
            int now = WorldHelper.getTicks();
            boolean paused = mod.getExtraBaritoneSettings().isInteractionPaused();
            ItemEntity locked = lockedDrop(mod);

            // We can't mine right now.
            if (paused) {
                Optional<ItemEntity> drop = locked != null ? Optional.of(locked) : nearestDrop(mod, pos, true);
                return (drop.isPresent() ? drop : nearestDrop(mod, pos, false)).map(Object.class::cast);
            }

            // halfway through a block is not the time to go and get a drop. the drop gets its turn when the block is gone
            BlockPos breaking = mod.getControllerExtras().getBreakingBlockPos();
            if (breaking != null && mod.getControllerExtras().isBreakingBlock() && stillOurs(mod, breaking)) {
                _stick.breaking(now, breaking);
            }
            BlockPos held = _stick.holdOn(now, check -> stillOurs(mod, check));
            if (held != null) {
                return Optional.of(held);
            }

            // and once we are on a drop nothing takes us off it. no maths, no second opinions, until it is in the bag
            if (locked != null && spendOn(mod, locked)) {
                return Optional.of(locked);
            }

            Optional<BlockPos> closestBlock = pickBlock(mod, pos);
            Optional<ItemEntity> closestDrop = nearestDrop(mod, pos, true);
            if (closestDrop.isEmpty() && closestBlock.isEmpty()) {
                // nothing else to do with our hands: a drop in reach is still better than wandering off from it
                closestDrop = nearestDrop(mod, pos, false);
            }

            double blockSq = closestBlock.isEmpty() ? Double.POSITIVE_INFINITY : closestBlock.get().distToCenterSqr(pos);
            double rawDropSq = closestDrop.isEmpty() ? Double.POSITIVE_INFINITY : closestDrop.get().distanceToSqr(pos);
            // the + 10 is on a SQUARED distance, so any block within ~3 blocks beat a drop sitting on our feet, and
            // in a tunnel there is always a block that close. so close drops just win, no maths
            double dropSq = rawDropSq + 10;

            if (rawDropSq <= DROP_FIRST_RANGE_SQ || dropSq <= blockSq) {
                return closestDrop.map(Object.class::cast);
            } else {
                return closestBlock.map(Object.class::cast);
            }
        }

        // the next block to break. around the one we just broke while there is anything there, otherwise the nearest
        private Optional<BlockPos> pickBlock(AltoClef mod, Vec3 pos) {
            Predicate<BlockPos> usable = check -> {
                if (_blacklist.contains(check)) return false;
                if (mod.getBlockTracker().unreachable(check)) return false;
                return WorldHelper.canBreak(mod, check);
            };
            // what we put down ourselves is scaffolding, not a resource. it is the best looking stone there is (exposed, at
            // our feet) and eating it is how the table placement looped. only when nothing else is close do we take it
            long gameTime = mod.getWorld().getGameTime();
            Predicate<BlockPos> notOurs = check -> usable.test(check) && !PlacedByUs.GLOBAL.recent(check.getX(), check.getY(), check.getZ(), gameTime);

            if (_anchor != null) {
                Optional<BlockPos> around = MineAnchor.nearest(_anchor, mod.getBlockTracker().getKnownLocations(_blocks), _logsOnly,
                        pos.x, pos.y, pos.z, check -> mod.getBlockTracker().blockIsValid(check, _blocks) && notOurs.test(check));
                if (around.isPresent()) {
                    if (!_anchorAnnounced) {
                        _anchorAnnounced = true;
                        BlockPos at = around.get();
                        Debug.logMessage("sticking with the " + (_logsOnly ? "tree" : "vein") + " at " + at.getX() + " " + at.getY() + " " + at.getZ());
                    }
                    return around;
                }
                // nothing left around it, that one is done. back to the nearest
                if (_anchorAnnounced) {
                    Debug.logMessage("done with that " + (_logsOnly ? "tree" : "vein"));
                }
                _anchor = null;
                _anchorAnnounced = false;
            }

            Optional<BlockPos> closestBlock = nearestBlock(mod, pos, notOurs);
            if (closestBlock.isEmpty() || closestBlock.get().distToCenterSqr(pos) > OWN_BLOCK_RANGE_SQ) {
                Optional<BlockPos> anything = nearestBlock(mod, pos, usable);
                if (anything.isPresent() && (closestBlock.isEmpty() || anything.get().distToCenterSqr(pos) < closestBlock.get().distToCenterSqr(pos))) {
                    closestBlock = anything;
                }
            }
            return closestBlock;
        }

        // a block of ours going away is a break, and its drop is a tick or two from existing. picking the next block
        // right now is how a log fell and the bot walked off to the next one (-119,69,71 then -119,70,71, no pickup between),
        // so we stand still until the drop shows. the pick that follows has the patience lock for the walk to it
        @Override
        protected void onPursuitGone(AltoClef mod, Object gone) {
            if (!(gone instanceof BlockPos pos) || !pos.equals(_miningPos)) {
                return;
            }
            if (_blacklist.contains(pos) || mod.getBlockTracker().unreachable(pos) || mod.getBlockTracker().blockIsValid(pos, _blocks)) {
                return;
            }
            Vec3 spot = Vec3.atCenterOf(pos);
            if (mod.getPlayer().getEyePosition().distanceToSqr(spot) > HOLD_REACH_SQ) {
                return;
            }
            // the break is booked (anchor and all) now, the wait must not look like a block we are failing to mine
            noteBreak(mod);
            _miningPos = null;
            _progressChecker.reset();
            expectDrop(false, spot);
        }

        @Override
        protected boolean dropSeen(AltoClef mod, Vec3 spot) {
            return DropWatch.seen(mod, spot, DROP_SEEN_RADIUS, _targets);
        }

        // a drop that fell further from the block than this was somebody else's
        private static final double DROP_SEEN_RADIUS = 5;

        // the block we were on is gone and we did not give up on it: that was a break, so that is where we are working.
        // stone has its own idea of where to dig next (see StoneDigRank), a neighbourhood would walk it down a shaft
        private void noteBreak(AltoClef mod) {
            BlockPos was = _miningPos;
            if (was == null || _stoneOnly || _blacklist.contains(was) || mod.getBlockTracker().unreachable(was)) {
                return;
            }
            if (!mod.getBlockTracker().blockIsValid(was, _blocks)) {
                _anchor = was;
                _miningPos = null;
            }
        }

        // the drop we are on, or null when there is none or it stopped being worth it
        private ItemEntity lockedDrop(AltoClef mod) {
            ItemEntity drop = _lockedDrop;
            if (drop == null) {
                return null;
            }
            if (!isValid(mod, drop)) {
                _lockedDrop = null;
                _patience.unlock();
                return null;
            }
            return drop;
        }

        // a tick spent walking to the locked drop (not one spent cracking a log on the way, or paused). false when the
        // patience ran out: stuck in leaves, a hole, a pathing hole. the pickup task would take it right back as nearest,
        // so it is banned there too
        private boolean spendOn(AltoClef mod, ItemEntity drop) {
            _patience.tick();
            _patience.progress(mod.getPlayer().distanceTo(drop));
            if (!_patience.expired()) {
                return true;
            }
            Debug.logInternal("Giving up on a drop that never came");
            _patience.giveUp();
            mod.getEntityTracker().banEntity(drop);
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            _lockedDrop = null;
            return false;
        }

        // the nearest drop we want. skipInReach leaves out the ones vanilla is about to put in the bag for us, as long as
        // there is room for them (a full bag is the pickup task's job, it frees a slot when it is touching the drop)
        private Optional<ItemEntity> nearestDrop(AltoClef mod, Vec3 pos, boolean skipInReach) {
            if (!mod.getEntityTracker().itemDropped(_targets)) {
                return Optional.empty();
            }
            Vec3 feet = mod.getPlayer().position();
            Predicate<ItemEntity> worthIt = drop -> {
                if (_patience.gaveUp(drop.getId())) return false;
                if (!skipInReach) return true;
                Vec3 at = drop.position();
                return !DropPatience.alreadyInReach(at.x - feet.x, at.y - feet.y, at.z - feet.z)
                        || mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(drop.getItem(), false).isEmpty();
            };
            return mod.getEntityTracker().getClosestItemDrop(pos, worthIt, _targets);
        }

        // 6 blocks, squared. anything this close gets picked up before we mine another block
        private static final double DROP_FIRST_RANGE_SQ = 36;

        // 16 blocks, squared. past this a block we placed ourselves is a fair meal
        private static final double OWN_BLOCK_RANGE_SQ = 256;

        private Optional<BlockPos> nearestBlock(AltoClef mod, Vec3 pos, Predicate<BlockPos> usable) {
            // stone gets its own idea of near: sideways and at our level, not the floor (see StoneDigRank)
            if (_stoneOnly) {
                return mod.getBlockTracker().getNearestTracking(pos, usable, (fx, fy, fz, tx, ty, tz) -> stoneScore(mod, fx, fy, fz, tx, ty, tz), _blocks);
            }
            // logs want plain distance: baritone's heuristic prices up like sideways, so the log above us tied with the
            // one next door and below us was nearly free. ores keep the old pick, mining down to them is the point
            if (_logsOnly) {
                return mod.getBlockTracker().getNearestTracking(pos, usable, MineAnchor::distSq, _blocks);
            }
            return mod.getBlockTracker().getNearestTracking(pos, usable, _blocks);
        }

        // one of the blocks we are after, still standing, not given up on, and close enough to keep swinging at
        private boolean stillOurs(AltoClef mod, BlockPos pos) {
            if (_blacklist.contains(pos) || mod.getBlockTracker().unreachable(pos)) return false;
            if (!mod.getBlockTracker().blockIsValid(pos, _blocks)) return false;
            return mod.getPlayer().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= HOLD_REACH_SQ;
        }

        // the mid-break block is only worth holding on to while we can still hit it
        private static final double HOLD_REACH_SQ = 5.5 * 5.5;

        // the block we are breaking beats a never-tried one no matter how close the new one is. a drop beats a block we
        // haven't cracked yet too: getClosestTo only hands one out when it should win, and the one the break just made
        // shows up a tick after the block is gone, by when we already picked the next log
        @Override
        protected boolean mustSwitchTo(AltoClef mod, Object current, Object candidate) {
            if (candidate instanceof ItemEntity) {
                return true;
            }
            return candidate instanceof BlockPos b && _stick.isHolding(b);
        }

        @Override
        public void resetSearch() {
            super.resetSearch();
            forgetFocus();
        }

        private void forgetFocus() {
            forgetDropExpect();
            _anchor = null;
            _anchorAnnounced = false;
            _lockedDrop = null;
            _patience.clear();
        }

        @Override
        protected Vec3 getOriginPos(AltoClef mod) {
            return mod.getPlayer().position();
        }

        @Override
        protected boolean stillLooking(AltoClef mod) {
            // a drop we can see is an answer too, but getClosestTo already counts those before we get asked
            return mod.getBlockTracker().scanPending(_blocks);
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                _progressChecker.reset();
            }
            if (_miningPos != null && !_progressChecker.check(mod)) {
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                Debug.logMessage("Failed to mine block. Suggesting it may be unreachable.");
                mod.getBlockTracker().requestBlockUnreachable(_miningPos, 2);
                _blacklist.add(_miningPos);
                _miningPos = null;
                _progressChecker.reset();
            }
            return super.onTick(mod);
        }

        @Override
        protected Task getGoalTask(Object obj) {
            if (obj instanceof BlockPos newPos) {
                if (_miningPos == null || !_miningPos.equals(newPos)) {
                    _progressChecker.reset();
                }
                _miningPos = newPos;
                return new DestroyBlockTask(_miningPos);
            }
            if (obj instanceof ItemEntity) {
                _miningPos = null;
                return _pickupTask;
            }
            throw new UnsupportedOperationException("Shouldn't try to get the goal from object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        @Override
        protected boolean isValid(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                // a block we gave up on is not valid anymore, or the "new target must be twice as close" rule would
                // keep us on it (it used to be swapped out for any other nearest block, which is how it got away with this)
                return !_blacklist.contains(b) && !mod.getBlockTracker().unreachable(b)
                        && mod.getBlockTracker().blockIsValid(b, _blocks) && WorldHelper.canBreak(mod, b);
            }
            if (obj instanceof ItemEntity drop) {
                // picked up or despawned, don't keep chasing a ghost. one we gave up on or the pickup task banned stays dead
                if (!drop.isAlive() || _patience.gaveUp(drop.getId()) || !mod.getEntityTracker().isEntityReachable(drop)) return false;
                // in the water the pickup task can't see it anymore and would wander until the patience ran out
                if (!Baritone.settings().altoPickupItemsInWater.value && !ItemPickupRules.isPickupSafe(drop)) return false;
                Item item = drop.getItem().getItem();
                if (_targets != null) {
                    for (ItemTarget target : _targets) {
                        if (target.matches(item)) return true;
                    }
                }
                return false;
            }
            return false;
        }

        @Override
        protected void onStart(AltoClef mod) {
            _progressChecker.reset();
            _miningPos = null;
            _stick.clear();
            forgetFocus();
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {

        }

        @Override
        protected boolean isEqual(Task other) {
            if (other instanceof MineOrCollectTask task) {
                return Arrays.equals(task._blocks, _blocks) && Arrays.equals(task._targets, _targets);
            }
            return false;
        }

        @Override
        protected String toDebugString() {
            return "Mining or Collecting";
        }

        @Override
        protected String toHudString() {
            return "Looking for " + HudText.some(_targets);
        }

        @Override
        protected boolean isHudPlumbing() {
            return true;
        }

        public boolean isMining() {
            return _miningPos != null;
        }

        public BlockPos miningPos() {
            return _miningPos;
        }
    }

}
