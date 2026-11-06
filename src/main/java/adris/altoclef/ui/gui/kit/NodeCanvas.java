package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the node editor's canvas: dots, wires, cards, the wire you're dragging and the wire's delete chip, in that order. it
// knows nothing about graphs, it draws whatever the Model hands it and tells the Listener what the mouse did. the Model
// hands out a new list whenever something changed: list identity is what says "work it all out again", so the common
// frame where nothing moved does no work past drawing
public class NodeCanvas extends Control implements FocusOnClick {

    public interface Model {
        // draw order, last on top
        List<NodeCard> cards();

        List<NodeWire> wires();

        String selectedCard();

        // NodeWire.key()
        String selectedWire();

        // a broken file or a broken store: you can look and pan, not change anything
        default boolean readOnly() {
            return false;
        }
    }

    public interface Listener {
        // null = nothing selected
        void select(String cardId);

        void move(String cardId, int x, int y);

        void connect(String from, String port, String to);

        // a wire let go on empty canvas
        void dropAt(String from, String port, float worldX, float worldY);

        void wireClicked(String wireKey);

        void deleteSelected();

        void viewChanged();
    }

    private enum Press { NONE, WIRING, CARD, WIRE, PAN, CHIP }

    private static final int FILLS = 0;
    private static final int CORNERS = 1;
    private static final int GLYPHS = 2;
    private static final int DRAG_THRESHOLD = 3;
    private static final int MAX_DOTS = 1000;
    private static final int DOT_STEP = 24;
    private static final int SHADOW_MAX_CARDS = 64;
    private static final int CHIP = 24;
    private static final int PORT_HIT = 14;
    private static final int WIRE_HIT = 6;

    private final Model model;
    private final Listener listener;
    private final CanvasView view;

    // worked out in sync(), only when the model hands out new lists or a drag moves something
    private List<NodeCard> syncedCards;
    private List<NodeWire> syncedWires;
    private boolean dirty = true;
    private Map<String, NodeCardLayout> layouts = new HashMap<>();
    private final List<NodeCard> cards = new ArrayList<>();
    private final List<NodeHits.Box> boxes = new ArrayList<>();
    private final List<NodeWire> wires = new ArrayList<>();
    private final List<WireRouter.Route> routes = new ArrayList<>();
    private final Map<String, Integer> indexOf = new HashMap<>();

    private Press press = Press.NONE;
    private boolean moved;
    private float pressX;
    private float pressY;
    private float mouseX;
    private float mouseY;
    private float panStartX;
    private float panStartY;
    private String dragId;
    private float dragStartX;
    private float dragStartY;
    private float dragX;
    private float dragY;
    // the card this press started on: where the wire comes from, or what gets carried
    private String pressId;
    private int wirePort;
    private String wirePortName;
    private int hotCard = -1;
    private NodeHits.PortHit hoverPort;
    private boolean hoverChip;
    private boolean lastPressHere;

    // the frame's drawing state, kept in fields so the frame allocates nothing
    private int[] vis = new int[0];
    private int[] cardX0 = new int[0];
    private int[] cardY0 = new int[0];
    private int[] cardX1 = new int[0];
    private int[] cardY1 = new int[0];
    private final int[] sx = new int[8];
    private final int[] sy = new int[8];
    private Canvas sinkCanvas;
    private int sinkColor;
    private final WireRouter.Sink fillSink = new WireRouter.Sink() {
        @Override
        public void segment(int x, int y, int w, int h) {
            sinkCanvas.fillRect(x, y, w, h, sinkColor);
        }

        @Override
        public void corner(int left, int top, int r, int quadrant) {
        }
    };
    private final WireRouter.Sink cornerSink = new WireRouter.Sink() {
        @Override
        public void segment(int x, int y, int w, int h) {
        }

        @Override
        public void corner(int left, int top, int r, int quadrant) {
            sinkCanvas.cornerLine(left, top, r, quadrant, sinkColor);
        }
    };

    public NodeCanvas(Theme theme, Model model, Listener listener) {
        super(theme);
        this.model = model;
        this.listener = listener;
        this.view = new CanvasView(theme.scale);
    }

    public CanvasView view() {
        return view;
    }

    // for a palette drag: where the mouse is in the graph
    public float[] screenToWorld(float sx, float sy) {
        return new float[]{view.worldX(sx), view.worldY(sy)};
    }

    // real height with this card's real text, design px. what AutoArrange and fit want
    public int cardHeight(NodeCard card) {
        return layoutFor(card).height;
    }

    // everything the graph covers, loop lanes included, world units
    public CanvasView.Rect worldBounds() {
        sync();
        if (boxes.isEmpty()) {
            return new CanvasView.Rect(0, 0, 0, 0);
        }
        float x0 = Float.MAX_VALUE;
        float y0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE;
        float y1 = -Float.MAX_VALUE;
        for (NodeHits.Box b : boxes) {
            x0 = Math.min(x0, b.x());
            y0 = Math.min(y0, b.y());
            x1 = Math.max(x1, b.x() + b.w());
            y1 = Math.max(y1, b.y() + b.h());
        }
        for (WireRouter.Route r : routes) {
            x0 = Math.min(x0, r.minX());
            y0 = Math.min(y0, r.minY());
            x1 = Math.max(x1, r.maxX());
            y1 = Math.max(y1, r.maxY());
        }
        return new CanvasView.Rect(x0, y0, x1 - x0, y1 - y0);
    }

