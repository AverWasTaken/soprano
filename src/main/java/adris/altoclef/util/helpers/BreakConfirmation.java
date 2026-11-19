package adris.altoclef.util.helpers;

import baritone.utils.accessor.IClientLevelPrediction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

// The client removes a broken block from the world as soon as its own break timer fills and only then tells the server
// (MultiPlayerGameMode.startPrediction). On a slow connection the server can still refuse the break and put the block
// back. Until the answer comes, the air is a prediction and not a fact: anything that finishes on it stops swinging at a
// block that may be about to return, which is exactly how a ghost block is left behind. The prediction handler keeps one
// entry per predicted position until the server acknowledges the sequence, so that entry is the answer to "has the
// server agreed yet?".
public final class BreakConfirmation {

    private BreakConfirmation() {
    }

    public static boolean isPending(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        return level instanceof IClientLevelPrediction prediction && prediction.isBreakPredictionPending(pos);
    }
}
