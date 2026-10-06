package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// a player in a map. Items need Bootstrap, the tests that use this call it in @BeforeClass
class FakeFacts implements GamerFacts {
    Dimension dimension = Dimension.OVERWORLD;
    final Map<Item, Integer> items = new HashMap<>();
    final Set<Item> worn = new HashSet<>();
    int armorPoints;
    int food = 100;
    int blocks = 64;
    int x;
    int z;
    long gameTime = 100_000;
    boolean credits;

    FakeFacts with(Item item, int count) {
        items.put(item, count);
        return this;
    }

    // worn pieces count as owned, like the real facts do
    FakeFacts wear(Item item) {
        worn.add(item);
        items.merge(item, 1, Integer::sum);
        return this;
    }

    @Override
    public Dimension dimension() {
        return dimension;
    }

    @Override
    public int count(Item item) {
        return items.getOrDefault(item, 0);
    }

    @Override
    public boolean armorEquipped(Item item) {
        return worn.contains(item);
    }

    @Override
    public int armorPoints() {
        return armorPoints;
    }

    @Override
    public int foodUnits() {
        return food;
    }

    @Override
    public int buildBlocks() {
        return blocks;
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int y() {
        return 64;
    }

    @Override
    public int z() {
        return z;
    }

    @Override
    public long gameTime() {
        return gameTime;
    }

    @Override
    public boolean creditsShown() {
        return credits;
    }
}
