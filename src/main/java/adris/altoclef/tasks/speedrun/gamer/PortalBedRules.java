package adris.altoclef.tasks.speedrun.gamer;

// the bed by the overworld nether portal, the rules half (tasks/PortalBedStep does the walking). a nether death lands us there
// and not at the world spawn, which is the whole point. pure so the order and the retry are testable without a game
public final class PortalBedRules {
    // one try and one retry, then the nether gets entered anyway with a loud line. a bed is worth a minute, not the run
    public static final int MAX_TRIES = 2;
    // after a give up the next way in may look again this much later (game ticks): the food trips all come back through here
    public static final long RETRY_AFTER_TICKS = 20 * 60 * 5;
    // a bed this close to the portal (flat, either axis) counts as the one standing by it
    public static final int STANDING_NEAR = 12;

    private PortalBedRules() {
    }

    public enum Source {
        STANDING, BAG, CRAFT, NONE
    }

    // a bed already standing by the portal costs nothing (the task clicks it before it ever looks at the bag), then one from the
    // bag (borrowed from the End's, END_PREP buys it back), then one crafted from the bag's wool. never a hunt for one
    public static Source source(boolean standing, int bagBeds, boolean canCraft) {
        if (standing) {
            return Source.STANDING;
        }
        if (bagBeds > 0) {
            return Source.BAG;
        }
        return canCraft ? Source.CRAFT : Source.NONE;
    }

    // a craft needs three wool of one colour and three planks after the table's cut (BedCraft.usableWood), nothing gets hunted for
    public static boolean canCraft(int bestWoolStack, int usableWood) {
        return bestWoolStack >= 3 && usableWood >= 3;
    }

    public enum After {
        DONE, RETRY, GIVE_UP
    }

    // what a finished try leads to. confirmed = the game says we respawn by the portal now, not "the task said done" (it says done
    // on its own 5 s guess and on nowhere to put a bed too)
    public static After after(boolean confirmed, int tries) {
        if (confirmed) {
            return After.DONE;
        }
        return tries < MAX_TRIES ? After.RETRY : After.GIVE_UP;
    }
}
