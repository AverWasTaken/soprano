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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.pathing.path.PathExecutor;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Records a run so it can be looked at (and rebuilt) somewhere else: the blocks around the player, chunk by chunk as they
 * come into range, the player every tick (where, how fast, which keys, what Soprano was doing), and the chat. One zip in
 * baritone/recordings when it stops: blocks.json (a palette of full block states and every non-air block), trace.csv,
 * events.txt and meta.json. See #record.
 */
public final class RecordingBehavior extends Behavior {

    // chat comes in from a mixin on whatever thread the game hands it over on: queued, written on the tick
    private static final ConcurrentLinkedQueue<String> CHAT = new ConcurrentLinkedQueue<>();
    private static volatile RecordingBehavior active;

    // a lobby can be a lot of solid ground: past this many blocks it stops taking more (the trace goes on)
    private static final int MAX_BLOCKS = 3_000_000;

    private Path dir;
    private String name;
    private int radius, below, above;
    private BufferedWriter trace, events;
    private final Map<String, Integer> palette = new HashMap<>();
    private final List<String> paletteList = new ArrayList<>();
    private final Set<Long> captured = new HashSet<>();
    // x y z palette index world, five ints a block
    private int[] blocks = new int[5 * 4096];
    // a server like CubeCraft sends you to a new world (lobby, game, lobby) without a disconnect: each gets a number, and the
    // blocks and ticks say which one they're in
    private Level lastLevel;
    private int world = -1;
    private final List<String> worlds = new ArrayList<>();
    private IPath lastPath;
    private int blockCount;
    private boolean full;
    private long ticks, startMs;
    private int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;

    public RecordingBehavior(Baritone baritone) {
        super(baritone);
    }

    public boolean recording() {
        return dir != null;
    }

    public String name() {
        return name;
    }

    public long ticks() {
        return ticks;
    }

    public int blockCount() {
        return blockCount;
    }

    // called by the chat mixin for every line that goes into the chat box
    public static void chat(String line) {
        if (active != null) {
            CHAT.add(line);
        }
    }

    // radius in chunks round the player, below and above in blocks round its feet
    public Path start(String name, int radius, int below, int above) throws IOException {
        if (recording()) {
            stop();
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        this.name = name == null || name.isBlank() ? stamp : name.replaceAll("[^A-Za-z0-9._-]", "_");
        this.dir = baritone.getDirectory().resolve("recordings").resolve(this.name);
        Files.createDirectories(dir);
        this.radius = radius;
        this.below = below;
        this.above = above;
        this.trace = Files.newBufferedWriter(dir.resolve("trace.csv"), StandardCharsets.UTF_8);
        this.events = Files.newBufferedWriter(dir.resolve("events.txt"), StandardCharsets.UTF_8);
        trace.write("tick,ms,world,x,y,z,vx,vy,vz,yaw,pitch,onGround,hCollision,sprinting,sneaking,forward,back,left,right,jump,shift,sprintKey,health,movement,src,dest,pathIndex,pathSize,climbing\n");
        palette.clear();
        paletteList.clear();
        captured.clear();
        worlds.clear();
        world = -1;
        lastLevel = null;
        lastPath = null;
        blockCount = 0;
        full = false;
        ticks = 0;
        startMs = System.currentTimeMillis();
        lastChunkX = lastChunkZ = Integer.MIN_VALUE;
        CHAT.clear();
        active = this;
        writeMeta(false);
        event("start " + this.name);
        return dir;
    }

    public void mark(String note) throws IOException {
        if (!recording()) {
            return;
        }
        LocalPlayer p = ctx.player();
        event("mark " + (p == null ? "" : String.format("%.3f %.3f %.3f ", p.getX(), p.getY(), p.getZ())) + note);
    }

    // finishes the files and zips them up. the zip's path, or null if nothing was recording
    public Path stop() throws IOException {
        if (!recording()) {
            return null;
        }
        active = null;
        drainChat();
        event("stop after " + ticks + " ticks");
        trace.close();
        events.close();
        writeBlocks();
        writeMeta(true);
        Path zip = dir.resolveSibling(name + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String f : new String[]{"meta.json", "blocks.json", "trace.csv", "events.txt"}) {
                out.putNextEntry(new ZipEntry(name + "/" + f));
                Files.copy(dir.resolve(f), out);
                out.closeEntry();
            }
        }
        dir = null;
        return zip;
    }

