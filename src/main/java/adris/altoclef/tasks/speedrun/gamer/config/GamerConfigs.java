package adris.altoclef.tasks.speedrun.gamer.config;

import adris.altoclef.util.helpers.ConfigHelper;

// the one place the gamer reads configs/beat_minecraft.json from. #altoreload re-reads it through ConfigHelper, and the
// file is versioned: the old BeatMinecraftConfig shape has no "version", so it gets replaced by the new defaults instead of
// leaking its numbers (minimumEyes 12, requiredBeds 10, minFoodUnits 180...) into them
public final class GamerConfigs {
    public static final String PATH = "configs/beat_minecraft.json";

    private static volatile GamerConfig current;

    private GamerConfigs() {
    }

    public static GamerConfig get() {
        GamerConfig c = current;
        return c != null ? c : load();
    }

    // reads the file now (creating it with the defaults if it is missing), and hooks #altoreload up to it
    public static synchronized GamerConfig load() {
        ConfigHelper.loadVersionedConfig(PATH, GamerConfig.VERSION, GamerConfig::new, GamerConfig.class, c -> current = sanitize(c));
        return current;
    }

    // "nether": null in a hand edited file would be an NPE hours into a run. a missing key is fine, gson keeps the default
    static GamerConfig sanitize(GamerConfig c) {
        if (c.budgets == null) {
            c.budgets = new Budgets();
        }
        if (c.death == null) {
            c.death = new GamerConfig.Death();
        }
        if (c.overworld == null) {
            c.overworld = new OverworldConfig();
        }
        if (c.nether == null) {
            c.nether = new NetherConfig();
        }
        if (c.stronghold == null) {
            c.stronghold = new StrongholdConfig();
        }
        if (c.end == null) {
            c.end = new EndConfig();
        }
        clamp(c);
        return c;
    }

    // numbers that would turn a phase into an instant failure or switch its watchdog off: back to a sane value. only the
    // ones where a typo hurts (zero attempts = stuck on the first timeout, zero minutes = no budget at all)
    private static void clamp(GamerConfig c) {
        GamerConfig d = new GamerConfig();
        c.maxAttempts = Math.max(1, c.maxAttempts);
        c.targetEyes = Math.max(1, c.targetEyes);
        c.floorEyes = Math.max(1, Math.min(c.floorEyes, c.targetEyes));
        c.death.maxPerPhase = Math.max(1, c.death.maxPerPhase);
        c.death.maxTotal = Math.max(1, c.death.maxTotal);
        c.death.recoverBlocks = Math.max(0, c.death.recoverBlocks);
        c.death.recoverSeconds = Math.max(0, c.death.recoverSeconds);
        c.end.attempts = Math.max(1, c.end.attempts);
        c.end.beds = Math.max(0, c.end.beds);
        c.stronghold.maxThrows = Math.max(1, c.stronghold.maxThrows);
        c.stronghold.maxEmptyThrows = Math.max(1, c.stronghold.maxEmptyThrows);
        c.stronghold.spiralRadiusChunks = Math.max(1, c.stronghold.spiralRadiusChunks);
        c.stronghold.sigmaDeg = positive(c.stronghold.sigmaDeg, d.stronghold.sigmaDeg);
        c.stronghold.snapRadius = positive(c.stronghold.snapRadius, d.stronghold.snapRadius);
        c.stronghold.perChunkSeconds = positive(c.stronghold.perChunkSeconds, d.stronghold.perChunkSeconds);
        c.nether.maxCells = Math.max(1, c.nether.maxCells);
        c.nether.sweepSpacingChunks = Math.max(1, c.nether.sweepSpacingChunks);
        c.nether.waypointSeconds = positive(c.nether.waypointSeconds, d.nether.waypointSeconds);
        clampBudgets(c.budgets, d.budgets);
    }

    private static void clampBudgets(Budgets b, Budgets d) {
        b.gather = positive(b.gather, d.gather);
        b.iron = positive(b.iron, d.iron);
        b.portal = positive(b.portal, d.portal);
        b.nether = positive(b.nether, d.nether);
        b.eyes = positive(b.eyes, d.eyes);
        b.returnHome = positive(b.returnHome, d.returnHome);
        b.locate = positive(b.locate, d.locate);
        b.room = positive(b.room, d.room);
        b.open = positive(b.open, d.open);
        b.endPrep = positive(b.endPrep, d.endPrep);
        b.dragon = positive(b.dragon, d.dragon);
    }

    // zero, negative and NaN all mean "typo", infinity would be a budget that never ends
    private static double positive(double value, double fallback) {
        return value > 0 && !Double.isInfinite(value) ? value : fallback;
    }
}
