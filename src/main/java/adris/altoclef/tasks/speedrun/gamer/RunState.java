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

    // w4 (nether), additive
    // the spawner camping took too long: the rod hunt moves on to the next fortress
    public boolean netherRodsGaveUp;
    // fortresses we already gave up on (their blaze spawner never paid out)
    public List<Pos> fortressExhausted = new ArrayList<>();

    // w6
    // the extra bed near the portal took too long (or could not be placed): go to the End without a spawn bed
    public boolean spawnBedGaveUp;

    public int attemptsOf(GamerPhase p) {
        return phaseAttempts.getOrDefault(p.name(), 0);
    }
}