    // free and whole are device px relative to this widget, see CanvasView.fit
    public void fit(CanvasView.Rect free, CanvasView.Rect whole) {
        view.fit(worldBounds(), free, whole);
        listener.viewChanged();
    }

    public void zoomStep(int steps) {
        if (view.zoomAt(steps, x + w / 2f, y + h / 2f)) {
            listener.viewChanged();
        }
    }

    // a tab switch can lose the mouse up and the Shell doesn't tell content it went away, so the tab calls this on hide
    public void cancelPress() {
        press = Press.NONE;
        dragId = null;
        pressId = null;
        hotCard = -1;
        dirty = true;
    }

    // a press that went to something floating on top of us never reaches mouseDown, but the Shell still focuses us. the
    // floats call this, or Delete would eat a node because you clicked the inspector's blank bit
    public void pressedElsewhere() {
        lastPressHere = false;
        cancelPress();
    }

    @Override
    public void setVisible(boolean v) {
        super.setVisible(v);
        if (!v) {
            cancelPress();
        }
    }

    @Override
    protected void layout() {
        view.setOrigin(x, y);
    }

    @Override
    public boolean focusable() {
        return true;
    }

    // ----- keeping up with the model

    private NodeCardLayout layoutFor(NodeCard card) {
        NodeCardLayout l = layouts.get(card.id());
        if (l == null || !l.sameText(card)) {
            l = new NodeCardLayout(theme, card);
            layouts.put(card.id(), l);
        }
        return l;
    }

    private void sync() {
        List<NodeCard> newCards = model.cards();
        List<NodeWire> newWires = model.wires();
        if (!dirty && newCards == syncedCards && newWires == syncedWires) {
            return;
        }
        syncedCards = newCards;
        syncedWires = newWires;
        dirty = false;
        rebuild(newCards, newWires);
    }

    private void rebuild(List<NodeCard> newCards, List<NodeWire> newWires) {
        Map<String, NodeCardLayout> kept = new HashMap<>();
        cards.clear();
        boxes.clear();
        indexOf.clear();
        // draggee last: it's on top while it's in the air
        for (NodeCard c : newCards) {
            if (!c.id().equals(dragId)) {
                addCard(c, kept, c.x(), c.y());
            }
        }
        boolean dragLives = false;
        for (NodeCard c : newCards) {
            if (c.id().equals(dragId)) {
                addCard(c, kept, dragX, dragY);
                dragLives = true;
            }
        }
        if (dragId != null && !dragLives && press == Press.CARD) {
            // whatever we were carrying got deleted out from under us
            press = Press.NONE;
            dragId = null;
        }
        layouts = kept;
        // these are list positions, and the list just changed. the next mouse event finds them again
        hoverPort = null;
        hotCard = -1;
        routeWires(newWires);
        if (press == Press.WIRING) {
            findWirePort();
        }
    }

    // a param change can take a port away or shuffle them while a wire is in the air. the name is what got grabbed,
    // the position is just where it happens to be drawn. if it's gone the wire goes with it
    private void findWirePort() {
        Integer card = indexOf.get(pressId);
        List<NodeCard.Port> outs = card == null ? List.of() : cards.get(card).outs();
        for (int p = 0; p < outs.size(); p++) {
            if (outs.get(p).name().equals(wirePortName)) {
                wirePort = p;
                return;
            }
        }
        cancelPress();
    }

    private void addCard(NodeCard c, Map<String, NodeCardLayout> kept, float wx, float wy) {
        NodeCardLayout l = layoutFor(c);
        kept.put(c.id(), l);
        indexOf.put(c.id(), cards.size());
        cards.add(c);
        boxes.add(new NodeHits.Box(c.id(), wx, wy, NodeGeometry.width(c.small()), l.height, c.hasIn(), c.outs().size()));
    }

    private void routeWires(List<NodeWire> newWires) {
        wires.clear();
        List<WireRouter.In> in = new ArrayList<>(newWires.size());
        for (NodeWire wire : newWires) {
            Integer from = indexOf.get(wire.from());
            Integer to = indexOf.get(wire.to());
            if (from == null || to == null) {
                continue;
            }
            int port = cards.get(from).outIndex(wire.port());
            NodeHits.Box a = boxes.get(from);
            NodeHits.Box b = boxes.get(to);
            in.add(new WireRouter.In(a.x() + a.w(), a.y() + NodeGeometry.outPortY(port), b.x(), b.y() + NodeGeometry.inPortY(),
                    port, a.y(), b.y()));
            wires.add(wire);
        }
        routes.clear();
        routes.addAll(WireRouter.routeAll(in));
    }

    // ----- input

