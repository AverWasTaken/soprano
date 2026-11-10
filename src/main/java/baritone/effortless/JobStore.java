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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// the queue on disk, one file per world and dimension, so a relog finds its jobs where it left them. a job is saved
// as what made it (shape, clicks, mode, filter, palette), the cells come back from the shape. nothing here throws: a
// file that won't read is moved aside and the queue starts empty
public final class JobStore {

    public static final String FILE_NAME = "effortless-queue.json";
    private static final int SCHEMA = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JobStore() {
    }

    public static String toJson(JobQueue queue) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        JsonArray list = new JsonArray();
        for (JobQueue.Entry e : queue.entries()) {
            JsonObject o = new JsonObject();
            if (e.givenName() != null) {
                o.addProperty("name", e.givenName());
            }
            o.addProperty("added", e.added());
            // a job that was mid build when the game closed goes back to waiting, it isn't running any more
            o.addProperty("state", e.state() == JobQueue.State.RUNNING ? JobQueue.State.QUEUED.name() : e.state().name());
            if (e.why() != null) {
                o.addProperty("why", e.why());
            }
            o.addProperty("materials", e.materials().name());
            JsonArray jobs = new JsonArray();
            for (EffortlessJob j : e.jobs()) {
                jobs.add(job(j));
            }
            o.add("jobs", jobs);
            list.add(o);
        }
        root.add("entries", list);
        JsonArray areas = new JsonArray();
        for (StorageArea a : queue.storage()) {
            JsonObject o = new JsonObject();
            o.add("a", pos(a.a()));
            o.add("b", pos(a.b()));
            o.addProperty("role", a.role().name());
            areas.add(o);
        }
        root.add("storage", areas);
        return GSON.toJson(root);
    }

    static JsonObject job(EffortlessJob j) {
        JsonObject o = new JsonObject();
        o.addProperty("name", j.name);
        o.addProperty("shape", j.spec.kind().name());
        o.addProperty("axis", j.spec.axis().name());
        o.addProperty("fill", j.spec.fill().name());
        o.addProperty("thickness", j.spec.thickness());
        o.addProperty("half", j.spec.half());
        JsonArray pts = new JsonArray();
        for (BlockPos p : j.points) {
            JsonArray a = new JsonArray();
            a.add(p.getX());
            a.add(p.getY());
            a.add(p.getZ());
            pts.add(a);
        }
        o.add("points", pts);
        o.addProperty("mode", j.mode.name());
        o.addProperty("fillMode", j.fillMode.name());
        JsonObject f = new JsonObject();
        f.add("only", strings(j.filter.only()));
        f.add("except", strings(j.filter.except()));
        f.addProperty("skipContainers", j.filter.skipContainers());
        f.addProperty("keepFluids", j.filter.keepFluids());
        o.add("filter", f);
        if (j.palette != null) {
            JsonObject p = new JsonObject();
            p.addProperty("seed", j.palette.seed());
            JsonArray es = new JsonArray();
            for (Palette.Entry pe : j.palette.entries()) {
                JsonObject eo = new JsonObject();
                eo.addProperty("block", state(pe.state()));
                eo.addProperty("weight", pe.weight());
                eo.addProperty("rotate", pe.randomRotation());
                es.add(eo);
            }
            p.add("entries", es);
            o.add("palette", p);
        }
        return o;
    }

    private static JsonArray pos(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    private static BlockPos pos(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }

    // "minecraft:oak_stairs[facing=east,half=top]", the way the game writes a state, so the turn survives
    static String state(BlockState s) {
        StringBuilder b = new StringBuilder(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
        List<String> parts = new ArrayList<>();
        s.getValues().forEach(v -> parts.add(v.property().getName() + "=" + v.valueName()));
        if (!parts.isEmpty()) {
            b.append('[').append(String.join(",", parts)).append(']');
        }
        return b.toString();
    }

    static BlockState parseState(String text) {
        int br = text.indexOf('[');
        String id = br < 0 ? text : text.substring(0, br);
        Identifier rl = Identifier.tryParse(id);
        Optional<Block> block = rl == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(rl);
        if (block.isEmpty()) {
            return null;
        }
        BlockState s = block.get().defaultBlockState();
        if (br >= 0 && text.endsWith("]")) {
            for (String kv : text.substring(br + 1, text.length() - 1).split(",")) {
                int eq = kv.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                Property<?> p = block.get().getStateDefinition().getProperty(kv.substring(0, eq));
                if (p != null) {
                    s = with(s, p, kv.substring(eq + 1));
                }
            }
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String value) {
        return p.getValue(value).map(v -> s.setValue(p, v)).orElse(s);
    }

    // ----- reading

    public static JobQueue fromJson(String text) {
        JobQueue q = new JobQueue();
        JsonElement root = JsonParser.parseString(text);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("entries")) {
            return q;
        }
        if (root.getAsJsonObject().has("storage")) {
            for (JsonElement el : root.getAsJsonObject().getAsJsonArray("storage")) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    StorageArea.Role role = StorageArea.Role.byId(o.get("role").getAsString());
                    q.storage().add(new StorageArea(pos(o.getAsJsonArray("a")), pos(o.getAsJsonArray("b")),
                            role == null ? StorageArea.Role.BOTH : role));
                } catch (RuntimeException ignored) {
                }
            }
        }
        for (JsonElement el : root.getAsJsonObject().getAsJsonArray("entries")) {
            try {
                JsonObject o = el.getAsJsonObject();
                List<EffortlessJob> jobs = new ArrayList<>();
                for (JsonElement je : o.getAsJsonArray("jobs")) {
                    EffortlessJob j = readJob(je.getAsJsonObject());
                    if (j != null) {
                        jobs.add(j);
                    }
                }
                if (jobs.isEmpty()) {
                    continue;
                }
                JobQueue.Entry e = new JobQueue.Entry(jobs, o.has("name") ? o.get("name").getAsString() : null,
                        o.has("added") ? o.get("added").getAsLong() : 0);
                JobQueue.State st = enumOr(JobQueue.State.class, o.has("state") ? o.get("state").getAsString() : null, JobQueue.State.QUEUED);
                e.state(st == JobQueue.State.RUNNING ? JobQueue.State.QUEUED : st, o.has("why") ? o.get("why").getAsString() : null);
                e.materials(enumOr(baritone.api.utils.EffortlessMaterials.class, str(o, "materials"), null));
                q.add(e);
            } catch (RuntimeException ignored) {
                // one broken entry doesn't cost the rest
            }
        }
        return q;
    }

    static EffortlessJob readJob(JsonObject o) {
        ShapeKind kind = enumOr(ShapeKind.class, o.get("shape").getAsString(), null);
        if (kind == null) {
            return null;
        }
        ShapeSpec spec = new ShapeSpec(kind, enumOr(ShapeSpec.Axis.class, str(o, "axis"), null),
                enumOr(ShapeSpec.Fill.class, str(o, "fill"), null), o.has("thickness") ? o.get("thickness").getAsInt() : 1,
                o.has("half") && o.get("half").getAsBoolean());
        List<BlockPos> pts = new ArrayList<>();
        for (JsonElement pe : o.getAsJsonArray("points")) {
            JsonArray a = pe.getAsJsonArray();
            pts.add(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
        }
        EffortlessJob.Mode mode = enumOr(EffortlessJob.Mode.class, str(o, "mode"), EffortlessJob.Mode.PLACE);
        EffortlessJob.FillMode fill = enumOr(EffortlessJob.FillMode.class, str(o, "fillMode"), EffortlessJob.FillMode.FILL_EMPTY);
        BlockFilter filter = BlockFilter.ALL;
        if (o.has("filter")) {
            JsonObject f = o.getAsJsonObject("filter");
            filter = new BlockFilter(list(f, "only"), list(f, "except"),
                    f.has("skipContainers") && f.get("skipContainers").getAsBoolean(),
                    !f.has("keepFluids") || f.get("keepFluids").getAsBoolean());
        }
        Palette palette = null;
        if (o.has("palette")) {
            JsonObject p = o.getAsJsonObject("palette");
            List<Palette.Entry> es = new ArrayList<>();
            for (JsonElement ee : p.getAsJsonArray("entries")) {
                JsonObject eo = ee.getAsJsonObject();
                BlockState st = parseState(eo.get("block").getAsString());
                if (st != null) {
                    es.add(new Palette.Entry(st, eo.has("weight") ? eo.get("weight").getAsInt() : 1,
                            eo.has("rotate") && eo.get("rotate").getAsBoolean()));
                }
            }
            if (!es.isEmpty()) {
                palette = new Palette(es, p.has("seed") ? p.get("seed").getAsLong() : 0);
            }
        }
        if (mode == EffortlessJob.Mode.PLACE && palette == null) {
            return null;
        }
        return new EffortlessJob(System.nanoTime(), str(o, "name"), spec, pts, mode, fill, filter, palette, null);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static List<String> list(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        if (o.has(k)) {
            for (JsonElement e : o.getAsJsonArray(k)) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    private static <T extends Enum<T>> T enumOr(Class<T> type, String name, T missing) {
        if (name == null) {
            return missing;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return missing;
        }
    }

    // ----- files

    public static JobQueue load(Path file) {
        if (file == null || !Files.exists(file)) {
            return new JobQueue();
        }
        try {
            return fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            return new JobQueue();
        }
    }

    public static boolean save(Path file, JobQueue queue) {
        if (file == null) {
            return false;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(tmp, toJson(queue), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
