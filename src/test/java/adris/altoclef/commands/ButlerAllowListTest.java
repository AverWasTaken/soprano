package adris.altoclef.commands;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

// what a whisper may run. the real command names, without registering them on a game
public class ButlerAllowListTest {

    // same fake as AltoClefCommandsTest: every interface getter hands out another fake, everything else is null/zero
    private static Object fake(Class<?> type) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Class<?> r = method.getReturnType();
            if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (method.getName().equals("equals")) {
                return proxy == args[0];
            }
            if (r.isInterface()) {
                return fake(r);
            }
            if (r == boolean.class) {
                return false;
            }
            if (r.isPrimitive() && r != void.class) {
                return r == double.class ? 0.0 : r == float.class ? 0f : r == long.class ? 0L : r == int.class ? 0 : r == short.class ? (short) 0 : r == byte.class ? (byte) 0 : '\0';
            }
            return null;
        });
    }

    private static Set<String> owned() {
        Set<String> names = new HashSet<>();
        for (ICommand c : AltoClefCommands.createAll((IBaritone) fake(IBaritone.class))) {
            names.addAll(c.getNames());
        }
        return names;
    }

    private static final List<String> DENIED_OURS = List.of("punk", "gamma", "setgamma", "set_gamma");

    @Test
    public void everyAliasOfPunkAndGammaIsDenied() {
        Set<String> owned = owned();
        for (String name : DENIED_OURS) {
            assertTrue(name + " should be one of our commands", owned.contains(name));
            assertFalse(name, AltoClefCommands.isButlerAllowed(name, owned));
            assertFalse(name, AltoClefCommands.isButlerAllowed(name.toUpperCase(Locale.ROOT), owned));
        }
    }

    @Test
    public void everyOtherCommandOfOursIsStillAllowed() {
        Set<String> owned = owned();
        for (String name : owned) {
            if (!DENIED_OURS.contains(name)) {
                assertTrue(name, AltoClefCommands.isButlerAllowed(name, owned));
            }
        }
        for (String name : new String[]{"get", "GET", "give", "goto", "Follow", "stop", "cancel"}) {
            assertTrue(name, AltoClefCommands.isButlerAllowed(name, owned));
        }
    }

    @Test
    public void sopranosOwnCommandsAreDenied() {
        Set<String> owned = owned();
        for (String name : new String[]{"set", "SET", "build", "mine", "sel", "click", "schematica", "settings", "", "#set", "/say", "set;get"}) {
            assertFalse(name, AltoClefCommands.isButlerAllowed(name, owned));
        }
    }
}
