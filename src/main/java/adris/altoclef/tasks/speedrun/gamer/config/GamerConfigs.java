package adris.altoclef.tasks.speedrun.gamer.config;

import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.KitItem;
import adris.altoclef.util.helpers.ConfigHelper;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// the one place the gamer reads configs/beat_minecraft.json from. #altoreload re-reads it through ConfigHelper, and the
// file is versioned: the old BeatMinecraftConfig shape has no "version", so it gets replaced by the new defaults instead of
// leaking its numbers (minimumEyes 12, requiredBeds 10, minFoodUnits 180...) into them. a version from OLDEST_KEPT up
// keeps its numbers and only loses its kit lists (migrate)
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
        ConfigHelper.loadVersionedConfig(PATH, GamerConfig.VERSION, GamerConfig.OLDEST_KEPT, GamerConfigs::migrate,
                GamerConfig::new, GamerConfig.class, c -> current = sanitize(c));
        return current;
    }

    // an older file keeps its numbers but not its kit lists: those are the strategy, not tuning, and a saved list replaces
    // the default outright (the bot walked into the nether with no ladders because of exactly that). walks the fields
    // instead of naming them so a kit list somebody adds later is covered without touching this
    static GamerConfig migrate(GamerConfig c) {
        int from = c.version;
        resetKitLists(c, new GamerConfig());
        c.version = GamerConfig.VERSION;
        Debug.logMessage("gamer config v" + from + " -> v" + GamerConfig.VERSION + ": kit lists reset to defaults");
        return c;
    }

    private static void resetKitLists(Object loaded, Object defaults) {
        for (Field f : fields(loaded.getClass())) {
            try {
                if (isKitList(f)) {
                    f.set(loaded, f.get(defaults));
                } else if (isSection(f)) {
                    Object l = f.get(loaded);
                    Object d = f.get(defaults);
                    // a null section gets replaced whole by sanitize
                    if (l != null && d != null) {
                        resetKitLists(l, d);
                    }
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // every List<KitItem> in the config tree by dotted path ("overworld.ironKit"). what the tests pin the defaults with
    public static Map<String, List<KitItem>> kitLists(GamerConfig c) {
        Map<String, List<KitItem>> out = new TreeMap<>();
        collectKitLists(c, "", out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectKitLists(Object section, String prefix, Map<String, List<KitItem>> out) {
        for (Field f : fields(section.getClass())) {
            try {
                if (isKitList(f)) {
                    out.put(prefix + f.getName(), (List<KitItem>) f.get(section));
                } else if (isSection(f) && f.get(section) != null) {
                    collectKitLists(f.get(section), prefix + f.getName() + ".", out);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field f : type.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                out.add(f);
            }
        }
        return out;
    }

    private static boolean isKitList(Field f) {
        return f.getGenericType() instanceof ParameterizedType p && p.getRawType() == List.class
                && p.getActualTypeArguments().length == 1 && p.getActualTypeArguments()[0] == KitItem.class;
    }

    // a nested config object (budgets, overworld...), not a number, a list or an enum
    private static boolean isSection(Field f) {
        Class<?> t = f.getType();
        return !t.isEnum() && t.getPackageName().equals(GamerConfig.class.getPackageName());
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
