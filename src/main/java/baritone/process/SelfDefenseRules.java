/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import java.util.Set;

// the decisions behind SelfDefenseProcess with the world taken out: ticks, distances and yes/no in, yes/no out.
// leave mobs alone until one actually bites, then finish it, and never flip between fighting and mining every second
public final class SelfDefenseRules {

    // it hit us this recently or it is not our problem. two zombie swings
    public static final long FRESH_HIT = 40;
    // how long a hit stays in the book at all
    public static final long MEMORY = 200;
    // a knocked back zombie walks back in way under this
    public static final int OUT_OF_REACH_TICKS = 30;
    // in reach but we can't get a clean swing in. its melee line can be clear while ours isn't, don't stand there eating it
    public static final int NO_LINE_TICKS = 40;
    // and one that walked off this far is gone, no waiting around
    public static final double TOO_FAR = 8;
    // one we gave up on for having no line stays ignored this long, or it bites again and we freeze for another 40
    public static final long SHRUG_TICKS = 100;
    // near a full charge, a 0.5 swing does a quarter of the damage and still resets the cooldown
    public static final float SWING_CHARGE = 0.9f;
    // how far off the rotation the server has can be from where we want to hit and still count as looking at it
    public static final float AIM_TOLERANCE = 15;

    // creepers want us to come closer, wardens and withers and the dragon are not a melee job, and the flyers swoop in and
    // out so holding for them freezes the job over and over
    private static final Set<String> EXCLUDED = Set.of("creeper", "warden", "wither", "ender_dragon",
            "phantom", "vex", "ghast", "blaze");

    private SelfDefenseRules() {
    }

    public static boolean excluded(String type) {
        return EXCLUDED.contains(type);
    }

    // does this attacker go in the book. type is the registry path ("zombie")
    public static boolean noteable(boolean mob, boolean enemy, String type) {
        return mob && enemy && !excluded(type);
    }

    // drop it from the book: old, dead, or a clock that went backwards (new world)
    public static boolean forget(long age, boolean alive) {
        return !alive || age < 0 || age > MEMORY;
    }

    // everything that has to hold before we even look at mobs. a pause also kills swim inputs, and alto fights its own fights
    public static boolean canRun(boolean enabled, boolean alive, boolean flying, boolean inWater, boolean inLava,
                                 boolean altoRunning, boolean jobActive) {
        return enabled && alive && !flying && !inWater && !inLava && !altoRunning && jobActive;
    }

    // start a fight: it bit us lately and it is right here
    public static boolean engage(long sinceHit, boolean inReach) {
        return inReach && sinceHit >= 0 && sinceHit <= FRESH_HIT;
    }

    // one we shrugged off for having no line is left alone for a bit even if it bites again
    public static boolean shrugged(long sinceShrug) {
        return sinceShrug >= 0 && sinceShrug < SHRUG_TICKS;
    }

    // once engaged we hold until one of these. no chasing, the job is what we came here for
    public static boolean release(boolean gone, double distance, int ticksOutOfReach, int ticksNoLine) {
        return gone || distance > TOO_FAR || ticksOutOfReach >= OUT_OF_REACH_TICKS || ticksNoLine >= NO_LINE_TICKS;
    }

    // aimError is NaN when we don't know what the server thinks we're looking at, which is a no
    public static boolean swing(float charge, boolean lineClear, boolean inReach, double aimError) {
        return charge >= SWING_CHARGE && lineClear && inReach && aimError <= AIM_TOLERANCE;
    }

    // worst of the two axes, yaw wrapped so 179 and -179 are 2 apart and not 358
    public static double aimError(float yawA, float pitchA, float yawB, float pitchB) {
        double yaw = Math.abs(wrap(yawA - yawB));
        double pitch = Math.abs(pitchA - pitchB);
        return Math.max(yaw, pitch);
    }

    private static double wrap(double degrees) {
        double d = degrees % 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    // the hotbar slot to fight with: a sword, then an axe, then whatever hits harder than a fist, best damage first in
    // each. the slot in hand wins a tie so we never swap for nothing. -1 means keep what we hold.
    // kind: 2 sword, 1 axe, 0 anything else. damage is one full hit with it, fists are 1
    public static int weaponSlot(int[] kind, float[] damage, int selected, float fist) {
        int best = -1;
        for (int i = 0; i < kind.length; i++) {
            if (kind[i] == 0 && damage[i] <= fist) continue;
            if (best < 0 || beats(kind, damage, i, best, selected)) {
                best = i;
            }
        }
        return best;
    }

    private static boolean beats(int[] kind, float[] damage, int a, int b, int selected) {
        if (kind[a] != kind[b]) return kind[a] > kind[b];
        if (damage[a] != damage[b]) return damage[a] > damage[b];
        return a == selected;
    }
}
