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

package baritone.altoclef;

import adris.altoclef.AltoClef;
import adris.altoclef.commands.AltoClefCommands;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.eventbus.events.ChunkUnloadEvent;
import baritone.Baritone;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.event.events.ChunkEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.event.GameEventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.ChunkPos;

// the one place soprano and altoclef touch. it owns the AltoClef instance, feeds it soprano's tick and chunk events
// (these used to be four altoclef mixins) and keeps any exception it throws from taking the game down with it
public final class AltoClefBridge implements AbstractGameEventListener {

    // after this many exceptions altoclef is switched off for the session instead of spamming the log every tick
    private static final int MAX_ERRORS = 5;

    private static AltoClef altoClef;
    private static AltoClefBridge instance;
    private static boolean hudBroken;

    private boolean initTried;
    private int errors;

    private AltoClefBridge() {
    }

    // called once for the primary baritone. goes to the FRONT of the listener list: altoclef used to tick at the top
    // of Minecraft.tick, ahead of everything baritone does, and it sets goals and inputs that the behaviors and
    // processes are supposed to see this very tick
    public static void attach(Baritone primary) {
        instance = new AltoClefBridge();
        ((GameEventHandler) primary.getGameEventHandler()).registerEventListenerFirst(instance);
        // the commands go in now, long before altoclef itself exists, so #help and tab completion know about them from
        // the title screen on. running one is what creates altoclef, see require
        AltoClefCommands.register(primary);
    }

    // the instance for a command that is about to run. altoclef is made lazily on the first tick it can be, so a command
    // typed before that (or after altoclef gave up) has to say so instead of falling over a null
    public static AltoClef require() throws CommandException {
        if (altoClef == null && instance != null && !instance.initTried) {
            instance.init();
        }
        if (altoClef == null) {
            throw new CommandInvalidStateException(instance != null && instance.initTried
                    ? "altoclef is off for this session, it failed to start or crashed too many times (the log says why)"
                    : "altoclef is not up yet, give it a second");
        }
        return altoClef;
    }

    // what stop does about altoclef. never creates it, a user task can't be running if it was never made
    public static void cancelUserTask() {
        AltoClefCommands.abortSequences();
        if (altoClef != null) {
            try {
                altoClef.cancelUserTask();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    // true while an altoclef task (or the idle gate) has the bot. mixins that change vanilla behaviour check this
    public static boolean isRunning() {
        return altoClef != null && AltoClef.isRunning();
    }

    // called from MixinAltoClefGui at the end of Gui#render, every frame
    public static void renderHud(GuiGraphics graphics) {
        if (hudBroken || !isRunning()) {
            return;
        }
        try {
            altoClef.onClientRenderOverlay(graphics);
        } catch (Throwable t) {
            hudBroken = true;
            System.err.println("altoclef hud threw, turning it off");
            t.printStackTrace();
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (!initTried) {
            init();
        }
        // OUT is the title screen / loading, nothing of altoclef's runs there
        if (altoClef == null || event.getType() != TickEvent.Type.IN) {
            return;
        }
        try {
            altoClef.onClientTick();
        } catch (Throwable t) {
            onError(t);
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (altoClef == null) {
            return;
        }
        // POST populate is "the packet's chunk is in the world", which is when the old loadChunkFromPacket mixin fired
        if (event.isPostPopulate()) {
            EventBus.publish(new ChunkLoadEvent(new ChunkPos(event.getX(), event.getZ())));
        } else if (event.getState() == EventState.POST && event.getType() == ChunkEvent.Type.UNLOAD) {
            EventBus.publish(new ChunkUnloadEvent(new ChunkPos(event.getX(), event.getZ())));
        }
    }

    // altoclef used to start on the first TitleScreen#init. same moment here (registries and resources are up by
    // then), or the first tick with a world for people who skip the title screen
    private void init() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen) && mc.level == null) {
            return;
        }
        initTried = true;
        try {
            AltoClef ac = new AltoClef();
            // set before onInitializeLoad, not after: the idle command can run a command while it is still starting and
            // that command asks for the instance
            altoClef = ac;
            ac.onInitializeLoad();
        } catch (Throwable t) {
            altoClef = null;
            System.err.println("altoclef failed to start, it stays off this session");
            t.printStackTrace();
        }
    }

    private void onError(Throwable t) {
        System.err.println("altoclef threw in its tick");
        t.printStackTrace();
        try {
            // whatever it was doing is what blew up, so stop doing it
            altoClef.cancelUserTask();
            altoClef.getTaskRunner().disable();
        } catch (Throwable ignored) {
            // already cleaning up after one exception
        }
        if (++errors >= MAX_ERRORS) {
            System.err.println("altoclef threw " + MAX_ERRORS + " times, switching it off");
            altoClef = null;
        }
    }
}
