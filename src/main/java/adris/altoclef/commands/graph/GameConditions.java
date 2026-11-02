package adris.altoclef.commands.graph;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commands.GameChecks;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.altoclef.AltoClefBridge;
import baritone.api.utils.Dimension;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

// the real answers to a condition's questions. anything it can't answer right now throws Unreadable and the run fails,
// that beats guessing
public final class GameConditions implements Conditions.World {

    public static final GameConditions INSTANCE = new GameConditions();

    private final NearBlockCache nearBlocks = new NearBlockCache();

    private GameConditions() {
    }

    public static GraphProgram program(RoutineGraph graph) {
        return new GraphProgram(graph, INSTANCE, GameChecks.INSTANCE, Debug::logInternal);
    }

    private static AltoClef mod() {
        AltoClef mod = AltoClefBridge.instanceOrNull();
        if (mod == null) {
            throw new Conditions.Unreadable("the bot isn't up yet");
        }
        return mod;
    }

    private static LocalPlayer player() {
        LocalPlayer player = mod().getPlayer();
        if (player == null) {
            throw new Conditions.Unreadable("there's no player right now");
        }
        return player;
    }

    private static ClientLevel level() {
        ClientLevel level = mod().getWorld();
        if (level == null) {
            throw new Conditions.Unreadable("there's no world right now");
        }
        return level;
    }

    private static <T> T lookup(Registry<T> registry, String name, String what) {
        Identifier id = Identifier.tryParse(name);
        T found = id == null ? null : registry.getOptional(id).orElse(null);
        if (found == null) {
            throw new Conditions.Unreadable("\"" + name + "\" is not " + what);
        }
        return found;
    }

    @Override
    public void freshen() {
        // not setDirty: that only flags the outer tracker, the counts live in the inventory sub tracker with its own flag
        mod().getItemStorage().registerSlotAction();
    }

    @Override
    public int itemCount(String item) {
        Item it = lookup(BuiltInRegistries.ITEM, item, "an item");
        return mod().getItemStorage().getItemCount(it);
    }

    @Override
    public double health() {
        return player().getHealth();
    }

    @Override
    public int hunger() {
        return player().getFoodData().getFoodLevel();
    }

    @Override
    public boolean isDay() {
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            throw new Conditions.Unreadable("there's no day or night outside the overworld");
        }
        // the clock and not isBrightOutside, a thunderstorm at noon is not night
        return Conditions.isDayTime(level().getOverworldClockTime());
    }

    @Override
    public String dimension() {
        level();
        return WorldHelper.getCurrentDimension().name().toLowerCase(Locale.ROOT);
    }

    @Override
    public int freeSlots() {
        int free = 0;
        for (ItemStack stack : player().getInventory().getNonEquipmentItems()) {
            free += stack.isEmpty() ? 1 : 0;
        }
        return free;
    }

    @Override
    public double x() {
        return player().getX();
    }

    @Override
    public double y() {
        return player().getY();
    }

    @Override
    public double z() {
        return player().getZ();
    }

    @Override
    public boolean nearMob(String mob, int radius) {
        EntityType<?> type = lookup(BuiltInRegistries.ENTITY_TYPE, mob, "a mob");
        LocalPlayer me = player();
        double squared = (double) radius * radius;
        return !level().getEntitiesOfClass(Entity.class, me.getBoundingBox().inflate(radius),
                e -> e != me && e.getType() == type && e.isAlive() && e.distanceToSqr(me) <= squared).isEmpty();
    }

    @Override
    public boolean nearBlock(String block, int radius) {
        Block wanted = lookup(BuiltInRegistries.BLOCK, block, "a block");
        ClientLevel level = level();
        BlockPos at = player().blockPosition();
        return nearBlocks.ask(System.identityHashCode(level), block, radius, level.getGameTime(), () -> scan(level, at, wanted, radius));
    }

    // not BlockTracker: that one wants registering and hands the work to a background scan, a condition needs a yes or
    // no right now about a small box. the palette check throws out most sections without touching a single block
    private static boolean scan(ClientLevel level, BlockPos at, Block wanted, int radius) {
        Predicate<BlockState> match = s -> s.getBlock() == wanted;
        for (int cx = (at.getX() - radius) >> 4; cx <= (at.getX() + radius) >> 4; cx++) {
            for (int cz = (at.getZ() - radius) >> 4; cz <= (at.getZ() + radius) >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                // not loaded hands back an EmptyLevelChunk, same as SimpleChunkTracker
                if (!(chunk instanceof EmptyLevelChunk) && scanChunk(chunk, cx, cz, at, match, radius)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean scanChunk(LevelChunk chunk, int cx, int cz, BlockPos at, Predicate<BlockState> match, int radius) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            // sections[i] starts at minY + i * 16, same trap as SimpleChunkTracker's note
            int bottom = chunk.getMinY() + i * 16;
            if (section == null || bottom + 15 < at.getY() - radius || bottom > at.getY() + radius || !section.maybeHas(match)) {
                continue;
            }
            if (scanSection(section, cx << 4, bottom, cz << 4, at, match, radius)) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanSection(LevelChunkSection section, int minX, int minY, int minZ, BlockPos at, Predicate<BlockState> match, int radius) {
        int squared = radius * radius;
        for (int y = 0; y < 16; y++) {
            int dy = minY + y - at.getY();
            for (int z = 0; z < 16; z++) {
                int dz = minZ + z - at.getZ();
                for (int x = 0; x < 16; x++) {
                    int dx = minX + x - at.getX();
                    if (dx * dx + dy * dy + dz * dz <= squared && match.test(section.getBlockState(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
