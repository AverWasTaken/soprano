package adris.altoclef.tasks.speedrun.gamer.config;

// owner: nether worker (NETHER, EYES, RETURN)
public class NetherConfig {
    public enum PearlSource {
        // endermen first, barter only as a top-up when the gold is already in hand (1.21.4 barter is 2.18% per ingot)
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

    // never sweep more than this many structure cells in one attempt (about 2.5 cells per fortress, so 12 is generous)
    public int maxCells = 12;
    // a waypoint that is not reached in this long is skipped (lava lake in the way, bad terrain, whatever)
    public double waypointSeconds = 90;
    // close enough to a waypoint (blocks): the view distance does the rest
    public int waypointArriveBlocks = 24;
    // total time we are willing to spend on the barter top-up
    public double barterMinutes = 8;
    // endermen wandering in the nether with no warped forest known: give up after this long without a pearl
    public double huntWanderMinutes = 10;
    // how long the way back to the portal may take before the return phase calls it failed
    public double returnGiveUpMinutes = 3;
    // the engine budget fires at nether minutes, leave a little earlier than that when deciding "budget over"
    // so the floor rule wins the race against the timeout
    public double budgetGraceSeconds = 20;
    // two ghast hits inside this window make us run for cover
    public double ghastWindowSeconds = 20;
}
