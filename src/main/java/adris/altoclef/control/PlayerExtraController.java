package adris.altoclef.control;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBreakingCancelEvent;
import adris.altoclef.eventbus.events.BlockBreakingEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

public class PlayerExtraController {

    private final AltoClef _mod;
    private BlockPos _blockBreakPos;
    private double _blockBreakProgress;

    public PlayerExtraController(AltoClef mod) {
        _mod = mod;

        EventBus.subscribe(BlockBreakingEvent.class, evt -> onBlockBreak(evt.blockPos, evt.progress));
        EventBus.subscribe(BlockBreakingCancelEvent.class, evt -> onBlockStopBreaking());
    }

    // continueDestroyBlock runs every tick we are mining, so a position nobody refreshed for this long is a block that
    // already broke. the cancel event only fires after three stopDestroyBlock calls and a finished break may never get them,
    // so the flag stayed up: a table placed where we had just mined a log failed its first interact check (it saw a
    // "break" making no progress) and got blacklisted and wandered off from
    private static final long BREAK_STALE_MS = 500;
    private volatile long _blockBreakStamp;

    private void onBlockBreak(BlockPos pos, double progress) {
        _blockBreakPos = pos;
        _blockBreakProgress = progress;
        _blockBreakStamp = System.currentTimeMillis();
    }

    private void onBlockStopBreaking() {
        _blockBreakPos = null;
        _blockBreakProgress = 0;
    }

    public BlockPos getBreakingBlockPos() {
        return _blockBreakPos;
    }

    public boolean isBreakingBlock() {
        return _blockBreakPos != null && System.currentTimeMillis() - _blockBreakStamp <= BREAK_STALE_MS;
    }

    public double getBreakingBlockProgress() {
        return _blockBreakProgress;
    }

    public boolean inRange(Entity entity) {
        return _mod.getPlayer().closerThan(entity, Baritone.settings().altoEntityReachRange.value);
    }

    public void attack(Entity entity) {
        if (inRange(entity)) {
            _mod.getController().attack(_mod.getPlayer(), entity);
            _mod.getPlayer().swing(InteractionHand.MAIN_HAND);
        }
    }
}
