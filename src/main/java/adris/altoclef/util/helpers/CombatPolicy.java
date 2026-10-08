package adris.altoclef.util.helpers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// the pure half of "do we fight this, run from this, or just keep walking". no world, just numbers, so it can be tested
// without minecraft. MobDefenseChain feeds it real data once a tick.
//
// the two things it exists for: eight zombies used to be answered with a shield and a prayer (a shield counted for +20
// mobs, so a shielded bot never left any crowd), and walking past a zombie on the way somewhere used to end the task
// and start a fight. a speedrunner backs off, fights them one at a time, or just keeps going
public final class CombatPolicy {

    // a mob this close is a fight, whatever we were doing. also the "is anything on top of us" distance
    public static final double STRIKE_RANGE = 2.5;
    // the path has to stay this far from a mob for it to be somebody else's problem
    public static final double PATH_CLEARANCE = 2.5;
    // creepers ruin your day from further than most things, skeletons from further still
    public static final double CREEPER_NO_IGNORE = 7;
    public static final double RANGED_NO_IGNORE = 15;
    // a shooter this near, or this near the route, is a charge even before it sees us
    public static final double SHOOTER_NEAR = 6;
    public static final double SHOOTER_PATH_CLEARANCE = 4;

    // count melee mobs this close for the "that is a crowd" call
    public static final double SWARM_RANGE = 6;
    // and this close for "is it one at a time or are they stacked on me"
    public static final double CONTACT_RANGE = 3;

    // no flapping between kiting and fighting faster than this
    public static final long MIN_DWELL = 20;
    // a retreat that makes less than this much ground in KITE_WINDOW ticks is going nowhere (a dead end, a door, a lava lake)
    public static final double KITE_PROGRESS = 3;
    public static final long KITE_WINDOW = 40;
    // and once it has gone nowhere we do not try again for this long, we tank it
    public static final long KITE_GIVE_UP = 200;
    // and a retreat that is still going after this long is not working either, they are keeping up
    public static final long KITE_MAX = 300;
    // after a retreat, the ones still coming get to come to us. chasing them back into the pile is how a bot dances
    public static final long HOLD_TICKS = 100;
    public static final double HOLD_CHASE = CONTACT_RANGE + 0.5;

    // the most mobs a shield and good gear can stand against. more than that and the answer is feet
    public static final int STAND_MAX = 4;

    // one or two skeletons are not a crowd and running from them is how a naked bot dies: they shoot you in the back for
    // free. the answer is to walk up and hit them. the closest shooter has to be this near to start it
    public static final double CHARGE_RANGE = 12;
    // three or more with eyes on us is a firing line, that one really is cover or feet
    public static final int CHARGE_MAX_SEEN = 2;
    // once it is on it stays on this long no matter what, a skeleton ducking behind a pillar is not a reason to turn round
    public static final long CHARGE_COMMIT = 20;
    // at or below this a charge is over (same line the flee stance uses), and it does not start again until we are back
    // above CHARGE_RESUME, or hp 8 and hp 9 would take turns every hit
    public static final float CHARGE_MIN_HEALTH = CombatRules.FLEE_HEALTH;
    public static final float CHARGE_RESUME = 12;

    // at or below this hp (half hearts) with something that can reach us this close, anything in contact is a run (never a
    // trade), and the wheel stays with the defense chain for LOW_HP_LATCH ticks after the last time it was true. at hp 3
    // the verdict used to flip kite / fight / "passing 5 zombified piglins" every few ticks and the user task took the
    // wheel back in between and walked us into the pile
    public static final float LOW_HP = 6;
    public static final double LOW_HP_RANGE = 8;
    public static final long LOW_HP_LATCH = 40;

    // how fast we need to be moving for "travelling" to mean it, over the last TRAVEL_WINDOW ticks
    public static final double TRAVEL_MOVED = 2;
    public static final long TRAVEL_WINDOW = 20;
    // the path was seen under the user task this recently. past it we were probably busy fighting
    public static final long TRAVEL_FRESH = 10;

