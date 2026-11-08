package adris.altoclef.util.helpers;

// the run's rim shield with a leash: up for RIM_HOLD_TICKS in a row, then down until nobody wants it at all. the foe does not
// matter: two cubes taking turns as the closest one must not make a fresh leash every swap. the shield pauses pathing, so
// without the cap a cube that keeps pace would pin the run on the rim for good
public final class RimHold {
    private int held;
    // every tick the shield was up this episode, exempt ticks too. the leash is for a cube hanging back, this is for one that
    // stays in our face: a cube we block never dies of it, so the in reach exemption alone would stand there forever
    private int up;
    private boolean told;

    // foe: the id the shield wants this tick, -1 for none. true when the shield may be up
    public boolean step(int foe) {
        return step(foe, false);
    }

    // exempt: this tick doesn't spend the leash (a cube in arm's reach), and it even lifts a spent one. only the cap says no then
    public boolean step(int foe, boolean exempt) {
        if (foe < 0) {
            reset();
            return false;
        }
        if (up >= CombatRules.RIM_GUARD_CAP_TICKS) return false;
        if (!exempt) {
            if (held >= CombatRules.RIM_HOLD_TICKS) return false;
            held++;
        }
        up++;
        return true;
    }

    // the cap ran out on this episode, nothing lifts it until nobody wants the shield
    public boolean capped() {
        return up >= CombatRules.RIM_GUARD_CAP_TICKS;
    }

    // true once per episode, the tick after the cap ran out. the brain's log line asks so it says it once and not every tick
    public boolean capNews() {
        if (!capped() || told) return false;
        told = true;
        return true;
    }

    public void reset() {
        held = 0;
        up = 0;
        told = false;
    }
}
