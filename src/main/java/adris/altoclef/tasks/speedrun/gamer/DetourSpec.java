package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.DetourRules.Limits;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Step;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import baritone.api.utils.Dimension;

import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

// what is different between one detour and the next: the words, the numbers, and the bits of maths only one resource has.
// pure, no blocks or items in here (ResourceDetour maps a Resource to those), so the tests can poke it without a bootstrap
public final class DetourSpec {
    public enum Resource { COAL, GRAVEL }

    // ---- coal: the coal the plan still burns. a coal is 8 smelts, and a couple more than the sum so a stray smelt does not send
    // us out. whatever the sum says, a detour never takes us past the ceiling, a full run's iron and meat is about 10
    public static final int SMELTS_PER_COAL = 8;
    public static final int NEED_MARGIN = 2;
    public static final int NEED_CEILING = 16;

    // ---- gravel: two minutes between gravel detours. the 5 s coal rest would have us dig every patch we walk past, and one
    // flint is all we want
    public static final long GRAVEL_COOLDOWN_TICKS = 2 * 60 * 20;
    // breaking the bottom of a column brings the rest down the hole. two of them fill our feet and then our head, so a column
    // of more than this straight over us is a block we do not pick
    public static final int TALL_COLUMN = 2;

    public static final DetourSpec COAL = new DetourSpec(Resource.COAL, "coal", "coal", "Mining coal since we found it here",
            "Mining coal while it is close", "mining coal", DetourRules.COOLDOWN_TICKS,
            c -> c.coalSideBudget, c -> c.coalSideCap, c -> c.coalSideSeconds, c -> 0);
    // the held cap is 1: one flint is the flint and steel, and the need is 0 or 1 anyway
    public static final DetourSpec GRAVEL = new DetourSpec(Resource.GRAVEL, "gravel", "flint", "Getting flint from this gravel",
            "Getting flint from gravel", "digging gravel for flint", GRAVEL_COOLDOWN_TICKS,
            c -> c.gravelSideBudget, c -> 1, c -> c.gravelSideSeconds, c -> c.gravelSideBlocks);

    public final Resource resource;
    // for the log lines ("coal side job: ...") and the activity words
    public final String name;
    // the kit need that means the kit task is already on it (DetourRules.blocked), see kitIsOnIt
    public final String headNeed;
    // once per detour in chat (DetourRules.announce)
    public final String chat;
    public final String hud;
    // what ctx.progress says when the bag gets a piece
    public final String progress;
    public final long cooldownTicks;
    private final ToDoubleFunction<OverworldConfig> budget;
    private final ToIntFunction<OverworldConfig> heldCap;
    private final ToDoubleFunction<OverworldConfig> seconds;
    private final ToIntFunction<OverworldConfig> mineCap;

    private DetourSpec(Resource resource, String name, String headNeed, String chat, String hud, String progress, long cooldownTicks,
                       ToDoubleFunction<OverworldConfig> budget, ToIntFunction<OverworldConfig> heldCap,
                       ToDoubleFunction<OverworldConfig> seconds, ToIntFunction<OverworldConfig> mineCap) {
        this.resource = resource;
        this.name = name;
        this.headNeed = headNeed;
        this.chat = chat;
        this.hud = hud;
        this.progress = progress;
        this.cooldownTicks = cooldownTicks;
        this.budget = budget;
        this.heldCap = heldCap;
        this.seconds = seconds;
        this.mineCap = mineCap;
    }

    // the kit's head need is this resource already. for gravel the flint and steel counts too: its craft fetches the flint
    // itself (CollectFlintTask), and a detour next to it would just race it for the same gravel
    public boolean kitIsOnIt(String need) {
        if (resource == Resource.GRAVEL && "flint_and_steel".equals(need)) {
            return true;
        }
        return headNeed.equals(need);
    }

    // the start reach, WalkCost
    public double budget(OverworldConfig cfg) {
        return budget.applyAsDouble(cfg);
    }

    public double seconds(OverworldConfig cfg) {
        return seconds.applyAsDouble(cfg);
    }