    // a fight we picked is a fight for at least this long. the zombie knocked back to 2.6 blocks used to read as "walking
    // past it" for a tick, mob defense let go, the user task took the wheel, the zombie was close again, and round we went
    // several times a second. it ends early when the thing dies (gone from the scene) or is this far away
    public static final long FIGHT_LATCH = 30;
    public static final double FIGHT_LEAVE = 6;

    // the user task keeps its travelling answer this long after mob defense takes the wheel. without it the answer flipped
    // the moment the hand-off happened and the verdict flipped with it
    public static final long TRAVEL_HOLD = 40;

    // a route is only somebody else's problem if no mob can get within this much of it by the time we are there
    public static final double RUN_CLEARANCE = 3;
    // blocks per tick: a sprinting player, and a zombie (anything faster is not outrun, see canOutrun)
    private static final double RUN_SPEED = 0.28;
    private static final double MOB_SPEED = 0.22;

    private static final String CORNERED = "nowhere to run";
    private static final String OUTRUN = "outrunning";
    private static final String IN_THE_WAY = "they're in the way";

    private enum State {
        FIGHT, KITE
    }

    public enum Verdict {
        // nothing here is worth stopping for, keep doing what we were doing
        IGNORE,
        // one thing close enough to hit: turn, hit it, no shield
        FIGHT_ONE,
        // a crowd, back off and string it out. no shield, no swinging, just feet
        KITE,
        // stacked on us (or nowhere to run): shield up and trade
        STAND,
        // one or two shooters: close the distance and kill the nearest. no dodging, no stopping, shield only for an arrow
        // that is about to land
        CHARGE;
    }

    public record Point(double x, double y, double z) {
    }

