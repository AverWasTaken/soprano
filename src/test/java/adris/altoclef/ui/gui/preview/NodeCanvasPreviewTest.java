package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.kit.CanvasView;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.NodeCanvas;
import adris.altoclef.ui.gui.kit.NodeCanvasFixtures;
import adris.altoclef.ui.gui.kit.NodeCard;
import adris.altoclef.ui.gui.kit.NodeWire;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Widget;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertTrue;

// the bare canvas, cropped out of the real shell so it's judged against the real panel colour. the look gets compared
// against the mock's nodes view by eye, a png nobody opened proves nothing
public class NodeCanvasPreviewTest {

    private static final class CanvasTab implements Tab {
        final NodeCanvas canvas;

        CanvasTab(NodeCanvas canvas) {
            this.canvas = canvas;
        }

        @Override
        public String id() {
            return "nodes";
        }

        @Override
        public String title() {
            return "Nodes";
        }

        @Override
        public Icon icon() {
            return Icon.NODES;
        }

        @Override
        public Widget content() {
            return canvas;
        }
    }

    private NodeCanvas canvas;

    private Preview open(NodeCanvasFixtures.Host host) throws Exception {
        Preview p = new Preview(1920, 1200, 1f, new FakeFacade(), pv -> {
            canvas = new NodeCanvas(pv.theme, host, host);
            return List.of(new CanvasTab(canvas));
        });
        p.settle();
        p.advance(500);
        return p;
    }

    private void save(Preview p, String name, boolean dark) throws Exception {
        p.shell.setDarkMode(dark);
        p.advance(500);
        BufferedImage all = p.render().image();
        int pad = 8;
        BufferedImage crop = all.getSubimage(canvas.x() - pad, canvas.y() - pad, canvas.w() + 2 * pad, canvas.h() + 2 * pad);
        Path out = Preview.OUT.resolve("nodecanvas_" + name + (dark ? "_dark" : "_light") + ".png");
        Files.createDirectories(out.getParent());
        ImageIO.write(crop, "png", out.toFile());
        assertTrue(Files.size(out) > 0);
    }

    private void bothThemes(Preview p, String name) throws Exception {
        save(p, name, true);
        save(p, name, false);
    }

    // centred on the loop at this zoom, ignoring whether it all fits: that's the point of 1.5
    private void centre(int zoomIndex) {
        CanvasView.Rect b = canvas.worldBounds();
        float z = CanvasView.zoomOf(zoomIndex);
        float pan = (canvas.w() / canvas.view().scale() - b.w() * z) / 2f - b.x() * z;
        float panY = (canvas.h() / canvas.view().scale() - b.h() * z) / 2f - b.y() * z;
        canvas.view().restore(pan, panY, zoomIndex);
    }

    @Test
    public void woodLoopAtEveryZoom() throws Exception {
        NodeCanvasFixtures.Host host = new NodeCanvasFixtures.Host(NodeCanvasFixtures.woodLoopCards(), NodeCanvasFixtures.woodLoopWires());
        Preview p = open(host);
        for (int i = 0; i < CanvasView.steps(); i++) {
            centre(i);
            int pct = Math.round(CanvasView.zoomOf(i) * 100);
            bothThemes(p, "wood_" + pct);
        }
    }

    @Test
    public void midRunHighlight() throws Exception {
        NodeCanvasFixtures.Host host = new NodeCanvasFixtures.Host(NodeCanvasFixtures.midRunCards(), NodeCanvasFixtures.midRunWires());
        host.selectedCard = "r";
        Preview p = open(host);
        centre(2);
        bothThemes(p, "run");
    }

    @Test
    public void everyCardState() throws Exception {
        List<NodeCard> cards = NodeCanvasFixtures.statesCards();
        List<NodeWire> wires = NodeCanvasFixtures.statesWires();
        NodeCanvasFixtures.Host host = new NodeCanvasFixtures.Host(cards, wires);
        host.selectedWire = wires.get(1).key();
        Preview p = open(host);
        centre(2);
        bothThemes(p, "states");
    }

    @Test
    public void wireBeingDraggedOverACard() throws Exception {
        NodeCanvasFixtures.Host host = new NodeCanvasFixtures.Host(NodeCanvasFixtures.woodLoopCards(), NodeCanvasFixtures.woodLoopWires());
        Preview p = open(host);
        centre(2);
        p.press(canvas.view().screenX(384 + 176), canvas.view().screenY(40 + 68));
        p.drag(canvas.view().screenX(450), canvas.view().screenY(190));
        bothThemes(p, "wiring");
        p.release(canvas.view().screenX(450), canvas.view().screenY(190));
    }

    @Test
    public void twoHundredCardsAtHalfZoom() throws Exception {
        NodeCanvasFixtures.Host host = new NodeCanvasFixtures.Host(NodeCanvasFixtures.stressCards(), NodeCanvasFixtures.stressWires());
        Preview p = open(host);
        canvas.view().restore(16, 60, 0);
        bothThemes(p, "stress_50");
    }
}
