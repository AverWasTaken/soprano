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

package adris.altoclef.ui.gui.mc.ghost;

import adris.altoclef.ui.gui.mc.ghost.GhostScan.Ghost;
import adris.altoclef.ui.gui.mc.ghost.GhostScan.Kind;
import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.schematic.ISchematic;
import baritone.effortless.EffortlessJob;
import baritone.effortless.EffortlessManager;
import baritone.effortless.JobSchematic;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Effortless Soprano's preview on the schematic ghost's machinery: the job you're setting up (or the one running), a
// hologram of the exact block for every cell it will place, red for every block it will break, grey for blocks in the
// area it will leave alone. the randomizer's pick is the palette's hash of the cell, so the hologram is the block that
// will actually go there
public final class EffortlessGhosts implements SchematicGhostRenderer.Source, GhostField.Scanner {

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int PAD = 18;

    private final EffortlessManager manager;
    private final Kind[] kinds = new Kind[PAD * PAD * PAD];
    private final BlockState[] wants = new BlockState[PAD * PAD * PAD];
    private final BlockState[] haves = new BlockState[PAD * PAD * PAD];

    private EffortlessGhosts(EffortlessManager manager) {
        this.manager = manager;
    }

    public static void attach(IBaritone primary) {
        EffortlessGhosts ghosts = new EffortlessGhosts(((Baritone) primary).getEffortlessManager());
        SchematicGhostRenderer.attach(primary, ghosts, ghosts);
    }

    // ----- what to show

    @Override
    public SchematicGhostRenderer.Target target() {
        // what you're setting up first, then what's being built, then the queued ones you asked to see
        JobSchematic s = manager.previewSchematic();
        if (s == null) {
            s = manager.runningSchematic();
        }
        if (s == null) {
            s = manager.shownSchematic();
        }
        if (s == null) {
            return null;
        }
        return new SchematicGhostRenderer.Target("effortless", s, s.origin().getX(), s.origin().getY(), s.origin().getZ());
    }

    @Override
    public float alpha() {
        return Baritone.settings().effortlessPreviewOpacity.value;
    }

    @Override
    public int distance() {
        return Baritone.settings().effortlessPreviewDistance.value;
    }

    @Override
    public int rgb(Kind kind) {
        return switch (kind) {
            case EXTRA -> Baritone.settings().colorEffortlessBreak.value.getRGB() & 0xFFFFFF;
            case UNTOUCHED -> Baritone.settings().colorEffortlessUntouched.value.getRGB() & 0xFFFFFF;
            case WRONG_STATE, WRONG_BLOCK -> Baritone.settings().colorEffortlessReplace.value.getRGB() & 0xFFFFFF;
            default -> SchematicGhostRenderer.kindRgb(kind);
        };
    }

    // nothing here is a hint: red is a promise and grey is "this one stays", both at full preview strength. the grey is
    // a dull colour on purpose, it doesn't need a lower alpha to stay out of the red's way
    @Override
    public boolean faint(Kind kind) {
        return false;
    }

    @Override
    public boolean logs() {
        return false;
    }

    // ----- what each cell will get

    // the same kinds the schematic ghost draws: MISSING is a hologram of what goes there, WRONG_STATE a hologram of
    // what replaces the block there, EXTRA a red box for a break, UNTOUCHED a grey one
    static Kind kind(EffortlessJob.Outcome outcome) {
        return switch (outcome) {
            case PLACE -> Kind.MISSING;
            case REPLACE -> Kind.WRONG_STATE;
            case BREAK -> Kind.EXTRA;
            case UNTOUCHED -> Kind.UNTOUCHED;
        };
    }

    @Override
    public List<Ghost> section(ISchematic schematic, int ox, int oy, int oz, GhostScan.World world, int sx, int sy, int sz) {
        if (!(schematic instanceof JobSchematic s)) {
            return List.of();
        }
        int baseX = (sx << 4) - 1;
        int baseY = (sy << 4) - 1;
        int baseZ = (sz << 4) - 1;
        Arrays.fill(kinds, null);
        Arrays.fill(wants, null);
        Arrays.fill(haves, null);
        var b = s.bounds();
        int x0 = Math.max(0, b.minX - baseX);
        int x1 = Math.min(PAD - 1, b.maxX() - baseX);
        int y0 = Math.max(0, b.minY - baseY);
        int y1 = Math.min(PAD - 1, b.maxY() - baseY);
        int z0 = Math.max(0, b.minZ - baseZ);
        int z1 = Math.min(PAD - 1, b.maxZ() - baseZ);
        for (int x = x0; x <= x1; x++) {
            int wx = baseX + x;
            for (int z = z0; z <= z1; z++) {
                int wz = baseZ + z;
                if (!world.loaded(wx >> 4, wz >> 4)) {
                    continue;
                }
                for (int y = y0; y <= y1; y++) {
                    int wy = baseY + y;
                    EffortlessJob job = s.owner(wx, wy, wz);
                    if (job == null) {
                        continue;
                    }
                    BlockState have = world.state(wx, wy, wz);
                    EffortlessJob.Outcome o = job.outcome(wx, wy, wz, have);
                    // air the job won't fill and a block it already got right have nothing to say
                    if (o == EffortlessJob.Outcome.UNTOUCHED && (have.isAir() || have == job.desired(wx, wy, wz))) {
                        continue;
                    }
                    int i = index(x, y, z);
                    kinds[i] = kind(o);
                    haves[i] = have;
                    wants[i] = o == EffortlessJob.Outcome.BREAK || o == EffortlessJob.Outcome.UNTOUCHED ? AIR : job.desired(wx, wy, wz);
                }
            }
        }
        List<Ghost> out = new ArrayList<>();
        for (int x = 1; x <= 16; x++) {
            for (int z = 1; z <= 16; z++) {
                for (int y = 1; y <= 16; y++) {
                    int i = index(x, y, z);
                    Kind k = kinds[i];
                    if (k == null) {
                        continue;
                    }
                    int faces = faces(x, y, z, k);
                    if (faces != 0) {
                        out.add(new Ghost(baseX + x, baseY + y, baseZ + z, wants[i], haves[i], k, faces));
                    }
                }
            }
        }
        return out;
    }

    // a box shows the sides that don't touch another box of its own colour, so a red block of stone is one red block
    // and not a grid. a hologram hides a side behind a solid world block or a solid neighbouring hologram, like the
    // schematic ghost does
    private int faces(int x, int y, int z, Kind k) {
        boolean box = k == Kind.EXTRA || k == Kind.UNTOUCHED;
        int faces = 0;
        for (Direction d : DIRECTIONS) {
            int n = index(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
            boolean hidden;
            if (box) {
                hidden = kinds[n] == k;
            } else {
                hidden = solid(haves[n]) && kinds[n] == null || solid(wants[n]) && kinds[n] != null && kinds[n] != Kind.EXTRA;
            }
            if (!hidden) {
                faces |= 1 << d.get3DDataValue();
            }
        }
        return faces;
    }

    private static boolean solid(BlockState state) {
        return state != null && state.isSolidRender();
    }

    private static int index(int x, int y, int z) {
        return (x * PAD + z) * PAD + y;
    }
}
