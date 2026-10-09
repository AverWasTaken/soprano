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

package baritone.behavior;

// a task handing over to the next one cancels the path, and when the next one asks for the very same goal the bot used
// to stand there while a fresh path got calculated to where it was already walking. the handover parks the path it
// cancelled and this says whether the new request may have it back. pure, PathingBehavior asks the world questions
public final class PathKeep {
    // half a second. a handover re-issues the goal in the same tick, anything later is a different story
    public static final long MAX_AGE_TICKS = 10;

    private PathKeep() {
    }

    // sameGoal: the new goal equals the goal the parked path was made for. feetOnPath: where we stand is one of its
    // positions (knockback, a teleport or a respawn put us somewhere else). sameWorld / samePlayer: a dimension change or a
    // death in between makes it someone else's path even when the coordinates line up
    public static boolean restore(boolean enabled, boolean sameGoal, long age, boolean feetOnPath, boolean sameWorld, boolean samePlayer) {
        return enabled && sameGoal && age >= 0 && age <= MAX_AGE_TICKS && feetOnPath && sameWorld && samePlayer;
    }
}
