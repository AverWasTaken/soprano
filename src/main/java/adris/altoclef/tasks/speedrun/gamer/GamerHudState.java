package adris.altoclef.tasks.speedrun.gamer;

import net.minecraft.world.item.Item;

import java.util.List;

// what the gamer card shows, frozen once per engine tick (GamerHud.build) and read by the renderer every frame. plain
// values only: the render thread never gets to ask the world anything through this. the clocks on the furnace and the
// detour are ticks, the renderer counts them down against the level's own time so they keep moving while the engine
// is paused (mob defense has the wheel) without anyone touching the engine
public record GamerHudState(GamerPhase phase, double secondsInPhase, double budgetMinutes, int attempt, String action,
                            List<KitRow> rows, List<FurnaceRow> furnaces, CoalRow coal, int foodUnits, int foodTarget) {

    // one kit line. want < 0 means the numbers make no sense for this need (armor on, meat to cook) and only the name shows.
    // working = the need the runner is on, the one that gets a bar. done = satisfied and lingering
    public record KitRow(String catalogueName, String name, Item icon, int have, int want, boolean working, boolean done) {
        public boolean counted() {
            return want > 0;
        }
    }

    // one furnace job. words is already "Smelting 37 iron"
    public record FurnaceRow(String words, Item icon, long startTick, long doneTick) {
    }

    // the coal side job while one is on
    public record CoalRow(int blocks, long startTick, double budgetSeconds) {
    }
}
