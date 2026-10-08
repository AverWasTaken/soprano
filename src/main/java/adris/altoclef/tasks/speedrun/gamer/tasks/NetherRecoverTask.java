package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.phases.NetherRegress;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Stage;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

// we died in the nether and woke up at home with an empty bag. the pile is still down there, so: dirt by hand (nothing else
// comes without a pickaxe), the home portal, across the nether, pick it up. the stage lives in RunState.netherTrip so a relog
// carries on and the rules for what comes next are NetherTripRules. when it ends, one way or another, state.netherTrip is
// null and GamerTask hands the wheel back to the phase machine (which rebuilds the kit if the trip did not bring it back)
public class NetherRecoverTask extends Task {
    // at the pile, the pile task gets this long on its own (the walk there is the trip's budget, not this one's)
    private static final double PILE_SECONDS = 90;

    private final RunState state;
    private final GamerFacts facts;
    private final Supplier<GamerConfig> cfg;
    private final Consumer<String> say;
    private final Runnable save;
    private final HomePortalWalk walk = new HomePortalWalk();
    private Task blocks;
    private Task toPile;
    private RecoverItemsTask pile;
    private Task wear;
    private Task home;
    // what the bag held at its lowest since the pile task started, and the most it ever climbed from there. the bag shrinks
    // while the pile task runs (dirt placed to reach it, food eaten), so the net change says nothing about what came back
    private int lowItems;
    private int peakItems;
    private int lowStacks;
    private int peakStacks;

    public NetherRecoverTask(RunState state, GamerFacts facts, Supplier<GamerConfig> cfg, Consumer<String> say, Runnable save) {
        this.state = state;
        this.facts = facts;
        this.cfg = cfg;
        this.say = say;
        this.save = save;
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        try {
            return tickTrip(mod);
        } catch (RuntimeException e) {
            // the phase machine is not ticking while we have the wheel, so nobody else would ever clear a trip that throws
            say.accept(giveUpLine("the trip hit an error (" + e.getClass().getSimpleName() + ")"));
            end(mod, null);
            return null;
        }
    }

    private Task tickTrip(AltoClef mod) {
        RunState.NetherTrip trip = state.netherTrip;
        if (trip == null) {
            return null;
        }
        Stage stage = Stage.parse(trip.stage);
        if (stage == null) {
            end(mod, "the saved trip made no sense");
            return null;
        }
        // PORTAL asks the walk first: whether the portal is gone is only known once it has had a look this tick
        Task child = childFor(mod, stage);
        long now = facts.gameTime();
        NetherTripRules.Step step = NetherTripRules.next(inputs(mod, trip, stage, now));
        if (step.giveUp() == null && !step.recovered() && step.stage() == stage) {
            return child;
        }
        if (step.recovered()) {
            say.accept("recovered " + gained());
            end(mod, null);
            return null;
        }
        if (step.giveUp() != null) {
            say.accept(giveUpLine(step.giveUp()));
        } else if (step.stage() != null) {
            say.accept(entered(step.stage()));
        }
        if (step.stage() == null) {
            end(mod, null);
            return null;
        }
        trip.stage = step.stage().name();
        trip.stageTick = now;
        save.run();
        return childFor(mod, step.stage());
    }

    private NetherTripRules.Inputs inputs(AltoClef mod, RunState.NetherTrip trip, Stage stage, long now) {
        GamerConfig.Death d = cfg.get().death;
        BlockPos at = new BlockPos(trip.pile.x, trip.pile.y, trip.pile.z);
        double dist = Math.sqrt(mod.getPlayer().blockPosition().distSqr(at));
        boolean homeGone = walk.gone() || state.overworldPortal == null;
        boolean finished = pile != null && pile.isFinished(mod);
        int gained = pile == null ? 0 : trackGain(mod);
        NetherTripRules.Limits limits = NetherTripRules.Limits.of(d.netherTripSeconds, d.netherBlocks, d.netherBlocksSeconds);
        int toWear = stage == Stage.RECOVER || stage == Stage.WEAR ? KitPlanner.toEquip(facts, cfg.get().overworld).size() : 0;
        return new NetherTripRules.Inputs(stage, facts.dimension(), now - trip.startTick, now - trip.stageTick,
                facts.buildBlocks(), homeGone, dist, finished, gained, NetherRegress.kitShort(facts, cfg.get()), toWear, limits);
    }

