package adris.altoclef.util.helpers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// the pure half of "do something about it, and then actually finish", for every dimension: mobs are ignored until one
// really needs dealing with, and then we commit. "really" is doing a lot of work there: the task walks on past crowds,
// shooters and lit creepers, only a mob chewing on us in contact or actual danger gets the wheel. a fight holds until
// its target is dead or gone, a run holds until we are far away and nothing is following. no world in here, just numbers, so every transition can be tested without minecraft.
// what differs between the overworld, the nether and the end is who counts as a foe and what kind of foe it is (FoeRules),
// the machine itself does not know where it is
//
// the thing it replaced is a stack of small latches (a fight latch, a charge latch, a run latch, a park timer, a hold)
// that each handed the wheel back when they expired. one commitment has one way in and one way out, and nothing in
// between lets go
public final class CombatCommit {

    // a mob that hit us is only a fight while it is this close. a skeleton plinking from ten blocks is answered by walking,
    // a moving target is a bad target
    public static final double CONTACT = CombatRules.CONTACT_RANGE;
    // the warden's sonic boom reaches about this far, and a boom is a run whatever the hp
    public static final double BOOM_RANGE = 15;
    public static final float FLEE_HP = CombatRules.FLEE_HEALTH;
    // heavy hitters are run from at this hp instead (see CombatRules.HEAVY_FLEE_HEALTH for why it is its own number)
    public static final float HEAVY_FLEE_HP = CombatRules.HEAVY_FLEE_HEALTH;
    // at or below FLEE_HP anything angry this close is a reason to leave
    public static final double LOW_HP_RANGE = CombatRules.LOW_HP_RANGE;
    // "it hit us" is remembered this long. a skeleton that landed an arrow ten seconds ago and is still standing there
    // is the same skeleton
    public static final long HIT_MEMORY = 200;
    // a hit this recent is news (the cooldown only listens to news)
    public static final long FRESH = 5;

    // a fight target this far away and quiet for LOST_TICKS is somebody else's problem now. we are not chasing a zombie
    // across the map, the task has places to be
    public static final double LOST_RANGE = 6;
    public static final long LOST_TICKS = 60;
    // ten seconds of not getting any closer: it is on a pillar, across water, or just not coming
    public static final long STALL_TICKS = 200;
    public static final double PROGRESS = 0.5;
    // the target ran away this much from the best we had: that is the new baseline, not progress
    public static final double RUNAWAY_SLACK = 3;

    // a run is over 24 blocks out (flat) with nothing angry within 12 for two seconds, or at 25 s. it used to be 50/16/45,
    // which was a lot of jogging for one zombie
    public static final double RUN_DISTANCE = 24;
    public static final double RUN_CLEAR = 12;
    public static final long RUN_CLEAR_TICKS = 40;
    public static final long RUN_CAP = 500;

    // cornered: a run that went nowhere for CORNER_TICKS with something on top of us (CORNER_RANGE), or for twice that with
    // something angry anywhere within CORNER_WATCH (a shooter we can't get away from). the window only runs while something
    // angry is within CORNER_WATCH, so standing still to eat does not count as being stuck
    public static final double CORNER_WATCH = LOW_HP_RANGE;
    public static final double CORNER_RANGE = CombatRules.CONTACT_RANGE;
    public static final long CORNER_TICKS = 60;
    public static final long CORNER_FAR_TICKS = 120;
    public static final double CORNER_PROGRESS = 1.5;

    // a run that has gone nowhere for this long with nothing angry within CORNER_WATCH is not a run, it is a bot standing at
    // a lava lake with a pathfinder that keeps saying no. over, instead of spinning until the cap. with something near the
    // cornered rule above already has an answer (fight it)
    public static final long RUN_STUCK_TICKS = 100;

    // a run that is really trying to move (a path executing, or the search came back with none) and has moved us less than this
    // far over this many such ticks with a melee foe in contact (ReachRules.MELEE_REACH, eye to box) has no room: it is the
    // cornered fight now and not after CORNER_TICKS. ticks where the run is not trying (the search still going, a bite, the air,
    // water) do not count at all: standing still there is by design and says nothing about room
    public static final long NO_ROOM_TICKS = 10;
    public static final double NO_ROOM_BLOCKS = 1;

    // after any commitment ends, only a fresh hit starts the next one. low hp too: only for something on top of us or one
    // that just hit us, see trigger
    public static final long COOLDOWN = 100;

    // a mob in contact that is standing in the path (or in the block we want to place) is a fight even before it swings:
    // walking past it is not an option when it is the thing in the way, and baritone will try to place a block into it
    // forever. no path progress for WAY_BOXED_TICKS with one in contact is the same thing in a 1 wide hole, where "the path"
    // is a pillar going nowhere. the cornered rule above is the run's version of this and wants 3 s, this one 1.5
    public static final long WAY_BOXED_TICKS = 30;
    public static final double WAY_PROGRESS = 1;
    // an in-the-way fight is over once it is this far off (and not hitting us), the path is clear again
    public static final double WAY_RELEASE = 4;
    // baritone drops the path for a tick or two between giving up on a place and the next search. that is not "we stopped
    // wanting to move", so the boxed clock only starts over after this long without a path
    public static final long WAY_GAP_TICKS = 10;

    // the target is a zombie we cannot reach and another melee mob that hit us stands in reach: after this long of that we
    // swing at the one that is actually there. two zombies taking turns through each other's i-frames are not this, the
    // target stays in reach for those
    public static final long TAKEOVER_TICKS = 20;

    // a crowd is this many foes actually on us (see engaged): a run whatever the hp. two is a fight, hurt or not
    public static final int CROWD_SIZE = 3;

    // a fast melee mob that already hit us this close is not outrun: at a run decision (low hp, crowd) it is the cornered fight
    public static final double FAST_FIGHT_RANGE = 4;

    // a run that keeps taking hits is not working. every hit knocks us out of contact, so a mob that runs in, swings, gets
    // knocked off and runs in again never added up to a second of anything. this many hits landed while we run (each one
    // counted once) and an armed bot turns on it
    public static final int RUN_HITS = 2;
    // hits in the first this many ticks of a run do not count. a crowd run starts with them on us, and they get their swings in
    // while we turn round and the first path comes back: that is why we are running, not news that it is not working.
    // (player i-frames are about 10 ticks and a zombie swings about every 20, so this is a swing or two each)
    public static final long RUN_HIT_GRACE = 30;

    // a sniper we could not get to (a cliff skeleton, one across water) is left alone this long, a lobber for LOBBER_GIVE_UP_TICKS.
    // the run out of its sight is cheap, the fight that stalls is not, so the sniper gets three times the patience
    public static final long SNIPER_GIVE_UP_TICKS = 1800;

    // a run from shooters is over once none of them has had a line to us this long (a leaf flickering in the way is not cover)
    public static final long LOS_CLEAR_TICKS = 20;

    // how far a lobber that hit us is still worth a fight (FoeRules.WITCH_HIT_RANGE is the same number from the other side:
    // how far a witch can be and still be what the poison is blamed on)
    public static final double LOBBER_RANGE = FoeRules.WITCH_HIT_RANGE;

    // a fight with a ranged foe we chose to go and get (a lobber, or a sniper that pinned us down) that stalled is not tried
    // again for this long: the next qualifying hit in that time is a run out of its range. thirty seconds, then it is a fight again
    public static final long LOBBER_GIVE_UP_TICKS = 600;

    // a lobber counts as landing hits for the next-target pick when its last one is this fresh. the witch throws about every three
    // seconds (60 tick attack interval) and half the throws miss, so FRESH (5 ticks) would almost never be true at the moment a
    // target dies
    public static final long LOBBER_PICK_TICKS = 100;

    // the tracker only lists hostiles closer than this, and it is how far a sniper can be and still pin us down
    public static final double FOE_RANGE = 16;
    // a shooter with a line to us that hit us this lately counts as near for the low hp rule, whatever the distance
    public static final long SHOT_MEMORY = 100;
    // a shooter that was a reason not to eat stays one this long after it stops being (a line that flickers behind a tree, a
    // skeleton that steps out past the range): the meal is 1.6 s and one arrow in it is 4
    public static final long SHOT_LATCH = 60;

    // the bail line moves with how hard we are being hit: leave when a hit like the last big one would put us at or under
    // FLEE_HP, not when we are already there. the biggest single drop in the last NEXT_HIT_WINDOW ticks (capped, a fall or a
    // creeper is not a model for the next swing) is the guess. at armor 0 with hits of 3 to 5 the line sits at 11 to 13, in full
    // iron (about 2 an arrow) it is the old 10. checking "<= 8" only ever passed at 6 when the hits skip over 8
    public static final long NEXT_HIT_WINDOW = 40;
    public static final float NEXT_HIT_CAP = 6;
    // two steps further apart than this are not one continuous watch (the chain was off), a drop across the gap is no hit
    private static final long HIT_WATCH_GAP = 10;

    // whether a run is really trying to get somewhere this tick, for noRoomFight. the "search came back with no path" half is
    // only believed while it is the latest news: any path executing or search going takes the count of failures as the new base
    public static final class Drive {
        private int base;

        public boolean trying(boolean running, boolean pathing, boolean searching, int failures, boolean usingItem, boolean onGround,
                              boolean inWater) {
            if (!running || pathing || searching) {
                base = failures;
            }
            boolean failed = running && !pathing && !searching && failures > base;
            return running && (pathing || failed) && !usingItem && onGround && !inWater;
        }
    }

    // never hit us
    public static final long NEVER = Long.MAX_VALUE / 4;

    public enum Mode {
        NONE, FIGHT, RUN
    }

    // why a commitment started, so the chain can say so when it does
    public enum Why {
        NONE, HIT, LOW_HP, HEAVY, CORNERED,
        // it was standing in our path (or we were boxed in with it) and the path could not go on
        IN_WAY,
        // the run hit its cap with something still on our heels, so we turned around (boxed in rules, like CORNERED).
        // a run that something in contact keeps biting through turns around for the same reason
        CHASED,
        // enough of them on us at once (CROWD_SIZE): not a fight to take
        CROWD,
        // something hit us and we have nothing better than bare hands. a punch is not a fight, feet are
        UNARMED,
        // a lobber we already failed to get to hit us again: not another fight, a run out of its range
        LOBBER,
        // hurt, and a shooter with a line to us hit us lately: near enough to leave, whatever the distance
        SHOT,
        // a skeleton that is shooting us and we are fit to fight it: one target, till it is dead
        PINNED,
        // a cube or an angry piglin walked up to us on a lava rim: hit it first, its first hit is a knock into the lava
        RIM,
        // a creeper we already stepped from back beside us again (CreeperStep.REPEAT_WINDOW): stepping a third time is the
        // loop, leaving is not
        REPEAT_CREEPER
    }

