package adris.altoclef.tasks.speedrun.gamer.config;

// owner: nether worker (NETHER, EYES, RETURN)
public class NetherConfig {
    public enum PearlSource {
        // endermen. v1 never barters on its own: 26.3 barter is 2.13% per ingot (10 in 469) and the gold for it rarely exists
        AUTO,
        ENDERMEN,
        BARTER
    }

    public PearlSource pearlSource = PearlSource.AUTO;
    // gold ingots per pearl we still need before bartering is worth the time
    public int barterGoldPerPearl = 15;
    // chunks between the waypoints of the 3x3 sweep inside one 27 chunk structure cell
    public int sweepSpacingChunks = 9;
    // stay this far from a bastion we only wanted to see once (blocks)
    public int bastionAvoidRadius = 48;
    public double rodsBudgetMinutes = 12;
    // no rod after this long next to the spawner: try the next fortress cell
    public double spawnerCampMinutes = 6;
    // no rod after this long with a spawner picked, camping or busy with its blazes: same thing, next fortress
    public double spawnerDryMinutes = 8;

    // never sweep more than this many structure cells in one attempt (about 2.5 cells per fortress, so 12 is generous)
    public int maxCells = 12;
    // a waypoint that is not reached in this long is skipped (lava lake in the way, bad terrain, whatever)
    public double waypointSeconds = 90;
    // close enough to a waypoint (blocks): the view distance does the rest
    public int waypointArriveBlocks = 24;
    // a waypoint we have not got waypointNoProgressBlocks closer to in this long (walking time, not the clock on the wall)
    // is dropped for the rest of the sweep. 0 = off, only the waypointSeconds backstop is left
    public double waypointNoProgressSeconds = 20;
    public int waypointNoProgressBlocks = 8;
    // a waypoint with no floor under it (lava sea) is moved to the nearest place that has one inside this many blocks
    public int waypointShiftBlocks = 12;
    // total time we are willing to spend on the barter top-up
    public double barterMinutes = 8;
    // endermen wandering in the nether with no warped forest known: give up after this long without a pearl
    public double huntWanderMinutes = 10;
    // the engine budget fires at nether minutes, leave a little earlier than that when deciding "budget over"
    // so the floor rule wins the race against the timeout
    public double budgetGraceSeconds = 20;
    // two ghast hits inside this window make us run for cover
    public double ghastWindowSeconds = 20;
}
