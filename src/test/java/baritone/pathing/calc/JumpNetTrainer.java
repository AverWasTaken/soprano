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

package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.tungsten.JumpNet;
import baritone.pathing.tungsten.JumpNetStore;
import baritone.pathing.tungsten.TungstenJumps;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.Assume;
import org.junit.Test;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

// trains the jump net that comes with the mod: every jump the planner would ask the sim about, from round every jump and
// checkpoint of the courses given, with what the sim said; then a net fitted to most of them and scored on the rest.
// JUMPNET_JSON=course.json[,more.json] JUMPNET_OUT=src/main/resources/soprano/jumpnet.bin
public class JumpNetTrainer {

    // (the features as shorts: every index fits, and there are millions of them)
    record Sample(short[] s, boolean made) {
        static Sample of(int[] f, boolean made) {
            short[] s = new short[f.length];
            for (int i = 0; i < f.length; i++) {
                s[i] = (short) f[i];
            }
            return new Sample(s, made);
        }

        int[] f() {
            int[] f = new int[s.length];
            for (int i = 0; i < s.length; i++) {
                f[i] = s[i] & 0xFFFF;
            }
            return f;
        }
    }

    static final float MADE_WEIGHT = Float.parseFloat(System.getenv().getOrDefault("JUMPNET_MADE_WEIGHT", "4"));

    @Test
    public void train() throws Exception {
        String jsons = System.getenv("JUMPNET_JSON");
        Assume.assumeTrue(jsons != null);
        ItemFixture.boot();
        Settings settings = BaritoneAPI.getSettings();
        settings.allowTungstenJumps.value = true;
        settings.allowParkour.value = true;
        settings.allowParkourAscend.value = true;
        settings.allowBreak.value = false;
        settings.allowPlace.value = false;
        settings.considerPotionEffects.value = false;
        settings.maxFallHeightNoWater.value = 8;
        settings.learnJumpNet.value = false; // (the samples are gathered here, the net's trained below)
        settings.useJumpNet.value = false;
        List<Sample> samples = new ArrayList<>();
        for (String json : jsons.split(",")) {
            samples.addAll(gather(Path.of(json.trim())));
            System.out.println("JUMPNET " + json + ": " + samples.size() + " samples so far");
        }
        String eval = System.getenv("JUMPNET_EVAL");
        if (eval != null) {
            // just how a net already trained does on these (courses it's never seen)
            try (java.io.InputStream in = new java.io.BufferedInputStream(Files.newInputStream(Path.of(eval)))) {
                JumpNet trained = JumpNet.read(in);
                score(trained, samples, "on " + jsons);
            }
            return;
        }
        Collections.shuffle(samples, new Random(7));
        int test = samples.size() / 10;
        List<Sample> held = samples.subList(0, test), fit = samples.subList(test, samples.size());
        long made = samples.stream().filter(Sample::made).count();
        System.out.println("JUMPNET " + samples.size() + " samples, " + made + " made");
        JumpNet net = new JumpNet(11);
        int epochs = Integer.parseInt(System.getenv().getOrDefault("JUMPNET_EPOCHS", "8"));
        Random r = new Random(3);
        List<Sample> order = new ArrayList<>(fit);
        for (int e = 0; e < epochs; e++) {
            Collections.shuffle(order, r);
            float rate = Float.parseFloat(System.getenv().getOrDefault("JUMPNET_RATE", "0.02")) * (float) Math.pow(0.7, e);
            for (Sample s : order) {
                net.learn(s.f(), s.made(), rate, s.made() ? MADE_WEIGHT : 1f);
            }
            score(net, held, "epoch " + e, 0.02f);
        }
        String testJsons = System.getenv("JUMPNET_TEST");
        if (testJsons != null) {
            List<Sample> unseen = new ArrayList<>();
            for (String json : testJsons.split(",")) {
                unseen.addAll(gather(Path.of(json.trim())));
            }
            score(net, unseen, "never seen " + testJsons.replaceAll(".*/", ""));
        }
        String out = System.getenv("JUMPNET_OUT");
        if (out != null) {
            Files.createDirectories(Path.of(out).getParent());
            try (OutputStream os = new java.io.BufferedOutputStream(Files.newOutputStream(Path.of(out)))) {
                net.write(os);
            }
            System.out.println("JUMPNET wrote " + out);
        }
    }

    // how it does on the held back ones: of the jumps that don't work, how many it would have the sim leave out, and of the
    // ones that do, how many it would lose
    static void score(JumpNet net, List<Sample> held, String what) {
        for (float t : new float[]{0.002f, 0.005f, 0.01f, 0.02f, 0.05f}) {
            score(net, held, what + " below " + t, t);
        }
    }

