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

package baritone.gradle.task;

import baritone.gradle.util.Determinizer;
import com.google.gson.*;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

// stuffs mod jars into META-INF/jars of the finished fabric jars so people install one file, not two
public class NestJarsTask extends BaritoneGradleTask {

    private static final String FABRIC_MOD_JSON = "fabric.mod.json";

    private final ConfigurableFileCollection nested = getProject().files();

    @InputFiles
    public ConfigurableFileCollection getNested() {
        return nested;
    }

    @TaskAction
    protected void exec() throws Exception {
        // plain method, not a gradle hook. skip it and every path below is null
        super.doFirst();

        List<File> files = new ArrayList<>(nested.getFiles());
        // one jar is all we ship right now. if this ever trips, someone added a second one on purpose and can fix this
        if (files.size() != 1) {
            throw new IllegalStateException("expected exactly one jar to nest, got " + files);
        }
        File jar = files.get(0);
        if (!jar.isFile()) {
            throw new IllegalStateException("jar to nest is missing: " + jar);
        }

        for (Path target : List.of(artifactApiPath, artifactStandalonePath, artifactUnoptimizedPath)) {
            if (!Files.exists(target)) {
                throw new IllegalStateException("can't nest into a jar that isn't there: " + target);
            }
            nest(target, jar);
        }
    }

    private void nest(Path target, File jar) throws IOException {
        String nestedPath = "META-INF/jars/" + jar.getName();
        Path temp = getTemporaryFile("nesting-" + target.getFileName());
        Files.deleteIfExists(temp);

        boolean sawModJson = false;
        try (JarFile in = new JarFile(target.toFile());
             JarOutputStream out = new JarOutputStream(new FileOutputStream(temp.toFile()))) {
            for (JarEntry entry : Collections.list(in.entries())) {
                String name = entry.getName();
                // skipping our own nested copy too, so running this twice doesn't end in a duplicate entry explosion
                if (name.equals(nestedPath)) {
                    continue;
                }
                out.putNextEntry(new JarEntry(name));
                try (InputStream is = in.getInputStream(entry)) {
                    if (name.equals(FABRIC_MOD_JSON)) {
                        sawModJson = true;
                        out.write(addJarsEntry(is, nestedPath));
                    } else {
                        is.transferTo(out);
                    }
                }
                out.closeEntry();
            }
            if (!sawModJson) {
                throw new IllegalStateException("no fabric.mod.json in " + target + ", loader would never see the nested jar");
            }
            out.putNextEntry(new JarEntry(nestedPath));
            // bytes untouched, it's their jar and their license file rides along inside it
            Files.copy(jar.toPath(), out);
            out.closeEntry();
        }

        // back through the determinizer so the output stays sorted with the funny mtimes, same as proguard leaves it
        Determinizer.determinize(temp.toString(), target.toString(), List.of(), false);
        Files.delete(temp);
    }

    private static byte[] addJarsEntry(InputStream is, String nestedPath) {
        JsonObject json = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray jars = json.has("jars") ? json.getAsJsonArray("jars") : new JsonArray();
        for (JsonElement e : jars) {
            JsonElement file = e.isJsonObject() ? e.getAsJsonObject().get("file") : null;
            if (file != null && file.isJsonPrimitive() && nestedPath.equals(file.getAsString())) {
                return toBytes(json);
            }
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("file", nestedPath);
        jars.add(entry);
        json.add("jars", jars);
        return toBytes(json);
    }

    private static byte[] toBytes(JsonObject json) {
        // html escaping would turn fabricloader's >= into unicode escape soup. it's json, not a web page
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        return (gson.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
