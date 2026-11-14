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
import baritone.utils.IRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.awt.Color;

// the same skin and contour for every shape; textured ghost blocks are drawn by EffortlessGhosts alongside it
final class SelectionOverlay {
    private AreaMask previous;
    private SelectionSurface surface;
    // cap per-frame work; the normal selection limit is 32768 cells
    private static final int MAX_FACES = 24000;

    void draw(PoseStack stack, AreaMask mask, JobSchematic job, boolean breaking, Level world) {
        if (previous != mask) {
            surface = new SelectionSurface(mask);
            previous = mask;
        }
        Color boundary = breaking ? Baritone.settings().colorEffortlessBreak.value : new Color(0xFFF1CE);
        boolean through = Baritone.settings().effortlessOutlineThroughBlocks.value;
        double cx = IRenderer.renderManager.renderPosX();
        double cy = IRenderer.renderManager.renderPosY();
        double cz = IRenderer.renderManager.renderPosZ();
        BufferBuilder buf = IRenderer.startQuads();
        try {
            int n = 0;
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            for (SelectionSurface.Face f : surface.faces) {
                if (++n > MAX_FACES) break;
                Color color = boundary;
                if (job != null && world != null) {
                    EffortlessJob owner = job.owner(f.x(), f.y(), f.z());
                    if (owner != null && owner.outcome(f.x(), f.y(), f.z(), world.getBlockState(at.set(f.x(), f.y(), f.z())))
                            == EffortlessJob.Outcome.UNTOUCHED) color = Baritone.settings().colorEffortlessUntouched.value;
                }
                // four checks per block face, fixed in world space so they don't crawl as the camera moves
                for (int u = 0; u < 2; u++) for (int v = 0; v < 2; v++) {
                    float alpha = ((u + v) & 1) == 0 ? 0.035f : 0.105f;
                    if (breaking) alpha *= 1.5f;
                    int argb = (Math.round(alpha * 255) << 24) | (color.getRGB() & 0xFFFFFF);
                    double offset = f.sign() * 0.003;
                    if (f.sign() > 0) {
                        vertex(buf, stack, f, u * .5, v * .5, offset, cx, cy, cz, argb);
                        vertex(buf, stack, f, (u + 1) * .5, v * .5, offset, cx, cy, cz, argb);
                        vertex(buf, stack, f, (u + 1) * .5, (v + 1) * .5, offset, cx, cy, cz, argb);
                        vertex(buf, stack, f, u * .5, (v + 1) * .5, offset, cx, cy, cz, argb);
                    } else {
                        vertex(buf, stack, f, u * .5, v * .5, offset, cx, cy, cz, argb);
                        vertex(buf, stack, f, u * .5, (v + 1) * .5, offset, cx, cy, cz, argb);
                        vertex(buf, stack, f, (u + 1) * .5, (v + 1) * .5, offset, cx, cy, cz, argb);
                        vertex(buf, stack, f, (u + 1) * .5, v * .5, offset, cx, cy, cz, argb);
                    }
                }
            }
        } finally { IRenderer.endQuads(buf, through); }
        buf = IRenderer.startLines(boundary, 0.95f);
        try {
            int n = 0;
            for (SelectionSurface.Edge e : surface.edges) {
                if (++n > MAX_FACES * 2) break;
                IRenderer.emitLine(buf, stack, new Vec3(e.a().x(), e.a().y(), e.a().z()),
                        new Vec3(e.b().x(), e.b().y(), e.b().z()), Math.max(1.5f, Baritone.settings().selectionLineWidth.value));
            }
        } finally { IRenderer.endLines(buf, through); }
    }

    private static void vertex(BufferBuilder buf, PoseStack stack, SelectionSurface.Face f, double u, double v,
                               double offset, double cx, double cy, double cz, int color) {
        double x = f.a().x() + (f.b().x() - f.a().x()) * u + (f.d().x() - f.a().x()) * v;
        double y = f.a().y() + (f.b().y() - f.a().y()) * u + (f.d().y() - f.a().y()) * v;
        double z = f.a().z() + (f.b().z() - f.a().z()) * u + (f.d().z() - f.a().z()) * v;
        if (f.axis() == 0) x += offset;
        if (f.axis() == 1) y += offset;
        if (f.axis() == 2) z += offset;
        buf.addVertex(stack.last(), (float)(x - cx), (float)(y - cy), (float)(z - cz)).setColor(color);
    }
}
