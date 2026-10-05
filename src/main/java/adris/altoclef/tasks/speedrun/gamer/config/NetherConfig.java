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
}
