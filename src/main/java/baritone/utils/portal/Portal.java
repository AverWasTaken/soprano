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

package baritone.utils.portal;

// the safe side of the viafabricplus wall. nothing in here may mention a via type, not even in a signature,
// because forge/neoforge/tweaker (and fabric without vfp) load this class and would die on the first one.
// every via call goes through ViaPortal, which only gets loaded once available() said yes
public final class Portal {

    private static Boolean available;
    // flipped the first time a rule question blows up (vfp moved something, api not up yet), after that it's 26.3 forever
    private static volatile boolean broken;

    private Portal() {}

    public static boolean available() {
        if (available == null) {
            // a string and not ViaFabricPlus.class, so there's no class constant for anyone to resolve early.
            // same idea as LitematicaHelper.isLitematicaPresent
            try {
                Class.forName("com.viaversion.viafabricplus.ViaFabricPlus");
                available = true;
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                available = false;
            }
        }
        return available;
    }

    // releases oldest to newest, then auto as the last notch
    public static int stepCount() {
        return ViaPortal.stepCount();
    }

    // where the knob goes for whatever vfp is set to right now
    public static int currentStep() {
        return ViaPortal.currentStep();
    }

    public static String label(int step) {
        return ViaPortal.label(step);
    }

    // the real name when the target isn't one of our steps (a beta, april fools, ...), null when it is
    public static String offListName() {
        return ViaPortal.offListName();
    }

    public static void select(int step) {
        ViaPortal.select(step);
    }

    // does the server round fall damage up and skip the landing move, see LadderClutch. false means 26.3 rules
    public static boolean roundsFallDamageUp() {
        if (!available() || broken) {
            return false;
        }
        try {
            return ViaPortal.roundsFallDamageUp();
        } catch (LinkageError | RuntimeException e) {
            broken = true;
            return false;
        }
    }

    // is the client breaking with the old fatigue table, see ToolSet.fatigueFactor. false means 26.3 rules
    public static boolean oldMiningFatigue() {
        if (!available() || broken) {
            return false;
        }
        try {
            return ViaPortal.oldMiningFatigue();
        } catch (LinkageError | RuntimeException e) {
            broken = true;
            return false;
        }
    }

    // does the client skip normalizing diagonal input like <= 1.21.4 did, see PlayerMovementInput. false means 26.3 rules
    public static boolean rawDiagonalInput() {
        if (!available() || broken) {
            return false;
        }
        try {
            return ViaPortal.rawDiagonalInput();
        } catch (LinkageError | RuntimeException e) {
            broken = true;
            return false;
        }
    }
}
