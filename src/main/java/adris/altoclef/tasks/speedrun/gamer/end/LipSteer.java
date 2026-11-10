package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.WalkIntoEndPortalTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasksystem.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// the backstop under the middle block goal, for both portal walks: 3 s standing still on the lip means the goal walk is not
// getting us in (an eye or a fountain corner holding us up, or whatever we didn't think of), so walk straight at the middle
// by hand for a bit. ask every tick the walk is the step, null = let the goal walk do it
public final class LipSteer {
    private WalkIntoEndPortalTask _steer;
    private Vec3 _last;
    private int _lastTick = Integer.MIN_VALUE;
    private int _still;
    private int _left;

    public Task tick(AltoClef mod, GamerContext ctx) {
        // a tick we weren't asked (another step won, a respawn, a new dimension: the player's tickCount starts over) starts it over
        int now = mod.getPlayer().tickCount;
        if (now != _lastTick + 1) {
            forget();
        }
        _lastTick = now;
        Vec3 at = mod.getPlayer().position();
        double moved = _last == null ? 1 : Math.hypot(at.x - _last.x, at.z - _last.z);
        _last = at;
        BlockPos portal = mod.getBlockTracker().getNearestTracking(at, Blocks.END_PORTAL).orElse(null);
        if (portal == null || !EndPortalWalk.onTheLip(at.x - portal.getX() - 0.5, at.y - portal.getY(), at.z - portal.getZ() - 0.5)) {
            _still = 0;
            _left = 0;
            return null;
        }
        if (_left > 0) {
            _left--;
            return _steer;
        }
        _still = EndPortalWalk.stillTicks(_still, true, moved);
        if (!EndPortalWalk.steer(_still)) {
            return null;
        }
        BlockPos middle = mod.getBlockTracker().getNearestTracking(at, p -> EndPortalWalk.interiorIn(mod, p), Blocks.END_PORTAL).orElse(null);
        if (middle == null) {
            return null;
        }
        _still = 0;
        _left = EndPortalWalk.STEER_FOR;
        if (_steer == null || !_steer.middle().equals(middle)) {
            _steer = new WalkIntoEndPortalTask(middle);
        }
        ctx.log("stuck on the end portal's lip, walking straight at " + middle.toShortString());
        return _steer;
    }

    private void forget() {
        _last = null;
        _still = 0;
        _left = 0;
    }
}
