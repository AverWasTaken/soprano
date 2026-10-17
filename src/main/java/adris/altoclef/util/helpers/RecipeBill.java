package adris.altoclef.util.helpers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;

// the whole job's shopping list, added up before anything gets fetched. the recipe tasks each replan from the bag one level
// at a time, so a lantern mined iron for its nuggets, then the chain noticed it wanted an ingot too and off we went again.
// this pools every target's needs (the bag counted once, shared between everyone who wants the thing), so GatherBillTask can
// fetch it all in one go and then only craft. pure: recipes come in through Book, tests hand it fakes, the game CatalogueBook
public final class RecipeBill {

    public static final String TABLE = "crafting_table";
    public static final String FURNACE = "furnace";
    public static final String COAL = "coal";
    public static final String IRON = "iron_ingot";
    public static final String RAW_IRON = "raw_iron";
    public static final String SMOOTH_STONE = "smooth_stone";
    // CollectIronIngotTask's blast furnace: 5 ingots (smelted the slow way first), 3 smooth stone and a furnace
    static final int BLAST_IRON = 5;
    static final int BLAST_SMOOTH_STONE = 3;
    // a lump of coal is 8 smelts, same in a blast furnace (burns twice as fast, cooks twice as fast)
    static final int SMELTS_PER_COAL = 8;
    // the fixed point below settles in 2 or 3 rounds (stations, then fuel for the stations' smelts). this is just a seatbelt
    private static final int MAX_ROUNDS = 8;

    public static final String[] PICKAXES = {null, "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe"};
    // the blocks a shovel is for, by catalogue name
    static final Set<String> SHOVEL_STUFF = Set.of("dirt", "sand", "red_sand", "gravel", "clay_ball", "soul_sand", "soul_soil", "snowball", "snow_block");
    static final int AXE_LOGS = 8;
    static final int STONE_TOOL_COUNT = 32;
    static final int SHOVEL_BLOCKS = 32;

    private RecipeBill() {
    }

    // CRAFT fits the 2x2, CRAFT_TABLE wants a table, FILL is a bucket dunked in something
    public enum Kind {CRAFT, CRAFT_TABLE, SMELT, FILL}

    // how a thing is made: one run eats inputs (name -> per run) and makes out
    public record Way(Kind kind, Map<String, Integer> inputs, int out) {
    }

    public interface Book {
        // null: not made from anything we know about. it's a leaf
        Way wayOf(String name);

        // the pickaxe tier (MiningRequirement ordinal) a plain mine of it needs, -1 when it isn't fetched up front
        int gatherTier(String name);

        int held(String name);
    }

    // the bits that come from the world: stations already standing nearby, fuel in the bag that isn't coal (in smelts), and
    // CollectIronIngotTask.wouldBuildBlast for that many ingots
    public record World(boolean tableNear, boolean furnaceNear, int otherFuelSmelts, IntPredicate buildsBlast) {
    }

    // gross is everything anyone wants of it (an absolute bag count), held what the bag already has, runs how many times
    // its way runs. kind is null for leaves. tier only means something for leaves
    public record Node(String name, Kind kind, int gross, int held, int runs, int level, int tier) {
        public int net() {
            return Math.max(0, gross - held);
        }
    }

    public record Fetch(String name, int target, int tier) {
    }

    public static final class Bill {
        private final Map<String, Node> _nodes;
        // fetched up front: plain mines with something still missing, lowest pickaxe first
        public final List<Node> leaves;
        // the rest of the leaves, the tasks that make their parents get them like they always did
        public final List<Node> deferred;
        // everything made, lowest level first, smelts first inside a level
        public final List<List<Node>> levels;
        public final int smelts;
        public final boolean table;
        public final boolean furnace;
        public final boolean blast;
        public final int fuelCoal;

        Bill(Map<String, Node> nodes, List<Node> leaves, List<Node> deferred, List<List<Node>> levels, int smelts, boolean table,
             boolean furnace, boolean blast, int fuelCoal) {
            _nodes = nodes;
            this.leaves = leaves;
            this.deferred = deferred;
            this.levels = levels;
            this.smelts = smelts;
            this.table = table;
            this.furnace = furnace;
            this.blast = blast;
            this.fuelCoal = fuelCoal;
        }

