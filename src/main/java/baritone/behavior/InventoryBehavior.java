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

package baritone.behavior;

import adris.altoclef.trace.Trace;
import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.Helper;
import baritone.utils.ExperimentalMovement;
import baritone.utils.ToolSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

public final class InventoryBehavior extends Behavior implements Helper {

    private static final int THROWAWAY_SLOT = 7;

    int ticksSinceLastInventoryMove;
    int[] lastTickRequestedMove; // not everything asks every tick, so remember the request while coming to a halt
    private String lastTickRequestedWho; // who asked for it, so the replay in the trace says whose it was
    // the item throwaway() selected slot 7 for while its swap was throttled, and when. lastTickRequestedMove can't answer
    // this: the pick and the throwaway refill overwrite it every tick before the click is looked at
    private Item awaitedItem;
    private long awaitedAt;
    private long tickCount;
    // what was in hand last tick, for the trace. a flickering hotbar is invisible in a log otherwise
    private int lastSelected = -1;
    private Item lastHeld;

    public InventoryBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.OUT) {
            tickCount++;
            traceHand();
        }
        boolean shuffle = Baritone.settings().allowInventory.value;
        // the clutch item only works from the hotbar (nobody shuffles the inventory mid fall), so that one move is allowed
        // even when the user keeps allowInventory off
        boolean clutch = ExperimentalMovement.allowLadderClutch();
        if (!(shuffle || clutch) || AltoClefSettings.getInstance().isInteractionPaused()) {
            return; // paused means the hotbar is somebody else's problem right now
        }
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            // we have a crafting table or a chest or something open
            return;
        }
        ticksSinceLastInventoryMove++;
        if (clutch && baritone.getPathingBehavior().isPathing()) {
            keepClutchItemOnHotbar();
        }
        if (shuffle && baritone.getPathingBehavior().isPathing()) {
            if (firstValidThrowaway() >= 9) { // aka there are none on the hotbar, but there are some in main inventory
                requestSwapWithHotBar(firstValidThrowaway(), 8, "tool");
            }
            int pick = bestToolAgainst(Blocks.STONE, PickaxeItem.class);
            if (pick >= 9) {
                requestSwapWithHotBar(pick, 0, "tool");
            }
        }
        if (lastTickRequestedMove != null) {
            logDebug("Remembering to move " + lastTickRequestedMove[0] + " " + lastTickRequestedMove[1] + " from a previous tick");
            requestSwapWithHotBar(lastTickRequestedMove[0], lastTickRequestedMove[1], lastTickRequestedWho, true);
        }
    }

    private void traceHand() {
        LocalPlayer p = ctx.player();
        if (p == null) {
            return;
        }
        int slot = p.getInventory().selected;
        Item held = p.getInventory().items.get(slot).getItem();
        if (lastSelected >= 0 && (slot != lastSelected || held != lastHeld)) {
            Trace.record("held " + lastHeld + "->" + held + " slot " + lastSelected + "->" + slot);
        }
        lastSelected = slot;
        lastHeld = held;
    }

    // ladders crafted or picked up land wherever there was room, and the clutch only looks at the hotbar
    private void keepClutchItemOnHotbar() {
        if (pickClutchItem(false) != null) {
            return;
        }
        NonNullList<ItemStack> inv = ctx.player().getInventory().items;
        for (int i = 9; i < 36; i++) {
            if (inv.get(i).is(Items.VINE) || inv.get(i).is(Items.LADDER)) {
                attemptToPutOnHotbar(i, slot -> false, "clutch");
                return;
            }
        }
    }

    // a fall doesn't wait for the move delay or for us to stand still (we're not going to be, that's the whole problem), so
    // this just does the swap. altoclef's unplanned fall calls it when the ladder is still in the main inventory
    public boolean fetchClutchItemNow() {
        if (AltoClefSettings.getInstance().isInteractionPaused() || ctx.player().containerMenu != ctx.player().inventoryMenu || !ctx.player().containerMenu.getCarried().isEmpty()) {
            return false;
        }
        if (pickClutchItem(false) != null) {
            return true; // already there
        }
        NonNullList<ItemStack> inv = ctx.player().getInventory().items;
        for (int i = 9; i < 36; i++) {
            if (inv.get(i).is(Items.VINE) || inv.get(i).is(Items.LADDER)) {
                OptionalInt slot = getTempHotbarSlot(s -> false);
                if (slot.isEmpty()) {
                    return false;
                }
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, i, slot.getAsInt(), ClickType.SWAP, ctx.player());
                ticksSinceLastInventoryMove = 0;
                Trace.record("swap inv" + i + "<->hot" + slot.getAsInt() + " by clutch");
                return true;
            }
        }
        return false;
    }

    public boolean attemptToPutOnHotbar(int inMainInvy, Predicate<Integer> disallowedHotbar) {
        return attemptToPutOnHotbar(inMainInvy, disallowedHotbar, "other");
    }

    public boolean attemptToPutOnHotbar(int inMainInvy, Predicate<Integer> disallowedHotbar, String who) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return false;
        }
        OptionalInt destination = getTempHotbarSlot(disallowedHotbar);
        if (destination.isPresent()) {
            if (!requestSwapWithHotBar(inMainInvy, destination.getAsInt(), who)) {
                return false;
            }
        }
        return true;
    }

    // attemptToPutOnHotbar for someone who wants to know where it went (or is going, a throttled one lands later).
    // tiers are disallowed sets tried in order until one leaves a slot. a move from inMainInvy that's already pending
    // keeps its slot, picking a fresh random one every tick it waits was a swap fight all by itself
    public OptionalInt fetchToHotbar(int inMainInvy, List<IntPredicate> tiers, String who) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return OptionalInt.empty();
        }
        OptionalInt destination = OptionalInt.empty();
        // the loosest tier, a pending slot that turned useful meanwhile is still off limits
        if (lastTickRequestedMove != null && lastTickRequestedMove[0] == inMainInvy && !tiers.isEmpty()
                && !tiers.get(tiers.size() - 1).test(lastTickRequestedMove[1])) {
            destination = OptionalInt.of(lastTickRequestedMove[1]);
        }
        for (int i = 0; i < tiers.size() && destination.isEmpty(); i++) {
            destination = getTempHotbarSlot(tiers.get(i)::test);
        }
        if (destination.isPresent()) {
            requestSwapWithHotBar(inMainInvy, destination.getAsInt(), who);
        }
        return destination;
    }

    // the one place a placement click is vetted (see InputOverrideHandler): throwaway() selects slot 7 even when the swap into
    // it was throttled, and a right click with a bucket or a steak in hand empties the bucket or eats the steak
    public boolean handWaitingOnSwap() {
        if (awaitedItem == null) {
            return false;
        }
        LocalPlayer p = ctx.player();
        return ThrowawayPicks.handWaiting(awaitedItem, p.getInventory().items.get(THROWAWAY_SLOT).getItem(), THROWAWAY_SLOT,
                p.getInventory().selected, awaitedAt, tickCount);
    }

    public OptionalInt getTempHotbarSlot(Predicate<Integer> disallowedHotbar) {
        // we're using 0 and 8 for pickaxe and throwaway. the rest goes through ThrowawayPicks so a temp swap can't stomp
        // the slot in hand or the one the next placement is about to pull from (that one was a fun afternoon)
        NonNullList<ItemStack> inv = ctx.player().getInventory().items;
        boolean[] empty = new boolean[9];
        int throwawaySlot = -1;
        for (int i = 0; i < 9; i++) {
            empty[i] = inv.get(i).isEmpty();
            if (throwawaySlot < 0 && Baritone.settings().acceptableThrowawayItems.value.contains(inv.get(i).getItem())) {
                throwawaySlot = i;
            }
        }
        int pending = lastTickRequestedMove == null ? -1 : lastTickRequestedMove[1];
        Random rng = new Random();
        return ThrowawayPicks.tempHotbarSlot(empty, disallowedHotbar::test, ctx.player().getInventory().selected, throwawaySlot, pending, rng::nextInt);
    }

    private boolean requestSwapWithHotBar(int inInventory, int inHotbar, String who) {
        return requestSwapWithHotBar(inInventory, inHotbar, who, false);
    }

    private boolean requestSwapWithHotBar(int inInventory, int inHotbar, String who, boolean replay) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return false; // and don't remember it either, it'd go off the second the pause ended
        }
        lastTickRequestedMove = new int[]{inInventory, inHotbar};
        lastTickRequestedWho = who;
        if (!moveReady()) {
            return false;
        }
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, inInventory < 9 ? inInventory + 36 : inInventory, inHotbar, ClickType.SWAP, ctx.player());
        ticksSinceLastInventoryMove = 0;
        lastTickRequestedMove = null;
        lastTickRequestedWho = null;
        Trace.record("swap inv" + inInventory + "<->hot" + inHotbar + " by " + who + (replay ? "/replay" : ""));
        return true;
    }

    // the move delay first, so a throttled move doesn't also stop us to wait for it (stationaryForInventoryMove asks for a pause)
    private boolean moveReady() {
        if (ticksSinceLastInventoryMove < Baritone.settings().ticksBetweenInventoryMoves.value) {
            logDebug("Inventory move requested but delaying " + ticksSinceLastInventoryMove + " " + Baritone.settings().ticksBetweenInventoryMoves.value);
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value && !baritone.getInventoryPauserProcess().stationaryForInventoryMove()) {
            logDebug("Inventory move requested but delaying until stationary");
            return false;
        }
        return true;
    }

    // someone else's inventory click, held to the same rules as ours: allowInventory, the move delay, standing still. the click
    // itself stays theirs (altoclef's SlotHandler has its own cooldown) and only counts as a move if it went out. no
    // remembering a throttled one like requestSwapWithHotBar does, callers ask again next tick and their slots may have moved
    public boolean inventoryMoveIfAllowed(BooleanSupplier click) {
        if (!Baritone.settings().allowInventory.value || AltoClefSettings.getInstance().isInteractionPaused()
                || ctx.player().containerMenu != ctx.player().inventoryMenu || !moveReady()) {
            return false;
        }
        if (!click.getAsBoolean()) {
            return false;
        }
        ticksSinceLastInventoryMove = 0;
        Trace.record("inventory click by hotbartools");
        return true;
    }

    private int firstValidThrowaway() { // TODO offhand idk
        NonNullList<ItemStack> invy = ctx.player().getInventory().items;
        // same list the movements place from, so the hotbar never gets a stack that has nothing above its reserve
        List<Item> usable = movementThrowaways(true);
        for (int i = 0; i < invy.size(); i++) {
            if (usable.contains(invy.get(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private int bestToolAgainst(Block against, Class<? extends DiggerItem> cla$$) {
        NonNullList<ItemStack> invy = ctx.player().getInventory().items;
        int bestInd = -1;
        double bestSpeed = -1;
        for (int i = 0; i < invy.size(); i++) {
            ItemStack stack = invy.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (Baritone.settings().itemSaver.value && (stack.getDamageValue() + Baritone.settings().itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                continue;
            }
            if (cla$$.isInstance(stack.getItem())) {
                double speed = ToolSet.calculateSpeedVsBlock(stack, against.defaultBlockState()); // takes into account enchants
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestInd = i;
                }
            }
        }
        return bestInd;
    }

    // movement scope: the planner and the movements both ask this, so they can't disagree about what's in the bag.
    // altoclef saving cobble for a recipe doesn't make a parkour any less in need of a block under its feet, but only the
    // cobble above what it's saving counts. (the planner asks once when the context is built and the movements ask again
    // every placement, so a path planned on a surplus that's since been spent fails in the movement and replans)
    public boolean hasGenericThrowaway() {
        for (Item item : movementThrowaways(true)) {
            if (throwaway(false, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    // how many blocks a path could actually burn right now, the same spare-above-the-reserve count the movements use.
    // the trip ask takes this off what a what-if path needs before it goes collecting
    public int spareThrowawayCount() {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        int total = 0;
        for (Item item : new LinkedHashSet<>(movementThrowaways(true))) {
            total += ThrowawayPicks.spare(heldCount(item), alto.reservedCount(item));
        }
        return total;
    }

    private List<Item> movementThrowaways(boolean allowProtected) {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        return ThrowawayPicks.order(Baritone.settings().acceptableThrowawayItems.value, alto::isItemProtected, item -> ThrowawayPicks.spare(heldCount(item), alto.reservedCount(item)), allowProtected);
    }

    // the same spare-above-the-reserve rule movementThrowaways applies, for the lookups that match a stack by what the builder
    // wants there. those skipped it and would grab a cobble the recipe was saving as long as it was the right block
    private boolean mayBurn(Item item, boolean allowProtected) {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        boolean isProtected = alto.isItemProtected(item);
        int spare = isProtected ? ThrowawayPicks.spare(heldCount(item), alto.reservedCount(item)) : 0;
        return ThrowawayPicks.mayBurn(isProtected, allowProtected, spare);
    }

    // everywhere it could be, a stack in the offhand is just as placeable. read fresh every ask: a placement takes one off
    // the stack, and the next ask has to see that or we'd spend the reserve one block at a time
    private int heldCount(Item item) {
        int total = 0;
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        ItemStack offhand = ctx.player().getInventory().offhand.get(0);
        return offhand.is(item) ? total + offhand.getCount() : total;
    }

    // why the lookups came back empty, for the log. same inputs as the real thing, just asked a different way
    public String whyNoThrowaway(boolean allowProtected, int x, int y, int z) {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        List<Item> acceptable = Baritone.settings().acceptableThrowawayItems.value;
        List<Item> usable = movementThrowaways(allowProtected);
        boolean haveAny = false;
        boolean haveUsable = false;
        boolean usableOnHotbar = false;
        NonNullList<ItemStack> inv = ctx.player().getInventory().items;
        for (int i = 0; i <= inv.size(); i++) {
            boolean offhand = i == inv.size();
            Item item = (offhand ? ctx.player().getInventory().offhand.get(0) : inv.get(i)).getItem();
            haveAny |= acceptable.contains(item);
            if (usable.contains(item)) {
                haveUsable = true;
                usableOnHotbar |= i < 9 || offhand;
            }
        }
        return ThrowawayPicks.classify(alto.isInteractionPaused(), alto.shouldAvoidPlacingAt(x, y, z), haveAny, haveUsable, usableOnHotbar).why;
    }

    // vine first, it has no collision box so we can't land on top of it or get the placement refused for standing in it
    // hotbar only, same as the water bucket: there's no time to shuffle the inventory in the middle of a fall
    public Item pickClutchItem(boolean select) {
        if (throwaway(select, stack -> stack.is(Items.VINE), false)) {
            return Items.VINE;
        }
        if (throwaway(select, stack -> stack.is(Items.LADDER), false)) {
            return Items.LADDER;
        }
        return null;
    }

    // allowProtected is the movement scope: a path that needs a block under it right now beats a recipe's stash.
    // anything that isn't mid-movement (backfill) passes false and leaves altoclef's items alone
    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z, boolean allowProtected) {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        if (alto.isInteractionPaused() || alto.shouldAvoidPlacingAt(x, y, z)) {
            return false;
        }
        BlockState maybe = baritone.getBuilderProcess().placeAt(x, y, z, baritone.bsi.get0(x, y, z));
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof BlockItem && mayBurn(stack.getItem(), allowProtected) && maybe.equals(((BlockItem) stack.getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}))))) {
            return true; // gotem
        }
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof BlockItem && mayBurn(stack.getItem(), allowProtected) && ((BlockItem) stack.getItem()).getBlock().equals(maybe.getBlock()))) {
            return true;
        }
        for (Item item : movementThrowaways(allowProtected)) {
            if (throwaway(select, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired) {
        return throwaway(select, desired, Baritone.settings().allowInventory.value);
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired, boolean allowInventory) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return false; // no selecting, no swapping, and nothing counts as being in the hotbar
        }
        LocalPlayer p = ctx.player();
        NonNullList<ItemStack> inv = p.getInventory().items;
        if (select) {
            awaitedItem = null; // whatever we pick now is picked fresh, an old wait is not about it
        }
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.get(i);
            // this usage of settings() is okay because it's only called once during pathing
            // (while creating the CalculationContext at the very beginning)
            // and then it's called during execution
            // since this function is never called during cost calculation, we don't need to migrate
            // acceptableThrowawayItems to the CalculationContext
            if (desired.test(item)) {
                if (select) {
                    p.getInventory().selected = i;
                }
                return true;
            }
        }
        if (desired.test(p.getInventory().offhand.get(0))) {
            // main hand takes precedence over off hand
            // that means that if we have block A selected in main hand and block B in off hand, right clicking places block B
            // we've already checked above ^ and the main hand can't possible have an acceptablethrowawayitem
            // so we need to select in the main hand something that doesn't right click
            // so not a shovel, not a hoe, not a block, etc
            for (int i = 0; i < 9; i++) {
                ItemStack item = inv.get(i);
                if (item.isEmpty() || item.getItem() instanceof PickaxeItem) {
                    if (select) {
                        p.getInventory().selected = i;
                    }
                    return true;
                }
            }
        }

        if (allowInventory) {
            for (int i = 9; i < 36; i++) {
                if (desired.test(inv.get(i))) {
                    if (select) {
                        // a throttled swap stays pending and lands on a later tick, slot 7 is selected so it lands in hand.
                        // until then nothing may click, see handWaitingOnSwap
                        boolean landed = requestSwapWithHotBar(i, THROWAWAY_SLOT, "throwaway");
                        p.getInventory().selected = THROWAWAY_SLOT;
                        awaitedItem = landed ? null : inv.get(i).getItem();
                        awaitedAt = tickCount;
                    }
                    return true;
                }
            }
        }

        return false;
    }
}
