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

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.component.SwingAnimation;

// pre 1.9 combat: no cooldown, spam click, swing packet before the hit. every melee swing goes through hit() so the packet
// order lives in one place, and the callers that pace themselves off the cooldown ask active() for the clicker instead
public final class LegacyCombat {

    private LegacyCombat() {
    }

    // 26.3 only ever talks to 26.3 servers, so never. version-agnostic swaps this one line for the portal's server version
    // check, keep it alone
    public static boolean active() {
        return false;
    }

    // a 1.8 client sends the arm swing and then the attack, and old anticheats want to see a swing before every hit.
    // ViaFabricPlus only fixes the order inside Minecraft#startAttack and we never go through that
    public static void hit(MultiPlayerGameMode gameMode, LocalPlayer player, Entity target, InteractionHand hand) {
        if (active()) {
            player.swing(hand, SwingAnimation.DEFAULT, false);
            gameMode.attack(player, target);
        } else {
            gameMode.attack(player, target);
            player.swing(hand, SwingAnimation.DEFAULT, false);
        }
    }
}
