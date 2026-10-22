package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import baritone.api.utils.Dimension;

// the pure half of the trip back for a nether death. we respawn in the overworld, the pile in the nether stops aging the
// moment nobody is there to load it, so it is still waiting when we come back through the portal. no world in here:
// NetherRecoverTask feeds it numbers, GamerTask asks it whether a death is worth a trip at all
public final class NetherTripRules {

    // the walk hands over to the pile task this close. the pile task has its own standoff for whatever killed us, and that
    // looks 16 blocks out, so the hand over has to be further out than that or we walk into the crowd before it can look
    public static final int ARRIVE_BLOCKS = 20;
    // how close the walk itself tries to get, a little inside ARRIVE_BLOCKS or baritone calls it done a hair outside the line
    public static final int WALK_RANGE = 16;
    // a failed trip that is still in the nether walks home for this long before we hand the run back as it is
    public static final int HOME_SECONDS = 120;
    // a second death this close to the last pile is the same hazard, not a new pile
    public static final int SAME_SPOT_BLOCKS = 8;
    // the overworld stages run on their own clocks, the trip budget only starts at the nether (the pile does not age before that).
    // the pick gets the same cap the pile task gives itself
    public static final int PICK_SECONDS = (int) RecoverRules.PICK_SECONDS;
    public static final int PORTAL_SECONDS = 300;
    // the portal walk has to close this many blocks (3d, a horizontal number lies when the portal is under a mountain) in this
    // long or it is not getting there
    public static final int PORTAL_PROGRESS_BLOCKS = 8;
    public static final int PORTAL_PROGRESS_SECONDS = 60;

    private NetherTripRules() {
    }

    // why we died, as far as the pile cares: lava eats item entities and the void takes them
    public enum Cause {
        LAVA,
        VOID,
        OTHER;
    }

    public static Cause cause(boolean inLava, boolean outOfTheWorld) {
        if (inLava) {
            return Cause.LAVA;
        }
        return outOfTheWorld ? Cause.VOID : Cause.OTHER;
    }

    public enum Stage {
        // by hand, in the overworld
        BLOCKS,
        // no stone pick on us: get one, the way down to the portal and the netherrack on the far side both want it
        PICK,
        // to the home portal and through it
        PORTAL,
        // across the nether to the pile
        WALK,
        // at the pile, picking up
        RECOVER,
        // the pile gave armor back and it is still in the bag
        WEAR,
        // gave up while still in the nether without a kit: walk back to the overworld so the rebuild can start
        HOME;

        public static Stage parse(String name) {
            if (name != null) {
                for (Stage s : values()) {
                    if (s.name().equals(name)) {
                        return s;
                    }
                }
            }
            return null;
        }
    }

    // ---- eligibility

    // a nether death that left us in the overworld: the only shape of death this trip is for. a death where we respawned
    // in the nether (an anchor) is the plain same dimension recovery
    public static boolean applies(Dimension died, Dimension now) {
        return died == Dimension.NETHER && now == Dimension.OVERWORLD;
    }

    // null = go for it, otherwise the reason it is not worth it (said in the log, so it reads as a sentence)
    public static String refuse(Cause cause, boolean homeKnown, boolean enabled, boolean tripPending, boolean pileTried,
                                boolean kitIntact) {
        if (!enabled) {
            return "the trip is switched off";
        }
        if (tripPending) {
            return "already on a trip, this is a second death";
        }
        // keepInventory, or whatever else left the kit on us: nothing down there we need
        if (kitIntact) {
            return "the kit is still on us";
        }
        if (cause == Cause.LAVA) {
            return "it was lava";
        }
        if (cause == Cause.VOID) {
            return "it was the void";
        }
        if (!homeKnown) {
            return "no portal to go back through";
        }
        if (pileTried) {
            return "already went for this pile";
        }
        return null;
    }

    // the pile of the last trip, near enough that it is the same spot. nothing tried yet = false
    public static boolean tried(RunState.Pos last, int x, int z) {
        if (last == null) {
            return false;
        }
        long dx = (long) last.x - x;
        long dz = (long) last.z - z;
        return dx * dx + dz * dz <= (long) SAME_SPOT_BLOCKS * SAME_SPOT_BLOCKS;
    }

    // game time since we first stood in the nether, 0 until then. what the trip budget counts
    public static long netherTicks(long arrivedTick, long now) {
        return arrivedTick <= 0 ? 0 : now - arrivedTick;
    }

    // the walk was not ticking for a while (combat or food had the wheel): start the no progress clock over
    public static boolean markStale(long lastSeenTick, long now) {
        return now - lastSeenTick > 40;
    }

    // the portal walk got far enough in since the mark to count as moving
    public static boolean closedIn(double markDistance, double distance) {
        return markDistance - distance >= PORTAL_PROGRESS_BLOCKS;
    }

    // ---- the stages

    // the numbers from the config, in ticks. homeTicks is fixed
    public record Limits(long tripTicks, int blocksWanted, long blocksTicks, long homeTicks, long pickTicks, long portalTicks,
                         long progressTicks) {
        public static Limits of(int tripSeconds, int blocksWanted, int blocksSeconds) {
            return new Limits(tripSeconds * 20L, blocksWanted, blocksSeconds * 20L, HOME_SECONDS * 20L, PICK_SECONDS * 20L,
                    PORTAL_SECONDS * 20L, PORTAL_PROGRESS_SECONDS * 20L);
        }
    }

