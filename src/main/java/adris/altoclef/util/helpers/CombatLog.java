package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Why;
import baritone.api.utils.Dimension;
import java.util.Locale;

// the one `combat:` line a transition gets, as text, so every event can be checked to say what happened and where. the
// dimension goes in square brackets right after the prefix, everything after it reads the same in all three
public final class CombatLog {

    private CombatLog() {
    }

    // null for no transition. target is who the fight is about (the new one for a chain), fighting the name remembered from the
    // fight that just ended (the mob is already gone from the world by then), crowd the "2 zombies, 1 skeleton" list
    public static String line(Dimension dimension, Event event, Why why, boolean cornered, String target, String fighting,
                              String crowd, int crowdCount, int hp, int blocks) {
        String tag = "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] ";
        return switch (event) {
            case NONE -> null;
            case FIGHT_START -> tag + "FIGHT " + target + " (" + (why == Why.HIT ? "hit us" : "in contact") + ", armed, hp " + hp + ")";
            case FIGHT_NEXT -> cornered ? tag + "cornered, next " + target
                    : tag + "target dead, next " + target + " (" + (why == Why.HIT ? "hit us" : "in contact") + ", hp " + hp + ")";
            case RUN_START -> tag + "RUN from " + crowd + " (" + runWhy(why, crowdCount, hp) + ")";
            case FIGHT_TO_RUN -> tag + "FIGHT -> RUN from " + crowd + " (" + runWhy(why, crowdCount, hp) + ")";
            case RUN_TO_FIGHT -> tag + "cornered, fighting " + target;
            case FIGHT_DEAD -> tag + "fight over, " + fighting + " dead";
            case FIGHT_LOST -> tag + "fight over, lost track of " + fighting;
            case FIGHT_STALLED -> tag + "fight over, can't get to " + fighting + ", ignoring it until it hits us again";
            case RUN_CLEAR -> tag + "run over, " + blocks + " blocks, clear";
            case RUN_CAP -> tag + "run over, " + CombatCommit.RUN_CAP / 20 + " s cap, " + blocks + " blocks";
            case RUN_STUCK -> tag + "run over, stuck " + blocks + " blocks from where it started";
        };
    }

    public static String runWhy(Why why, int crowdCount, int hp) {
        return switch (why) {
            case UNARMED -> "unarmed, hp " + hp;
            case CROWD -> "crowd of " + crowdCount + ", hp " + hp;
            case HEAVY -> "something nasty close, hp " + hp;
            case UNREACHABLE -> "it hit us and we can't get to it, hp " + hp;
            default -> "hp " + hp;
        };
    }
}
