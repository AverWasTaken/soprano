/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

// a swimmer whose cell keeps failing no matter which way out the planner picks (see FailedMoves) is not going to be
// saved by another replan, banning exits can't work when every path has to leave the start cell. so stand up for a
// bit instead: no sprint, jump held, which breaks the swim pose and bobs us back to the surface or the floor.
// once per movement, the next movement gets its own go. no game in here so it can be tested on its own
final class SwimEscape {

    static final int MIN_EXITS = 2;
    static final int STAND_TICKS = 20;

    private int left;
    private boolean started;
    private boolean began;

    // exits is how many different moves out of src are failing. true while we should be standing up
    boolean tick(int exits) {
        began = false;
        if (!started) {
            if (exits < MIN_EXITS) {
                return false;
            }
            started = true;
            left = STAND_TICKS;
            began = true;
        }
        return left > 0 && left-- > 0;
    }

    // true on the one tick the escape kicked in, for the log line
    boolean began() {
        return began;
    }
}
