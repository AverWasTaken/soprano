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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// the words after "#ef place" / "#ef break" / "#ef shape". kept apart from the command so it can be tested without a
// player: the hand and offhand come in as arguments
public final class EffortlessOptions {

    public static final List<String> KEYWORDS = List.of(
            "all", "x", "y", "z", "direction=floor", "facing=", "vertical", "horizontal", "filled", "hollow", "skeleton",
            "middle", "corner", "equal", "variable", "long", "short",
            "block=", "mode=fill", "mode=overwrite", "mode=replace", "only=", "except=", "hand", "offhand",
            "nothand", "notoffhand", "skipcontainers", "fluids", "seed="
    );

    public static final class Bad extends Exception {
        public Bad(String message) {
            super(message);
        }
    }

    private ShapeSpec spec;
    private String blocks;
    private EffortlessJob.FillMode fill;
    private final List<String> only = new ArrayList<>();
    private final List<String> except = new ArrayList<>();
    private boolean hand;
    private boolean offhand;
    private boolean notHand;
    private boolean notOffhand;
    private boolean skipContainers;
    private boolean fluids;
    private Long seed;

    private EffortlessOptions(ShapeSpec spec) {
        this.spec = spec;
    }

    public ShapeSpec spec() {
        return spec;
    }

    // the same options for another shape. the draft keeps its words while the radial changes the shape under it
    public EffortlessOptions withSpec(ShapeSpec newSpec) {
        EffortlessOptions o = copy();
        o.spec = newSpec;
        return o;
    }

    private EffortlessOptions copy() {
        EffortlessOptions o = new EffortlessOptions(spec);
        o.blocks = blocks;
        o.fill = fill;
        o.only.addAll(only);
        o.except.addAll(except);
        o.hand = hand;
        o.offhand = offhand;
        o.notHand = notHand;
        o.notOffhand = notOffhand;
        o.skipContainers = skipContainers;
        o.fluids = fluids;
        o.seed = seed;
        return o;
    }

    public static EffortlessOptions parse(ShapeSpec base, String raw) throws IllegalArgumentException {
        EffortlessOptions o = new EffortlessOptions(base);
        for (String word : raw.trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            o.take(word);
        }
        return o;
    }

    private void take(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        int eq = w.indexOf('=');
        String key = eq < 0 ? w : w.substring(0, eq);
        String value = eq < 0 ? null : w.substring(eq + 1);
        if (value == null) {
            ShapeSpec.Fill f = ShapeSpec.Fill.byId(key);
            if (f != null) {
                spec = spec.withFill(f);
                return;
            }
        }
        switch (key) {
            // a line's direction: all, floor (x or z), or one axis
            case "x", "y", "z", "all" -> spec = spec.withDirection(ShapeSpec.Direction.byId(key));
            case "direction", "dir" -> {
                ShapeSpec.Direction d = ShapeSpec.Direction.byId(need(key, value));
                if (d == null) {
                    throw new IllegalArgumentException("direction is all, floor, x, y or z");
                }
                spec = spec.withDirection(d);
            }
            case "facing", "face" -> {
                ShapeSpec.Facing f = ShapeSpec.Facing.byId(need(key, value));
                if (f == null) {
                    throw new IllegalArgumentException("facing is both, vertical or horizontal");
                }
                spec = spec.withFacing(f);
            }
            case "vertical" -> spec = spec.withFacing(ShapeSpec.Facing.VERTICAL);
            case "horizontal" -> spec = spec.withFacing(ShapeSpec.Facing.HORIZONTAL);
            case "fill" -> {
                ShapeSpec.Fill f = ShapeSpec.Fill.byId(need(key, value));
                if (f == null) {
                    throw new IllegalArgumentException("fill is filled, hollow or skeleton");
                }
                spec = spec.withFill(f);
            }
            case "middle", "centered", "centre", "center" -> spec = spec.withCentered(true);
            case "corner" -> spec = spec.withCentered(false);
            case "equal" -> spec = spec.withEqual(true);
            case "variable" -> spec = spec.withEqual(false);
            case "long" -> spec = spec.withLongEdge(true);
            case "short" -> spec = spec.withLongEdge(false);
            case "block", "blocks", "with" -> blocks = need(key, value);
            case "mode" -> fill = switch (need(key, value)) {
                case "fill", "empty", "fillempty", "fill_empty" -> EffortlessJob.FillMode.FILL_EMPTY;
                case "overwrite", "all" -> EffortlessJob.FillMode.OVERWRITE;
                case "replace" -> EffortlessJob.FillMode.REPLACE;
                default -> throw new IllegalArgumentException("mode is fill, overwrite or replace");
            };
            case "overwrite" -> fill = EffortlessJob.FillMode.OVERWRITE;
            case "replace" -> fill = EffortlessJob.FillMode.REPLACE;
            case "only" -> only.addAll(list(need(key, value)));
            case "except" -> except.addAll(list(need(key, value)));
            case "hand" -> hand = true;
            case "offhand" -> offhand = true;
            case "nothand" -> notHand = true;
            case "notoffhand" -> notOffhand = true;
            case "skipcontainers" -> skipContainers = true;
            case "fluids" -> fluids = true;
            case "seed" -> seed = (long) number(key, value);
            default -> throw new IllegalArgumentException("don't know \"" + word + "\"");
        }
    }

