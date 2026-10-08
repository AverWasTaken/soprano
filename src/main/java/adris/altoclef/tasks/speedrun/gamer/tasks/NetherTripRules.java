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

    private NetherTripRules() {
    }

    // why we died, as far as the pile cares: lava eats item entities and the void takes them
    public enum Cause {
        LAVA,
        VOID,
        OTHER;

        // an old save has no cause, and a hand edit could say anything
        public static Cause parse(String name) {
            if (name != null) {
                for (Cause c : values()) {
                    if (c.name().equalsIgnoreCase(name)) {
                        return c;
                    }
                }
            }
            return OTHER;
        }
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
        // to the home portal and through it
        PORTAL,
        // across the nether to the pile
        WALK,
        // at the pile, picking up
        RECOVER,
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
    public static String refuse(Cause cause, boolean homeKnown, boolean enabled, boolean tripPending, boolean pileTried) {
        if (!enabled) {
            return "the trip is switched off";
        }
        if (tripPending) {
            return "already on a trip, this is a second death";
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

    // ---- the stages

    // the numbers from the config, in ticks. homeTicks is fixed
    public record Limits(long tripTicks, int blocksWanted, long blocksTicks, long homeTicks) {
        public static Limits of(int tripSeconds, int blocksWanted, int blocksSeconds) {
            return new Limits(tripSeconds * 20L, blocksWanted, blocksSeconds * 20L, HOME_SECONDS * 20L);
        }
    }

    // everything one decision looks at. tripTicks / stageTicks are game time since the trip / this stage began
    public record Inputs(Stage stage, Dimension dim, long tripTicks, long stageTicks, int buildBlocks, boolean homeGone,
                         double pileDistance, boolean recoverFinished, int itemsGained, boolean kitShort, Limits limits) {
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
        if (in.tripTicks() < 0 || in.tripTicks() > in.limits().tripTicks()) {
            return giveUp(in, "out of time");
        }
        return switch (st) {
            case BLOCKS -> blocks(in);
            case PORTAL -> portal(in);
            case WALK -> walk(in);
            case RECOVER -> recover(in);
            default -> over();
        };
    }

    private static Step blocks(Inputs in) {
        if (in.dim() != Dimension.OVERWORLD) {
            return giveUp(in, "not in the overworld to get blocks");
        }
        // short of the count after a minute is still worth going with: the blocks only make the walk safer
        if (in.buildBlocks() >= in.limits().blocksWanted() || in.stageTicks() > in.limits().blocksTicks()) {
            return new Step(Stage.PORTAL, null, false);
        }
        return stay(Stage.BLOCKS);
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
        return in.itemsGained() > 0 ? new Step(null, null, true) : giveUp(in, "nothing left at the pile");
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
