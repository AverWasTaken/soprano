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

package baritone.utils.portal;

import com.viaversion.viafabricplus.ViaFabricPlus;
import com.viaversion.viafabricplus.api.protocoltranslator.ProtocolTranslation;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// the only class allowed to touch viafabricplus. only Portal calls in here, and only after Portal.available().
// proguard keeps it whole (scripts/proguard.pro) so none of this gets inlined back into Portal
public final class ViaPortal {

    private static List<ProtocolVersion> steps;

    private ViaPortal() {}

    private static List<ProtocolVersion> steps() {
        if (steps == null) {
            // plain releases only. the pre-netty ones are RELEASE_INITIAL and nobody's 2b2t is on 1.6.4, the
            // betas/alphas/classic/april fools stay in vfp's own screen where there's room for 80 of them
            List<ProtocolVersion> list = new ArrayList<>();
            for (ProtocolVersion version : ProtocolVersion.getProtocols()) {
                if (version.getVersionType() == VersionType.RELEASE) {
                    list.add(version);
                }
            }
            list.sort(Comparator.naturalOrder());
            list.add(ProtocolTranslation.AUTO_DETECT_VERSION);
            steps = List.copyOf(list);
        }
        return steps;
    }

    public static int stepCount() {
        return steps().size();
    }

    public static int currentStep() {
        ProtocolVersion target = ViaFabricPlus.api().targetVersion();
        int index = steps().indexOf(target);
        return index >= 0 ? index : nearest(target);
    }

    // a target we don't list parks the knob on the closest release. classic through the pre-netty releases sort
    // before every release so they land on the first notch, april fools and other specials mostly sort after and
    // land on the last one. close enough for a knob that's showing the real name anyway
    private static int nearest(ProtocolVersion target) {
        int releases = steps().size() - 1;
        int below = 0;
        for (int i = 0; i < releases; i++) {
            if (steps().get(i).compareTo(target) <= 0) {
                below = i;
            }
        }
        return below;
    }

    public static String label(int step) {
        List<ProtocolVersion> list = steps();
        int clamped = Math.max(0, Math.min(step, list.size() - 1));
        // AUTO_DETECT_VERSION's real name is way too long for a slider that gets like 150px
        return clamped == list.size() - 1 ? "Auto" : list.get(clamped).getName();
    }

    public static String offListName() {
        ProtocolVersion target = ViaFabricPlus.api().targetVersion();
        return steps().contains(target) ? null : target.getName();
    }

    // the version vfp is actually talking right now, same call its own gameplay mixins gate on. auto, bedrock and april
    // fools are SPECIAL and don't count as older, so off a server (auto) we get plain 26.3 rules
    static boolean serverOlderThan(ProtocolVersion version) {
        ProtocolVersion target = ViaFabricPlus.api().targetVersion();
        return target.getVersionType() != VersionType.SPECIAL && target.olderThan(version);
    }

    // 1.21.5 is where fall damage went from ceil to floor and the landing move started counting
    public static boolean roundsFallDamageUp() {
        return serverOlderThan(ProtocolVersion.v1_21_5);
    }

    // vfp puts the old fatigue table back for anything up to 26.2, so this is that gate said the other way round
    public static boolean oldMiningFatigue() {
        return serverOlderThan(ProtocolVersion.v26_3);
    }

    // exactly vfp's own gate on KeyboardInput's normalized() call, no SPECIAL carve out, so bot input and real
    // keyboard input never disagree about it
    public static boolean rawDiagonalInput() {
        return ViaFabricPlus.api().targetVersion().olderThanOrEqualTo(ProtocolVersion.v1_21_4);
    }

    // not serverOlderThan: its SPECIAL carve out would also skip classic c0_30cpe, which is SPECIAL but sorts with the
    // classics. auto and bedrock sort newest anyway, so the plain compare already leaves them out
    public static boolean legacyCombat() {
        return ViaFabricPlus.api().targetVersion().olderThan(ProtocolVersion.v1_9);
    }

    public static void select(int step) {
        List<ProtocolVersion> list = steps();
        ViaFabricPlus.api().setTargetVersion(list.get(Math.max(0, Math.min(step, list.size() - 1))));
    }
}