        public Node node(String name) {
            return _nodes.get(name);
        }

        // gross wanted of name, 0 when the bill doesn't know it
        public int need(String name) {
            Node n = _nodes.get(name);
            return n == null ? 0 : n.gross();
        }

        public int net(String name) {
            Node n = _nodes.get(name);
            return n == null ? 0 : n.net();
        }
    }

    public static Bill of(Map<String, Integer> targets, Book book, World world) {
        Map<String, Integer> side = new LinkedHashMap<>();
        boolean blast = false;
        Round round = null;
        for (int i = 0; i < MAX_ROUNDS; i++) {
            round = new Round(book, targets, side, blast);
            boolean blastNow = blastFor(round, world);
            int smelts = round.smelts + (blastNow ? BLAST_IRON : 0);
            boolean tableNeeded = round.anyTableCraft || blastNow;
            Map<String, Integer> nextSide = new LinkedHashMap<>();
            if (tableNeeded && !world.tableNear()) {
                nextSide.merge(TABLE, 1, Integer::sum);
            }
            if (smelts > 0 && !world.furnaceNear()) {
                nextSide.merge(FURNACE, 1, Integer::sum);
            }
            if (blastNow) {
                // the blast furnace eats a furnace on top of the one doing the first 5 ingots
                nextSide.merge(FURNACE, 1, Integer::sum);
                nextSide.merge(RAW_IRON, BLAST_IRON, Integer::sum);
                nextSide.merge(SMOOTH_STONE, BLAST_SMOOTH_STONE, Integer::sum);
            }
            int coal = fuelCoal(smelts, world.otherFuelSmelts());
            if (coal > 0) {
                nextSide.merge(COAL, coal, Integer::sum);
            }
            if (nextSide.equals(side) && blastNow == blast) {
                return round.bill(book, blast, coal, tableNeeded && !world.tableNear(), smelts > 0 && !world.furnaceNear(), smelts);
            }
            side = nextSide;
            blast = blastNow;
        }
        // didn't settle (it always does, but): the last round is still a sane bill, just maybe a lump of coal off
        return round.bill(book, blast, side.getOrDefault(COAL, 0), side.containsKey(TABLE), side.containsKey(FURNACE), round.smelts);
    }

    // the iron run makes a blast furnace when there's real smelting to do and wouldBuildBlast says so for its target
    private static boolean blastFor(Round round, World world) {
        Node iron = round.nodes.get(IRON);
        return iron != null && iron.kind() == Kind.SMELT && iron.runs() > 0 && world.buildsBlast().test(iron.gross());
    }

    // coal for the smelts the other fuel in the bag can't cover
    static int fuelCoal(int smelts, int otherFuelSmelts) {
        int uncovered = smelts - Math.max(0, otherFuelSmelts);
        if (uncovered <= 0) {
            return 0;
        }
        return (uncovered + SMELTS_PER_COAL - 1) / SMELTS_PER_COAL;
    }

    // one pass: discover the graph from the targets, push demand down it in topological order, bag netted once per node
    private static final class Round {
        final Map<String, Way> ways = new LinkedHashMap<>();
        final Set<String> cyclic = new HashSet<>();
        final Map<String, Integer> demand = new LinkedHashMap<>();
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final Map<String, Integer> levels = new HashMap<>();
        final boolean blast;
        final boolean stationTable;
        final boolean stationFurnace;
        int smelts;
        boolean anyTableCraft;

        Round(Book book, Map<String, Integer> targets, Map<String, Integer> side, boolean blast) {
            this.blast = blast;
            stationTable = side.containsKey(TABLE);
            stationFurnace = side.containsKey(FURNACE);
            for (Map.Entry<String, Integer> t : targets.entrySet()) {
                if (t.getValue() > 0) demand.merge(t.getKey(), t.getValue(), Integer::sum);
            }
            for (Map.Entry<String, Integer> t : side.entrySet()) {
                if (t.getValue() > 0) demand.merge(t.getKey(), t.getValue(), Integer::sum);
            }
            for (String root : new ArrayList<>(demand.keySet())) {
                discover(book, root, new HashSet<>());
            }
            push(book);
        }

