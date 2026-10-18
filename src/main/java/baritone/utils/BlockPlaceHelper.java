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

package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class BlockPlaceHelper {
    // base ticks between places caused by tick logic
    private static final int BASE_PLACE_DELAY = 1;
    // a throwaway click the world hasn't shown yet is given up on after this many ticks. the client predicts the block
    // the same tick, so this is only for a laggy server putting it back late
    private static final int CONFIRM_TICKS = 10;

    private final IPlayerContext ctx;
    private int rightClickTimer;
    // who hears about throwaway blocks we put down, null while nobody cares (then nothing gets tracked at all)
    private PlaceListener listener;
    private final List<Pending> pending = new ArrayList<>(2);

    BlockPlaceHelper(IPlayerContext playerContext) {
        this.ctx = playerContext;
    }

    // a throwaway block click went through and the block is really there now. game thread
    public interface PlaceListener {
        void placed(BlockPos pos, BlockState state);
    }

    private static final class Pending {
        final BlockPos pos;
        final Block block;
        int age;

        Pending(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
        }
    }

    public void setPlaceListener(PlaceListener listener) {
        this.listener = listener;
        pending.clear();
    }

    public PlaceListener placeListener() {
        return listener;
    }

    // the list baritone pillars and bridges with. altoclef merges its own throwaways into it while a task runs, so this
    // is the same list the movements picked the block from
    public static boolean throwaway(Item item) {
        return item instanceof BlockItem && Baritone.settings().acceptableThrowawayItems.value.contains(item);
    }

    // would a click requested right now go out. same two checks as tick, so a movement can plan around the throttle
    public boolean ready() {
        return rightClickTimer <= 0 && !ctx.player().isHandsBusy();
    }

    public void tick(boolean rightClickRequested) {
        confirm();
        if (rightClickTimer > 0) {
            rightClickTimer--;
            return;
        }
        HitResult mouseOver = ctx.objectMouseOver();
        if (!rightClickRequested || ctx.player().isHandsBusy() || mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
            return;
        }
        rightClickTimer = Baritone.settings().rightClickSpeed.value - BASE_PLACE_DELAY;
        for (InteractionHand hand : InteractionHand.values()) {
            // asked before the click: the last block of a stack leaves the hand empty, and empty stacks say air
            Pending scaffold = scaffoldClick(hand, (BlockHitResult) mouseOver);
            if (ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand, (BlockHitResult) mouseOver) == InteractionResult.SUCCESS) {
                ctx.player().swing(hand, SwingAnimation.DEFAULT, false);
                if (scaffold != null) {
                    pending.add(scaffold);
                }
                return;
            }
            if (!ctx.player().getItemInHand(hand).isEmpty() && ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand) == InteractionResult.SUCCESS) {
                return;
            }
        }
    }

    // where a throwaway in this hand would land. BlockPlaceContext knows the replaceable rule (tall grass, snow), so
    // clicking a flower lands in the flower's cell and not the one past it
    private Pending scaffoldClick(InteractionHand hand, BlockHitResult hit) {
        if (listener == null) {
            return null;
        }
        ItemStack held = ctx.player().getItemInHand(hand);
        if (held.isEmpty() || !throwaway(held.getItem())) {
            return null;
        }
        BlockPos at = new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), hand, held, hit) {}).getClickedPos().immutable();
        Block block = ((BlockItem) held.getItem()).getBlock();
        if (ctx.world().getBlockState(at).is(block)) {
            return null; // already there, whatever happens next isn't ours
        }
        return new Pending(at, block);
    }

    // the click going out isn't the block being there, a full cell or an entity in the way eats it
    private void confirm() {
        if (pending.isEmpty()) {
            return;
        }
        if (listener == null || ctx.world() == null) {
            pending.clear();
            return;
        }
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            BlockState state = ctx.world().getBlockState(p.pos);
            if (state.is(p.block)) {
                it.remove();
                listener.placed(p.pos, state);
            } else if (++p.age > CONFIRM_TICKS) {
                it.remove();
            }
        }
    }
}
