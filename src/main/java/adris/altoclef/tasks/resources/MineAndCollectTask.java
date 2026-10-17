package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.Bans;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import baritone.Baritone;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.HotbarTools;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.DropPatience;
import adris.altoclef.util.helpers.DropWatch;
import adris.altoclef.util.helpers.MineAnchor;
import adris.altoclef.util.helpers.MineStick;
import adris.altoclef.util.helpers.NoPathScope;
import adris.altoclef.util.helpers.PlacedByUs;
import adris.altoclef.util.helpers.StoneDigRank;
import adris.altoclef.util.helpers.TreeChop;
import adris.altoclef.util.helpers.TreeReach;
import adris.altoclef.util.helpers.TreeReachScan;
import adris.altoclef.util.helpers.TreeStrikes;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import net.minecraft.world.inventory.ContainerInput;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.ui.HudText;
import java.util.*;
import net.minecraft.client.Minecraft;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
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

    // blocks to build a tower with, dug by hand before the log that needs one (see dirtFirst)
    private Task _dirtTask;
    private int _dirtSince;
    private BlockPos _dirtLog;
    // how long the dirt trip gets, and how long a trip that got us nothing to place with keeps the high logs off the menu
    private static final int DIRT_PATIENCE_TICKS = 1200;
    private static final int DIRT_OFF_TICKS = 6000;
    // a tree we already dug for is not dug for again for this long
    private static final int DIRT_TREE_TICKS = 3600;

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

    // only ever pick blocks that pass this as the next one to break (the coal detour: only coal we can see). a block already
    // being broken is not asked again, the swing that is halfway through finishes
    public MineAndCollectTask onlyWhere(Predicate<BlockPos> only) {
        _subtask._only = only;
        return this;
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
        // an axe for wood, a shovel for dirt and sand, out of the bag and into the hotbar where ToolSet can see them
        HotbarTools.forBlocks(mod, _blocksToMine);
        if (!StorageHelper.miningRequirementMet(mod, _requirement)) {
            return new SatisfyMiningRequirementTask(_requirement);
        }

        Task dirt = dirtFirst(mod);
        if (dirt != null) {
            setDebugState("Digging blocks to tower up to a log");
            return dirt;
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

    // a canopy log past the shaft needs a tower, and a tower needs something to place. dirt is the one block that comes out of
    // the ground by hand (same reasoning as NetherRecoverTask), so the trip happens once, before the walk to the log
    private Task dirtFirst(AltoClef mod) {
        int now = WorldHelper.getTicks();
        if (_dirtTask != null) {
            // the task's own count, not "anything to place": one dirt in the bag is a tower one block high
            boolean done = _dirtTask.isFinished(mod);
            boolean late = now - _dirtSince > DIRT_PATIENCE_TICKS || now < _dirtSince;
            if (!done && !late) {
                return _dirtTask;
            }
            _dirtTask = null;
            // the stall clock still has its baseline from before the walk, it would call the first tick back a stall
            _subtask.backFromDirt();
            if (done) {
                // one trip per tree: the tower eats the blocks as it goes up, and a trip for "the shortfall" every time they
                // dip would drag us off a half built tower
                MineOrCollectTask.dirtTrip(_dirtLog, now);
            } else {
                // fell short (bag full, no dirt where we looked): not worth another go any time soon, for any tree
                MineOrCollectTask.dirtOff(now, DIRT_OFF_TICKS);
                Debug.logMessage("could not dig enough dirt to tower with, leaving the high logs alone for a while");
            }
            return null;
        }
        int want = _subtask.dirtWanted(mod);
        if (want <= 0) {
            return null;
        }
        Debug.logMessage("that log is too high to reach from the ground, digging " + want + " more dirt to tower up");
        _dirtSince = now;
        _dirtLog = _subtask.takeDirtLog();
        _dirtTask = TaskCatalogue.getItemTask(Items.DIRT, mod.getItemStorage().getItemCount(Items.DIRT) + want);
        return _dirtTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        _dirtTask = null;
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

    // the cursor slot is window index -1, which forceEquipSlot turned into a click outside the window: the better pick went on the
    // floor. a pickup click on the hand slot trades the cursor for what we hold, the old tool goes back to the bag from the cursor
    private static void equipFromCursor(AltoClef mod) {
        mod.getSlotHandler().clickSlot(PlayerSlot.getEquipSlot(), 0, ContainerInput.PICKUP);
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
                    if (ItemHelper.isDigger(cursorStack.getItem())) {
                        if (ItemHelper.isDigger(currentlyEquipped)) {
                            Item swapPick = cursorStack.getItem();
                            if (ItemHelper.getMiningSpeed(swapPick) > ItemHelper.getMiningSpeed(currentlyEquipped)) {
                                // We can equip a better pickaxe.
                                equipFromCursor(mod);
                            }
                        } else {
                            // We're not equipped with a pickaxe...
                            equipFromCursor(mod);
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
        private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
        // empty searches since we handed baritone this block, and the tick we were last driving (see noPathVerdict)
        private final NoPathScope.Watch _noPath = new NoPathScope.Watch();
        private int _lastTickAt = Integer.MIN_VALUE / 2;
        private final Task _pickupTask;
        private final MineStick _stick = new MineStick();
        private final DropPatience _patience = new DropPatience();
        private final boolean _logsOnly;
        private BlockPos _miningPos;
        // the last block we broke, while there is more around it (see MineAnchor)
        private BlockPos _anchor;
        private boolean _anchorAnnounced;
        private ItemEntity _lockedDrop;
        // a caller's extra say on which blocks may be picked (see onlyWhere). last in the test, it may be the costly one
        private Predicate<BlockPos> _only = check -> true;
        // failed breaks per tree. static because the parent rebuilds this task whenever it likes and the count has to outlive that
        private static final TreeStrikes STRIKES = new TreeStrikes();
        private final TreeReachScan _scan = new TreeReachScan();
        // no digging for dirt before this tick. static for the same reason as the strikes, a rebuilt task would start another trip
        private static int dirtOffUntil = Integer.MIN_VALUE;
        // the last tree we dug dirt for, and when
        private static BlockPos dirtTreeAt;
        private static int dirtTreeTick = Integer.MIN_VALUE;
        // how many trips that tree got (see TreeChop.tripsFor)
        private static int dirtTreeTrips;
        // a log baritone found no way to, waiting on the dirt trip it asked for (see askForDirt), and the tower it needs
        private BlockPos _dirtAsk;
        private int _dirtAskGap;
        // the tree whose drops we pick up once it is down, see treeSweep
        private BlockPos _sweepTree;

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
            // (a drop we are already walking to is not traded for a block that happens to be cracking behind us)
            BlockPos breaking = mod.getControllerExtras().getBreakingBlockPos();
            if (locked == null && breaking != null && mod.getControllerExtras().isBreakingBlock() && stillOurs(mod, breaking)) {
                _stick.breaking(now, breaking);
            } else if (locked == null) {
                beginHold(mod, now);
            }
            BlockPos held = _stick.holdOn(now, check -> stillOurs(mod, check));
            if (held != null && !mod.getControllerExtras().isBreakingBlock() && despawningDropNear(mod, now, pos)) {
                // the one exception: a drop a few seconds from gone is worth a block we have not cracked yet (mid crack the base task
                // refuses every switch anyway, and a lock it never acts on would just burn the patience)
                _stick.clear();
                held = null;
            }
            if (held != null) {
                return Optional.of(held);
            }

            // and once we are on a drop nothing takes us off it. no maths, no second opinions, until it is in the bag
            if (locked != null && spendOn(mod, locked)) {
                return Optional.of(locked);
            }

            Optional<BlockPos> closestBlock = pickBlock(mod, pos);
            // pickBlock lets go of the anchor when its tree is out of logs, so a block with the anchor still set is from the tree
            boolean onTree = _logsOnly && _anchor != null && closestBlock.isPresent();
            if (onTree) {
                _sweepTree = _anchor;
            }
            if (_sweepTree != null) {
                Optional<ItemEntity> sweep = treeSweep(mod, pos, onTree);
                if (sweep.isPresent()) {
                    return sweep.map(Object.class::cast);
                }
            }
            Optional<ItemEntity> closestDrop = nearestDrop(mod, pos, true);
            if (closestDrop.isEmpty() && closestBlock.isEmpty()) {
                // nothing else to do with our hands: a drop in reach is still better than wandering off from it
                closestDrop = nearestDrop(mod, pos, false);
            }

            double blockSq = closestBlock.isEmpty() ? Double.POSITIVE_INFINITY : closestBlock.get().distToCenterSqr(pos);
            double rawDropSq = closestDrop.isEmpty() ? Double.POSITIVE_INFINITY : closestDrop.get().distanceToSqr(pos);
            boolean despawning = closestDrop.isPresent() && MineStick.aboutToDespawn(closestDrop.get().getAge());
            boolean bagFull = closestDrop.isPresent() && mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(closestDrop.get().getItem(), false).isEmpty();

            if (TreeChop.dropWins(onTree, despawning, bagFull, rawDropSq, blockSq)) {
                return closestDrop.map(Object.class::cast);
            } else {
                return closestBlock.map(Object.class::cast);
            }
        }

        // the drops of the tree we are on (or just finished). mid tree only once they cover what we came for, the logs still
        // standing would be overchop. once the tree is down, all of them, nearest first from wherever we are standing
        private Optional<ItemEntity> treeSweep(AltoClef mod, Vec3 pos, boolean onTree) {
            if (!mod.getEntityTracker().itemDropped(_targets)) {
                if (!onTree) {
                    _sweepTree = null;
                }
                return Optional.empty();
            }
            List<ItemEntity> lying = countableDrops(mod, _sweepTree);
            if (onTree) {
                if (!enoughWith(mod, lying)) {
                    return Optional.empty();
                }
            } else if (lying.isEmpty()) {
                _sweepTree = null;
                return Optional.empty();
            }
            // nearest of the counted ones, not nearestDrop: that one doesn't ask isValid, and a drop in the pond next door
            // would win every tick and get dropped by lockedDrop every tick
            ItemEntity best = null;
            for (ItemEntity drop : lying) {
                if (best == null || drop.distanceToSqr(pos) < best.distanceToSqr(pos)) {
                    best = drop;
                }
            }
            return Optional.ofNullable(best);
        }

        // our drops lying around the tree that we can still count on, see TreeChop.countable
        private List<ItemEntity> countableDrops(AltoClef mod, BlockPos tree) {
            List<ItemEntity> out = new ArrayList<>();
            for (ItemEntity drop : mod.getEntityTracker().getDroppedItems()) {
                if (!TreeChop.countable(!mod.getEntityTracker().isEntityReachable(drop), drop.getAge())) continue;
                if (!TreeStrikes.sameTree(tree, drop.blockPosition()) || !isValid(mod, drop)) continue;
                out.add(drop);
            }
            return out;
        }

        // every target covered by what we hold plus what is on the floor
        private boolean enoughWith(AltoClef mod, List<ItemEntity> lying) {
            for (ItemTarget target : _targets) {
                int onFloor = 0;
                for (ItemEntity drop : lying) {
                    if (target.matches(drop.getItem().getItem())) {
                        onFloor += drop.getItem().getCount();
                    }
                }
                if (!TreeChop.enough(mod.getItemStorage().getItemCount(target), onFloor, target.getTargetCount())) {
                    return false;
                }
            }
            return true;
        }

        // the block we were handed counts as started once we can reach it, not only once the controller reports the crack: a
        // drop from the last block rolling out of the pickup box in between used to win and the block was never touched
        private void beginHold(AltoClef mod, int now) {
            BlockPos at = _miningPos;
            if (at == null || !_stick.wouldBegin(at) || !stillOurs(mod, at)) {
                return;
            }
            _stick.begin(now, at);
            if (mod.getEntityTracker().itemDropped(_targets)) {
                // once per block, so the unprotected first ticks can be counted instead of guessed at
                Debug.logInternal("mine: holding " + at.toShortString() + " from the first tick, a drop is in sight");
            }
        }

        // a drop within the first-pick range that has been around long enough to be nearly gone. every 5th tick, this is
        // the exception and not worth a tracker query per tick of every break
        private boolean despawningDropNear(AltoClef mod, int now, Vec3 pos) {
            if (now % 5 != 0) {
                return false;
            }
            Optional<ItemEntity> drop = nearestDrop(mod, pos, true);
            return drop.isPresent() && MineStick.aboutToDespawn(drop.get().getAge()) && drop.get().distanceToSqr(pos) <= DROP_FIRST_RANGE_SQ;
        }

        // the next block to break. around the one we just broke while there is anything there, otherwise the nearest
        private Optional<BlockPos> pickBlock(AltoClef mod, Vec3 pos) {
            Optional<BlockPos> pick = pickBlock(mod, pos, _logsOnly);
            if (pick.isEmpty() && _logsOnly) {
                // every log left is one we can't get at and can't tower to. a log we might fail on beats standing still, and
                // the strikes (see giveUpTree) are what move us on from the tree
                pick = pickBlock(mod, pos, false);
            }
            return pick;
        }

        // sparing leaves out the logs we have no way up to (TreeReach.skip)
        private Optional<BlockPos> pickBlock(AltoClef mod, Vec3 pos, boolean sparing) {
            Predicate<BlockPos> usable = check -> {
                if (mod.getBlockTracker().unreachable(check)) return false;
                return WorldHelper.canBreak(mod, check) && _only.test(check) && (!sparing || worthTheTrip(mod, check));
            };
            // what we put down ourselves is scaffolding, not a resource. it is the best looking stone there is (exposed, at
            // our feet) and eating it is how the table placement looped. only when nothing else is close do we take it
            long gameTime = mod.getWorld().getGameTime();
            Predicate<BlockPos> notOurs = check -> usable.test(check) && !PlacedByUs.GLOBAL.recent(check.getX(), check.getY(), check.getZ(), gameTime);

            if (_anchor != null) {
                Predicate<BlockPos> inTree = check -> mod.getBlockTracker().blockIsValid(check, _blocks) && notOurs.test(check);
                Optional<BlockPos> around = topDown(mod, inTree);
                if (around.isEmpty()) {
                    around = MineAnchor.nearest(_anchor, mod.getBlockTracker().getKnownLocations(_blocks), _logsOnly, pos.x, pos.y, pos.z, inTree);
                }
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
            if (_stoneOnly && closestBlock.isPresent()) {
                closestBlock = Optional.of(reachableStone(mod, pos, closestBlock.get(), check -> mod.getBlockTracker().blockIsValid(check, _blocks) && notOurs.test(check)));
            }
            return closestBlock;
        }

        // up on a tower next to the trunk, the highest log in reach first (TreeChop.topDown)
        private Optional<BlockPos> topDown(AltoClef mod, Predicate<BlockPos> inTree) {
            if (!_logsOnly) {
                return Optional.empty();
            }
            int feetY = mod.getPlayer().blockPosition().getY();
            // the whole trunk, not the anchor box: that one only goes LOG_DOWN under the last break, so its "base" was always
            // right under our hands and the tower never counted as a tower
            List<BlockPos> tree = new ArrayList<>();
            for (BlockPos at : mod.getBlockTracker().getKnownLocations(_blocks)) {
                if (TreeChop.inTrunk(_anchor, at) && inTree.test(at)) {
                    tree.add(at);
                }
            }
            Vec3 eye = mod.getPlayer().getEyePosition();
            return Optional.ofNullable(TreeChop.topDown(tree, feetY, check -> eye.distanceToSqr(Vec3.atCenterOf(check)) <= HOLD_REACH_SQ));
        }

        // the last reach decision. the pick runs every tick and raycasts aren't free, so standing in the same block with the
        // same best we just reuse the answer
        private BlockPos _reachFeet;
        private BlockPos _reachBest;
        private BlockPos _reachPick;
        // only stone this close to the eye gets a raycast, past it nothing is going to be in reach anyway
        private static final double REACH_LOOK_SQ = 5 * 5;

        // the score ranks from the feet and doesn't care about the dirt in front of the face, so the best stone can be one
        // we have to walk to while one just as good is right there to hit. when the best is out of reach, a near tie we can
        // hit from here wins (see StoneDigRank.preferReachable)
        private BlockPos reachableStone(AltoClef mod, Vec3 pos, BlockPos best, Predicate<BlockPos> ok) {
            BlockPos feet = mod.getPlayer().blockPosition();
            if (feet.equals(_reachFeet) && best.equals(_reachBest) && (_reachPick.equals(best) || ok.test(_reachPick))) {
                return _reachPick;
            }
            _reachFeet = feet;
            _reachBest = best;
            _reachPick = best;
            if (LookHelper.getReach(best).isPresent()) {
                return best;
            }
            Vec3 eye = mod.getPlayer().getEyePosition();
            double bestScore = stoneScore(mod, pos.x, pos.y, pos.z, best.getX(), best.getY(), best.getZ());
            List<StoneDigRank.Scored> near = new ArrayList<>();
            for (BlockPos check : mod.getBlockTracker().getKnownLocations(_blocks)) {
                if (check.equals(best) || eye.distanceToSqr(Vec3.atCenterOf(check)) > REACH_LOOK_SQ || !ok.test(check)) {
                    continue;
                }
                near.add(new StoneDigRank.Scored(check, stoneScore(mod, pos.x, pos.y, pos.z, check.getX(), check.getY(), check.getZ())));
            }
            BlockPos hittable = StoneDigRank.preferReachable(near, bestScore, StoneDigRank.REACH_MARGIN, StoneDigRank.REACH_CHECKS,
                    check -> LookHelper.getReach(check).isPresent());
            if (hittable != null) {
                _reachPick = hittable;
            }
            return _reachPick;
        }

        // a block of ours going away is a break, and its drop is a tick or two from existing. picking the next block
        // right now is how a log fell and the bot walked off to the next one (-119,69,71 then -119,70,71, no pickup between),
        // so we stand still until the drop shows. the pick that follows has the patience lock for the walk to it
        @Override
        protected void onPursuitGone(AltoClef mod, Object gone) {
            if (!(gone instanceof BlockPos pos) || !pos.equals(_miningPos)) {
                return;
            }
            // a tunnel's cobble lands in vacuum range on its own while the next block cracks, stopping for it is pure cost
            if (_stoneOnly) {
                return;
            }
            if (mod.getBlockTracker().unreachable(pos) || mod.getBlockTracker().blockIsValid(pos, _blocks)) {
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
            return DropWatch.anyNear(mod, spot, DROP_SEEN_RADIUS);
        }

        // a drop that fell further from the block than this was somebody else's
        private static final double DROP_SEEN_RADIUS = 5;

        // the block we were on is gone and we did not give up on it: that was a break, so that is where we are working.
        // stone has its own idea of where to dig next (see StoneDigRank), a neighbourhood would walk it down a shaft
        private void noteBreak(AltoClef mod) {
            BlockPos was = _miningPos;
            if (was == null || _stoneOnly || mod.getBlockTracker().unreachable(was)) {
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
            BanPolicy.dropGaveUp(mod.getBans(), drop.getId(), EntityTracker.describe(drop));
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
                if (!mod.getEntityTracker().isEntityReachable(drop)) return false;
                if (!skipInReach) return true;
                Vec3 at = drop.position();
                return !DropPatience.alreadyInReach(at.x - feet.x, at.y - feet.y, at.z - feet.z)
                        || mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(drop.getItem(), false).isEmpty();
            };
            return mod.getEntityTracker().getClosestItemDrop(pos, worthIt, _targets);
        }

        // 6 blocks, squared. anything this close gets picked up before we mine another block
        private static final double DROP_FIRST_RANGE_SQ = TreeChop.DROP_FIRST_RANGE_SQ;

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

        // something to place with, asked the way the planner asks, so a tower we count on is one it will plan
        private static boolean hasThrowaway(AltoClef mod) {
            return mod.getClientBaritoneSettings().allowPlace.value && mod.getClientBaritone().getInventoryBehavior().hasGenericThrowaway();
        }

        // with placing off nothing we dig could be used, so there is no point in the trip
        private boolean dirtPossible(AltoClef mod, int now) {
            return !dirtLockedOut(now) && mod.getClientBaritoneSettings().allowPlace.value && _scan.dirtAround(mod, now);
        }

        // a lockout further off than any we set is from a world whose clock was not ours
        private static boolean dirtLockedOut(int now) {
            return now < dirtOffUntil && dirtOffUntil - now <= DIRT_OFF_TICKS;
        }

        // baritone has no way to the log and we have nothing to tower with: that's what dirt is for, the log isn't banned yet.
        // no dirtAround here, the getItemTask finds dirt on its own and a log we can't otherwise get is worth the walk
        private boolean askForDirt(AltoClef mod, BlockPos stuck) {
            // nether stems: no dirt down there, the trip would wander a minute and lock every high log out for five
            if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
                return false;
            }
            int now = WorldHelper.getTicks();
            int gap = askGap(mod, stuck, now);
            // a log in swinging range isn't missing a tower, the trip would come back with dirt and still no path
            if (!TreeChop.highUp(gap)) {
                return false;
            }
            boolean placing = mod.getClientBaritoneSettings().allowPlace.value;
            boolean holding = mod.getClientBaritone().getInventoryBehavior().hasGenericThrowaway();
            if (!TreeChop.noPathDirt(placing, holding, dirtLockedOut(now), tripsOn(stuck, now), TreeChop.tripsFor(gap))) {
                return false;
            }
            _dirtAsk = stuck.immutable();
            _dirtAskGap = gap;
            return true;
        }

        // how high the tower goes. a flagged log knows its floor, an unflagged one is measured from our feet (it has a trunk
        // under it, so the no path is about getting near it, not up to it)
        private int askGap(AltoClef mod, BlockPos log, int now) {
            TreeReachScan.Look look = _scan.look(mod, log, now);
            if (look.flagged()) {
                return look.gap();
            }
            return Math.max(0, log.getY() - mod.getPlayer().blockPosition().getY());
        }

        // the log the next dirt trip is for, and the ask is used up
        BlockPos takeDirtLog() {
            BlockPos at = _dirtAsk != null ? _dirtAsk : _miningPos;
            _dirtAsk = null;
            return at;
        }

        void backFromDirt() {
            _progressChecker.newTarget();
            _noPath.disarm();
        }

        // false for a log high over the ground (canopy, or a trunk with its bottom cut away) when we have nothing to tower
        // with and no dirt to get it from. an intact trunk is never flagged, it opens up as it is cut
        private boolean worthTheTrip(AltoClef mod, BlockPos log) {
            int now = WorldHelper.getTicks();
            TreeReachScan.Look look = _scan.look(mod, log, now);
            // most logs are not even flagged, and the bag and the dirt look are not free
            if (!look.flagged() || look.fromGround()) {
                return true;
            }
            return !TreeReach.skip(true, false, hasThrowaway(mod), dirtPossible(mod, now));
        }

        // how much more dirt to dig before the log we are on. 0 when that one needs no tower, or we hold enough blocks for the
        // climb, or we are mid swing at it, or there is no dirt about
        private int dirtWanted(AltoClef mod) {
            if (_dirtAsk != null) {
                // askForDirt only asks with nothing to place in the bag, so the whole tower
                return TreeReach.dirtWanted(_dirtAskGap);
            }
            BlockPos at = _miningPos;
            if (!_logsOnly || at == null || mod.getControllerExtras().isBreakingBlock()) {
                return 0;
            }
            int now = WorldHelper.getTicks();
            TreeReachScan.Look look = _scan.look(mod, at, now);
            if (!look.flagged() || look.fromGround() || !dirtPossible(mod, now)) {
                return 0;
            }
            // one trip per tree on this road. a second one for a giant only comes from askForDirt, once the tower ran dry
            if (tripsOn(at, now) > 0) {
                return 0;
            }
            // what the planner places from, the same list hasGenericThrowaway goes by. the old hardcoded four missed the rest
            // of the list, so a bag full of andesite still sent us off digging dirt
            List<Item> throwaways = mod.getClientBaritoneSettings().acceptableThrowawayItems.value;
            int held = mod.getItemStorage().getItemCount(throwaways.toArray(Item[]::new));
            return TreeReach.dirtShortfall(look.gap(), held);
        }

        private static void dirtOff(int now, int ticks) {
            dirtOffUntil = now + ticks;
        }

        private static void dirtTrip(BlockPos log, int now) {
            dirtTreeTrips = tripsOn(log, now) + 1;
            dirtTreeAt = log;
            dirtTreeTick = now;
        }

        // trips already made for this log's tree, while they still count
        private static int tripsOn(BlockPos log, int now) {
            boolean recent = dirtTreeAt != null && log != null && now >= dirtTreeTick && now - dirtTreeTick < DIRT_TREE_TICKS
                    && TreeStrikes.sameTree(dirtTreeAt, log);
            return recent ? dirtTreeTrips : 0;
        }

        // one of the blocks we are after, still standing, not given up on, and close enough to keep swinging at
        private boolean stillOurs(AltoClef mod, BlockPos pos) {
            if (mod.getBlockTracker().unreachable(pos)) return false;
            if (!mod.getBlockTracker().blockIsValid(pos, _blocks)) return false;
            return mod.getPlayer().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= HOLD_REACH_SQ;
        }

        // the mid-break block is only worth holding on to while we can still hit it
        private static final double HOLD_REACH_SQ = 5.5 * 5.5;

        // the block we are breaking beats a never-tried one no matter how close the new one is. a drop beats a block we
        // haven't started yet: getClosestTo only hands one out when it should win, and the one the break just made shows up
        // a tick after the block is gone, by when we already picked the next log. once the block is started (MineStick) or
        // we are on a drop, nothing but a drop about to despawn moves us
        @Override
        protected boolean mustSwitchTo(AltoClef mod, Object current, Object candidate) {
            if (candidate instanceof ItemEntity drop) {
                boolean held = current instanceof BlockPos at && _stick.isHolding(at);
                return MineStick.dropMayTakeOver(current instanceof ItemEntity, held, MineStick.aboutToDespawn(drop.getAge()));
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
            _dirtAsk = null;
            _sweepTree = null;
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
            NoPathScope.Verdict noPath = noPathVerdict(mod);
            if (noPath != NoPathScope.Verdict.NONE) {
                giveUpNoPath(mod, noPath);
            }
            if (_miningPos != null && !_progressChecker.check(mod, _miningPos)) {
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                if (_logsOnly && askForDirt(mod, _miningPos)) {
                    // same deal as giveUpNoPath: a path that goes nowhere with an empty bag gets a tower before a ban
                    Debug.logMessage("Got nowhere on the log at " + _miningPos.toShortString() + ", getting dirt to tower up to it.");
                    _miningPos = null;
                    _progressChecker.newTarget();
                    return super.onTick(mod);
                }
                Debug.logMessage("Failed to mine block. Suggesting it may be unreachable.");
                // a short ban right away (the tracker honours it, so the next pick is somewhere else) and a strike, three of which
                // is the long one. the short one used to be a set of this task's own that no other task could see
                BanPolicy.miningStalled(mod.getBans(), WorldHelper.getCurrentDimension(), _miningPos.getX(), _miningPos.getY(), _miningPos.getZ());
                mod.getBlockTracker().requestBlockUnreachable(_miningPos, 2, "mining got nowhere");
                if (_logsOnly) {
                    giveUpTree(mod, _miningPos);
                }
                _miningPos = null;
                _progressChecker.newTarget();
            }
            return super.onTick(mod);
        }

        // baritone's search for the block came back empty. the six second progress checker would get to the same ban, 6 s
        // (and a failed search or three) later, standing still the whole time. NONE when we aren't after a block, were away
        // from the wheel (the failures counted then were somebody else's asks), or can swing at it from here anyway
        private NoPathScope.Verdict noPathVerdict(AltoClef mod) {
            int now = WorldHelper.getTicks();
            boolean away = now - _lastTickAt > 2;
            _lastTickAt = now;
            // a block that is gone or already given up on is not what a search is for anymore (the wander that takes over from
            // a pursuit that ended would get blamed on it)
            if (_miningPos == null || mod.getBlockTracker().unreachable(_miningPos) || !mod.getBlockTracker().blockIsValid(_miningPos, _blocks)) {
                _noPath.disarm();
                return NoPathScope.Verdict.NONE;
            }
            var pathing = mod.getClientBaritone().getPathingBehavior();
            // failures first, then exhausted: the pathing thread writes them the other way round
            int failures = pathing.calcFailures();
            int exhausted = pathing.calcExhausted();
            boolean inReach = mod.getPlayer().getEyePosition().distanceToSqr(Vec3.atCenterOf(_miningPos)) <= HOLD_REACH_SQ
                    || mod.getControllerExtras().isBreakingBlock();
            if (away || inReach || !_noPath.armed()) {
                _noPath.arm(failures, exhausted);
                return NoPathScope.Verdict.NONE;
            }
            return _noPath.poll(failures, exhausted, pathing.isPathing());
        }

        // the block, and when the search looked at everything, the rest of its vein for a short while. a timed out search says
        // nothing about the neighbours, they may be a short walk from where it gave up
        private void giveUpNoPath(AltoClef mod, NoPathScope.Verdict verdict) {
            BlockPos stuck = _miningPos;
            var dim = WorldHelper.getCurrentDimension();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            if (_logsOnly && askForDirt(mod, stuck)) {
                // no ban, no strike: with dirt in the bag baritone towers up beside the trunk. a second no path after the trip
                // (tripsOn) is the real one and lands below
                Debug.logMessage("No way to the log at " + stuck.getX() + " " + stuck.getY() + " " + stuck.getZ() + " yet, getting dirt to tower up to it.");
                _miningPos = null;
                _progressChecker.newTarget();
                _noPath.disarm();
                return;
            }
            // stone and logs have no veins to speak of (a hillside, a tree with its own rules)
            boolean veins = verdict == NoPathScope.Verdict.EXHAUSTED && !_stoneOnly && !_logsOnly;
            List<BlockPos> cells = NoPathScope.cells(stuck, veins ? mod.getBlockTracker().getKnownLocations(_blocks) : List.of(), verdict, veins);
            BanPolicy.noPathBlock(mod.getBans(), dim, stuck.getX(), stuck.getY(), stuck.getZ());
            Set<Bans.Key> vein = new LinkedHashSet<>();
            for (int i = 1; i < cells.size(); i++) {
                BlockPos cell = cells.get(i);
                vein.add(Bans.Key.block(dim, cell.getX(), cell.getY(), cell.getZ()));
            }
            if (!vein.isEmpty()) {
                BanPolicy.noPathVein(mod.getBans(), vein, stuck.getX(), stuck.getY(), stuck.getZ());
            }
            // a strike either way, like the stall this replaces: a block that times out every time has to reach the long ban
            mod.getBlockTracker().requestBlockUnreachable(stuck, 2, "no path to it");
            if (_logsOnly) {
                // the stall used to be what counted a tree's strikes, and the pick relies on them to leave a canopy
                giveUpTree(mod, stuck);
            }
            Debug.logMessage("No way to the block at " + stuck.getX() + " " + stuck.getY() + " " + stuck.getZ()
                    + (verdict == NoPathScope.Verdict.EXHAUSTED ? " (searched it all)" : " (search timed out)")
                    + (vein.isEmpty() ? "" : ", skipping its vein too") + ".");
            _miningPos = null;
            _progressChecker.newTarget();
            _noPath.disarm();
        }

        // the per block ban is over in a minute and the same canopy came right back. the second stall on one tree gives up on
        // all of it for five minutes (it goes in the shared ban book, so any other task honours it too), and the anchor goes so
        // the next pick can't walk back into the tree it was just banned from
        private void giveUpTree(AltoClef mod, BlockPos stalled) {
            var dim = WorldHelper.getCurrentDimension();
            List<BlockPos> spots = STRIKES.strike(dim, stalled, WorldHelper.getTicks());
            if (spots.isEmpty()) {
                return;
            }
            // the spots that stalled go in themselves, the tracker may not know a log we were standing at
            Set<Bans.Key> logs = new LinkedHashSet<>();
            for (BlockPos spot : spots) {
                logs.add(Bans.Key.block(dim, spot.getX(), spot.getY(), spot.getZ()));
            }
            for (BlockPos log : mod.getBlockTracker().getKnownLocations(_blocks)) {
                for (BlockPos spot : spots) {
                    if (TreeStrikes.sameTree(spot, log)) {
                        logs.add(Bans.Key.block(dim, log.getX(), log.getY(), log.getZ()));
                        break;
                    }
                }
            }
            BanPolicy.treeGaveUp(mod.getBans(), logs, stalled.getX(), stalled.getY(), stalled.getZ());
            _anchor = null;
            _anchorAnnounced = false;
            Debug.logMessage("Giving up on the tree at " + stalled.getX() + " " + stalled.getY() + " " + stalled.getZ() + ", on to another one.");
        }

        @Override
        protected Task getGoalTask(Object obj) {
            if (obj instanceof BlockPos newPos) {
                if (_miningPos == null || !_miningPos.equals(newPos)) {
                    _progressChecker.newTarget();
                    _noPath.disarm();
                }
                _miningPos = newPos;
                return new DestroyBlockTask(_miningPos);
            }
            if (obj instanceof ItemEntity drop) {
                _miningPos = null;
                BlockPos leaf = leafUnder(drop);
                return leaf != null ? new DestroyBlockTask(leaf) : _pickupTask;
            }
            throw new UnsupportedOperationException("Shouldn't try to get the goal from object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        // a log drop that landed on the canopy. pathing up there is a climb through leaves that DropPatience gives up on,
        // the leaf under it is one swing and the drop comes down to us
        private BlockPos leafUnder(ItemEntity drop) {
            AltoClef mod = AltoClef.getInstance();
            if (!_logsOnly || mod == null || mod.getPlayer() == null) {
                return null;
            }
            BlockPos under = drop.blockPosition().below();
            boolean leaf = mod.getWorld().getBlockState(under).is(BlockTags.LEAVES);
            return TreeChop.cutLeafUnder(drop.getY() - mod.getPlayer().getY(), leaf) ? under : null;
        }

        @Override
        protected boolean isValid(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                // a block we gave up on is not valid anymore, or the "new target must be twice as close" rule would
                // keep us on it (it used to be swapped out for any other nearest block, which is how it got away with this)
                return !mod.getBlockTracker().unreachable(b)
                        && mod.getBlockTracker().blockIsValid(b, _blocks) && WorldHelper.canBreak(mod, b);
            }
            if (obj instanceof ItemEntity drop) {
                // picked up or despawned, don't keep chasing a ghost. one we gave up on or the pickup task banned stays dead
                if (!drop.isAlive() || !mod.getEntityTracker().isEntityReachable(drop)) return false;
                // never worth a swim, whatever the water setting says
                if (ItemPickupRules.lavaBlocksPickup(drop)) return false;
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
            _progressChecker.newTarget();
            _noPath.disarm();
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
