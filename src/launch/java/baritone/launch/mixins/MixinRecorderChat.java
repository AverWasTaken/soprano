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

package baritone.launch.mixins;

import baritone.behavior.RecordingBehavior;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// every line that goes into the chat box, for #record
@Mixin(ChatComponent.class)
public class MixinRecorderChat {

    @Inject(method = "addServerSystemMessage", at = @At("HEAD"), require = 0)
    private void soprano$recordServer(Component message, CallbackInfo ci) {
        RecordingBehavior.chat(message.getString());
    }

    @Inject(method = "addClientSystemMessage", at = @At("HEAD"), require = 0)
    private void soprano$recordClient(Component message, CallbackInfo ci) {
        RecordingBehavior.chat(message.getString());
    }

    @Inject(method = "addPlayerMessage", at = @At("HEAD"), require = 0)
    private void soprano$recordPlayer(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        RecordingBehavior.chat(message.getString());
    }
}
