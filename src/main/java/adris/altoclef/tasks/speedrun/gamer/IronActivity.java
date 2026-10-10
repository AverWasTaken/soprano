package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// the one place IRON decides "what are we doing right now". same deal as CombatCommit for fights: one way in, one way out,
// nothing in between lets go. the jobs keep their own insides (ResourceDetour, VillageLoot, GolemHunt, FurnaceWatch...), this only
// says WHICH of them gets asked. pure, IronPhase is the thin driver that asks the jobs in the order this hands it.
//
// the rule: the kit is the floor, anything eligible above it takes the wheel. anything else, once it has the wheel, holds it
// until its own end (its job hands back null: done, gave up, out of budget) or something allowed to cut in shows up: the finishing
// at a station cuts in on anything (a half taken furnace is how stations got left behind), a quick smoker stand-by cuts in on the
// side jobs below it (see preempts). combat is a chain of its own and sits above all of this already. one shared hysteresis
// rule: something that ended on its own sits out REST_TICKS before it can start again
public final class IronActivity {

    // the order IS the priority, top first. every entry says why it sits where it does
    public enum Kind {
        // a pickup in flight, or the cook that goes before an emptied furnace comes down. the only must: it preempts anything
        FINISHING("finishing at a station"),
        // a golem fight that is going. a chest is not worth stepping off the pillar for (it only gets here without being the
        // activity when the old path or a re-entry left one running, see Scene.golemFighting)
        GOLEM_FIGHT("golem fight"),
        // standing at a quick smoker (FurnacePlan STAND_BY QUICK): 30 s at most of waiting beats walking off and back, so every side job
        // that walks away waits for it. the old tickStandBy rule. only offered from within FurnacePlan.COLLECT_CUT_IN, so it never
        // drags a side job home from across the map
        STAND_BY("smoker stand-by"),
        // taking our table, furnace or smoker back. above the loot because a station left standing is gone for good once we walk
        // past FORGET, a chest is still there next time
        STATION("station pickup"),
        // loot first, chests and beds are things we cannot just mine
        RUINED_PORTAL("ruined portal"),
        VILLAGE_CHEST("village chest"),
        // a bed is a few seconds of punching, a golem is a minute on a pillar
        BED("village bed"),
        GOLEM_START("golem hunt"),
        // last of the side jobs: coal is the only one for something we might need rather than something we do. it ends itself
        // on a furnace that is due and close (DetourRules), so it never sits on a collect
        COAL("coal detour"),
        // gravel for flint, right behind coal: coal is burnt this phase, the flint waits for the portal. same rules otherwise
        // (it ends itself on a due furnace close by too), and its 2 min cooldown keeps it from taking every patch we walk past
        GRAVEL("gravel detour"),
        // a collect, a take-back, the early pick interrupt, the idle wait. after the side jobs like it always was (a trip only
        // starts when the wheel is free)
        FURNACE("furnace trip"),
        // about to climb out of the mine with something still cooking at the bottom: take it along first (PackUp)
        PACK_UP("pack up"),
        // all the ore is mined and we are down a mine: up first, the smelt places its furnace in the open (SmeltSurface)
        SURFACE("surfacing"),
        // the plan's head need. the floor, it never holds against anything
        KIT("kit");

        public final String words;

        Kind(String words) {
            this.words = words;
        }
    }

    // something that ended on its own can not start again for this long. 2 s is longer than any one-tick wobble in a job's
    // own start test and shorter than anything worth doing, so a job that really wants the wheel back loses 2 s and a job that
    // flickers loses its flicker. musts and the kit never rest
    public static final long REST_TICKS = 40;

    // what the phase can say about this tick for the eligibility rules. jobs = something is cooking, standingBy = the plan
    // stands at a quick smoker, hasHead = the plan has a need, loots = village/portal/golem jobs are on (IRON yes, GATHER no),
    // golemFighting = a golem fight task exists whatever the activity is
    public record Scene(boolean jobs, boolean standingBy, boolean hasHead, boolean loots, boolean golemFighting) {
    }

    private Kind current;
    private long since;
    private final Map<Kind, Long> endedAt = new EnumMap<>(Kind.class);
    // why the last activity stopped, for the line the next start writes
    private String endReason;
    private Kind lastEnded;
    // the last pick was nothing at all, so the next one is a restart and not the first pick
    private boolean idled;

    public static boolean must(Kind k) {
        return k == Kind.FINISHING;
    }