    public enum Event {
        NONE, FIGHT_START, RUN_START,
        // the target died and somebody else was already on us, so no gap
        FIGHT_NEXT,
        FIGHT_DEAD, FIGHT_LOST, FIGHT_STALLED,
        // an in-the-way fight whose mob stepped off, the path is ours again
        FIGHT_CLEARED,
        FIGHT_TO_RUN,
        RUN_CLEAR, RUN_CAP, RUN_STUCK,
        RUN_TO_FIGHT,
        // the cap came with us still hurt and chased: a fight
        RUN_CHASED_FIGHT
    }

    // what sort of foe it is, which is the only thing the machine needs to know about a species
    public enum Kind {
        NORMAL,
        // hovers where we cannot walk up to it (a blaze). it counts as a shooter (FoeRules.shoots), and like any shooter
        // it is only a fight once it is in contact
        FLYER,
        // lands 8 to 13 in one swing (hoglin, brute, vindicator): a run at HEAVY_FLEE_HP instead of FLEE_HP
        HEAVY,
        // not a fight at any hp (warden, wither): whatever fires, it is a run
        UNTOUCHABLE,
        // throws things from where it stands (the witch). a poison that keeps ticking is the hit, so one that hit us is a fight
        // from LOBBER_RANGE and not only in contact: it never walks up to us, and waiting for it is how a minute of poison happens
        LOBBER,
        // angry at us and not worth leaving: it teleports and chases, so a run is just free hits. fought to the end at any hp
        // (the water floor is the chain's, see EndermanWater). a calm one is never a foe at all
        ENDERMAN
    }