        Way way(String name) {
            return cyclic.contains(name) ? null : ways.get(name);
        }

        // a child that is already on the path closes a loop (nugget -> ingot -> nugget style). the node that would close it
        // stops being made from anything and turns into a leaf, which is the end of the loop
        private void discover(Book book, String name, Set<String> onPath) {
            if (ways.containsKey(name)) {
                return;
            }
            Way w = book.wayOf(name);
            ways.put(name, w);
            if (w == null) {
                return;
            }
            onPath.add(name);
            for (String child : w.inputs().keySet()) {
                if (onPath.contains(child)) {
                    cyclic.add(name);
                    continue;
                }
                discover(book, child, onPath);
            }
            onPath.remove(name);
        }

        // kahn's: a node is netted and expanded only once every parent has added its share
        private void push(Book book) {
            Map<String, Integer> parents = new HashMap<>();
            for (String name : ways.keySet()) {
                Way w = way(name);
                if (w == null) continue;
                for (String child : w.inputs().keySet()) {
                    parents.merge(child, 1, Integer::sum);
                }
            }
            Deque<String> ready = new ArrayDeque<>();
            for (String name : ways.keySet()) {
                if (parents.getOrDefault(name, 0) == 0) ready.add(name);
            }
            while (!ready.isEmpty()) {
                String name = ready.poll();
                Way w = way(name);
                int gross = demand.getOrDefault(name, 0);
                int held = Math.max(0, book.held(name));
                int net = Math.max(0, gross - held);
                int runs = 0;
                if (w != null && net > 0) {
                    runs = (net + Math.max(1, w.out()) - 1) / Math.max(1, w.out());
                    if (w.kind() == Kind.SMELT) smelts += runs;
                    if (w.kind() == Kind.CRAFT_TABLE) anyTableCraft = true;
                }
                if (w != null) {
                    for (Map.Entry<String, Integer> in : w.inputs().entrySet()) {
                        if (runs > 0) demand.merge(in.getKey(), runs * in.getValue(), Integer::sum);
                        if (parents.merge(in.getKey(), -1, Integer::sum) == 0) ready.add(in.getKey());
                    }
                }
                if (gross > 0) {
                    nodes.put(name, new Node(name, w == null ? null : w.kind(), gross, held, runs, level(name, new HashSet<>()),
                            w == null ? book.gatherTier(name) : -1));
                }
            }
        }

        // 0 for a leaf, else one past its highest input. with a blast furnace coming the ingots also wait for its parts, so
        // the smooth stone and the furnace are made before the iron run goes looking for them
        private int level(String name, Set<String> visiting) {
            Integer known = levels.get(name);
            if (known != null) return known;
            Way w = way(name);
            if (w == null || !visiting.add(name)) return 0;
            int max = 0;
            for (String child : w.inputs().keySet()) {
                max = Math.max(max, level(child, visiting));
            }
            if (blast && IRON.equals(name)) {
                max = Math.max(max, level(SMOOTH_STONE, visiting));
                max = Math.max(max, level(FURNACE, visiting));
            }
            // and a station we're making goes before what needs it, or the first table craft makes its own table out of order
            if (stationTable && w.kind() == Kind.CRAFT_TABLE) {
                max = Math.max(max, level(TABLE, visiting));
            }
            if (stationFurnace && w.kind() == Kind.SMELT) {
                max = Math.max(max, level(FURNACE, visiting));
            }
            visiting.remove(name);
            levels.put(name, max + 1);
            return max + 1;
        }

