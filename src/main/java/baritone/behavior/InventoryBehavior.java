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

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.Helper;
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
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.Predicate;

public final class InventoryBehavior extends Behavior implements Helper {

    int ticksSinceLastInventoryMove;
    int[] lastTickRequestedMove; // not everything asks every tick, so remember the request while coming to a halt

    public InventoryBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        boolean shuffle = Baritone.settings().allowInventory.value;
        // the clutch item only works from the hotbar (nobody shuffles the inventory mid fall), so that one move is allowed
        // even when the user keeps allowInventory off
        boolean clutch = Baritone.settings().allowLadderClutch.value;
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
                requestSwapWithHotBar(firstValidThrowaway(), 8);
            }
            int pick = bestToolAgainst(Blocks.STONE, PickaxeItem.class);
            if (pick >= 9) {
                requestSwapWithHotBar(pick, 0);
            }
        }
        if (lastTickRequestedMove != null) {
            logDebug("Remembering to move " + lastTickRequestedMove[0] + " " + lastTickRequestedMove[1] + " from a previous tick");
            requestSwapWithHotBar(lastTickRequestedMove[0], lastTickRequestedMove[1]);
        }
    }

    // ladders crafted or picked up land wherever there was room, and the clutch only looks at the hotbar
    private void keepClutchItemOnHotbar() {
        if (pickClutchItem(false) != null) {
            return;
        }
        NonNullList<ItemStack> inv = ctx.player().getInventory().items;
        for (int i = 9; i < 36; i++) {
            if (inv.get(i).is(Items.VINE) || inv.get(i).is(Items.LADDER)) {
                attemptToPutOnHotbar(i, slot -> false);
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
                return true;
            }
        }
        return false;
    }

    public boolean attemptToPutOnHotbar(int inMainInvy, Predicate<Integer> disallowedHotbar) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return false;
        }
        OptionalInt destination = getTempHotbarSlot(disallowedHotbar);
        if (destination.isPresent()) {
            if (!requestSwapWithHotBar(inMainInvy, destination.getAsInt())) {
                return false;
            }
        }
        return true;
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

    private boolean requestSwapWithHotBar(int inInventory, int inHotbar) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return false; // and don't remember it either, it'd go off the second the pause ended
        }
        lastTickRequestedMove = new int[]{inInventory, inHotbar};
        if (ticksSinceLastInventoryMove < Baritone.settings().ticksBetweenInventoryMoves.value) {
            logDebug("Inventory move requested but delaying " + ticksSinceLastInventoryMove + " " + Baritone.settings().ticksBetweenInventoryMoves.value);
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value && !baritone.getInventoryPauserProcess().stationaryForInventoryMove()) {
            logDebug("Inventory move requested but delaying until stationary");
            return false;
        }
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, inInventory < 9 ? inInventory + 36 : inInventory, inHotbar, ClickType.SWAP, ctx.player());
        ticksSinceLastInventoryMove = 0;
        lastTickRequestedMove = null;
        return true;
    }

    private int firstValidThrowaway() { // TODO offhand idk
        NonNullList<ItemStack> invy = ctx.player().getInventory().items;
        // protected ones count too: this is hotbar upkeep for the path we're walking, same scope as the movements' placing
        for (int i = 0; i < invy.size(); i++) {
            if (Baritone.settings().acceptableThrowawayItems.value.contains(invy.get(i).getItem())) {
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
    // altoclef saving cobble for a recipe doesn't make a parkour any less in need of a block under its feet
    public boolean hasGenericThrowaway() {
        for (Item item : movementThrowaways(true)) {
            if (throwaway(false, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    private List<Item> movementThrowaways(boolean allowProtected) {
        return ThrowawayPicks.order(Baritone.settings().acceptableThrowawayItems.value, AltoClefSettings.getInstance()::isItemProtected, allowProtected);
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
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof BlockItem && maybe.equals(((BlockItem) stack.getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}))))) {
            return true; // gotem
        }
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock().equals(maybe.getBlock()))) {
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
                        requestSwapWithHotBar(i, 7);
                        p.getInventory().selected = 7;
                    }
                    return true;
                }
            }
        }

        return false;
    }
}
