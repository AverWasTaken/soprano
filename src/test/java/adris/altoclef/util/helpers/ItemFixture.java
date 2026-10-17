package adris.altoclef.util.helpers;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

// the 26.3 tests boot through this. 1.21.4 hands out default components at bootstrap already, so the plain boot is
// the whole job here. item tags are still empty without a data pack, same as every other 1.21.4 test
public final class ItemFixture {
    private ItemFixture() {
    }

    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
}
