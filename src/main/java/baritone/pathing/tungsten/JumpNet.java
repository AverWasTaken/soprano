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

package baritone.pathing.tungsten;

import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.Direction;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

// a small net that guesses, from the blocks round a jump, whether the sim will find a way to make it: an NNUE's shape (a
// wide first layer over sparse inputs, which is a sum of a few dozen rows, then two small dense ones). the planner asks the
// sim about hundreds of jumps a search and most of them come to nothing; the ones this is sure about, it doesn't ask
// (Settings.useJumpNet). it learns from every jump the sim does work out, as the bot plays, and keeps what it's learned
// between sessions (see JumpNetStore)
public final class JumpNet {

    // the blocks round a jump, turned so the jump goes +u (and the landing's off to +v, if at all): u along, v across, w up
    static final int U0 = -2, U1 = 10, V0 = -3, V1 = 9, W0 = -5, W1 = 3;
    static final int NU = U1 - U0 + 1, NV = V1 - V0 + 1, NW = W1 - W0 + 1;
    // what a block is, for this: nothing (not an input), a full block, a low one (slab, head, pot), a post (fence, wall, bars,
    // pane), something else part of a block (stair, ...), climbable, water, something that hurts or resets (lava, fire,
    // cactus, magma), slippery, bouncy or slow (slime, honey, soul sand)
    static final int KINDS = 10;
    static final int GRID = NU * NV * NW * KINDS;
    // and the jump itself: how far along, across, up or down, and what's under src
    static final int F_U = GRID, F_V = F_U + 11, F_DY = F_V + 11, F_FLOOR = F_DY + 12, INPUTS = F_FLOOR + KINDS;

    static final int H1 = 32, H2 = 16;

    final float[] w1 = new float[INPUTS * H1], b1 = new float[H1];
    final float[] w2 = new float[H1 * H2], b2 = new float[H2];
    final float[] w3 = new float[H2];
    float b3;
    // how many examples it's learned from
    long seen;

    public JumpNet(long seed) {
        Random r = new Random(seed);
        // (the first layer sums a few dozen rows: small, or that sum swamps everything)
        for (int i = 0; i < w1.length; i++) {
            w1[i] = (float) (r.nextGaussian() * 0.05);
        }
        for (int i = 0; i < w2.length; i++) {
            w2[i] = (float) (r.nextGaussian() * Math.sqrt(2.0 / H1));
        }
        for (int i = 0; i < w3.length; i++) {
            w3[i] = (float) (r.nextGaussian() * Math.sqrt(2.0 / H2));
        }
        b3 = -1f; // most jumps asked about don't work out
    }

    // ---- the inputs

    private static final Map<BlockState, Integer> KIND = new ConcurrentHashMap<>();

    static int kind(BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        Integer k = KIND.get(state);
        if (k == null) {
            k = kindOf(state);
            KIND.put(state, k);
        }
        return k;
    }

    private static int kindOf(BlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.LAVA || b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CACTUS || b == Blocks.MAGMA_BLOCK
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.POWDER_SNOW || b == Blocks.COBWEB) {
            return 7;
        }
        if (!state.getFluidState().isEmpty() && b == Blocks.WATER) {
            return 6;
        }
        if (MovementHelper.isClimbable(b)) {
            return 5;
        }
        VoxelShape shape;
        try {
            shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (Throwable t) {
            return 4;
        }
        if (shape.isEmpty()) {
            return 0;
        }
        if (b.getFriction() > 0.7F) {
            return 8;
        }
        if (b == Blocks.SLIME_BLOCK || b == Blocks.HONEY_BLOCK || b == Blocks.SOUL_SAND || b.getSpeedFactor() < 1F || b.getJumpFactor() < 1F) {
            return 9;
        }
        double minX = shape.min(Direction.Axis.X), maxX = shape.max(Direction.Axis.X);
        double minZ = shape.min(Direction.Axis.Z), maxZ = shape.max(Direction.Axis.Z);
        double maxY = shape.max(Direction.Axis.Y);
        if (minX <= 0 && maxX >= 1 && minZ <= 0 && maxZ >= 1 && maxY >= 1 && maxY <= 1 && shape.min(Direction.Axis.Y) <= 0) {
            return 1;
        }
        if (maxX - minX <= 0.5 && maxZ - minZ <= 0.5) {
            return 3; // a post (a fence, a wall, bars and panes end on end)
        }
        if (maxY <= 0.6) {
            return 2;
        }
        return 4;
    }

