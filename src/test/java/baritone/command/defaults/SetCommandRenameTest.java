package baritone.command.defaults;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.argument.ICommandArgument;
import baritone.command.argument.ArgConsumer;
import baritone.command.argument.CommandArguments;
import net.minecraft.network.chat.Component;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// #set with the names fast mode had before the rename. every lookup in SetCommand goes through findByLowerName now,
// so set, toggle, reset and value completion all take the old names, and a string value still eats the rest of the line
public class SetCommandRenameTest {

    @BeforeClass
    public static void bootstrap() {
        // the old value line is a chat component, and the settings' default block lists need the registries
        ItemFixture.boot();
    }

    // SetCommand with the two things that need a running game swapped out: chat and settings.txt
    private static final class Quiet extends SetCommand {
        final List<String> said = new ArrayList<>();
        int saves;

        Quiet() {
            super(fakeBaritone());
        }

        @Override
        void saveSettings() {
            saves++;
        }

        @Override
        public void logDirect(boolean logAsToast, Component... components) {
            for (Component c : components) {
                said.add(c.getString());
            }
        }

        @Override
        public void logDirect(Component... components) {
            logDirect(false, components);
        }
    }

    // same trick as AltoClefCommandsTest: an IBaritone that hands out more of itself, enough to build a command
    private static IBaritone fakeBaritone() {
        return (IBaritone) fake(IBaritone.class);
    }

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

    private static IArgConsumer args(String rest) {
        List<ICommandArgument> list = CommandArguments.from(rest, true);
        return new ArgConsumer(null, list);
    }

    private final Settings settings = BaritoneAPI.getSettings();
    private final Quiet set = new Quiet();

    private void run(String rest) throws Exception {
        set.execute("set", args(rest));
    }

    @After
    public void putThemBack() {
        settings.fastMode.reset();
        settings.fastModeMinHealth.reset();
        settings.altoIdleCommand.reset();
    }

    @Test
    public void setTakesTheOldName() throws Exception {
        run("experimentalmovement true");
        assertTrue(settings.fastMode.value);
        assertEquals(1, set.saves);
        run("experimentalminhealth 8");
        assertEquals(8.0, settings.fastModeMinHealth.value, 0);
        // the chat line names the setting by what it is called now
        assertTrue(set.said.toString(), set.said.stream().anyMatch(s -> s.contains("fastModeMinHealth")));
    }

    @Test
    public void toggleTakesTheOldName() throws Exception {
        run("toggle experimentalmovement");
        assertTrue(settings.fastMode.value);
        run("toggle ExperimentalMovement");
        assertFalse(settings.fastMode.value);
        assertEquals(2, set.saves);
    }

    @Test
    public void resetTakesTheOldName() throws Exception {
        run("experimentalminhealth 4");
        assertEquals(4.0, settings.fastModeMinHealth.value, 0);
        run("reset experimentalminhealth");
        assertEquals(12.0, settings.fastModeMinHealth.value, 0);
        assertTrue(settings.fastModeMinHealth.value == settings.fastModeMinHealth.defaultValue);
    }

    @Test
    public void valueCompletionKnowsTheOldName() throws Exception {
        assertEquals(List.of("false", "true"), set.tabComplete("set", args("experimentalmovement ")).toList());
        assertEquals(List.of("12.0"), set.tabComplete("set", args("experimentalminhealth ")).toList());
        // and the new names, of course
        assertEquals(List.of("false", "true"), set.tabComplete("set", args("fastMode ")).toList());
    }

    @Test
    public void aStringValueTakesTheRestOfTheLine() throws Exception {
        run("altoIdleCommand follow Jacob");
        assertEquals("follow Jacob", settings.altoIdleCommand.value);
        run("altoidlecommand goto 100 64 -30");
        assertEquals("goto 100 64 -30", settings.altoIdleCommand.value);
    }
}
