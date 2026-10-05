package adris.altoclef.eventbus.events;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;

public class ClientRenderEvent {
    public PoseStack stack;
    public DeltaTracker tickDelta;

    public ClientRenderEvent(PoseStack stack, DeltaTracker tickDelta) {
        this.stack = stack;
        this.tickDelta = tickDelta;
    }
}
