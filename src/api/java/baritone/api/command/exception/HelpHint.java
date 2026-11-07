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

package baritone.api.command.exception;

import baritone.api.BaritoneAPI;

// the "what now" half of a command error. package private on purpose, the api jar doesn't need another public toy
final class HelpHint {

    private HelpHint() {}

    static String prefix() {
        try {
            return BaritoneAPI.getSettings().prefix.value;
        } catch (Throwable t) {
            // no settings yet (a unit test, or something blew up at startup): the default is the right guess anyway
            return "#";
        }
    }

    static String forCommand(String name) {
        return " Type " + prefix() + "help " + name + " to see how it's used.";
    }
}
