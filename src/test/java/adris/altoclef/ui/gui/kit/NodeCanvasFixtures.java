package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.List;

// hand made graphs for the canvas tests and previews, and a model + listener that write down what happened
public final class NodeCanvasFixtures {

    private NodeCanvasFixtures() {
    }

    public static final class Host implements NodeCanvas.Model, NodeCanvas.Listener {
        public List<NodeCard> cards;
        public List<NodeWire> wires;
        public String selectedCard;
        public String selectedWire;
        public boolean readOnly;
        public final List<String> events = new ArrayList<>();

        public Host(List<NodeCard> cards, List<NodeWire> wires) {
            this.cards = cards;
            this.wires = wires;
        }

        @Override
        public List<NodeCard> cards() {
            return cards;
        }

        @Override
        public List<NodeWire> wires() {
            return wires;
        }

        @Override
        public String selectedCard() {
            return selectedCard;
        }

        @Override
        public String selectedWire() {
            return selectedWire;
        }

        @Override
        public boolean readOnly() {
            return readOnly;
        }

        @Override
        public void select(String cardId) {
            selectedCard = cardId;
            selectedWire = null;
            events.add("select " + cardId);
        }

        @Override
        public void move(String cardId, int x, int y) {
            List<NodeCard> next = new ArrayList<>();
            for (NodeCard c : cards) {
                next.add(c.id().equals(cardId) ? at(c, x, y) : c);
            }
            cards = next;
            events.add("move " + cardId + " " + x + " " + y);
        }

        @Override
        public void connect(String from, String port, String to) {
            events.add("connect " + from + " " + port + " " + to);
        }

        @Override
        public void dropAt(String from, String port, float worldX, float worldY) {
            events.add("drop " + from + " " + port + " " + Math.round(worldX) + " " + Math.round(worldY));
        }

        @Override
        public void wireClicked(String wireKey) {
            selectedWire = wireKey;
            selectedCard = null;
            events.add("wire " + wireKey);
        }

        @Override
        public void deleteSelected() {
            events.add("delete");
        }

        @Override
        public void viewChanged() {
            events.add("view");
        }
    }

    public static NodeCard at(NodeCard c, int x, int y) {
        return new NodeCard(c.id(), x, y, c.small(), c.title(), c.icon(), c.group(), c.summary(), c.itemIds(), c.outs(),
                c.hasIn(), c.inWired(), c.state(), c.badgeText(), c.badgeIcon(), c.why(), c.warn());
    }

    public static NodeCard with(NodeCard c, NodeCard.State state, String badgeText, Icon badgeIcon) {
        return new NodeCard(c.id(), c.x(), c.y(), c.small(), c.title(), c.icon(), c.group(), c.summary(), c.itemIds(), c.outs(),
                c.hasIn(), c.inWired(), state, badgeText, badgeIcon, c.why(), c.warn());
    }

    public static NodeCard card(String id, int x, int y, boolean small, String type, String group, String title, String summary,
                                List<String> items, List<NodeCard.Port> outs, boolean hasIn, boolean inWired) {
        return new NodeCard(id, x, y, small, title, NodeCard.iconFor(type, group), group, summary, items, outs, hasIn, inWired,
                NodeCard.State.IDLE, null, null, null, false);
    }

    private static NodeCard.Port wired(String name) {
        return new NodeCard.Port(name, true);
    }

    private static NodeCard.Port open(String name) {
        return new NodeCard.Port(name, false);
    }

    // same shape and spots as the mock's wood loop
    public static List<NodeCard> woodLoopCards() {
        List<NodeCard> out = new ArrayList<>();
        out.add(card("s", 0, 40, true, "start", "end", "Start", "When you press Run", List.of(), List.of(wired("out")), false, false));
        out.add(card("r", 160, 40, false, "repeat", "flow", "Repeat", "Until has oak_log >= 64 or inventory full", List.of(),
                List.of(wired("body"), wired("done")), true, true));
        out.add(card("g", 384, 40, false, "goto", "act", "Go to block", "Nearest oak_log", List.of(),
                List.of(wired("ok"), open("failed")), true, true));
        out.add(card("m", 608, 40, false, "mine", "act", "Mine", "8 oak_log", List.of(), List.of(wired("ok"), open("failed")), true, true));
        out.add(card("z", 384, 176, true, "stop", "end", "Stop", "Ends the run", List.of(), List.of(), true, true));
        return out;
    }

    public static List<NodeWire> woodLoopWires() {
        return List.of(new NodeWire("s", "out", "r", "in"), new NodeWire("r", "body", "g", "in"), new NodeWire("g", "ok", "m", "in"),
                new NodeWire("m", "ok", "r", "again"), new NodeWire("r", "done", "z", "in"));
    }