    @Override
    public void onTick(TickEvent event) {
        if (!recording() || event.getType() != TickEvent.Type.IN) {
            return;
        }
        LocalPlayer p = ctx.player();
        Level level = ctx.world();
        if (p == null || level == null) {
            return;
        }
        try {
            drainChat();
            if (level != lastLevel) {
                lastLevel = level;
                world++;
                worlds.add(level.dimension().identifier().toString());
                captured.clear();
                event("world " + world + " " + worlds.get(world) + String.format(java.util.Locale.ROOT, " at %.3f %.3f %.3f", p.getX(), p.getY(), p.getZ()));
                lastChunkX = lastChunkZ = Integer.MIN_VALUE;
            }
            logPath();
            int cx = p.getBlockX() >> 4, cz = p.getBlockZ() >> 4;
            if (cx != lastChunkX || cz != lastChunkZ || ticks % 40 == 0) {
                lastChunkX = cx;
                lastChunkZ = cz;
                captureAround(level, cx, cz, p.getBlockY());
            }
            writeTick(p);
            ticks++;
        } catch (IOException ignored) {
            // keep going: a failed write is a gap in the file, not a reason to take the game down
        }
    }

    // every new path, movement by movement, so a run can be checked against what was planned
    private void logPath() throws IOException {
        PathExecutor current = baritone.getPathingBehavior().getCurrent();
        IPath path = current == null ? null : current.getPath();
        if (path == lastPath) {
            return;
        }
        lastPath = path;
        if (path == null) {
            event("path none");
            return;
        }
        StringBuilder sb = new StringBuilder("path ").append(path.movements().size()).append(':');
        for (IMovement m : path.movements()) {
            sb.append(' ').append(m.getClass().getSimpleName().replace("Movement", "")).append(' ')
                    .append(m.getSrc().x).append(',').append(m.getSrc().y).append(',').append(m.getSrc().z).append('>')
                    .append(m.getDest().x).append(',').append(m.getDest().y).append(',').append(m.getDest().z).append(';');
        }
        event(sb.toString());
    }

    private void writeTick(LocalPlayer p) throws IOException {
        Vec3 v = p.getDeltaMovement();
        Input in = p.input == null ? Input.EMPTY : p.input.keyPresses;
        String movement = "", src = "", dest = "";
        int index = -1, size = 0;
        PathExecutor current = baritone.getPathingBehavior().getCurrent();
        if (current != null) {
            IPath path = current.getPath();
            index = current.getPosition();
            size = path.movements().size();
            if (index >= 0 && index < size) {
                IMovement m = path.movements().get(index);
                movement = m.getClass().getSimpleName();
                src = m.getSrc().x + " " + m.getSrc().y + " " + m.getSrc().z;
                dest = m.getDest().x + " " + m.getDest().y + " " + m.getDest().z;
            }
        }
        trace.write(String.format(java.util.Locale.ROOT, "%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f,%s,%s,%s,%d,%d,%d%n",
                ticks, System.currentTimeMillis() - startMs, world, p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.getYRot(), p.getXRot(),
                p.onGround() ? 1 : 0, p.horizontalCollision ? 1 : 0, p.isSprinting() ? 1 : 0, p.isShiftKeyDown() ? 1 : 0,
                in.forward() ? 1 : 0, in.backward() ? 1 : 0, in.left() ? 1 : 0, in.right() ? 1 : 0, in.jump() ? 1 : 0,
                in.shift() ? 1 : 0, in.sprint() ? 1 : 0, p.getHealth(), movement, src, dest, index, size, p.onClimbable() ? 1 : 0));
    }