        Bill bill(Book book, boolean blast, int coal, boolean table, boolean furnace, int smeltCount) {
            List<Node> leaves = new ArrayList<>();
            List<Node> deferred = new ArrayList<>();
            Map<Integer, List<Node>> byLevel = new java.util.TreeMap<>();
            for (Node n : nodes.values()) {
                if (n.kind() == null) {
                    if (n.net() <= 0) continue;
                    (n.tier() >= 0 ? leaves : deferred).add(n);
                } else if (n.runs() > 0) {
                    byLevel.computeIfAbsent(n.level(), k -> new ArrayList<>()).add(n);
                }
            }
            leaves.sort(Comparator.comparingInt(Node::tier).thenComparing(Node::name));
            deferred.sort(Comparator.comparing(Node::name));
            List<List<Node>> levels = new ArrayList<>();
            for (List<Node> level : byLevel.values()) {
                level.sort(Comparator.comparingInt((Node n) -> n.kind() == Kind.SMELT ? 0 : 1).thenComparing(Node::name));
                levels.add(Collections.unmodifiableList(level));
            }
            return new Bill(Collections.unmodifiableMap(nodes), Collections.unmodifiableList(leaves), Collections.unmodifiableList(deferred),
                    Collections.unmodifiableList(levels), smeltCount, table, furnace, blast, coal);
        }
    }

    // what fits in the bag, lowest pickaxe first (the bill's leaf order). the rest is left for the recipe tasks to fetch the
    // way they always did. a leaf that only partly fits gets topped up to what fits and the list stops there
    public static List<Fetch> fitBag(List<Node> leaves, int freeSlots, ToIntFunction<String> stackSize) {
        List<Fetch> out = new ArrayList<>();
        int free = Math.max(0, freeSlots);
        for (Node leaf : leaves) {
            int stack = Math.max(1, stackSize.applyAsInt(leaf.name()));
            int heldStacks = ceilDiv(leaf.held(), stack);
            int extra = ceilDiv(leaf.gross(), stack) - heldStacks;
            if (extra <= free) {
                out.add(new Fetch(leaf.name(), leaf.gross(), leaf.tier()));
                free -= Math.max(0, extra);
                continue;
            }
            // the room left in the stacks we already have, plus whatever empty slots are left
            int room = heldStacks * stack - leaf.held() + free * stack;
            if (room > 0) {
                out.add(new Fetch(leaf.name(), leaf.held() + room, leaf.tier()));
            }
            break;
        }
        return out;
    }

    private static int ceilDiv(int a, int b) {
        return a <= 0 ? 0 : (a + b - 1) / b;
    }

    // the tools a job calls for, cheapest first: the pickaxe the hardest leaf needs, an axe for a pile of logs, a shovel for a
    // pile of dirt and sand. "tier" is 1 wood (gold counts, it's fast enough), 2 stone, 3 iron and up. -1 means none in the bag
    public interface ToolBag {
        int pickTier();

        int axeTier();

        int shovelTier();
    }

    public static List<String> tools(List<Node> leaves, ToolBag bag) {
        int pick = 0;
        int logs = 0;
        int shovelStuff = 0;
        for (Node leaf : leaves) {
            if (leaf.net() <= 0) continue;
            pick = Math.max(pick, leaf.tier());
            if (isLog(leaf.name())) logs += leaf.net();
            if (SHOVEL_STUFF.contains(leaf.name())) shovelStuff += leaf.net();
        }
        List<String> out = new ArrayList<>();
        if (pick > 0 && pick < PICKAXES.length && bag.pickTier() < pick) {
            out.add(PICKAXES[pick]);
        }
        // a stone pick on the way means cobble is coming anyway, so the axe might as well be stone too
        boolean stonePlanned = Math.max(pick, bag.pickTier()) >= 2;
        if (logs >= AXE_LOGS) {
            int want = toolTier(logs, stonePlanned);
            if (bag.axeTier() < want) out.add(want >= 2 ? "stone_axe" : "wooden_axe");
        }
        if (shovelStuff >= SHOVEL_BLOCKS) {
            int want = toolTier(shovelStuff, stonePlanned);
            if (bag.shovelTier() < want) out.add(want >= 2 ? "stone_shovel" : "wooden_shovel");
        }
        return out;
    }

    // a wooden one does for a handful, past a couple stacks a stone one pays for itself (and wood breaks after 59 uses)
    static int toolTier(int count, boolean stonePlanned) {
        return count >= STONE_TOOL_COUNT || stonePlanned ? 2 : 1;
    }

    static boolean isLog(String name) {
        return "log".equals(name) || name.endsWith("_log");
    }
}
