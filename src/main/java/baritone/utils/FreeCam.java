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

package baritone.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * A spectator camera: fly round the bot with the movement keys and the mouse while it goes on playing.
 * <ul>
 * <li>The mouse turns the camera, not the player (MixinFreeCamMouse); the movement keys fly it (space up, shift down,
 * sprint for faster, the scroll wheel sets the speed) and never reach the player (its input is a still one while the bot
 * isn't driving: FreeCam.tick).</li>
 * <li>Following (on by default) the camera keeps its place relative to the player as the player moves, so it stays on the
 * bot; off, it stays put in the world.</li>
 * <li>The camera mixin (MixinMoveCamera) puts the frame where pose() says, detached, so the player's body is drawn; the hand
 * and crosshair are off while it's on.</li>
 * </ul>
 * On with the freecamKey (F6 by default), #freecam, or the Free cam button in the gui's status strip. The same again, or
 * dying, leaving the world, or another world, puts the camera back.
 */
public final class FreeCam {

    private static boolean on;
    private static boolean follow = true;
    // the camera's eye, in the world (follow off) or relative to the player's eye (follow on)
    private static double x, y, z;
    private static float yaw, pitch;
    private static Object level;
    private static long lastFrame;
    // blocks a second
    private static double speed = 10;
    private static final double MIN_SPEED = 1, MAX_SPEED = 80;
    // last frame's player eye, for following smoothly between ticks
    private static double px, py, pz;

    private FreeCam() {
    }

    public static boolean on() {
        return on;
    }

    public static boolean following() {
        return follow;
    }

    public static String toggle() {
        return on ? stop() : start();
    }

    public static String start() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return "Join a world first.";
        }
        level = mc.level;
        yaw = p.getYRot();
        pitch = p.getXRot();
        // a few blocks back and up from the player, looking the way it looks: the bot's in the shot from the start
        double r = Math.toRadians(yaw);
        double ex = p.getX() + Math.sin(r) * 4, ey = p.getEyeY() + 1.5, ez = p.getZ() - Math.cos(r) * 4;
        pitch = 15;
        px = p.getX();
        py = p.getEyeY();
        pz = p.getZ();
        if (follow) {
            x = ex - px;
            y = ey - py;
            z = ez - pz;
        } else {
            x = ex;
            y = ey;
            z = ez;
        }
        lastFrame = 0;
        on = true;
        return "Free cam on: fly with " + keys(mc.options) + ", the mouse looks, scroll for speed. "
                + (follow ? "It follows the bot (#freecam follow to stop that)." : "It stays where you put it (#freecam follow to follow the bot).")
                + " The free cam key or #freecam again to come back.";
    }

    private static String keys(Options o) {
        return o.keyUp.getTranslatedKeyMessage().getString() + o.keyLeft.getTranslatedKeyMessage().getString()
                + o.keyDown.getTranslatedKeyMessage().getString() + o.keyRight.getTranslatedKeyMessage().getString();
    }

    public static String stop() {
        if (!on) {
            return "Free cam is already off.";
        }
        on = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.input instanceof Still) {
            mc.player.input = new KeyboardInput(mc.options);
        }
        return "Free cam off.";
    }

    public static String toggleFollow() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        follow = !follow;
        if (on && p != null) {
            // the camera stays exactly where it is: only what it's measured from changes
            if (follow) {
                x -= px;
                y -= py;
                z -= pz;
            } else {
                x += px;
                y += py;
                z += pz;
            }
        }
        return follow ? "The free cam follows the bot." : "The free cam stays put.";
    }

    // the scroll wheel: faster or slower by a fifth a notch
    public static void scroll(double notches) {
        speed = Mth.clamp(speed * Math.pow(1.2, notches), MIN_SPEED, MAX_SPEED);
    }

    // the mouse, already scaled the way the game scales it for the player (degrees times 0.15, as Entity.turn does)
    public static void turn(double dx, double dy) {
        yaw += (float) (dx * 0.15);
        pitch = Mth.clamp(pitch + (float) (dy * 0.15), -90F, 90F);
    }

    /**
     * every client tick: off when there's nothing to watch, and the player standing still while the bot isn't driving
     */
    public static void tick() {
        if (!on) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.level != level || p.isDeadOrDying()) {
            stop();
            return;
        }
        if (p.input instanceof KeyboardInput) {
            p.input = new Still();
        }
    }

    /**
     * x y z yaw pitch for the camera this frame, null for the player's own camera. the flying happens here, per frame, so
     * it's smooth at any frame rate
     */
    public static double[] pose(float partialTicks) {
        if (!on) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            return null;
        }
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        Options o = mc.options;
        // no flying while typing in chat or a screen is up
        boolean keys = mc.gui.screen() == null;
        double f = keys ? (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0) : 0;
        double s = keys ? (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0) : 0;
        double u = keys ? (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0) : 0;
        double v = speed * (keys && o.keySprint.isDown() ? 2.5 : 1) * dt;
        double r = Math.toRadians(yaw), cp = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
        // forward along where the camera looks (pitch too, the way spectator mode flies), sideways level
        double fx = -Math.sin(r) * cp, fy = -sp, fz = Math.cos(r) * cp;
        double lx = Math.cos(r), lz = Math.sin(r);
        double len = Math.sqrt(f * f + s * s);
        if (len > 1) {
            f /= len;
            s /= len;
        }
        x += (fx * f + lx * s) * v;
        y += fy * f * v + u * v;
        z += (fz * f + lz * s) * v;
        px = Mth.lerp(partialTicks, p.xo, p.getX());
        py = Mth.lerp(partialTicks, p.yo, p.getY()) + p.getEyeHeight();
        pz = Mth.lerp(partialTicks, p.zo, p.getZ());
        double cx = follow ? px + x : x, cy = follow ? py + y : y, cz = follow ? pz + z : z;
        // no further than the loaded chunks go, or there's nothing to look at
        double leash = Math.max(32, mc.options.getEffectiveRenderDistance() * 16 - 16);
        double ox = cx - px, oz = cz - pz, d = Math.sqrt(ox * ox + oz * oz);
        if (d > leash) {
            cx = px + ox * leash / d;
            cz = pz + oz * leash / d;
            if (follow) {
                x = cx - px;
                z = cz - pz;
            } else {
                x = cx;
                z = cz;
            }
        }
        return new double[]{cx, cy, cz, yaw, pitch};
    }

    // the player's input while the camera flies and the bot isn't driving: nothing pressed
    public static final class Still extends ClientInput {
        @Override
        public void tick() {
            this.keyPresses = net.minecraft.world.entity.player.Input.EMPTY;
            this.moveVector = net.minecraft.world.phys.Vec2.ZERO;
        }
    }
}
