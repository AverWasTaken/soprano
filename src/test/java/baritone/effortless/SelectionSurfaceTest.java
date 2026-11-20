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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SelectionSurfaceTest {
    @Test
    public void sharedFacesAndFlatSeamsDisappear() {
        AreaMask mask = ShapeGeometry.build(ShapeSpec.of(ShapeKind.CUBE),
                List.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(1, 1, 1)));
        SelectionSurface skin = new SelectionSurface(mask);
        assertEquals(24, skin.faces.size());
        // twelve outer cube edges, each two blocks long; no lines across any of its six flat faces
        assertEquals(24, skin.edges.size());
        for (SelectionSurface.Edge e : skin.edges) {
            int fixedBoundaryAxes = 0;
            if (e.a().x() == e.b().x() && (e.a().x() == 0 || e.a().x() == 2)) fixedBoundaryAxes++;
            if (e.a().y() == e.b().y() && (e.a().y() == 0 || e.a().y() == 2)) fixedBoundaryAxes++;
            if (e.a().z() == e.b().z() && (e.a().z() == 0 || e.a().z() == 2)) fixedBoundaryAxes++;
            assertEquals(2, fixedBoundaryAxes);
        }
    }

    @Test
    public void hollowShapeKeepsItsInsideContour() {
        AreaMask mask = ShapeGeometry.build(ShapeSpec.of(ShapeKind.CUBE).withFill(ShapeSpec.Fill.HOLLOW),
                List.of(BlockPos.ZERO, new BlockPos(2, 0, 2), new BlockPos(2, 2, 2)));
        SelectionSurface skin = new SelectionSurface(mask);
        assertEquals(60, skin.faces.size());
        assertEquals(48, skin.edges.size());
    }

    @Test
    public void startMarkerAndClickUseTheSameCellOnEveryFace() {
        BlockPos target = new BlockPos(5, 10, -3);
        for (Direction face : Direction.values()) {
            assertEquals(target.relative(face), BuildMode.startCell(target, face, true, false));
            assertEquals(target, BuildMode.startCell(target, face, true, true));
            assertEquals(target, BuildMode.startCell(target, face, false, false));
        }
    }
}
