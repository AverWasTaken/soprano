package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook.Kind;
import adris.altoclef.util.helpers.WalkCost;

// every answer the gamer has about the tables, furnaces and smokers it put down, worked out once per tick. "do we have a usable
// crafting table / furnace / smoker" used to be asked in about forty places and answered four ways, so the places agreed on the
// block and disagreed on the verdict (see WoodBudget for the same cure on wood). this is the one home: MinecraftFacts builds it in
// refresh() (the world half asks the game, this class only does arithmetic over the registry), GamerContext.stations() hands it out.
// it is immutable. the only thing its construction writes is the StationLatches it is handed: the hysteresis lives here and nowhere else.
//
// what is NOT in it on purpose: the per-bench decisions (WorkbenchRules.decide moves the bench's clocks and reads the phase's plan, so
// it stays in Workbenches.run), idle / in use (they ask the task tree), and the container tasks' own choice (StationChoice is alto's,
// it runs on candidates the task builds from the tracker). phaseMayEnd is here as `mayEnd` (same WorkbenchRules function), but no
// reader reads it: the isDone predicates and PortalPhase ask AFTER the tick's pickups moved the registry (and have no ctx), a value
// built in refresh() would answer one tick late, so they keep calling Workbenches.phaseMayEnd. one implementation, two call sites
public final class StationVerdict {
    // nobody is placed anywhere, nothing is held
    public static final StationVerdict NONE = fromFlags(false, false, false);

    // what the game says about the stations, as questions. MinecraftFacts answers them from the world, the equivalence test from a table
    interface World {
        // the block is still there (an unloaded chunk keeps its station)
        boolean stands(Kind kind, RunState.Pos pos);

        // the bag holds one of the item
        boolean inBag(Kind kind);

        // DoStuffInContainerTask.bagCanMake: the bag has what one more takes
        boolean canMake(Kind kind);

        // DoStuffInContainerTask.walkBackUsable for this bench
        boolean walkUsable(Kind kind, Bench b);
    }

    // one kind of station
    public record Station(
            Kind kind,
            // one of this kind is registered in this dimension at all, so a miss in heldNear means "out of range" and not "none"
            boolean present,
            // what the planner counts: heldNear, or the walk back past it. the flag KitPlanner, CookGate and the wood budget read. a
            // PICKING_UP bench counts (it is on its way to the bag), and a smoker's also says "smoker first" so it only counts the far
            // ones when no furnace is at hand (WorkbenchRules.plannerSmoker). this one and the task's view (standingNear / standingFar)
            // disagree: a bench coming down is held here and not standing there, a smoker in (20, 21] is held or not here by which side
            // we came from and plain standing there. known wrong, pinned in StationContradictionsTest, not fixed here
            boolean heldForPlanner,
            // inside the latch radius (WorkbenchRules.returnRadius / bandRadius) before any walk back, stands or is coming down
            boolean heldNear,
            // past the latch, the container task would still walk back to one (Workbenches.plannerWalksBack). false when heldNear said
            // yes: it is not worked out then
            boolean walksBack,
            // what StationHook.standingNear answers: one of ours standing (not coming down) within NEAR of us, no latch
            boolean standingNear,
            // the same out to the forget line, before the task's usable test
            boolean standingFar,
            // some bench of this kind is coming down and somebody still drives it, so no container task may place or craft another
            boolean pickingUp,
            // the nearest standing bench of this kind in this dimension, wherever it is: its block and the straight line to it, NaN
            // distance and null block for none
            RunState.Pos nearest,
            double nearestDistance,
            // one line for the log: why heldForPlanner says what it says
            String reason) {
    }

    private final Station table;
    private final Station furnace;
    private final Station smoker;
    // WorkbenchRules.phaseMayEnd over the registry as of the build (no sync, no log): nothing of ours is standing or coming down in this
    // dimension
    private final boolean mayEnd;

    private StationVerdict(Station table, Station furnace, Station smoker, boolean mayEnd) {
        this.table = table;
        this.furnace = furnace;
        this.smoker = smoker;
        this.mayEnd = mayEnd;
    }

    public Station table() {
        return table;
    }

    public Station furnace() {
        return furnace;
    }

    public Station smoker() {
        return smoker;
    }

    public Station of(Kind kind) {
        return switch (kind) {
            case TABLE -> table;
            case FURNACE -> furnace;
            case SMOKER -> smoker;
        };
    }

    public boolean mayEnd() {
        return mayEnd;
    }

    // only the planner's flags known (a test's facts, a state that is not loaded yet): every other field says "nothing registered"
    public static StationVerdict fromFlags(boolean table, boolean furnace, boolean smoker) {
        return new StationVerdict(bare(Kind.TABLE, table), bare(Kind.FURNACE, furnace), bare(Kind.SMOKER, smoker), true);
    }

    private static Station bare(Kind kind, boolean held) {
        return new Station(kind, false, held, held, false, false, false, false, null, Double.NaN, held ? "flag" : "none");
    }