    // third lap, on the mine node: what the running highlight has to show
    public static List<NodeCard> midRunCards() {
        List<NodeCard> out = new ArrayList<>();
        for (NodeCard c : woodLoopCards()) {
            switch (c.id()) {
                case "s", "g" -> out.add(with(c, NodeCard.State.DONE, null, Icon.CHECK));
                case "r" -> out.add(with(c, NodeCard.State.DONE, "3/20", null));
                case "m" -> out.add(with(c, NodeCard.State.ACTIVE, null, Icon.DOT));
                default -> out.add(c);
            }
        }
        return out;
    }

    public static List<NodeWire> midRunWires() {
        return List.of(new NodeWire("s", "out", "r", "in", true, false), new NodeWire("r", "body", "g", "in", true, false),
                new NodeWire("g", "ok", "m", "in", true, false), new NodeWire("m", "ok", "r", "again", true, false),
                new NodeWire("r", "done", "z", "in", false, true));
    }

    // every kind of card state at once, for eyeballing
    public static List<NodeCard> statesCards() {
        List<NodeCard> out = new ArrayList<>();
        out.add(card("s", 0, 0, true, "start", "end", "Start", "When you press Run", List.of(), List.of(wired("out")), false, false));
        out.add(card("one", 160, 0, false, "get", "act", "Get items", "16 bread", List.of("minecraft:bread"),
                List.of(wired("ok"), open("failed")), true, true));
        out.add(card("many", 400, 0, false, "get", "act", "Get items", "5 items, 260 total",
                List.of("minecraft:oak_log", "minecraft:bread", "minecraft:iron_ingot", "minecraft:stick", "minecraft:coal"),
                List.of(open("ok"), open("failed")), true, false));
        out.add(new NodeCard("bad", 160, 120, false, "Mine", NodeCard.iconFor("mine", "act"), "act", "No block yet", List.of(),
                List.of(open("ok"), open("failed")), true, true, NodeCard.State.BAD, null, Icon.WARNING, "Pick a block to mine", false));
        out.add(new NodeCard("warn", 400, 120, false, "Eat", NodeCard.iconFor("eat", "act"), "act", "Best food it has", List.of(),
                List.of(open("ok"), open("failed")), true, false, NodeCard.State.IDLE, null, null, "Nothing runs this", true));
        out.add(new NodeCard("fail", 160, 240, false, "Craft", NodeCard.iconFor("craft", "act"), "act",
                "A really long summary that has no hope at all of fitting in the two lines a card gets", List.of(),
                List.of(open("ok"), open("failed")), true, true, NodeCard.State.FAILED, null, Icon.WARNING, null, false));
        out.add(card("if", 400, 240, false, "if", "flow", "If", "Time of day is night", List.of(), List.of(wired("yes"), open("no")), true, true));
        out.add(card("new", 640, 240, false, "teleport", "flow", "Brand new type", "Falls back to its group's look", List.of(), List.of(open("ok")), true, false));
        return out;
    }

    public static List<NodeWire> statesWires() {
        return List.of(new NodeWire("s", "out", "one", "in"), new NodeWire("one", "ok", "bad", "in"), new NodeWire("if", "yes", "fail", "in"));
    }

    // 200 cards in rows, 400 wires, every other one an elbow or a loop back. the point is the arithmetic, not the art
    public static List<NodeCard> stressCards() {
        List<NodeCard> out = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int col = i % 20;
            int row = i / 20;
            String id = "n" + i;
            if (i % 5 == 0) {
                out.add(card(id, col * 224, row * 120, false, "repeat", "flow", "Repeat " + i, "Until has oak_log >= 64 or inventory full", List.of(),
                        List.of(wired("body"), wired("done")), true, true));
            } else if (i % 5 == 1) {
                out.add(card(id, col * 224, row * 120, false, "get", "act", "Get items " + i, "3 items, 40 total",
                        List.of("minecraft:oak_log", "minecraft:bread", "minecraft:stick"), List.of(wired("ok"), open("failed")), true, true));
            } else {
                out.add(card(id, col * 224, row * 120, false, "mine", "act", "Mine " + i, i + " oak_log", List.of(),
                        List.of(wired("ok"), open("failed")), true, true));
            }
        }
        return out;
    }

    public static List<NodeWire> stressWires() {
        List<NodeWire> out = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String port = i % 5 == 0 ? "body" : "ok";
            String other = i % 5 == 0 ? "done" : "failed";
            out.add(new NodeWire("n" + i, port, "n" + ((i + 1) % 200), "in"));
            out.add(new NodeWire("n" + i, other, "n" + ((i + 23) % 200), "in"));
        }
        return out;
    }
}