    private int selectedWireIndex() {
        String key = model.selectedWire();
        if (key == null) {
            return -1;
        }
        for (int i = 0; i < wires.size(); i++) {
            if (wires.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private boolean overChip(float mx, float my) {
        int sel = selectedWireIndex();
        if (sel < 0 || model.readOnly()) {
            return false;
        }
        WireRouter.Route r = routes.get(sel);
        float dx = mx - view.screenX(r.midX());
        float dy = my - view.screenY(r.midY());
        float radius = chipSize() / 2f;
        return dx * dx + dy * dy <= radius * radius;
    }

    private int chipSize() {
        return Math.round(CHIP * view.scale() * Math.max(view.zoom(), 0.75f));
    }

    private NodeHits.PortHit portAt(float mx, float my) {
        return NodeHits.port(boxes, view.worldX(mx), view.worldY(my), PORT_HIT / 2f / view.zoom());
    }

    // the card that's really under the mouse: a port poking out past the card's edge still counts as that card
    private int cardAt(float mx, float my) {
        NodeHits.PortHit p = portAt(mx, my);
        return p != null ? p.card() : NodeHits.card(boxes, view.worldX(mx), view.worldY(my));
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        // whatever was held, the up got lost somewhere. a new press starts clean, even one that isn't ours
        cancelPress();
        if (!contains(mx, my) || button != 0) {
            return false;
        }
        sync();
        lastPressHere = true;
        pressX = mouseX = mx;
        pressY = mouseY = my;
        moved = false;
        if (overChip(mx, my)) {
            press = Press.CHIP;
            return true;
        }
        NodeHits.PortHit port = portAt(mx, my);
        if (port != null && port.out() >= 0 && !model.readOnly()) {
            press = Press.WIRING;
            pressId = boxes.get(port.card()).id();
            wirePort = port.out();
            wirePortName = cards.get(port.card()).outs().get(port.out()).name();
            return true;
        }
        int card = port != null ? port.card() : NodeHits.card(boxes, view.worldX(mx), view.worldY(my));
        if (card >= 0) {
            pressCard(card);
            return true;
        }
        int wire = wireAt(mx, my);
        if (wire >= 0) {
            press = Press.WIRE;
            listener.wireClicked(wires.get(wire).key());
            return true;
        }
        press = Press.PAN;
        panStartX = view.panX();
        panStartY = view.panY();
        return true;
    }

    // in the order they're really drawn, lit ones over the rest, or a click on a blue wire picks the grey one under it
    private int wireAt(float mx, float my) {
        int selected = selectedWireIndex();
        List<WireRouter.Route> drawn = new ArrayList<>(routes.size());
        int[] original = new int[routes.size()];
        for (int group = 0; group < 2; group++) {
            for (int i = 0; i < routes.size(); i++) {
                if (litWire(wires.get(i), i, selected) == (group == 1)) {
                    original[drawn.size()] = i;
                    drawn.add(routes.get(i));
                }
            }
        }
        int hit = NodeHits.wire(drawn, view.worldX(mx), view.worldY(my), WIRE_HIT / view.zoom());
        return hit < 0 ? -1 : original[hit];
    }

    private void pressCard(int card) {
        press = Press.CARD;
        NodeHits.Box b = boxes.get(card);
        // not dragId yet: nothing moves until the threshold, and a card that isn't moving keeps its place in the order
        dragStartX = b.x();
        dragStartY = b.y();
        pressId = b.id();
        if (!b.id().equals(model.selectedCard())) {
            listener.select(b.id());
        }
    }

    @Override
    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        if (press == Press.NONE || button != 0) {
            return false;
        }
        mouseX = mx;
        mouseY = my;
        if (!moved && Math.hypot(mx - pressX, my - pressY) >= DRAG_THRESHOLD) {
            moved = true;
        }
        if (!moved) {
            return true;
        }
        switch (press) {
            case PAN -> {
                view.restore(panStartX, panStartY, view.zoomIndex());
                view.panBy(mx - pressX, my - pressY);
                listener.viewChanged();
            }
            case CARD -> {
                if (!model.readOnly()) {
                    dragId = pressId;
                    dragX = dragStartX + (mx - pressX) / view.k();
                    dragY = dragStartY + (my - pressY) / view.k();
                    dirty = true;
                }
            }
            case WIRING -> {
                sync();
                int over = cardAt(mx, my);
                // never itself, and a card with no input (start) is no target either
                hotCard = over >= 0 && !boxes.get(over).id().equals(pressId) && boxes.get(over).hasIn() ? over : -1;
            }
            default -> {
            }
        }
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        if (press == Press.NONE || button != 0) {
            return false;
        }
        // first: the model may have changed since the last event, and that can end this press
        sync();
        if (press == Press.NONE) {
            return true;
        }
        Press was = press;
        String from = pressId;
        String port = wirePortName;
        boolean didMove = moved;
        String carried = dragId;
        float cx = dragX;
        float cy = dragY;
        mouseX = mx;
        mouseY = my;
        int over = was == Press.WIRING ? cardAt(mx, my) : -1;
        String overId = over >= 0 ? boxes.get(over).id() : null;
        boolean overHasIn = over >= 0 && boxes.get(over).hasIn();
        boolean chip = was == Press.CHIP && overChip(mx, my);
        cancelPress();
        switch (was) {
            case WIRING -> release(from, port, overId, overHasIn, mx, my);
            case CARD -> {
                if (didMove && carried != null) {
                    listener.move(carried, NodeGeometry.snap(cx), NodeGeometry.snap(cy));
                }
            }
            case PAN -> {
                if (!didMove && (model.selectedCard() != null || model.selectedWire() != null)) {
                    listener.select(null);
                }
            }
            case CHIP -> {
                if (chip) {
                    listener.deleteSelected();
                }
            }
            default -> {
            }
        }
        return true;
    }

    private void release(String from, String port, String overId, boolean overHasIn, float mx, float my) {
        if (overId == null) {
            listener.dropAt(from, port, view.worldX(mx), view.worldY(my));
        } else if (!overId.equals(from) && overHasIn) {
            listener.connect(from, port, overId);
        }
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        Widget hit = super.mouseMove(mx, my);
        mouseX = mx;
        mouseY = my;
        if (hovered && press == Press.NONE) {
            sync();
            hoverChip = overChip(mx, my);
            hoverPort = hoverChip ? null : portAt(mx, my);
        } else if (!hovered) {
            hoverChip = false;
            hoverPort = null;
        }
        return hit;
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        if (!contains(mx, my)) {
            return false;
        }
        // zooming under a held card or wire would have it jump, the drag maths is all from the press
        if (press == Press.NONE && dy != 0f && view.zoomAt(dy > 0 ? 1 : -1, mx, my)) {
            listener.viewChanged();
        }
        return true;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (key == Keys.ESCAPE && press != Press.NONE) {
            // let go of what's held first, a card in the air is the thing esc is about
            cancelPress();
            return true;
        }
        boolean selected = model.selectedCard() != null || model.selectedWire() != null;
        if (key == Keys.ESCAPE) {
            // false with nothing selected, so the Shell gets to blur and the next esc closes the panel
            if (selected) {
                listener.select(null);
                return true;
            }
            return false;
        }
        if ((key == Keys.DELETE || key == Keys.BACKSPACE) && selected && lastPressHere && !model.readOnly()) {
            listener.deleteSelected();
            return true;
        }
        return false;
    }

    // ----- drawing

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        sync();
        float radius = theme.pxf(Theme.CARD_RADIUS);
        c.fillRoundRect(x, y, w, h, radius, theme.color(Theme.FIELD));
        c.pushClip(x, y, w, h);
        drawDots(c);
        drawWires(c);
        drawCards(c);
        drawGhost(c);
        drawChip(c);
        c.popClip();
        c.strokeRoundRect(x, y, w, h, radius, theme.color(Theme.HAIRLINE));
    }

    private void drawDots(Canvas c) {
        float step = DOT_STEP * view.k();
        while ((w / step + 1) * (h / step + 1) >= MAX_DOTS) {
            step *= 2f;
        }
        float offX = floorMod(view.screenX(0) - x, step);
        float offY = floorMod(view.screenY(0) - y, step);
        int size = Math.max(1, Math.round(1.5f * view.scale() * Math.min(view.zoom(), 1f)));
        int colour = theme.color(Theme.HAIRLINE);
        for (float py = y + offY; py < y + h; py += step) {
            int iy = Math.round(py);
            for (float px = x + offX; px < x + w; px += step) {
                c.fillRect(Math.round(px), iy, size, size, colour);
            }
        }
    }

    private static float floorMod(float v, float m) {
        float r = v % m;
        return r < 0 ? r + m : r;
    }

    // the ones the run went through and the selected one go over the rest, they're what you're looking at
    private void drawWires(Canvas c) {
        int thin = Math.max(1, (int) Math.floor(view.k() + 0.25f));
        int radius = Math.round(WireRouter.RADIUS * view.k());
        int selected = selectedWireIndex();
        sinkCanvas = c;
        int plain = Theme.fade(theme.color(Theme.TEXT_3), 0.75f);
        int hot = theme.color(Theme.ACCENT);
        for (int group = 0; group < 2; group++) {
            boolean top = group == 1;
            // every straight first, then every corner: one run of fills and one run of blits, not a switch per elbow
            for (int i = 0; i < wires.size(); i++) {
                NodeWire wire = wires.get(i);
                if (litWire(wire, i, selected) == top && project(routes.get(i))) {
                    sinkColor = wireColour(wire, top, plain, hot);
                    int t = top ? thin + 1 : thin;
                    tip(c, routes.get(i), t, sinkColor);
                    WireRouter.trace(sx, sy, routes.get(i).xs().length, radius, t, fillSink);
                }
            }
            for (int i = 0; i < wires.size(); i++) {
                NodeWire wire = wires.get(i);
                if (litWire(wire, i, selected) == top && project(routes.get(i))) {
                    sinkColor = wireColour(wire, top, plain, hot);
                    WireRouter.trace(sx, sy, routes.get(i).xs().length, radius, top ? thin + 1 : thin, cornerSink);
                }
            }
        }
        sinkCanvas = null;
    }

    private static boolean litWire(NodeWire wire, int index, int selected) {
        return wire.ran() || index == selected;
    }

    private static int wireColour(NodeWire wire, boolean lit, int plain, int hot) {
        return lit ? hot : wire.dim() ? Theme.fade(plain, 0.45f) : plain;
    }

    // false when it's off screen. fills sx, sy otherwise
    private boolean project(WireRouter.Route r) {
        if (view.screenX(r.maxX()) < x || view.screenX(r.minX()) > x + w
                || view.screenY(r.maxY()) < y || view.screenY(r.minY()) > y + h) {
            return false;
        }
        float[] xs = r.xs();
        float[] ys = r.ys();
        for (int i = 0; i < xs.length; i++) {
            sx[i] = Math.round(view.screenX(xs[i]));
            sy[i] = Math.round(view.screenY(ys[i]));
        }
        return true;
    }

    // no triangle primitive and 400 of these, so three stacked rects, tall to short, on the wire's own centre
    private void tip(Canvas c, WireRouter.Route r, int t, int colour) {
        int last = r.xs().length - 1;
        float end = r.xs()[last];
        float k = view.k();
        int left = Math.round(view.screenX(end - 5));
        // stops at the port's edge, under the dot the last step would just be a notch
        int column = Math.max(1, Math.round(2 * k));
        int mid = Math.round(view.screenY(r.ys()[last])) - t / 2;
        for (int i = 0; i < 3; i++) {
            int half = Math.round(4f * k * (3 - i) / 3f);
            c.fillRect(left + i * column, mid - half, column, 2 * half + t, colour);
        }
    }

    private void drawCards(Canvas c) {
        int count = cards.size();
        if (vis.length < count) {
            vis = new int[count];
            cardX0 = new int[count];
            cardY0 = new int[count];
            cardX1 = new int[count];
            cardY1 = new int[count];
        }
        int shown = cullCards(count);
        boolean shadow = view.zoom() >= 1f && shown < SHADOW_MAX_CARDS;
        // cards that don't touch can share runs: all their fills, then all their corners, then all their text. a card
        // that lands on a card already in the batch starts a new batch, so the one on top still ends up on top
        int start = 0;
        while (start < shown) {
            int end = start + 1;
            while (end < shown && !touchesBatch(start, end)) {
                end++;
            }
            drawBatch(c, start, end, shadow);
            start = end;
        }
    }

    private int cullCards(int count) {
        int margin = theme.px(16);
        int n = 0;
        for (int i = 0; i < count; i++) {
            NodeHits.Box b = boxes.get(i);
            int x0 = Math.round(view.screenX(b.x()));
            int y0 = Math.round(view.screenY(b.y()));
            int x1 = Math.round(view.screenX(b.x() + b.w()));
            int y1 = Math.round(view.screenY(b.y() + b.h()));
            if (x1 + margin < x || x0 - margin > x + w || y1 + margin < y || y0 - margin > y + h) {
                continue;
            }
            vis[n] = i;
            cardX0[n] = x0;
            cardY0[n] = y0;
            cardX1[n] = x1;
            cardY1[n] = y1;
            n++;
        }
        return n;
    }

    // with a little slop for the port dots and the selection ring. the n squared here is 200 squared, at worst
    private boolean touchesBatch(int start, int card) {
        int slop = Math.round(NodeGeometry.PORT * view.k());
        for (int i = start; i < card; i++) {
            if (cardX0[card] - slop < cardX1[i] && cardX0[i] - slop < cardX1[card]
                    && cardY0[card] - slop < cardY1[i] && cardY0[i] - slop < cardY1[card]) {
                return true;
            }
        }
        return false;
    }

    private void drawBatch(Canvas c, int start, int end, boolean shadow) {
        if (shadow) {
            for (int i = start; i < end; i++) {
                drawShadow(c, i);
            }
        }
        for (int i = start; i < end; i++) {
            drawCard(c, i, FILLS);
        }
        for (int i = start; i < end; i++) {
            drawCard(c, i, CORNERS);
        }
        // below 0.75 a card is header tint, body and ports. no text, no icons, nothing to do here
        if (view.zoom() >= 0.75f) {
            for (int i = start; i < end; i++) {
                drawCard(c, i, GLYPHS);
            }
        }
    }

    private void drawShadow(Canvas c, int i) {
        NodeCard card = cards.get(vis[i]);
        float k = view.k();
        int colour = Theme.fade(theme.color(Theme.SHADOW), card.warn() ? 0.24f : 0.4f);
        // the radius sets the blur too, and the mock's shadow is tighter than the card's corner
        c.shadow(cardX0[i], cardY0[i], cardX1[i] - cardX0[i], cardY1[i] - cardY0[i], 5 * k, colour);
    }

    private void drawCard(Canvas c, int i, int pass) {
        int index = vis[i];
        NodeCard card = cards.get(index);
        if (pass == GLYPHS) {
            drawGlyphs(c, i, card, layoutFor(card));
            return;
        }
        float k = view.k();
        int x0 = cardX0[i];
        int y0 = cardY0[i];
        int x1 = cardX1[i];
        int y1 = cardY1[i];
        float a = card.warn() ? 0.6f : 1f;
        int r = Math.round(NodeGeometry.RADIUS * k);
        int headBottom = y0 + Math.round(NodeGeometry.HEAD * k);
        boolean detail = view.zoom() >= 0.75f;

        fillTop(c, pass, x0, y0, x1, headBottom, r, Theme.fade(headerColour(card), a));
        fillBottom(c, pass, x0, headBottom, x1, y1, r, Theme.fade(theme.color(Theme.SURFACE), a));
        if (detail && pass == FILLS) {
            c.fillRect(x0, headBottom - 1, x1 - x0, 1, Theme.fade(theme.color(Theme.HAIRLINE), a));
        }
        int edge = edgeColour(card);
        if (edge != 0 || detail) {
            ring(c, pass, x0, y0, x1, y1, r, Theme.fade(edge != 0 ? edge : theme.color(Theme.HAIRLINE_STRONG), a));
        }
        if (card.id().equals(model.selectedCard())) {
            int accent = theme.color(Theme.ACCENT);
            ring(c, pass, x0 - 1, y0 - 1, x1 + 1, y1 + 1, r + 1, accent);
            ring(c, pass, x0 - 2, y0 - 2, x1 + 2, y1 + 2, r + 2, accent);
        }
        if (detail && card.badgeText() != null && !card.badgeText().isEmpty()) {
            NodeCardLayout.AtZoom at = layoutFor(card).at(theme, view.zoomIndex());
            int pillH = Math.round(18 * k);
            int pillX1 = x1 - Math.round(8 * k);
            int pillY0 = y0 + (headBottom - y0 - pillH) / 2;
            fillAll(c, pass, pillX1 - Math.round(at.badgeWidth), pillY0, pillX1, pillY0 + pillH, pillH / 2,
                    Theme.fade(theme.color(Theme.ACCENT_SOFT), a));
        }
        if (pass == CORNERS) {
            drawPorts(c, index, card, x0, x1, a);
        }
    }

    private int headerColour(NodeCard card) {
        if (card.state() == NodeCard.State.ACTIVE) {
            return theme.color(Theme.ACCENT_SOFT);
        }
        float mix = theme.dark() ? 0.16f : 0.09f;
        int surface = theme.color(Theme.SURFACE);
        return switch (card.group() == null ? "" : card.group()) {
            case "act" -> Theme.lerp(surface, theme.color(Theme.ACCENT), mix);
            case "flow" -> Theme.lerp(surface, theme.color(Theme.FLOW), mix);
            default -> theme.color(Theme.RAISED);
        };
    }

    private int edgeColour(NodeCard card) {
        return switch (card.state()) {
            case ACTIVE -> theme.color(Theme.ACCENT);
            case FAILED, BAD -> theme.color(Theme.DANGER);
            default -> 0;
        };
    }

    private int inkColour(NodeCard card) {
        if (card.icon() == Icon.PLAY) {
            return theme.color(Theme.PATH_START);
        }
        return switch (card.group() == null ? "" : card.group()) {
            case "act" -> theme.color(Theme.ACCENT_TEXT);
            case "flow" -> theme.color(Theme.FLOW_INK);
            default -> theme.color(Theme.TEXT_2);
        };
    }

    private int badgeColour(NodeCard card) {
        return switch (card.state()) {
            case ACTIVE -> theme.color(Theme.ACCENT);
            case DONE -> theme.color(Theme.ACCENT_TEXT);
            case FAILED, BAD -> theme.color(Theme.DANGER);
            default -> theme.color(Theme.TEXT_3);
        };
    }

    private void drawPorts(Canvas c, int index, NodeCard card, int x0, int x1, float a) {
        NodeHits.Box b = boxes.get(index);
        boolean wiringOverThis = hotCard == index;
        if (card.hasIn()) {
            boolean hot = wiringOverThis || hoverPort != null && hoverPort.card() == index && hoverPort.out() == -1;
            port(c, x0, Math.round(view.screenY(b.y() + NodeGeometry.inPortY())), card.inWired(), hot, a);
        }
        for (int p = 0; p < card.outs().size(); p++) {
            boolean hot = hoverPort != null && hoverPort.card() == index && hoverPort.out() == p
                    || pressId != null && press == Press.WIRING && pressId.equals(card.id()) && wirePort == p;
            port(c, x1, Math.round(view.screenY(b.y() + NodeGeometry.outPortY(p))), card.outs().get(p).wired(), hot, a);
        }
    }

    // always an even size, so it's four quarter discs and no fill: the whole dot lands in the blit run
    private void port(Canvas c, int cx, int cy, boolean wired, boolean hot, float a) {
        int r = Math.max(2, Math.round(NodeGeometry.PORT / 2f * view.k()));
        int fill = hot ? theme.color(Theme.ACCENT) : wired ? theme.color(Theme.HAIRLINE_STRONG) : theme.color(Theme.SURFACE);
        c.cornerFill(cx - r, cy - r, r, ShapeAtlas.TL, Theme.fade(fill, a));
        c.cornerFill(cx, cy - r, r, ShapeAtlas.TR, Theme.fade(fill, a));
        c.cornerFill(cx - r, cy, r, ShapeAtlas.BL, Theme.fade(fill, a));
        c.cornerFill(cx, cy, r, ShapeAtlas.BR, Theme.fade(fill, a));
        if (!hot && !wired) {
            int line = Theme.fade(theme.color(Theme.HAIRLINE_STRONG), a);
            c.cornerLine(cx - r, cy - r, r, ShapeAtlas.TL, line);
            c.cornerLine(cx, cy - r, r, ShapeAtlas.TR, line);
            c.cornerLine(cx - r, cy, r, ShapeAtlas.BL, line);
            c.cornerLine(cx, cy, r, ShapeAtlas.BR, line);
        }
    }

    private void drawGlyphs(Canvas c, int i, NodeCard card, NodeCardLayout layout) {
        float k = view.k();
        int x0 = cardX0[i];
        int y0 = cardY0[i];
        int x1 = cardX1[i];
        float a = card.warn() ? 0.6f : 1f;
        int headH = Math.round(NodeGeometry.HEAD * k);
        int iconSize = Math.round(16 * k);
        if (card.icon() != null) {
            c.icon(card.icon(), x0 + Math.round(NodeGeometry.TEXT_LEFT * k), y0 + (headH - iconSize) / 2,
                    iconSize, Theme.fade(inkColour(card), a));
        }
        drawItems(c, card, layout, x0, y0, k);
        if (!NodeCardLayout.showsText(view.scale(), view.zoom())) {
            drawBadgeIcon(c, card, x1, y0, headH, k, a);
            return;
        }
        NodeCardLayout.AtZoom at = layout.at(theme, view.zoomIndex());
        c.text(at.titleText, x0 + Math.round(34 * k), baseline(c, at.title, y0, headH), at.title, Theme.fade(theme.color(Theme.TEXT), a));
        drawBadge(c, card, at, x1, y0, headH, k, a);
        drawSummary(c, card, layout, at, x0, y0, k, a);
        drawPortNames(c, card, at, i, x1, k, a);
        if (at.why != null) {
            int top = y0 + Math.round((layout.bodyBottom - 2) * k);
            int colour = card.warn() ? theme.color(Theme.TEXT_3) : theme.color(Theme.DANGER);
            c.text(at.why, x0 + Math.round(NodeGeometry.TEXT_LEFT * k), baseline(c, at.small, top, Math.round(NodeGeometry.LINE * k)),
                    at.small, Theme.fade(colour, a));
        }
    }

    private void drawBadge(Canvas c, NodeCard card, NodeCardLayout.AtZoom at, int x1, int y0, int headH, float k, float a) {
        if (card.badgeText() != null && !card.badgeText().isEmpty()) {
            int right = x1 - Math.round(8 * k);
            int left = right - Math.round(at.badgeWidth);
            c.text(card.badgeText(), left + Math.round(6 * k), baseline(c, at.smallLabel, y0, headH), at.smallLabel,
                    Theme.fade(theme.color(Theme.ACCENT_TEXT), a));
            return;
        }
        drawBadgeIcon(c, card, x1, y0, headH, k, a);
    }

    private void drawBadgeIcon(Canvas c, NodeCard card, int x1, int y0, int headH, float k, float a) {
        if (card.badgeIcon() == null) {
            return;
        }
        // the busy dot is drawn a size down in the mock, it's a full circle and looks enormous otherwise
        int size = Math.round((card.badgeIcon() == Icon.DOT ? 12 : 16) * k);
        c.icon(card.badgeIcon(), x1 - Math.round(8 * k) - size, y0 + (headH - size) / 2, size, Theme.fade(badgeColour(card), a));
    }

    private void drawItems(Canvas c, NodeCard card, NodeCardLayout layout, int x0, int y0, float k) {
        int size = NodeCardLayout.itemSize(view.scale(), view.zoom());
        List<String> items = card.itemIds();
        if (size == 0 || items.isEmpty()) {
            return;
        }
        int left = x0 + Math.round(NodeGeometry.TEXT_LEFT * k);
        int slot = Math.round(NodeGeometry.ITEM * k);
        int top = y0 + Math.round((NodeGeometry.HEAD + (layout.itemRow ? NodeGeometry.ITEM_TOP : NodeGeometry.TEXT_PAD)) * k) + (slot - size) / 2;
        int step = Math.max(size + Math.round(3 * k), Math.round(19 * k));
        int room = Math.round(layout.summaryWidthAt(view.zoom()));
        for (int n = 0; n < items.size() && (n + 1) * step - (step - size) <= room; n++) {
            c.itemIcon(items.get(n), left + n * step, top, size);
        }
    }

    private void drawSummary(Canvas c, NodeCard card, NodeCardLayout layout, NodeCardLayout.AtZoom at, int x0, int y0, float k, float a) {
        int design = NodeGeometry.HEAD + (layout.itemRow ? NodeGeometry.ITEM_TOP + NodeGeometry.ITEM + NodeGeometry.ITEM_BELOW : NodeGeometry.TEXT_PAD);
        int left = NodeGeometry.TEXT_LEFT + (card.itemIds().size() == 1 ? NodeGeometry.ITEM + NodeGeometry.ITEM_GAP : 0);
        int colour = Theme.fade(theme.color(Theme.TEXT_2), a);
        for (int n = 0; n < at.lines.length; n++) {
            int top = y0 + Math.round((design + NodeGeometry.LINE * n) * k);
            c.text(at.lines[n], x0 + Math.round(left * k), baseline(c, at.small, top, Math.round(NodeGeometry.LINE * k)), at.small, colour);
        }
    }

    private void drawPortNames(Canvas c, NodeCard card, NodeCardLayout.AtZoom at, int i, int x1, float k, float a) {
        if (at.portNames == null) {
            return;
        }
        NodeHits.Box b = boxes.get(vis[i]);
        int colour = Theme.fade(theme.color(Theme.TEXT_3), a);
        for (int p = 0; p < at.portNames.length; p++) {
            int cy = Math.round(view.screenY(b.y() + NodeGeometry.outPortY(p)));
            c.text(at.portNames[p], x1 - Math.round(NodeGeometry.PORT_COLUMN_RIGHT * k) - at.portWidths[p],
                    baseline(c, at.small, cy, 0), at.small, colour);
        }
    }

    private void drawGhost(Canvas c) {
        if (press != Press.WIRING || !moved) {
            return;
        }
        Integer from = indexOf.get(pressId);
        if (from == null) {
            return;
        }
        NodeHits.Box b = boxes.get(from);
        float ax = Math.round(view.screenX(b.x() + b.w()));
        float ay = Math.round(view.screenY(b.y() + NodeGeometry.outPortY(wirePort)));
        int colour = theme.color(Theme.ACCENT);
        float mid = Math.round((ax + mouseX) / 2f);
        dashes(c, ax, ay, mid, ay, colour);
        dashes(c, mid, ay, mid, mouseY, colour);
        dashes(c, mid, mouseY, mouseX, mouseY, colour);
    }

    // along one straight leg. no corner blits, the ghost is one wire and nobody's looking at its elbows
    private void dashes(Canvas c, float x0, float y0, float x1, float y1, int colour) {
        int dash = Math.max(2, theme.px(5));
        int gap = Math.max(2, theme.px(4));
        float length = Math.abs(x1 - x0) + Math.abs(y1 - y0);
        float dx = Math.signum(x1 - x0);
        float dy = Math.signum(y1 - y0);
        for (float t = 0; t < length; t += dash + gap) {
            float d = Math.min(dash, length - t);
            float px = x0 + dx * t;
            float py = y0 + dy * t;
            if (dx != 0f) {
                c.fillRect(dx > 0 ? px : px - d, py, d, 1, colour);
            } else {
                c.fillRect(px, dy > 0 ? py : py - d, 1, d, colour);
            }
        }
    }

    private void drawChip(Canvas c) {
        int sel = selectedWireIndex();
        if (sel < 0 || model.readOnly()) {
            return;
        }
        WireRouter.Route r = routes.get(sel);
        int size = chipSize();
        float cx = Math.round(view.screenX(r.midX()) - size / 2f);
        float cy = Math.round(view.screenY(r.midY()) - size / 2f);
        c.shadow(cx, cy, size, size, size / 4f, Theme.fade(theme.color(Theme.SHADOW), 0.5f));
        c.fillRoundRect(cx, cy, size, size, size / 2f, theme.color(hoverChip ? Theme.DANGER_FILL : Theme.SURFACE));
        c.strokeRoundRect(cx, cy, size, size, size / 2f, theme.color(Theme.HAIRLINE_STRONG));
        int icon = Math.round(size / 2f);
        c.icon(Icon.TRASH, cx + (size - icon) / 2f, cy + (size - icon) / 2f, icon, theme.color(hoverChip ? Theme.DANGER : Theme.TEXT_2));
    }

    // ----- rounded things in two passes. same maths as TexturedCanvas, but fills and corners go out separately

    private static void fillTop(Canvas c, int pass, int x0, int y0, int x1, int y1, int r, int argb) {
        int w = x1 - x0;
        if (pass == FILLS) {
            if (w > 2 * r) {
                c.fillRect(x0 + r, y0, w - 2 * r, r, argb);
            }
            if (y1 - y0 > r) {
                c.fillRect(x0, y0 + r, w, y1 - y0 - r, argb);
            }
        } else if (pass == CORNERS) {
            c.cornerFill(x0, y0, r, ShapeAtlas.TL, argb);
            c.cornerFill(x1 - r, y0, r, ShapeAtlas.TR, argb);
        }
    }

    private static void fillBottom(Canvas c, int pass, int x0, int y0, int x1, int y1, int r, int argb) {
        int w = x1 - x0;
        if (pass == FILLS) {
            if (y1 - y0 > r) {
                c.fillRect(x0, y0, w, y1 - y0 - r, argb);
            }
            if (w > 2 * r) {
                c.fillRect(x0 + r, y1 - r, w - 2 * r, r, argb);
            }
        } else if (pass == CORNERS) {
            c.cornerFill(x0, y1 - r, r, ShapeAtlas.BL, argb);
            c.cornerFill(x1 - r, y1 - r, r, ShapeAtlas.BR, argb);
        }
    }

    private static void fillAll(Canvas c, int pass, int x0, int y0, int x1, int y1, int r, int argb) {
        fillTop(c, pass, x0, y0, x1, y0 + r, r, argb);
        fillBottom(c, pass, x0, y0 + r, x1, y1, r, argb);
    }

    private static void ring(Canvas c, int pass, int x0, int y0, int x1, int y1, int r, int argb) {
        int w = x1 - x0;
        int h = y1 - y0;
        if (pass == FILLS) {
            if (w > 2 * r) {
                c.fillRect(x0 + r, y0, w - 2 * r, 1, argb);
                c.fillRect(x0 + r, y1 - 1, w - 2 * r, 1, argb);
            }
            if (h > 2 * r) {
                c.fillRect(x0, y0 + r, 1, h - 2 * r, argb);
                c.fillRect(x1 - 1, y0 + r, 1, h - 2 * r, argb);
            }
        } else if (pass == CORNERS) {
            c.cornerLine(x0, y0, r, ShapeAtlas.TL, argb);
            c.cornerLine(x1 - r, y0, r, ShapeAtlas.TR, argb);
            c.cornerLine(x0, y1 - r, r, ShapeAtlas.BL, argb);
            c.cornerLine(x1 - r, y1 - r, r, ShapeAtlas.BR, argb);
        }
    }
}
