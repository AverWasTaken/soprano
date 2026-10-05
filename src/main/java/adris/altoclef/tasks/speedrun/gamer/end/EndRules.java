package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

// small pure questions about the End that both handlers and EndGear ask, all over RunState
public final class EndRules {
    private EndRules() {
    }

    // RunState.Death.dimension is whatever the engine wrote ("END", "the_end", "minecraft:the_end"), none of the others has "end" in it
    public static boolean isEnd(String dimension) {
        return dimension != null && dimension.toUpperCase().contains("END");
    }

    public static int endDeaths(RunState state) {
        int deaths = 0;
        for (RunState.Death death : state.deaths) {
            if (isEnd(death.dimension)) {
                deaths++;
            }
        }
        return deaths;
    }

    // null when we never died there
    public static RunState.Death lastEndDeath(RunState state) {
        RunState.Death last = null;
        for (RunState.Death death : state.deaths) {
            if (isEnd(death.dimension) && (last == null || death.gameTime >= last.gameTime)) {
                last = death;
            }
        }
        return last;
    }

    public static boolean attemptsLeft(RunState state, EndConfig cfg) {
        return endDeaths(state) < cfg.attempts;
    }

    // gear lying in the End only counts while it has not despawned. no death there = nothing is lying there,
    // whatever the cache says (it is only ever filled from the End, but a stale file should not talk us out of crafting)
    public static boolean dropsStillThere(RunState state, long gameTime, EndConfig cfg) {
        RunState.Death death = lastEndDeath(state);
        return death != null && gameTime - death.gameTime < cfg.dropLifetimeTicks;
    }

    public static String key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    public static int dropped(RunState state, long gameTime, EndConfig cfg, Item... items) {
        if (!dropsStillThere(state, gameTime, cfg)) {
            return 0;
        }
        int total = 0;
        for (Item item : items) {
            total += state.endDrops.getOrDefault(key(item), 0);
        }
        return total;
    }
}
