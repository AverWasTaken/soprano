package adris.altoclef.tasks.speedrun.gamer.config;

// configs/beat_minecraft.json. "version" is checked before gson sees the file: any other value (including the old
// BeatMinecraftConfig shape, which has none) means defaults, saved over it, one log line. nested objects are one class
// per phase group so the workers do not step on each other.
// an older file from OLDEST_KEPT up is not thrown away: its numbers stay and the kit lists (every List<KitItem> in here)
// go back to the defaults, see GamerConfigs.migrate
public class GamerConfig {
    // bump VERSION when you change a default kit list (or add one). a saved file replaces the default list outright, so
    // without the bump every existing run keeps the old kit forever (that is how the bot went to the nether with no
    // ladders). GamerConfigsTest has a hash of the defaults that fails until you do
    public static final int VERSION = 4;
    // files from here up to VERSION get the migration instead of the defaults. raise it if a bump ever changes the shape
    // in a way the migration can not paper over
    public static final int OLDEST_KEPT = 2;

    public int version = VERSION;
    // a failing phase gets this many tries before it is skipped or the run goes STUCK
    public int maxAttempts = 2;
    // eyes of ender: craft this many in the nether, the portal needs 12 minus the ~1.2 that are pre filled,
    // and every throw loses an eye one time in five
    public int targetEyes = 14;
    // leave the nether / start the stronghold with at least this many when the pearl budget ran out
    public int floorEyes = 12;

    public Budgets budgets = new Budgets();
    public Death death = new Death();
    public OverworldConfig overworld = new OverworldConfig();
    public NetherConfig nether = new NetherConfig();
    public StrongholdConfig stronghold = new StrongholdConfig();
    public EndConfig end = new EndConfig();

    public static class Death {
        // go back for our stuff when we died this close in the dimension we respawned in
        public int recoverBlocks = 300;
        // items despawn after 5 minutes, leave margin
        public int recoverSeconds = 240;
        public int maxPerPhase = 3;
        // deaths since the run was (re)started by hand, over every phase together
        public int maxTotal = 12;
    }
}
