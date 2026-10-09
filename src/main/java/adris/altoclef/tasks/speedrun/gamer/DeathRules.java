package adris.altoclef.tasks.speedrun.gamer;

// the pure half of "was that new player instance a death". a respawn swaps the LocalPlayer and so does a portal, so the swap
// alone says nothing. three things can say it did: the death packet (the stash), our own tick catching the player dying, or
// the old instance, which keeps its zero health after the swap. a dimension change is not a signal either way: a nether death
// respawns in the overworld, and a portal is a swap with all three quiet
public final class DeathRules {

    private DeathRules() {
    }

    // where the record of the death comes from, best first
    public enum Source {
        // the death packet: the earliest sample, taken before the body could slide or the damage source expire
        STASH,
        // our own tick saw the player dying and wrote it down
        TICK,
        // nobody saw it, the old instance is dead though
        OLD_INSTANCE,
        // a portal
        NONE
    }

    public static Source source(boolean tickSawDeath, boolean stashed, boolean oldInstanceDead) {
        if (stashed) {
            return Source.STASH;
        }
        if (tickSawDeath) {
            return Source.TICK;
        }
        return oldInstanceDead ? Source.OLD_INSTANCE : Source.NONE;
    }

    public static boolean seen(boolean tickSawDeath, boolean stashed, boolean oldInstanceDead) {
        return source(tickSawDeath, stashed, oldInstanceDead) != Source.NONE;
    }
}