    private void captureAround(Level level, int cx, int cz, int feetY) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = cx + dx, z = cz + dz;
                if (level.hasChunk(x, z)) {
                    captureChunk(level.getChunk(x, z), feetY - below, feetY + above);
                }
            }
        }
    }

    private void captureChunk(LevelChunk chunk, int minY, int maxY) {
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length && !full; i++) {
            LevelChunkSection section = sections[i];
            int sy = chunk.getMinY() + 16 * i;
            if (sy + 15 < minY || sy > maxY) {
                continue;
            }
            // each 16 cube is taken whole, once, the first time it comes in range (as the player climbs, the ones above do)
            long key = (((long) chunk.getPos().x()) & 0x3fffff) << 42 | (((long) chunk.getPos().z()) & 0x3fffff) << 20 | ((sy >> 4) & 0xfffff);
            if (!captured.add(key) || section == null || section.hasOnlyAir()) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) {
                            continue;
                        }
                        add(baseX + x, sy + y, baseZ + z, state);
                    }
                }
            }
        }
    }

    private void add(int x, int y, int z, BlockState state) {
        if (blockCount >= MAX_BLOCKS) {
            if (!full) {
                full = true;
                try {
                    event("block limit reached (" + MAX_BLOCKS + "), no more blocks taken");
                } catch (IOException ignored) {}
            }
            return;
        }
        String s = BlockStateParser.serialize(state);
        Integer idx = palette.get(s);
        if (idx == null) {
            idx = paletteList.size();
            palette.put(s, idx);
            paletteList.add(s);
        }
        if (blockCount * 5 + 5 > blocks.length) {
            blocks = java.util.Arrays.copyOf(blocks, blocks.length * 2);
        }
        int o = blockCount * 5;
        blocks[o] = x;
        blocks[o + 1] = y;
        blocks[o + 2] = z;
        blocks[o + 3] = idx;
        blocks[o + 4] = world;
        blockCount++;
    }

    private void drainChat() throws IOException {
        String line;
        while ((line = CHAT.poll()) != null) {
            event("chat " + line);
        }
    }

    private void event(String text) throws IOException {
        if (events != null) {
            events.write(ticks + " " + (System.currentTimeMillis() - startMs) + " " + text.replace('\n', ' ') + "\n");
            events.flush();
        }
    }

    private void writeBlocks() throws IOException {
        try (OutputStream out = Files.newOutputStream(dir.resolve("blocks.json"));
             BufferedWriter w = new BufferedWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            w.write("{\"palette\":[");
            for (int i = 0; i < paletteList.size(); i++) {
                w.write((i > 0 ? "," : "") + "\"" + paletteList.get(i).replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            }
            w.write("],\"worlds\":[");
            for (int i = 0; i < worlds.size(); i++) {
                w.write((i > 0 ? "," : "") + "\"" + worlds.get(i) + "\"");
            }
            // x, y, z, palette index, world
            w.write("],\"blocks\":[");
            for (int i = 0; i < blockCount; i++) {
                int o = i * 5;
                w.write((i > 0 ? "," : "") + "[" + blocks[o] + "," + blocks[o + 1] + "," + blocks[o + 2] + "," + blocks[o + 3] + "," + blocks[o + 4] + "]");
            }
            w.write("]}");
        }
    }

    private void writeMeta(boolean done) throws IOException {
        ServerData server = ctx.minecraft().getCurrentServer();
        String dim = ctx.world() == null ? "" : ctx.world().dimension().toString();
        String player = ctx.player() == null ? "" : ctx.player().getGameProfile().name().replaceAll("[^A-Za-z0-9_]", "");
        String json = "{\"name\":\"" + name + "\",\"player\":\"" + player + "\",\"server\":\"" + (server == null ? "singleplayer" : server.ip.replace("\"", "")) + "\""
                + ",\"dimension\":\"" + dim.replace("\"", "") + "\",\"radiusChunks\":" + radius + ",\"below\":" + below + ",\"above\":" + above
                + ",\"worlds\":" + worlds.size() + ",\"ticks\":" + ticks + ",\"blocks\":" + blockCount + ",\"chunks\":" + captured.size() + ",\"finished\":" + done + "}\n";
        Files.writeString(dir.resolve("meta.json"), json);
    }
}
