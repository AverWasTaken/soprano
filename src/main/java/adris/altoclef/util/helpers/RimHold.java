package adris.altoclef.util.helpers;

// the run's rim shield with a leash: up for RIM_HOLD_TICKS in a row on one foe, then down until that foe is gone (or a new one
// shows up). the shield pauses pathing, so without the cap a cube that keeps pace would pin the run on the rim for good
public final class RimHold {
    private int foeId = -1;
    private int held;

    // foe: the id the shield wants this tick, -1 for none. true when the shield may be up
    public boolean step(int foe) {
        if (foe < 0) {
            reset();
            return false;
        }
        if (foe != foeId) {
            foeId = foe;
            held = 0;
        }
        if (held >= CombatRules.RIM_HOLD_TICKS) return false;
        held++;
        return true;
    }

    public void reset() {
        foeId = -1;
        held = 0;
    }
}
