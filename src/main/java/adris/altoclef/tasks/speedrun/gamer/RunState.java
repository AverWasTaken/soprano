package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// everything a relog must not forget. plain fields and no minecraft types on purpose: gson reads it, tests build it
// without a game, and a human can open the file. one file per world, see RunStateStore
public class RunState {
    public static final int SCHEMA = 1;

    public int schema = SCHEMA;
    // "<level name>|<server ip>", a stale file from another world with the same folder name gets ignored
    public String fingerprint = "";
    public long startedEpochMs;
    public GamerPhase phase = GamerPhase.GATHER;
    // attempts per phase name, so a retry survives a relog
    public Map<String, Integer> phaseAttempts = new HashMap<>();
    public long phaseEnteredGameTime;
    public int netherRevisits;
    public String stuckReason = "";
    // engine (w1). stuck keeps `phase` where it was so #gamer resumes there, finished = the run is over for good
    public boolean stuck;
    public boolean finished;
    // client ticks the gamer actually ran, for #gamer status
    public long runTicks;
    // reset on every phase change and on a manual resume, three of these in one phase is stuck
    public int deathsThisPhase;
    // "FROM>TO" -> how often the engine regressed that way, so a regress loop ends in STUCK instead of resetting every clock forever
    public Map<String, Integer> regressCounts = new HashMap<>();

    public List<Death> deaths = new ArrayList<>();

    // the pair we built or used. one pair is enough, bots do not hop between portals
    public Pos overworldPortal;
    public Pos netherPortal;
    // CAST or OBSIDIAN, decided by the portal phase
    public String portalMethod = "CAST";

    // nether: 27 chunk structure cells as "cx,cz"
    public Set<String> visitedCells = new LinkedHashSet<>();
    public Set<String> bastionCells = new LinkedHashSet<>();
    public Set<String> fortressCells = new LinkedHashSet<>();
    public List<Pos> fortress = new ArrayList<>();
    public List<Pos> bastion = new ArrayList<>();
    public Pos spawner;
    public Pos warpedForest;

    // stronghold
    public List<Ray> strongholdRays = new ArrayList<>();
    public Pos strongholdEstimate;
    public double strongholdRadius;
    public Pos strongholdStart;
    public Set<String> roomChunksVisited = new LinkedHashSet<>();
    public int eyeThrows;

    // end portal
    public Pos endPortalCenter;
    public int framesFilled;
    public boolean endPortalOpened;
    public Pos spawnBed;
    public boolean spawnBedSet;

    // gear we left lying in the End (item registry name -> count), replaces BM2's cache so a death there does not re-craft it
    public Map<String, Integer> endDrops = new HashMap<>();
    public boolean dragonDead;

    public static class Pos {
        public int x;
        public int y;
        public int z;

        public Pos() {
        }

