package adris.altoclef.tasks.speedrun.gamer;

import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

// a snapshot you set by hand. counts need Bootstrap (Items), everything else does not
public class FakeFacts implements GamerFacts {
    public final Map<Item, Integer> items = new HashMap<>();
    public final java.util.Set<Item> worn = new java.util.HashSet<>();
    public Dimension dimension = Dimension.OVERWORLD;
    public int armorPoints;
    public int foodUnits;
    public int buildBlocks;
    public int x;
    public int y = 64;
    public int z;
    public long gameTime;
    public boolean credits;
    public int fingerprint;

    // one game second
    public FakeFacts seconds(double s) {
        gameTime += (long) Math.round(s * 20);
        return this;
    }

    public FakeFacts give(Item item, int n) {
        items.merge(item, n, Integer::sum);
        fingerprint++;
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
        return foodUnits;
    }

    @Override
    public int buildBlocks() {
        return buildBlocks;
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int y() {
        return y;
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

    @Override
    public int inventoryFingerprint() {
        return fingerprint;
    }
}
