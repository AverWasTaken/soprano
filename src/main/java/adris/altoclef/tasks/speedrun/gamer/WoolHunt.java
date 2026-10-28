package adris.altoclef.tasks.speedrun.gamer;

// pure: the sheep chase for the beds. same clock as GoldHunt, its seconds live in RunState and the wool is gone from the plan for
// the rest of the run once it gives up. the end prep then crafts whatever the bag allows (EndGear.bedsRequired)
public final class WoolHunt {
    // the catalogue name of the chase
    public static final String NEED = "wool";

    private WoolHunt() {
    }

    // the chase's clock, only on ticks the kit runner ran it (same shape as GoldHunt.tick). out of time: no more sheep this run
    public static DiamondHunt.Step tick(RunState s, double lastAge, boolean chased, double age, double minutes) {
        if (!chased || s.woolHuntGaveUp) {
            return new DiamondHunt.Step(-1, false);
        }
        s.woolHuntSeconds = DiamondHunt.add(s.woolHuntSeconds, lastAge, age);
        if (s.woolHuntSeconds >= minutes * 60) {
            s.woolHuntGaveUp = true;
            return new DiamondHunt.Step(-1, true);
        }
        return new DiamondHunt.Step(age, false);
    }

    // the need the kit runner is on is the wool chase
    public static boolean chasing(KitNeed running) {
        return running != null && NEED.equals(running.catalogueName());
    }
}