    // may `k` cut in on `held`. finishing on anything. a quick smoker stand-by on the side jobs below it, the old tickStandBy rule:
    // standing at the smoker is the plan, and a coal detour that started a minute before the smoker went quick would otherwise
    // run its 30 s and eat the stand-by (FurnacePlan's quick window opens before the job is due, so coal's own due rule is late).
    // never on a golem fight that is going, a hunt that launched stays GOLEM_START but it is a man on a pillar by now
    public static boolean preempts(Kind k, Kind held, boolean golemFighting) {
        if (k == Kind.FINISHING) {
            return true;
        }
        if (held == Kind.GOLEM_START && golemFighting) {
            return false;
        }
        return k == Kind.STAND_BY && held.ordinal() >= Kind.STATION.ordinal() && held.ordinal() <= Kind.GRAVEL.ordinal();
    }

    // holds the wheel once it has it. the kit is the floor and yields to anything eligible
    public static boolean commits(Kind k) {
        return k != Kind.KIT;
    }

    // may this kind be asked at all this tick. this is the start test only: an activity that holds is asked whatever this says,
    // its own job decides when it is over
    public static boolean eligible(Kind k, Scene s) {
        return switch (k) {
            case FINISHING, KIT -> true;
            case GOLEM_FIGHT -> s.loots() && s.golemFighting();
            case STAND_BY -> s.jobs() && s.standingBy();
            // standing by a smoker is the plan, nothing that walks off starts meanwhile
            case STATION, BED, COAL, GRAVEL -> !s.standingBy();
            case RUINED_PORTAL, VILLAGE_CHEST -> s.loots() && !s.standingBy();
            case GOLEM_START -> s.loots() && !s.standingBy() && !s.golemFighting();
            case FURNACE -> s.jobs() && !s.standingBy();
            case PACK_UP -> s.jobs() && s.hasHead();
            case SURFACE -> s.hasHead();
        };
    }

    public void reset() {
        current = null;
        since = 0;
        endedAt.clear();
        endReason = null;
        lastEnded = null;
        idled = false;
    }

    // null before the first choice and when the kit had nothing to do either
    public Kind current() {
        return current;
    }

    public long since() {
        return since;
    }

    public boolean resting(Kind k, long now) {
        Long t = endedAt.get(k);
        return t != null && !must(k) && k != Kind.KIT && now - t < REST_TICKS;
    }

    // who to ask this tick, in order; the driver hands the wheel to the first one whose job returns a task. while something holds:
    // the musts above it, then it. after that (only reached when it handed back null, see ended) the open choice: everything
    // eligible and not resting, top first. nobody is in the list twice, a job asked twice in a tick could start twice
    public List<Kind> ask(Scene s, long now) {
        List<Kind> out = new ArrayList<>();
        Kind held = current != null && commits(current) ? current : null;
        if (held != null) {
            for (Kind k : Kind.values()) {
                if (k.ordinal() >= held.ordinal()) {
                    break;
                }
                if (preempts(k, held, s.golemFighting()) && eligible(k, s)) {
                    out.add(k);
                }
            }
            // a fight that is going is somebody we already committed to, even if the activity forgot (old path, re-entry)
            if (s.loots() && s.golemFighting() && held != Kind.GOLEM_START && held != Kind.GOLEM_FIGHT && !must(held)) {
                out.add(Kind.GOLEM_FIGHT);
            }
            out.add(held);
        }
        for (Kind k : Kind.values()) {
            if (!out.contains(k) && eligible(k, s) && !resting(k, now)) {
                out.add(k);
            }
        }
        return out;
    }

    // the held activity's job handed back null: it is over. `why` goes in the next line
    public void ended(Kind k, long now, String why) {
        if (k != current) {
            return;
        }
        endedAt.put(k, now);
        endReason = k.words + " done" + (why == null || why.isEmpty() ? "" : ": " + why);
        lastEnded = k;
        current = null;
    }

    // `k` got the wheel this tick. returns the one `activity:` line when that is a change, null when it is not
    public String chose(Kind k, long now) {
        String ended = endReason;
        Kind before = ended != null ? lastEnded : current;
        endReason = null;
        lastEnded = null;
        if (k == before && ended == null) {
            return null;
        }
        current = k;
        since = now;
        // the same job straight back (a new trip right after the last one) is not a change worth a line
        if (k == before) {
            return null;
        }
        boolean restart = idled;
        idled = k == null;
        String from = before == null ? "nothing" : before.words;
        String to = k == null ? "nothing" : k.words;
        String why;
        if (ended != null) {
            why = ended;
        } else if (before == null) {
            why = restart ? "something to do again" : "first pick";
        } else if (k != null && must(k)) {
            why = "must, " + k.words + " goes first";
        } else if (k == Kind.STAND_BY && before != Kind.KIT) {
            why = "a quick smoker comes before the " + before.words;
        } else if (before == Kind.KIT) {
            why = "the kit gives way";
        } else if (k == Kind.GOLEM_FIGHT) {
            why = "a golem fight is going";
        } else {
            why = "switched";
        }
        return "activity: " + from + " -> " + to + " (" + why + ")";
    }

    // nothing wanted the wheel, not even the kit
    public String idle(long now) {
        return chose(null, now);
    }
}
