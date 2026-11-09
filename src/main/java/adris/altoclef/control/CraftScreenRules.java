package adris.altoclef.control;

// when CraftScreenShow puts the inventory up and takes it down, with no game in it so the tests can ask
public final class CraftScreenRules {

    // half a second of the empty grid after the last click, so the result is seen landing in the bag before it shuts
    static final int LINGER_TICKS = 10;
    // stuff in the grid and nobody clicking for 5s is a craft somebody dropped, not a slow one
    static final int ABANDON_TICKS = 100;

    public enum Action { NOTHING, OPEN, CLOSE, FORGET }

    // everything the call depends on, read once a tick. trouble is "the bot needs its keys": with a screen up the game
    // stops looking at the use key, so eating, the shield and the bucket all freeze until it is gone. keysHeld is any
    // key down at all: opening a screen lets go of every key, and a magma or ledge sneak pin is not worth a tick of that
    public record Facts(boolean on, boolean showing, boolean stillOurs, boolean screenOpen, boolean clicked, boolean creative,
                        boolean inventoryMenu, boolean trouble, boolean keysHeld, int idleTicks, boolean gridEmpty) {
    }

    public static Action decide(Facts f) {
        if (f.showing()) {
            // the player closed it (E and esc go through closeContainer, which is fine) or put their own screen over it.
            // closing now would shut whatever they opened, so it just stops being ours
            if (!f.stillOurs()) return Action.FORGET;
            if (!f.on() || f.trouble() || f.creative() || !f.inventoryMenu()) return Action.CLOSE;
            if (f.clicked()) return Action.NOTHING;
            if (f.idleTicks() >= LINGER_TICKS && f.gridEmpty()) return Action.CLOSE;
            return f.idleTicks() >= ABANDON_TICKS ? Action.CLOSE : Action.NOTHING;
        }
        // never over somebody else's screen (their inventory, chat, the pause menu, our gui). creative's inventory is a
        // different screen entirely, and opening into trouble would just get shut again next tick
        boolean mayOpen = f.on() && !f.screenOpen() && !f.creative() && f.inventoryMenu() && !f.trouble() && !f.keysHeld();
        return f.clicked() && mayOpen ? Action.OPEN : Action.NOTHING;
    }

    private CraftScreenRules() {
    }
}