    public Limits limits(OverworldConfig cfg) {
        return new Limits(heldCap.applyAsInt(cfg), Math.round(seconds(cfg) * 20), mineCap.applyAsInt(cfg));
    }

    // how a detour ended, in words, for the activity line
    public String ended(Step why) {
        return switch (why) {
            case DONE -> resource == Resource.GRAVEL ? "got flint, or the patch is dug" : "cluster mined";
            case CAPPED -> "dug its share, no flint";
            case TIMEOUT -> "out of time";
            case STRAYED -> "wandered off the cluster";
            default -> "a never rule said stop (furnace due, food, a load in flight)";
        };
    }

    // ---- coal

    // coal enough for the rest of the plan: every iron ingot still owed that is not in the bag or in one of our furnaces, plus
    // the raw meat in the bag, is a smelt. the wood we would burn anyway (above the reserve) covers some, the rest is coal, a
    // coal is 8. plus the margin, never past the ceiling. a flat cap has no idea how much smelting is left, so it went mining
    // for coal nothing was ever going to burn
    public static int coalNeed(int ingotsOwed, int ingotsHeld, int ingotsPending, int rawMeat, int woodSmelts) {
        int smelts = Math.max(0, ingotsOwed - ingotsHeld - ingotsPending) + Math.max(0, rawMeat);
        int left = Math.max(0, smelts - Math.max(0, woodSmelts));
        return Math.min(NEED_CEILING, (left + SMELTS_PER_COAL - 1) / SMELTS_PER_COAL + NEED_MARGIN);
    }

    // ---- gravel

    // one flint, until the flint and steel exists (or a fire charge does the lighting). the portal phase asks for flint and steel
    // whatever the kit list says, so the kit list is not asked
    public static int flintNeed(int flint, boolean lighter) {
        return lighter || flint >= 1 ? 0 : 1;
    }

    // where gravel is worth a stop: any overworld phase from GATHER on, right at spawn included, and the nether (soul sand valleys,
    // the gravel by the lava seas) while flint is still owed. never the end, never a run that is over. the need rule is what
    // makes the late phases quiet, the flint and steel is long made by then
    public static boolean gravelPhase(GamerPhase phase, Dimension dim) {
        if (phase == null || phase.isTerminal() || phase.ordinal() < GamerPhase.GATHER.ordinal()) {
            return false;
        }
        return dim == Dimension.OVERWORLD || dim == Dimension.NETHER;
    }

    // PORTAL asks gravel only in the overworld, and only while the gate is still being packed. once the gate is done the pool,
    // the cast or the obsidian juggle their own buckets and blocks, a detour in the middle of that would fight them
    public static boolean portalMayDetour(boolean overworld, boolean gateDone) {
        return overworld && !gateDone;
    }

    // NETHER asks gravel only in the nether (the walk to the portal is the home walk's), and never while the phase is on something
    // that can not wait: a blaze spawner we picked, a piglin trade going, a pearl hunt going (an enderman we walk at, angry ones
    // included). a run from a ghast comes before this is asked and ends the detour itself (NetherPhase.tick), a fight is the combat
    // chain's, it just stops asking us and the gap rule ends it. the warped forest search is a walk, gravel on the way is fair
    public static boolean netherMayDetour(boolean nether, boolean atSpawner, boolean trading, boolean hunting) {
        return nether && !atSpawner && !trading && !hunting;
    }

    // gravel needs no pick, only a hand. a shovel is 3 to 6x faster and is the only tool that counts as right for gravel, so the
    // tool picker (DestroyBlockTask's best tool) takes it whenever one is in the bag and leaves the pick and the sword alone.
    // this is just the word for the log line
    public static String gravelTool(boolean shovel) {
        return shovel ? "a shovel" : "bare hands";
    }

    // the block at (x, y, z) with `stack` loose blocks in its column (itself plus what is stacked on it) is straight over the
    // player's head and tall enough to bury it. a column next to us falls into its own cell, that is the way to dig it
    public static boolean underTallColumn(int feetX, int feetY, int feetZ, int x, int y, int z, int stack) {
        return x == feetX && z == feetZ && y >= feetY + 2 && stack > TALL_COLUMN;
    }
}
