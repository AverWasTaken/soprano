package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.phases.NetherRegress;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Stage;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Consumer;

// we died in the nether and woke up at home with an empty bag. the pile is still down there, so: dirt by hand (nothing else
// comes without a pickaxe), the home portal, across the nether, pick it up. the stage lives in RunState.netherTrip so a relog
// carries on and the rules for what comes next are NetherTripRules. when it ends, one way or another, state.netherTrip is
// null and GamerTask hands the wheel back to the phase machine (which rebuilds the kit if the trip did not bring it back)
public class NetherRecoverTask extends Task {
    // at the pile, the pile task gets this long on its own (the walk there is the trip's budget, not this one's)
    private static final double PILE_SECONDS = 90;

    private final RunState state;
    private final GamerFacts facts;
    private final GamerConfig cfg;
    private final Consumer<String> say;
    private final Runnable save;
    private final HomePortalWalk walk = new HomePortalWalk();
    private Task blocks;
    private Task toPile;
    private RecoverItemsTask pile;
    private Task home;
    private int itemsBefore;
    private int stacksBefore;

    public NetherRecoverTask(RunState state, GamerFacts facts, GamerConfig cfg, Consumer<String> say, Runnable save) {
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
            say.accept("recovered " + gained(mod));
            end(mod, null);
            return null;
        }
        if (step.giveUp() != null) {
            say.accept("gave up: " + step.giveUp() + ", rebuilding");
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
        GamerConfig.Death d = cfg.death;
        BlockPos at = new BlockPos(trip.pile.x, trip.pile.y, trip.pile.z);
        double dist = Math.sqrt(mod.getPlayer().blockPosition().distSqr(at));
        boolean homeGone = walk.gone() || state.overworldPortal == null;
        boolean finished = pile != null && pile.isFinished(mod);
        int gained = pile == null ? 0 : itemTotal(mod) - itemsBefore;
        NetherTripRules.Limits limits = NetherTripRules.Limits.of(d.netherTripSeconds, d.netherBlocks, d.netherBlocksSeconds);
        return new NetherTripRules.Inputs(stage, facts.dimension(), now - trip.startTick, now - trip.stageTick,
                facts.buildBlocks(), homeGone, dist, finished, gained, NetherRegress.kitShort(facts, cfg), limits);
    }

    private Task childFor(AltoClef mod, Stage stage) {
        RunState.NetherTrip trip = state.netherTrip;
        BlockPos at = new BlockPos(trip.pile.x, trip.pile.y, trip.pile.z);
        switch (stage) {
            case BLOCKS -> {
                if (blocks == null) {
                    // dirt is the one throwaway block that comes out of the ground without a tool. the count is the gap, the
                    // blocks we already hold (a stray cobble) are part of the total
                    int gap = Math.max(1, cfg.death.netherBlocks - facts.buildBlocks());
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
                    itemsBefore = itemTotal(mod);
                    stacksBefore = stackTotal(mod);
                }
                setDebugState("Picking up our stuff.", "Getting our stuff back");
                return pile;
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
            case PORTAL -> facts.buildBlocks() >= cfg.death.netherBlocks ? "got " + facts.buildBlocks() + " blocks"
                    : "going with " + facts.buildBlocks() + " blocks";
            case WALK -> "through the portal";
            case RECOVER -> "at the pile";
            default -> "heading home";
        };
    }

    private String gained(AltoClef mod) {
        int stacks = stackTotal(mod) - stacksBefore;
        int items = itemTotal(mod) - itemsBefore;
        // a pile that only topped up stacks we already held has no new stack to count
        return stacks > 0 ? stacks + (stacks == 1 ? " stack" : " stacks") : items + (items == 1 ? " item" : " items");
    }

    // trip over, any way: the next tick belongs to the phase machine
    private void end(AltoClef mod, String why) {
        if (why != null) {
            say.accept("gave up: " + why + ", rebuilding");
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