    private Task childFor(AltoClef mod, Stage stage) {
        RunState.NetherTrip trip = state.netherTrip;
        BlockPos at = new BlockPos(trip.pile.x, trip.pile.y, trip.pile.z);
        switch (stage) {
            case BLOCKS -> {
                if (blocks == null) {
                    // dirt is the one throwaway block that comes out of the ground without a tool. the count is the gap, the
                    // blocks we already hold (a stray cobble) are part of the total
                    int gap = Math.max(1, cfg.get().death.netherBlocks - facts.buildBlocks());
                    blocks = TaskCatalogue.getItemTask(Items.DIRT, gap + facts.count(Items.DIRT));
                }
                setDebugState("Getting blocks for the walk.", "Getting building blocks");
                return blocks;
            }
            case PORTAL -> {
                setDebugState("Back to the portal.", "Going back for our stuff");
                return walk.toTheNether(mod, state, save);
            }
            case WALK -> {
                walk.stopTracking(mod);
                if (toPile == null) {
                    toPile = new GetWithinRangeOfBlockTask(at, NetherTripRules.WALK_RANGE);
                }
                setDebugState("Walking to where we died.", "Going back for our stuff");
                return toPile;
            }
            case RECOVER -> {
                if (pile == null) {
                    pile = new RecoverItemsTask(at, PILE_SECONDS);
                    lowItems = itemTotal(mod);
                    lowStacks = stackTotal(mod);
                    peakItems = 0;
                    peakStacks = 0;
                }
                setDebugState("Picking up our stuff.", "Getting our stuff back");
                return pile;
            }
            case WEAR -> {
                if (wear == null) {
                    List<Item> pieces = KitPlanner.toEquip(facts, cfg.get().overworld);
                    wear = new EquipArmorTask(pieces.toArray(new Item[0]));
                }
                setDebugState("Putting our armor back on.", "Putting on armor");
                return wear;
            }
            default -> {
                if (home == null) {
                    home = new DefaultGoToDimensionTask(Dimension.OVERWORLD);
                }
                setDebugState("Going home empty handed.", "Heading home");
                return home;
            }
        }
    }

    private String entered(Stage next) {
        return switch (next) {
            case PORTAL -> facts.buildBlocks() >= cfg.get().death.netherBlocks ? "got " + facts.buildBlocks() + " blocks"
                    : "going with " + facts.buildBlocks() + " blocks";
            case WALK -> "through the portal";
            case RECOVER -> "at the pile";
            case WEAR -> "putting the armor on";
            default -> "heading home";
        };
    }

    private int trackGain(AltoClef mod) {
        int items = itemTotal(mod);
        int stacks = stackTotal(mod);
        lowItems = Math.min(lowItems, items);
        lowStacks = Math.min(lowStacks, stacks);
        peakItems = Math.max(peakItems, items - lowItems);
        peakStacks = Math.max(peakStacks, stacks - lowStacks);
        return peakItems;
    }

    // "rebuilding" only when there is something to rebuild: a pile that gave the kit back and then ran out of time is not
    private String giveUpLine(String why) {
        return "gave up: " + why + (NetherRegress.kitShort(facts, cfg.get()) ? ", rebuilding" : ", carrying on");
    }

    private String gained() {
        int stacks = peakStacks;
        int items = peakItems;
        // a pile that only topped up stacks we already held has no new stack to count
        return stacks > 0 ? stacks + (stacks == 1 ? " stack" : " stacks") : items + (items == 1 ? " item" : " items");
    }

    // trip over, any way: the next tick belongs to the phase machine
    private void end(AltoClef mod, String why) {
        if (why != null) {
            say.accept(giveUpLine(why));
        }
        state.netherTrip = null;
        walk.stopTracking(mod);
        save.run();
    }

    private static int itemTotal(AltoClef mod) {
        int total = 0;
        Inventory inv = mod.getPlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            total += inv.getItem(i).getCount();
        }
        return total;
    }

    private static int stackTotal(AltoClef mod) {
        int total = 0;
        Inventory inv = mod.getPlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                total++;
            }
        }
        return total;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // the walk tracks portals while it runs, an interrupt must not leave that scan on for the rest of the run
        walk.stopTracking(mod);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return state.netherTrip == null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof NetherRecoverTask;
    }

    @Override
    protected String toDebugString() {
        RunState.NetherTrip trip = state.netherTrip;
        return "Nether death trip" + (trip == null ? "" : " (" + trip.stage + ")");
    }

    @Override
    protected String toHudString() {
        RunState.NetherTrip trip = state.netherTrip;
        return trip == null ? "Getting our stuff back" : "Getting our stuff back near " + HudText.pos(new BlockPos(trip.pile.x, trip.pile.y, trip.pile.z));
    }
}