    // the active inputs for a jump from src by (dx, dy, dz): a few dozen indices, sorted
    public static int[] features(CalculationContext context, int x, int y, int z, int dx, int dy, int dz) {
        int sx = dx < 0 ? -1 : 1, sz = dz < 0 ? -1 : 1;
        boolean swap = Math.abs(dz) > Math.abs(dx);
        int along = swap ? Math.abs(dz) : Math.abs(dx), across = swap ? Math.abs(dx) : Math.abs(dz);
        int[] out = new int[128];
        int n = 0;
        for (int u = U0; u <= U1; u++) {
            for (int v = V0; v <= V1; v++) {
                int wx = x + (swap ? v * sx : u * sx), wz = z + (swap ? u * sz : v * sz);
                for (int w = W0; w <= W1; w++) {
                    int k = kind(context.get(wx, y + w, wz));
                    if (k == 0) {
                        continue;
                    }
                    if (n == out.length) {
                        out = Arrays.copyOf(out, n * 2);
                    }
                    out[n++] = (((u - U0) * NV + (v - V0)) * NW + (w - W0)) * KINDS + k;
                }
            }
        }
        if (n + 4 > out.length) {
            out = Arrays.copyOf(out, n + 4);
        }
        out[n++] = F_U + Math.min(along, 10);
        out[n++] = F_V + Math.min(across, 10);
        out[n++] = F_DY + Math.max(0, Math.min(11, dy + 9));
        out[n++] = F_FLOOR + kind(context.get(x, y - 1, z));
        int[] f = Arrays.copyOf(out, n);
        Arrays.sort(f);
        return f;
    }

    // ---- guessing and learning

    // the chance the sim makes this jump
    public synchronized float predict(int[] f) {
        float[] h1 = new float[H1], h2 = new float[H2];
        return forward(f, h1, h2);
    }

    private float forward(int[] f, float[] h1, float[] h2) {
        System.arraycopy(b1, 0, h1, 0, H1);
        for (int i : f) {
            int o = i * H1;
            for (int j = 0; j < H1; j++) {
                h1[j] += w1[o + j];
            }
        }
        for (int j = 0; j < H1; j++) {
            h1[j] = Math.max(0f, Math.min(1f, h1[j])); // clipped, NNUE style
        }
        for (int k = 0; k < H2; k++) {
            float s = b2[k];
            for (int j = 0; j < H1; j++) {
                s += h1[j] * w2[j * H2 + k];
            }
            h2[k] = Math.max(0f, s);
        }
        float s = b3;
        for (int k = 0; k < H2; k++) {
            s += h2[k] * w3[k];
        }
        return (float) (1 / (1 + Math.exp(-s)));
    }

    // one step towards the right answer for f (plain SGD on the log loss; weight: how much this one counts)
    public synchronized void learn(int[] f, boolean made, float rate, float weight) {
        float[] h1 = new float[H1], h2 = new float[H2];
        float p = forward(f, h1, h2);
        float g = (p - (made ? 1f : 0f)) * weight; // d loss / d out
        float[] g2 = new float[H2];
        for (int k = 0; k < H2; k++) {
            g2[k] = h2[k] > 0 ? g * w3[k] : 0f;
            w3[k] -= rate * g * h2[k];
        }
        b3 -= rate * g;
        float[] g1 = new float[H1];
        for (int j = 0; j < H1; j++) {
            float s = 0;
            for (int k = 0; k < H2; k++) {
                s += g2[k] * w2[j * H2 + k];
                w2[j * H2 + k] -= rate * g2[k] * h1[j];
            }
            g1[j] = h1[j] > 0 && h1[j] < 1 ? s : 0f;
        }
        for (int k = 0; k < H2; k++) {
            b2[k] -= rate * g2[k];
        }
        for (int j = 0; j < H1; j++) {
            b1[j] -= rate * g1[j];
        }
        for (int i : f) {
            int o = i * H1;
            for (int j = 0; j < H1; j++) {
                w1[o + j] -= rate * g1[j];
            }
        }
        seen++;
    }

    // ---- kept

    static final int MAGIC = 0x4A4E4554; // "JNET"
    static final int VERSION = 1;

    public synchronized void write(OutputStream os) throws IOException {
        DataOutputStream out = new DataOutputStream(os);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(INPUTS);
        out.writeInt(H1);
        out.writeInt(H2);
        out.writeLong(seen);
        for (float[] a : new float[][]{w1, b1, w2, b2, w3}) {
            for (float v : a) {
                out.writeFloat(v);
            }
        }
        out.writeFloat(b3);
        out.flush();
    }

    // null if it isn't one of ours (another version, other sizes)
    public static JumpNet read(InputStream is) throws IOException {
        DataInputStream in = new DataInputStream(is);
        if (in.readInt() != MAGIC || in.readInt() != VERSION || in.readInt() != INPUTS || in.readInt() != H1 || in.readInt() != H2) {
            return null;
        }
        JumpNet net = new JumpNet(0);
        net.seen = in.readLong();
        for (float[] a : new float[][]{net.w1, net.b1, net.w2, net.b2, net.w3}) {
            for (int i = 0; i < a.length; i++) {
                a[i] = in.readFloat();
            }
        }
        net.b3 = in.readFloat();
        return net;
    }

    public long seen() {
        return seen;
    }
}