    // an angry hostile that can hurt us, as plain numbers. sinceHit: ticks since it hit us (NEVER for not yet)
    // reachGap: eye to the nearest point of its box, the three block reach (ReachRules). infinite when nobody measured
    // it, which is every foe that does not come from the world: the no room rule only believes a gap somebody really took
    // sight: it has a line to us (only asked for shooters that hit us lately, so false is the default and the cheap answer).
    // sinceHitBefore: ticks since the hit before the last one (NEVER for fewer than two), a shooter that keeps hitting us (nothing reads it since the pin window went, it is just carried)
    // fast: it moves at CombatRules.FAST_MOB_SPEED or better (spiders, slimes and magma cubes of any size, piglins, angry zombified piglins), so a run does not shake it
    // splitType: "slime" or "magma_cube" for the cube family, null for everything else. splits: it is big enough to leave splits
    // behind when it dies. x/y/z is where it stands (only read for the family, see splitHeir)
    public record Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind, double reachGap,
                      boolean sight, long sinceHitBefore, boolean fast, String splitType, boolean splits, double x, double y, double z,
                      boolean knocker) {
        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind, double reachGap,
                   boolean sight, long sinceHitBefore, boolean fast) {
            this(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, null, false, 0, 0, 0, false);
        }

        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind, double reachGap,
                   boolean sight, long sinceHitBefore) {
            this(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, false);
        }

        public Foe withFast(boolean fast) {
            return new Foe(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, splitType, splits, x, y, z, knocker);
        }

        // knocker: a melee hit of its shoves us (cubes of any size, an angry zombified piglin), see CombatRules.rimKnocker
        public Foe withKnocker(boolean knocker) {
            return new Foe(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, splitType, splits, x, y, z, knocker);
        }

        public Foe withSplit(String splitType, boolean splits, double x, double y, double z) {
            return new Foe(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, splitType, splits, x, y, z, knocker);
        }

        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind, double reachGap) {
            this(id, distance, ranged, creeper, sinceHit, kind, reachGap, false, NEVER);
        }

        // a plain mob
        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit) {
            this(id, distance, ranged, creeper, sinceHit, Kind.NORMAL);
        }

        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind) {
            this(id, distance, ranged, creeper, sinceHit, kind, Double.POSITIVE_INFINITY);
        }

        // how far away a hit of its counts as "on us" for the fight rules: in contact, or for a lobber where it stands
        public double strikeRange() {
            return kind == Kind.LOBBER ? LOBBER_RANGE : CONTACT;
        }

        // close enough that a swing of ours lands, by the three block number
        public boolean inReach() {
            return reachGap <= ReachRules.MELEE_REACH;
        }

        // never a reason to run, whatever the hp or the headcount
        public boolean standsAndFights() {
            return kind == Kind.ENDERMAN;
        }

        public boolean melee() {
            return !ranged && !creeper;
        }

        public boolean hitUs() {
            return sinceHit <= HIT_MEMORY;
        }

        public boolean fresh() {
            return sinceHit <= FRESH;
        }

        // a plain bow-and-arrow sort of shooter: no lobber (own rule), no hoverer, nothing we only run from
        public boolean sniper() {
            return ranged && !creeper && kind == Kind.NORMAL;
        }

        // it shot us lately and still has a line: at low hp that is as good as standing next to us
        public boolean shooting() {
            return ranged && !creeper && sight && distance <= FOE_RANGE && sinceHit <= SHOT_MEMORY;
        }

        // this one is a run, not a fight, at this hp
        public boolean mustRun(float health) {
            return kind == Kind.UNTOUCHABLE || (kind == Kind.HEAVY && health <= HEAVY_FLEE_HP);
        }

        // something we only ever run from, close enough to matter. the warden: within 8, or booming us from out to 15. a
        // heavy hitter only once it has got to us (hit us from within 8, or in contact): one just standing around in a
        // bastion is walked past like anything else, otherwise the nether is one long jog
        public boolean danger(float health) {
            if (!mustRun(health)) return false;
            if (kind == Kind.UNTOUCHABLE) return distance <= LOW_HP_RANGE || (hitUs() && distance <= BOOM_RANGE);
            return distance <= CONTACT || (hitUs() && distance <= LOW_HP_RANGE);
        }
    }

    // everything one tick of the machine wants to know. target is the foe we are fighting looked up on its own (it can be
    // alive and outside the foe list), null when it is dead or gone. way is the path's side of things. armed: anything better
    // than a fist in the bag (a sword, an axe, or a tool that hits harder than a hand)
    // trying: the run is really trying to get somewhere this tick, see noRoomFight. false by default, so nothing that does not say
    // so can flip a run into a fight with it
    // creeperRepeat: CreeperStep just started a step on a creeper that came back (CreeperStep.escalate), idle makes it a run
    // body: hunger, food and whether a heal-up hold is allowed at all (RecoverHold). NONE by default, which never holds
    // boxed: every side of us is a wall or a mob (BoxRules), so a run has nowhere to go. false by default
    // nextHit: the machine's own guess at the next hit (see NEXT_HIT_WINDOW), filled in by step() from the hp it watches. whatever
    // a caller puts there is replaced, 0 by default
    // watched: a shooter that is not a foe (an uncharged blaze at range is not in the hostile list) has a line to us right now. only the
    // heal-up hold's re-run reads it, so it breaks the line before the meal and not after. false by default
    public record Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying,
                       boolean creeperRepeat, RecoverHold.Body body, boolean boxed, float nextHit, boolean watched) {
        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying,
                    boolean creeperRepeat, RecoverHold.Body body, boolean boxed, float nextHit) {
            this(now, health, x, z, foes, target, way, armed, trying, creeperRepeat, body, boxed, nextHit, false);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying,
                    boolean creeperRepeat, RecoverHold.Body body, boolean boxed) {
            this(now, health, x, z, foes, target, way, armed, trying, creeperRepeat, body, boxed, 0);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying,
                    boolean creeperRepeat, RecoverHold.Body body) {
            this(now, health, x, z, foes, target, way, armed, trying, creeperRepeat, body, false);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying,
                    boolean creeperRepeat) {
            this(now, health, x, z, foes, target, way, armed, trying, creeperRepeat, RecoverHold.Body.NONE);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying) {
            this(now, health, x, z, foes, target, way, armed, trying, false);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed) {
            this(now, health, x, z, foes, target, way, armed, false);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way) {
            this(now, health, x, z, foes, target, way, true, false);
        }

        // no path in the picture
        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target) {
            this(now, health, x, z, foes, target, Way.NONE, true, false);
        }

        // hp is at or under the bail line, counting the next hit
        public boolean low() {
            return health - nextHit <= FLEE_HP;
        }

        // hp as the heavy hitter rules see it. their line is the higher of HEAVY_FLEE_HP and the moving one, so past the 2 it
        // already sits above the flee line the hp slides down by the rest, instead of every call learning a second number
        public float guardHealth() {
            return CombatCommit.guardHealth(health, nextHit);
        }

        Tick withNextHit(float hit) {
            return new Tick(now, health, x, z, foes, target, way, armed, trying, creeperRepeat, body, boxed, hit, watched);
        }

        Tick withNow(long at) {
            return new Tick(at, health, x, z, foes, target, way, armed, trying, creeperRepeat, body, boxed, nextHit, watched);
        }
    }

    // inWay: ids of mobs sitting in the next few path nodes or the block baritone wants to place. pathing: something wants us
    // to move (a path or a search going), y: our height, a pillar out of a hole is progress too
    // weapon: a sword or an axe in the bag (not just a tool), which is what an in the way fight needs. true when nobody asked
    // rim: we stand on a lava rim or a ledge over a drop (LavaShore.edgeNear) with a melee foe close, path or no path
    public record Way(Set<Integer> inWay, boolean pathing, double y, boolean weapon, boolean rim) {
        public static final Way NONE = new Way(Set.of(), false, 0);

        public Way(Set<Integer> inWay, boolean pathing, double y) {
            this(inWay, pathing, y, true);
        }

        public Way(Set<Integer> inWay, boolean pathing, double y, boolean weapon) {
            this(inWay, pathing, y, weapon, false);
        }

        public Way withRim(boolean rim) {
            return new Way(inWay, pathing, y, weapon, rim);
        }
    }

    // what started a commitment: the mob, and the reason (HEAVY and LOW_HP are runs, the foe is just who to name)
    public record Trigger(Why why, Foe foe) {
    }

    // the rule for "does anything here really need dealing with". almost always no: a crowd around us, a skeleton
    // shooting from range, a lit creeper and a zombie ten blocks off are all scenery and the task walks on.
    // freshOnly is the cooldown. danger is never old news, low hp only counts something in contact or a fresh hit, and
    // the fight only a fresh hit.
    // 1. something we never fight at this hp has got to us (a heavy hitter at 10 hp that hit us or is in contact, the
    //    warden within 8 or booming us): a run
    // 2. we are hurt and something angry is within 8 (or a shooter with a line to us has hit us in the last SHOT_MEMORY ticks,
    //    from anywhere): a run. in the cooldown only if it is on top of us or just hit us,
    //    a run that ran out of time or a fight we turned around for ends with the others still about, and "within 8"
    //    alone would start the same run all over again
    // 3. it hit us and it is in contact: a fight. hitting back is the only way that one stops
    public static Trigger trigger(float health, List<Foe> foes, boolean freshOnly) {
        return trigger(health, 0, foes, freshOnly);
    }

    static float guardHealth(float health, float nextHit) {
        return health - Math.max(0, nextHit - (HEAVY_FLEE_HP - FLEE_HP));
    }

    public static Trigger trigger(float health, float nextHit, List<Foe> foes, boolean freshOnly) {
        return trigger(health, nextHit, nextHit, foes, freshOnly);
    }

    // lowHit is what the low hp line projects, nextHit still feeds the heavy hitter guard. they differ in a one on one fight
    // (see lowHit), where the real hp is the line and not the hp after the next swing lands
    static Trigger trigger(float health, float nextHit, float lowHit, List<Foe> foes, boolean freshOnly) {
        return trigger(health, nextHit, lowHit, foes, freshOnly, false);
    }

    // onFight: there is a fight target (see shooterFight)
    static Trigger trigger(float health, float nextHit, float lowHit, List<Foe> foes, boolean freshOnly, boolean onFight) {
        float guard = guardHealth(health, nextHit);
        Foe danger = null;
        Foe near = null;
        for (Foe foe : foes) {
            if (foe.danger(guard) && (danger == null || foe.distance < danger.distance)) danger = foe;
            boolean counts = !freshOnly || foe.distance <= CONTACT || foe.fresh();
            // a shooter with a line that hit us lately is near enough, however far it stands
            if (counts && (foe.distance <= LOW_HP_RANGE || foe.shooting()) && (near == null || foe.distance < near.distance)) near = foe;
        }
        if (danger != null) return new Trigger(Why.HEAVY, danger);
        if (health - lowHit <= FLEE_HP && near != null && !shooterFight(foes, freshOnly, guard, onFight)) return new Trigger(near.distance <= LOW_HP_RANGE ? Why.LOW_HP : Why.SHOT, near);
        Foe hit = hitInContact(foes, freshOnly);
        return hit == null ? null : new Trigger(Why.HIT, hit);
    }

    // the closest thing that hit us and is close enough to swing at
    private static Foe hitInContact(List<Foe> foes, boolean freshOnly) {
        Foe hit = null;
        for (Foe foe : foes) {
            boolean hitCounts = freshOnly ? foe.fresh() : foe.hitUs();
            if (hitCounts && foe.distance <= foe.strikeRange() && (hit == null || foe.distance < hit.distance)) hit = foe;
        }
        return hit;
    }

    // actually on us: it hit us and is within the swarm range, or it is in contact and could hit us (no creepers, those are
    // the creeper step's). bystanders do not count, three zombies milling about at five blocks are scenery as usual
    static boolean engaged(Foe foe, boolean freshOnly) {
        boolean hit = freshOnly ? foe.fresh() : foe.hitUs();
        if (hit && foe.distance() <= CombatRules.SWARM_RANGE) return true;
        return !foe.creeper() && foe.distance() <= CONTACT;
    }

    // one skeleton that is shooting us and at most one melee mob on us: kill the melee one in contact, then walk up to the skeleton and kill that. none
    // of the hp runs (LOW_HP, SHOT) apply to it: in a cave there is no outrunning arrows, every run was just
    // more arrows in the back and the skeleton never died. this deliberately beats the old "bail at hp 8" line for this one shape.
    // two shooters, a creeper close, a third mob on us or anything the danger rules want is not this shape, those keep their runs
    static boolean shooterFight(List<Foe> foes, boolean freshOnly, float guard) {
        return shooterFight(foes, freshOnly, guard, false);
    }

    // onFight: a fight is on, so a sniper that hit us lately still counts as landing arrows when its line flickers or it reloads
    static boolean shooterFight(List<Foe> foes, boolean freshOnly, float guard, boolean onFight) {
        int snipers = 0;
        boolean landing = false;
        int on = 0;
        for (Foe foe : foes) {
            if (foe.danger(guard)) return false;
            if (foe.creeper() && foe.distance() <= LOW_HP_RANGE) return false;
            if (foe.sniper()) {
                snipers++;
                landing |= foe.shooting() || (onFight && foe.hitUs() && foe.distance() <= FOE_RANGE);
            } else if (foe.ranged() && !foe.creeper() && foe.shooting()) {
                // a witch or a blaze with a line on us is its own rule
                return false;
            }
            // (melee only: the skeleton itself counts as engaged once it has hit us, and it is the one we are making room for)
            if (foe.melee() && engaged(foe, freshOnly)) on++;
        }
        return snipers == 1 && landing && on <= 1;
    }

    // too many on us to trade blows with: CROWD_SIZE of them at any hp
    public static boolean crowded(float health, List<Foe> foes, boolean freshOnly) {
        return crowded(health, foes, freshOnly, false);
    }

    static boolean crowded(float health, List<Foe> foes, boolean freshOnly, boolean onFight) {
        int on = 0;
        for (Foe foe : foes) {
            if (engaged(foe, freshOnly)) on++;
        }
        return on >= CROWD_SIZE;
    }

    // what the food chain is up to, for a run's meal: a shooter has a line on us, how far the nearest fast melee mob is (see
    // CombatRules.FAST_MOB_SPEED, infinity for none), and whether a bite is already under way
    // nearestMelee: the closest melee mob of any kind (infinity for none), hungry: the food bar is not full. both only matter to a
    // fight, which may take a bite when nothing can reach us (see fightBite)
    public record Bite(boolean shotAt, double nearestFast, boolean biting, double nearestMelee, boolean hungry) {
        public static final Bite NONE = new Bite(false, Double.POSITIVE_INFINITY, false);

        public Bite(boolean shotAt, double nearestFast, boolean biting) {
            this(shotAt, nearestFast, biting, 0, false);
        }

        Bite notBiting() {
            return new Bite(shotAt, nearestFast, false, nearestMelee, hungry);
        }
    }

    // a fight is no mealtime, except when we are hungry and no melee mob is within FIGHT_BITE_RANGE: the skeleton we are walking up
    // to cannot stop a bite, and regen needs the food bar (hunger 17 with no saturation means no regen at all)
    static final double FIGHT_BITE_RANGE = 4;

    private static boolean fightBite(Bite bite) {
        return bite.hungry() && bite.nearestMelee() > FIGHT_BITE_RANGE;
    }

    // is this foe a reason not to sit down to a meal: a shooter in SHOOTER_RANGE with a line to us right now, or one that already
    // hit us lately and still has a line, however far it stands (the range is for the ones that have not proved anything yet).
    // lineToUs is the ray the caller cast, only asked for the first kind
    public static boolean shotAt(Foe foe, boolean lineToUs) {
        if (!foe.ranged() || foe.creeper()) return false;
        return foe.shooting() || (foe.distance() <= CombatRules.SHOOTER_RANGE && (foe.sight() || lineToUs));
    }

    // fight / flee / eat for the food chain. a fight is never a mealtime. a run is one on a hysteresis pair: a bite may start
    // with nothing angry within RUN_BITE_START (nothing fast within RUN_BITE_START_FAST, nothing shooting at us), and once it
    // has started only a threat inside RUN_BITE_ABORT drops it. a hurt bot that never eats while it runs just bleeds slower,
    // and one line for both made every follower at 9 cancel the bite (the numbers are in CombatRules). the commitment stays
    // through a bite, it does not even hand the wheel over.
    // with nobody committed it is calm unless a lit creeper is close or we are hurt with something near. nearest is the
    // distance to the closest angry thing, infinity for none
    public static CombatRules.Stance stance(Mode mode, float health, double nearest, boolean creeperClose, boolean gapple) {
        return stance(mode, health, nearest, creeperClose, gapple, Bite.NONE);
    }

    public static CombatRules.Stance stance(Mode mode, float health, double nearest, boolean creeperClose, boolean gapple,
                                            Bite bite) {
        return stance(mode, health, nearest, creeperClose, gapple, bite, false);
    }

    // recovering: the heal-up hold is on (RecoverHold). with nothing committed it eats by the run's bite lines, not the calm
    // ones: we are standing about hurt with whatever we ran from somewhere out there
    public static CombatRules.Stance stance(Mode mode, float health, double nearest, boolean creeperClose, boolean gapple,
                                            Bite bite, boolean recovering) {
        return stance(mode, health, nearest, creeperClose, gapple, bite, recovering, true);
    }

    // hasFood: see CombatRules.stance
    public static CombatRules.Stance stance(Mode mode, float health, double nearest, boolean creeperClose, boolean gapple,
                                            Bite bite, boolean recovering, boolean hasFood) {
        boolean fighting = fighting(mode, recovering, health, nearest, creeperClose, bite);
        // chewing a gapple counts as "biting" too, and the abort line is 5: without this the gapple that a hurt run wants at
        // 5 to 10 blocks would be dropped on the next tick and picked up on the one after, forever
        if (!fighting && gapple && mode == Mode.RUN && bite.biting() && health <= FLEE_HP) {
            fighting = fighting(mode, recovering, health, nearest, creeperClose, bite.notBiting());
        }
        // (at gapple hp a fight keeps the gapple stance below, that bite is the better one)
        if (mode == Mode.FIGHT && gapple && health <= FLEE_HP) fighting = true;
        if (!fighting) return CombatRules.Stance.CALM;
        CombatRules.Stance stance = CombatRules.stance(true, health, nearest, gapple, hasFood);
        // the quick bite at hp 4 is for nothing next to us. committed means something is close, so feet first, bite later
        if (stance == CombatRules.Stance.EAT && mode != Mode.NONE) return CombatRules.Stance.FLEE;
        return stance;
    }

    private static boolean fighting(Mode mode, boolean recovering, float health, double nearest, boolean creeperClose, Bite bite) {
        return switch (mode) {
            case FIGHT -> !fightBite(bite);
            case RUN -> runBiteBlocked(nearest, bite);
            default -> creeperClose || (health <= FLEE_HP && nearest <= LOW_HP_RANGE) || (recovering && runBiteBlocked(nearest, bite));
        };
    }

    private static boolean runBiteBlocked(double nearest, Bite bite) {
        return bite.biting() ? nearest < CombatRules.RUN_BITE_ABORT
                : bite.shotAt() || nearest < CombatRules.RUN_BITE_START || bite.nearestFast() < CombatRules.RUN_BITE_START_FAST;
    }

    // why the stance is what it is, for the log line that says so when it changes (and so why a hurt bot is not eating).
    // only ever built when a line is going out
    public static String stanceWhy(Mode mode, float health, double nearest, boolean creeperClose, Bite bite) {
        return stanceWhy(mode, health, nearest, creeperClose, bite, false);
    }

    public static String stanceWhy(Mode mode, float health, double nearest, boolean creeperClose, Bite bite, boolean recovering) {
        String near = Double.isInfinite(nearest) ? "nothing angry in sight" : "nearest " + Math.round(nearest) + " blocks";
        boolean holding = recovering && mode == Mode.NONE;
        if (!fighting(mode, recovering, health, nearest, creeperClose, bite)) {
            if (mode == Mode.FIGHT) return "fighting, no melee mob within " + (int) FIGHT_BITE_RANGE;
            if (holding) return "recovering, " + near + ", free to eat";
            return mode == Mode.RUN ? "running, " + near + ", free to eat" : "calm, " + near;
        }
        // (the lit creeper and the low hp line say their own thing, the rest is the run's bite lines)
        if (holding && !creeperClose && !(health <= FLEE_HP && nearest <= LOW_HP_RANGE)) return "recovering, " + near + ", too close to eat";
        return switch (mode) {
            case FIGHT -> "fighting, no meals mid fight";
            case RUN -> bite.biting() ? "running, " + near + ", too close to keep chewing"
                    : bite.shotAt() ? "running, a shooter has a line on us"
                    : nearest >= CombatRules.RUN_BITE_START ? "running, something fast is too close to start a bite"
                    : "running, " + near + ", too close to eat";
            default -> creeperClose ? "a lit creeper is close"
                    : "hp " + Math.round(health) + " with something within " + (int) LOW_HP_RANGE + ", " + near;
        };
    }

    // ---- state

    private Mode mode = Mode.NONE;
    private Why why = Why.NONE;
    private int targetId = -1;
    // what the target was and where it stood the last time we saw it alive, for the splits it leaves behind (see splitHeir)
    private String lastSplitType;
    private boolean lastSplits;
    // the target was an angry enderman: teleporting out of the foe list is not dying (see targetGone)
    private boolean lastEnder;
    private double lastSplitX;
    private double lastSplitY;
    private double lastSplitZ;
    // when the splitter's target was first seen gone, -1 while it is alive
    private long splitGoneAt = -1;
    private boolean cornered;
    private double originX;
    private double originZ;
    private long startTick;
    private long lastTick = Long.MIN_VALUE;
    // how far step() pulled the clock forward this tick. the foes' sinceHit counts on the raw clock, so a hit's own time is
    // now - sinceHit - skew (or the same hit reads two ticks newer every time the clock slips and counts twice)
    private long clockSkew;
    private float lastHp;
    private final java.util.ArrayDeque<HitSize> hitSizes = new java.util.ArrayDeque<>();
    private long cooldownUntil = Long.MIN_VALUE / 2;
    // the commitment that ended on this step right before the one step() returned started, NONE when nothing chained
    private Event endedFirst = Event.NONE;

    // fight bookkeeping
    private double bestDistance;
    private long progressAt;
    private long farSince = -1;

    // run bookkeeping
    private long clearSince = -1;
    // this run is from shooters: it is over when none has a line to us (LOS_CLEAR_TICKS), not when we are 12 blocks off, and
    // noSightSince is the clock on that
    private boolean losRun;
    private long noSightSince = -1;
    // a run turned into a fight (cornered, chased, no room): no new run starts until nothing is within RUN_CLEAR for
    // RUN_CLEAR_TICKS, the run's own clear test. only the warden and the heavy hitters get past it. without this the fight ended
    // with a cube bouncing out at five blocks and the same tick's low hp rule started a fresh run with a fresh grace
    private boolean runLatch;
    private long latchClearSince = -1;
    // hits landed on us this run after the grace, and per foe the tick of the last one we counted: one hit seen for a hundred
    // ticks is still one hit, the next swing from the same mob is a new one
    private int runHits;
    private final Map<Integer, Long> runHitAt = new HashMap<>();
    private long cornerSince = -1;
    private double cornerX;
    private double cornerZ;
    private long stuckSince = -1;
    private double stuckX;
    private double stuckZ;
    private long noRoomSince = -1;
    private int noRoomCount;
    private double noRoomX;
    private double noRoomZ;
    // the fight we are in came from the no room rule (the log says so)
    private boolean noRoom;
    // this fight stands in for a run (it came from one, or a run was skipped because we are boxed in): when it ends hurt the
    // hold uses the run's hp line, not the fight's
    private boolean runLine;
    // cornered only because we are boxed in right now: when the box opens the normal bail rules are back
    private boolean boxCorner;
    // cornered because a fast mob is on us (not a box): the warden and the heavy hitters still get us out of there
    private boolean fastCorner;
    // since when the melee target has been out of reach (takeover)
    private long outReachSince = -1;

    // the idle boxed-in window (inTheWay)
    private long wayBoxSince = -1;
    private long wayGapSince = -1;
    private double wayBoxX;
    private double wayBoxY;
    private double wayBoxZ;

    // when a fight with a lobber last stalled, for the run that replaces the next fight with it (see LOBBER_GIVE_UP_TICKS)
    private long lobberGaveUpAt = Long.MIN_VALUE / 2;
    // the same for a sniper we went after because it pinned us down. its own clock: a witch we could not reach says nothing
    // about a skeleton
    private long sniperGaveUpAt = Long.MIN_VALUE / 2;
    // this fight is with a ranged foe we chose to go and get (a lobber, or a sniper that is shooting us): a stall or a lost track means we
    // could not get to it, and that is remembered
    private boolean atRange;
    // this fight is on a lobber we picked because it was landing hits when the last target died (lobberNext): only the hard floor
    // and the danger rules leave it, see fight
    private boolean lobberPick;
    // this fight got into contact with its target at some point (a lobber we never touched was out of reach, whatever ended it)
    private boolean contacted;

    // mobs we gave up on, and when. they get another go when they hit us again
    private final Map<Integer, Long> ignored = new HashMap<>();
    // heavy hitters we already ran from for standing in the path, and when. the second time one of them is in the way we walk on
    // (it hits us and that is the HIT fight, or the path goes round) instead of running the same 24 blocks over and over
    private final Map<Integer, Long> ranFrom = new HashMap<>();

    // heal up before going back in: on after a commitment that ended hurt. not a mode, nothing is committed while it holds
    private final RecoverHold recover = new RecoverHold();

    public Mode mode() {
        return mode;
    }

    public Why why() {
        return why;
    }

    public int targetId() {
        return targetId;
    }

    public boolean cornered() {
        return cornered;
    }

    public boolean noRoom() {
        return noRoom;
    }

    public double originX() {
        return originX;
    }

    public double originZ() {
        return originZ;
    }

    public long startTick() {
        return startTick;
    }

    // so the chain can still say how the old one ended when step() hands back the start of the next
    public Event endedFirst() {
        return endedFirst;
    }

    public boolean coolingDown(long now) {
        return now < cooldownUntil;
    }

    public boolean isIgnored(int id) {
        return ignored.containsKey(id);
    }

    // the heal-up hold is on. it outlives a re-run: a run that started from the hold comes back to it
    public boolean recovering() {
        return recover.on();
    }

    public RecoverHold recoverHold() {
        return recover;
    }

    public void reset() {
        lastSplitType = null;
        lastSplits = false;
        lastEnder = false;
        mode = Mode.NONE;
        why = Why.NONE;
        targetId = -1;
        cornered = false;
        lastTick = Long.MIN_VALUE;
        clockSkew = 0;
        hitSizes.clear();
        cooldownUntil = Long.MIN_VALUE / 2;
        endedFirst = Event.NONE;
        runLatch = false;
        latchClearSince = -1;
        farSince = -1;
        losRun = false;
        noSightSince = -1;
        clearSince = -1;
        clearRunHits();
        cornerSince = -1;
        stuckSince = -1;
        noRoomSince = -1;
        noRoom = false;
        runLine = false;
        boxCorner = false;
        fastCorner = false;
        outReachSince = -1;
        wayBoxSince = -1;
        wayGapSince = -1;
        ignored.clear();
        ranFrom.clear();
        lobberGaveUpAt = Long.MIN_VALUE / 2;
        sniperGaveUpAt = Long.MIN_VALUE / 2;
        atRange = false;
        recover.reset();
    }

    // a ranged foe that hit us when we already failed to reach one of its kind lately: running is the answer, a second fight
    // would stall the same (one that has walked into contact is a plain fight, that is the reachable witch)
    private boolean gaveUpOn(Foe foe, long now) {
        long at = foe.kind() == Kind.LOBBER ? lobberGaveUpAt : sniperGaveUpAt;
        boolean ranged = foe.kind() == Kind.LOBBER || foe.sniper();
        long patience = foe.kind() == Kind.LOBBER ? LOBBER_GIVE_UP_TICKS : SNIPER_GIVE_UP_TICKS;
        return ranged && foe.distance() > CONTACT && now >= at && now - at <= patience;
    }

    private void giveUpOn(Foe target, long now) {
        if (target.kind() == Kind.LOBBER) {
            lobberGaveUpAt = now;
        } else {
            sniperGaveUpAt = now;
        }
    }

    // hp that dropped between two steps is a hit, and the biggest of the lately ones is the guess for the next. pure numbers off
    // the hp we are handed, so a poison tick or a fall counts too and ages out in two seconds like the rest
    private float watchHits(Tick t) {
        boolean watching = lastTick != Long.MIN_VALUE && t.now() - lastTick <= HIT_WATCH_GAP;
        if (watching && lastHp - t.health() > 0.01f) hitSizes.addLast(new HitSize(t.now(), lastHp - t.health()));
        lastHp = t.health();
        while (!hitSizes.isEmpty() && t.now() - hitSizes.peekFirst().at() > NEXT_HIT_WINDOW) hitSizes.removeFirst();
        float biggest = 0;
        for (HitSize hit : hitSizes) biggest = Math.max(biggest, hit.size());
        return Math.min(biggest, NEXT_HIT_CAP);
    }

    private record HitSize(long at, float size) {
    }

    public Event step(Tick t) {
        // the server's time packet drags the clock back a tick or two under lag. that is not a new world (CombatBrain catches
        // those), so the clock just stands still instead of wiping a live run and every "since" going negative
        clockSkew = lastTick != Long.MIN_VALUE && t.now() < lastTick ? lastTick - t.now() : 0;
        if (clockSkew > 0) t = t.withNow(lastTick);
        t = t.withNextHit(watchHits(t));
        lastTick = t.now();
        endedFirst = Event.NONE;
        recover.update(t.now(), t.health(), mode != Mode.NONE, t.body());
        tickLatch(t);
        Mode before = mode;
        int oldTarget = targetId;
        Event event = switch (before) {
            case FIGHT -> fight(t);
            case RUN -> run(t);
            default -> idle(t);
        };
        if (before == Mode.NONE || mode != Mode.NONE) return event;
        // it just ended. whatever idle would start next tick starts now: a tick of NONE in between is a tick of the chain
        // letting go of the wheel and the user task walking with the mob still on us
        Event next = idle(afterEnd(t, oldTarget));
        if (next == Event.NONE) return event;
        endedFirst = event;
        return next;
    }

    // the latch lets go once nothing is within RUN_CLEAR for RUN_CLEAR_TICKS, whatever mode we are in
    private void tickLatch(Tick t) {
        if (!runLatch) return;
        double nearest = t.target() != null ? t.target().distance() : Double.POSITIVE_INFINITY;
        for (Foe foe : t.foes()) nearest = Math.min(nearest, foe.distance());
        if (nearest <= RUN_CLEAR) {
            latchClearSince = -1;
        } else if (latchClearSince < 0) {
            latchClearSince = t.now();
        } else if (t.now() - latchClearSince >= RUN_CLEAR_TICKS) {
            runLatch = false;
        }
    }

    // a run reason the latch lets through: the warden and the heavy hitters always, the rest only once the latch is off
    private Why unlatched(Why reason) {
        return runLatch && reason != Why.HEAVY ? Why.NONE : reason;
    }

    // what idle would have seen next tick: no fight target (there is none with nothing committed), and not the target if it
    // just died. a dead one can still be in the foe list for a tick with its fresh hit, same idea as nextTarget skipping it.
    // one we gave up on but is alive stays, ignored does its job and danger is still danger
    private static Tick afterEnd(Tick t, int oldTarget) {
        boolean dead = oldTarget >= 0 && t.target() == null;
        List<Foe> foes = !dead ? t.foes() : t.foes().stream().filter(foe -> foe.id() != oldTarget).toList();
        return new Tick(t.now(), t.health(), t.x(), t.z(), foes, null, t.way(), t.armed(), t.trying(), t.creeperRepeat(),
                t.body(), t.boxed(), t.nextHit(), t.watched());
    }

    // ---- NONE

    private Event idle(Tick t) {
        forgetIgnored(t);
        boolean cooling = coolingDown(t.now());
        // danger is "anything that bad within 8", ignored or not, cooldown or not: leaving is not a fight with that one mob.
        // low hp in the cooldown wants it on top of us or a fresh hit, see trigger
        Why run = unlatched(runReason(t, cooling));
        if (run != Why.NONE) return runOrBox(t, run);
        // cooldown or not: it is not something hitting us again, it is the creeper step going round in circles. idle only, a
        // committed fight is till dead and the step just steps again for it
        if (t.creeperRepeat()) return startRun(t, Why.REPEAT_CREEPER, Event.RUN_START);
        // healing up: whatever comes back near is a run again, before it is a fight and whatever the cooldown says. we are
        // standing here because we are hurt, and trading blows at 10 hp is how the last run started
        if (recover.on() && endermanAbout(t) == null) {
            Why again = unlatched(rerun(t));
            if (again != Why.NONE) return runOrBox(t, again);
        }
        // the fight is only for one we have not given up on, or one that has hit us since. with nothing in hand better than a
        // fist it is a run instead: a punch is not a fight, and the cornered rules still turn us round when a run goes nowhere
        // one that hit us from out there is the target too, not only one in contact: the fight waits for it where it teleports
        Foe ender = endermanOnUs(t);
        if (ender != null) return startFight(t, ender, Why.HIT, Event.FIGHT_START, false);
        Foe hit = hitInContact(candidates(t), cooling);
        if (hit != null) {
            // (no run for these while an enderman is about: it follows, so a swing at what is on us is the better trade)
            boolean enderAbout = endermanAbout(t) != null;
            if (gaveUpOn(hit, t.now()) && !enderAbout) return startRun(t, Why.LOBBER, Event.RUN_START);
            if (!t.armed() && !hit.standsAndFights() && !enderAbout) return startRun(t, Why.UNARMED, Event.RUN_START);
            return startFight(t, hit, Why.HIT, Event.FIGHT_START, false);
        }
        // a cube or an angry piglin close on a rim and no hit yet: its first hit is the knock into the lava, so we hit first
        Foe rim = rimEngage(t, cooling);
        if (rim != null) return startFight(t, rim, Why.RIM, Event.FIGHT_START, false);
        // not in contact, but a skeleton is shooting us. one target and kill it. hurt, or more than one of them, is a run out of
        // their sight instead (the low hp run above already took the hurt case when it was close enough to count)
        List<Foe> shooters = snipers(t, cooling);
        if (!shooters.isEmpty()) {
            Foe sniper = shooters.get(0);
            // (with an enderman about the run is off, a skeleton on us is the armed fight below or nothing)
            boolean hurtRun = t.low() && !(t.armed() && shooterFight(candidates(t), cooling, t.guardHealth()));
            if ((hurtRun || shooters.size() >= 2 || gaveUpOn(sniper, t.now())) && endermanAbout(t) == null) {
                return startRun(t, Why.SHOT, Event.RUN_START);
            }
            // (bare hands do not go and get a skeleton)
            if (t.armed()) return startFight(t, sniper, Why.PINNED, Event.FIGHT_START, false);
        }
        // not a swing yet, but it is the thing in the way. cooldown or not: the cooldown is about not picking a fight again
        // with what is around us, and this one is a path that cannot go on until it is gone
        Foe way = inTheWay(t);
        // a heavy hitter in the path is not a fight we go and pick, at any hp: one swing is 8 to 13 and they come in threes.
        // it is a run, and the sweep keeps a zone round where we ran from (RunZones) so the path does not walk back into it.
        // one that hits us is still the HIT fight above, that is unchanged
        if (way == null && endermanAbout(t) == null) {
            Foe heavy = heavyInTheWay(t);
            if (heavy != null) {
                ranFrom.put(heavy.id(), t.now());
                return startRun(t, Why.HEAVY, Event.RUN_START);
            }
        }
        // (not with bare hands: it would punch the thing down to the flee line. it hits us, and that is the unarmed run above)
        // (and not with a pickaxe either: the way fight is one we pick, it wants a sword or an axe)
        if (way == null || !t.armed() || !t.way().weapon()) return Event.NONE;
        return startFight(t, way, Why.IN_WAY, Event.FIGHT_START, false);
    }

    // the closest knocker within RIM_ENGAGE_RANGE while we stand on a rim. armed, above the flee line, not crowded and not in a
    // run latch (those are the run rules' call, and idle already asked them), and never one we gave up on
    private Foe rimEngage(Tick t, boolean cooling) {
        if (!t.way().rim() || !t.armed() || runLatch || t.health() <= FLEE_HP || crowded(t.health(), t.foes(), cooling)) return null;
        Foe best = null;
        for (Foe foe : candidates(t)) {
            if (!CombatRules.rimKnocker(foe) || foe.distance() > CombatRules.RIM_ENGAGE_RANGE) continue;
            if (foe.mustRun(t.guardHealth()) || gaveUpOn(foe, t.now())) continue;
            if (best == null || foe.distance() < best.distance()) best = foe;
        }
        return best;
    }

    // a run for a crowd or low hp, unless every side of us is a wall or a mob: a run from there is a few seconds of digging
    // with the thing still hitting us, so it is the cornered fight straight away. the rest (heavy hitters, the warden, a creeper
    // that keeps coming back, a lobber) keep their run, the fight would only bail again
    private Event runOrBox(Tick t, Why reason) {
        if (reason == Why.CROWD || reason == Why.LOW_HP) {
            Foe on = t.boxed() ? boxedTarget(t) : null;
            if (on == null) on = fastTarget(t);
            if (on != null) {
                Event event = startFight(t, on, Why.CORNERED, Event.RUN_TO_FIGHT, true);
                runLine = true;
                boxCorner = t.boxed();
                fastCorner = !t.boxed();
                return event;
            }
        }
        return startRun(t, reason, Event.RUN_START);
    }

    // a fast melee mob that has hit us, close, with a weapon in hand to answer it: a run is a jog with it biting our back, so
    // that is the fight. cubes, slimes and spiders move at 0.3 or better (see CombatBrain.fast). in the heal-up hold it is
    // seen from further off: knockback puts the thing past the close range and a re-run from it is the cornered death anyway
    private Foe fastTarget(Tick t) {
        if (!t.armed()) return null;
        double range = recover.on() ? RecoverHold.RERUN_RANGE : FAST_FIGHT_RANGE;
        Foe best = null;
        for (Foe foe : t.foes()) {
            if (!foe.fast() || !foe.melee() || !foe.hitUs() || foe.distance() > range || foe.kind() == Kind.UNTOUCHABLE) continue;
            if (foe.mustRun(t.guardHealth()) || gaveUpOn(foe, t.now()) || stillIgnored(foe, t.now())) continue;
            if (best == null || foe.distance() < best.distance()) best = foe;
        }
        return best;
    }

    // who we swing at when there is nowhere to go: whatever is in contact, a melee foe first. never the warden, never one we only
    // run from, never a creeper (that is the creeper step's)
    private Foe boxedTarget(Tick t) {
        Foe best = null;
        for (Foe foe : t.foes()) {
            if (foe.distance() > CONTACT || foe.creeper() || foe.kind() == Kind.UNTOUCHABLE || foe.mustRun(t.guardHealth())) continue;
            if (gaveUpOn(foe, t.now()) || stillIgnored(foe, t.now())) continue;
            if (best == null || (foe.melee() && !best.melee()) || (foe.melee() == best.melee() && foe.distance() < best.distance())) best = foe;
        }
        return best;
    }

    // the low hp rule's foes, minus the ones we gave up on: anything within RERUN_RANGE, or a shooter with a line that just hit us
    private Why rerun(Tick t) {
        boolean near = false;
        boolean shot = false;
        // (the shooter fight is not run from while healing either, the skeleton is the next kill. a watched blaze still counts)
        if (t.armed() && shooterFight(candidates(t), false, t.guardHealth())) return t.watched() ? Why.SHOT : Why.NONE;
        for (Foe foe : candidates(t)) {
            if (foe.standsAndFights() || gaveUpOn(foe, t.now())) continue;
            if (foe.distance() <= RecoverHold.RERUN_RANGE) near = true;
            if (foe.shooting() && foe.fresh()) shot = true;
        }
        if (near) return Why.LOW_HP;
        // a blaze with a line that has not hit us yet counts too: the meal is a long stand in its sights
        return shot || t.watched() ? Why.SHOT : Why.NONE;
    }

    // skeletons with a line to us that hit us lately, nearest first. in the cooldown only a fresh hit counts
    private List<Foe> snipers(Tick t, boolean freshOnly) {
        return candidates(t).stream()
                .filter(foe -> foe.sniper() && foe.shooting() && (!freshOnly || foe.fresh()))
                .sorted(java.util.Comparator.comparingDouble(Foe::distance))
                .toList();
    }

    // the closest one in contact that is in the path, or any in contact when the path has gone nowhere for 1.5 s. never a
    // creeper (a lit one is CreeperStep's, an unlit one in the way is not worth the swing and the bang), never one we only
    // run from, never one we gave up on
    private Foe inTheWay(Tick t) {
        Foe close = null;
        Foe blocking = null;
        for (Foe foe : candidates(t)) {
            // (no shooters: they back off on their own, so in the way at 3 and gone at 5 is a fight, a clear, and the same
            // fight again a few steps later, and they never block a cell for long anyway)
            if (foe.distance() > CONTACT || !foe.melee() || foe.mustRun(t.guardHealth())) continue;
            // (heavy hitters are heavyInTheWay's, a run. not a swing at any hp, and not the boxed in pick either)
            if (foe.kind() == Kind.HEAVY) continue;
            if (close == null || foe.distance() < close.distance()) close = foe;
            if (t.way().inWay().contains(foe.id()) && (blocking == null || foe.distance() < blocking.distance())) blocking = foe;
        }
        if (blocking != null) {
            wayBoxSince = -1;
            return blocking;
        }
        // boxed in: the path wants to go somewhere and has not got 1 block anywhere (up counts) with one of them on us
        if (t.way().pathing()) {
            wayGapSince = -1;
        } else if (wayGapSince < 0) {
            wayGapSince = t.now();
        }
        boolean gaveUp = wayGapSince >= 0 && t.now() - wayGapSince >= WAY_GAP_TICKS;
        if (close == null || gaveUp) {
            wayBoxSince = -1;
            return null;
        }
        if (!t.way().pathing()) return null;
        double y = t.way().y();
        if (wayBoxSince < 0 || Math.sqrt(sq(t.x() - wayBoxX) + sq(y - wayBoxY) + sq(t.z() - wayBoxZ)) >= WAY_PROGRESS) {
            wayBoxSince = t.now();
            wayBoxX = t.x();
            wayBoxY = y;
            wayBoxZ = t.z();
            return null;
        }
        if (t.now() - wayBoxSince < WAY_BOXED_TICKS) return null;
        wayBoxSince = -1;
        return close;
    }

    // a heavy hitter in contact that sits in the next path nodes. same filter as inTheWay, minus the boxed in half: boxed in
    // with one on us is the cornered rules' business
    private Foe heavyInTheWay(Tick t) {
        for (Foe foe : candidates(t)) {
            if (foe.kind() != Kind.HEAVY || foe.distance() > CONTACT || !foe.melee() || !t.way().inWay().contains(foe.id())) continue;
            // (a run we already made for this one, lately: not again. RunZones is what keeps the sweep's path off it)
            Long ran = ranFrom.get(foe.id());
            if (ran != null && t.now() >= ran && t.now() - ran < RunZones.EXPIRY_TICKS) continue;
            return foe;
        }
        return null;
    }

    private static double sq(double v) {
        return v * v;
    }

    // what makes a fight in progress (or one about to start) a run instead, NONE for fit to fight. the same danger rules
    // as trigger(), plus the fight target itself (it can be outside the foe list and still be the thing to leave)
    private Why runReason(Tick t, boolean freshOnly) {
        return runReason(t, freshOnly, t.nextHit());
    }

    // a committed fight against one melee mob is not left for the hp it would have after the next hit: a 4 damage hitter
    // would send us off at 12 hp, and the run is the thing that gets us hit in the back. the real hp is the line there,
    // a second melee mob near, a shooter with a line on us, and everything not yet fighting, keep the projected one
    private static float lowHit(Tick t) {
        Foe target = t.target();
        if (target == null || !target.melee() || target.standsAndFights()) return t.nextHit();
        for (Foe foe : t.foes()) {
            if (foe.id() == target.id()) continue;
            if (foe.shooting() || (foe.melee() && foe.distance() <= LOW_HP_RANGE)) return t.nextHit();
        }
        return 0;
    }

    private Why runReason(Tick t, boolean freshOnly, float lowHit) {
        return runReason(t, freshOnly, lowHit, t.target() != null);
    }

    // onFight: a fight is on even when the target was taken out of the picture (see shooterFight)
    private Why runReason(Tick t, boolean freshOnly, float lowHit, boolean onFight) {
        Foe target = t.target();
        // (the same "has it got to us" rule as for anything else, the fight target just gets asked too)
        if (target != null && target.danger(t.guardHealth())) return Why.HEAVY;
        // an angry enderman is never a reason to leave (low hp, crowd or otherwise), so the rules below do not get to see it
        List<Foe> foes = leavable(t.foes());
        Trigger trigger = trigger(t.health(), t.nextHit(), lowHit, foes, freshOnly, onFight);
        // and with one about, nothing else is either: it teleports onto us, so a run is just free hits. only the warden and the heavy
        // hitters (the danger rules) still get us out of there
        if (endermanAbout(t) != null) return trigger != null && trigger.why() == Why.HEAVY ? Why.HEAVY : Why.NONE;
        if (trigger != null && trigger.why() != Why.HIT) return trigger.why();
        if (target != null && !target.standsAndFights() && t.health() - lowHit <= FLEE_HP && target.distance() <= LOW_HP_RANGE
                && !shooterFight(foes, freshOnly, t.guardHealth(), onFight)) return Why.LOW_HP;
        // a crowd on us is not a fight to take, at any hp. only the ones actually on us count (engaged), never bystanders
        if (crowded(t.health(), foes, freshOnly, onFight)) return Why.CROWD;
        return Why.NONE;
    }

    // the nearest angry enderman within FOE_RANGE, the fight target counted too (it can be outside the foe list). hitUsOnly: only
    // one that has hit us lately, which is what starts or takes over a fight (a merely angry one is no reason to start swinging, it
    // comes to us). skipIgnored: not one we wrote off after it stalled or wandered off, unless it hit us since
    private Foe nearestEnderman(Tick t, boolean hitUsOnly, boolean skipIgnored) {
        Foe best = null;
        Foe target = t.target();
        if (target != null && target.standsAndFights() && target.distance() <= FOE_RANGE && (!hitUsOnly || target.hitUs())) best = target;
        for (Foe foe : t.foes()) {
            if (!foe.standsAndFights() || foe.distance() > FOE_RANGE || (hitUsOnly && !foe.hitUs())) continue;
            if (skipIgnored && stillIgnored(foe, t.now())) continue;
            if (best == null || foe.distance() < best.distance()) best = foe;
        }
        return best;
    }

    // what holds the runs back: an angry one nearby counts even after we wrote it off, it is still about
    private Foe endermanAbout(Tick t) {
        return nearestEnderman(t, false, false);
    }

    // what a run turns round for: one we have not written off
    private Foe endermanToFight(Tick t) {
        return nearestEnderman(t, false, true);
    }

    private Foe endermanOnUs(Tick t) {
        return nearestEnderman(t, true, true);
    }

    // the foes a run can be about: everyone but the ones we stand and fight
    static List<Foe> leavable(List<Foe> foes) {
        for (Foe foe : foes) {
            if (foe.standsAndFights()) return foes.stream().filter(f -> !f.standsAndFights()).toList();
        }
        return foes;
    }

    // the same tick with one foe gone from the list and no fight target, for asking what the rest of them would say
    private static Tick withoutFoe(Tick t, int id) {
        List<Foe> foes = t.foes().stream().filter(foe -> foe.id() != id).toList();
        return new Tick(t.now(), t.health(), t.x(), t.z(), foes, null, t.way(), t.armed(), t.trying(), t.creeperRepeat(),
                t.body(), t.boxed(), t.nextHit(), t.watched());
    }

    private static boolean anyDanger(Tick t) {
        if (t.target() != null && t.target().danger(t.guardHealth())) return true;
        for (Foe foe : t.foes()) {
            if (foe.danger(t.guardHealth())) return true;
        }
        return false;
    }

    // the foes minus the ones we gave up on (unless they have hit us since)
    private List<Foe> candidates(Tick t) {
        if (ignored.isEmpty()) return t.foes();
        return t.foes().stream().filter(foe -> !stillIgnored(foe, t.now())).toList();
    }

    private boolean stillIgnored(Foe foe, long now) {
        Long at = ignored.get(foe.id());
        if (at == null) return false;
        // the last hit is at or before the moment we gave up on it: no news
        return foe.sinceHit() >= now - at;
    }

    private void forgetIgnored(Tick t) {
        if (ignored.isEmpty()) return;
        ignored.entrySet().removeIf(e -> t.now() - e.getValue() > 6000 || t.now() < e.getValue());
    }

    // ---- FIGHT

    private Event fight(Tick t) {
        Foe target = t.target();
        // the box opened (the zombie in the only gap died, we got knocked out): no longer the cornered fight, so the bail rules
        // below are asked again
        if (boxCorner && !t.boxed()) {
            boxCorner = false;
            cornered = false;
            if (why == Why.CORNERED) why = Why.HIT;
        }
        // the bail-outs, and the only ones: the danger rules. a wobble above 8 is a fight, and so is hp 8 with nothing
        // within 8 (the last thing we were hitting died, that is a won fight, not a reason to run 24 blocks). boxed in
        // there is nowhere to bail to, so a cornered fight never asks
        // a fight we turned around for is out in the open though: the warden or a heavy walking up is still a run
        Why bail = !cornered ? runReason(t, false, lowHit(t)) : (why == Why.CHASED || fastCorner) && anyDanger(t) ? Why.HEAVY : Why.NONE;
        // the witch we picked over the zombies is not walked away from at 11 hp for being a witch (that is how it lobs for a
        // minute while we hit zombies): the hp line is the hard one, at or under it the run is on again
        // (only the part the witch itself causes: a second skeleton or a crowd still ends it, one skeleton is the shooter fight)
        // a second skeleton on us is not the one-target fight any more
        if (bail == Why.NONE && why == Why.PINNED && snipers(t, false).size() >= 2 && endermanAbout(t) == null) bail = Why.SHOT;
        if (lobberPick && endermanAbout(t) == null) {
            if ((bail == Why.LOW_HP || bail == Why.SHOT) && t.health() > FLEE_HP) bail = runReason(withoutFoe(t, targetId), false, t.nextHit(), true);
            if (bail == Why.NONE && t.health() <= FLEE_HP) bail = Why.LOW_HP;
        }
        bail = unlatched(bail);
        if (bail != Why.NONE) {
            // boxed in, a crowd or low hp is no reason to leave: there is no leaving. this fight is the cornered one from here.
            // the same goes for something fast that has hit us and is close: it comes along on the run
            boolean stay = (bail == Why.CROWD || bail == Why.LOW_HP) && (t.boxed() || fastTarget(t) != null);
            if (!stay) return startRun(t, bail, Event.FIGHT_TO_RUN);
            cornered = true;
            runLine = true;
            boxCorner = t.boxed();
            fastCorner = !t.boxed();
            runLatch = true;
            latchClearSince = -1;
            if (why != Why.CHASED) why = Why.CORNERED;
        }
        if (target != null) noteSplit(target);
        if (target == null) return targetGone(t);
        // an enderman that hit us is the target over whatever we are on, it is the one that cannot be walked away from
        Foe ender = endermanOnUs(t);
        if (ender != null && ender.id() != target.id() && (!target.standsAndFights() || !target.hitUs())) {
            return startFight(t, ender, why == Why.PINNED ? Why.HIT : why, Event.FIGHT_NEXT, cornered);
        }
        Foe swap = takeover(t, target);
        if (swap != null) return startFight(t, swap, why == Why.PINNED ? Why.HIT : why, Event.FIGHT_NEXT, cornered);
        // it stepped out of the way and is not on us: the path goes on. (one that hit us since is a normal fight now and
        // goes by the normal rules below)
        // (the rim fight lets go at the same four blocks, one past the three it starts at, so it does not flip back on)
        if ((why == Why.IN_WAY || why == Why.RIM) && target.distance() > WAY_RELEASE && !target.hitUs()) return end(t, Event.FIGHT_CLEARED);
        // out of play: wandered off past LOST_RANGE and not hitting us. if it wants another go, that is a new fight
        // (not a sniper we are still walking up to: it is far by design, an arrow in the air or a miss is no reason to lose it
        // after three seconds, only the stall rule below writes that one off)
        if (target.distance() > LOST_RANGE && !(why == Why.PINNED && !contacted)) {
            if (farSince < 0) farSince = t.now();
            if (t.now() - farSince >= LOST_TICKS && target.sinceHit() > LOST_TICKS) {
                // boxed in, the second tier picks within 8, so a lost one would be picked again every few seconds. same
                // deal as a stall: written off until it hits us again
                if (cornered || target.standsAndFights()) ignored.put(target.id(), t.now());
                // a ranged foe we never reached and lost track of is the same story as one that stalled: the poison goes on
                if (atRange && !contacted) giveUpOn(target, t.now());
                return end(t, Event.FIGHT_LOST);
            }
        } else {
            farSince = -1;
        }
        // getting anywhere? contact is the best kind of progress, closing in counts, running off just moves the baseline
        double d = target.distance();
        if (d <= CONTACT) {
            contacted = true;
            bestDistance = d;
            progressAt = t.now();
        } else if (d < bestDistance - PROGRESS) {
            bestDistance = d;
            progressAt = t.now();
        } else if (d > bestDistance + RUNAWAY_SLACK) {
            bestDistance = d;
        }
        if (t.now() - progressAt >= STALL_TICKS) {
            ignored.put(target.id(), t.now());
            if (atRange) giveUpOn(target, t.now());
            return end(t, Event.FIGHT_STALLED);
        }
        return Event.NONE;
    }

    // the one we are swinging at is not the one hitting us. a shooter target with a melee foe that hit us in reach: that foe,
    // once. a melee target that has been out of reach for TAKEOVER_TICKS with such a foe in reach: that foe. anything else stays,
    // the first kill matters most and two foes taking turns must not make us ping-pong
    private Foe takeover(Tick t, Foe target) {
        // (the enderman comes to us, standing for it is the fight. swapping to whatever else is in reach would just swap back)
        if (target.standsAndFights()) return null;
        // the witch we picked over the zombies stays picked: swapping straight back to the one biting us is the loop this pick
        // exists to end. boxed in we cannot walk to it anyway, so the swing at what is on us is the old rule's
        if (lobberPick && !t.boxed()) return null;
        boolean shooter = target.ranged() && !target.creeper();
        if (!shooter && !target.melee()) {
            outReachSince = -1;
            return null;
        }
        if (!shooter) {
            if (target.inReach()) {
                outReachSince = -1;
                return null;
            }
            if (outReachSince < 0) outReachSince = t.now();
            if (t.now() - outReachSince < TAKEOVER_TICKS) return null;
        }
        Foe best = null;
        for (Foe foe : t.foes()) {
            if (foe.id() == target.id() || !foe.melee() || foe.kind() == Kind.UNTOUCHABLE || foe.mustRun(t.guardHealth())) continue;
            if (!foe.hitUs() || !foe.inReach() || gaveUpOn(foe, t.now()) || stillIgnored(foe, t.now())) continue;
            if (best == null || foe.reachGap() < best.reachGap()) best = foe;
        }
        return best;
    }

    // the target died or left the world. if somebody else is already on us, carry straight on (a gap here is a tick of
    // the user task walking with a zombie in our face), otherwise it is over
    private Event targetGone(Tick t) {
        // (a chased fight stays CHASED, it is the same reason we turned around)
        Why boxed = why == Why.CHASED ? Why.CHASED : Why.CORNERED;
        Foe lobber = lobberNext(t);
        if (lobber != null) {
            Event event = startFight(t, lobber, cornered ? boxed : Why.HIT, Event.FIGHT_NEXT, cornered);
            lobberPick = true;
            return event;
        }
        Foe heir = splitHeir(t);
        if (heir != null) return startFight(t, heir, cornered ? boxed : Why.HIT, Event.FIGHT_NEXT, cornered);
        Foe next = nextTarget(t);
        if (next != null) return startFight(t, next, cornered ? boxed : Why.HIT, Event.FIGHT_NEXT, cornered);
        // a boxed in fight never asked the bail rules, so the warden standing on us when the last thing dies is still ours to
        // leave: a gap and a five second cooldown next to it is not a plan
        if (cornered && anyDanger(t)) return startRun(t, Why.HEAVY, Event.FIGHT_TO_RUN);
        // a cube that splits spawns its children when the body is removed, about a second after the last hit (the death animation).
        // ending the fight here hands the wheel to whatever walks next, and the children show up behind it. a short wait, then over
        if (lastSplits) {
            if (splitGoneAt < 0) splitGoneAt = t.now();
            if (t.now() - splitGoneAt <= SPLIT_WAIT_TICKS) return Event.NONE;
        }
        // an angry enderman that blinked away is not a dead one: it comes back angry and hits for 3.8 a second. the fight
        // waits for it (same clock as the split wait), and an enderman that shows up inside the wait is the same fight
        if (lastEnder) {
            if (splitGoneAt < 0) splitGoneAt = t.now();
            Foe back = endermanToFight(t);
            if (back != null) return startFight(t, back, cornered ? boxed : Why.HIT, Event.FIGHT_NEXT, cornered);
            if (t.now() - splitGoneAt <= ENDER_WAIT_TICKS) return Event.NONE;
        }
        return end(t, Event.FIGHT_DEAD);
    }

    // a lobber that has been landing hits on us just now, from where it can see us, when the last target dies: it is the next one, ahead
    // of whatever zombie is nearest. the old order (nearest in reach first, a run for anything lobbing at low hp after) left us
    // trading blows with zombies at 11 hp while it threw from 10 blocks away. only at this moment: a live target is never dropped
    // for it. at or under the hp line it is not a fight to pick (the low hp run has it), and not bare handed
    private Foe lobberNext(Tick t) {
        // (boxed in there is no walking to it, the heavy hitter or the warden is a run and not a pick, see targetGone)
        if (!t.armed() || t.health() <= FLEE_HP || t.boxed() || anyDanger(t)) return null;
        Foe best = null;
        for (Foe foe : t.foes()) {
            if (foe.id() == targetId || foe.kind() != Kind.LOBBER || foe.sinceHit() > LOBBER_PICK_TICKS || !foe.sight() || foe.distance() > LOBBER_RANGE) continue;
            if (gaveUpOn(foe, t.now()) || stillIgnored(foe, t.now())) continue;
            if (best == null || foe.distance() < best.distance()) best = foe;
        }
        return best;
    }

    // how far from the corpse a split can spawn and still be the same fight. they pop out within about a block, 3 is the margin
    static final double SPLIT_RANGE = 3.0;
    // the death animation is 20 ticks, the rest is margin
    static final long SPLIT_WAIT_TICKS = 30;
    // a teleport and the walk back is a few seconds at most, three is plenty
    static final long ENDER_WAIT_TICKS = 60;

    private void noteSplit(Foe foe) {
        lastSplitType = foe.splitType();
        lastSplits = foe.splits();
        lastEnder = foe.standsAndFights();
        lastSplitX = foe.x();
        lastSplitY = foe.y();
        lastSplitZ = foe.z();
        splitGoneAt = -1;
    }

    // a slime or magma cube just died: the same kind next to the corpse is its split and the same fight. they have not hit us yet,
    // so the "must have hit us" rule of nextTarget loses them, the fight ends, and whatever walks next walks past a cube that is
    // about to bite (on a ledge, that bite is a knock into the drop)
    private Foe splitHeir(Tick t) {
        if (lastSplitType == null) return null;
        Foe best = null;
        double bestGap = Double.MAX_VALUE;
        for (Foe foe : t.foes()) {
            if (foe.id() == targetId || !lastSplitType.equals(foe.splitType()) || !foe.melee() || foe.kind() == Kind.UNTOUCHABLE
                    || foe.mustRun(t.guardHealth()) || ignored.containsKey(foe.id()) || gaveUpOn(foe, t.now())) continue;
            double gap = Math.sqrt(Math.pow(foe.x() - lastSplitX, 2) + Math.pow(foe.y() - lastSplitY, 2) + Math.pow(foe.z() - lastSplitZ, 2));
            if (gap <= SPLIT_RANGE && gap < bestGap) {
                best = foe;
                bestGap = gap;
            }
        }
        return best;
    }

    private Foe nextTarget(Tick t) {
        Foe best = null;
        if (cornered) {
            // still boxed in: whatever is touching us and has hit us (bar the one thing that is never a fight, and creepers: that
            // is the creeper step's, a fresh zombie standing next to us is not a reason to keep swinging)
            for (Foe foe : t.foes()) {
                if (foe.id() != targetId && foe.kind() != Kind.UNTOUCHABLE && !foe.creeper() && foe.hitUs() && foe.distance() <= CORNER_RANGE
                        && (best == null || foe.distance() < best.distance())) best = foe;
            }
            return best;
        }
        // the next thing chewing on us, same rule as starting one. (not one we gave up on, idle sorts that out once this fight
        // is over, and not one we only run from, runReason already had its say)
        List<Foe> rest = candidates(t).stream()
                .filter(foe -> foe.id() != targetId && !ignored.containsKey(foe.id()) && !foe.mustRun(t.guardHealth()) && !gaveUpOn(foe, t.now()))
                .toList();
        return hitInContact(rest, false);
    }

    private Event startFight(Tick t, Foe foe, Why reason, Event event, boolean boxedIn) {
        // a new fight with a mob we once gave up on is a clean slate with it (it hit us since, or we are boxed in with it)
        ignored.remove(foe.id());
        fastCorner = false;
        noRoom = false;
        outReachSince = -1;
        // (a chain on a fight keeps where it came from)
        if (event != Event.FIGHT_NEXT) {
            runLine = event == Event.RUN_TO_FIGHT;
            boxCorner = false;
        }
        if (event == Event.RUN_TO_FIGHT || event == Event.RUN_CHASED_FIGHT) {
            runLatch = true;
            latchClearSince = -1;
        }
        contacted = false;
        lobberPick = false;
        atRange = foe.kind() == Kind.LOBBER || reason == Why.PINNED;
        mode = Mode.FIGHT;
        why = reason;
        targetId = foe.id();
        noteSplit(foe);
        cornered = boxedIn;
        startTick = t.now();
        bestDistance = foe.distance();
        progressAt = t.now();
        farSince = -1;
        wayBoxSince = -1;
        return event;
    }

    // ---- RUN

    private Event startRun(Tick t, Why reason, Event event) {
        mode = Mode.RUN;
        why = reason;
        targetId = -1;
        cornered = false;
        originX = t.x();
        originZ = t.z();
        startTick = t.now();
        clearSince = -1;
        noSightSince = -1;
        losRun = (reason == Why.SHOT || reason == Why.LOW_HP) && t.foes().stream().anyMatch(foe -> foe.sniper() && foe.shooting())
                && t.foes().stream().noneMatch(foe -> foe.distance() <= LOW_HP_RANGE && (!foe.ranged() || foe.creeper()));
        clearRunHits();
        cornerSince = -1;
        stuckSince = -1;
        noRoomSince = -1;
        noRoom = false;
        return event;
    }

    // a run that is not moving us with a melee foe in reach: nowhere to run, so this is the cornered fight already. the same
    // exit as the corner window below, only quicker, and it needs a gap somebody measured (see Foe.reachGap)
    private Event noRoomFight(Tick t) {
        Foe onUs = null;
        for (Foe foe : t.foes()) {
            if (foe.melee() && foe.kind() != Kind.UNTOUCHABLE && foe.inReach() && (onUs == null || foe.distance() < onUs.distance())) onUs = foe;
        }
        if (onUs == null) {
            noRoomSince = -1;
            return Event.NONE;
        }
        // (not trying: paused, the count and the anchor stay where they were)
        if (!t.trying()) return Event.NONE;
        if (noRoomSince < 0 || Math.hypot(t.x() - noRoomX, t.z() - noRoomZ) >= NO_ROOM_BLOCKS) {
            noRoomSince = t.now();
            noRoomCount = 1;
            noRoomX = t.x();
            noRoomZ = t.z();
        } else {
            noRoomCount++;
        }
        if (noRoomCount < NO_ROOM_TICKS) return Event.NONE;
        Event event = startFight(t, onUs, Why.CORNERED, Event.RUN_TO_FIGHT, true);
        noRoom = true;
        return event;
    }

    private Event run(Tick t) {
        // an angry enderman about turns any run into the fight: it teleports onto us, nothing outruns it. the warden still wins
        Foe ender = endermanToFight(t);
        // (a run from the warden or a heavy hitter is not ours to turn, it ends where it ends: that mob is still about once we are past 8)
        if (ender != null && why != Why.HEAVY && !anyDanger(t)) return startFight(t, ender, Why.CHASED, Event.RUN_CHASED_FIGHT, false);
        double fromOrigin = Math.hypot(t.x() - originX, t.z() - originZ);
        double nearest = Double.POSITIVE_INFINITY;
        for (Foe foe : t.foes()) {
            nearest = Math.min(nearest, foe.distance());
        }
        // clear means nothing angry within RUN_CLEAR. the clock is on whether or not we are far enough yet, so the moment
        // we are far enough the answer is already in
        if (nearest <= RUN_CLEAR) {
            clearSince = -1;
        } else if (clearSince < 0) {
            clearSince = t.now();
        }
        if (t.now() - startTick >= RUN_CAP) return capped(t);
        if (losRun) {
            // the line is the whole point: out of sight is out of range, however few blocks that took
            boolean seen = t.foes().stream().anyMatch(foe -> foe.ranged() && !foe.creeper() && foe.sight());
            if (seen) {
                noSightSince = -1;
            } else if (noSightSince < 0) {
                noSightSince = t.now();
            } else if (t.now() - noSightSince >= LOS_CLEAR_TICKS) {
                return end(t, Event.RUN_CLEAR);
            }
        } else if (fromOrigin >= RUN_DISTANCE && clearSince >= 0 && t.now() - clearSince >= RUN_CLEAR_TICKS) return end(t, Event.RUN_CLEAR);

        // running is not shaking it: it keeps landing hits, in contact or not. armed, that is a fight (the same boxed in fight
        // as the cap's, so it finishes this target and cannot flip back to a run on the next tick). unarmed, a punch is still
        // not a fight, the run goes on and the corner and cap rules decide
        Event hitThrough = hitThrough(t);
        if (hitThrough != Event.NONE) return hitThrough;

        Event cramped = noRoomFight(t);
        if (cramped != Event.NONE) return cramped;

        if (nearest > CORNER_WATCH) {
            cornerSince = -1;
            return stuck(t);
        }
        // something is close, the corner window below is the one that counts and this one starts over when it leaves
        stuckSince = -1;
        if (cornerSince < 0 || Math.hypot(t.x() - cornerX, t.z() - cornerZ) >= CORNER_PROGRESS) {
            cornerSince = t.now();
            cornerX = t.x();
            cornerZ = t.z();
            return Event.NONE;
        }
        // on the spot for three seconds with something on top of us, or six with something anywhere within the watch range
        long stuck = t.now() - cornerSince;
        if (stuck < CORNER_TICKS || (nearest > CORNER_RANGE && stuck < CORNER_FAR_TICKS)) return Event.NONE;
        // nowhere to go and it is close: armed or not, hurt or not, this is a fight. never a creeper (the creeper step's). on top
        // of us anything else will do, further out only something we could walk up to and hit and have not given up on (a skeleton across water that
        // stalled a fight would be picked again every six seconds, with a run in between, for as long as we stood there).
        // the warden is the exception to "anything will do": never a fight in any state, boxed in or not
        Foe closest = null;
        for (Foe foe : t.foes()) {
            if (foe.distance() > CORNER_WATCH || foe.kind() == Kind.UNTOUCHABLE) continue;
            if (foe.creeper() || (foe.distance() > CORNER_RANGE && ignored.containsKey(foe.id()))) continue;
            if (closest == null || foe.distance() < closest.distance()) closest = foe;
        }
        // nobody worth a fight: the run goes on, the cap or the mob settles it
        if (closest == null) return Event.NONE;
        return startFight(t, closest, Why.CORNERED, Event.RUN_TO_FIGHT, true);
    }

    // the cap with nothing on us is just the end of a run. hurt with something still inside 8, ending it only meant a fresh
    // run with a fresh origin on the very next tick, forever. so turn around and fight. bleeding out at a jog is the worst way to go
    private Event capped(Tick t) {
        if (!chased(t)) return end(t, Event.RUN_CAP);
        Foe chaser = chaser(t);
        // nobody to turn on: the warden (danger starts the next run on the same step), or only a creeper at range or mobs we
        // gave up on (the run just ends, the cooldown waits for one of them to get on us or hit us)
        // and with danger about a fight would only bail again on the next step, so it is the next run straight away
        if (chaser == null || anyDanger(t)) return end(t, Event.RUN_CAP);
        return startFight(t, chaser, Why.CHASED, Event.RUN_CHASED_FIGHT, true);
    }

    // RUN_HITS hits after the grace and the run is not working. armed with nothing we only run from about, turn on it (boxed
    // in rules). no pick this tick keeps the count, the next tick asks again
    private Event hitThrough(Tick t) {
        countRunHits(t);
        if (runHits < RUN_HITS || !t.armed() || anyDanger(t)) return Event.NONE;
        Foe pick = hitPick(t);
        if (pick == null) return Event.NONE;
        return startFight(t, pick, Why.CHASED, Event.RUN_CHASED_FIGHT, true);
    }

    // a hit counts wherever the hitter is within SWARM_RANGE, not just in contact: the tick we hear about a hit is the tick it
    // knocked us back, so the mob that did it is three or four blocks off by then. never one we only run from (the warden is
    // in there), never one we gave up on and that has not hit us since
    private void countRunHits(Tick t) {
        for (Foe foe : t.foes()) {
            if (!foe.hitUs() || !foe.melee() || foe.distance() > CombatRules.SWARM_RANGE) continue;
            if (foe.mustRun(t.guardHealth()) || stillIgnored(foe, t.now())) continue;
            long at = t.now() - foe.sinceHit() - clockSkew;
            if (at <= startTick + RUN_HIT_GRACE) continue;
            Long counted = runHitAt.get(foe.id());
            if (counted != null && at <= counted) continue;
            runHitAt.put(foe.id(), at);
            runHits++;
        }
    }

    // who the hits turn us on: the closest melee foe in contact, else the closest one that landed a counted hit this run and is
    // still within 8. same "never one we only run from, no creeper, nothing we gave up on" as chaser()
    private Foe hitPick(Tick t) {
        Foe onUs = null;
        Foe hitter = null;
        for (Foe foe : t.foes()) {
            if (!foe.melee() || foe.mustRun(t.guardHealth()) || gaveUpOn(foe, t.now()) || stillIgnored(foe, t.now())) continue;
            if (foe.distance() <= CONTACT && (onUs == null || foe.distance() < onUs.distance())) onUs = foe;
            if (foe.distance() <= LOW_HP_RANGE && runHitAt.containsKey(foe.id()) && (hitter == null || foe.distance() < hitter.distance())) {
                hitter = foe;
            }
        }
        return onUs != null ? onUs : hitter;
    }

    private void clearRunHits() {
        runHits = 0;
        runHitAt.clear();
    }

    // still on our heels at the cap: hurt with something near, or a crowd that is still on us
    private static boolean chased(Tick t) {
        if (crowded(t.health(), t.foes(), false)) return true;
        if (!t.low()) return false;
        for (Foe foe : t.foes()) {
            if (foe.distance() <= LOW_HP_RANGE) return true;
        }
        return false;
    }

    // who to turn around on: the closest one on top of us or that hit us from inside 8, else the closest inside 8 we could
    // walk up to and hit (same "no creeper, nothing we gave up on" as the corner pick). never the warden
    private Foe chaser(Tick t) {
        Foe onUs = null;
        Foe near = null;
        for (Foe foe : t.foes()) {
            if (foe.distance() > LOW_HP_RANGE || foe.kind() == Kind.UNTOUCHABLE) continue;
            // (a lobber or sniper we could not reach is not who the run turns round on)
            if (gaveUpOn(foe, t.now())) continue;
            if (!foe.creeper() && (foe.distance() <= CONTACT || foe.hitUs()) && (onUs == null || foe.distance() < onUs.distance())) onUs = foe;
            if (!foe.creeper() && !ignored.containsKey(foe.id()) && (near == null || foe.distance() < near.distance())) near = foe;
        }
        return onUs != null ? onUs : near;
    }

    // nothing angry within CORNER_WATCH and no ground gained for RUN_STUCK_TICKS: the pathfinder has nowhere to take us (a lava
    // lake, the edge of the loaded world, an island)
    private Event stuck(Tick t) {
        if (stuckSince < 0 || Math.hypot(t.x() - stuckX, t.z() - stuckZ) >= CORNER_PROGRESS) {
            stuckSince = t.now();
            stuckX = t.x();
            stuckZ = t.z();
            return Event.NONE;
        }
        return t.now() - stuckSince >= RUN_STUCK_TICKS ? end(t, Event.RUN_STUCK) : Event.NONE;
    }

    // ---- the end of anything

    private Event end(Tick t, Event event) {
        // (mode is still the one that is ending)
        recover.ended(t.now(), mode == Mode.FIGHT && runLine ? Mode.RUN : mode, t.health(), t.body());
        runLine = false;
        boxCorner = false;
        fastCorner = false;
        mode = Mode.NONE;
        why = Why.NONE;
        targetId = -1;
        cornered = false;
        farSince = -1;
        clearSince = -1;
        clearRunHits();
        cornerSince = -1;
        stuckSince = -1;
        losRun = false;
        cooldownUntil = t.now() + COOLDOWN;
        return event;
    }
}
