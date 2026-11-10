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

package baritone.effortless;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// the build mode itself: on once a shape is picked in the radial, off when "none" is. while it's on the player's own
// left and right clicks set a shape's corners instead of hitting and using things. right places, left breaks, the
// other button (or esc) drops the half set shape. game thread only
public final class BuildMode {

    // which block a place job takes when you don't say otherwise
    public enum PaletteSource {
        HAND, OFFHAND, HOTBAR
    }

    // which blocks a break job takes
    public enum BreakFilter {
        ALL, HAND, OFFHAND, NOT_HAND
    }

    private static boolean active;
    private static EffortlessJob.FillMode placeMode = EffortlessJob.FillMode.FILL_EMPTY;
    private static PaletteSource palette = PaletteSource.HAND;
    private static BreakFilter breakFilter = BreakFilter.ALL;
    private static boolean skipContainers;
    // the hotbar mix: a weight per slot (0 leaves the slot out), whether that slot's block gets turned at random, and
    // the pattern's seed. weights are relative, 3 and 1 is 75% and 25%
    private static final int[] slotWeight = {1, 1, 1, 1, 1, 1, 1, 1, 1};
    private static final boolean[] slotTurn = new boolean[9];
    private static long mixSeed;
    // where a place job's blocks come from, null until it's picked (then the setting's default)
    private static baritone.api.utils.EffortlessMaterials materials;
    // an empty hand's right clicks mark a storage box instead of placing
    private static boolean storagePick;
    // the mode the shape being set up is for, null while no corner is down
    private static EffortlessJob.Mode pending;
    // the game tick of the last use click we took. a held right button calls startUseItem every few ticks, that's one
    // click, not a new corner each time
    private static long lastUseTick = -100;
    private static long lastAttackTick = -100;
    private static long ticks;
    private static String lastBar;

    private BuildMode() {
    }

    private static EffortlessManager manager() {
        return ((Baritone) BaritoneAPI.getProvider().getPrimaryBaritone()).getEffortlessManager();
    }

    public static boolean active() {
        return active;
    }

    public static EffortlessJob.Mode pending() {
        return pending;
    }

    // the radial picked a shape (null for none)
    public static void choose(ShapeKind kind) {
        EffortlessManager m = manager();
        cancel();
        if (kind == null) {
            active = false;
            m.draft(null, null);
            say("Effortless off");
            return;
        }
        m.spec(m.spec().withKind(kind));
        active = true;
    }

    // the toggle key: off goes off, on comes back with the last shape
    public static void toggle() {
        if (active) {
            choose(null);
        } else {
            choose(manager().spec().kind());
            say("Effortless on: " + manager().spec().kind().title);
        }
    }

    public static EffortlessJob.FillMode placeMode() {
        return placeMode;
    }

    public static void placeMode(EffortlessJob.FillMode m) {
        placeMode = m;
        refreshDraft();
    }

    public static PaletteSource palette() {
        return palette;
    }

    public static void palette(PaletteSource p) {
        palette = p;
        refreshDraft();
    }

    public static BreakFilter breakFilter() {
        return breakFilter;
    }

    public static void breakFilter(BreakFilter f) {
        breakFilter = f;
        refreshDraft();
    }

    public static int slotWeight(int slot) {
        return slotWeight[slot];
    }

    public static void slotWeight(int slot, int w) {
        slotWeight[slot] = Math.max(0, w);
        refreshDraft();
    }

    public static boolean slotTurn(int slot) {
        return slotTurn[slot];
    }

    public static void slotTurn(int slot, boolean on) {
        slotTurn[slot] = on;
        refreshDraft();
    }

    // a new pattern for the same mix
    public static void reroll() {
        mixSeed = new java.util.Random().nextLong();
        refreshDraft();
    }

