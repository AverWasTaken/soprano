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

package baritone.event;

import baritone.Baritone;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.IEventBus;
import baritone.api.event.listener.IGameEventListener;
import baritone.api.utils.Helper;
import baritone.api.utils.Pair;
import baritone.cache.CachedChunk;
import baritone.cache.WorldProvider;
import baritone.utils.BlockStateInterface;
import baritone.utils.ChunkGens;
import baritone.utils.LagMeter;
import baritone.utils.PathRenderer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author Brady
 * @since 7/31/2018
 */
public final class GameEventHandler implements IEventBus, Helper {

    private final Baritone baritone;

    private final List<IGameEventListener> listeners = new CopyOnWriteArrayList<>();

    public GameEventHandler(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public final void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN) {
            try {
                baritone.bsi = new BlockStateInterface(baritone.getPlayerContext(), true);
            } catch (Exception ex) {
                ex.printStackTrace();
                baritone.bsi = null;
            }
        } else {
            baritone.bsi = null;
        }
        for (IGameEventListener l : listeners) {
            long t0 = LagMeter.start();
            l.onTick(event);
            LagMeter.stop(name(l) + ".tick", t0);
        }
    }

    @Override
    public void onPostTick(TickEvent event) {
        for (IGameEventListener l : listeners) {
            long t0 = LagMeter.start();
            l.onPostTick(event);
            LagMeter.stop(name(l) + ".post", t0);
        }
        LagMeter.endTick();
    }

    @Override
    public final void onPlayerUpdate(PlayerUpdateEvent event) {
        for (IGameEventListener l : listeners) {
            long t0 = LagMeter.start();
            l.onPlayerUpdate(event);
            LagMeter.stop(name(l) + ".update", t0);
        }
    }

    private static final ClassValue<String> NAMES = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> c) {
            String n = c.getSimpleName();
            return n.isEmpty() ? c.getName().replaceAll(".*\\.", "") : n;
        }
    };

    private static String name(IGameEventListener l) {
        return NAMES.get(l.getClass());
    }

    @Override
    public final void onSendChatMessage(ChatEvent event) {
        listeners.forEach(l -> l.onSendChatMessage(event));
    }

    @Override
    public void onPreTabComplete(TabCompleteEvent event) {
        listeners.forEach(l -> l.onPreTabComplete(event));
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        ChunkGens.loaded(event.getX(), event.getZ());
        EventState state = event.getState();
        ChunkEvent.Type type = event.getType();

        Level world = baritone.getPlayerContext().world();

        // Whenever the server sends us to another dimension, chunks are unloaded
        // technically after the new world has been loaded, so we perform a check
        // to make sure the chunk being unloaded is already loaded.
        boolean isPreUnload = state == EventState.PRE
                && type == ChunkEvent.Type.UNLOAD
                && world.getChunkSource().getChunk(event.getX(), event.getZ(), null, false) != null;

        if (event.isPostPopulate() || isPreUnload) {
            baritone.getWorldProvider().ifWorldLoaded(worldData -> {
                LevelChunk chunk = world.getChunk(event.getX(), event.getZ());
                worldData.getCachedWorld().queueForPacking(chunk);
            });
        }


        listeners.forEach(l -> l.onChunkEvent(event));
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        ChunkGens.blockChanged(event.getChunkPos().x(), event.getChunkPos().z());
        if (Baritone.settings().repackOnAnyBlockChange.value) {
            final boolean keepingTrackOf = event.getBlocks().stream()
                    .map(Pair::second).map(BlockState::getBlock)
                    .anyMatch(CachedChunk.BLOCKS_TO_KEEP_TRACK_OF::contains);

            if (keepingTrackOf) {
                baritone.getWorldProvider().ifWorldLoaded(worldData -> {
                    final Level world = baritone.getPlayerContext().world();
                    ChunkPos pos = event.getChunkPos();
                    worldData.getCachedWorld().queueForPacking(world.getChunk(pos.x(), pos.z()));
                });
            }
        }

        listeners.forEach(l -> l.onBlockChange(event));
    }

    @Override
    public final void onRenderPass(RenderEvent event) {
        // before the listeners, which is who draws with it
        PathRenderer.setProjection(event.getProjectionMatrix());
        for (IGameEventListener l : listeners) {
            long t0 = LagMeter.start();
            l.onRenderPass(event);
            LagMeter.stop(name(l) + ".render", t0);
        }
    }

    @Override
    public final void onWorldEvent(WorldEvent event) {
        WorldProvider cache = baritone.getWorldProvider();
        ChunkGens.newWorld();

        if (event.getState() == EventState.POST) {
            cache.closeWorld();
            if (event.getWorld() != null) {
                cache.initWorld(event.getWorld());
            }
        }

        listeners.forEach(l -> l.onWorldEvent(event));
    }

    @Override
    public final void onSendPacket(PacketEvent event) {
        listeners.forEach(l -> l.onSendPacket(event));
    }

    @Override
    public final void onReceivePacket(PacketEvent event) {
        listeners.forEach(l -> l.onReceivePacket(event));
    }

    @Override
    public void onPlayerRotationMove(RotationMoveEvent event) {
        for (IGameEventListener l : listeners) {
            long t0 = LagMeter.start();
            l.onPlayerRotationMove(event);
            LagMeter.stop(name(l) + ".rotmove", t0);
        }
    }

    @Override
    public void onPlayerSprintState(SprintStateEvent event) {
        for (IGameEventListener l : listeners) {
            long t0 = LagMeter.start();
            l.onPlayerSprintState(event);
            LagMeter.stop(name(l) + ".sprint", t0);
        }
    }

    @Override
    public void onBlockInteract(BlockInteractEvent event) {
        listeners.forEach(l -> l.onBlockInteract(event));
    }

    @Override
    public void onPlayerDeath() {
        listeners.forEach(IGameEventListener::onPlayerDeath);
    }

    @Override
    public void onPathEvent(PathEvent event) {
        listeners.forEach(l -> l.onPathEvent(event));
    }

    @Override
    public final void registerEventListener(IGameEventListener listener) {
        this.listeners.add(listener);
    }

    // altoclef ticks before everything else, see AltoClefBridge#attach
    public final void registerEventListenerFirst(IGameEventListener listener) {
        this.listeners.add(0, listener);
    }
}
