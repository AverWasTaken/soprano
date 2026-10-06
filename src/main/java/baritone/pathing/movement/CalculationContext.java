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

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.cache.WorldData;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.ExperimentalMovement;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.SearchCache;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * @author Brady
 * @since 8/7/2018
 */
public class CalculationContext {

    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);

    public final boolean safeForThreadedUse;
    public final IBaritone baritone;
    public final Level world;
    public final WorldData worldData;
    public final BlockStateInterface bsi;
    public final ToolSet toolSet;
    public final boolean hasWaterBucket;
    // a ladder or vine on the hotbar and allowLadderClutch on, see MovementDescend.dynamicFallCost
    public final boolean hasClutchItem;
    // hp plus absorption right now, for falls that hurt. same snapshot rule as hasWaterBucket
    public final double health;
    public final boolean experimental;
    public final double experimentalMinHealth;
    public final double fallDamageCost;
    // 1 unless experimentalMovement wants the jumpy movements to look a bit cheaper, see biasJump
    public final double jumpBias;
    public final boolean preferFasterPathing;
    // the clutch item is a ladder (vines win if both are there) and pickupLadders is on, so a clutch also costs the pickup
    public final boolean clutchPicksUp;
    public final float blockReach;
    public final boolean hasThrowaway;
    public final boolean canSprint;
    protected final double placeBlockCost; // protected because you should call the function instead
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowParkour;
    public final boolean allowParkourPlace;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean allowNeos;
    public final boolean allowClimbJumps;
    public final boolean allowMomentumJumps;
    public final boolean assumeWalkOnWater;
    public boolean allowFallIntoLava;
    public final int frostWalker;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    public int minFallHeight;
    public int maxFallHeightNoWater;
    public final int maxFallHeightBucket;
    public final double waterWalkSpeed;
    // rowing, when there's a boat to row. equal to waterWalkSpeed when there isn't so the min is a no-op
    public final double boatWaterSpeed;
    public final double breakBlockAdditionalCost;
    // snapshotted like the rest, so one search can't price some breaks with the old value and some with the new
    public final double waterBreakCostMultiplier;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final double walkOnWaterOnePenalty;
    public final boolean allowWalkOnMagmaBlocks;
    public final BetterWorldBorder worldBorder;
    // world.dimensionType() goes through a Holder and we were calling it twice per block. per block!
    public final int minY;
    public final int maxY;

    public final PrecomputedData precomputedData;

    // altoclef's position rules, frozen here so a search sees one answer all the way through and never takes a lock.
    // the arrays are null when altoclef isn't holding any of that kind and altoActive is false when none of them are, so
    // idle costs a field check per question. (it used to be a mutex, a new BlockPos and a stream. per call. in A*)
    public final boolean altoActive;
    private final LongOpenHashSet altoBreakPositions;
    private final Predicate<BlockPos>[] altoBreakAvoiders;
    private final Predicate<BlockPos>[] altoPlaceAvoiders;
    private final Predicate<BlockPos>[] altoWalkOn;
    private final Predicate<BlockPos>[] altoAvoidWalkThrough;
    // one pos for the searching thread to reuse. predicates must not keep it, it's about to become somewhere else
    private final BlockPos.MutableBlockPos altoPos;

    // memo for getMiningDurationTicks by position
    // the block under you gets its break cost worked out by four descends, a downward, and then all your neighbours' descends
    // and every one of those reads five more blocks for avoidBreaking. the answer doesn't change mid search so just remember it
    // two of everything because includeFalling is part of the question
    // only exists while a search has claimed it, see claimSearchCaches
    public static final int MINING_CACHE_BITS = 16;
    final int miningCacheBits;
    private SearchCache.MiningBuffers miningBuffers;
    long[] miningKeys;
    long[] miningKeysFalling;
    double[] miningVals;
    double[] miningValsFalling;
    Thread miningOwner;

    public CalculationContext(IBaritone baritone) {
        this(baritone, false);
    }

    public CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread) {
        this(
                baritone,
                forUseOnAnotherThread,
                baritone.getPlayerContext().world(),
                (WorldData) baritone.getPlayerContext().worldData(),
                new BlockStateInterface(baritone.getPlayerContext(), forUseOnAnotherThread),
                new ToolSet(baritone.getPlayerContext().player()),
                Baritone.settings().allowPlace.value && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway(),
                Baritone.settings().allowWaterBucketFall.value && Inventory.isHotbarSlot(baritone.getPlayerContext().player().getInventory().findSlotMatchingItem(STACK_BUCKET_WATER)) && baritone.getPlayerContext().world().dimension() != Level.NETHER,
                Baritone.settings().allowSprint.value && baritone.getPlayerContext().player().getFoodData().getFoodLevel() > 6,
                frostWalkerLevel(baritone.getPlayerContext().player()),
                waterSpeedMultiplier(baritone.getPlayerContext().player()),
                baritone.getPlayerContext().player().getHealth() + baritone.getPlayerContext().player().getAbsorptionAmount(),
                hasBoat(baritone.getPlayerContext().player())
        );
    }

    // everything that needs a player or a world comes in as a parameter so you can build one of these with no game running
    // all the settings get snapshotted in here so nobody can accidentally read them differently
    @SuppressWarnings("unchecked")
    protected CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread, Level world, WorldData worldData, BlockStateInterface bsi, ToolSet toolSet, boolean hasThrowaway, boolean hasWaterBucket, boolean canSprint, int frostWalker, float waterSpeedMultiplier, double health, boolean hasBoat) {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        AltoClefSettings.Snapshot altoRules = alto.snapshot();
        boolean paused = alto.isInteractionPaused();
        this.altoBreakPositions = altoRules.breakPositions;
        this.altoBreakAvoiders = altoRules.breakAvoiders;
        this.altoPlaceAvoiders = altoRules.placeAvoiders;
        this.altoWalkOn = altoRules.forceWalkOn;
        this.altoAvoidWalkThrough = altoRules.avoidWalkThrough;
        this.altoActive = altoRules.hasPathingRules();
        this.altoPos = altoActive ? new BlockPos.MutableBlockPos() : null;
        this.precomputedData = PrecomputedData.forCurrentSettings();
        this.miningCacheBits = SearchCache.bitsForSize(Baritone.settings().pathingCacheSize.value);
        this.safeForThreadedUse = forUseOnAnotherThread;
        this.baritone = baritone;
        this.world = world;
        this.worldData = worldData;
        this.bsi = bsi;
        this.toolSet = toolSet;
        this.hasThrowaway = hasThrowaway && !paused; // paused means no placing, whatever's in the hotbar
        this.hasWaterBucket = hasWaterBucket;
        // same idea as the bucket, except it isn't banned in the nether. the setting goes first so nobody scans the hotbar for nothing
        Item clutchItem = Baritone.settings().allowLadderClutch.value ? ((Baritone) baritone).getInventoryBehavior().pickClutchItem(false) : null;
        this.hasClutchItem = clutchItem != null;
        this.clutchPicksUp = clutchItem == Items.LADDER && Baritone.settings().pickupLadders.value;
        this.health = health;
        this.experimental = ExperimentalMovement.on();
        this.experimentalMinHealth = Baritone.settings().experimentalMinHealth.value;
        this.fallDamageCost = Baritone.settings().fallDamageCost.value;
        this.jumpBias = ExperimentalMovement.jumpBias();
        this.preferFasterPathing = ExperimentalMovement.preferFasterPathing();
        this.blockReach = Baritone.settings().blockReachDistance.value;
        this.canSprint = canSprint;
        this.minY = bsi.minY;
        this.maxY = bsi.maxY;
        this.placeBlockCost = ExperimentalMovement.blockPlacementPenalty();
        this.allowBreak = !paused && Baritone.settings().allowBreak.value;
        // allowBreakAnyway would sneak past a pause otherwise
        this.allowBreakAnyway = paused ? new ArrayList<>() : new ArrayList<>(Baritone.settings().allowBreakAnyway.value);
        this.allowParkour = ExperimentalMovement.allowParkour();
        this.allowParkourPlace = Baritone.settings().allowParkourPlace.value;
        this.allowJumpAtBuildLimit = Baritone.settings().allowJumpAtBuildLimit.value;
        this.allowParkourAscend = ExperimentalMovement.allowParkourAscend();
        this.allowNeos = ExperimentalMovement.allowNeos();
        this.allowClimbJumps = ExperimentalMovement.allowClimbJumps();
        this.allowMomentumJumps = ExperimentalMovement.allowMomentumJumps();
        this.assumeWalkOnWater = Baritone.settings().assumeWalkOnWater.value;
        this.allowFallIntoLava = false; // Super secret internal setting for ElytraBehavior
        this.frostWalker = frostWalker;
        this.allowDiagonalDescend = ExperimentalMovement.allowDiagonalDescend();
        this.allowDiagonalAscend = ExperimentalMovement.allowDiagonalAscend();
        this.allowDownward = Baritone.settings().allowDownward.value;
        this.minFallHeight = 3; // Minimum fall height used by MovementFall
        this.maxFallHeightNoWater = Baritone.settings().maxFallHeightNoWater.value;
        this.maxFallHeightBucket = Baritone.settings().maxFallHeightBucket.value;
        double waterSpeed = ActionCosts.WALK_ONE_IN_WATER_COST * (1 - waterSpeedMultiplier) + ActionCosts.WALK_ONE_BLOCK_COST * waterSpeedMultiplier;
        if (Baritone.settings().allowSwimming.value && this.canSprint) {
            // sprint swimming beats wading on the bottom, unless depth strider is good enough to flip that
            waterSpeed = Math.min(waterSpeed, ActionCosts.SWIM_ONE_BLOCK_COST);
        }
        this.waterWalkSpeed = waterSpeed;
        // a boat really is faster than sprinting, but the heuristic assumes nothing is. price it under
        // costHeuristic and A* stops being A* and wanders off on scenic detours across the lake. so rowing
        // costs what the heuristic thinks a block costs and not a tick less. still way under swimming
        double boatSpeed = Math.max(ActionCosts.BOAT_ONE_BLOCK_COST, Baritone.settings().costHeuristic.value);
        this.boatWaterSpeed = Baritone.settings().allowBoats.value && hasBoat ? Math.min(waterSpeed, boatSpeed) : waterSpeed;
        this.breakBlockAdditionalCost = Baritone.settings().blockBreakAdditionalPenalty.value;
        this.waterBreakCostMultiplier = Baritone.settings().waterBreakCostMultiplier.value;
        this.backtrackCostFavoringCoefficient = Baritone.settings().backtrackCostFavoringCoefficient.value;
        this.jumpPenalty = Baritone.settings().jumpPenalty.value;
        this.walkOnWaterOnePenalty = Baritone.settings().walkOnWaterOnePenalty.value;
        this.allowWalkOnMagmaBlocks = Baritone.settings().allowWalkOnMagmaBlocks.value;
        // why cache these things here, why not let the movements just get directly from settings?
        // because if some movements are calculated one way and others are calculated another way,
        // then you get a wildly inconsistent path that isn't optimal for either scenario.
        this.worldBorder = bsi.worldBorder;
    }

    /**
     * The cost of one block of water at this node. Rowing if a boat could float here, otherwise whatever
     * wading or swimming costs. Same answer the executor gives when it decides where to actually use the
     * boat, so the estimate and the execution agree about which water counts
     */
    public double waterCost(int x, int y, int z) {
        if (boatWaterSpeed >= waterWalkSpeed) {
            return waterWalkSpeed; // no boat, don't go reading nine blocks for nothing
        }
        return MovementHelper.canFloatBoat(bsi, x, y, z) ? boatWaterSpeed : waterWalkSpeed;
    }

    private static boolean hasBoat(LocalPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() instanceof BoatItem) {
                return true;
            }
        }
        return false;
    }

    private static int frostWalkerLevel(LocalPlayer player) {
        // todo: technically there can now be datapack enchants that replace blocks with any other at any range
        int frostWalkerLevel = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = player.getItemBySlot(slot).getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(Enchantments.FROST_WALKER)) {
                    frostWalkerLevel = itemEnchantments.getLevel(enchant);
                }
            }
        }
        return frostWalkerLevel;
    }

    private static float waterSpeedMultiplier(LocalPlayer player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = player.getItemBySlot(slot).getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchant.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect effect : effects) {
                    if (effect.attribute().is(Attributes.WATER_MOVEMENT_EFFICIENCY.unwrapKey().get())) {
                        return effect.amount().calculate(itemEnchantments.getLevel(enchant));
                    }
                }
            }
        }
        return 1.0f;
    }

    // the memo and the bsi's block cache used to be allocated in the constructor. that's 2.8mb, and PathingBehavior
    // builds one of these every tick whether it paths or not, so it was 50mb/s of garbage for contexts that ask three
    // questions and die. searches now borrow a worker's arrays and detach them when they're done
    // it's also the only thread allowed to touch them. PathExecutor checks costs with this same context on the main thread,
    // and two threads scribbling on one open addressed table is how you get the key from one block and the value from another
    public synchronized void claimSearchCaches() {
        if (miningOwner != null) {
            return; // somebody else is searching with this context. they keep it, we go without
        }
        miningBuffers = SearchCache.acquireMining(1 << miningCacheBits);
        miningKeys = miningBuffers.keys;
        miningKeysFalling = miningBuffers.fallingKeys;
        miningVals = miningBuffers.values;
        miningValsFalling = miningBuffers.fallingValues;
        miningOwner = Thread.currentThread();
        bsi.claimCache();
    }

    public synchronized void releaseSearchCaches() {
        if (miningOwner != Thread.currentThread()) {
            return;
        }
        miningOwner = null;
        miningKeys = null;
        miningKeysFalling = null;
        miningVals = null;
        miningValsFalling = null;
        SearchCache.release(miningBuffers);
        miningBuffers = null;
        bsi.releaseCache();
    }

    public final IBaritone getBaritone() {
        return baritone;
    }

    public BlockState get(int x, int y, int z) {
        return bsi.get0(x, y, z); // laughs maniacally
    }

    public boolean isLoaded(int x, int z) {
        return bsi.isLoaded(x, z);
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) { // only true if allowPlace is true, see constructor
            return COST_INF;
        }
        if (isPlaceProtected(x, y, z)) {
            return COST_INF;
        }
        if (!worldBorder.canPlaceAt(x, z)) {
            return COST_INF;
        }
        if (!Baritone.settings().allowPlaceInFluidsSource.value && current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (!Baritone.settings().allowPlaceInFluidsFlow.value && !current.getFluidState().isEmpty() && !current.getFluidState().isSource()) {
            return COST_INF;
        }
        return placeBlockCost;
    }

    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        if (isBreakProtected(x, y, z)) {
            return COST_INF;
        }
        return 1;
    }

    // parkour, neo and climb all run their cost through this where it's produced, so the planner and calculateCost agree
    public double biasJump(double cost) {
        return cost * jumpBias;
    }

    public double placeBucketCost() {
        return placeBlockCost; // shrug
    }

    // the two halves of what used to be one isPossiblyProtected stub. altoclef is the only thing that fills them in so far
    // (see #220 for anyone who wants to add their own)
    public boolean isBreakProtected(int x, int y, int z) {
        if (altoBreakPositions != null && altoBreakPositions.contains(BlockPos.asLong(x, y, z))) {
            return true;
        }
        return altoBreakAvoiders != null && altoAnyMatch(altoBreakAvoiders, x, y, z);
    }

    public boolean isPlaceProtected(int x, int y, int z) {
        return altoPlaceAvoiders != null && altoAnyMatch(altoPlaceAvoiders, x, y, z);
    }

    // callers check altoActive first so the idle case doesn't even get here
    public boolean altoForcesWalkOn(int x, int y, int z) {
        return altoWalkOn != null && altoAnyMatch(altoWalkOn, x, y, z);
    }

    public boolean altoAvoidsWalkThrough(int x, int y, int z) {
        return altoAvoidWalkThrough != null && altoAnyMatch(altoAvoidWalkThrough, x, y, z);
    }

    private boolean altoAnyMatch(Predicate<BlockPos>[] predicates, int x, int y, int z) {
        // the executor checks costs with the same context while a search is running, so the shared pos only goes to the
        // thread that claimed the search caches. anybody else pays for a fresh one
        BlockPos pos = Thread.currentThread() == miningOwner ? altoPos.set(x, y, z) : new BlockPos(x, y, z);
        return AltoClefSettings.Snapshot.anyMatch(predicates, pos);
    }
}
