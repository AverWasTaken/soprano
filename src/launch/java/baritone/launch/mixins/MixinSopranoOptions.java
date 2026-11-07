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

package baritone.launch.mixins;

import adris.altoclef.ui.gui.mc.SopranoKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

// our keys go on the end of vanilla's list right before load(), so options.txt has them to read a rebind into and the
// controls screen shows them. fabric api would do this with its keybinding helper, we don't need fabric api
@Mixin(Options.class)
public class MixinSopranoOptions {

    @Shadow
    @Final
    @Mutable
    public KeyMapping[] keyMappings;

    @Inject(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;load()V")
    )
    private void soprano$addKeys(final CallbackInfo ci) {
        try {
            KeyMapping[] ours = SopranoKeys.all();
            KeyMapping[] all = Arrays.copyOf(keyMappings, keyMappings.length + ours.length);
            System.arraycopy(ours, 0, all, keyMappings.length, ours.length);
            keyMappings = all;
        } catch (Throwable t) {
            // the game starts without our two rows, Y and N just aren't rebindable then
            t.printStackTrace();
        }
    }
}