    // the block in a hotbar slot, if it's one
    public static Optional<BlockState> slotBlock(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return Optional.empty();
        }
        ItemStack s = mc.player.getInventory().getItem(slot);
        if (s.getItem() instanceof BlockItem bi && !bi.getBlock().defaultBlockState().isAir()) {
            return Optional.of(bi.getBlock().defaultBlockState());
        }
        return Optional.empty();
    }

    // the share of the mix this slot gets, 0 to 100, for the panel. slots with the same block add up
    public static int share(int slot) {
        Optional<BlockState> mine = slotBlock(slot);
        if (mine.isEmpty() || slotWeight[slot] == 0) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < 9; i++) {
            if (slotBlock(i).isPresent()) {
                total += slotWeight[i];
            }
        }
        return total == 0 ? 0 : Math.round(100f * slotWeight[slot] / total);
    }

    public static baritone.api.utils.EffortlessMaterials materials() {
        return materials != null ? materials : Baritone.settings().effortlessMaterials.value;
    }

    public static void materials(baritone.api.utils.EffortlessMaterials m) {
        materials = m;
    }

    public static boolean storagePick() {
        return storagePick;
    }

    public static boolean skipContainers() {
        return skipContainers;
    }

    public static void skipContainers(boolean on) {
        skipContainers = on;
        refreshDraft();
    }

    // drops the corners set so far, the mode stays on
    public static void cancel() {
        EffortlessManager m = manager();
        m.clearPoints();
        pending = null;
        storagePick = false;
    }

    // ----- the clicks. true = it was ours and the game should do nothing with it

    public static boolean onUse() {
        if (!capturing()) {
            return false;
        }
        boolean repeat = ticks - lastUseTick <= 5;
        lastUseTick = ticks;
        if (repeat) {
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        // an empty hand marks storage: two corners around the chests, whatever shape is picked
        if (storagePick || (pending == null && mc.player.getMainHandItem().isEmpty())) {
            storageClick();
            return true;
        }
        click(EffortlessJob.Mode.PLACE);
        return true;
    }

    public static boolean onAttack() {
        if (!capturing()) {
            return false;
        }
        boolean repeat = ticks - lastAttackTick <= 1;
        lastAttackTick = ticks;
        if (!repeat) {
            click(EffortlessJob.Mode.BREAK);
        }
        return true;
    }

    // a held left button: no mining while build mode has the mouse
    public static boolean swallowHeldAttack() {
        return capturing();
    }

    public static boolean capturing() {
        Minecraft mc = Minecraft.getInstance();
        return active && mc.player != null && mc.gui.screen() == null && !mc.player.isSpectator();
    }

    private static void click(EffortlessJob.Mode mode) {
        EffortlessManager m = manager();
        if (pending != null && pending != mode) {
            // the other button: drop the shape, don't start one of the other kind on the same click
            cancel();
            say("Cancelled");
            return;
        }
        Optional<BlockPos> at = aim(mode);
        if (at.isEmpty()) {
            return;
        }
        pending = mode;
        refreshDraft();
        List<BlockPos> set = setPoints(m);
        m.point(set.size(), at.get());
        set = setPoints(m);
        int need = m.spec().kind().clicks(Baritone.settings().effortlessThreeClickHeight.value);
        if (set.size() < need) {
            return;
        }
        finish(m, mode);
    }

    private static void storageClick() {
        EffortlessManager m = manager();
        Optional<BlockPos> at = aim(EffortlessJob.Mode.BREAK);
        if (at.isEmpty()) {
            return;
        }
        if (!storagePick) {
            storagePick = true;
            m.clearPoints();
            m.point(0, at.get());
            say("Storage: right click the other corner (with an empty hand)");
            return;
        }
        BlockPos a = m.points().get(0);
        m.addStorage(new StorageArea(a, at.get(), StorageArea.Role.BOTH));
        int n = m.queue().storage().size();
        say("Storage " + n + " marked: input and output. #ef storage to change that");
        cancel();
    }

    private static List<BlockPos> setPoints(EffortlessManager m) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : m.points()) {
            if (p == null) {
                break;
            }
            out.add(p);
        }
        return out;
    }

    private static void finish(EffortlessManager m, EffortlessJob.Mode mode) {
        try {
            EffortlessOptions o = options(mode).withSpec(m.spec());
            List<BlockPos> pts = setPoints(m);
            EffortlessJob job = mode == EffortlessJob.Mode.PLACE
                    ? o.placeJob(pts, m.heldBlock(false), m.heldBlock(true))
                    : o.breakJob(pts, m.heldBlock(false), m.heldBlock(true));
            m.checkSize(job);
            m.submit(job);
        } catch (EffortlessOptions.Bad | EffortlessManager.NotReady | IllegalArgumentException e) {
            say(e.getMessage());
        }
        cancel();
    }

    // ----- aiming

    // where a click lands: the face you're looking at for a place (the cell in front of it, like placing a block by
    // hand), the block itself for a break, and with nothing in reach a point in the air that far out along your look
    static Optional<BlockPos> aim(EffortlessJob.Mode mode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return Optional.empty();
        }
        // after the first corner the rest follow your look along the shape's plane, wherever you look. a storage box
        // just takes the block you look at
        List<BlockPos> set = setPoints(manager());
        if (!set.isEmpty() && !storagePick) {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 dir = mc.player.getLookAngle();
            Optional<BlockPos> next = ClickAim.next(manager().spec(), set, eye.x, eye.y, eye.z, dir.x, dir.y, dir.z);
            if (next.isPresent()) {
                return next;
            }
        }
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
            return Optional.of(mode == EffortlessJob.Mode.PLACE ? b.getBlockPos().relative(b.getDirection()) : b.getBlockPos());
        }
        double reach = Baritone.settings().effortlessReach.value;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 out = eye.add(mc.player.getLookAngle().scale(reach));
        return Optional.of(BlockPos.containing(out));
    }

    // what the preview uses for the corner not set yet
    public static Optional<BlockPos> crosshair() {
        return aim(pending == null ? EffortlessJob.Mode.PLACE : pending);
    }

    // ----- the draft the preview shows

    static EffortlessOptions options(EffortlessJob.Mode mode) {
        StringBuilder b = new StringBuilder();
        if (mode == EffortlessJob.Mode.PLACE) {
            b.append("mode=").append(switch (placeMode) {
                case FILL_EMPTY -> "fill";
                case OVERWRITE -> "overwrite";
                case REPLACE -> "replace";
            });
            if (placeMode != EffortlessJob.FillMode.REPLACE) {
                String blocks = paletteBlocks();
                if (blocks != null) {
                    b.append(" block=").append(blocks);
                }
                if (palette == PaletteSource.HOTBAR) {
                    b.append(" seed=").append((int) mixSeed);
                }
            }
        } else {
            switch (breakFilter) {
                case HAND -> b.append("hand");
                case OFFHAND -> b.append("offhand");
                case NOT_HAND -> b.append("nothand");
                default -> {
                }
            }
            if (skipContainers) {
                b.append(" skipcontainers");
            }
        }
        return EffortlessOptions.parse(manager().spec(), b.toString());
    }

    // the block= words for the palette choice. null leaves it to the default (the hand)
    private static String paletteBlocks() {
        return switch (palette) {
            case HAND -> null;
            case OFFHAND -> "offhand";
            case HOTBAR -> hotbarBlocks();
        };
    }

    // the hotbar's blocks as block= words: id*weight, @r where that slot turns at random. the same block in two slots
    // adds up. null when there's none (the job then says "hold a block")
    static String hotbarBlocks() {
        java.util.Map<String, int[]> mix = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 9; i++) {
            Optional<BlockState> b = slotBlock(i);
            if (b.isEmpty() || slotWeight[i] == 0) {
                continue;
            }
            int[] e = mix.computeIfAbsent(BlockFilter.idOf(b.get().getBlock()), k -> new int[2]);
            e[0] += slotWeight[i];
            if (slotTurn[i]) {
                e[1] = 1;
            }
        }
        if (mix.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        mix.forEach((id, e) -> parts.add(id + "*" + e[0] + (e[1] == 1 ? "@r" : "")));
        return String.join(",", parts);
    }

    private static void refreshDraft() {
        if (!active) {
            return;
        }
        EffortlessManager m = manager();
        if (pending == null) {
            m.draft(null, null);
            return;
        }
        try {
            m.draft(pending, options(pending));
        } catch (IllegalArgumentException e) {
            m.draft(null, null);
        }
    }

    // ----- the action bar

    public static void tick() {
        ticks++;
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (pending != null) {
            // the hand may have changed, the draft follows it
            refreshDraft();
        }
        if (ticks % 10 != 0) {
            return;
        }
        String bar = statusLine();
        if (!bar.equals(lastBar) || ticks % 40 == 0) {
            lastBar = bar;
            mc.gui.hud.setOverlayMessage(Component.literal(bar), false);
        }
    }

    static String statusLine() {
        EffortlessManager m = manager();
        ShapeSpec spec = m.spec();
        StringBuilder b = new StringBuilder("Effortless · ").append(spec.kind().title);
        if (storagePick) {
            return "Effortless · marking storage · right click the other corner";
        }
        if (pending == null) {
            b.append(" · right click to place, left click to break, empty hand marks storage");
            return b.toString();
        }
        AreaMask mask = m.currentMask(true);
        int need = spec.kind().clicks(Baritone.settings().effortlessThreeClickHeight.value);
        int at = setPoints(m).size() + 1;
        b.append(" · ").append(pending == EffortlessJob.Mode.PLACE ? "placing" : "breaking");
        b.append(" · click ").append(at).append(" of ").append(need).append(": ").append(step(spec.kind(), at, need));
        if (mask != null) {
            b.append(" · X ").append(mask.sizeX).append("  Y ").append(mask.sizeY).append("  Z ").append(mask.sizeZ)
                    .append(" · ").append(mask.count()).append(" blocks");
        }
        return b.toString();
    }

    // what the click you're about to make sets, in the shape's own words
    static String step(ShapeKind kind, int at, int need) {
        if (at >= 3) {
            return "how high";
        }
        return switch (kind) {
            case WALL, DIAGONAL_WALL -> need == 3 ? "where it ends" : "where it ends, and how high";
            case CUBOID -> need == 3 ? "the far corner on the ground" : "the far corner";
            case CIRCLE, SPHERE, CYLINDER -> "how wide";
            case LINE, DIAGONAL_LINE -> "where it ends";
            default -> "the other corner";
        };
    }

    static void say(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && msg != null) {
            mc.gui.hud.setOverlayMessage(Component.literal(msg.substring(0, 1).toUpperCase(Locale.ROOT) + msg.substring(1)), false);
        }
    }
}