    // the verdict for one look from (px, py, pz). moves `latches`: this is the only place they are written
    static StationVerdict of(RunState state, String dimension, double px, double py, double pz, long now, World world, StationLatches latches) {
        // held when one of ours is within NEAR (a straight line, height counts), the same test the container tasks use, with a latch on
        // top so the plan does not flip at the line (the cobble floor, the cook gate, a log trip against the cobble). the table's is big
        // (once out of NEAR it only counts again well inside it, its planks swing the plan). furnace and smoker only get a block, and
        // only inward: the smoker first rule reads this flag and the container tasks read the plain line, so a smoker at 20 blocks is a
        // smoker whichever way we got there, and the flag is never true where the task would put down a second one
        // (table first, then furnace, then smoker: each latch is read before it is written, in the order the flags always ran in)
        boolean tableNear = heldNear(state, Kind.TABLE, dimension, px, py, pz, WorkbenchRules.returnRadius(latches.tableFar), world);
        latches.tableFar = !tableNear && Workbenches.any(state, Kind.TABLE, dimension);
        // past the latch a table of ours still counts as far as the craft would walk back to it (Workbenches.plannerWalksBack),
        // the latch only remembers its own answer like the furnace's
        // and once it counts that way it keeps counting HOLD past the line, the same slack the task gives the one it is walking to
        // (StationChoice.nearest), or the plan asks for table planks while the craft is still on its way to the table
        boolean tableBack = !tableNear && walksBack(state, Kind.TABLE, dimension, px, py, pz, latches.tableBack ? StationChoice.HOLD : 0, world);
        latches.tableBack = tableBack;
        // past the band a furnace or smoker of ours still counts when the smelt would walk back to it (plannerWalksBack). the latch
        // only ever remembers the band's own answer, the walk back has no line to dither over
        boolean furnaceNear = heldNear(state, Kind.FURNACE, dimension, px, py, pz, WorkbenchRules.bandRadius(latches.furnaceHeld), world);
        latches.furnaceHeld = Workbenches.any(state, Kind.FURNACE, dimension) ? Boolean.valueOf(furnaceNear) : null;
        boolean furnaceBack = !furnaceNear && walksBack(state, Kind.FURNACE, dimension, px, py, pz, world);
        boolean smokerNear = heldNear(state, Kind.SMOKER, dimension, px, py, pz, WorkbenchRules.bandRadius(latches.smokerHeld), world);
        latches.smokerHeld = Workbenches.any(state, Kind.SMOKER, dimension) ? Boolean.valueOf(smokerNear) : null;
        boolean furnaceAtHand = furnaceNear || world.inBag(Kind.FURNACE);
        boolean smokerBack = !smokerNear && !furnaceAtHand && walksBack(state, Kind.SMOKER, dimension, px, py, pz, world);
        boolean smokerHeld = WorkbenchRules.plannerSmoker(smokerNear, furnaceAtHand, smokerBack);
        return new StationVerdict(
                station(state, Kind.TABLE, dimension, px, py, pz, now, tableNear || tableBack, tableNear, tableBack, tableNear ? "near" : tableBack ? "walk back" : "none"),
                station(state, Kind.FURNACE, dimension, px, py, pz, now, furnaceNear || furnaceBack, furnaceNear, furnaceBack, furnaceNear ? "near" : furnaceBack ? "walk back" : "none"),
                station(state, Kind.SMOKER, dimension, px, py, pz, now, smokerHeld, smokerNear, smokerBack,
                        smokerNear ? "near" : smokerHeld ? "walk back" : "none"),
                WorkbenchRules.phaseMayEnd(state.benches, dimension, b -> FurnaceJobs.isBusy(state, b.pos, b.dimension)));
    }

    private static Station station(RunState state, Kind kind, String dimension, double px, double py, double pz, long now, boolean held,
                                   boolean near, boolean back, String reason) {
        Bench nearest = Workbenches.nearestStanding(state, kind, dimension, px, py, pz, Double.MAX_VALUE);
        return new Station(kind, Workbenches.any(state, kind, dimension), held, near, back,
                Workbenches.nearestStanding(state, kind, dimension, px, py, pz, WorkbenchRules.NEAR) != null,
                Workbenches.nearestStanding(state, kind, dimension, px, py, pz, WorkbenchRules.FORGET_DISTANCE) != null,
                WorkbenchRules.placeVetoed(state.benches, kind, now), nearest == null ? null : nearest.pos,
                nearest == null ? Double.NaN : WalkCost.stationDistance(nearest.pos.x, nearest.pos.y, nearest.pos.z, px, py, pz), reason);
    }

    // one of the stations this run placed (or is taking back) that is still there and within `radius` of us. the planner counts it
    // as held about where crafting and smelting will walk to it, so the plan and the container tasks can't disagree about whether
    // a second one is needed. an unloaded chunk keeps its station
    private static boolean heldNear(RunState state, Kind kind, String dimension, double px, double py, double pz, double radius, World world) {
        return Workbenches.heldNear(state, kind, dimension, px, py, pz, radius, pos -> world.stands(kind, pos));
    }

    // the walk back half asks DoStuffInContainerTask's own two questions (can the bag make one, would it take that far one), so the
    // planner and StationChoice agree on the block and on the bag
    private static boolean walksBack(RunState state, Kind kind, String dimension, double px, double py, double pz, World world) {
        return walksBack(state, kind, dimension, px, py, pz, 0, world);
    }

    private static boolean walksBack(RunState state, Kind kind, String dimension, double px, double py, double pz, double hold, World world) {
        boolean inBag = world.inBag(kind);
        boolean canMake = !inBag && world.canMake(kind);
        // in the bag is no walk back (KitPlanner counts a table in the bag itself, the furnace's readers ask the bag too)
        return Workbenches.plannerWalksBack(state, kind, dimension, px, py, pz, inBag, canMake, hold, b -> world.walkUsable(kind, b));
    }
}
