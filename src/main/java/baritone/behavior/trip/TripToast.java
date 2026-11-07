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

package baritone.behavior.trip;

// what the corner toast should be showing right now. TripBehavior writes it on the client thread, the hud reads it on
// the same thread, so a plain field is fine. no minecraft in here: the hud draws it, the tests poke it
public final class TripToast {

    public enum Kind {
        NONE,
        ASK,
        COLLECTING,
        DONE
    }

    // what the bag is doing right now, so the toast can say it in words
    public enum Phase {
        PICKAXE,
        BY_HAND,
        MINING
    }

    // savedTicks is only for a faster ask. have/need are blocks collected so far and the deficit, need 0 is a
    // pickaxe only trip. id goes up every time the kind changes, that's what slides a new toast in and plays the sound
    public record View(Kind kind, long tripId, int id, Trip.Ask ask, int blocks, boolean pickaxe, double savedTicks,
                       int have, int need, Phase phase) {

        public boolean asking() {
            return kind == Kind.ASK;
        }

        public boolean collecting() {
            return kind == Kind.COLLECTING;
        }
    }

    // long enough to read "Got 24 blocks" while walking, short enough to be gone before the next thing happens
    public static final long DONE_HOLD_MS = 4000;

    private static final View NONE = new View(Kind.NONE, -1, 0, null, 0, false, 0, 0, 0, null);

    private View view = NONE;
    private int ids;
    private long doneAtMs;

    public View view(long nowMs) {
        if (view.kind == Kind.DONE && nowMs - doneAtMs >= DONE_HOLD_MS) {
            view = NONE;
        }
        return view;
    }

    public void ask(long tripId, GatherPlan plan, double savedTicks) {
        view = new View(Kind.ASK, tripId, ++ids, plan.kind(), plan.blocks(), plan.pickaxe(), savedTicks, 0, plan.blocks(), null);
    }

    // every tick while the gather runs. the same toast keeps its id, only the counts move
    public void collecting(long tripId, boolean pickaxe, int have, int need, Phase phase) {
        int clamped = Math.max(0, Math.min(have, need));
        int id = view.kind == Kind.COLLECTING && view.tripId == tripId ? view.id : ++ids;
        view = new View(Kind.COLLECTING, tripId, id, null, need, pickaxe, 0, clamped, need, phase);
    }

    public void done(long tripId, boolean pickaxe, int got, long nowMs) {
        doneAtMs = nowMs;
        view = new View(Kind.DONE, tripId, ++ids, null, got, pickaxe, 0, got, got, null);
    }

    // "1m 20s", the chat line's own wording
    public static String duration(double ticks) {
        return TripPrompt.duration(ticks);
    }

    public void clear() {
        view = NONE;
    }

    // the question got answered some other way (chat, #gather) or the trip moved on. a done toast is left to time out
    public void sync(Trip trip) {
        View v = view;
        if (v.kind == Kind.ASK && (trip == null || trip.id != v.tripId || trip.decision != Trip.Decision.PENDING)) {
            view = NONE;
        } else if (v.kind == Kind.COLLECTING && (trip == null || trip.id != v.tripId || !trip.gathering)) {
            view = NONE;
        }
    }
}
