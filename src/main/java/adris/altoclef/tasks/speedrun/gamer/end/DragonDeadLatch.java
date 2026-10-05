package adris.altoclef.tasks.speedrun.gamer.end;

// "the dragon is dead", decided from facts and latched. the old static latch fired whenever the dragon entity was merely
// not loaded (low render distance), so: the exit portal exists, or we HAVE seen the dragon and it has been gone for
// goneSeconds with chunk (0,0) loaded (an unloaded chunk resets the timer, the dragon could be anywhere)
public class DragonDeadLatch {
    private final double goneSeconds;
    private boolean seen;
    private boolean dead;
    private double goneSince = -1;

    public DragonDeadLatch(double goneSeconds) {
        this.goneSeconds = goneSeconds;
    }

    public boolean update(boolean exitPortalPresent, boolean dragonVisible, boolean centerChunkLoaded, double now) {
        if (dead) {
            return true;
        }
        if (exitPortalPresent) {
            dead = true;
        } else if (dragonVisible) {
            seen = true;
            goneSince = -1;
        } else if (seen && centerChunkLoaded) {
            if (goneSince < 0) {
                goneSince = now;
            }
            dead = now - goneSince >= goneSeconds;
        } else {
            goneSince = -1;
        }
        return dead;
    }

    public boolean isDead() {
        return dead;
    }

    public boolean hasSeenDragon() {
        return seen;
    }

    // a run that already knows (RunState.dragonDead after a relog)
    public void markDead() {
        dead = true;
    }

    public void reset() {
        seen = false;
        dead = false;
        goneSince = -1;
    }
}
