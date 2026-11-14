package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Safe;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;

import java.util.Map;

// our rows in vanilla's controls screen, so Y and N can be rebound like any other key. they go into Options.keyMappings
// before load() (MixinSopranoOptions), so options.txt remembers a rebind. vanilla never gets to act on them: the
// keyboard mixin catches the press first (TripToastHud.onKey) and only while a question is up, the rest of the time
// the key is just a key. no fabric api, so no lang file either: the names come from NAMES through MixinSopranoLanguage
public final class SopranoKeys {

    static final String YES = "key.soprano.trip_yes";
    static final String NO = "key.soprano.trip_no";
    static final String RADIAL = "key.soprano.effortless_radial";
    static final String TOGGLE = "key.soprano.effortless_toggle";
    private static final String CATEGORY = "key.category.soprano.soprano";

    // forge and neoforge would read a lang file, fabric without api never does. one place for both
    private static final Map<String, String> NAMES = Map.of(
            CATEGORY, "Soprano",
            // the category header already says Soprano, so the rows just say what the key does
            YES, "Answer Yes",
            NO, "Answer No, or stop collecting",
            RADIAL, "Effortless menu (hold)",
            TOGGLE, "Effortless build mode on/off"
    );

    private static KeyMapping yes;
    private static KeyMapping no;
    private static KeyMapping radial;
    private static KeyMapping toggle;

    private SopranoKeys() {
    }

    // once per game. Category.register throws on a second call with the same id, and Options is only built once, but
    // a second Options would still get the same two mappings instead of an exception
    public static synchronized KeyMapping[] all() {
        if (yes == null) {
            try {
                KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("soprano", "soprano"));
                yes = new KeyMapping(YES, InputConstants.KEY_Y, cat);
                no = new KeyMapping(NO, InputConstants.KEY_N, cat);
                // left alt, like the build mods this is modelled on. vanilla leaves it free
                radial = new KeyMapping(RADIAL, InputConstants.KEY_LALT, cat);
                toggle = new KeyMapping(TOGGLE, InputConstants.UNKNOWN.getValue(), cat);
            } catch (Throwable t) {
                Safe.report("soprano keys", t);
                return new KeyMapping[0];
            }
        }
        return new KeyMapping[]{yes, no, radial, toggle};
    }

    public static String name(String key) {
        return NAMES.get(key);
    }

    static boolean isYes(KeyEvent event) {
        return yes != null && yes.matches(event);
    }

    static boolean isNo(KeyEvent event) {
        return no != null && no.matches(event);
    }

    public static boolean isRadial(KeyEvent event) {
        return radial != null && !radial.isUnbound() && radial.matches(event);
    }

    public static boolean isToggle(KeyEvent event) {
        return toggle != null && !toggle.isUnbound() && toggle.matches(event);
    }

    // what the chip on the button says. "Y" until someone rebinds it, then whatever the controls screen calls the new
    // key. unbound is null, the button loses its chip
    static String label(boolean yesKey) {
        KeyMapping k = yesKey ? yes : no;
        if (k == null) {
            return yesKey ? "Y" : "N";
        }
        if (k.isUnbound()) {
            return null;
        }
        return k.getTranslatedKeyMessage().getString();
    }
}
