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

package baritone.behavior.look;

// logAimSources plumbing. the pure bits live here so a test can poke them without a world
public final class AimLog {

    // anything under this is normal steering, anything over is the head whip people complain about
    public static final float BIG_TURN = 30;

    // one line every few ticks is plenty to see who did it, a jitter would otherwise flood the log at 20/s
    public static final int MIN_GAP_TICKS = 4;

    private int lastLogged = Integer.MIN_VALUE / 2;
    private int skipped;

    // shortest signed turn from `from` to `to`, in [-180, 180). 170 to -170 is 20 degrees, not 340
    public static float wrappedDelta(float to, float from) {
        float d = (to - from) % 360;
        if (d >= 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    public static boolean isBig(float delta) {
        return Math.abs(delta) > BIG_TURN;
    }

    // returns how many lines got swallowed since the last one, or -1 when this one should be swallowed too
    public int admit(int now) {
        // tickCount starts over on a respawn or a new dimension, don't sit out the old count
        if (now >= lastLogged && now - lastLogged < MIN_GAP_TICKS) {
            skipped++;
            return -1;
        }
        lastLogged = now;
        int s = skipped;
        skipped = 0;
        return s;
    }
}