    // everything one decision looks at. tripTicks is game time since we reached the nether (netherTicks, 0 before that),
    // stageTicks since this stage began, stuckTicks since the portal walk last closed PORTAL_PROGRESS_BLOCKS
    public record Inputs(Stage stage, Dimension dim, long tripTicks, long stageTicks, int buildBlocks, boolean homeGone,
                         double pileDistance, boolean recoverFinished, int itemsGained, boolean kitShort, int toWear, Limits limits,
                         boolean hasPick, long stuckTicks) {
        // the common shape: a pick in the bag and a walk that is going fine
        public Inputs(Stage stage, Dimension dim, long tripTicks, long stageTicks, int buildBlocks, boolean homeGone,
                      double pileDistance, boolean recoverFinished, int itemsGained, boolean kitShort, int toWear, Limits limits) {
            this(stage, dim, tripTicks, stageTicks, buildBlocks, homeGone, pileDistance, recoverFinished, itemsGained, kitShort,
                    toWear, limits, true, 0);
        }
    }

    // stage = where the trip goes from here, null when it is over. giveUp = why, set exactly when this step is a give up
    // (the stage can still be HOME then). recovered = the pile came back with something and the trip is done
    public record Step(Stage stage, String giveUp, boolean recovered) {
    }

    public static Step next(Inputs in) {
        Stage st = in.stage();
        if (st == Stage.HOME) {
            boolean back = in.dim() == Dimension.OVERWORLD || in.stageTicks() > in.limits().homeTicks();
            return back ? over() : stay(st);
        }
        // the clock going backwards is another world, not a trip with all its time left
        if (in.tripTicks() < 0 || in.stageTicks() < 0 || in.tripTicks() > in.limits().tripTicks()) {
            return giveUp(in, "out of time");
        }
        return switch (st) {
            case BLOCKS -> blocks(in);
            case PICK -> pick(in);
            case PORTAL -> portal(in);
            case WALK -> walk(in);
            case RECOVER -> recover(in);
            case WEAR -> wear(in);
            default -> over();
        };
    }

    private static Step blocks(Inputs in) {
        if (in.dim() != Dimension.OVERWORLD) {
            return giveUp(in, "not in the overworld to get blocks");
        }
        // short of the count after a minute is still worth going with: the blocks only make the walk safer
        if (in.buildBlocks() >= in.limits().blocksWanted() || in.stageTicks() > in.limits().blocksTicks()) {
            return new Step(in.hasPick() ? Stage.PORTAL : Stage.PICK, null, false);
        }
        return stay(Stage.BLOCKS);
    }

    // no height gate: an 88 block shaft through deepslate does not go with bare hands or wood, and the portal chunk may not
    // even be loaded from here to look at
    private static Step pick(Inputs in) {
        if (in.dim() != Dimension.OVERWORLD) {
            return giveUp(in, "not in the overworld to get a pickaxe");
        }
        if (in.hasPick()) {
            return new Step(Stage.PORTAL, null, false);
        }
        if (in.stageTicks() > in.limits().pickTicks()) {
            return giveUp(in, "no pickaxe in time");
        }
        return stay(Stage.PICK);
    }

    private static Step portal(Inputs in) {
        if (in.dim() == Dimension.NETHER) {
            return new Step(Stage.WALK, null, false);
        }
        if (in.dim() != Dimension.OVERWORLD) {
            return giveUp(in, "ended up in the wrong dimension");
        }
        if (in.homeGone()) {
            return giveUp(in, "the portal is gone");
        }
        if (in.stageTicks() > in.limits().portalTicks()) {
            return giveUp(in, "the portal took too long");
        }
        if (in.stuckTicks() > in.limits().progressTicks()) {
            return giveUp(in, "no closer to the portal");
        }
        return stay(Stage.PORTAL);
    }

    private static Step walk(Inputs in) {
        if (in.dim() != Dimension.NETHER) {
            return giveUp(in, "left the nether");
        }
        if (in.pileDistance() <= ARRIVE_BLOCKS) {
            return new Step(Stage.RECOVER, null, false);
        }
        return stay(Stage.WALK);
    }

    private static Step recover(Inputs in) {
        if (in.dim() != Dimension.NETHER) {
            return giveUp(in, "left the nether");
        }
        if (!in.recoverFinished()) {
            return stay(Stage.RECOVER);
        }
        // armor in the bag counts for nothing until it is on, so wear it before judging the kit. and before the "nothing came back"
        // check too: the gain is measured off the bag's low point, and a pile that only gave armor can read as nothing
        if (in.toWear() > 0) {
            return new Step(Stage.WEAR, null, false);
        }
        return pileVerdict(in);
    }

    private static Step wear(Inputs in) {
        if (in.dim() != Dimension.NETHER) {
            return giveUp(in, "left the nether");
        }
        // no clock of its own: a piece still in the bag is a piece we have, and the trip budget above ends this if it never goes on
        if (in.toWear() > 0) {
            return stay(Stage.WEAR);
        }
        return pileVerdict(in);
    }

    private static Step pileVerdict(Inputs in) {
        if (in.itemsGained() <= 0) {
            return giveUp(in, "nothing left at the pile");
        }
        return verdict(in);
    }

    // something came back but maybe not the kit: the phase machine only rebuilds from the overworld, so a pile without the
    // kit is the same as an empty one (giveUp walks home from the nether)
    private static Step verdict(Inputs in) {
        return in.kitShort() ? giveUp(in, "the pile did not have the kit") : new Step(null, null, true);
    }

    private static Step stay(Stage st) {
        return new Step(st, null, false);
    }

    private static Step over() {
        return new Step(null, null, false);
    }

    // still in the nether with the kit gone: the phase machine only rebuilds from the overworld, so go home first. a kit that
    // is fine (or the overworld already) just ends the trip and the machine carries on
    private static Step giveUp(Inputs in, String why) {
        boolean stranded = in.dim() == Dimension.NETHER && in.kitShort();
        return new Step(stranded ? Stage.HOME : null, why, false);
    }
}
