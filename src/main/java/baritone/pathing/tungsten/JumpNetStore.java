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

package baritone.pathing.tungsten;

import baritone.Baritone;
import baritone.api.utils.Helper;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

// the one JumpNet: what it's learned so far is kept in baritone/jumpnet.bin (all worlds share it: a jump's a jump), and
// it starts from the one that comes with the mod (trained on Parkour Lab) the first time
public final class JumpNetStore {

    // below this chance, and with the net having learned from enough jumps, the sim isn't asked (Settings.useJumpNet)
    static final float SKIP_BELOW = 0.02f;
    static final long MIN_SEEN = 20_000;
    // still ask about this many of the ones it would skip, so it keeps learning about them (and how often it's wrong shows)
    static final double STILL_ASK = 0.05;
    // how fast it learns as the bot plays, and how much more a jump that works counts than one that doesn't (they're rare)
    static final float RATE = 0.01f, MADE_WEIGHT = 4f;
    // written out after this many more examples
    static final long SAVE_EVERY = 5_000;

    private static volatile JumpNet net;
    private static Path file;
    private static long savedAt;

    // what it did, for the scorecards
    public static final AtomicLong ASKED = new AtomicLong(), SKIPPED = new AtomicLong(), WRONG_SKIPS = new AtomicLong(), MADE = new AtomicLong();

    private JumpNetStore() {}

    public static synchronized JumpNet net() {
        if (net == null) {
            net = load();
        }
        return net;
    }

    // for tests and tools: use this one, kept nowhere
    public static synchronized void use(JumpNet n) {
        net = n;
        file = null;
    }

    private static JumpNet load() {
        try {
            Baritone b = (Baritone) baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
            file = b.getDirectory().resolve("jumpnet.bin");
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    JumpNet n = JumpNet.read(new java.io.BufferedInputStream(in));
                    if (n != null) {
                        savedAt = n.seen();
                        return n;
                    }
                }
            }
        } catch (Throwable t) {
            file = null; // (no game: a test)
        }
        try (InputStream in = JumpNetStore.class.getResourceAsStream("/soprano/jumpnet.bin")) {
            if (in != null) {
                JumpNet n = JumpNet.read(new java.io.BufferedInputStream(in));
                if (n != null) {
                    savedAt = n.seen();
                    return n;
                }
            }
        } catch (Throwable ignored) {}
        return new JumpNet(1);
    }

    public static boolean learning() {
        return Baritone.settings().learnJumpNet.value || Baritone.settings().useJumpNet.value;
    }

    // whether to leave the sim out of this one: the net is sure it won't come to anything
    static boolean skip(float p) {
        ASKED.incrementAndGet();
        if (!Baritone.settings().useJumpNet.value || p >= SKIP_BELOW || net().seen() < MIN_SEEN) {
            return false;
        }
        if (ThreadLocalRandom.current().nextDouble() < STILL_ASK) {
            return false;
        }
        SKIPPED.incrementAndGet();
        return true;
    }

    // the sim's answer for a jump the net guessed p for
    static void learn(int[] f, float p, boolean made) {
        if (made) {
            MADE.incrementAndGet();
            if (p < SKIP_BELOW) {
                WRONG_SKIPS.incrementAndGet(); // (one it would have skipped, among those still asked)
            }
        }
        JumpNet n = net();
        n.learn(f, made, RATE, made ? MADE_WEIGHT : 1f);
        if (file != null && n.seen() - savedAt > SAVE_EVERY) {
            save();
        }
    }

    public static synchronized void save() {
        if (net == null || file == null) {
            return;
        }
        try {
            Path tmp = file.resolveSibling("jumpnet.bin.tmp");
            try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(tmp))) {
                net.write(out);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            savedAt = net.seen();
        } catch (Exception e) {
            Helper.HELPER.logDebug("Couldn't save the jump net: " + e);
        }
    }
}
