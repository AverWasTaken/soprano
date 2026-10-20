package adris.altoclef.tasks.speedrun.gamer;

import java.util.List;

// pure: the clock on the diamond pick dig (KitPlanner.addDiamondPick) and when it is over. the seconds live in RunState, so a retry
// or a relog picks up where the dig left off, and a pick lost later digs again on what is left of the same clock
public final class DiamondHunt {
    // IRON budget the dig leaves when nothing else is owed after it: the portal gate still has its own few crafts to do
    public static final double LAST_MARGIN_SECONDS = 90;
    // and when wool or food is still behind it. a sheep hunt is a few minutes, starving it to dig one more minute is a bad trade
    public static final double MORE_MARGIN_SECONDS = 180;

    private DiamondHunt() {
    }

    // `age` is the attempt's clock this tick (secondsInPhase, which already skips the death walk's held time), `lastAge` the same
    // clock on the last tick the dig was the head, -1 when it was not (or the phase was just entered). only the stretch between two
    // dig ticks counts, so the time a retry or relog ate, or a need in front of the dig took, is never billed to it
    public static double add(double hunted, double lastAge, double age) {
        return lastAge >= 0 && age > lastAge ? hunted + (age - lastAge) : hunted;
    }

    // dug for diamondPickMinutes all told, or the IRON budget is down to the margin. a dig that starts with less than that left
    // is over on its first tick. a budget of 0 is no budget
    public static boolean over(double hunted, double minutes, double budgetMinutes, double secondsInPhase, boolean moreAfter) {
        if (hunted >= minutes * 60) {
            return true;
        }
        double margin = moreAfter ? MORE_MARGIN_SECONDS : LAST_MARGIN_SECONDS;
        return budgetMinutes > 0 && budgetMinutes * 60 - secondsInPhase < margin;
    }

    // anything still owed behind the dig in this plan. the pick's own craft (diamondSpend off) is the dig finishing, not something after
    public static boolean moreAfter(List<KitNeed> needs) {
        boolean past = false;
        for (KitNeed n : needs) {
            String name = n.catalogueName();
            if (past && !name.equals(KitPlanner.DIAMOND) && !name.equals("diamond_pickaxe")) {
                return true;
            }
            past = past || name.equals(KitPlanner.DIAMOND);
        }
        return false;
    }

    // the dig is what the plan is on right now
    public static boolean digging(List<KitNeed> needs) {
        return needs != null && !needs.isEmpty() && KitPlanner.DIAMOND.equals(needs.get(0).catalogueName());
    }
}
