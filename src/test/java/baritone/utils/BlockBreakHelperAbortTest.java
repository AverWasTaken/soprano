package baritone.utils;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import net.minecraft.client.player.LocalPlayer;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

// the server keeps a dig (and its hit sound) going until it hears an abort, and the client's stopDestroyBlock only
// sends one if it thinks it's mid break. the fake controller plays that same gate, so "reset" without "abort" is the bug
public class BlockBreakHelperAbortTest {

    private static LocalPlayer player;

    @BeforeClass
    public static void boot() throws Exception {
        ItemFixture.boot();
        // never touched, stopBreakingBlock just wants to know there is one
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        player = (LocalPlayer) ((sun.misc.Unsafe) f.get(null)).allocateInstance(LocalPlayer.class);
    }

    // a MultiPlayerGameMode that only remembers isDestroying and what went over the wire
    private static final class FakeController {
        boolean destroying;
        final List<String> sent = new ArrayList<>();

        IPlayerController proxy() {
            return (IPlayerController) Proxy.newProxyInstance(IPlayerController.class.getClassLoader(), new Class<?>[]{IPlayerController.class}, (p, method, args) -> {
                switch (method.getName()) {
                    case "setHittingBlock":
                        destroying = (Boolean) args[0];
                        return null;
                    case "hasBrokenBlock":
                        return !destroying;
                    case "resetBlockRemoving":
                        if (destroying) {
                            sent.add("abort");
                            destroying = false;
                        }
                        return null;
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
        }
    }

    private static BlockBreakHelper helper(FakeController controller) {
        IPlayerController pc = controller.proxy();
        IPlayerContext ctx = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(), new Class<?>[]{IPlayerContext.class}, (p, method, args) -> {
            switch (method.getName()) {
                case "player":
                    return player;
                case "playerController":
                    return pc;
                case "objectMouseOver":
                    // looking at nothing, the else branch
                    return null;
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        });
        return new BlockBreakHelper(ctx);
    }

    // a real mid break needs a world and a Minecraft to click with, so set the one bit tick() would have left behind
    private static void midBreak(BlockBreakHelper helper) throws Exception {
        Field f = BlockBreakHelper.class.getDeclaredField("wasHitting");
        f.setAccessible(true);
        f.setBoolean(helper, true);
    }

    @Test
    public void stopBreakingBlockSendsTheAbort() throws Exception {
        FakeController controller = new FakeController();
        BlockBreakHelper helper = helper(controller);
        midBreak(helper);
        // what tick() leaves the client at between ticks, the reason the old stop never sent anything
        controller.destroying = false;
        helper.stopBreakingBlock();
        assertEquals(List.of("abort"), controller.sent);
        // and the client is told we're not mid break again, or vanilla's own stopDestroyBlock aborts a second time
        assertFalse(controller.destroying);
    }

    @Test
    public void lettingGoMidBreakSendsTheAbort() throws Exception {
        FakeController controller = new FakeController();
        BlockBreakHelper helper = helper(controller);
        midBreak(helper);
        helper.tick(false);
        assertEquals(List.of("abort"), controller.sent);
        // once is plenty
        helper.tick(false);
        helper.stopBreakingBlock();
        assertEquals(List.of("abort"), controller.sent);
    }

    @Test
    public void aFinishedBreakHasNothingToAbort() {
        // tick() clears wasHitting the tick the block goes, so the next release (or a stop) stays quiet
        FakeController controller = new FakeController();
        BlockBreakHelper helper = helper(controller);
        helper.tick(false);
        helper.stopBreakingBlock();
        assertEquals(List.of(), controller.sent);
    }
}