    private static String need(String key, String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(key + "= needs a value");
        }
        return value;
    }

    private static int number(String key, String value) {
        try {
            return Integer.parseInt(need(key, value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + "= needs a number");
        }
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<>();
        for (String s : value.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    // ----- jobs

    public EffortlessJob placeJob(List<BlockPos> points, Optional<BlockState> hand, Optional<BlockState> off) throws Bad {
        EffortlessJob.FillMode mode = fill == null ? EffortlessJob.FillMode.FILL_EMPTY : fill;
        // which existing blocks a replace or overwrite may touch. a replace with nothing said takes every block that's
        // there; only=offhand is "the blocks like the one in my offhand", except=offhand "all but those"
        BlockFilter filter = filterFrom(resolve(only, hand, off), resolve(except, hand, off));
        Palette palette = blocks != null ? palette(blocks, hand, off) : hand.map(Palette::of)
                .orElseThrow(() -> new Bad("hold a block or say block=<block>"));
        if (seed != null) {
            palette = palette.withSeed(seed);
        }
        return EffortlessJob.place(spec, points, mode, filter, palette);
    }

    // "hand" and "offhand" in an only=/except= list are whatever you're holding there
    private static List<String> resolve(List<String> ids, Optional<BlockState> hand, Optional<BlockState> off) throws Bad {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            switch (id) {
                case "hand" -> out.add(BlockFilter.idOf(hand.orElseThrow(() -> new Bad("\"hand\" needs a block in your hand")).getBlock()));
                case "offhand" -> out.add(BlockFilter.idOf(off.orElseThrow(() -> new Bad("put the block to match in your offhand")).getBlock()));
                default -> out.add(id);
            }
        }
        return out;
    }

    public EffortlessJob breakJob(List<BlockPos> points, Optional<BlockState> hand, Optional<BlockState> off) throws Bad {
        List<String> in = resolve(only, hand, off);
        List<String> out = resolve(except, hand, off);
        if (this.hand) {
            in.add(BlockFilter.idOf(hand.orElseThrow(() -> new Bad("\"hand\" needs a block in your hand")).getBlock()));
        }
        if (offhand) {
            in.add(BlockFilter.idOf(off.orElseThrow(() -> new Bad("\"offhand\" needs a block in your offhand")).getBlock()));
        }
        if (notHand) {
            out.add(BlockFilter.idOf(hand.orElseThrow(() -> new Bad("\"nothand\" needs a block in your hand")).getBlock()));
        }
        if (notOffhand) {
            out.add(BlockFilter.idOf(off.orElseThrow(() -> new Bad("put the block to keep in your offhand")).getBlock()));
        }
        BlockFilter filter = filterFrom(in, out).withSkipContainers(skipContainers).withKeepFluids(!fluids);
        return EffortlessJob.breaking(spec, points, filter);
    }

    private static BlockFilter filterFrom(List<String> in, List<String> out) throws Bad {
        for (String id : concat(in, out)) {
            String problem = BlockFilter.problem(id);
            if (problem != null) {
                throw new Bad(problem);
            }
        }
        return new BlockFilter(in, out, false, true);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    // "stone_bricks*6,cracked_stone_bricks*3@r,hand". weight after *, @r turns it randomly
    public static Palette palette(String spec, Optional<BlockState> hand, Optional<BlockState> off) throws Bad {
        List<Palette.Entry> entries = new ArrayList<>();
        for (String raw : spec.split(",")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            boolean rotate = false;
            if (s.endsWith("@r")) {
                rotate = true;
                s = s.substring(0, s.length() - 2);
            }
            int weight = 1;
            int star = s.indexOf('*');
            if (star >= 0) {
                try {
                    weight = Integer.parseInt(s.substring(star + 1));
                } catch (NumberFormatException e) {
                    throw new Bad("\"" + raw + "\": the weight after * has to be a number");
                }
                s = s.substring(0, star);
            }
            BlockState state = switch (s) {
                case "hand" -> hand.orElseThrow(() -> new Bad("\"hand\" needs a block in your hand"));
                case "offhand" -> off.orElseThrow(() -> new Bad("\"offhand\" needs a block in your offhand"));
                default -> block(s);
            };
            entries.add(new Palette.Entry(state, weight, rotate));
        }
        if (entries.isEmpty()) {
            throw new Bad("block= is empty");
        }
        return new Palette(entries, 0);
    }

    private static BlockState block(String id) throws Bad {
        Identifier rl = Identifier.tryParse(id);
        Optional<Block> b = rl == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(rl);
        if (b.isEmpty() || b.get().defaultBlockState().isAir()) {
            throw new Bad("\"" + id + "\" isn't a block you can place");
        }
        return b.get().defaultBlockState();
    }

    @Override
    public String toString() {
        return spec + " blocks=" + blocks + " fill=" + fill + " only=" + only + " except=" + except
                + Arrays.toString(new boolean[]{hand, offhand, notHand, notOffhand, skipContainers, fluids});
    }
}