    // offsets from the player. fast means it outruns a sprinting player (baby zombies, spiders, hoglins, brutes).
    // hitUs: it hit us lately, or we hit it. the only kind of mob a "we got hurt" grudge applies to
    public record Mob(int id, double dx, double dy, double dz, boolean ranged, boolean creeper, boolean fast, boolean seesUs,
                      boolean hitUs) {
        public Mob(int id, double dx, double dy, double dz, boolean ranged, boolean creeper, boolean fast, boolean seesUs) {
            this(id, dx, dy, dz, ranged, creeper, fast, seesUs, false);
        }

        public Mob withHitUs() {
            return new Mob(id, dx, dy, dz, ranged, creeper, fast, seesUs, true);
        }

        public double distance() {
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public boolean melee() {
            return !ranged && !creeper;
        }
    }

    // path: the next stretch of the route, as offsets from the player. empty when we are not on one.
    // x and z are where the player is, only used to see whether a retreat is getting anywhere
    // shield: we have one to stand behind (two on us and no shield is a run, not a trade).
    // ticksSinceHurt and graceTicks are the chain's business now: it turns them into Mob.hitUs, so a bite from one zombie
    // does not make every other zombie on the hillside our problem
    public record Scene(List<Mob> mobs, long ticksSinceHurt, boolean travelling, List<Point> path, double x, double z,
                        int swarmThreshold, long graceTicks, float health, boolean shield) {
        // no shield
        public Scene(List<Mob> mobs, long ticksSinceHurt, boolean travelling, List<Point> path, double x, double z,
                     int swarmThreshold, long graceTicks, float health) {
            this(mobs, ticksSinceHurt, travelling, path, x, z, swarmThreshold, graceTicks, health, false);
        }

        // full health, for everything that is not about to ask whether a charge is affordable
        public Scene(List<Mob> mobs, long ticksSinceHurt, boolean travelling, List<Point> path, double x, double z,
                     int swarmThreshold, long graceTicks) {
            this(mobs, ticksSinceHurt, travelling, path, x, z, swarmThreshold, graceTicks, 20f, false);
        }
    }

    // hold: do not walk up to anything farther than HOLD_CHASE, let it come. why: a few words for the log, so "kiting 2
    // zombies" can say whether it was the crowd, the missing shield or the hp
    public record Decision(Verdict verdict, Set<Integer> ignored, int swarm, int near, boolean hold, String why) {
        public Decision(Verdict verdict, Set<Integer> ignored, int swarm, int near, boolean hold) {
            this(verdict, ignored, swarm, near, hold, "");
        }

        // the shield is for STAND and nothing else
        public boolean shield() {
            return verdict == Verdict.STAND;
        }

        public boolean kiting() {
            return verdict == Verdict.KITE;
        }

        public boolean charging() {
            return verdict == Verdict.CHARGE;
        }

        // the run went nowhere. what is left is feet that cannot go, so the gear maths does not get a vote any more
        public boolean cornered() {
            return verdict == Verdict.STAND && CORNERED.equals(why);
        }

        // the crowd is behind us and our route is clear: the run is just the user task carrying on, so nobody takes the
        // wheel. every mob is in `ignored`, same as a pass-by
        public boolean outrunning() {
            return verdict == Verdict.IGNORE && OUTRUN.equals(why);
        }
    }

    private State state = State.FIGHT;
    private long stateSince = Long.MIN_VALUE / 2;
    // left KITE because the crowd strung out, not because it went away: do not run again until two are on us
    private boolean needContact;
    private long kiteWindowStart;
    private double kiteWindowX;
    private double kiteWindowZ;
    private long blockedUntil = Long.MIN_VALUE / 2;
    private long holdUntil = Long.MIN_VALUE / 2;
    // the charge is its own little machine next to the kite one, it never needs to know about the other's dwell
    private boolean charging;
    private long chargeSince = Long.MIN_VALUE / 2;
    // left a charge on health: not allowed back until CHARGE_RESUME
    private boolean chargeSpent;

    // left alone by reset(): the fight going quiet for a tick is exactly when the latch earns its keep
    private long lowHpUntil = Long.MIN_VALUE / 2;

    // the hysteresis: a mob we chose to fight stays a fight until its tick (or it dies, or leaves), and a mob we chose to
    // walk past stays walked past until it is on top of us or bites. both are per mob id, and both forget a mob that is
    // gone from the scene
    private final Map<Integer, Long> fightUntil = new HashMap<>();
    private final Set<Integer> passing = new HashSet<>();

    // is the low hp latch on. the chain asks this for the stance and for whether a held kill target still gets a say
    public boolean lowHpLatched(long now) {
        return now < lowHpUntil;
    }

    public Decision decide(long now, Scene scene) {
        if (scene.health() <= LOW_HP) {
            for (Mob mob : scene.mobs()) {
                if (mob.distance() <= LOW_HP_RANGE) {
                    lowHpUntil = now + LOW_HP_LATCH;
                    break;
                }
            }
        }
        boolean latched = lowHpLatched(now);
        forgetGone(scene);
        Set<Integer> ignored = new HashSet<>();
        List<Mob> active = new ArrayList<>(scene.mobs().size());
        for (Mob mob : scene.mobs()) {
            // (the latch used to switch this off, "nobody is walked past at low hp". walking past is the safe move at low
            // hp, it is standing next to them that kills us. what the latch does now is turn contact into a run, below)
            if (ignorable(mob, scene, now)) {
                ignored.add(mob.id());
                passing.add(mob.id());
            } else {
                active.add(mob);
                passing.remove(mob.id());
            }
        }
        if (active.isEmpty()) {
            // nothing left to fight. the next crowd is a new crowd
            reset();
            return new Decision(Verdict.IGNORE, ignored, 0, 0, false, ignored.isEmpty() ? "" : passWhy(scene));
        }

        int swarm = 0;
        int near = 0;
        // the slow ones on us. you can outwalk these, so two of them on us is a reason to leave, not to trade
        int slowNear = 0;
        // the charge's numbers: everything shooting at us, the ones worth walking at, and how close the nearest of those is
        int seen = 0;
        int shooters = 0;
        int meleeNear = 0;
        double shooterRange = Double.POSITIVE_INFINITY;
        for (Mob mob : active) {
            double distance = mob.distance();
            if (mob.melee() && !mob.fast() && distance <= SWARM_RANGE) swarm++;
            // creepers are the creeper logic's problem, they do not count towards being stacked on
            if (!mob.creeper() && distance <= CONTACT_RANGE) near++;
            if (mob.melee() && distance <= CONTACT_RANGE) meleeNear++;
            if (mob.melee() && !mob.fast() && distance <= CONTACT_RANGE) slowNear++;
            if (mob.ranged() && mob.seesUs()) seen++;
            if (isShooter(mob)) {
                // any shooter that made it this far is one we are meant to deal with (in the engage zone, or not ignorable
                // on a walk), seeing us or not. the one by the route that has not spotted us yet is the cheap one to kill
                shooters++;
                shooterRange = Math.min(shooterRange, distance);
            }
        }
        boolean crowd = swarm >= scene.swarmThreshold();
        // at low hp nobody waits for a second zombie to show up before leaving
        if (!crowd || latched) needContact = false;
        boolean blocked = now < blockedUntil;
        // two on us and nothing to stand behind: trading hits standing still is what killed us twice to plain zombies. a
        // charge in its first moments gets to finish its commit, same as it does against a zombie wandering by
        boolean committed = charging && now - chargeSince < CHARGE_COMMIT;
        boolean pressed = !scene.shield() && slowNear >= 2 && !committed;
        // and at low hp anything in reach is a reason to go, one hit from anything is a lot of what is left
        boolean lowRun = latched && meleeNear >= 1;

        if (state == State.KITE) {
            if (now - stateSince >= KITE_MAX) {
                // they are keeping up with us, so this is not shaking anybody
                blockedUntil = now + KITE_GIVE_UP;
                blocked = true;
                switchTo(State.FIGHT, now);
            } else if (now - kiteWindowStart >= KITE_WINDOW) {
                double moved = Math.hypot(scene.x() - kiteWindowX, scene.z() - kiteWindowZ);
                if (moved < KITE_PROGRESS) {
                    // going nowhere. whatever is in the way, the shield and the sword work anywhere
                    blockedUntil = now + KITE_GIVE_UP;
                    blocked = true;
                    switchTo(State.FIGHT, now);
                } else {
                    kiteWindowStart = now;
                    kiteWindowX = scene.x();
                    kiteWindowZ = scene.z();
                }
            }
            // done when the crowd has thinned out, or the front runner got to us while the rest are still on their way.
            // nobody in reach and a crowd still on our heels means the run is working, not that it is over. a pair with no
            // shield is done once they string out to one on us (same idea), and nothing is done while we are hurt and
            // something can still reach us
            boolean thinned = crowd ? near == 1 : !pressed;
            if (state == State.KITE && now - stateSince >= MIN_DWELL && !lowRun && thinned) {
                needContact = crowd && !latched;
                // let them come to us, unless we are low and the last thing we want is a reason to stand and wait for them
                if (!latched) holdUntil = now + HOLD_TICKS;
                switchTo(State.FIGHT, now);
            }
        } else if ((lowRun || pressed || (crowd && (!needContact || near >= 2))) && !blocked && now - stateSince >= MIN_DWELL) {
            switchTo(State.KITE, now);
            kiteWindowStart = now;
            kiteWindowX = scene.x();
            kiteWindowZ = scene.z();
        }

        if (state == State.KITE) {
            // a crowd outranks a skeleton, even a committed charge. feet first
            charging = false;
            fightUntil.clear();
            // (not with one already on us, or at low hp: that one walks along with us swinging, and the route might be a
            // ladder or a block to break, none of which is a sprint)
            if (!scene.path().isEmpty() && scene.travelling() && !lowRun && meleeNear == 0) {
                if (canOutrun(scene.path(), active)) {
                    // the way we were going is clear of them, so going is the run. sprint on, nobody takes the wheel
                    Set<Integer> everyone = new HashSet<>(ignored);
                    for (Mob mob : active) everyone.add(mob.id());
                    return new Decision(Verdict.IGNORE, everyone, swarm, near, false, OUTRUN);
                }
                return new Decision(Verdict.KITE, ignored, swarm, near, false, IN_THE_WAY);
            }
            return new Decision(Verdict.KITE, ignored, swarm, near, false, kiteWhy(lowRun, crowd, pressed));
        }
        if (chargeOn(now, scene.health(), crowd, seen, shooters, shooterRange, meleeNear)) {
            return new Decision(Verdict.CHARGE, ignored, swarm, near, false, "shooters first");
        }
        boolean hold = now < holdUntil;
        // a run we could not make is a fight we stand in front of. a shield is the other reason to stand, with one of those
        // two on us the trade is at least a fair one
        boolean cornered = blocked && (crowd || pressed || lowRun);
        latchFights(now, active);
        if (cornered || near >= 2) {
            return new Decision(Verdict.STAND, ignored, swarm, near, false,
                    cornered ? CORNERED : scene.shield() ? "shield up" : "they outrun us");
        }
        return new Decision(Verdict.FIGHT_ONE, ignored, swarm, near, hold, hold ? "let them come" : "one at a time");
    }

    // ignorable now, with what we decided last time folded in
    private boolean ignorable(Mob mob, Scene scene, long now) {
        Long until = fightUntil.get(mob.id());
        // we picked this one, it does not get to turn back into a bystander for a knockback
        if (until != null && now < until && mob.distance() <= FIGHT_LEAVE) return false;
        return canIgnore(mob, scene, passing.contains(mob.id()));
    }

    // the ones that are on us are a fight for FIGHT_LATCH ticks, and still being on us keeps that going
    private void latchFights(long now, List<Mob> active) {
        for (Mob mob : active) {
            if (mob.distance() <= STRIKE_RANGE || mob.hitUs()) {
                fightUntil.merge(mob.id(), now + FIGHT_LATCH, Math::max);
            }
        }
    }

    // dead and gone is the end of both latches
    private void forgetGone(Scene scene) {
        if (fightUntil.isEmpty() && passing.isEmpty()) return;
        Set<Integer> here = new HashSet<>();
        for (Mob mob : scene.mobs()) here.add(mob.id());
        fightUntil.keySet().retainAll(here);
        passing.retainAll(here);
    }

    // why a run, in the words the log wants
    private static String kiteWhy(boolean lowRun, boolean crowd, boolean pressed) {
        if (lowRun) return "low hp";
        if (pressed) return "two on us, no shield";
        return crowd ? "a crowd" : "backing off";
    }

    private static String passWhy(Scene scene) {
        return scene.travelling() ? "on our way" : "busy and they are not close";
    }

    // nobody around at all: the cheap version of decide, for the ticks that are nearly all of them
    public Decision idle() {
        reset();
        passing.clear();
        return IDLE;
    }

    // a skeleton-ish thing: shoots, and does not outrun a sprinting player, so walking at it works. pillagers and
    // piglins are fast, and the wither is "ungated" which counts as fast here, none of them are ours to charge
    public static boolean isShooter(Mob mob) {
        return mob.ranged() && !mob.fast();
    }

    // whether the charge is on this tick. it is its own latch: starting wants a shooter inside CHARGE_RANGE, staying only
    // wants a shooter left, because a skeleton that steps behind a pillar is still the thing to kill
    private boolean chargeOn(long now, float health, boolean crowd, int seen, int shooters, double shooterRange, int meleeNear) {
        if (health >= CHARGE_RESUME) chargeSpent = false;
        if (health <= CHARGE_MIN_HEALTH) {
            // out of health, not out of skeleton
            if (charging) chargeSpent = true;
            charging = false;
            return false;
        }
        if (chargeSpent || crowd || seen > CHARGE_MAX_SEEN || shooters == 0) {
            charging = false;
            return false;
        }
        if (charging) {
            // two on top of us is a melee, just not in the first moments. the commit is what stops a zombie wandering by
            // from turning us round before we have hit anything
            if (meleeNear >= 2 && now - chargeSince >= CHARGE_COMMIT) {
                charging = false;
                return false;
            }
            return true;
        }
        if (meleeNear >= 2 || shooterRange > CHARGE_RANGE) return false;
        charging = true;
        chargeSince = now;
        return true;
    }

    // the policy said "one at a time" and there really is one slow melee thing to deal with: that is a fight, and the gear
    // maths (standCapacity) does not get to turn it into a run. it is for piles the policy did not already rule on
    public static boolean fightsLoneMelee(Verdict verdict, int dealWithCount, boolean loneSlowMelee) {
        return verdict == Verdict.FIGHT_ONE && dealWithCount == 1 && loneSlowMelee;
    }

    // the vulnerable branch of the chain's isInDanger, as numbers. armor 0 at hp 17 used to flee from a lone skeleton,
    // which is exactly the wrong way to meet one
    public static boolean vulnerable(int armor, float health) {
        if (armor <= 15 && health < 3) return true;
        if (armor < 10 && health < 10) return true;
        return armor < 5 && health < 18;
    }

    // and who counts as company worth fleeing for when we are vulnerable: anything that walks at us, or a firing line.
    // one or two shooters are a charge, see chargeOn
    public static boolean dangerousCompany(int melee, int shooters) {
        return melee >= 1 || shooters > CHARGE_MAX_SEEN;
    }

    // everything the chain knows when it asks "is this a reason to run". melee and shooters are the ones the policy is
    // actually dealing with (not the ones it is walking past) within the chain's danger range
    public record Danger(float health, int armor, boolean hasFood, boolean witchAround, boolean charging,
                         boolean statusEffect, boolean policyOn, Decision decision, int melee, int shooters, int capacity) {
    }

    // the hp the food rule runs at (same line the dodge gate and the food chain use for "should be eating")
    public static final float LOW_HP_FOOD = 10;

    // the chain's isInDanger, as numbers. the vulnerable branch used to be "armor under 5 and hp under 18 and a melee mob
    // within 8", which is every early game bot below full health and one zombie. that ran from a lone zombie at hp 17 with
    // a shield, twice, while the policy was passing it. the policy owns every verdict it gave: a pass, a fight and a kite
    // are its business, what is left for this is a stand against more than the gear can take, and a firing line
    public static boolean inDanger(Danger d) {
        if (d.statusEffect()) return true;
        // running along the route already is the answer
        if (d.decision().outrunning()) return false;
        // nothing real on us is nothing to run from (hp 9 with food and a creeper two chunks away is not a reason)
        if (d.melee() + d.shooters() == 0) return false;
        if (d.health() <= LOW_HP_FOOD && d.hasFood() && !d.witchAround() && !d.charging()) return true;
        if (!vulnerable(d.armor(), d.health())) return false;
        if (d.shooters() > CHARGE_MAX_SEEN) return true;
        if (!d.policyOn()) return dangerousCompany(d.melee(), d.shooters());
        Decision decision = d.decision();
        return decision.verdict() == Verdict.STAND && !decision.cornered() && d.melee() > d.capacity();
    }

    private void reset() {
        state = State.FIGHT;
        needContact = false;
        charging = false;
        // the dwell is for flapping inside one fight, not between two
        stateSince = Long.MIN_VALUE / 2;
        // (the walked past latch survives this, an all-ignored scene is exactly where it earns its keep)
        fightUntil.clear();
    }

    private static final Decision IDLE = new Decision(Verdict.IGNORE, Set.of(), 0, 0, false);

    private void switchTo(State next, long now) {
        state = next;
        stateSince = now;
    }

    // whether this mob is somebody else's business while we walk past. every one of these has to hold
    public static boolean canIgnore(Mob mob, Scene scene) {
        return canIgnore(mob, scene, false);
    }

    // stillPassing: we already walked past this one last tick. then the travelling answer and the route do not get to
    // change our mind (they flip when mob defense takes the wheel), only the things that make it a real threat do
    public static boolean canIgnore(Mob mob, Scene scene, boolean stillPassing) {
        // the one that bit us (or that we hit) is a fight. the rest of the hillside is not, a hit used to end the stroll for
        // every zombie in sight and a bot at hp 5 would stop and swing at all of them. low hp does not stop a pass either:
        // walking past is the safe move there
        if (mob.hitUs()) return false;
        // outruns us, so walking away from it is not an option
        if (mob.fast()) return false;
        double distance = mob.distance();
        if (distance <= STRIKE_RANGE) return false;
        if (mob.creeper() && distance <= CREEPER_NO_IGNORE) return false;
        if (mob.ranged() && mob.seesUs() && distance <= RANGED_NO_IGNORE) return false;
        // a skeleton this close is about to have a line on us whatever it can see right now, and one by the route is
        // going to be a charge in a few steps either way. wider berth than a zombie gets
        boolean shooter = isShooter(mob);
        if (shooter && distance <= SHOOTER_NEAR) return false;
        if (stillPassing && !pileForming(scene)) return true;
        // standing at a furnace or a tree there is no route to keep clear of, just us. same radius as the stroll, and the
        // task keeps going until it really is on top of us (it used to drop the pickaxe for a zombie nine blocks away). a
        // pile of them though is never somebody else's business, the run has to start before they are on us
        if (!scene.travelling()) return !pileForming(scene);
        double clearance = shooter ? SHOOTER_PATH_CLEARANCE : PATH_CLEARANCE;
        // heading for it is not passing it
        for (Point p : scene.path()) {
            double dx = p.x() - mob.dx(), dz = p.z() - mob.dz();
            if (Math.abs(p.y() - mob.dy()) <= 3 && dx * dx + dz * dz <= clearance * clearance) return false;
        }
        return true;
    }

    // can we just keep going. needs a route, nothing that outruns us or shoots us, and no mob able to get within
    // RUN_CLEARANCE of any stretch of the route by the time we are on it. the mob is assumed to walk straight at wherever
    // we will be, at zombie speed, so one off to the side that would cut the corner counts and one behind us does not
    public static boolean canOutrun(List<Point> path, List<Mob> threats) {
        if (path.isEmpty() || threats.isEmpty()) return false;
        for (Mob mob : threats) {
            if (mob.fast() || mob.ranged()) return false;
        }
        double along = 0;
        double px = 0, pz = 0;
        for (Point p : path) {
            along += Math.hypot(p.x() - px, p.z() - pz);
            px = p.x();
            pz = p.z();
            double reach = along * MOB_SPEED / RUN_SPEED;
            for (Mob mob : threats) {
                if (Math.abs(p.y() - mob.dy()) > 3) continue;
                double there = Math.hypot(mob.dx() - p.x(), mob.dz() - p.z());
                // a mob that is farther from this spot than from us is behind it, falling back as we go
                if (there >= Math.hypot(mob.dx(), mob.dz())) continue;
                if (there - reach <= RUN_CLEARANCE) return false;
            }
        }
        return true;
    }

    // enough slow melee mobs inside the swarm range to be a crowd, counting the ones nobody has ruled on yet
    private static boolean pileForming(Scene scene) {
        int slow = 0;
        for (Mob mob : scene.mobs()) {
            if (mob.melee() && !mob.fast() && mob.distance() <= SWARM_RANGE) slow++;
        }
        return slow >= scene.swarmThreshold();
    }

    // how many mobs we can tank. the shield used to add 20 here, which is how a shielded bot stood in the middle of
    // eight zombies. one extra now, and nothing stands against more than STAND_MAX whatever it is wearing.
    // damage is the held weapon's attack damage minus 3 (the tuning is from before the real numbers were used). an axe
    // reads high next to a sword of its tier, which is fine, STAND_MAX is where it stops mattering
    public static int standCapacity(int armor, float damage, boolean hasShield) {
        int base = (int) Math.ceil((armor * 3.6 / 20.0) + (damage * 0.8)) + 1;
        if (hasShield) base++;
        return Math.min(base, STAND_MAX);
    }

    // is the user's path leading us somewhere. the latch: while the user task is the one pathing we keep what it was
    // about to walk, because the moment mob defense takes over the goal is gone and the question has no answer
    public static final class TravelTracker {
        private static final long SAMPLE_EVERY = 2;

        private record Sample(long tick, double x, double z) {
        }

        private final ArrayDeque<Sample> samples = new ArrayDeque<>();
        private long lastPathing = Long.MIN_VALUE / 2;
        private List<Point> upcoming = List.of();
        // the last tick the answer was a real yes, and whether somebody else has the wheel right now
        private long travellingAt = Long.MIN_VALUE / 2;
        private boolean handedOff;

        // pathing is "the user task is current and baritone has a path"
        public void update(long now, double x, double z, boolean pathing) {
            update(now, x, z, pathing, false);
        }

        // handedOff: another chain has the wheel (mob defense, or the food chain chewing), so "not pathing" says nothing
        // about the user task
        public void update(long now, double x, double z, boolean pathing, boolean handedOff) {
            this.handedOff = handedOff;
            if (!samples.isEmpty() && (now < samples.peekLast().tick || now - samples.peekLast().tick > TRAVEL_WINDOW)) {
                // time went backwards or we were not watching, the history is nonsense
                samples.clear();
            }
            while (!samples.isEmpty() && now - samples.peekFirst().tick > TRAVEL_WINDOW) {
                samples.pollFirst();
            }
            if (samples.isEmpty() || now - samples.peekLast().tick >= SAMPLE_EVERY) {
                samples.addLast(new Sample(now, x, z));
            }
            if (pathing) lastPathing = now;
        }

        // the next stretch of the route, absolute coordinates. only worth building when somebody is going to ask
        public void setUpcoming(List<Point> upcomingAbsolute) {
            upcoming = upcomingAbsolute;
        }

        public boolean travelling(long now, double x, double z) {
            if (walkingNow(now, x, z)) {
                travellingAt = now;
                return true;
            }
            // mob defense has the wheel: the answer from just before it did still stands. reading the current chain here is
            // what made the verdict flip the moment the hand-off happened
            return handedOff && now - travellingAt < TRAVEL_HOLD;
        }

        private boolean walkingNow(long now, double x, double z) {
            if (now - lastPathing > TRAVEL_FRESH) return false;
            // a full window of history, or it could be the first step of anything
            if (samples.isEmpty() || now - samples.peekFirst().tick < TRAVEL_WINDOW - SAMPLE_EVERY * 2) return false;
            for (Sample s : samples) {
                if (Math.hypot(x - s.x, z - s.z) >= TRAVEL_MOVED) return true;
            }
            return false;
        }

        public List<Point> upcoming() {
            return upcoming;
        }

        // the user task changed, whatever route we were remembering is somebody else's
        public void clear() {
            samples.clear();
            lastPathing = Long.MIN_VALUE / 2;
            travellingAt = Long.MIN_VALUE / 2;
            upcoming = List.of();
        }
    }
}