    static void score(JumpNet net, List<Sample> held, String what, float below) {
        int neg = 0, pos = 0, skippedNeg = 0, lostPos = 0;
        for (Sample s : held) {
            float p = net.predict(s.f());
            boolean skip = p < below;
            if (s.made()) {
                pos++;
                lostPos += skip ? 1 : 0;
            } else {
                neg++;
                skippedNeg += skip ? 1 : 0;
            }
        }
        System.out.printf("JUMPNET %s: leaves out %.1f%% of the %d that don't work, loses %.2f%% of the %d that do%n",
                what, 100.0 * skippedNeg / Math.max(1, neg), neg, 100.0 * lostPos / Math.max(1, pos), pos);
    }

    // the samples for a course, kept next to it (JUMPNET_CACHE=dir): gathering them is most of the time
    static List<Sample> gather(Path json) throws Exception {
        String dir = System.getenv("JUMPNET_CACHE");
        Path cache = dir == null ? null : Path.of(dir, json.getFileName() + "." + System.getenv().getOrDefault("JUMPNET_TOPS", "3000") + ".samples");
        if (cache != null && Files.exists(cache)) {
            List<Sample> out = new ArrayList<>();
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(cache)))) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    boolean made = in.readBoolean();
                    int[] f = new int[in.readShort()];
                    for (int k = 0; k < f.length; k++) {
                        f[k] = in.readInt();
                    }
                    out.add(Sample.of(f, made));
                }
            }
            return out;
        }
        List<Sample> out = gatherFresh(json);
        if (cache != null) {
            Files.createDirectories(cache.getParent());
            try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(cache)))) {
                o.writeInt(out.size());
                for (Sample sm : out) {
                    o.writeBoolean(sm.made());
                    o.writeShort(sm.s().length);
                    for (int v : sm.f()) {
                        o.writeInt(v);
                    }
                }
            }
        }
        return out;
    }

    static List<Sample> gatherFresh(Path json) throws Exception {
        JsonObject course = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        for (JsonElement e : course.getAsJsonArray("blocks")) {
            JsonArray b = e.getAsJsonArray();
            w.set(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(),
                    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "minecraft:" + b.get(3).getAsString(), false).blockState());
        }
        // where to ask from: round every jump's ends and every checkpoint
        Set<BetterBlockPos> spots = new LinkedHashSet<>();
        for (JsonElement e : course.getAsJsonArray("sections")) {
            for (JsonElement je : e.getAsJsonObject().getAsJsonArray("jumps")) {
                for (String end : new String[]{"from", "to"}) {
                    JsonArray a = je.getAsJsonObject().getAsJsonArray(end);
                    spots.add(new BetterBlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
                }
            }
        }
        for (JsonElement e : course.getAsJsonArray("checkpoints")) {
            JsonObject cp = e.getAsJsonObject();
            spots.add(new BetterBlockPos(cp.get("x").getAsInt(), cp.get("y").getAsInt(), cp.get("z").getAsInt()));
        }
        // and on top of every block of the course that isn't under another (a sample of them, shuffled)
        List<BetterBlockPos> tops = new ArrayList<>();
        java.util.Set<Long> solid = new java.util.HashSet<>();
        for (JsonElement e : course.getAsJsonArray("blocks")) {
            JsonArray b = e.getAsJsonArray();
            solid.add(BetterBlockPos.longHash(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt()));
        }
        for (JsonElement e : course.getAsJsonArray("blocks")) {
            JsonArray b = e.getAsJsonArray();
            int x = b.get(0).getAsInt(), y = b.get(1).getAsInt(), z = b.get(2).getAsInt();
            if (!solid.contains(BetterBlockPos.longHash(x, y + 1, z)) && !solid.contains(BetterBlockPos.longHash(x, y + 2, z))) {
                tops.add(new BetterBlockPos(x, y + 1, z));
            }
        }
        Collections.shuffle(tops, new Random(5));
        spots.addAll(tops.subList(0, Math.min(tops.size(), Integer.parseInt(System.getenv().getOrDefault("JUMPNET_TOPS", "3000")))));
        int max = Integer.parseInt(System.getenv().getOrDefault("JUMPNET_SPOTS", "100000"));
        List<Sample> out = new ArrayList<>();
        VineLogPathTest.Ctx ctx = new VineLogPathTest.Ctx(w);
        int done = 0;
        for (BetterBlockPos src : spots) {
            if (done++ >= max) {
                break;
            }
            if (!TungstenJumps.takeoff(ctx, src.x, src.y, src.z)) {
                continue;
            }
            for (int dy = 2; dy >= -8; dy--) {
                for (int dx = -9; dx <= 9; dx++) {
                    for (int dz = -9; dz <= 9; dz++) {
                        if (!TungstenJumps.candidate(ctx, src.x, src.y, src.z, dx, dy, dz, true)) {
                            continue;
                        }
                        BetterBlockPos dest = new BetterBlockPos(src.x + dx, src.y + dy, src.z + dz);
                        boolean made = TungstenJumps.between(ctx, src, dest) != null;
                        out.add(Sample.of(JumpNet.features(ctx, src.x, src.y, src.z, dx, dy, dz), made));
                    }
                }
            }
        }
        return out;
    }
}