        public Pos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pos p && p.x == x && p.y == y && p.z == z;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * x + y) + z;
        }

        @Override
        public String toString() {
            return x + ", " + y + ", " + z;
        }
    }

    // one furnace cooking something for us. plain strings (registry paths) so gson and the tests need no minecraft
    public static class FurnaceJob {
        public Pos pos;
        // "OVERWORLD", "NETHER"... a furnace is only ever collected from in the dimension it stands in
        public String dimension = "OVERWORLD";
        // block registry path: furnace, blast_furnace, smoker
        public String kind = "furnace";
        public String input = "";
        // what was in the input slot when we left, so also what we expect to come back as output
        public int count;
        public String output = "";
        public long startTick;
        // a hint, never a promise: the furnace contents on return are what counts
        public long doneTick;
        // nutrition of ONE finished item, 0 for anything that is not food. the food planner counts count * unitsEach as food
        // on the way. an old save has no key and reads 0, which is just "not food"
        public int unitsEach;
        // doneTick came from the furnace's own slots on a visit, not from our guess when we loaded it (FurnaceJobs.anyDue). an
        // old save has no key and reads false, which is just "a guess"
        public boolean visited;

        public FurnaceJob() {
        }

        public FurnaceJob(Pos pos, String dimension, String kind, String input, int count, String output, long startTick, long doneTick) {
            this.pos = pos;
            this.dimension = dimension;
            this.kind = kind;
            this.input = input;
            this.count = count;
            this.output = output;
            this.startTick = startTick;
            this.doneTick = doneTick;
        }
    }

    public static class Death {
        public String dimension;
        public int x;
        public int y;
        public int z;
        public long gameTime;
        public String phase;
    }

    // eye throw bearing: origin and unit direction in XZ, dived = the eye sank into the ground (we are within ~12 blocks of the target)
    public static class Ray {
        public double ox;
        public double oz;
        public double dx;
        public double dz;
        public boolean dived;
    }

    // w5 (stronghold), additive
    // how many of the 12 frames are known (seen, or inferred from the seen ones: the ring is rigid)
    public int framesSeen;
    // spiral searches that came up empty. 1 = one re-estimate was spent
    public int roomRetries;
    // where the last room search started, so the re-estimate steps sideways before its first throw. null = no re-estimate pending
    public Pos relocateFrom;
    // game time the open phase first noticed it could not fill the rest of the frames. 0 = fine
    public long openNoEyesSince;
    // eyes held right before the last throw, 0 = nothing out there to collect. an eye in the air or on the floor is owed
    // to us until the count is back to this (or it shattered), also across an interrupt or a relog
    public int eyeBaseline;

    // w4 (nether), additive
    // the spawner camping took too long: the rod hunt moves on to the next fortress
    public boolean netherRodsGaveUp;
    // fortresses we already gave up on (their blaze spawner never paid out)
    public List<Pos> fortressExhausted = new ArrayList<>();

    // w6
    // the extra bed near the portal took too long (or could not be placed): go to the End without a spawn bed
    public boolean spawnBedGaveUp;

    // crafting tables this run placed (overworld), the only ones the table pickup may take back. see OwnTables
    public List<Pos> placedTables = new ArrayList<>();
    // same for furnaces (plain ones only, a blast furnace of ours goes in placedJobBlocks below)
    public List<Pos> placedFurnaces = new ArrayList<>();
    // smokers we crafted for the food. a list of their own so an old save without the key just loads empty
    public List<Pos> placedSmokers = new ArrayList<>();

    // when a station was last open or placed, which kit need was running then, and when we last took one back. transient
    // on purpose: they only gate a debounce and a backstop, a relog starting them over is fine. see OwnTables
    public static final class StationUse {
        public long lastUseTick = OwnTables.NEVER;
        // split from the use stamp: a boundary ignores the last use, but a block that went down a moment ago still waits
        public long lastPlaceTick = OwnTables.NEVER;
        public long lastRecoveredTick = OwnTables.NEVER;
        // catalogue name of the need, null = we do not know (relog, or placed outside a prep phase)
        public String useNeed;
    }

    public transient StationUse tableUse = new StationUse();
    public transient StationUse furnaceUse = new StationUse();
    public transient StationUse smokerUse = new StationUse();
    // the kit need the prep phase is running right now, so the placement hook in GamerTask can say which need used a
    // station. null outside GATHER / IRON
    public transient String currentNeed;
    // game tick the early iron batch (EarlyIronPick) started loading, -1 = no load in flight. transient: a relog starting
    // it over is fine, the planner just goes back to looking at the bag
    public transient long earlyLoadTick = -1;

    // village blacksmith chests we already opened (or started to), and the game ticks spent in them. the budget in
    // VillageChests reads both, so a relog does not hand out a fresh one
    public List<Pos> villageChestsTried = new ArrayList<>();
    public long villageLootTicks;
    // grindstones, smithing tables and blast furnaces this run placed. a village's own job blocks vouch for its chests,
    // ours must not (we craft blast furnaces). same guess as placedTables, see GamerTask.watchPlacements
    public List<Pos> placedJobBlocks = new ArrayList<>();

    // village beds we already went for (either half, both count) and the game ticks spent on them. BedRules reads the
    // ticks for the budget, so a relog does not hand out a fresh one
    public List<Pos> villageBedsTried = new ArrayList<>();
    public long villageBedTicks;

    // furnaces we loaded and walked away from, see FurnaceJobs. the furnace's own contents are the truth when we get back,
    // this is the memory that says there is something to go back for (and survives a relog)
    public List<FurnaceJob> furnaceJobs = new ArrayList<>();

    public int attemptsOf(GamerPhase p) {
        return phaseAttempts.getOrDefault(p.name(), 0);
    }
}
