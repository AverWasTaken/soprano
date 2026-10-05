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

import adris.altoclef.AltoSettings;
import adris.altoclef.AltoClef;
import baritone.Baritone;
import baritone.api.Settings;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// what AltoClef.initializeBaritoneSettings used to do once at startup, forever. that turned parkour off for
// everybody, so now it is applied when a task starts and every value gets put back when the task stops
public final class BaritoneSettingsScope {

    private final AltoClef mod;
    private final List<Runnable> undo = new ArrayList<>();
    // the baritone settings we changed: what they were, what we made them. a setting whose value is no longer ours was
    // changed by the user (#set) in the meantime and is theirs to keep
    private final List<Held<?>> overrides = new ArrayList<>();
    private boolean applied;
    private List<Item> savedThrowaway;
    // the altoThrowawayItems list the merged baritone list was built from, to notice a #set of it
    private List<Item> mergedFrom;
    private List<Item> lastMerged;

    private record Held<T>(Settings.Setting<T> setting, T old, T ours) {
        boolean stillOurs() {
            return setting.value == ours;
        }
    }

    public BaritoneSettingsScope(AltoClef mod) {
        this.mod = mod;
    }

    // safe to call again while applied (a settings reload does), it only refreshes the throwaway list then
    public void apply() {
        Settings s = Baritone.settings();
        if (!applied) {
            applied = true;
            AltoClefSettings extra = AltoClefSettings.getInstance();

            boolean walkOnEndPortal = extra.isCanWalkOnEndPortal();
            undo.add(() -> extra.canWalkOnEndPortal(walkOnEndPortal));
            extra.canWalkOnEndPortal(false);

            set(s.freeLook, false);
            set(s.overshootTraverse, false);
            set(s.allowOvershootDiagonalDescend, true);
            set(s.allowInventory, true);
            set(s.allowParkour, false);
            set(s.allowParkourAscend, false);
            set(s.allowParkourPlace, false);
            set(s.allowDiagonalDescend, false);
            set(s.allowDiagonalAscend, false);
            set(s.blocksToAvoid, List.of(Blocks.FLOWERING_AZALEA, Blocks.AZALEA,
                    Blocks.POWDER_SNOW, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.CAVE_VINES,
                    Blocks.CAVE_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.SWEET_BERRY_BUSH,
                    Blocks.WARPED_ROOTS, Blocks.VINE, Blocks.GRASS_BLOCK, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN,
                    Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
                    Blocks.AMETHYST_CLUSTER, Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SUNFLOWER, Blocks.LILAC,
                    Blocks.ROSE_BUSH, Blocks.PEONY));
            // Reduces a bit of far rendering to save FPS
            set(s.fadePath, true);
            // Don't let baritone scan dropped items, we handle that ourselves.
            set(s.mineScanDroppedItems, false);
            // Don't let baritone wait for drops, we handle that ourselves.
            set(s.mineDropLoiterDurationMSThanksLouca, 0L);

            // Water bucket placement will be handled by us exclusively
            boolean placeBucketButDontFall = extra.shouldNotPlaceBucketButStillFall();
            undo.add(() -> extra.configurePlaceBucketButDontFall(placeBucketButDontFall));
            extra.configurePlaceBucketButDontFall(true);

            // For render smoothing
            set(s.randomLooking, 0.0);
            set(s.randomLooking113, 0.0);

            // Give baritone more time to calculate paths. Sometimes they can be really far away.
            // Was: 2000L
            set(s.failureTimeoutMS, s.failureTimeoutMS.defaultValue);
            // Was: 5000L
            set(s.planAheadFailureTimeoutMS, s.planAheadFailureTimeoutMS.defaultValue);
            // Was 100
            set(s.movementTimeoutTicks, s.movementTimeoutTicks.defaultValue);

            savedThrowaway = s.acceptableThrowawayItems.value;
        }

        // Baritone's `acceptableThrowawayItems` should match our own. A fresh list every time: the setting's own list
        // is also its default, adding to it in place would poison reset
        // somebody #set baritone's own list while we were applied: that is the list to merge into and to give back
        if (lastMerged != null && s.acceptableThrowawayItems.value != lastMerged) {
            savedThrowaway = s.acceptableThrowawayItems.value;
        }
        mergedFrom = s.altoThrowawayItems.value;
        List<Item> baritoneCanPlace = Arrays.stream(AltoSettings.getThrowawayItems(mod, true))
                .filter(item -> item != Items.SOUL_SAND && item != Items.MAGMA_BLOCK && item != Items.SAND && item
                        != Items.GRAVEL).toList();
        List<Item> merged = new ArrayList<>(savedThrowaway);
        merged.addAll(baritoneCanPlace);
        // our list is still ours when we came here a second time, otherwise a #set of it would be undone by restore
        overrides.removeIf(o -> o.setting() == s.acceptableThrowawayItems);
        overrides.add(new Held<>(s.acceptableThrowawayItems, savedThrowaway, merged));
        s.acceptableThrowawayItems.value = merged;
        lastMerged = merged;
    }

    // a #set of altoThrowawayItems while a task runs has to reach baritone's list as well, one reference compare a tick
    public void refreshThrowaway() {
        if (applied && mergedFrom != Baritone.settings().altoThrowawayItems.value) {
            apply();
        }
    }

    // runs a settings.txt save as if none of this was applied: every setting we are still holding goes back to what it
    // was for the duration. without it the first #set during a task would save allowParkour false (and friends) for good
    public void saveWithoutOverrides(Runnable save) {
        List<Held<?>> held = new ArrayList<>();
        for (Held<?> o : overrides) {
            if (o.stillOurs()) {
                held.add(o);
                put(o, o.old());
            }
        }
        try {
            save.run();
        } finally {
            for (Held<?> o : held) {
                put(o, o.ours());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void put(Held<T> o, Object value) {
        o.setting().value = (T) value;
    }

    public void restore() {
        if (!applied) {
            return;
        }
        applied = false;
        for (Held<?> o : overrides) {
            if (o.stillOurs()) {
                put(o, o.old());
            }
        }
        overrides.clear();
        savedThrowaway = null;
        mergedFrom = null;
        lastMerged = null;
        for (int i = undo.size() - 1; i >= 0; i--) {
            undo.get(i).run();
        }
        undo.clear();
        // chains pause baritone's clicking while they eat and so on, a stop in the middle of that must not leave it paused
        AltoClefSettings.getInstance().setInteractionPaused(false);
    }

    private <T> void set(Settings.Setting<T> setting, T value) {
        overrides.add(new Held<>(setting, setting.value, value));
        setting.value = value;
    }
}
