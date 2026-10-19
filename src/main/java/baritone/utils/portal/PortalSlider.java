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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

// the version knob on the server list. only ever built after Portal.available(), and only talks to Portal,
// so it's as via-free as Portal is
public class PortalSlider extends AbstractSliderButton {

    private static final Component TOOLTIP = Component.literal("The server version ViaFabricPlus translates to. "
            + "Auto asks the server, which doesn't work on 2b2t (its proxy echoes back whatever version you send), "
            + "so pick 1.21.4 there. A version set per server in Edit Server still wins.");

    private int steps;
    // where the knob is vs what vfp actually has. dragging only moves the first one
    private int step;
    private int committed;
    // the real name of a target we don't list, shown until the knob moves off it
    private String offList;

    public PortalSlider(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty(), 0);
        setTooltip(Tooltip.create(TOOLTIP));
        refresh();
    }

    // re-read vfp, it may have changed behind our back in its own screen or a server's edit screen
    public void refresh() {
        steps = Portal.stepCount();
        step = Portal.currentStep();
        offList = Portal.offListName();
        // an unlisted target is parked on a notch it isn't actually on, so clicking that notch has to count
        committed = offList != null ? -1 : step;
        value = toValue(step);
        updateMessage();
    }

    private double toValue(int s) {
        return steps <= 1 ? 0 : s / (double) (steps - 1);
    }

    private int toStep(double v) {
        return (int) Math.round(v * (steps - 1));
    }

    @Override
    protected void updateMessage() {
        String name;
        try {
            name = offList != null ? offList : Portal.label(step);
        } catch (LinkageError | RuntimeException e) {
            broke();
            return;
        }
        String full = "Version: " + name;
        // the knob gets narrow on small windows, the name is the part people actually need
        int room = getWidth() - 2 * TEXT_MARGIN;
        setMessage(Component.literal(Minecraft.getInstance().font.width(full) <= room ? full : name));
    }

    @Override
    protected void applyValue() {
        // snap while dragging but don't commit, every setTargetVersion fires all of vfp's change listeners and
        // a drag across the whole bar would be 60 of them
        step = toStep(value);
        value = toValue(step);
        offList = null;
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        commit();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // enter/space still toggles canChangeValue in super, we only take over the arrows so one press is one
        // version instead of 1/(width-8) of the bar, which rounds back to the same notch
        if (!event.isSelection() && canChangeValue && (event.isLeft() || event.isRight())) {
            move(event.isLeft() ? -1 : 1);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        move(scrollY > 0 ? 1 : -1);
        return true;
    }

    private void move(int delta) {
        int s = Math.max(0, Math.min(step + delta, steps - 1));
        if (s != step) {
            step = s;
            offList = null;
            value = toValue(s);
            updateMessage();
        }
        commit();
    }

    private void commit() {
        if (step == committed) {
            return;
        }
        // same guard as the mixin, a vfp listener throwing in here would otherwise go straight up the screen's
        // event handler and take the client with it
        try {
            Portal.select(step);
        } catch (LinkageError | RuntimeException e) {
            broke();
            return;
        }
        committed = step;
        if (offList != null) {
            offList = null;
            updateMessage();
        }
    }

    // vfp went weird on us, the server list is worth more than the knob
    private void broke() {
        visible = false;
        active = false;
    }
}
